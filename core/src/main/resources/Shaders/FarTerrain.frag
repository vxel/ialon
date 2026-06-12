#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform vec4 m_BaseColor;
uniform vec3 m_LightDir;     // world-space direction the sunlight travels along
uniform vec4 m_AmbientColor; // scene ambient light colour (sunColor * ambiantIntensity, day/night)
uniform vec4 m_SunColor;     // scene directional light colour (sunColor * sunIntensity, day/night)
uniform vec4 m_FogColor;
uniform float m_FogDistance; // distance scale of the fog
uniform float m_FogDensity;  // fog thickness
uniform float m_InnerRadius; // below this horizontal distance the voxels are the truth -> discard

uniform vec3 g_CameraPosition;

// Altitude palette : roughly matches the blocks the generator places by height (a sandy-to-deep-blue
// seabed below the water level, a thin sand shore, grass/land above, then bare rock and snow on peaks).
uniform vec4 m_SeabedColor;
uniform vec4 m_SandColor;
uniform vec4 m_RockColor;
uniform vec4 m_SnowColor;
uniform float m_WaterHeight;
uniform float m_RockHeight;   // bare rock above this world height
uniform float m_SnowHeight;   // snow above this world height
uniform float m_HeightOffset; // vertical nudge applied to the terrain mesh ; undone here for colouring

// Sky/sun/moon reflection on the (flattened) sea surface. Same model & tuning as the near calm water.
uniform vec4 m_SkyColor;
uniform vec4 m_SkyHorizonColor;
uniform float m_ReflectionStrength;
uniform float m_FresnelPower;
uniform float m_GlintPower;
uniform float m_GlintStrength;
uniform vec3 m_MoonDirection;
uniform vec4 m_MoonColor;
uniform float m_MoonGlintStrength;

#ifdef FOREST_TINT
uniform sampler2D m_ForestDensityMap;
uniform vec4 m_ForestTintColor;
uniform float m_ForestTintStrength;
uniform float m_ForestTintStart; // distance at which the tint ramps in (where the billboards thin out)
uniform float m_Extent;          // world span the density map covers (same as the terrain extent)
uniform vec2 m_ForestOrigin;     // terrain world translation (tile snap on the torus ; 0 otherwise)
#endif

varying vec3 vNormal;
varying float vDist;
varying float vHorizDist;
varying float vHeight;
varying vec3 vWorldPos;

