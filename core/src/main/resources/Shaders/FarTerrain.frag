#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform vec4 m_BaseColor;
uniform vec3 m_LightDir;     // world-space direction the sunlight travels along
uniform vec4 m_FogColor;
uniform float m_FogDistance; // distance scale of the fog
uniform float m_FogDensity;  // fog thickness
uniform float m_InnerRadius; // below this horizontal distance the voxels are the truth -> discard

// Altitude palette : roughly matches the blocks the generator places by height
// (water below the water level, a thin sand shore around it, grass/land above).
uniform vec4 m_WaterColor;
uniform vec4 m_SandColor;
uniform float m_WaterHeight;

varying vec3 vNormal;
varying float vDist;
varying float vHorizDist;
varying float vHeight;

void main() {
    // The far terrain is only an horizon ring : inside the loaded-chunk region the voxels are the
    // truth, so discard it there (prevents it showing through caves / overhangs near the player).
    if (vHorizDist < m_InnerRadius) {
        discard;
    }

    vec3 n = normalize(vNormal);
    float diffuse = max(dot(n, -normalize(m_LightDir)), 0.0);

    // Altitude palette : water (below) -> sand (shore) -> grass/land (m_BaseColor, above), blended.
    float landAmount = smoothstep(m_WaterHeight - 1.0, m_WaterHeight + 1.0, vHeight);
    vec3 land = mix(m_SandColor.rgb, m_BaseColor.rgb, smoothstep(m_WaterHeight, m_WaterHeight + 3.0, vHeight));
    vec3 terrainColor = mix(m_WaterColor.rgb, land, landAmount);

    vec3 color = terrainColor * (0.35 + 0.65 * diffuse);

    // Exponential-squared distance fog : 1 near (clear) -> 0 far (full fog colour).
    // Only the far terrain uses this material, so the sky is left untouched.
    float f = (vDist / m_FogDistance) * m_FogDensity;
    float fog = clamp(exp(-f * f), 0.0, 1.0);

    gl_FragColor = vec4(mix(m_FogColor.rgb, color, fog), 1.0);
}
