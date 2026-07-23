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
uniform float m_UnderStrength;     // underside (underwater looking up) transmitted-light brightness
uniform float m_UnderGlintPower;   // underside sun sparkle tightness (LOW = wide glow, HIGH = pinpoint)
uniform float m_UnderScatter;      // underside wave-tilt exaggeration (HIGH = more shattered/spread sparkle)
uniform float m_FresnelPower;      // Fresnel falloff exponent (~5 for water)
uniform float m_ReflectionStrength;// how much the sky reflection tints the water (0 = pure water
                                   // colour matching the FarTerrain, 1 = full mirror)
uniform float m_OpaqueDistance;    // horizontal distance over which the surface fades to fully opaque
uniform float m_InnerRadius;       // FAR water plane only : discard within this square (Chebyshev) horizontal
                                   // radius of the camera, where the near voxel water is the truth. 0 (near
                                   // voxel water) never discards.
uniform vec4 m_FogColor;           // horizon haze colour the FAR water fades to with distance (matches the
uniform float m_FogDistance;       // far terrain relief). Near voxel water sets FogDensity 0 -> no fog.
uniform float m_FogDensity;

#ifdef SHORE_CLIP
    // Analytic shoreline (FAR water plane only). See the SHORE_CLIP block in main().
    uniform sampler2D m_HeightMap;
    uniform float m_HeightMapSize;
    uniform float m_HeightMapRange;
    uniform vec2 m_HeightMapOrigin;
    uniform float m_Extent;
    uniform float m_ShoreMargin;
#endif

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
#ifdef FAR_WATER
    // FAR water plane : discard within the loaded-chunk region (square/Chebyshev horizontal radius),
    // where the near voxel water is the truth.
    if (max(abs(vWorldPos.x - g_CameraPosition.x), abs(vWorldPos.z - g_CameraPosition.z)) < m_InnerRadius) {
        discard;
    }
#endif

#ifdef SHORE_CLIP
    // ANALYTIC SHORELINE. Draw the water only where the relief is genuinely BELOW it, read from the far
    // terrain's own height field rather than inferred from a depth comparison.
    //
    // The relief and this flat plane meet at a very grazing angle, so the coastline used to be the locus
    // where their two depths tie -- and a tie is decided by whatever per-pixel depth error the driver
    // happens to produce. That error moves with the camera, which is why the shoreline wobbled while
    // moving and sat perfectly still at rest, on Android but never on the desktop. Sampling the field
    // instead makes the edge a contour of the same numbers the relief mesh is built from : no tie, no
    // driver dependence, stable by construction.
    //
    // The field is exactly one texel per heightmap sample, hence the texel-centre correction : shoreT is
    // 0..1 across the extent and lands on i/(n-1) at sample i, while texel i is centred at (i+0.5)/n.
    // Skipping that correction offsets the whole coastline by half a cell, i.e. 8 world units.
    vec2 shoreT = (vWorldPos.xz - m_HeightMapOrigin) / m_Extent + 0.5;
    vec2 shoreUv = (shoreT * (m_HeightMapSize - 1.0) + 0.5) / m_HeightMapSize;
    float reliefAboveWater = (texture2D(m_HeightMap, shoreUv).r - 0.5) * m_HeightMapRange;
    // Requiring a real margin (not just "below") is what keeps the surviving water clear of the relief by
    // a resolvable amount, so the remaining depth test can never be close enough to tie. Erring this way
    // is deliberate : a hair too much clipped shows a sliver of (already water-tinted) sea floor at the
    // shore, whereas too little would drape blue over the land edge, which reads far worse.
    if (reliefAboveWater > -m_ShoreMargin) {
        discard;
    }
