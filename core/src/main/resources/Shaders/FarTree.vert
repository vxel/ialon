#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform mat4 g_ViewProjectionMatrix;
uniform mat4 g_ViewMatrix;
uniform mat4 g_WorldMatrix;
uniform vec3 g_CameraPosition;
uniform float m_DepthBias;

// One quad per tree : the 4 vertices share the same anchor (trunk base, local coords -- the geometry's
// localTranslation carries the torus tile offset). The corner & sprite size are packed in inTexCoord2,
// and the atlas UV (species sub-rect) is baked per corner in inTexCoord. The quad is expanded here.
attribute vec3 inPosition;   // trunk base anchor (shared by the 4 corners)
attribute vec2 inTexCoord;   // atlas UV for this corner
attribute vec4 inTexCoord2;  // (cornerX in {-0.5,+0.5}, cornerY in {0,1}, width, height) in world units

varying vec2 vUv;
varying float vDist;
varying float vHorizDist;

void main() {
    vec3 anchor = (g_WorldMatrix * vec4(inPosition, 1.0)).xyz;

    // Cylindrical (Y-up) billboard : expand the quad around the anchor along the camera's right axis
    // projected onto the ground plane, so trees always face the camera horizontally yet stay perfectly
    // upright (no tilt when the player looks up/down). The right axis is the first row of the
    // world->view matrix ; zeroing its Y and renormalizing keeps the billboard vertical.
    vec3 right = normalize(vec3(g_ViewMatrix[0][0], g_ViewMatrix[1][0], g_ViewMatrix[2][0]) * vec3(1.0, 0.0, 1.0));
    vec3 up = vec3(0.0, 1.0, 0.0);

    float cornerX = inTexCoord2.x;
    float cornerY = inTexCoord2.y;
    float width = inTexCoord2.z;
    float height = inTexCoord2.w;
    vec3 worldPos = anchor + right * (cornerX * width) + up * (cornerY * height);

    vUv = inTexCoord;
    vDist = distance(worldPos, g_CameraPosition);
    // Square (Chebyshev) horizontal distance of the ANCHOR (the whole tree fades together), matching the
    // far terrain's inner-radius test so the billboards appear exactly where the far terrain does.
    vec2 horiz = abs(anchor.xz - g_CameraPosition.xz);
    vHorizDist = max(horiz.x, horiz.y);

    gl_Position = g_ViewProjectionMatrix * vec4(worldPos, 1.0);
    gl_Position.z += m_DepthBias;
}
