#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform mat4 g_ViewProjectionMatrix;
uniform mat4 g_WorldMatrix;
uniform vec3 g_CameraPosition;
uniform float m_DepthBias;

attribute vec3 inPosition;
attribute vec3 inNormal;

varying vec3 vNormal;
varying float vDist;
varying float vHorizDist;
varying float vHeight;
varying vec3 vWorldPos;

void main() {
    vec4 worldPos = g_WorldMatrix * vec4(inPosition, 1.0);
    // Uniform world scale : direction is preserved (renormalized in the fragment shader).
    vNormal = (g_WorldMatrix * vec4(inNormal, 0.0)).xyz;
    // World height, passed to the fragment shader for the altitude palette / coastal depth gradient. The
    // far terrain is now the TRUE relief (no flatten) : the RELIEF mesh renders the real land + sea floor,
    // and the flat sea is a SEPARATE water plane (WATER) whose geometry is already flat -- so this shader
    // is a plain pass-through for both, no vertex displacement.
    vHeight = worldPos.y;
    vWorldPos = worldPos.xyz;

    vDist = distance(worldPos.xyz, g_CameraPosition);
    // Square (Chebyshev) horizontal distance from the camera : used to discard the far terrain inside
    // the loaded-chunk region (where the voxels are the truth), so it can't show up in caves / under
    // overhangs there. The loaded region is an axis-aligned SQUARE of chunks, so max(|dx|,|dz|) matches
    // its boundary on all four sides ; a Euclidean (circular) test would cut the corners and let the far
    // terrain creep in along the diagonals, where it never lines up with a chunk edge.
    vec2 horiz = abs(worldPos.xz - g_CameraPosition.xz);
    vHorizDist = max(horiz.x, horiz.y);

    gl_Position = g_ViewProjectionMatrix * worldPos;
    // Depth bias : push the far terrain slightly further away in clip-space depth so the voxel
    // chunks always win the depth test where they overlap it -- prevents the smooth far terrain
    // from poking through the blocky voxel surface, while it still shows on the open horizon.
    gl_Position.z += m_DepthBias;
}