#endif

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

    // 1 when the camera is ABOVE this water fragment (outside view), 0 when BELOW it (underwater / inside
    // view). The sky reflection + sun/moon glint are gated to the outside only : underwater the surface
    // keeps its plain water body colour (no reflection). The opacity gradient below applies to BOTH sides.
    float aboveWater = step(vWorldPos.y, g_CameraPosition.y);

    // --- Fresnel (Schlick), water F0 ~ 0.02 --- abs() so the angle law is symmetric across the surface :
    // from BELOW (underwater) V points down relative to the up-normal, so a plain max(dot,0) would be 0 ->
    // fresnel 1 -> the surface fully OPAQUE looking up. With abs(), looking straight up from underwater is
    // "steep" (transparent, a Snell's-window peek at the sky) and grazing is opaque, mirroring the top side.
    float ndv = abs(dot(N, V));
    float fresnel = 0.02 + 0.98 * pow(1.0 - ndv, m_FresnelPower);

    vec3 R = reflect(-V, N);

    // --- sky reflection : kept subtle so the base water colour (which matches the FarTerrain water)
    // stays dominant. The sky here is near-uniform, so a strong reflection would only shift the hue
    // away from the FarTerrain and create a visible near/far seam. ReflectionStrength dials it. ---
    vec3 skyRefl = mix(m_SkyHorizonColor.rgb, m_SkyColor.rgb, clamp(R.y, 0.0, 1.0));
    // Reflection only on the outside (above-water) view ; underwater the surface stays plain water body.
    float refl = fresnel * m_ReflectionStrength * aboveWater;

    // --- sun glint : specular along the reflected sun direction, fading as the sun sets. This is the
    // main "alive" cue and is kept at full strength regardless of the sky reflection. ---
    float sunUp = clamp(m_SunDirection.y * 4.0, 0.0, 1.0);
    float glint = pow(max(dot(R, m_SunDirection), 0.0), m_GlintPower) * m_GlintStrength * sunUp;

    // --- moon glint : a fainter highlight along the moon's reflection. The moon is anti-solar, so
    // moonUp is ~0 by day (moon below the horizon) and rises at night -> the moon path only shows then.
    float moonUp = clamp(m_MoonDirection.y * 4.0, 0.0, 1.0);
    float moonGlint = pow(max(dot(R, m_MoonDirection), 0.0), m_GlintPower) * m_MoonGlintStrength * moonUp;

    vec3 color = mix(vBodyColor, skyRefl, refl)
               + (glint * m_SunColor.rgb + moonGlint * m_MoonColor.rgb) * aboveWater;

    // --- underside (underwater, looking up) : the surface is the brightest thing in view from below --
    // it transmits the sky light plus a broad, ripple-broken shimmer of the sun (and the moon at night).
    // This is the below-water counterpart of the above-water reflection/glint (both gated by aboveWater),
    // turning the flat body-colour ceiling into a bright, dappled sheet. Additive ; the alpha law is left
    // untouched, so straight up (steep) stays a transparent Snell's window onto the bright sky and grazing
    // stays the opaque, shimmering sheet. Modulated by the wave normal N so the ripples read as bright
    // caustic-like bands, and by the live SunColor/SkyColor so it dims and warms with the day/night cycle.
    float belowWater = 1.0 - aboveWater;
    if (belowWater > 0.0) {
        // Sun/moon "glint" seen from BELOW : the underside equivalent of the outer-surface sun glint.
        // Trace the view ray UP through the surface (water -> air) : refract() bends it by the wave-tilted
        // normal, so each wavelet sends the ray a slightly different way -- where it points at the sun the
        // fragment sparkles (exactly the "reflet du soleil" of the outer surface, transmitted).
        //
        // Two knobs make it WIDE and SHATTERED rather than one tight blob :
        //   - m_UnderScatter EXAGGERATES the wave tilt used here, so the ripples throw the refracted ray
        //     much further off-axis : the sun's image breaks up and spreads across the ceiling, and the
        //     steepest facets tip past the Snell window into total internal reflection (dark gaps between
        //     the glints -> the "éclaté" look) ;
        //   - m_UnderGlintPower is a BROAD specular exponent (<< the tight outer m_GlintPower), so each
        //     glint is a soft glow, not a pinpoint.
        vec3 Ns = normalize(vec3(-grad.x * m_UnderScatter, 1.0, -grad.y * m_UnderScatter));
        vec3 refr = refract(-V, -Ns, 1.33);      // -V = ray going up ; -Ns = normal facing the water side
        float r2  = dot(refr, refr);
        float inWindow = step(1e-4, r2);         // 0 on total internal reflection (refract returned 0)
        vec3  refrN = refr * inversesqrt(max(r2, 1e-8));
        float sSun  = pow(max(dot(refrN, m_SunDirection),  0.0), m_UnderGlintPower) * m_GlintStrength     * sunUp;
        float sMoon = pow(max(dot(refrN, m_MoonDirection), 0.0), m_UnderGlintPower) * m_MoonGlintStrength * moonUp;
        // Subtle transmitted skylight near the vertical (Snell window), so the ceiling is not pitch body-
        // colour between sparkles.
        float window = ndv * ndv * 0.4;
        vec3 underLight = m_SkyColor.rgb * window
                        + (sSun * m_SunColor.rgb + sMoon * m_MoonColor.rgb) * inWindow;
        color = vBodyColor + underLight * m_UnderStrength;
    }

    // Distance fog : fade to the horizon haze (m_FogColor) with distance, EXACTLY like the far terrain
    // relief -- so distant water and distant land recede into the same haze and the water never reads as a
    // bright opaque sheet "cutting" the far relief. Starts at m_InnerRadius. The NEAR voxel water sets
    // m_FogDensity = 0 (it is always too close to fog anyway), so this is a no-op there ; only the FAR
    // water plane fogs. exp(-f*f), same model as FarTerrain.frag.
    float fdist = distance(g_CameraPosition, vWorldPos);
    float ff = (max(fdist - m_InnerRadius, 0.0) / m_FogDistance) * m_FogDensity;
    float fog = clamp(exp(-ff * ff), 0.0, 1.0);
    color = mix(m_FogColor.rgb, color, fog);

    // Opacity from the ray's PATH LENGTH through the water -- the same law near & far, above & below the
    // surface (one continuous body). Two contributions, whichever is larger :
    //   - ANGLE (Fresnel) : steep (looking straight in) = short path = transparent (m_Diffuse.a, the depths
    //     show through) ; grazing = long path = opaque and mirrors the sky ;
    //   - horizontal DISTANCE : nearby = transparent ; PROGRESSIVELY opaque with distance (m_OpaqueDistance)
    //     so the surface never reads clear all the way to the horizon (very visible underwater, looking up).
    // Horizontal distance (not 3D) so looking straight DOWN from high up stays transparent (the depths are
    // right below), only horizontal range closes it.
    float distOpacity = smoothstep(0.0, m_OpaqueDistance, distance(g_CameraPosition.xz, vWorldPos.xz));
    float alpha = mix(vAlpha, 1.0, max(fresnel, distOpacity));

#ifdef MANUAL_SRGB
    // Emulate the sRGB framebuffer encode where the hardware one is missing (Android GLES).
    color = pow(color, vec3(1.0 / 2.2));
#endif

    gl_FragColor = vec4(color, alpha);
}
