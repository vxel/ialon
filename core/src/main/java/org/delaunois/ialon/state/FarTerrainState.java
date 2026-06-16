/*
 * Copyright (C) 2022 Cédric de Launois
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.terrain.geomipmap.TerrainLodControl;
import com.jme3.terrain.geomipmap.TerrainQuad;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.Chunk;
import org.delaunois.ialon.blocks.ChunkManager;
import org.delaunois.ialon.blocks.ChunkManagerListener;
import org.delaunois.ialon.blocks.WorldEditOverlay;
import org.delaunois.ialon.blocks.WorldManager;
import org.delaunois.ialon.blocks.ChunkMeshGenerator;
import org.delaunois.ialon.blocks.FacesMeshGenerator;
import org.delaunois.ialon.blocks.generator.NoiseTerrainGenerator;
import org.delaunois.ialon.blocks.generator.TerrainGenerator;
import org.delaunois.ialon.control.MoonControl;
import org.delaunois.ialon.control.SkyControl;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Renders a distant low-detail terrain ("far horizon") well beyond the loaded voxel chunks.
 *
 * <p>The far terrain is a jME geomipmap {@link TerrainQuad} (built-in distance LOD + culling) whose
 * heightmap is sampled from the <b>same</b> {@link TerrainGenerator#getHeight(Vector3f)} used to
 * generate the voxel chunks, so the distant relief matches exactly what the player walks on. No
 * voxels, no chunk meshing : a single heightfield mesh covers a large finite area cheaply.
 *
 * <p>The sea is part of this same mesh : the vertex shader flattens everything below the water level
 * up to the water surface (so the distant sea is a single flat surface, natively occluded by the hills
 * and voxels -- no separate water plane, no depth-fighting), while the fragment shader draws the
 * coastal sand-&gt;deep-blue gradient (from the preserved true height) and the sky/sun/moon reflection.
 *
 * <p>A closed enclosure (the {@link #enclosure} box) seals the world from below : its top is open (the
 * heightfield above is the lid), and its four walls + floor are simple inward-facing opaque quads. This
 * guarantees no ray can ever reach the sky from below the water surface -- which used to leak the bright
 * sky through the transparent voxel water (external grazing views, underwater views, and the brief gap
 * while a chunk pages in). The walls top out just under the flattened far-sea level, and the floor sits
 * below the deepest relief. The box follows the <b>camera</b> (not the world tile) at a radius safely
 * inside the camera far clip plane : a world-edge box would sit beyond the far plane and be clipped away,
 * leaving the underwater horizon unsealed.
 */
@Slf4j
public class FarTerrainState extends BaseAppState implements ChunkManagerListener {

    // Heightmap resolution : must be (2^n + 1). 513 -> 513x513 samples.
    private static final int HEIGHTMAP_SIZE = 513;
    // Patch size : must be (2^m + 1) and <= HEIGHTMAP_SIZE. Drives the LOD granularity.
    private static final int PATCH_SIZE = 65;
    // Forest-density tint map resolution (single channel, covers the whole extent).
    private static final int FOREST_DENSITY_SIZE = 256;

    private static final float ENCLOSURE_OFFSET = 0f;

    private final IalonConfig config;
    private final float extent;

    private SimpleApplication app;

    @Getter
    private TerrainQuad terrain;

    // Closed box sealing the world from below (open top : the heightfield is the lid). Built once, it
    // follows the camera at a radius inside the far clip plane so the horizon is always enclosed in
    // every direction (a world-edge box would be clipped away by the far plane -- see update()).
    @Getter
    private Geometry enclosure;
    // Enclosure vertical extents : depend on the relief/water level, not on the render distance, so they
    // are cached when the box is first built and reused when only its half-size (gridRadius) changes.
    private float enclosureFloorY;
    private float enclosureWallTopY;

