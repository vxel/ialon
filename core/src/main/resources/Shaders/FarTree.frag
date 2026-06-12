#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform sampler2D m_TreeAtlas;
uniform vec4 m_AmbientColor; // scene ambient light colour (day/night), as in FarTerrain.frag
uniform vec4 m_SunColor;     // scene directional light colour (day/night)
uniform vec4 m_FogColor;
uniform float m_FogDistance;
uniform float m_FogDensity;
uniform float m_InnerRadius;
uniform float m_AlphaDiscard;

varying vec2 vUv;
varying float vDist;
varying float vHorizDist;

void main() {
    // Inside the loaded-chunk square the voxel chunks hold the real trees : hard-clip the billboards
    // there (camera-relative, like the far terrain) so they never overlap the real voxel trees.
    if (vHorizDist < m_InnerRadius) {
        discard;
    }

    vec4 tex = texture2D(m_TreeAtlas, vUv);
    if (tex.a < m_AlphaDiscard) {
        discard; // transparent silhouette background (alpha test : opaque, no blending, no depth sorting)
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

    // Exponential-squared distance fog (RGB only, alpha stays opaque), identical to FarTerrain.frag, so
    // trees take on the same horizon haze as the far terrain behind them. This is the shared fog, not a
    // dissolve : the billboards never fade in/out, they simply share the terrain's horizon colour.
    float f = (max(vDist - m_InnerRadius, 0.0) / m_FogDistance) * m_FogDensity;
    float fog = clamp(exp(-f * f), 0.0, 1.0);
    vec3 outColor = mix(m_FogColor.rgb, lit, fog);

#ifdef MANUAL_SRGB
    outColor = pow(outColor, vec3(1.0 / 2.2));
#endif
    gl_FragColor = vec4(outColor, 1.0);
}
