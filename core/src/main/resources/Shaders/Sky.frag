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

#ifdef MANUAL_SRGB
    // No hardware sRGB framebuffer (Android GLES) : colours are linear, encode here.
    col = pow(col, vec3(1.0 / 2.2));
#endif
    gl_FragColor = vec4(col, 1.0);
}
