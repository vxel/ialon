#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform vec4 m_BaseColor;
uniform vec3 m_LightDir;     // world-space direction the sunlight travels along
uniform vec4 m_AmbientColor; // scene ambient light colour (sunColor * ambiantIntensity, day/night)
uniform vec4 m_SunColor;     // scene directional light colour (sunColor * sunIntensity, day/night)
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

// Sky/sun reflection on the distant water (no waves : the far sea is flat, normal is up). Same colours
// and tuning as the calm-water shader (fed by FarTerrainState) so near and far water stay consistent.
uniform vec3 g_CameraPosition;
uniform vec4 m_SkyColor;
uniform vec4 m_SkyHorizonColor;
uniform float m_ReflectionStrength;
uniform float m_FresnelPower;
uniform float m_GlintPower;
uniform float m_GlintStrength;
uniform vec3 m_MoonDirection;      // world-space, points TOWARDS the moon (normalised)
uniform vec4 m_MoonColor;
uniform float m_MoonGlintStrength;

varying vec3 vNormal;
varying float vDist;
varying float vHorizDist;
varying float vHeight;
varying vec3 vWorldPos;

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

    // Match the voxel lighting model (jME Phong, see Ialon.j3md) : ambient + sun * NdotL, where
    // both colours already carry the config intensities (ambiantIntensity / sunIntensity) AND the
    // day/night cycle, baked in by SunControl. So the far terrain now dims at dusk and respects the
    // lighting sliders exactly like the loaded voxels do (no baked vertex light / specular here).
    vec3 color = terrainColor * (m_AmbientColor.rgb + m_SunColor.rgb * diffuse);

    // Sky + sun reflection on the flat distant water (weighted by how "water" this fragment is, so land
    // and shores are untouched). Like the calm-water shader but without waves : the far sea is flat, so
    // the surface normal n (~up) is used directly -> the sun glint is a clean, smooth highlight (no
    // sparkle/aliasing), which is exactly the low evening sun's reflection streak that must continue
    // past the voxel/far-terrain seam. Computed before the fog, so it fades into the horizon haze.
    float waterness = 1.0 - landAmount;
    if (waterness > 0.001) {
        vec3 toSun = -normalize(m_LightDir);
        vec3 V = normalize(g_CameraPosition - vWorldPos);
        float fresnel = 0.02 + 0.98 * pow(1.0 - max(dot(n, V), 0.0), m_FresnelPower);
        vec3 R = reflect(-V, n);
        vec3 skyRefl = mix(m_SkyHorizonColor.rgb, m_SkyColor.rgb, clamp(R.y, 0.0, 1.0));
        // Same gate/tuning as the near water so the glint is continuous across the seam.
        float sunUp = clamp(toSun.y * 4.0, 0.0, 1.0);
        float glint = pow(max(dot(R, toSun), 0.0), m_GlintPower) * m_GlintStrength * sunUp;
        // Moon glint : fainter, gated by the moon's height -> only at night (the moon is anti-solar).
        vec3 toMoon = normalize(m_MoonDirection);
        float moonUp = clamp(toMoon.y * 4.0, 0.0, 1.0);
        float moonGlint = pow(max(dot(R, toMoon), 0.0), m_GlintPower) * m_MoonGlintStrength * moonUp;
        color = mix(color, skyRefl, fresnel * m_ReflectionStrength * waterness)
              + glint * m_SunColor.rgb * waterness
              + moonGlint * m_MoonColor.rgb * waterness;
    }

    // Exponential-squared distance fog : 1 near (clear) -> 0 far (full fog colour).
    // Only the far terrain uses this material, so the sky is left untouched.
    float f = (vDist / m_FogDistance) * m_FogDensity;
    float fog = clamp(exp(-f * f), 0.0, 1.0);

    vec3 outColor = mix(m_FogColor.rgb, color, fog);
#ifdef MANUAL_SRGB
    // No hardware sRGB framebuffer (Android GLES) : the palette/fog colours and lighting are all in
    // linear space (matching the desktop path), so encode linear->sRGB here as the framebuffer would.
    outColor = pow(outColor, vec3(1.0 / 2.2));
#endif
    gl_FragColor = vec4(outColor, 1.0);
}
