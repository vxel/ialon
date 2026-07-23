#import "Common/ShaderLib/GLSLCompat.glsllib"

// Calm-water surface vertex shader (prototype).
// The calm-water geometry is a set of large, flat, greedy-merged quads whose normal is always +Y,
// so there is no point doing per-vertex wave displacement (4 verts per huge quad -> no detail). The
// waves are done as a normal perturbation in the fragment shader instead. Here we only:
//   - project the vertex and forward its WORLD position (needed for the view ray and wave coords),
//   - reproduce the voxel day/night light-level decode so the water body keeps darkening at night.

uniform mat4 g_WorldViewProjectionMatrix;
uniform mat4 g_WorldMatrix;
uniform vec4 g_AmbientLightColor;

uniform vec4 m_Diffuse;
uniform float m_DepthBias; // FAR water plane : matches the far terrain relief's clip-space depth bias so
                           // the two are compared by TRUE depth (higher land occludes the lower water,
                           // instead of the relief's own bias making the flat water win over it). 0 for
                           // the near voxel water (which uses PolyOffset to win over the voxel side-faces).

attribute vec3 inPosition;

#ifdef VERTEX_COLOR
    attribute vec4 inColor;
    // Same perceptual light curve as Blocks/Shaders/IalonArray.vert (16 discrete sun/torch levels).
    const float lightDecay = 2.2;
    const float levels[16] = float[16](
        0.0,
        pow(1.0 / 15.0, lightDecay),  pow(2.0 / 15.0, lightDecay),  pow(3.0 / 15.0, lightDecay),
        pow(4.0 / 15.0, lightDecay),  pow(5.0 / 15.0, lightDecay),  pow(6.0 / 15.0, lightDecay),
        pow(7.0 / 15.0, lightDecay),  pow(8.0 / 15.0, lightDecay),  pow(9.0 / 15.0, lightDecay),
        pow(10.0 / 15.0, lightDecay), pow(11.0 / 15.0, lightDecay), pow(12.0 / 15.0, lightDecay),
        pow(13.0 / 15.0, lightDecay), pow(14.0 / 15.0, lightDecay), 1.0
    );
#endif

out vec3 vWorldPos;
out vec3 vBodyColor;
out float vAlpha;

void main() {
    vec4 modelSpacePos = vec4(inPosition, 1.0);
    gl_Position = g_WorldViewProjectionMatrix * modelSpacePos;
    gl_Position.z += m_DepthBias; // see m_DepthBias (0 for the near voxel water)
    vWorldPos = (g_WorldMatrix * modelSpacePos).xyz;

    // Water body colour = tint * current light level (so it follows the day/night cycle like the voxels).
    // Two sources, one shader :
    //   - NEAR voxel water (VERTEX_COLOR) : tint + packed sun/torch light level from the vertex colour ;
    //   - FAR water plane (no vertex colour) : the m_Diffuse tint (calmWaterColor) at open-sky light.
    float lum = g_AmbientLightColor.r * 0.3 + g_AmbientLightColor.g * 0.59 + g_AmbientLightColor.b * 0.11;
    vec3 tint;
    float lightLevel;
    #ifdef VERTEX_COLOR
        // inColor is a normalized UnsignedByte buffer : the alpha channel (the packed light level)
        // arrives in [0,1], scale back to [0,255] with rounding before unpacking the nibbles.
        int packedLight = int(inColor.a * 255.0 + 0.5);
        int sunIntensity = (packedLight >> 4) & 0xF;
        int torchIntensity = packedLight & 0xF;
        float sunlight = levels[sunIntensity] * lum;
        float torchlight = levels[torchIntensity];
        lightLevel = min(max(sunlight, torchlight), 0.7);
        tint = inColor.rgb;
    #else
        lightLevel = min(lum, 0.7); // open sky : full sun, same cap as the near water body
        tint = m_Diffuse.rgb;
    #endif

    vBodyColor = lightLevel * tint;
    vAlpha = m_Diffuse.a;
}
