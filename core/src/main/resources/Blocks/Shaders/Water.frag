#import "Common/ShaderLib/GLSLCompat.glsllib"

// Calm-water surface fragment shader (prototype).
// A cheap, mobile-friendly water look with NO extra render pass and NO cubemap :
//   - analytic sky reflection : the reflected ray samples the SAME procedural sky gradient the
//     sky dome uses (zenith colour -> horizon haze), passed in as uniforms by WaterState ;
//   - Fresnel (Schlick) : blends the water body colour with the sky reflection by view angle ;
//   - sun glint : a specular highlight along the reflected sun direction ;
//   - waves : the flat +Y normal is perturbed by the analytic slope of a fractal sum of directional
//     ripples over world XZ (no vertex displacement -> works on the big greedy-merged quads). The
//     octaves use golden-angle-spread directions and geometric frequencies so no two ripples are
//     parallel or harmonic -> no visible repeating banding. The perturbation fades out with distance
//     so far water flattens and joins the FarTerrain seamlessly.

uniform vec3 g_CameraPosition;
uniform float g_Time;

uniform vec3 m_SunDirection;       // world-space, points TOWARDS the sun (normalised)
uniform vec4 m_SunColor;
uniform vec3 m_MoonDirection;      // world-space, points TOWARDS the moon (normalised)
uniform vec4 m_MoonColor;
uniform float m_MoonGlintStrength; // moon highlight intensity (fainter than the sun)
uniform vec4 m_SkyColor;           // overhead sky colour (reflection at steep angles)
uniform vec4 m_SkyHorizonColor;    // horizon haze colour (reflection at grazing angles)
uniform float m_WaveScale;         // spatial frequency of the ripples
uniform float m_WaveSpeed;         // animation speed
uniform float m_WaveAmplitude;     // normal tilt strength
uniform float m_WaveFadeNear;      // distance (world units) at which waves start fading out
uniform float m_WaveFadeFar;       // distance at which waves are fully flattened
uniform float m_GlintPower;        // sun highlight tightness (higher = smaller/sharper)
uniform float m_GlintStrength;     // sun highlight intensity
uniform float m_FresnelPower;      // Fresnel falloff exponent (~5 for water)
uniform float m_ReflectionStrength;// how much the sky reflection tints the water (0 = pure water
                                   // colour matching the FarTerrain, 1 = full mirror)

in vec3 vWorldPos;
in vec3 vBodyColor;
in float vAlpha;

// Per-octave domain rotations (unit vectors at the golden angle, ~137.5°) : decorrelate the octaves
// so the FBM has no preferred direction. Per-octave scroll speeds animate the surface.
const int OCTAVES = 4;
const vec2 WAVE_DIR[4] = vec2[4](
    vec2( 0.921,  0.389), vec2(-0.942,  0.335), vec2( 0.469, -0.884), vec2( 0.253,  0.967)
);
const float WAVE_SPEED[4] = float[4](0.85, 1.10, 0.95, 1.35);

// Cheap 2D hash -> [0,1).
float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// Value noise with analytic derivatives (Inigo Quilez) : returns (value, d/dx, d/dy). Using the
// derivative directly gives the surface slope -> no finite differencing, exact normals.
vec3 noised(vec2 x) {
    vec2 i = floor(x);
    vec2 f = fract(x);
    vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0); // quintic fade (C2 -> smooth normals)
    vec2 du = 30.0 * f * f * (f * (f - 2.0) + 1.0);
    float a = hash12(i + vec2(0.0, 0.0));
    float b = hash12(i + vec2(1.0, 0.0));
    float c = hash12(i + vec2(0.0, 1.0));
    float d = hash12(i + vec2(1.0, 1.0));
    float k1 = b - a;
    float k2 = c - a;
    float k3 = a - b - c + d;
    float value = a + k1 * u.x + k2 * u.y + k3 * u.x * u.y;
    vec2 deriv = du * vec2(k1 + k3 * u.y, k2 + k3 * u.x);
    return vec3(value, deriv);
}

