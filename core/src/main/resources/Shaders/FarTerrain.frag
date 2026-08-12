#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform vec3 m_LightDir;     // world-space direction the sunlight travels along
uniform vec4 m_AmbientColor; // scene ambient light colour (sunColor * ambiantIntensity, day/night)
uniform vec4 m_SunColor;     // scene directional light colour (sunColor * sunIntensity, day/night)
uniform vec4 m_FogColor;
uniform float m_FogDistance; // distance scale of the fog
uniform float m_FogDensity;  // fog thickness
uniform float m_InnerRadius; // below this horizontal distance the voxels are the truth -> discard
// Where the fog (and the FAR_NOISE fade) starts, in world units from the camera. This is NOT m_InnerRadius :
// the relief is drawn by TWO meshes that hand over to each other at a fixed radius (the near clipmap up to
// NearTerrainState.OUTER_RADIUS, the far TerrainQuad beyond), so their discard radii differ by hundreds of
// units. Driving the fog from the discard radius would give each mesh its own fog ramp and turn the handover
// into a visible colour seam -- so both are given the SAME FogStart (the voxel seam, see FarTerrainState).
uniform float m_FogStart;
#ifdef OUTER_RADIUS
// Near clipmap only : beyond this horizontal distance the far TerrainQuad is the truth -> discard. Set to
// exactly the TerrainQuad's m_InnerRadius, so the two tests (< inner / >= outer) are strictly complementary
// on the same vHorizDist : every pixel gets exactly one of the two surfaces, no hole and no overlap.
uniform float m_OuterRadius;
#endif

uniform vec3 g_CameraPosition;

varying vec3 vNormal;
varying float vDist;
varying float vHorizDist;
varying float vHeight;
varying vec3 vWorldPos;

// The far terrain is rendered in TWO modes from this one shader :
//   - RELIEF (default) : one mesh of the TRUE relief -- the real land ABOVE the water and the real sea
//     floor BELOW it, coloured by altitude and lit by the real normal. Opaque, single-sided (only ever
//     seen from above, so no underside is drawn). The old "flatten the sea" surface and the separate
//     "seabed" mesh are merged into this single relief.
//   - WATER : a flat, SEMI-TRANSPARENT blue water plane at the water line, with the sky/sun/moon
//     reflection -- the same water body as the near voxel calm water, so near & far read as one sea. It
//     is its own geometry (a flat grid), detached from the relief.

// Altitude palette : matches the blocks the generator places by height. Below the water line : a sea
// floor of sand-fringe -> dirt -> grey bedrock, tinted by the water column (SeabedWaterColor) so it
// reads like the near voxel floor seen through the water. Above : sand shore -> grass -> rock -> snow.
uniform vec4 m_SeabedColor;      // dirt sea floor
uniform vec4 m_BedrockColor;     // grey bedrock the deepest floor grades to
uniform vec4 m_SeabedWaterColor; // bluish through-water tint on the sea floor
uniform vec4 m_BaseColor;        // grass band (or per-biome via BiomeMap)
uniform vec4 m_SandColor;
uniform vec4 m_RockColor;        // bare mountain rock
uniform vec4 m_SnowColor;
uniform float m_WaterHeight;
uniform float m_WaterlineClearance;
uniform float m_RockHeight;      // bare rock above this world height (base line, see STRATA_MAP)
uniform float m_SnowHeight;      // snow above this world height (base line, see STRATA_MAP)
uniform float m_HeightOffset;    // vertical nudge applied to the mesh ; undone here for colouring

#ifdef STRATA_MAP
// Per-column rock & snow lines, baked from NoiseTerrainGenerator.strataLinesAt : the same wandering,
// climate-driven lines the voxel generator caps its columns with, so a massif that is snowy up close is
// snowy on the horizon too. R = snow line, G = rock line, both as a fraction of the world ceiling.
uniform sampler2D m_StrataMap;
uniform float m_WorldCeiling;
// (soilSlope, bareSlope, snowRise, rockDrop) -- the generator's slope ramp, see STRATA_SLOPE_* there.
uniform vec4 m_SlopeStrata;
// (altLow, altHigh, rockFloor) : the altitudes over which the SOIL half of that ramp fades in -- a
// lowland hillside keeps its grass however steep it is -- and the floor the rock line stops at
// (NoiseTerrainGenerator.soilBareness / rockLineWithSlope).
uniform vec3 m_SoilStrata;
#endif

#if defined(FOREST_TINT) || defined(BIOME_MAP) || defined(BIOME_SEABED_MAP) || defined(STRATA_MAP)
uniform float m_Extent;
uniform vec2 m_ForestOrigin;
#endif
#ifdef FOREST_TINT
uniform sampler2D m_ForestDensityMap;
uniform vec4 m_ForestTintColor;
uniform float m_ForestTintStrength;
uniform float m_ForestTintStart;
#endif
#ifdef BIOME_MAP
uniform sampler2D m_BiomeMap;
#endif
#ifdef BIOME_SEABED_MAP
uniform sampler2D m_BiomeSeabedMap;
#endif
#ifdef FAR_NOISE
uniform float m_NoiseStrength;
uniform float m_NoiseScale;

