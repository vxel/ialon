#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform mat4 g_WorldMatrix;
uniform mat4 g_ViewProjectionMatrix;
uniform vec3 g_CameraPosition;

attribute vec3 inPosition;

// View direction in world space (camera -> fragment). Normalized in the fragment shader.
// The sky dome follows the camera in translation (SpatialFollowCamControl), so this direction is
// independent of the player position and the gradient stays anchored to the world vertical.
varying vec3 vDir;

void main() {
    vec4 worldPos = g_WorldMatrix * vec4(inPosition, 1.0);
    vDir = worldPos.xyz - g_CameraPosition;
    gl_Position = g_ViewProjectionMatrix * worldPos;
}
