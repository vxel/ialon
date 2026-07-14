#import "Common/ShaderLib/GLSLCompat.glsllib"

// The 3D hash multiplies/adds mid-range constants ; mediump (the GLES fragment default) collapses it
// to a near-constant and the star field thins out. Force highp on GLES so it matches desktop.
#ifdef GL_ES
precision highp float;
#endif

uniform float m_Intensity;   // 0 (day, hidden) .. 1 (deep night) - driven by StarControl
uniform vec4 m_SkyRotation;  // quaternion (x,y,z,w) wheeling the sky about the celestial pole
uniform float g_Time;        // global time, for a subtle per-star twinkle

varying vec3 vDir;

// Cheap, texture-free 3D hash : maps a cell coordinate to a pseudo-random vec3 in [0, 1).
vec3 hash33(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx);
}

// Rotate vector v by quaternion q (x,y,z,w).
vec3 qrot(vec4 q, vec3 v) {
    return v + 2.0 * cross(q.xyz, cross(q.xyz, v) + q.w * v);
}

void main() {
    vec3 viewDir = normalize(vDir);

    // Stars only in the upper hemisphere : fade them out towards the horizon so they never appear below
    // the player nor poke through the terrain. Uses the TRUE view direction so the horizon stays put.
    float above = smoothstep(0.0, 0.25, viewDir.y);

    // The view ray decides which sky direction each pixel looks at (the dome is a sphere centred on the
    // camera, so rotating the MESH can't move the stars). Instead rotate the sampling direction here :
    // applying the INVERSE sky rotation (quaternion conjugate) makes the star pattern appear to wheel by
    // +SkyRotation about the celestial pole.
    vec3 dir = qrot(vec4(-m_SkyRotation.xyz, m_SkyRotation.w), viewDir);

    // Tile the view sphere into cells ; a sparse subset of cells holds a single star. Using the full
    // 3D direction (not a 2D projection) keeps the density uniform and free of pole distortion.
    vec3 p = dir * 90.0;
    vec3 cell = floor(p);
    vec3 f = fract(p) - 0.5;

    vec3 rnd = hash33(cell + 0.5);
    float present = step(0.94, rnd.x);            // ~6% of cells get a star
    vec3 offset = (hash33(cell) - 0.5) * 0.5;     // jitter, kept small so the disc stays inside its cell
    float d = length(f - offset);

    // A bright core plus a faint halo : the halo widens the on-screen footprint so a star covers a few
    // pixels even on a dense phone panel, instead of a sub-pixel speck that the display swallows.
    float core = smoothstep(0.10, 0.0, d);
    float halo = 0.35 * smoothstep(0.22, 0.0, d);
    float star = present * (core + halo);
    star *= 0.6 + 0.4 * rnd.y;                    // vary apparent magnitude (kept bright : 0.6 .. 1.0)

    // Subtle twinkle, phase-shifted per star so they don't pulse in unison.
    star *= 0.65 + 0.35 * sin(g_Time * 2.5 + rnd.z * 6.2831);

    // Linear brightness. Carried in the RGB (not the alpha) so the sRGB encode below lifts the dark
    // values exactly as a hardware sRGB framebuffer would after additive blending.
    vec3 col = vec3(clamp(star * above * m_Intensity, 0.0, 1.0));

#ifdef MANUAL_SRGB
    // No hardware sRGB framebuffer (Android GLES) : the additive blend happens in gamma space, so encode
    // here to match the desktop path (where the same blend is done linear, then encoded on store). Without
    // this the stars are ~2x darker on Android. See the sRGB-pipeline note.
    col = pow(col, vec3(1.0 / 2.2));
#endif

    // Pure additive (blend One,One) : the star colour is added straight onto the night sky.
    gl_FragColor = vec4(col, 1.0);
}