void main() {
    // The far terrain is only an horizon ring : inside the loaded-chunk region the voxels are the
    // truth, so discard it there (prevents it showing through caves / overhangs near the player, and
    // keeps the near voxel water transparent -- no opaque far sea backing it).
    if (vHorizDist < m_InnerRadius) {
        discard;
    }

    // Altitude in generator/world-block space : the mesh is nudged up by m_HeightOffset, so undo that
    // here. NB : the sea geometry is flattened to the water line in the vertex shader, but vHeight still
    // carries the TRUE (submerged) height, which is what drives the coastal gradient below.
    float height = vHeight - m_HeightOffset;
    float landAmount = smoothstep(m_WaterHeight, m_WaterHeight, height);
    float waterness = 1.0 - landAmount;

    // Coastal gradient : sand at the shoreline shallows -> dark-blue seabed with depth (like the voxels).
    float depth = clamp(m_WaterHeight - height, 0.0, 1.0);
    vec3 seabed = mix(m_SandColor.rgb, m_SeabedColor.rgb, depth);
    // Sand fringe : the shore is sand for ~2 units above the water, then grass/land.
    vec3 land = mix(m_SandColor.rgb, m_BaseColor.rgb, smoothstep(m_WaterHeight, m_WaterHeight + 2.0, height));
    // High-altitude tiers, matching the voxel generator : grass -> bare rock -> snow caps.
    land = mix(land, m_RockColor.rgb, smoothstep(m_RockHeight - 2.0, m_RockHeight + 2.0, height));
    land = mix(land, m_SnowColor.rgb, smoothstep(m_SnowHeight - 2.0, m_SnowHeight + 2.0, height));
    vec3 terrainColor = mix(seabed, land, landAmount);

#ifdef FOREST_TINT
    // Forest tint : pull the distant grass slopes toward a dark forest green where the (seamless) density
    // field is high. Gated to land in the grass band only (no tint on sea / sand / bare rock / snow) and
    // to beyond the billboard ring (ForestTintStart), so the near billboards aren't double-darkened.
    vec2 fuv = (vWorldPos.xz - m_ForestOrigin) / m_Extent + 0.5;
    float density = texture2D(m_ForestDensityMap, fuv).r;
    float grass = smoothstep(m_WaterHeight, m_WaterHeight + 2.0, height)
                * (1.0 - smoothstep(m_RockHeight - 2.0, m_RockHeight + 2.0, height));
    float distGate = smoothstep(m_ForestTintStart, m_ForestTintStart + 128.0, vDist);
    float tint = density * m_ForestTintStrength * landAmount * grass * distGate;
    terrainColor = mix(terrainColor, m_ForestTintColor.rgb, tint);
#endif

    // Lighting normal : flat (up) on the sea (it was flattened), real relief on land. The sea reflection
    // also uses the flat up-normal, so the distant sea reads as a calm flat surface.
    vec3 up = vec3(0.0, 1.0, 0.0);
    vec3 nLit = normalize(mix(up, normalize(vNormal), landAmount));
    float diffuse = max(dot(nLit, -normalize(m_LightDir)), 0.0);

    // Match the voxel lighting model (see Ialon.vert) : ambient tints only by its LUMINANCE (scalar dim,
    // no orange wash), the directional term keeps the warm sun colour on lit slopes.
    float ambientLum = dot(m_AmbientColor.rgb, vec3(0.3, 0.59, 0.11));
    // Sea body lighting matches the near calm water EXACTLY (its body = colour * min(ambientLum, 0.7) :
    // ambient luminance, capped at 0.7, no directional term -- see Ialon.vert), so the two seas have the
    // same shade at the seam. Land keeps the full ambient + directional lighting.
    vec3 seaLit = terrainColor * min(ambientLum, 0.7);
    vec3 landLit = terrainColor * (vec3(ambientLum) + m_SunColor.rgb * diffuse);
    vec3 color = mix(seaLit, landLit, landAmount);

    // Sea reflection : sky (Fresnel-weighted) + sun glint + moon glint, only on the water (weighted by
    // waterness). Computed before the fog so it fades into the horizon haze.
    if (waterness > 0.001) {
        vec3 V = normalize(g_CameraPosition - vWorldPos);
        vec3 R = reflect(-V, up);
        float fresnel = 0.02 + 0.98 * pow(1.0 - max(dot(up, V), 0.0), m_FresnelPower);
        vec3 skyRefl = mix(m_SkyHorizonColor.rgb, m_SkyColor.rgb, clamp(R.y, 0.0, 1.0));
        vec3 toSun = -normalize(m_LightDir);
        float sunUp = clamp(toSun.y * 4.0, 0.0, 1.0);
        float glint = pow(max(dot(R, toSun), 0.0), m_GlintPower) * m_GlintStrength * sunUp;
        vec3 toMoon = normalize(m_MoonDirection);
        float moonUp = clamp(toMoon.y * 4.0, 0.0, 1.0);
        float moonGlint = pow(max(dot(R, toMoon), 0.0), m_GlintPower) * m_MoonGlintStrength * moonUp;
        color = mix(color, skyRefl, fresnel * m_ReflectionStrength * waterness)
              + (glint * m_SunColor.rgb + moonGlint * m_MoonColor.rgb) * waterness;
    }

    // Exponential-squared distance fog : 1 near (clear) -> 0 far (full fog colour). The fog starts at
    // the inner radius (the seam with the near water/voxels), not at the camera, so the far terrain is
    // CLEAR where it meets the unfogged near water -- no brightness step at the junction -- and only
    // fades as it recedes towards the horizon.
    float f = (max(vDist - m_InnerRadius, 0.0) / m_FogDistance) * m_FogDensity;
    float fog = clamp(exp(-f * f), 0.0, 1.0);

    vec3 outColor = mix(m_FogColor.rgb, color, fog);
#ifdef MANUAL_SRGB
    // No hardware sRGB framebuffer (Android GLES) : palette/fog/lighting are linear, encode here.
    outColor = pow(outColor, vec3(1.0 / 2.2));
#endif
    gl_FragColor = vec4(outColor, 1.0);
}