    private Material material;
    // Sun direction, shared by reference with the material and refreshed each frame from LightingState.
    private final Vector3f lightDir = new Vector3f(-0.5f, -0.7f, -0.5f).normalizeLocal();
    // Moon direction (towards the moon), shared by reference with the material and refreshed each frame
    // from MoonControl. Starts below the horizon so the moon glint stays off until resolved.
    private final Vector3f moonDir = new Vector3f(0f, -1f, 0f);
    private MoonControl moonControl;
    // The fog colour is bound (once) to SkyControl's live ground colour so it follows the day/night cycle.
    private boolean fogColorBound = false;
    // The enclosure backstop colour is bound (once) to the same live ground colour as the fog, so the box
    // matches the horizon haze and darkens with the day/night cycle (otherwise it stays bright at night).
    private boolean enclosureColorBound = false;
    // The ambient / sun colours are bound (once) to LightingState's live light colours (mutated in place
    // by SunControl) so the far terrain dims and tints with the day/night cycle like the voxels.
    private boolean lightColorsBound = false;
    // Sea reflection : the overhead sky colour, refreshed each frame from SkyControl (shared source with
    // WaterState). Bound once by reference, then mutated in place so the shader tracks it.
    private final ColorRGBA reflectionSkyColor = new ColorRGBA();
    private boolean reflectionBound = false;
    // The reflection tuning (strength / glint / Fresnel / moon) is copied once from the calm-water
    // material so near and far water reflect identically ; the calm-water j3m stays the single source.
    private boolean reflectionTuningCopied = false;
    // Finite world : the (periodic) far terrain is re-centered on the player's current tile so the
    // horizon surrounds the player wherever they roam. These hold the current snap, in world units
    // (always a multiple of the world period), so update() only moves the mesh when the tile changes.
    private float tileOffsetX = 0f;
    private float tileOffsetZ = 0f;
    // Forest tint : the density map's local origin, shared by reference with the material. On the torus it
    // tracks the tile snap (a multiple of the period, so the periodic density texture stays aligned).
    private final Vector2f forestOrigin = new Vector2f();
    private boolean forestTintEnabled = false;
    // World units between two heightmap samples (extent / (HEIGHTMAP_SIZE - 1)) ; kept so the player-edit
    // height overrides can be converted back into the heightmap's pre-scaled units (see sampleHeightmap).
    private float step;
    // Player edits to the relief : each edited column is mapped to its nearest heightmap SAMPLE, which is
    // then re-measured from the live world (so an off-sample single-block edit moves nothing — it is
    // sub-resolution — instead of dragging a sample to a slope-mismatched height). Bound at init.
    private WorldEditOverlay editOverlay;
    private TerrainGenerator terrainGen;

    public FarTerrainState(IalonConfig config) {
        // Finite (torus) world : span 2 tiles, centered on the player's tile (see update()). With the
        // far terrain snapped to the nearest tile, the player is at most worldSize/2 from the center, so
        // a 2-tile span (worldSize on each side) comfortably covers the fog-visible horizon in every
        // direction while keeping the sampling step half that of a 3-tile span (sharper coastlines).
        // The relief is periodic, so the off-center tiles match seamlessly. Infinite world : plain extent.
        this(config, config.getWorldSize() > 0f ? 2f * config.getWorldSize() : config.getFarTerrainExtent());
    }

    public FarTerrainState(IalonConfig config, float extent) {
        this.config = config;
        this.extent = extent;
    }

    @Override
    protected void initialize(Application application) {
        this.app = (SimpleApplication) application;

        TerrainGenerator generator = config.getTerrainGenerator();
        if (generator == null) {
            log.warn("No terrain generator available : far terrain disabled");
            return;
        }

        // World units between two heightmap samples.
        this.step = extent / (HEIGHTMAP_SIZE - 1);
        float step = this.step;
        float[] heightmap = sampleHeightmap(generator, step);

        terrain = new TerrainQuad("FarTerrain", PATCH_SIZE, HEIGHTMAP_SIZE, heightmap);
        material = createMaterial();
        bindForestTint(generator, step);
        terrain.setMaterial(material);
        // Uniform scale : heights were pre-divided by step (see sampleHeightmap), so world Y is exact
        // again after scaling, AND a uniform scale keeps the terrain normals correct in world space.
        terrain.setLocalScale(step, step, step);
        // The far terrain spans the whole island, including under the loaded voxel chunks. Sinking it
        // keeps it buried inside the solid voxel volume near the player (so the real chunks hide it /
        // avoid z-fighting), while it still rises into view as the horizon beyond the chunks.
        terrain.setLocalTranslation(0f, config.getFarTerrainVerticalOffset(), 0f);
        terrain.setShadowMode(RenderQueue.ShadowMode.Off);

        // Distance-based LOD : reduces far patches' triangle count.
        TerrainLodControl lodControl = new TerrainLodControl(terrain, app.getCamera());
        terrain.addControl(lodControl);

        enclosure = createEnclosure(heightmap, step);

        // Replay any persisted player edits (reshaped relief) onto the fresh procedural heightmap.
        this.terrainGen = generator;
        editOverlay = config.getWorldEditOverlay();
        applyAllOverrides();

        // Refresh the far relief lazily : recompute an edited column's samples only when it leaves the
        // loaded grid (unfetch) and becomes visible at the horizon — never on the edit itself.
        if (config.getChunkManager() != null) {
            config.getChunkManager().addListener(this);
        }

        log.info("Far terrain generated : {}x{} heightmap, extent {} units (step {})",
                HEIGHTMAP_SIZE, HEIGHTMAP_SIZE, extent, step);
    }

