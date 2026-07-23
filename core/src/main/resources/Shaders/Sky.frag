#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform vec4 m_HorizonColor;
uniform vec4 m_SkyColor;
uniform vec4 m_ZenithColor;
uniform vec4 m_Color;       // day/night multiplier (white noon -> warm dusk -> dark night)
uniform vec4 m_GlowColor;
uniform vec3 m_SunDirection;
uniform float m_GlowStrength;
uniform float m_GlowSharpness;
uniform float m_ZenithExponent;
// 1 while the eye is in the depths -- under its column's terrain surface AND below the water level.
// See SkyState.isDeepUnderground() ; 0 otherwise.
uniform float m_UnderSurface;
// dir.y at the cylinder's rim : above it the view direction lands on the TOP cap, below it on a side wall
// or the bottom cap. Derived from the actual cylinder dimensions in SkyState, so it stays correct if they
// change. The camera sits at the cylinder's centre (SpatialFollowCamControl), so this split is exact.
uniform float m_CapElevation;

// Angular softening of that split : the boundary is a large circle on screen, and a hard cut on it aliases
// into a ragged ring. ~1.5 degrees is enough to clean it up without blurring the edge.
const float CAP_FADE = 0.026;

varying vec3 vDir;

void main() {
    vec3 dir = normalize(vDir);
    float e = dir.y;                 // elevation : -1 (nadir) .. 0 (horizon) .. +1 (zenith)
    float up = clamp(e, 0.0, 1.0);

    // Vertical gradient : horizon -> mid sky -> deep zenith, computed per fragment (no banding).
    // The horizon->sky band is kept thin (small upper bound) so the white horizon fades to blue quickly.
    // Below the horizon there is nothing to draw : the enclosure cube hides it, so the gradient simply
    // stays at the horizon colour down there (up is clamped to 0).
    vec3 col = mix(m_HorizonColor.rgb, m_SkyColor.rgb, smoothstep(0.0, 0.35, up));
    col = mix(col, m_ZenithColor.rgb, pow(up, m_ZenithExponent));

    // Day/night modulation (same behaviour as the old vertex-coloured sky).
    col *= m_Color.rgb;

    // Warm glow towards the sun, concentrated near the horizon (sunrise/sunset). m_GlowStrength is
    // driven by SkyControl so it peaks at the horizon and vanishes at noon / deep night.
    float sunDot = max(dot(dir, normalize(m_SunDirection)), 0.0);
    float band = 1.0 - smoothstep(0.0, 0.4, abs(e));
    col += m_GlowColor.rgb * (pow(sunDot, m_GlowSharpness) * band * m_GlowStrength);

    // In the depths : every wall of the cylinder goes BLACK except the top cap, which keeps the sky.
    // The sky covers every direction (closed, camera-following cylinder) and below the horizon it just holds
    // the horizon colour, so from inside the ground it read as bright daylight through the rock. Applied
    // last, after the day/night modulation and the sun glow, so black stays black instead of picking up the
    // glow. Above ground m_UnderSurface is 0 and this is a no-op.
    float wall = 1.0 - smoothstep(m_CapElevation - CAP_FADE, m_CapElevation, e);
    col = mix(col, vec3(0.0), m_UnderSurface * wall);

#ifdef MANUAL_SRGB
    // No hardware sRGB framebuffer (Android GLES) : colours are linear, encode here.
    col = pow(col, vec3(1.0 / 2.2));
#endif
    gl_FragColor = vec4(col, 1.0);
}
