#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform mat4 g_ViewProjectionMatrix;
uniform mat4 g_WorldMatrix;
uniform vec3 g_CameraPosition;
uniform float m_DepthBias;
uniform float m_WaterHeight;
uniform float m_HeightOffset; // vertical nudge applied to the terrain mesh

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
    // TRUE generator height (the mesh is nudged up by m_HeightOffset) : kept for the fragment shader's
    // altitude palette AND the coastal depth gradient, even after the sea is flattened below.
    vHeight = worldPos.y;

    // Flatten everything below the water level UP to the water surface : the far sea becomes a single
    // flat surface at the water line. Because it's part of THIS terrain mesh, the hills and the voxels
    // occlude it natively -- there's no separate water plane, so no depth-fighting and no flooding of
    // the hills. The submerged relief is gone from the geometry but its true height stays in vHeight,
    // so the fragment shader can still draw the sand->deep-blue coastal gradient.
    float trueHeight = worldPos.y - m_HeightOffset;
    if (trueHeight < m_WaterHeight) {
        worldPos.y = m_WaterHeight + m_HeightOffset;
    }
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