    /**
     * Samples the generator height over a regular grid centered on the origin. The world coordinate
     * of sample (i, j) matches the position {@link TerrainQuad} gives to that heightmap cell, so the
     * far relief lines up with the voxel terrain. The real (un-flattened) relief is kept everywhere,
     * including the submerged sea floor : the vertex shader flattens the sea to the water level while
     * preserving the true height for the fragment shader's coastal depth gradient.
     */
    private float[] sampleHeightmap(TerrainGenerator generator, float step) {
        float[] heightmap = new float[HEIGHTMAP_SIZE * HEIGHTMAP_SIZE];
        float half = (HEIGHTMAP_SIZE - 1) / 2f;
        Vector3f sample = new Vector3f();
        for (int j = 0; j < HEIGHTMAP_SIZE; j++) {
            float worldZ = (j - half) * step;
            int row = j * HEIGHTMAP_SIZE;
            for (int i = 0; i < HEIGHTMAP_SIZE; i++) {
                float worldX = (i - half) * step;
                float h = generator.getHeight(sample.set(worldX, 0f, worldZ));
                // Pre-divided by step : after the uniform localScale (step) the world Y is exact again.
                heightmap[row + i] = h / step;
            }
        }
        return heightmap;
    }

    /**
     * Replays all persisted relief overrides onto the heightmap. Each override is keyed by a
     * <b>canonical</b> heightmap sample (wrapped to the world period), so on the finite torus it is
     * placed on every copy of its tile within the current 2-tile span ({@link TerrainQuad#setHeight}
     * culls the out-of-grid copies itself). Called once at init / after a reload. A canonical sample
     * maps to a tile-invariant grid cell (the tile snap is a whole number of periods), so the patches
     * survive tile snaps untouched and only fresh edits need re-applying (see {@link #onChunkUnfetched}).
     */
    private void applyAllOverrides() {
        if (terrain == null || editOverlay == null || editOverlay.getHeightOverrides().isEmpty()) {
            return;
        }
        List<Vector2f> xz = new ArrayList<>();
        List<Float> heights = new ArrayList<>();
        for (Map.Entry<Long, Float> e : editOverlay.getHeightOverrides().entrySet()) {
            addCopies(xz, heights, WorldEditOverlay.unpackX(e.getKey()), WorldEditOverlay.unpackZ(e.getKey()), e.getValue());
        }
        if (!xz.isEmpty()) {
            terrain.setHeight(xz, heights);
        }
    }

    // Only the unfetch event is used ; the other listener callbacks are irrelevant to the far terrain.
    @Override
    public void onChunkUpdated(Chunk chunk) {
        // no-op
    }

    @Override
    public void onChunkAvailable(Chunk chunk) {
        // no-op
    }

