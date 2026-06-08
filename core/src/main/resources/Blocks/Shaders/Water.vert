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

attribute vec3 inPosition;

#ifdef VERTEX_COLOR
    attribute vec4 inColor;
    // Same perceptual light curve as Blocks/Shaders/Ialon.vert (16 discrete sun/torch levels).
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
    vWorldPos = (g_WorldMatrix * modelSpacePos).xyz;

    // Water body colour = tint * current light level (so it follows the day/night cycle like the voxels).
    vec3 tint = vec3(0.19, 0.52, 0.70);
    float lightLevel = 0.7;
    #ifdef VERTEX_COLOR
        int sunIntensity = (int(inColor.a) >> 4) & 0xF;
        int torchIntensity = (int(inColor.a)) & 0xF;
        float lum = g_AmbientLightColor.r * 0.3 + g_AmbientLightColor.g * 0.59 + g_AmbientLightColor.b * 0.11;
        float sunlight = levels[sunIntensity] * lum;
        float torchlight = levels[torchIntensity];
        lightLevel = min(max(sunlight, torchlight), 0.7);
        tint = inColor.rgb;
    #endif

    vBodyColor = lightLevel * tint;
    vAlpha = m_Diffuse.a;
}