// Low-quality value noise + fBm (same construction as Shaders/map.frag), used only to break up the flat
// altitude palette of the distant land into something that reads as textured relief.
float hash21(vec2 p) {
    p = 50.0 * fract(p * 0.3183099);
    return fract(p.x * p.y * (p.x + p.y));
}
float vnoise(in vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
}
const mat2 NOISE_ROT = mat2(0.80, 0.60, -0.60, 0.80);
float fbm(vec2 p) {
    float a;
    a  = 0.5000 * vnoise(p); p = p * NOISE_ROT * 2.0;
    a += 0.2500 * vnoise(p); p = p * NOISE_ROT * 2.0;
    a += 0.1250 * vnoise(p); p = p * NOISE_ROT * 2.0;
    a += 0.0625 * vnoise(p);
    return a;
}
#endif

void main() {
    // The far terrain is only an horizon ring : inside the loaded-chunk region the voxels are the truth,
    // so discard it there (prevents it showing through caves / overhangs near the player).
    if (vHorizDist < m_InnerRadius) {
        discard;
    }
#ifdef OUTER_RADIUS
    // Near clipmap : hand over to the far TerrainQuad (see m_OuterRadius).
    if (vHorizDist >= m_OuterRadius) {
        discard;
    }
#endif

    float ambientLum = dot(m_AmbientColor.rgb, vec3(0.3, 0.59, 0.11));

    // ---- Real relief : sea floor below the water line, land above ----
    float trueHeight = vHeight - m_HeightOffset;

    // The relief is kept clear of the flat water plane by +/- m_WaterlineClearance so the two surfaces
    // cannot graze each other and z-fight along the coast (FarTerrainState.clampAcrossWaterline). No
    // sample exists inside that band, so each palette branch starts at the edge it can actually reach :
    // without this shift the first LAND vertex would already be past the sand band (no beach at the far
    // shore) and the shallowest SEA FLOOR vertex would already be past its sand fringe.
    float landLine = m_WaterHeight + m_WaterlineClearance;
    float seaLine = m_WaterHeight - m_WaterlineClearance;
    // Land/sea colour blend, kept centred on the TRUE water line (where the relief actually crosses the
    // plane), spread over the clearance band -- which the interpolation sweeps through in one cell.
    float halfBand = max(m_WaterlineClearance, 0.001) * 0.5;

    // Below-water sea floor : sand fringe (top ~3 blocks) -> subsurface deeper -> grey bedrock at the world
    // floor, all tinted by the water column (SeabedWaterColor) so it reads blue like the near voxel floor.
    // The subsurface colour is per-biome (BiomeSeabedMap) so desert seas grade sand->sand, matching the
    // near voxels (generateUnderground: biomeSubsurface = sand for desert, dirt elsewhere) ; falls back to
    // the flat SeabedColor (dirt) when no biome map is bound.
    vec3 seabedColor = m_SeabedColor.rgb;
#ifdef BIOME_SEABED_MAP
    vec2 sbuv = (vWorldPos.xz - m_ForestOrigin) / m_Extent + 0.5;
    seabedColor = texture2D(m_BiomeSeabedMap, sbuv).rgb;
#endif
    float blockGrade = clamp((seaLine - trueHeight) / 3.0, 0.0, 1.0);
    vec3 sediment = mix(m_SandColor.rgb, seabedColor, blockGrade);
    float rockness = 1.0 - smoothstep(0.0, 1.0, trueHeight);
    // Bluish water-column tint on the sea floor : fades OUT toward the water surface so the shallow sand at
    // the shoreline reads as sand (not the teal sand*SeabedWaterColor that looked like a greenish rim at
    // the coast), ramping to the full tint ~3 blocks down so the deeper floor still reads blue like the
    // near voxel floor. Matches the sand-fringe depth (blockGrade, /3 above).
    float waterTint = smoothstep(seaLine, seaLine - 3.0, trueHeight);
    vec3 tint = mix(vec3(1.0), m_SeabedWaterColor.rgb, waterTint);
    vec3 seaFloor = mix(sediment, m_BedrockColor.rgb, rockness) * tint;

    vec3 nLit = normalize(vNormal);

    // Altitude strata : where this column turns to bare rock, then to snow. Flat world-wide lines unless
    // the strata map is bound, in which case they are the generator's own per-column lines.
    float rockH = m_RockHeight;
    float snowH = m_SnowHeight;
#ifdef STRATA_MAP
    vec2 stuv = (vWorldPos.xz - m_ForestOrigin) / m_Extent + 0.5;
    vec2 lines = texture2D(m_StrataMap, stuv).rg * m_WorldCeiling;
    snowH = lines.r;
    rockH = lines.g;
    // Slope term, from the relief normal : soil and snow slide off a steep face, so the rock line dives
    // toward the shore and the snow line climbs. The relief mesh is sampled every few blocks, so its
    // normal is a smoothed version of the generator's per-block slope -- the distant crags therefore come
    // out a little softer than the voxel ones (measured : the mesh keeps ~90% of the per-block slope).
    float ny = max(nLit.y, 0.001);
    float slope = sqrt(max(0.0, 1.0 - ny * ny)) / ny;
    float bare = smoothstep(m_SlopeStrata.x, m_SlopeStrata.y, slope);
    snowH += bare * m_SlopeStrata.z;
    // The soil is only stripped up on the flanks : fade the same ramp in with altitude, then drop the
    // rock line by a BOUNDED amount (see STRATA_ROCK_SLOPE_DROP -- a dive to the shore would make the
    // grass/rock edge a ruled horizontal contour).
    float soilBare = bare * smoothstep(m_SoilStrata.x, m_SoilStrata.y, trueHeight);
    rockH = max(rockH - soilBare * m_SlopeStrata.w, min(m_SoilStrata.z, rockH));
    rockH = min(rockH, snowH);
#endif

    // Above-water land palette : sand shore -> grass (or per-biome) -> bare rock -> snow caps.
    vec3 grassColor = m_BaseColor.rgb;
#ifdef BIOME_MAP
    vec2 buv = (vWorldPos.xz - m_ForestOrigin) / m_Extent + 0.5;
    grassColor = texture2D(m_BiomeMap, buv).rgb;
#endif
    vec3 land = mix(m_SandColor.rgb, grassColor, smoothstep(landLine, landLine + 2.0, trueHeight));
    land = mix(land, m_RockColor.rgb, smoothstep(rockH - 2.0, rockH + 2.0, trueHeight));
    land = mix(land, m_SnowColor.rgb, smoothstep(snowH - 2.0, snowH + 2.0, trueHeight));

    float landAmount = smoothstep(m_WaterHeight - halfBand, m_WaterHeight + halfBand, trueHeight);

#ifdef FAR_NOISE
    // Break up the flat altitude palette with a touch of fBm brightness variation (both the land AND the
    // sea floor, faded with distance so the far horizon doesn't shimmer -- the fog takes over there anyway).
    float noiseFade = 1.0 - smoothstep(m_FogStart, m_FogStart + m_FogDistance, vDist);
    float n = fbm(vWorldPos.xz * m_NoiseScale);
    float noiseMod = 1.0 + (n - 0.5) * 2.0 * m_NoiseStrength * noiseFade;
    land *= noiseMod;
    seaFloor *= noiseMod;
#endif
#ifdef FOREST_TINT
    // Pull the distant grass slopes toward a dark forest green where the (seamless) density field is high
    // (grass band only, beyond the billboard ring so the near billboards aren't double-darkened).
    vec2 fuv = (vWorldPos.xz - m_ForestOrigin) / m_Extent + 0.5;
    float density = texture2D(m_ForestDensityMap, fuv).r;
    float grass = smoothstep(landLine, landLine + 2.0, trueHeight)
                * (1.0 - smoothstep(rockH - 2.0, rockH + 2.0, trueHeight));
    float distGate = smoothstep(m_ForestTintStart, m_ForestTintStart + 128.0, vDist);
    land = mix(land, m_ForestTintColor.rgb, density * m_ForestTintStrength * grass * distGate);
#endif

    // Lit by the real relief normal. Below water : ambient-only (capped), matching the near voxel floor.
    // Above water : full ambient + warm directional term on lit slopes (matches the near voxel land).
    float diffuse = max(dot(nLit, -normalize(m_LightDir)), 0.0);
    vec3 floorLit = seaFloor * (vec3(min(ambientLum, 0.9)) + m_SunColor.rgb * diffuse * 0.35);
    vec3 landLit = land * (vec3(ambientLum) + m_SunColor.rgb * diffuse);
    vec3 color = mix(floorLit, landLit, landAmount);

    // Exponential-squared distance fog : 1 near (clear) -> 0 far (full fog colour). Starts at the VOXEL
    // seam (m_FogStart), not the camera and not this mesh's discard radius, so the far terrain is clear
    // where it meets the unfogged near water and only fades toward the horizon -- and so the near clipmap
    // and the far TerrainQuad share one continuous ramp across their handover.
    float f = (max(vDist - m_FogStart, 0.0) / m_FogDistance) * m_FogDensity;
    float fog = clamp(exp(-f * f), 0.0, 1.0);
    vec3 outColor = mix(m_FogColor.rgb, color, fog);

#ifdef MANUAL_SRGB
    // No hardware sRGB framebuffer (Android GLES) : palette/fog/lighting are linear, encode here.
    outColor = pow(outColor, vec3(1.0 / 2.2));
#endif
    gl_FragColor = vec4(outColor, 1.0);
}