    /**
     * Refreshes the far heightmap for an edited chunk column as it leaves the loaded grid (and becomes
     * visible at the horizon). For each far-terrain <b>sample</b> in the chunk's XZ footprint, the
     * ground is re-measured from the live world : if it now differs from the procedural relief the
     * sample is overridden, otherwise any stale override is dropped — and {@code setHeight} is called
     * <b>only when the value actually changes</b> (so an edit that doesn't move a sample, or a building
     * block, costs no terrain-mesh work).
     *
     * <p>Re-measuring the sample (not copying the edited column's height) avoids the slope-mismatch that
     * flashed water on land. The whole vertical column must still be cached so the true surface is found ;
     * the first of the column's chunks to leave satisfies this (siblings not yet evicted), later ones bail.
     */
    @Override
    public void onChunkUnfetched(Chunk chunk) {
        if (chunk == null || terrain == null || editOverlay == null || terrainGen == null) {
            return;
        }
        Vec3i loc = chunk.getLocation();
        long colKey = WorldEditOverlay.pack(loc.x, loc.z);
        if (!editOverlay.isColumnEdited(colKey)) {
            return; // untouched column : the far relief already matches the procedural noise
        }
        ChunkManager cm = config.getChunkManager();
        if (cm == null) {
            return;
        }
        // Recompute only while the whole vertical column is still cached, so groundHeight sees the true
        // (highest) surface regardless of the per-chunk eviction order.
        int gridHeight = config.getGridHeight();
        for (int cy = 0; cy < gridHeight; cy++) {
            if (!cm.getChunk(new Vec3i(loc.x, cy, loc.z)).isPresent()) {
                return;
            }
        }
        editOverlay.clearColumnEdited(colKey);

        Vec3i cs = BlocksConfig.getInstance().getChunkSize();
        int ceiling = config.getMaxy();
        int worldX0 = loc.x * cs.x;
        int worldZ0 = loc.z * cs.z;
        // Far-terrain samples (multiples of step from the tile origin) inside this chunk's XZ footprint.
        int kxMin = (int) Math.ceil((worldX0 - tileOffsetX) / step);
        int kxMax = (int) Math.floor((worldX0 + cs.x - 1 - tileOffsetX) / step);
        int kzMin = (int) Math.ceil((worldZ0 - tileOffsetZ) / step);
        int kzMax = (int) Math.floor((worldZ0 + cs.z - 1 - tileOffsetZ) / step);

        List<Vector2f> xz = new ArrayList<>();
        List<Float> heights = new ArrayList<>();
        Vector3f scratch = new Vector3f();
        for (int kx = kxMin; kx <= kxMax; kx++) {
            float sx = tileOffsetX + kx * step;
            for (int kz = kzMin; kz <= kzMax; kz++) {
                float sz = tileOffsetZ + kz * step;
                float measured = WorldManager.groundHeight(cm, Math.round(sx), Math.round(sz), ceiling);
                if (Float.isNaN(measured)) {
                    continue;
                }
                float procedural = terrainGen.getHeight(scratch.set(sx, 0f, sz));
                long key = sampleKey(sx, sz);
                Float prev = editOverlay.getHeightOverrides().get(key);
                if (Math.abs(measured - procedural) > 0.5f) {
                    if (prev != null && Math.abs(prev - measured) < 0.001f) {
                        continue; // already overridden to this value : no mesh work
                    }
                    editOverlay.putHeight(key, measured);
                    addCopies(xz, heights, sx, sz, measured);
                } else {
                    if (prev == null) {
                        continue; // already procedural : nothing to do (the common case)
                    }
                    editOverlay.putHeight(key, Float.NaN); // restore the procedural relief
                    addCopies(xz, heights, sx, sz, procedural);
                }
            }
        }
        if (!xz.isEmpty()) {
            terrain.setHeight(xz, heights);
        }
    }

    /**
     * Appends the terrain-local {@code setHeight} entries for a world position : the value (pre-scaled
     * {@code worldHeight / step}, matching {@link #sampleHeightmap}) at every copy of that position
     * within the current 2-tile span. The xz are terrain-local because {@code setHeight} ignores the
     * world translation (unlike {@code getHeight}) ; out-of-grid copies are culled by {@code setHeight}.
     */
    private void addCopies(List<Vector2f> xz, List<Float> heights, float worldX, float worldZ, float worldHeight) {
        float w = config.getWorldSize();
        float hy = worldHeight / step;
        if (w > 0f) {
            int kx0 = Math.round((tileOffsetX - worldX) / w);
            int kz0 = Math.round((tileOffsetZ - worldZ) / w);
            for (int kx = kx0 - 1; kx <= kx0 + 1; kx++) {
                for (int kz = kz0 - 1; kz <= kz0 + 1; kz++) {
                    xz.add(new Vector2f((worldX + kx * w) - tileOffsetX, (worldZ + kz * w) - tileOffsetZ));
                    heights.add(hy);
                }
            }
        } else {
            xz.add(new Vector2f(worldX - tileOffsetX, worldZ - tileOffsetZ));
            heights.add(hy);
        }
    }

    /** Canonical key (wrapped to the world period) identifying a heightmap sample across tiles. */
    private long sampleKey(float sampleWorldX, float sampleWorldZ) {
        int x = Math.round(sampleWorldX);
        int z = Math.round(sampleWorldZ);
        float w = config.getWorldSize();
        if (w > 0f) {
            x = Math.floorMod(x, (int) w);
            z = Math.floorMod(z, (int) w);
        }
        return WorldEditOverlay.pack(x, z);
    }

