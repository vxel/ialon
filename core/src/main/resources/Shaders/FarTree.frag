#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform sampler2D m_TreeAtlas;
uniform vec4 m_AmbientColor; // scene ambient light colour (day/night), as in FarTerrain.frag
uniform vec4 m_SunColor;     // scene directional light colour (day/night)
uniform vec4 m_FogColor;
uniform float m_FogDistance;
uniform float m_FogDensity;
uniform float m_InnerRadius;
uniform float m_OuterRadius; // far edge of the billboard ring (fade out before it, no hard cut-off)
uniform float m_FadeRange;
uniform float m_AlphaDiscard;

varying vec2 vUv;
varying float vDist;
varying float vHorizDist;

void main() {
    vec4 tex = texture2D(m_TreeAtlas, vUv);
    if (tex.a < m_AlphaDiscard) {
        discard; // transparent silhouette background
    }

#ifdef MANUAL_SRGB
    // No hardware sRGB sampling (Android GLES) : decode the atlas texel to linear, like the block shader.
    tex.rgb = pow(tex.rgb, vec3(2.2));
#endif

    // Lighting : ambient luminance (scalar dim, no colour wash) + a flat directional fill, matching the
    // far terrain's land lighting so the trees dim and tint through the day/night cycle like the relief.
    // Billboards carry no real normal, hence a constant directional factor rather than dot(N, L).
    float ambientLum = dot(m_AmbientColor.rgb, vec3(0.3, 0.59, 0.11));
    vec3 lit = tex.rgb * (vec3(ambientLum) + m_SunColor.rgb * 0.4);

    // Exponential-squared distance fog, identical to FarTerrain.frag : the tree colour fades to the
    // horizon haze, so trees melt into the same horizon as the far terrain.
    float f = (max(vDist - m_InnerRadius, 0.0) / m_FogDistance) * m_FogDensity;
    float fog = clamp(exp(-f * f), 0.0, 1.0);
    vec3 outColor = mix(m_FogColor.rgb, lit, fog);

    // Smooth alpha fade (no dithering) : thin IN just beyond the inner radius (where the voxel chunks hold
    // the real trees -> no hard pop / double trees at the seam) and OUT before the far edge of the ring
    // (no hard cut-off). The alpha is blended, so the silhouette keeps its own soft edges too.
    float seamIn = clamp((vHorizDist - m_InnerRadius) / m_FadeRange, 0.0, 1.0);
    float seamOut = clamp((m_OuterRadius - vHorizDist) / m_FadeRange, 0.0, 1.0);
    float alpha = tex.a * seamIn * seamOut;

#ifdef MANUAL_SRGB
    outColor = pow(outColor, vec3(1.0 / 2.2));
#endif
    gl_FragColor = vec4(outColor, alpha);
}
