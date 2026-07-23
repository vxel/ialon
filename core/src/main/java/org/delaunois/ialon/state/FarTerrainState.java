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
import com.jme3.math.Vector4f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.terrain.geomipmap.NormalRecalcControl;
import com.jme3.terrain.geomipmap.TerrainQuad;
import com.jme3.terrain.geomipmap.lodcalc.DistanceLodCalculator;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.Chunk;
import org.delaunois.ialon.blocks.ChunkManager;
import org.delaunois.ialon.blocks.ChunkManagerListener;
import org.delaunois.ialon.blocks.ChunkMeshGenerator;
import org.delaunois.ialon.blocks.FacesMeshGenerator;
import org.delaunois.ialon.blocks.WorldEditOverlay;
import org.delaunois.ialon.blocks.WorldManager;
import org.delaunois.ialon.blocks.shapes.Liquid;
import org.delaunois.ialon.blocks.generator.NoiseTerrainGenerator;
import org.delaunois.ialon.blocks.generator.TerrainGenerator;
import org.delaunois.ialon.control.SkyControl;
import org.delaunois.ialon.control.ThrottledTerrainLodControl;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * <p>The distant world is rendered as a terrain + a water plane, the standard split :
 * <ul>
 *   <li>the <b>relief</b> ({@link #terrain}) : ONE mesh of the TRUE relief -- the real land ABOVE the
 *       water line and the real sea floor BELOW it, coloured by altitude (land palette above, sea-floor
 *       palette tinted by the water column below) and lit by the real normal. Opaque and SINGLE-sided
 *       (only ever seen from above), so its under-side is never drawn -- a submerged eye never sees a
 *       peak's interior "through" the surface (the artifact the old flatten/double-side produced) ; and</li>
 *   <li>the <b>water</b> ({@link #water}) : a flat, SEMI-TRANSPARENT blue plane at the water line,
 *       detached from the relief, with the sky/sun/moon reflection -- the same water body / tuning as the
 *       near voxel calm water, so near &amp; far read as one sea. Double-sided (the reflective surface from
 *       above, the blue lid from below), its alpha ramping to opaque at the far horizon (no sky leak).</li>
 * </ul>
 *
 * <p>Four {@link #enclosure} walls sit far out at the horizon ({@link #ENCLOSURE_RADIUS}) as a last-resort
 * failsafe : they stop a near-horizontal ray from reaching the sky through the transparent water in open
 * ocean (no coast in sight, where the sea floor dips below the sightline). Open top AND bottom -- the
 * water plane is the lid, the relief sea floor is the floor -- they follow the <b>camera</b> at a radius
 * safely inside the far clip plane (a world-edge box would sit beyond the far plane and be clipped away).
 */
@Slf4j
public class FarTerrainState extends BaseAppState implements ChunkManagerListener {

    // Heightmap resolution : must be (2^n + 1). 513 -> 513x513 samples, i.e. a 16-world-unit step on the
    // 8192 extent (one chunk per sample).
    //
    // DO NOT LOWER THIS to cut the horizon's cost -- use PATCH_SIZE below instead. Halving it to 257 was
    // tried and reverted : a 32-unit step resolves a steep coast into a SINGLE near-vertical cell whose
    // normal faces away from the sun, which renders as a black wedge at the water line (reproduced by the
    // 'shore' vantage of ScreenshotHarness, 360k pixels changed ; the same vantage shows 0 at 513). It is
    // the sample step that does this, not the shore machinery -- forcing farTerrainWaterlineClearance to 0
    // leaves the wedge untouched. Two other things are sized off this constant and would silently coarsen
    // with it : the shore-clip field (see shoreFieldImage) and FarTreeState's billboard grounding grid.
    // Public because FarTreeState grounds its billboards on this same grid and must not duplicate the value.
    public static final int HEIGHTMAP_SIZE = 513;
    // Patch size : must be (2^m + 1) and <= HEIGHTMAP_SIZE. Drives the LOD granularity AND the draw count :
    // TerrainQuad splits the map into ((HEIGHTMAP_SIZE-1)/(PATCH_SIZE-1))^2 patches, one draw each.
    //
    // 129 (4x4 = 16 patches instead of 8x8 = 64) was measured twice and rejected twice. It is tempting --
    // it removes 12 to 17 draws -- so here is why it does not pay, to save the third attempt :
    //
    //  - AS IS, it costs 70k to 112k triangles ('island' 34k -> 121k, 'shore' 42k -> 154k). LOD_PATCHES_-
    //    PER_LEVEL below is counted in patch WIDTHS, so doubling the patch also doubles the distance that
    //    buys a LOD level (1040 -> 2064 world units) : far less of the visible range is ever decimated.
    //  - With that compensated (multiplier halved, same world-unit steps) the numbers do look better on
    //    both axes -- 'shore' 39 -> 27 draws AND 42k -> 35k triangles. But it is NOT free : 36% of the
    //    frame's pixels change, and visibly for the worse. A patch is the unit that picks a LOD level, and
    //    a 2064-unit patch takes its level from the distance of its CENTRE -- so the nearest patch
    //    decimates the relief right in front of the camera. Coastlines lose their indentations and
    //    mountains turn into large flat facets.
    //
    // The draw saving is real but it is bought with horizon quality, not with nothing. Left at 65.
    private static final int PATCH_SIZE = 65;
    // How many patch-widths of distance buy one LOD level, replacing jME's stock 2.7.
    //
    // DistanceLodCalculator drops a patch to LOD i at distance size*multiplier*(i+1)*worldScale, and a
    // patch spans size*worldScale -- the terrain scale cancels, so this multiplier IS a count of patch
    // widths. The stock 2.7 puts the first drop 2.7 patches out, but the far terrain is only ~4 patches
    // deep in the frustum (far clip 4000, patch span 65*16 = 1040), so in practice NOTHING ever left LOD 0
    // : every drawn patch cost a full 8463 triangles, 91% of the frame's geometry on the 'shore' vantage.
    //
    // 1 patch width per level is the natural floor : two side-by-side patches are then at most one level
    // apart, which is what TerrainQuad's edge stitching is built to absorb. Going below this makes
    // neighbours differ by two or more levels and risks cracks along the patch seams.
    private static final float LOD_PATCHES_PER_LEVEL = 1f;
    // Water-plane grid size (2^n + 1) : the water is FLAT, so a coarse grid is enough -- it only needs
    // enough vertices for the interpolated fog to read smoothly across the extent. No heightmap, no LOD.
    //
    // 65 puts the cells at extent/64 = 128 world units, an order of magnitude coarser than the relief's 16.
    // That asymmetry was suspected of causing the Android-only coastline wobble (a 128-unit triangle seen
    // in grazing view spans ~1000 screen pixels, with vertices projected off-screen, and depth plane-
    // equation setup / guard-band clipping in that regime is where mobile GPUs diverge from desktop ones).
    // Tested at 257 (32-unit cells, 16x the triangle setup) on device : it did NOT fix the wobble, so the
    // theory is disproven and the grid stays coarse and cheap. The wobble is handled by the analytic shore
    // clip instead -- see shoreFieldImage.
    private static final int WATER_GRID_SIZE = 65;
    // Forest-density tint map resolution (single channel, covers the whole extent).
    private static final int FOREST_DENSITY_SIZE = 256;
    // Biome colour map resolution (RGB grass-band colour per biome, covers the whole extent).
    private static final int BIOME_MAP_SIZE = 256;

    private static final float ENCLOSURE_OFFSET = 0f;

    // Horizon-seal wall radius (world units), camera-relative. Pushed FAR out (was chunkSize*gridRadius,
    // right at the loaded region) so it no longer occludes the sea-floor surface : the seabed is now the
    // visible sea floor, the walls are only a last-resort failsafe against a sky leak through the near
    // transparent water at the true horizon (open ocean, no coast in sight). Kept well inside the game far
    // clip (4000) : the square's corner (radius*sqrt(2)) must not cross it or the wall is clipped away.
    private static final float ENCLOSURE_RADIUS = 2500f;

    private final IalonConfig config;
    @Getter
    private final float extent;

    private SimpleApplication app;

    // The RELIEF : a single mesh of the TRUE relief (real land ABOVE the water and real sea floor BELOW
    // it), coloured by altitude and lit by the real normal. Opaque and single-sided (only ever seen from
    // above), so no under-side is ever drawn -- a submerged eye never sees a peak's interior. The old
    // "flatten the sea" surface and the separate seabed mesh are merged into this one relief.
    @Getter
    private TerrainQuad terrain;

    // The WATER : a flat, SEMI-TRANSPARENT blue plane at the water line, DETACHED from the relief, with the
    // sky/sun/moon reflection -- the same water body as the near voxel calm water, so near & far read as
    // one sea. Rendered double-sided (seen from above AND below) in the Transparent bucket ; its alpha
    // ramps from clear near the eye to opaque at the far horizon (no sky leak at the edge). Flat, so a
    // cheap coarse grid (no heightmap, no LOD).
    @Getter
    private Geometry water;
    private Material waterMaterial;

    // The far-terrain LOD machinery is dropped after a short warmup (-1 = done). The LOD recompute and
    // jME's auto-added NormalRecalcControl both run on the MAIN thread on every LOD change (the dominant
    // updateLogicalState hitch while moving — confirmed by an on-device A/B). On a distant, periodic
    // backdrop that follows the player, the LOD + normals can stay frozen at their warmup-computed values
    // (imperceptible), so once computed we remove both controls and pay that cost zero further times.
    private int farLodWarmup = 0;

    // The procedural heightmap (HEIGHTMAP_SIZE^2, heights pre-divided by step) sampled at init. Kept so the
    // minimap (MinimapState) can reuse it for its top-down view instead of re-sampling the generator.
    @Getter
    private float[] heightmap;

    // SHORE CLIP field, bound to the far WATER material : the same heightmap, as a texture, encoded as a
    // signed height RELATIVE to the water line. It lets the water plane cut its own shoreline analytically
    // (see Water.frag SHORE_CLIP) instead of having it emerge from a depth-test tie against the relief.
    //
    // That tie was the bug : the two surfaces meet at a very grazing angle, so the visible coastline was
    // hypersensitive to any per-pixel depth error, and moved with the camera (rock steady at rest) on
    // Android while staying clean on the desktop. Depth-buffer precision was ruled out (24 bits on both,
    // ~0.007 units of depth step at 130 units against a 2-unit separation), as were shader precision,
    // polygon offset and the clip-space bias. Rather than keep hunting the driver-side mechanism, this
    // takes the decision away from the depth test entirely : the coastline becomes a contour of the height
    // FIELD, evaluated per fragment from the same numbers the relief mesh is built from, so it is as stable
    // as the terrain itself.
    //
    // One texel per heightmap sample (HEIGHTMAP_SIZE^2, single channel as RGBA8 -- the format already proven
    // on the device by the forest/biome maps, ~1 MB). Same resolution as the mesh means the field's bilinear
    // interpolation and the mesh's linear interpolation agree along cell edges, so the clip lands where the
    // relief actually crosses the plane rather than a fraction of a cell away.
    private static final float SHORE_FIELD_RANGE = 8f; // world units encoded, centred on the water line
    private Image shoreFieldImage;
    private ByteBuffer shoreFieldData;
    // The field's world origin (the tile snap), shared BY REFERENCE with the water material like
    // forestOrigin is with the relief material, so a snap needs no re-upload.
    private final Vector2f shoreFieldOrigin = new Vector2f();

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
    // Sun direction, shared by reference with the RELIEF material and refreshed each frame from LightingState.
    private final Vector3f lightDir = new Vector3f(-0.5f, -0.7f, -0.5f).normalizeLocal();
    // The fog colour is bound (once) to SkyControl's live ground colour so it follows the day/night cycle.
    private boolean fogColorBound = false;
    // The enclosure backstop colour is bound (once) to the same live ground colour as the fog, so the box
    // matches the horizon haze and darkens with the day/night cycle (otherwise it stays bright at night).
    private boolean enclosureColorBound = false;
    // The ambient / sun colours are bound (once) to LightingState's live light colours (mutated in place
    // by SunControl) so the far terrain dims and tints with the day/night cycle like the voxels.
    private boolean lightColorsBound = false;
    // True while the distant horizon is hidden because the eye is inside the ground (see update()).
    private boolean hiddenUnderSurface = false;
    // The far water plane uses the SAME shader/material as the near voxel calm water : its dynamic
    // reflection uniforms (sun/sky/moon) are copied from that material each frame (see update()), so near
    // & far water reflect identically. The near-water material is the single source (managed by WaterState).
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
    @Getter
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
        this.heightmap = heightmap;

        // The RELIEF : one mesh of the true relief (land + sea floor). Single-sided, opaque (see below).
        terrain = new TerrainQuad("FarTerrain", PATCH_SIZE, HEIGHTMAP_SIZE, heightmap);
        material = createMaterial();
        bindProceduralMaps(generator, step);
        terrain.setMaterial(material);
        // Uniform scale : heights were pre-divided by step (see sampleHeightmap), so world Y is exact
        // again after scaling, AND a uniform scale keeps the terrain normals correct in world space.
        terrain.setLocalScale(step, step, step);
        // The far terrain spans the whole island, including under the loaded voxel chunks. Sinking it
        // keeps it buried inside the solid voxel volume near the player (so the real chunks hide it /
        // avoid z-fighting), while it still rises into view as the horizon beyond the chunks.
        terrain.setLocalTranslation(0f, config.getFarTerrainVerticalOffset(), 0f);
        terrain.setShadowMode(RenderQueue.ShadowMode.Off);

        // Distance-based LOD : reduces far patches' triangle count. Throttled so the LOD recompute (which
        // runs on the main thread inside updateLogicalState) only fires every so many units of travel,
        // instead of on every camera move -- otherwise it causes intermittent movement hitches.
        ThrottledTerrainLodControl lodControl = new ThrottledTerrainLodControl(terrain, app.getCamera());
        lodControl.setLodCalculator(new DistanceLodCalculator(PATCH_SIZE, LOD_PATCHES_PER_LEVEL));
        terrain.addControl(lodControl);

        // The WATER plane : a flat, semi-transparent water surface at the water line (see the water field
        // doc). Being FLAT it needs no heightmap / LOD -- a COARSE grid (WATER_GRID_SIZE) scaled to span
        // the whole extent, with just enough vertices for the wave animation to read smoothly. Translated
        // to exactly the near voxel water level (waterHeight, NOT the relief's buried +offset) so near &
        // far water are coplanar and seamless at the InnerRadius junction.
        //
        // A plain Geometry, NOT a TerrainQuad : a TerrainQuad splits itself into patches whatever its size
        // (it was measured on device emitting FarWaterPatch1..4, four draw calls of ~2191 triangles), and
        // every reason for those patches -- per-patch LOD, per-patch culling -- is moot on a flat plane
        // that is always entirely in view. One geometry, same vertices, same material : four draws become
        // one. The grid matches TerrainQuad's own layout (centred on the origin, unit spacing) so the
        // scale and translation below are unchanged.
        float waterScale = extent / (WATER_GRID_SIZE - 1);
        water = new Geometry("FarWater", createGridMesh(WATER_GRID_SIZE));
        waterMaterial = createWaterMaterial();
        water.setMaterial(waterMaterial);
        water.setLocalScale(waterScale, waterScale, waterScale);
        water.setLocalTranslation(0f, waterSurfaceY(), 0f);
        water.setShadowMode(RenderQueue.ShadowMode.Off);
        // Transparent : the water is a semi-transparent blue plane, so it must render after the opaque
        // relief and blend over the sea floor / sky seen through it (see createWaterMaterial).
        water.setQueueBucket(RenderQueue.Bucket.Transparent);

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
     * far relief lines up with the voxel terrain. The TRUE relief is kept everywhere, land and submerged
     * sea floor alike -- it is the {@link #terrain} relief mesh ; the flat sea is the separate
     * {@link #water} plane.
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
                float h = clampAcrossWaterline(generator.getHeight(sample.set(worldX, 0f, worldZ)));
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
        // Same waterline clearance the procedural samples get (see clampAcrossWaterline) : this is the
        // single funnel for player-edit overrides, so without it a reshaped shore would put samples back
        // onto the water plane and reintroduce the coastal z-fighting on that spot only.
        float clamped = clampAcrossWaterline(worldHeight);
        float hy = clamped / step;
        if (w > 0f) {
            int kx0 = Math.round((tileOffsetX - worldX) / w);
            int kz0 = Math.round((tileOffsetZ - worldZ) / w);
            for (int kx = kx0 - 1; kx <= kx0 + 1; kx++) {
                for (int kz = kz0 - 1; kz <= kz0 + 1; kz++) {
                    float lx = (worldX + kx * w) - tileOffsetX;
                    float lz = (worldZ + kz * w) - tileOffsetZ;
                    xz.add(new Vector2f(lx, lz));
                    heights.add(hy);
                    // Keep the analytic shoreline in step with the mesh on every tile copy, or a reshaped
                    // shore would keep its old water edge.
                    patchShoreField(lx, lz, clamped);
                }
            }
        } else {
            float lx = worldX - tileOffsetX;
            float lz = worldZ - tileOffsetZ;
            xz.add(new Vector2f(lx, lz));
            heights.add(hy);
            patchShoreField(lx, lz, clamped);
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

    /**
     * Material for the RELIEF mesh ({@link #terrain}) : the real relief, land above the water line + sea
     * floor below it, coloured by altitude and lit by the real normal. Opaque and SINGLE-sided (only ever
     * seen from above — its under-side is never drawn, so a submerged eye never sees a peak's interior).
     * No reflection (that belongs to the separate {@link #water} plane).
     */
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
        // Altitude palette. Below the water line : sand fringe -> dirt sea floor -> grey bedrock, tinted by
        // the water column (SeabedWaterColor = the same filter the submerged voxels get) so the distant
        // floor reads blue like the near voxel floor. Above : sand shore -> grass -> rock -> snow, the rock
        // & snow lines using the SAME ratios / world ceiling as NoiseTerrainGenerator so the tiers line up.
        mat.setColor("SeabedColor", config.getFarTerrainSeabedColor());   // dirt sea floor
        mat.setColor("BedrockColor", config.getFarTerrainBedrockColor()); // grey deep-floor bedrock
        ColorRGBA w = Chunk.WATER_COLOR_FILTER;
        mat.setColor("SeabedWaterColor", new ColorRGBA(w.r, w.g, w.b, 1f));
        mat.setColor("SandColor", config.getFarTerrainSandColor());
        mat.setColor("RockColor", config.getFarTerrainRockColor());       // bare mountain rock
        mat.setColor("SnowColor", config.getFarTerrainSnowColor());
        // The TRUE water line, i.e. the relief height at which the surface sits exactly on the flat water
        // plane (waterHeight + 0.5 + the liquid top offset, minus the mesh's vertical nudge) -- not the raw
        // waterHeight, which is a whole block lower and left the palette's shoreline off the geometry's.
        mat.setFloat("WaterHeight", waterlineHeight());
        // No relief sample sits within this of the water plane (clampAcrossWaterline), so the shader shifts
        // the land / sea-floor palette branches by it to keep the sand beach and the sand sea-floor fringe.
        mat.setFloat("WaterlineClearance", config.getFarTerrainWaterlineClearance());
        mat.setFloat("RockHeight", NoiseTerrainGenerator.ROCK_LINE_RATIO * config.getMaxy());
        mat.setFloat("SnowHeight", NoiseTerrainGenerator.SNOW_LINE_RATIO * config.getMaxy());
        // The terrain mesh is nudged vertically (localTranslation above) ; the shader undoes this offset
        // so the altitude palette compares against the generator's true block heights.
        mat.setFloat("HeightOffset", config.getFarTerrainVerticalOffset());
        // fBm brightness variation : breaks up the flat altitude palette so the distant land reads as
        // textured relief. Only set when enabled (>0), so the FAR_NOISE define (keyed on NoiseStrength)
        // compiles the noise out entirely otherwise.
        if (config.getFarTerrainNoiseStrength() > 0f) {
            mat.setFloat("NoiseStrength", config.getFarTerrainNoiseStrength());
            mat.setFloat("NoiseScale", config.getFarTerrainNoiseScale());
        }
        // Shared instance : update() mutates it in place so the sun direction follows the cycle.
        mat.setVector3("LightDir", lightDir);
        // Lighting colours : placeholders here, rebound by reference in update() to the scene's live
        // AmbientLight / DirectionalLight colours so the far terrain is lit exactly like the voxels.
        mat.setColor("AmbientColor", ColorRGBA.White.mult(config.getAmbiantIntensity()));
        mat.setColor("SunColor", ColorRGBA.White.mult(config.getSunIntensity()));
        // SINGLE-sided (cull back-faces) : the relief is only ever seen from ABOVE. Its under-side is never
        // drawn, so a submerged eye never sees a peak's interior "through" the water surface (the artifact
        // the old double-sided flatten produced) ; underwater, looking up shows the separate water plane.
        mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Back);
        return mat;
    }

    /**
     * Material for the flat {@link #water} plane : the SAME shader/material as the near voxel calm water
     * (Blocks/Textures/water_calm.j3m -> Blocks/Shaders/Water.*), so near &amp; far water are literally one
     * shader -- same waves, reflection and angle-based (Fresnel) transparency. A clone, so the far-specific
     * overrides below don't touch the near-water instance :
     * <ul>
     *   <li>{@code UseVertexColor = false} : the flat far plane has no per-vertex light packing, so the
     *       shader takes the water body colour from {@code Diffuse} (calmWaterColor) at open-sky light ;</li>
     *   <li>{@code InnerRadius} : discard within the loaded-chunk region (the near voxel water is the truth
     *       there) ;</li>
     *   <li>double-sided : the far plane is seen from above (reflective) AND below (the underwater lid).</li>
     * </ul>
     * The dynamic reflection uniforms (sun/sky/moon) are copied from the near-water material each frame in
     * {@link #update} ; all the static tuning (waves/glint/fresnel/blend/depth) comes from the shared j3m.
     */
    /**
     * World Y of the water SURFACE, matching the near voxel calm water exactly so the far plane is
     * coplanar with it (no step at the junction). Same formula as {@code UnderwaterState.liquidSurfaceY} :
     * {@code (blockY + 0.5 + topOffset(level))} -- the topmost water block sits at world grid Y
     * {@code waterHeight}, {@code +0.5} is its centre in world space, and {@link Liquid#topOffset(int)}
     * adds the liquid height above that centre. The block is a SOURCE, and {@link Block#getLiquidLevel()}
     * maps a source to {@link Block#LIQUID_LEVEL5}, so it does NOT fill the whole block (topOffset(5) = 0.3).
     */
    private float waterSurfaceY() {
        return config.getWaterHeight() + 0.5f + Liquid.topOffset(Block.LIQUID_LEVEL5);
    }

    /**
     * The relief height (in {@link TerrainGenerator#getHeight} units, i.e. BEFORE the
     * {@code farTerrainVerticalOffset} the relief mesh is translated by) at which the relief surface
     * would sit exactly on the flat far {@link #water} plane.
     */
    float waterlineHeight() { // package-private : asserted by FarTerrainWaterlineTest
        return waterSurfaceY() - config.getFarTerrainVerticalOffset();
    }

    /**
     * Keeps a relief sample clear of the flat far water plane, so the two surfaces never graze each other
     * around a coastline.
     *
     * <p>The relief is the true, continuous generator height, so around every shore it crosses the water
     * plane at a <b>very</b> grazing angle : with a 16-unit heightmap step, the band where the two surfaces
     * are less than a unit apart is hundreds of units wide on the ground and tens of pixels wide on screen.
     * Inside that band the visible coastline is whichever surface happens to win the depth test, which
     * makes it hypersensitive : on Android it visibly wobbled as the camera moved (and sat perfectly still
     * at rest). Not a depth-<i>resolution</i> problem — 24 bits are granted on device and resolve ~0.007
     * units at 130 units — but a tangency problem: a tiny depth error times a near-zero crossing slope is a
     * large horizontal displacement of the waterline.
     *
     * <p>Pushing every sample at least {@code farTerrainWaterlineClearance} clear of the plane — up if
     * it is land, down if it is sea floor — collapses that band to a single heightmap cell : the relief
     * now steps across the water line instead of grazing it. The side each sample falls on is
     * unchanged, so the coastline contour itself stays put (to within one 16-unit cell) ; only its
     * vertical profile becomes a small ledge, at the price of a slightly more angular coastline at high
     * clearances.
     *
     * <p>This narrows the sensitive band but cannot remove it — the crossing cell still contains a tangency.
     * The analytic shore clip ({@link #shoreFieldImage}) is what removes the depth test from the decision
     * there ; the two work together, and both are tuned by their own config knob.
     */
    float clampAcrossWaterline(float height) { // package-private : asserted by FarTerrainWaterlineTest
        float waterline = waterlineHeight();
        float clearance = config.getFarTerrainWaterlineClearance();
        if (clearance <= 0f) {
            return height;
        }
        return height >= waterline
                ? Math.max(height, waterline + clearance)
                : Math.min(height, waterline - clearance);
    }

    /**
     * Builds the {@link #shoreFieldImage shore-clip field} : one texel per heightmap sample, holding the
     * relief height RELATIVE to the water line, encoded into {@code [0,1]} over
     * {@code +/- SHORE_FIELD_RANGE/2} (so 0.5 is exactly the water line). Only the sign and a couple of
     * units around zero matter, which is why 8 bits over an 8-unit window is ample : that is ~0.03 units
     * per step, an order of magnitude finer than the clearance the clip works with.
     */
    private Texture2D createShoreFieldTexture(float[] heights, float step) {
        int n = HEIGHTMAP_SIZE;
        float waterline = waterlineHeight();
        shoreFieldData = BufferUtils.createByteBuffer(n * n * 4);
        for (int k = 0; k < n * n; k++) {
            // heights[] is pre-divided by step (see sampleHeightmap) : scale back to world units.
            byte b = encodeShoreHeight(heights[k] * step - waterline);
            shoreFieldData.put(b).put(b).put(b).put((byte) 0xFF);
        }
        shoreFieldData.flip();
        shoreFieldImage = new Image(Image.Format.RGBA8, n, n, shoreFieldData, ColorSpace.Linear);
        Texture2D tex = new Texture2D(shoreFieldImage);
        tex.setMagFilter(Texture.MagFilter.Bilinear);
        tex.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
        // The water plane spans exactly the field's extent, but clamp anyway : a wrap at the border would
        // teleport the clip to the opposite shore.
        tex.setWrap(Texture.WrapMode.EdgeClamp);
        return tex;
    }

    /** Encodes a height relative to the water line into the shore field's 8-bit range. */
    private byte encodeShoreHeight(float relativeHeight) {
        float unit = relativeHeight / SHORE_FIELD_RANGE + 0.5f;
        return unitToByte(Math.max(0f, Math.min(1f, unit)));
    }

    /**
     * Re-encodes one shore-field texel after a player edit changed that heightmap sample, so the analytic
     * shoreline follows a reshaped shore instead of going stale. {@code localX/localZ} are terrain-local
     * (the coordinates {@link #addCopies} feeds to {@code setHeight}) and {@code worldHeight} is in world
     * units, already clamped across the water line.
     */
    private void patchShoreField(float localX, float localZ, float worldHeight) {
        if (shoreFieldImage == null) {
            return;
        }
        int n = HEIGHTMAP_SIZE;
        float half = (n - 1) / 2f;
        int i = Math.round(localX / step + half);
        int j = Math.round(localZ / step + half);
        if (i < 0 || i >= n || j < 0 || j >= n) {
            return; // out-of-grid tile copy : setHeight culls these too
        }
        byte b = encodeShoreHeight(worldHeight - waterlineHeight());
        int at = (j * n + i) * 4;
        shoreFieldData.put(at, b).put(at + 1, b).put(at + 2, b);
        shoreFieldImage.setUpdateNeeded();
    }

    /**
     * Builds the far water plane's flat grid : {@code n x n} vertices at unit spacing, centred on the
     * origin — laid out exactly as {@link TerrainQuad} lays out a terrain of the same size, so the scale
     * and translation applied by the caller carry over unchanged.
     *
     * <p>Positions only. {@code Water.vert} reads {@code inPosition}, and {@code inColor} solely when
     * {@code UseVertexColor} is on (the far plane leaves it off) : normals and texture coordinates would
     * be dead weight, since the waves are a world-XZ noise evaluated per fragment, not mesh detail.
     */
    private static Mesh createGridMesh(int n) {
        float half = (n - 1) * 0.5f;
        FloatBuffer positions = BufferUtils.createFloatBuffer(n * n * 3);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                positions.put(i - half).put(0f).put(j - half);
            }
        }
        positions.flip();

        ShortBuffer indices = BufferUtils.createShortBuffer((n - 1) * (n - 1) * 6);
        for (int j = 0; j < n - 1; j++) {
            for (int i = 0; i < n - 1; i++) {
                short a = (short) (j * n + i);
                short b = (short) (a + 1);
                short c = (short) (a + n);
                short d = (short) (c + 1);
                indices.put(a).put(c).put(b);
                indices.put(b).put(c).put(d);
            }
        }
        indices.flip();

        Mesh mesh = new Mesh();
        mesh.setBuffer(VertexBuffer.Type.Position, 3, positions);
        mesh.setBuffer(VertexBuffer.Type.Index, 3, indices);
        mesh.updateBound();
        return mesh;
    }

    private Material createWaterMaterial() {
        Material mat = app.getAssetManager().loadMaterial("Blocks/Textures/water_calm.j3m").clone();
        mat.setBoolean("ManualSrgb", config.isManualGammaEncode());
        mat.setBoolean("UseVertexColor", false);
        // FAR plane : enables the loaded-region discard AND the distance-based transparency law (see the
        // FAR_WATER branch in Water.frag) -- transparent nearby to show the relief, opaque haze at the
        // far horizon. The near voxel water leaves this off and uses the angle-based (Fresnel) law.
        mat.setBoolean("FarWater", true);
        ColorRGBA deep = config.getCalmWaterColor();
        mat.setColor("Diffuse", new ColorRGBA(deep.r, deep.g, deep.b, deep.a));
        mat.setFloat("InnerRadius",
                (float) config.getGridRadius() * config.getChunkSize() - config.getChunkSize());
        // SHORE CLIP : hand the water plane the relief's own height field so it cuts its coastline from it
        // analytically (see the shoreFieldImage doc, and SHORE_CLIP in Water.frag). Binding the texture is
        // what switches the define on, so a zero margin compiles the whole thing out for an easy A/B.
        float shoreMargin = config.getFarWaterShoreMargin();
        if (shoreMargin > 0f && heightmap != null) {
            mat.setTexture("HeightMap", createShoreFieldTexture(heightmap, step));
            mat.setVector2("HeightMapOrigin", shoreFieldOrigin); // mutated in place in update() on tile snaps
            mat.setFloat("HeightMapSize", HEIGHTMAP_SIZE);
            mat.setFloat("HeightMapRange", SHORE_FIELD_RANGE);
            mat.setFloat("ShoreMargin", shoreMargin);
            mat.setFloat("Extent", extent);
        }
        // Distance fog : fade to the horizon haze with distance, matching the RELIEF (same distance/density),
        // so distant water and land recede into the same haze. FogColor is a placeholder here, rebound to
        // SkyControl's live ground colour each frame in update() (like the relief).
        ColorRGBA ground = config.getGroundDayColor();
        mat.setColor("FogColor", new ColorRGBA(ground.r, ground.g, ground.b, 1f));
        mat.setFloat("FogDistance", config.getFarTerrainFogDistance());
        mat.setFloat("FogDensity", config.getFarTerrainFogDensity());
        // Match the RELIEF's clip-space depth bias so the two are compared by true depth : higher land
        // occludes the flat water, instead of the relief's own bias making the water win and cover it.
        mat.setFloat("DepthBias", config.getFarTerrainDepthBias());
        // Progressive opacity with (horizontal) distance -- same value as the near water, so the surface
        // fades to opaque consistently across the near/far junction.
        mat.setFloat("OpaqueDistance", config.getWaterOpaqueDistance());
        mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        // Clear the near-water PolyOffset : the near voxel water nudges itself TOWARDS the camera to win
        // over the voxel side-faces at the seam, but on the far plane that forward nudge makes the water
        // win the depth test over the distant RELIEF and draw (semi-transparent) OVER the mountains' lower
        // slopes -- "cutting" them at the water line. The far water has no voxel seam, so plain depth (no
        // offset) is correct : the relief that rises above the water occludes it, as it should.
        mat.getAdditionalRenderState().setPolyOffset(0f, 0f);
        return mat;
    }

    /**
     * Builds the forest-density tint map (a low-res field of the same seamless forest noise that scatters
     * the trees) and binds it plus the tint parameters to the material. The shader darkens the distant
     * grass slopes where this field is high, so the beyond-billboard woods read as dark-green relief. Only
     * the noise generator carries a forest field ; for the others the FOREST_TINT define stays off.
     */
    private void bindProceduralMaps(TerrainGenerator generator, float step) {
        if (!(generator instanceof NoiseTerrainGenerator)) {
            return;
        }
        NoiseTerrainGenerator noise = (NoiseTerrainGenerator) generator;
        material.setTexture("ForestDensityMap", createForestDensityTexture(noise, step));
        material.setColor("ForestTintColor", config.getForestTintColor());
        material.setFloat("ForestTintStrength", config.getForestTintStrength());
        // Ramp the tint in where the billboards thin out, so the two layers don't double-darken the slopes.
        material.setFloat("ForestTintStart", config.getFarTreeDistance());
        // Biome colour map : the grass band is tinted per biome (same source as the near voxels), so the
        // distant horizon reads with the same biome palette. Shares the Extent/ForestOrigin UV below.
        material.setTexture("BiomeMap", createBiomeColorTexture(noise, step, false));
        // Debug : -Dialon.noBiomeSeabed skips the per-biome seabed map, so the seabed falls back to the
        // flat dirt SeabedColor (the pre-fix behaviour) for an A/B on the desert-sea sand colour.
        if (!Boolean.getBoolean("ialon.noBiomeSeabed")) {
            material.setTexture("BiomeSeabedMap", createBiomeColorTexture(noise, step, true));
        }
        // Strata map : the generator's per-column rock & snow lines, so the distant tiers wander and
        // follow the climate exactly like the voxel ones. The slope term is NOT baked -- the shader
        // re-applies it from the relief normal, which varies per fragment.
        material.setTexture("StrataMap", createStrataTexture(noise, step));
        material.setFloat("WorldCeiling", config.getMaxy());
        material.setVector4("SlopeStrata", new Vector4f(
                NoiseTerrainGenerator.STRATA_SLOPE_SOIL,
                NoiseTerrainGenerator.STRATA_SLOPE_BARE,
                NoiseTerrainGenerator.STRATA_SNOW_SLOPE_RISE,
                NoiseTerrainGenerator.STRATA_ROCK_SLOPE_DROP));
        material.setVector3("SoilStrata", new Vector3f(noise.soilAltitudeLow(), noise.soilAltitudeHigh(),
                config.getWaterHeight() + NoiseTerrainGenerator.STRATA_ROCK_SLOPE_FLOOR));
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
     * Bakes a per-biome colour over the same origin-centered grid as the forest-density map. The biome
     * field is periodic with the world size (seamless on the torus), so the tile snap keeps it aligned.
     * Colours are LINEAR (like the m_BaseColor palette in the shader) ; the shader does the sRGB output
     * encode where needed (MANUAL_SRGB). {@code seabed} bakes the underwater sea-floor palette (used by the
     * seabed branch, so desert seas read sand) instead of the grass band (which replaces the land base).
     */
    private Texture2D createBiomeColorTexture(NoiseTerrainGenerator noise, float step, boolean seabed) {
        int n = BIOME_MAP_SIZE;
        float worldStep = step * (HEIGHTMAP_SIZE - 1) / (float) n;
        float half = n / 2f;
        ByteBuffer data = BufferUtils.createByteBuffer(n * n * 4);
        Vector2f sample = new Vector2f();
        ColorRGBA c = new ColorRGBA();
        for (int j = 0; j < n; j++) {
            float worldZ = (j - half) * worldStep;
            for (int i = 0; i < n; i++) {
                float worldX = (i - half) * worldStep;
                if (seabed) {
                    noise.biomeSeabedColorAt(worldX, worldZ, sample, c);
                } else {
                    noise.biomeColorAt(worldX, worldZ, sample, c);
                }
                data.put(unitToByte(c.r)).put(unitToByte(c.g)).put(unitToByte(c.b)).put((byte) 0xFF);
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
     * Bakes the generator's per-column rock &amp; snow lines over the same origin-centered grid as the biome
     * maps : R = snow line, G = rock line, each as a fraction of the world ceiling (so a byte holds the
     * whole range to within ~0.6 block -- far finer than the 4-block palette blend that consumes it). The
     * lines are a smooth, low-frequency field, hence safe to sample at map resolution and interpolate ;
     * the per-block slope term and the per-column dither are left to the shader / the voxels.
     */
    private Texture2D createStrataTexture(NoiseTerrainGenerator noise, float step) {
        int n = BIOME_MAP_SIZE;
        float worldStep = step * (HEIGHTMAP_SIZE - 1) / (float) n;
        float half = n / 2f;
        float ceiling = config.getMaxy();
        ByteBuffer data = BufferUtils.createByteBuffer(n * n * 4);
        Vector2f sample = new Vector2f();
        Vector2f lines = new Vector2f();
        for (int j = 0; j < n; j++) {
            float worldZ = (j - half) * worldStep;
            for (int i = 0; i < n; i++) {
                float worldX = (i - half) * worldStep;
                noise.strataLinesAt(worldX, worldZ, sample, lines);
                data.put(unitToByte(lines.x / ceiling)).put(unitToByte(lines.y / ceiling))
                        .put((byte) 0).put((byte) 0xFF);
            }
        }
        data.flip();
        Image img = new Image(Image.Format.RGBA8, n, n, data, ColorSpace.Linear);
        Texture2D tex = new Texture2D(img);
        tex.setMagFilter(Texture.MagFilter.Bilinear);
        tex.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
        return tex;
    }

    private static byte unitToByte(float v) {
        return (byte) Math.round(Math.max(0f, Math.min(1f, v)) * 255f);
    }

    /**
     * Builds the four horizon-seal walls (open top AND bottom : the water plane is the lid, the relief sea
     * floor is the floor). Plain inward-facing opaque quads far out at the horizon (see
     * {@link #ENCLOSURE_RADIUS}), a last-resort failsafe so no near-horizontal ray can reach the sky
     * through the transparent water in open ocean (no coast in sight, where the sea floor dips below the
     * sightline). The Y extents are baked into the mesh in WORLD units, so the camera-follow in update()
     * only shifts X/Z and the walls stay at the right height (top at the water line, bottom past the
     * deepest relief). {@code floorY} is only the walls' lower edge, not a closing floor quad.
     */
    private Geometry createEnclosure(float[] heightmap, float step) {
        float offset = config.getFarTerrainVerticalOffset();
        // Walls top out exactly at the water-plane level (WaterHeight + offset) : they reach the water
        // line, so a near-horizontal ray skimming the surface in open ocean is stopped before the horizon.
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

        // Far radius (see ENCLOSURE_RADIUS) : the relief sea floor is the visible floor, so the walls sit
        // out at the horizon as a pure failsafe rather than right beyond the loaded chunks.
        float half = ENCLOSURE_RADIUS;

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
     * Four inward-facing wall quads (open top AND bottom) spanning [-half, +half] in X/Z, from floorY to
     * topY. No floor quad : the relief sea floor (a gap-free heightfield covering the whole extent) is the
     * sea floor, so every downward ray hits it. The walls stay as the cheap failsafe against a sky leak
     * through the transparent water at grazing angles (the far sea floor dips below such a near-horizontal
     * ray in open sea). Positions only : the
     * material is unshaded flat colour, so no normals / texcoords needed ; winding is irrelevant (culling Off).
     */
    private Mesh buildEnclosureMesh(float half, float floorY, float topY) {
        float[] pos = {
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
                0, 1, 2, 0, 2, 3,        // west
                4, 5, 6, 4, 6, 7,        // east
                8, 9, 10, 8, 10, 11,     // north
                12, 13, 14, 12, 14, 15   // south
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
        if (terrain != null && terrain.getParent() == null && !Boolean.getBoolean("ialon.noRelief")) {
            node.attachChild(terrain);
        }
        // Debug/validation toggle : -Dialon.noWater skips the water plane for A/B screenshots.
        if (water != null && water.getParent() == null && !Boolean.getBoolean("ialon.noWater")) {
            node.attachChild(water);
        }
        if (enclosure != null && enclosure.getParent() == null && !Boolean.getBoolean("ialon.noEnclosure")) {
            node.attachChild(enclosure);
        }
    }

    @Override
    protected void onDisable() {
        if (terrain != null && terrain.getParent() != null) {
            terrain.removeFromParent();
        }
        if (water != null && water.getParent() != null) {
            water.removeFromParent();
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
        if (waterMaterial != null) {
            waterMaterial.setFloat("InnerRadius",
                    (float) config.getGridRadius() * config.getChunkSize() - config.getChunkSize());
        }
        // The enclosure walls no longer scale with the render distance (fixed ENCLOSURE_RADIUS horizon
        // failsafe), so a render-distance change leaves them untouched.
    }

    @Override
    public void update(float tpf) {
        // After a couple of frames (so the LOD + normals have been computed once), drop both far-terrain
        // mesh controls : the backdrop then keeps that fixed LOD/normals, removing the recurrent
        // main-thread LOD-apply + normal-recompute hitch (see field doc).
        if (farLodWarmup >= 0 && terrain != null) {
            if (farLodWarmup >= 2) {
                terrain.removeControl(ThrottledTerrainLodControl.class);
                terrain.removeControl(NormalRecalcControl.class);
                farLodWarmup = -1;
            } else {
                farLodWarmup++;
            }
        }

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
                    if (water != null) {
                        water.setLocalTranslation(sx, waterSurfaceY(), sz);
                    }
                    // Keep the (periodic) density map aligned with the snapped terrain : the snap is a
                    // multiple of the world period, so the field repeats onto the new tile exactly.
                    if (forestTintEnabled) {
                        forestOrigin.set(sx, sz);
                    }
                    // Same for the shore-clip field : it is bound by reference, so moving its origin with
                    // the mesh keeps the analytic coastline on the geometric one. Missing this would slide
                    // the whole clip by the snap distance the first time the player crosses a tile edge.
                    shoreFieldOrigin.set(sx, sz);
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

        SkyState sky = getState(SkyState.class);

        // Hide the whole distant horizon while the eye is inside the ground AND below the water level. From
        // under the surface the relief is see-through (it culls its back faces), so what showed through was
        // its own front-facing slopes and the far water's underside -- distant scenery visible through solid
        // rock. There is nothing to draw a horizon FOR down there, so the cheapest and most complete answer
        // is to draw none : SkyState blacks out its cylinder at the same moment.
        //
        // "In the depths" -- under the column's surface AND below the water level -- is SkyState's single
        // source of truth for this, so the sky blackout and the horizon hiding can never disagree. The
        // water-level clause is what keeps a cave mouth working (rationale on SkyState.isDeepUnderground).
        //
        // CullHint rather than detach/attach : no scene-graph churn on the flip, and the culled subtree
        // costs nothing to render.
        boolean under = sky != null && sky.isDeepUnderground();
        if (under != hiddenUnderSurface) {
            hiddenUnderSurface = under;
            Spatial.CullHint hint = under ? Spatial.CullHint.Always : Spatial.CullHint.Dynamic;
            if (terrain != null) {
                terrain.setCullHint(hint);
            }
            if (water != null) {
                water.setCullHint(hint);
            }
            if (enclosure != null) {
                // The box is normally CullHint.Never (its bounds straddle the frustum).
                enclosure.setCullHint(under ? Spatial.CullHint.Always : Spatial.CullHint.Never);
            }
        }

        SkyControl skyControl = (sky != null) ? sky.getSkyControl() : null;
        if (skyControl != null && material != null) {
            // Bind the RELIEF fog colour to SkyControl's live ground colour (mutated in place as time
            // passes), so the terrain fades into the current ground colour at the horizon. Bound once.
            if (!fogColorBound) {
                material.setColor("FogColor", skyControl.getGroundColor());
                if (waterMaterial != null) {
                    waterMaterial.setColor("FogColor", skyControl.getGroundColor());
                }
                fogColorBound = true;
            }
            // Same live ground colour for the enclosure backstop : it sits behind the far terrain at the
            // horizon, so it must fade and darken identically (bound once, then tracked by reference).
            if (!enclosureColorBound && enclosure != null) {
                enclosure.getMaterial().setColor("Color", skyControl.getGroundColor());
                enclosureColorBound = true;
            }
        }

        // The far water plane uses the SAME material as the near voxel calm water, so copy that material's
        // live reflection uniforms (sun/sky/moon, pushed by WaterState) onto the far instance each frame --
        // then near & far water reflect identically. The static tuning + the body colour (via the scene's
        // ambient light) are already in place, so this is all the far water needs.
        if (waterMaterial != null) {
            ChunkMeshGenerator generator = BlocksConfig.getInstance().getChunkMeshGenerator();
            if (generator instanceof FacesMeshGenerator) {
                Material w = ((FacesMeshGenerator) generator).getCalmWaterMaterial();
                copyParam(w, waterMaterial, "SunDirection");
                copyParam(w, waterMaterial, "SunColor");
                copyParam(w, waterMaterial, "MoonDirection");
                copyParam(w, waterMaterial, "SkyColor");
                copyParam(w, waterMaterial, "SkyHorizonColor");
            }
        }
    }

    /** Copies one material parameter from {@code src} to {@code dst} (no-op if unset on the source). */
    private static void copyParam(Material src, Material dst, String name) {
        com.jme3.material.MatParam p = src.getParam(name);
        if (p != null && p.getValue() != null) {
            dst.setParam(name, p.getVarType(), p.getValue());
        }
    }
}