    private Material createMaterial() {
        // Custom material : fog lives HERE (only the terrain fades to the horizon colour), so the
        // sky — rendered separately by SkyState — keeps its blue gradient untouched.
        Material mat = new Material(app.getAssetManager(), "Shaders/FarTerrain.j3md");
        // Emulate the sRGB output encode in-shader where the hardware sRGB framebuffer is missing
        // (Android GLES) ; on desktop the hardware does it and this define compiles out.
        mat.setBoolean("ManualSrgb", config.isManualGammaEncode());
        mat.setColor("BaseColor", config.getFarTerrainBaseColor());
        // Initial placeholder : update() rebinds this to SkyControl's live ground colour (day/night).
        ColorRGBA ground = config.getGroundDayColor();
        mat.setColor("FogColor", new ColorRGBA(ground.r, ground.g, ground.b, 1f));
        mat.setFloat("FogDistance", config.getFarTerrainFogDistance());
        mat.setFloat("FogDensity", config.getFarTerrainFogDensity());
        mat.setFloat("DepthBias", config.getFarTerrainDepthBias());
        // Discard the far terrain within the loaded-chunk region : it only shows beyond the voxels.
        // Interpreted as a square (Chebyshev) half-extent in the shader, so it matches the square chunk
        // footprint ; the -chunkSize margin keeps it safely inside as the camera moves within a chunk.
        mat.setFloat("InnerRadius", (float) config.getGridRadius() * config.getChunkSize() - config.getChunkSize());
        // Altitude palette : the coastal gradient (sand at the shore -> open-water colour with depth)
        // plus sand / grass / rock / snow above. The rock & snow lines use the SAME ratios and world
        // ceiling as NoiseTerrainGenerator, so the distant tiers line up exactly.
        // Deep-sea colour : the OPAQUE far sea is a water SURFACE, so its deep tint must match the near
        // voxel/calm water (calmWaterColor), not a dark seabed -- otherwise the deep blue doesn't line
        // up with the near water at the seam.
        ColorRGBA deep = config.getCalmWaterColor();
        mat.setColor("SeabedColor", new ColorRGBA(deep.r, deep.g, deep.b, 1f));
        mat.setColor("SandColor", config.getFarTerrainSandColor());
        mat.setColor("RockColor", config.getFarTerrainRockColor());
        mat.setColor("SnowColor", config.getFarTerrainSnowColor());
        mat.setFloat("WaterHeight", config.getWaterHeight());
        mat.setFloat("RockHeight", NoiseTerrainGenerator.ROCK_LINE_RATIO * config.getMaxy());
        mat.setFloat("SnowHeight", NoiseTerrainGenerator.SNOW_LINE_RATIO * config.getMaxy());
        // The terrain mesh is nudged vertically (localTranslation above) ; the shaders undo this offset
        // so the altitude palette compares against the generator's true block heights, and the vertex
        // shader flattens the sea at the right world level.
        mat.setFloat("HeightOffset", config.getFarTerrainVerticalOffset());
        // fBm brightness variation : breaks up the flat altitude palette so the distant land reads as
        // textured relief. Only set when enabled (>0), so the FAR_NOISE define (keyed on NoiseStrength)
        // compiles the noise out entirely otherwise.
        if (config.getFarTerrainNoiseStrength() > 0f) {
            mat.setFloat("NoiseStrength", config.getFarTerrainNoiseStrength());
            mat.setFloat("NoiseScale", config.getFarTerrainNoiseScale());
        }
        // Shared instances : update() mutates them in place so the sun / moon directions follow the cycle.
        mat.setVector3("LightDir", lightDir);
        mat.setVector3("MoonDirection", moonDir);
        // Lighting colours : placeholders here, rebound by reference in update() to the scene's live
        // AmbientLight / DirectionalLight colours so the far terrain is lit exactly like the voxels.
        mat.setColor("AmbientColor", ColorRGBA.White.mult(config.getAmbiantIntensity()));
        mat.setColor("SunColor", ColorRGBA.White.mult(config.getSunIntensity()));
        // Sea reflection : placeholders, rebound/overwritten in update() (sky colours + tuning copied
        // from the calm-water material, the single source of truth).
        mat.setColor("SkyColor", new ColorRGBA(0.25f, 0.55f, 1f, 1f));
        mat.setColor("SkyHorizonColor", new ColorRGBA(0.7f, 0.82f, 1f, 1f));
        mat.setFloat("ReflectionStrength", 0f);
        mat.setFloat("FresnelPower", 5f);
        mat.setFloat("GlintPower", 220f);
        mat.setFloat("GlintStrength", 2.2f);
        mat.setColor("MoonColor", new ColorRGBA(0.55f, 0.62f, 0.78f, 1f));
        mat.setFloat("MoonGlintStrength", 0.6f);
        return mat;
    }

