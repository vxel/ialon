#import "Common/ShaderLib/GLSLCompat.glsllib"

// Procedural flame vertex shader (for the billboard fire block).
// The fire block's mesh is a single degenerate quad whose 4 vertices all sit at the block centre
// (see the Billboard shape) ; the corner of each vertex is carried in the UV. Here we expand that
// quad around the centre toward the camera — a cylindrical (Y-up) billboard, identical in spirit to
// Shaders/FarTree.vert : the flame always faces the player horizontally yet stays perfectly upright.

uniform mat4 g_WorldMatrix;
uniform mat4 g_ViewMatrix;
uniform mat4 g_ViewProjectionMatrix;
uniform float m_Size; // world-space size of the quad (one block in-world ; pixels in the GUI preview)

attribute vec3 inPosition; // block centre (shared by the 4 corners)
attribute vec2 inTexCoord; // padded corner UV (0.25 .. 0.75), also drives the flame pattern

out vec2 vUv;
out vec3 vWorldPos; // the block CENTRE (constant across the quad) — used for the per-block phase

void main() {
    // Remap the padded UV (0.25 .. 0.75) to a centred corner offset in [-0.5, 0.5].
    vec2 corner = (inTexCoord - 0.25) * 2.0 - 0.5;

    vec3 centerWorld = (g_WorldMatrix * vec4(inPosition, 1.0)).xyz;

    // Camera right projected onto the ground plane (first column of the world->view matrix, Y zeroed
    // then renormalised) keeps the billboard vertical ; up is world up.
    vec3 right = normalize(vec3(g_ViewMatrix[0][0], g_ViewMatrix[1][0], g_ViewMatrix[2][0]) * vec3(1.0, 0.0, 1.0));
    vec3 up = vec3(0.0, 1.0, 0.0);

    vec3 worldPos = centerWorld + right * (corner.x * m_Size) + up * (corner.y * m_Size);

    gl_Position = g_ViewProjectionMatrix * vec4(worldPos, 1.0);
    vUv = inTexCoord;
    vWorldPos = centerWorld;
}