void main() {
    // --- animated normal (waves) : slope of a value-noise FBM over world XZ. Noise (not a sum of
    // sines) avoids the regular interference lattice / stripes the sharp sun glint would reveal. ---
    vec2 p = vWorldPos.xz * m_WaveScale;
    // Pixel footprint in wave space (screen-space derivative) : drives the analytic anti-aliasing.
    vec2 fp = fwidth(p);
    float footprint = max(fp.x, fp.y);
    float t = g_Time * m_WaveSpeed;
    vec2 grad = vec2(0.0);
    float ampSum = 0.0;
    float amp = 1.0;
    float freq = 1.0;
    for (int i = 0; i < OCTAVES; i++) {
        vec2 dir = WAVE_DIR[i];
        // Rotate the domain by this octave's angle (and scroll it) so octaves don't share a direction.
        vec2 rp = vec2(dir.x * p.x - dir.y * p.y, dir.y * p.x + dir.x * p.y);
        vec3 n = noised(rp * freq + vec2(t * WAVE_SPEED[i], 0.0));
        // Rotate the noise gradient back to world space (inverse rotation), scaled by the frequency.
        vec2 g = n.yz * freq;
        vec2 gw = vec2(dir.x * g.x + dir.y * g.y, -dir.y * g.x + dir.x * g.y);
        // Analytic anti-aliasing (the procedural equivalent of normal-map mipmapping) : fade an octave
        // out as its noise cells shrink towards the pixel footprint (Nyquist), so distant water neither
        // shimmers nor aliases the sharp sun glint, and flattens smoothly into the horizon.
        float aa = 1.0 - smoothstep(0.5, 1.0, freq * footprint);
        grad += amp * gw * aa;
        ampSum += amp * freq;
        amp *= 0.55;
        freq *= 1.9;
    }
    grad /= ampSum; // normalise so the tilt is stable regardless of the octave set

    // Explicit far fade on top of the AA, so the surface fully flattens for the FarTerrain join.
    float dist = distance(g_CameraPosition, vWorldPos);
    float waveFade = 1.0 - smoothstep(m_WaveFadeNear, m_WaveFadeFar, dist);
    grad *= m_WaveAmplitude * waveFade;

    vec3 N = normalize(vec3(-grad.x, 1.0, -grad.y));

    vec3 V = normalize(g_CameraPosition - vWorldPos);

    // --- Fresnel (Schlick), water F0 ~ 0.02 ---
    float ndv = max(dot(N, V), 0.0);
    float fresnel = 0.02 + 0.98 * pow(1.0 - ndv, m_FresnelPower);

    vec3 R = reflect(-V, N);

    // --- sky reflection : kept subtle so the base water colour (which matches the FarTerrain water)
    // stays dominant. The sky here is near-uniform, so a strong reflection would only shift the hue
    // away from the FarTerrain and create a visible near/far seam. ReflectionStrength dials it. ---
    vec3 skyRefl = mix(m_SkyHorizonColor.rgb, m_SkyColor.rgb, clamp(R.y, 0.0, 1.0));
    float refl = fresnel * m_ReflectionStrength;

    // --- sun glint : specular along the reflected sun direction, fading as the sun sets. This is the
    // main "alive" cue and is kept at full strength regardless of the sky reflection. ---
    float sunUp = clamp(m_SunDirection.y * 4.0, 0.0, 1.0);
    float glint = pow(max(dot(R, m_SunDirection), 0.0), m_GlintPower) * m_GlintStrength * sunUp;

    // --- moon glint : a fainter highlight along the moon's reflection. The moon is anti-solar, so
    // moonUp is ~0 by day (moon below the horizon) and rises at night -> the moon path only shows then.
    float moonUp = clamp(m_MoonDirection.y * 4.0, 0.0, 1.0);
    float moonGlint = pow(max(dot(R, m_MoonDirection), 0.0), m_GlintPower) * m_MoonGlintStrength * moonUp;

    vec3 color = mix(vBodyColor, skyRefl, refl) + glint * m_SunColor.rgb + moonGlint * m_MoonColor.rgb;
    // Bright sky leaks THROUGH the transparent water where nothing backs it (the far-terrain seam, where
    // the voxel seabed runs out). That only happens FAR away and at GRAZING angles ; near the player the
    // seabed backs the water, so we keep it see-through there (you can watch the bottom). So, seen from
    // ABOVE, make the water opaque when it is grazing (Fresnel) OR distant (smoothstep) -> it then shows
    // the sky REFLECTION, not the sky through a gap. Seen from BELOW (underwater) keep the configured
    // alpha so you can still look out of the water.
    // Use the HORIZONTAL distance (not the 3D one) : the missing backdrop is at the far chunk edge, a
    // horizontal boundary. The 3D distance also grows with camera altitude, which would wrongly turn the
    // water opaque when looking straight down from high up (where the seabed IS there, right below).
    float aboveWater = step(vWorldPos.y, g_CameraPosition.y);
    float horizDist = distance(g_CameraPosition.xz, vWorldPos.xz);
    float farOpaque = smoothstep(50.0, 80.0, horizDist);
    // Angle-based opacity : opaque unless looking fairly steeply DOWN (where the ray hits the seabed
    // right below and we want to see it). ndv = dot(up, viewDir) -> ~1 looking straight down, ~0 grazing.
    // A sharp ramp (sharper than the Fresnel) closes the medium/grazing angles where the ray shoots far
    // out to the no-backdrop horizon and the sky would otherwise leak through.
    float angleOpaque = 1.0 - smoothstep(0.05, 0.4, ndv);
    float opaqueness = max(angleOpaque, farOpaque) * aboveWater;
    float alpha = mix(vAlpha, 1.0, opaqueness);

#ifdef MANUAL_SRGB
    // Emulate the sRGB framebuffer encode where the hardware one is missing (Android GLES).
    color = pow(color, vec3(1.0 / 2.2));
#endif

    gl_FragColor = vec4(color, alpha);
}