    /**
     * Builds the forest-density tint map (a low-res field of the same seamless forest noise that scatters
     * the trees) and binds it plus the tint parameters to the material. The shader darkens the distant
     * grass slopes where this field is high, so the beyond-billboard woods read as dark-green relief. Only
     * the noise generator carries a forest field ; for the others the FOREST_TINT define stays off.
     */
    private void bindForestTint(TerrainGenerator generator, float step) {
        if (!(generator instanceof NoiseTerrainGenerator)) {
            return;
        }
        NoiseTerrainGenerator noise = (NoiseTerrainGenerator) generator;
        material.setTexture("ForestDensityMap", createForestDensityTexture(noise, step));
        material.setColor("ForestTintColor", config.getForestTintColor());
        material.setFloat("ForestTintStrength", config.getForestTintStrength());
        // Ramp the tint in where the billboards thin out, so the two layers don't double-darken the slopes.
        material.setFloat("ForestTintStart", config.getFarTreeDistance());
        material.setFloat("Extent", extent);
        material.setVector2("ForestOrigin", forestOrigin); // mutated in place in update() on tile snaps
        forestTintEnabled = true;
    }

    /**
     * Samples the forest-density field over the same origin-centered grid as the heightmap (local coords),
     * into a single-channel-as-RGBA8 texture covering the whole extent. The field is periodic with the
     * world size, so on the torus the tile snap (a multiple of that period) keeps it aligned.
     */
    private Texture2D createForestDensityTexture(NoiseTerrainGenerator noise, float step) {
        int n = FOREST_DENSITY_SIZE;
        // Map the n texels across the same world span the heightmap covers (HEIGHTMAP_SIZE-1 cells of step).
        float worldStep = step * (HEIGHTMAP_SIZE - 1) / (float) n;
        float half = n / 2f;
        ByteBuffer data = BufferUtils.createByteBuffer(n * n * 4);
        for (int j = 0; j < n; j++) {
            float worldZ = (j - half) * worldStep;
            for (int i = 0; i < n; i++) {
                float worldX = (i - half) * worldStep;
                float d = noise.getForestDensity(worldX, worldZ);
                byte b = (byte) Math.round(Math.max(0f, Math.min(1f, d)) * 255f);
                data.put(b).put(b).put(b).put((byte) 0xFF);
            }
        }
        data.flip();
        Image img = new Image(Image.Format.RGBA8, n, n, data, ColorSpace.Linear);
        Texture2D tex = new Texture2D(img);
        tex.setMagFilter(Texture.MagFilter.Bilinear);
        tex.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
        return tex;
    }

    /**
     * Builds the closed box that seals the world from below (open top : the heightfield is the lid).
     * Four walls + a floor of plain inward-facing opaque quads, so no ray can reach the sky through the
     * transparent water from below the surface. The Y extents are baked into the mesh in WORLD units, so
     * the camera-follow in update() only shifts X/Z and the box stays at the right height (walls just
     * under the flattened far-sea level, floor below the deepest relief).
     */
    private Geometry createEnclosure(float[] heightmap, float step) {
        float offset = config.getFarTerrainVerticalOffset();
        // Walls top out exactly at the flattened far-sea level (worldPos.y = WaterHeight + HeightOffset,
        // see FarTerrain.vert) : they seam seamlessly with the far sea surface AND reach at least the
        // near voxel water level, so an underwater horizontal ray is always stopped before the horizon.
        float wallTopY = config.getWaterHeight() + offset;
        // Floor below the deepest relief AND below the world bottom (Y=0, the lowest a player can dig),
        // with a chunk-size margin so even the deepest valley/pit is comfortably sealed.
        float minWorldY = offset;
        for (float h : heightmap) {
            float y = h * step + offset;
            if (y < minWorldY) {
                minWorldY = y;
            }
        }
        float floorY = Math.min(minWorldY, 0f) - config.getChunkSize();

        enclosureFloorY = floorY;
        enclosureWallTopY = wallTopY;

        float half = config.getChunkSize() * config.getGridRadius();

        Geometry geom = new Geometry("FarTerrainEnclosure", buildEnclosureMesh(half, floorY, wallTopY));

        Material mat = new Material(app.getAssetManager(), "Shaders/Enclosure.j3md");
        mat.setBoolean("ManualSrgb", config.isManualGammaEncode());
        // Opaque deep-water blue (calmWaterColor is the deep-sea source, but authored with alpha 0.5 for
        // the transparent near water -- force it opaque here, this box is a solid backdrop, not water).
        ColorRGBA deep = config.getSkyColor();
        mat.setColor("Color", new ColorRGBA(deep.r, deep.g, deep.b, 1.0f));
        // Slightly more clip-space depth push-back than the far terrain, so the enclosure is always the
        // backstop BEHIND both the voxels and the far terrain (it only shows through genuine gaps).
        // Without it the unbiased box won the depth test against the depth-biased far sea and occluded it.
        mat.setFloat("DepthBias", config.getFarTerrainDepthBias() + 0.01f);
        // Inward-facing : the camera is always inside the box, so render both sides (only 5 quads).
        mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        geom.setMaterial(mat);

        geom.setShadowMode(RenderQueue.ShadowMode.Off);
        // The box always encloses the camera, so its bounds straddle the frustum : never cull it.
        geom.setCullHint(Spatial.CullHint.Never);
        Vector3f camLoc = app.getCamera().getLocation();
        geom.setLocalTranslation(camLoc.x, ENCLOSURE_OFFSET, camLoc.z);
        return geom;
    }

