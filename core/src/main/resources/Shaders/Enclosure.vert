#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform mat4 g_WorldViewProjectionMatrix;
uniform float m_DepthBias;

attribute vec3 inPosition;

void main() {
    gl_Position = g_WorldViewProjectionMatrix * vec4(inPosition, 1.0);
    // Same clip-space depth push-back as FarTerrain.vert : the enclosure is the backstop, so it must
    // lose the depth test against the voxels AND the (already depth-biased) far terrain. Without this an
    // unbiased box wins against the depth-biased far sea and occludes it when viewed from above.
    gl_Position.z += m_DepthBias;
}
