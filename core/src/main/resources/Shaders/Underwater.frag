#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/MultiSample.glsllib"

// Underwater post-process : applied full-screen only while the camera is below the water surface
// (the FilterPostProcessor is attached/detached by UnderwaterState). Two combined effects :
//   1) a gentle UV wobble that makes the whole view ripple ("eau qui bouge" / refraction) ;
//   2) a bluish exponential-squared distance fog that shortens the underwater view range,
//      reusing the same fog model as the near/far terrain (see FarTerrain.frag).
// Both are scaled by m_Intensity (0->1) so the effect fades in/out smoothly around the surface
// rather than popping on at the exact water line.

uniform COLORTEXTURE m_Texture;       // the rendered scene (set by the FilterPostProcessor)
uniform DEPTHTEXTURE m_DepthTexture;  // scene depth, for the distance fog
varying vec2 texCoord;

uniform vec4 m_FogColor;
uniform float m_FogDensity;
uniform float m_FogDistance;
// Reconstruct the fragment's world position from the depth buffer (scene camera matrices, fed by
// UnderwaterFilter) so the fog can use HORIZONTAL distance only.
uniform mat4 m_ViewProjInverse;
uniform vec3 m_CameraPosition;

uniform float m_DistortionAmplitude; // UV offset magnitude (in texCoord units, ~0.005-0.02)
uniform float m_DistortionSpeed;     // ripple speed
uniform float m_DistortionFrequency; // ripple spatial frequency
uniform float m_Intensity;           // 0 = no effect (surface), 1 = fully submerged
uniform float m_Time;                // seconds, fed by UnderwaterFilter.preFrame

void main() {
    // --- 1) Refraction : wobble the sample coordinate with two crossed sine waves. The phase mixes
    // both axes so the ripple looks 2D rather than a pure horizontal/vertical slide. Clamped to the
    // valid [0,1] range so the edges never sample outside the scene texture (would smear/wrap).
    // NOT scaled by m_Intensity : the ripple is constant once submerged, it must not grow with depth
    // (m_Intensity only fades the fog in around the surface).
    float amp = m_DistortionAmplitude;
    vec2 uv = texCoord;
    uv.x += amp * sin(m_Time * m_DistortionSpeed + texCoord.y * m_DistortionFrequency);
    uv.y += amp * cos(m_Time * m_DistortionSpeed + texCoord.x * m_DistortionFrequency);
    uv = clamp(uv, 0.0, 1.0);

    vec4 sceneColor = getColor(m_Texture, uv);

    // --- 2) Distance fog, HORIZONTAL only : reconstruct the fragment's world position from the
    // depth buffer, then measure its distance to the camera in the XZ plane (ignoring altitude). So
    // the seabed straight below / the surface straight above stay clear, and only horizontal range
    // fades to the fog colour. Same exp(-f*f) model as the terrain fog (FarTerrain.frag).
    float rawDepth = getDepth(m_DepthTexture, uv).r;
    vec4 ndc = vec4(uv, rawDepth, 1.0) * 2.0 - 1.0;
    vec4 worldPos = m_ViewProjInverse * ndc;
    worldPos /= worldPos.w;
    float dist = length(worldPos.xz - m_CameraPosition.xz);
    float f = (dist / m_FogDistance) * m_FogDensity;
    float fogFactor = clamp(exp(-f * f), 0.0, 1.0);

    vec3 fogColor = m_FogColor.rgb;
#ifdef MANUAL_SRGB
    // No hardware sRGB framebuffer (Android GLES) : the scene texture already holds sRGB-encoded
    // values (our world shaders encode on write), and the fog colour is authored linear, so encode
    // it here to mix in the same space. On desktop the framebuffer is linear and this compiles out.
    fogColor = pow(fogColor, vec3(1.0 / 2.2));
#endif

    // Blend toward fog with distance, then scale the whole shift by the submersion intensity so a
    // camera right at the surface keeps the original image.
    vec3 fogged = mix(fogColor, sceneColor.rgb, fogFactor);
    gl_FragColor = vec4(mix(sceneColor.rgb, fogged, m_Intensity), 1.0);
}
