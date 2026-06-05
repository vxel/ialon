#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform vec4 m_BaseColor;
uniform vec3 m_LightDir;     // world-space direction the sunlight travels along
uniform vec4 m_FogColor;
uniform float m_FogDistance; // distance scale of the fog
uniform float m_FogDensity;  // fog thickness
uniform float m_InnerRadius; // below this horizontal distance the voxels are the truth -> discard

// Altitude palette : roughly matches the blocks the generator places by height (water below the
// water level, a thin sand shore, grass/land above, then bare rock and snow on the high mountains).
uniform vec4 m_WaterColor;
uniform vec4 m_SandColor;
uniform vec4 m_RockColor;
uniform vec4 m_SnowColor;
uniform float m_WaterHeight;
uniform float m_RockHeight;   // bare rock above this world height
uniform float m_SnowHeight;   // snow above this world height
uniform float m_HeightOffset; // vertical nudge applied to the terrain mesh ; undone here for colouring

varying vec3 vNormal;
varying float vDist;
varying float vHorizDist;
varying float vHeight;

void main() {
    // The far terrain is only an horizon ring : inside the loaded-chunk region the voxels are the
    // truth, so discard it there (prevents it showing through caves / overhangs near the player).
    // vHorizDist is the SQUARE (Chebyshev) distance to the camera, so the discarded region is an
    // axis-aligned square matching the square chunk footprint (see FarTerrain.vert).
    if (vHorizDist < m_InnerRadius) {
        discard;
    }

    vec3 n = normalize(vNormal);
    float diffuse = max(dot(n, -normalize(m_LightDir)), 0.0);

    // Altitude in generator/world-block space : the terrain mesh is nudged up by m_HeightOffset to
    // bury its seam in the voxels, so undo that here to compare against the true block heights.
    float height = vHeight - m_HeightOffset;

    // Altitude palette : water (below) -> sand (shore) -> grass/land (m_BaseColor, above), blended.
    // Underwater ground is flattened to the water surface (FarTerrainState), so height == m_WaterHeight
    // there. The water->land ramp therefore starts AT the water level (so flat sea reads as full water),
    // but rises within ~1 unit : the voxel generator puts sand right at the water line (groundh in
    // [waterHeight, waterHeight+1)), so anything above the surface must read as land/sand, not sea -- a
    // wider ramp made shorelines (a thin band just above the water) wrongly fade back to blue water.
    float landAmount = smoothstep(m_WaterHeight, m_WaterHeight + 1.0, height);
    // Sand fringe : the shore is sand for ~2 units above the water, then grass/land. The sand blend
    // starts a touch above the water line so the sea floor's flattened edge doesn't tint the water.
    vec3 land = mix(m_SandColor.rgb, m_BaseColor.rgb, smoothstep(m_WaterHeight + 1.0, m_WaterHeight + 3.0, height));
    // High-altitude tiers, matching the voxel generator : grass -> bare rock -> snow caps. The hard
    // voxel cut is softened into a short blend band (the seam to the voxels is far away).
    land = mix(land, m_RockColor.rgb, smoothstep(m_RockHeight - 2.0, m_RockHeight + 2.0, height));
    land = mix(land, m_SnowColor.rgb, smoothstep(m_SnowHeight - 2.0, m_SnowHeight + 2.0, height));
    vec3 terrainColor = mix(m_WaterColor.rgb, land, landAmount);

    vec3 color = terrainColor * (0.35 + 0.65 * diffuse);

    // Exponential-squared distance fog : 1 near (clear) -> 0 far (full fog colour).
    // Only the far terrain uses this material, so the sky is left untouched.
    float f = (vDist / m_FogDistance) * m_FogDensity;
    float fog = clamp(exp(-f * f), 0.0, 1.0);

    gl_FragColor = vec4(mix(m_FogColor.rgb, color, fog), 1.0);
}
