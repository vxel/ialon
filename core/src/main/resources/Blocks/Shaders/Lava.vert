#import "Common/ShaderLib/GLSLCompat.glsllib"

// Procedural lava vertex shader (for the solid lava cube block).
// It computes the 2D coordinate (vCoord) the fragment shader feeds to the molten fbm.
//
// Default (in-world) : a SEAMLESS triplanar mapping in world space. The coordinate is two of the
// three world-position axes, picked by the dominant face normal (top/bottom -> XZ, east/west -> ZY,
// north/south -> XY). Because it is a pure function of the world position, two coplanar faces of
// adjacent blocks sample one continuous noise field — the molten pattern flows from one block to
// the next with no per-face tiling. (With blockScale = 1 the frequency matches one pattern per
// block, so the look is unchanged apart from the seams disappearing.)
//
// USE_UV (block-selection preview only) : the preview batches the quad with a jME BatchNode, which
// BAKES the vertex positions into GUI-pixel space — world position is meaningless there. So the
// preview falls back to the per-face UV (the cube pads it into 0.25..0.75 — see Shape.UV_PADDING),
// remapped to a full 0..1 tile, which fills the icon correctly.

uniform mat4 g_WorldViewProjectionMatrix;
uniform mat4 g_WorldMatrix;
uniform float m_Scale; // zoom of the molten pattern (1.0 = one pattern unit per world/UV unit)

attribute vec3 inPosition;
attribute vec2 inTexCoord;
attribute vec3 inNormal;

out vec2 vCoord;

void main() {
    gl_Position = g_WorldViewProjectionMatrix * vec4(inPosition, 1.0);

#ifdef USE_UV
    vCoord = clamp((inTexCoord - 0.25) * 2.0, 0.0, 1.0) * m_Scale;
#else
    vec3 wp = (g_WorldMatrix * vec4(inPosition, 1.0)).xyz;
    vec3 n = abs(inNormal);
    vec2 c;
    if (n.y >= n.x && n.y >= n.z) {
        c = wp.xz;            // up / down faces
    } else if (n.x >= n.z) {
        c = wp.zy;            // east / west faces
    } else {
        c = wp.xy;            // north / south faces
    }
    vCoord = c * m_Scale;
#endif
}
