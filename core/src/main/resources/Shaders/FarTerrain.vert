#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform mat4 g_WorldViewProjectionMatrix;
uniform mat4 g_WorldMatrix;
uniform vec3 g_CameraPosition;
uniform float m_DepthBias;

attribute vec3 inPosition;
attribute vec3 inNormal;

varying vec3 vNormal;
varying float vDist;
varying float vHorizDist;
varying float vHeight;

void main() {
    vec4 worldPos = g_WorldMatrix * vec4(inPosition, 1.0);
    // Uniform world scale : direction is preserved (renormalized in the fragment shader).
    vNormal = (g_WorldMatrix * vec4(inNormal, 0.0)).xyz;
    vDist = distance(worldPos.xyz, g_CameraPosition);
    // Horizontal distance from the camera : used to discard the far terrain inside the loaded-chunk
    // region (where the voxels are the truth), so it can't show up in caves / under overhangs there.
    vHorizDist = distance(worldPos.xz, g_CameraPosition.xz);
    // World height drives the altitude palette (water / sand / grass) in the fragment shader.
    vHeight = worldPos.y;
    gl_Position = g_WorldViewProjectionMatrix * vec4(inPosition, 1.0);
    // Depth bias : push the far terrain slightly further away in clip-space depth so the voxel
    // chunks always win the depth test where they overlap it -- prevents the smooth far terrain
    // from poking through the blocky voxel surface, while it still shows on the open horizon.
    gl_Position.z += m_DepthBias;
}