    /**
     * Five inward-facing quads (4 walls + floor, open top) spanning [-half, +half] in X/Z, from floorY
     * to topY. Positions only : the material is unshaded flat colour, so no normals / texcoords needed.
     * Winding is irrelevant (face culling is Off).
     */
    private Mesh buildEnclosureMesh(float half, float floorY, float topY) {
        float[] pos = {
                // Floor (y = floorY)
                -half, floorY, -half,   half, floorY, -half,   half, floorY, half,   -half, floorY, half,
                // West wall (x = -half)
                -half, floorY, -half,   -half, floorY, half,   -half, topY, half,    -half, topY, -half,
                // East wall (x = +half)
                half, floorY, -half,    half, floorY, half,    half, topY, half,     half, topY, -half,
                // North wall (z = -half)
                -half, floorY, -half,   half, floorY, -half,   half, topY, -half,    -half, topY, -half,
                // South wall (z = +half)
                -half, floorY, half,    half, floorY, half,    half, topY, half,     -half, topY, half
        };
        short[] idx = {
                0, 1, 2, 0, 2, 3,        // floor
                4, 5, 6, 4, 6, 7,        // west
                8, 9, 10, 8, 10, 11,     // east
                12, 13, 14, 12, 14, 15,  // north
                16, 17, 18, 16, 18, 19   // south
        };
        Mesh mesh = new Mesh();
        mesh.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(pos));
        mesh.setBuffer(VertexBuffer.Type.Index, 3, BufferUtils.createShortBuffer(idx));
        mesh.updateBound();
        return mesh;
    }

    @Override
    protected void cleanup(Application application) {
        if (config.getChunkManager() != null) {
            config.getChunkManager().removeListener(this);
        }
        // nothing else to release : the terrain mesh is GC'd with this state
    }

    @Override
    protected void onEnable() {
        Node node = app.getRootNode();
        if (terrain != null && terrain.getParent() == null) {
            node.attachChild(terrain);
        }
        if (enclosure != null && enclosure.getParent() == null) {
            node.attachChild(enclosure);
        }
    }

    @Override
    protected void onDisable() {
        if (terrain != null && terrain.getParent() != null) {
            terrain.removeFromParent();
        }
        if (enclosure != null && enclosure.getParent() != null) {
            enclosure.removeFromParent();
        }
    }

    /**
     * Adapts the far terrain to a change of render distance (voxel {@code gridRadius}) : the shader's
     * inner-radius discard (where the far terrain starts, just beyond the loaded chunks) and the
     * enclosure box half-size both scale with the render distance and must be refreshed.
     */
    public void onRenderDistanceChanged() {
        if (material != null) {
            material.setFloat("InnerRadius",
                    (float) config.getGridRadius() * config.getChunkSize() - config.getChunkSize());
        }
        if (enclosure != null) {
            float half = config.getChunkSize() * config.getGridRadius();
            enclosure.setMesh(buildEnclosureMesh(half, enclosureFloorY, enclosureWallTopY));
            enclosure.updateModelBound();
        }
    }

    @Override
    public void update(float tpf) {
        // Finite (torus) world : follow the player so the horizon is always around them. The relief is
        // periodic with the world size, so snapping the mesh to the nearest multiple of that period
        // keeps it perfectly aligned with the voxels -- a pure translation, no heightmap regeneration.
        // The shader's inner-radius discard and fog are camera-relative, so they stay correct too.
        if (terrain != null) {
            float w = config.getWorldSize();
            Vector3f p = config.getPlayerLocation();
            if (w > 0f && p != null) {
                float sx = Math.round(p.x / w) * w;
                float sz = Math.round(p.z / w) * w;
                if (sx != tileOffsetX || sz != tileOffsetZ) {
                    tileOffsetX = sx;
                    tileOffsetZ = sz;
                    terrain.setLocalTranslation(sx, config.getFarTerrainVerticalOffset(), sz);
                    // Keep the (periodic) density map aligned with the snapped terrain : the snap is a
                    // multiple of the world period, so the field repeats onto the new tile exactly.
                    if (forestTintEnabled) {
                        forestOrigin.set(sx, sz);
                    }
                }
            }
        }

        // The enclosure follows the CAMERA (not the world tile) at a radius within the far clip plane,
        // so its walls are never clipped and seal the horizon in every direction (a world-edge box would
        // sit beyond the far plane). Y is baked into the mesh, so only shift X/Z.
        if (enclosure != null) {
            Vector3f cam = app.getCamera().getLocation();
            enclosure.setLocalTranslation(cam.x, ENCLOSURE_OFFSET, cam.z);
        }

        // Keep the far terrain lit by the same sun as the world (day/night cycle). The material holds
        // the lightDir instance by reference, so mutating it in place updates the shader uniform.
        LightingState lighting = getState(LightingState.class);
        if (lighting != null && lighting.getDirectionalLight() != null) {
            lightDir.set(lighting.getDirectionalLight().getDirection());

            // Bind the ambient / sun colours to the scene's live light colours (mutated in place by
            // SunControl as time passes). Bound once : the shared references then track the day/night
            // cycle automatically, so the far terrain dims and tints exactly like the voxels.
            if (!lightColorsBound && material != null) {
                material.setColor("AmbientColor", lighting.getAmbientLight().getColor());
                material.setColor("SunColor", lighting.getDirectionalLight().getColor());
                lightColorsBound = true;
            }
        }

        // Moon direction (towards the moon), mutated in place so the sea's moon glint tracks it.
        if (moonControl == null) {
            MoonState moonState = getState(MoonState.class);
            if (moonState != null) {
                moonControl = moonState.getMoonControl();
            }
        }
        if (moonControl != null) {
            moonDir.set(moonControl.getPosition()).normalizeLocal();
        }

        SkyState sky = getState(SkyState.class);
        SkyControl skyControl = (sky != null) ? sky.getSkyControl() : null;
        if (skyControl != null && material != null) {
            // Bind the fog colour to SkyControl's live ground colour (mutated in place as time passes),
            // so the terrain/sea fade into the exact current ground colour at the horizon. Bound once.
            if (!fogColorBound) {
                material.setColor("FogColor", skyControl.getGroundColor());
                fogColorBound = true;
            }
            // Same live ground colour for the enclosure backstop : it sits behind the far terrain at the
            // horizon, so it must fade and darken identically (bound once, then tracked by reference).
            if (!enclosureColorBound && enclosure != null) {
                enclosure.getMaterial().setColor("Color", skyControl.getGroundColor());
                enclosureColorBound = true;
            }
            // Sea reflection sky colours, shared with the calm-water shader (WaterState) so near and far
            // water reflect the same sky. The overhead colour is a product (sky hue * day/night
            // multiplier), refreshed each frame into a held instance ; the horizon colour is the live
            // ground colour, bound by reference. Both are bound once, then tracked automatically.
            skyControl.getReflectionSkyColor(reflectionSkyColor);
            if (!reflectionBound) {
                material.setColor("SkyColor", reflectionSkyColor);
                material.setColor("SkyHorizonColor", skyControl.getGroundColor());
                reflectionBound = true;
            }
        }

        // Copy the reflection tuning from the calm-water material once it exists, so near and far water
        // use identical strength / glint / Fresnel / moon (the calm-water j3m stays the single source).
        if (!reflectionTuningCopied && material != null) {
            ChunkMeshGenerator generator = BlocksConfig.getInstance().getChunkMeshGenerator();
            if (generator instanceof FacesMeshGenerator) {
                Material w = ((FacesMeshGenerator) generator).getCalmWaterMaterial();
                material.setFloat("ReflectionStrength", w.getParamValue("ReflectionStrength"));
                material.setFloat("FresnelPower", w.getParamValue("FresnelPower"));
                material.setFloat("GlintPower", w.getParamValue("GlintPower"));
                material.setFloat("GlintStrength", w.getParamValue("GlintStrength"));
                material.setColor("MoonColor", w.getParamValue("MoonColor"));
                material.setFloat("MoonGlintStrength", w.getParamValue("MoonGlintStrength"));
                reflectionTuningCopied = true;
            }
        }
    }
}
