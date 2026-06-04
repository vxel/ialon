#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform vec4 m_BaseColor;
uniform vec3 m_LightDir;     // world-space direction the sunlight travels along
uniform vec4 m_FogColor;
uniform float m_FogDistance; // distance scale of the fog
uniform float m_FogDensity;  // fog thickness
uniform float m_InnerRadius; // below this horizontal distance the voxels are the truth -> discard

varying vec3 vNormal;
varying float vDist;
varying float vHorizDist;

void main() {
    // The far terrain is only an horizon ring : inside the loaded-chunk region the voxels are the
    // truth, so discard it there (prevents it showing through caves / overhangs near the player).
    if (vHorizDist < m_InnerRadius) {
        discard;
    }

    vec3 n = normalize(vNormal);
    float diffuse = max(dot(n, -normalize(m_LightDir)), 0.0);
    vec3 color = m_BaseColor.rgb * (0.35 + 0.65 * diffuse);

    // Exponential-squared distance fog : 1 near (clear) -> 0 far (full fog colour).
    // Only the far terrain uses this material, so the sky is left untouched.
    float f = (vDist / m_FogDistance) * m_FogDensity;
    float fog = clamp(exp(-f * f), 0.0, 1.0);

    gl_FragColor = vec4(mix(m_FogColor.rgb, color, fog), 1.0);
}
