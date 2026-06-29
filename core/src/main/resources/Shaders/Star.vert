#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform mat4 g_WorldMatrix;
uniform mat4 g_ViewProjectionMatrix;
uniform vec3 g_CameraPosition;

attribute vec3 inPosition;

// View direction in world space (camera -> fragment). Normalized in the fragment shader.
// The star dome follows the camera in translation (SpatialFollowCamControl) without rotating, so this
// direction is anchored to the world : the star field stays fixed in the sky as the player moves.
varying vec3 vDir;

void main() {
    vec4 worldPos = g_WorldMatrix * vec4(inPosition, 1.0);
    vDir = worldPos.xyz - g_CameraPosition;
    gl_Position = g_ViewProjectionMatrix * worldPos;
}
