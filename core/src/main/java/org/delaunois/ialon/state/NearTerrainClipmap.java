/**
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

import com.jme3.bounding.BoundingBox;
import com.jme3.material.Material;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferUtils;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.blocks.WorldEditOverlay;
import org.delaunois.ialon.blocks.generator.TerrainGenerator;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;

/**
 * The NEAR half of the far relief : a two-level geometry clipmap centred on the camera that resolves the
 * first few hundred world units at a 4- then 8-unit step, and hands over to {@link FarTerrainState}'s
 * {@code TerrainQuad} at {@link #OUTER_RADIUS}.
 *
 * <p><b>Why this exists.</b> The far relief becomes visible barely
 * {@code gridRadius*chunkSize - chunkSize} units from the eye (48 with the default render distance), yet
 * the {@code TerrainQuad} samples the generator every 16 units — one chunk. Between two samples it
 * interpolates linearly while the voxels follow the true noise curve, so at the boundary the two surfaces
 * disagree by 1 to 3 blocks : where the far relief passes <i>under</i> the voxel surface that reads as a
 * notch, the "gap" at the seam. LOD cannot fix this — it only ever decimates, so 16 units is the quad's
 * floor. Going finer on the quad itself is not an option either : its heightmap covers 8192 units, so a
 * 4-unit step would need 2049² = 4.2M vertices. A clipmap pays the fine sampling only where it is seen.
 *
 * <p><b>Geometry.</b> Two nested square grids in FIXED local coordinates (only the translation moves) :
 * <ul>
 *   <li>fine : {@value #FINE_GRID}² vertices, {@value #FINE_STEP}-unit step, half-extent 192 ;</li>
 *   <li>coarse : {@value #COARSE_GRID}² vertices, {@value #COARSE_STEP}-unit step, half-extent 448, with
 *       a central hole exactly matching the fine level's footprint.</li>
 * </ul>
 * The coarse step is twice the fine one and the hole falls on the coarse lattice, so one coarse vertex
 * out of two on the hole's border coincides <b>exactly</b> with a fine vertex — same world position, same
 * sampled height. Linearising the fine border's odd vertices (see {@code stitchBorder}) then makes the two
 * edges identical polylines, which closes the T-junctions without a single extra triangle.
 *
 * <p><b>Handover.</b> The clipmap discards outside {@link #OUTER_RADIUS} and the quad resumes
 * {@link #HANDOVER_OVERLAP} units inside it, both from the same Chebyshev camera distance. Complementary
 * discards alone are NOT enough : they leave no hole per pixel, but the two <i>surfaces</i> still differ
 * at the boundary, and down a slope the ray simply passes over the clipmap's edge to the distant
 * background — a bright sliver. So the coarse level first converges onto the quad's own 16-unit lattice
 * surface (see {@code morphOntoQuadLattice}), and the meshes then overlap over a band where they are
 * coincident, which removes the failure mode outright. The 64-unit margin between {@link #OUTER_RADIUS}
 * and the coarse geometry's 448 absorbs up to two snap steps of rebuild lag. Fog is driven by a separate
 * {@code FogStart} uniform, identical on both, or the handover would show as a colour seam.
 *
 * <p><b>Cost.</b> ~22k vertices / ~39k triangles in two draws, re-sampled off the render thread every
 * {@value #SNAP_STEP} units of travel (~23k {@code getHeight} calls, tens of milliseconds on a worker).
 * The vertex arrays are allocated once and refilled in place ; the only per-frame work is a null check.
 *
 * @author Cedric de Launois
 */
@Slf4j
class NearTerrainClipmap {

    /**
     * Camera-relative Chebyshev radius at which the clipmap hands the relief over to the far
     * {@code TerrainQuad}. Must stay far enough inside the coarse half-extent (448) that a rebuild lagging
     * a couple of snap steps behind the player still covers it — see the class doc. Also kept beyond the
     * default {@code farTreeDistance} (350) so the billboard ring stands entirely on the fine relief.
     */
    static final float OUTER_RADIUS = 384f;

    // Fine level : 4-unit step over +/-192 units. This is the resolution that actually closes the notches
    // at the voxel seam -- the linear-interpolation error falls with the square of the step, so 16 -> 4
    // divides it by ~16.
    // (package-private rather than private : NearTerrainClipmapTest asserts the lattice invariants these
    // values must satisfy for the levels to stitch — see the class doc.)
    static final int FINE_GRID = 97;
    static final float FINE_STEP = 4f;
    // Coarse level : MUST be exactly twice the fine step (the stitching relies on one coarse vertex out of
    // two coinciding with a fine one). 113 vertices -> +/-448 units, i.e. 64 units of slack beyond the
    // handover radius for rebuild lag.
    static final int COARSE_GRID = 113;
    static final float COARSE_STEP = 8f;
    // World-unit grid the clipmap origin snaps to. A multiple of the coarse step AND of the far quad's
    // 16-unit lattice, so all three lattices stay aligned whatever the player position ; and large enough
    // that a walking player triggers a rebuild only every few seconds.
    static final float SNAP_STEP = 32f;

    // Half-extents, derived : (GRID - 1) / 2 * STEP.
    static final float FINE_HALF = (FINE_GRID - 1) / 2f * FINE_STEP;
    static final float COARSE_HALF = (COARSE_GRID - 1) / 2f * COARSE_STEP;

    // Band (snap-relative Chebyshev radius) over which the coarse level gives up its own detail and
    // converges onto the far quad's 16-unit lattice surface.
    //
    // Complementary DISCARDS are not enough to make the handover invisible : they guarantee every pixel
    // gets exactly one of the two meshes, but the two SURFACES still differ there (8-unit vs 16-unit
    // interpolation of the same function). Seen down a slope, the ray that was about to hit the clipmap
    // passes over its edge instead and travels on to the distant background — a bright sliver along the
    // handover. So the clipmap must ARRIVE at the quad's surface, not merely stop next to it.
    //
    // MORPH_START must stay beyond FINE_HALF so the fine/coarse stitch is never touched ; MORPH_END must
    // stay far enough inside OUTER_RADIUS that the (camera-relative) discard always falls in the fully
    // converged zone — the camera is up to SNAP_STEP/2 from the snap point, plus up to SNAP_STEP of
    // rebuild lag, hence the 64-unit clearance.
    static final float MORPH_START = 224f;
    static final float MORPH_END = 288f;

    /**
     * How far INSIDE {@link #OUTER_RADIUS} the far quad is allowed to start drawing again, i.e. the width
     * of the band where both meshes are rendered.
     *
     * <p>Strictly complementary discards leave no hole in the pixel sense, but they cannot survive the
     * residual difference between the two surfaces at the boundary : the morph above lands on the mean of
     * a lattice cell's four corners, while the quad triangulates that cell (and may have dropped a LOD
     * level), so a fraction of a unit still separates them and on a silhouette that is a visible hairline.
     * Overlapping removes the failure mode instead of shrinking it — whichever surface is higher simply
     * wins the depth test, and there is nothing behind to show through.
     *
     * <p>It is free because of the morph : inside this band the clipmap is already drawing the quad's own
     * surface, so the two are coincident, same material, same heights — a depth tie between fragments that
     * look alike. Without the morph this overlap would z-fight visibly. The band must therefore stay
     * entirely inside the fully converged zone : {@code OUTER_RADIUS - this} minus the worst camera offset
     * from the snap point (half a snap step, plus a snap step of rebuild lag) must be >= MORPH_END.
     */
    static final float HANDOVER_OVERLAP = 48f;

    private final FarTerrainState far;
    private final IalonConfig config;
    // The clipmap follows the CAMERA, not the player : both discard radii are evaluated against
    // g_CameraPosition in the shader, so centring the geometry on anything else means the ring it covers
    // and the ring it is allowed to draw in drift apart — and the mismatch shows as a HOLE in the relief,
    // through which the horizon behind is visible. They coincide in normal play (the camera rides the
    // player's head), which is exactly why the bug would only surface in a detached-camera situation.
    private final Camera camera;
    private final TerrainGenerator generator;
    private final WorldEditOverlay editOverlay;

    private final Level fine;
    private final Level coarse;
    // Shared by both levels (see the constructor) ; registered with FarTerrainState so the live day/night
    // colours are bound on it by reference, exactly like the far quad's.
    private final Material material;

    // World XZ the CURRENT geometry is built and translated for. Volatile : stepAt() is called from the
    // far-tree builder thread.
    private volatile float snapX;
    private volatile float snapZ;
    // World XZ that the in-flight rebuild is sampling — only meaningful while building is true.
    private float buildX;
    private float buildZ;
    private final AtomicBoolean building = new AtomicBoolean(false);
    // Set by the worker once both levels' arrays are filled ; read (and cleared) on the render thread,
    // which is what publishes the arrays safely across the two threads.
    private volatile boolean ready;
    // Set once a sampling pass has thrown, so the warning is logged a single time (see update()).
    private volatile boolean sampleFailed;

    // Off-render-thread sampler, exactly like FarTreeState's : one daemon thread, result polled in
    // update(). Below-normal priority so it never competes with the chunk generation for a core.
    private final ExecutorService sampler = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "near-terrain-clipmap");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    // Scratch vectors for the sampling, only ever touched by the sampler thread (or by the constructor,
    // before that thread exists).
    private final Vector3f scratch = new Vector3f();
    // Player-edit height deltas that fall inside the current build's footprint, keyed by canonical far
    // sample. Rebuilt per sampling pass ; empty (the common case) means the relief is purely procedural.
    private final Map<Long, Float> localDeltas = new HashMap<>();

    NearTerrainClipmap(FarTerrainState far, IalonConfig config, Camera camera, TerrainGenerator generator) {
        this.far = far;
        this.config = config;
        this.camera = camera;
        this.generator = generator;
        this.editOverlay = config.getWorldEditOverlay();

        this.fine = new Level("NearTerrainFine", FINE_GRID, FINE_STEP, 0);
        // Hole radius in coarse cells : the fine level's half-extent. Lands on the coarse lattice because
        // COARSE_STEP is exactly twice FINE_STEP.
        this.coarse = new Level("NearTerrainCoarse", COARSE_GRID, COARSE_STEP, (int) (FINE_HALF / COARSE_STEP));

        // ONE material for both levels : they differ only in geometry, so sharing it keeps the two draws in
        // the same shader/uniform state and leaves a single object for the day/night bindings to track.
        this.material = far.createReliefMaterial(far.fogStart(), OUTER_RADIUS);
        fine.geometry.setMaterial(material);
        coarse.geometry.setMaterial(material);

        // Sample the first position synchronously : the quad already discards inside OUTER_RADIUS from
        // this point on, so an asynchronous first build would leave a frame with a hole around the camera.
        Vector3f eye = camera.getLocation();
        buildX = snap(eye.x);
        buildZ = snap(eye.z);
        sampleLevels();
        applySampled();

        // Tell the quad where to start drawing again. Done last : the quad's material already exists.
        far.setReliefHandoverRadius(OUTER_RADIUS - HANDOVER_OVERLAP);

        log.info("Near terrain clipmap : {} + {} vertices, steps {} / {}, handover at {} units",
                FINE_GRID * FINE_GRID, COARSE_GRID * COARSE_GRID, FINE_STEP, COARSE_STEP, OUTER_RADIUS);
    }

    /**
     * One clipmap level : a square grid mesh whose Y coordinates and normals are refilled on every
     * rebuild, plus the reusable staging arrays that feed them.
     */
    static final class Level {

        final int grid;
        private final float step;
        private final Geometry geometry;
        private final FloatBuffer positionBuffer;
        private final FloatBuffer normalBuffer;
        // Staging arrays, filled by the sampler thread and pushed to the buffers on the render thread.
        // ALWAYS staged through a primitive array : FloatBuffer.put(FloatBuffer) is a per-element loop on
        // ART, while put(float[]) is a memcpy.
        final float[] positions;
        final float[] normals;
        // Sampled heights with a one-cell border ring, so the outermost vertices still get a central-
        // difference normal. Indexed by (j + 1) * (grid + 2) + (i + 1).
        final float[] heights;
        final int heightStride;

        Level(String name, int grid, float step, int holeCells) { // package-private : built by the test too
            this.grid = grid;
            this.step = step;
            this.heightStride = grid + 2;
            this.positions = new float[grid * grid * 3];
            this.normals = new float[grid * grid * 3];
            this.heights = new float[heightStride * heightStride];

            Mesh mesh = new Mesh();
            positionBuffer = BufferUtils.createFloatBuffer(positions.length);
            normalBuffer = BufferUtils.createFloatBuffer(normals.length);
            mesh.setBuffer(VertexBuffer.Type.Position, 3, positionBuffer);
            mesh.setBuffer(VertexBuffer.Type.Normal, 3, normalBuffer);
            mesh.setBuffer(VertexBuffer.Type.Index, 3, buildIndices(grid, holeCells));
            // Fixed model bound covering the whole footprint and the full world height range, set once :
            // the mesh never leaves it, so no rebuild has to walk the vertices to recompute a bound.
            float half = (grid - 1) / 2f * step;
            mesh.setBound(new BoundingBox(new Vector3f(0f, 0f, 0f), half, 4096f, half));

            geometry = new Geometry(name, mesh);
            geometry.setShadowMode(RenderQueue.ShadowMode.Off);
            geometry.setQueueBucket(RenderQueue.Bucket.Opaque);
        }

        /**
         * Static index buffer : every quad of the grid except those fully inside the central hole (the
         * footprint the next finer level covers). {@code holeCells} is the hole's half-size in cells ; 0
         * means no hole. Vertex count stays below 2^16 on both levels, so 16-bit indices are enough.
         */
        private static ShortBuffer buildIndices(int grid, int holeCells) {
            int cells = grid - 1;
            int center = cells / 2;
            int lo = center - holeCells;
            int hi = center + holeCells;
            int quads = cells * cells - (holeCells * 2) * (holeCells * 2);
            ShortBuffer indices = BufferUtils.createShortBuffer(quads * 6);
            for (int j = 0; j < cells; j++) {
                for (int i = 0; i < cells; i++) {
                    if (holeCells > 0 && i >= lo && i < hi && j >= lo && j < hi) {
                        continue; // covered by the finer level
                    }
                    short a = (short) (j * grid + i);
                    short b = (short) (a + 1);
                    short c = (short) (a + grid);
                    short d = (short) (c + 1);
                    // Same winding as FarTerrainState.createGridMesh : front faces up (the relief material
                    // culls back faces, so getting this wrong makes the whole clipmap invisible).
                    indices.put(a).put(c).put(b);
                    indices.put(b).put(c).put(d);
                }
            }
            indices.flip();
            return indices;
        }

        Geometry getGeometry() {
            return geometry;
        }

        /** Pushes the staged arrays into the GL buffers. Render thread only. */
        void upload() {
            positionBuffer.clear();
            positionBuffer.put(positions);
            positionBuffer.flip();
            normalBuffer.clear();
            normalBuffer.put(normals);
            normalBuffer.flip();
            geometry.getMesh().getBuffer(VertexBuffer.Type.Position).updateData(positionBuffer);
            geometry.getMesh().getBuffer(VertexBuffer.Type.Normal).updateData(normalBuffer);
        }
    }

    // --- Scene graph / lifecycle -------------------------------------------------------------------

    void attachTo(Node node) {
        if (fine.geometry.getParent() == null) {
            node.attachChild(fine.geometry);
        }
        if (coarse.geometry.getParent() == null) {
            node.attachChild(coarse.geometry);
        }
    }

    void detach() {
        fine.geometry.removeFromParent();
        coarse.geometry.removeFromParent();
    }

    void setCullHint(Spatial.CullHint hint) {
        fine.geometry.setCullHint(hint);
        coarse.geometry.setCullHint(hint);
    }

    void cleanup() {
        sampler.shutdownNow();
    }

    /**
     * Refreshes the inner discard after a render-distance change : the clipmap starts exactly where the
     * loaded chunks end. The handover radius is fixed and does NOT move with the render distance.
     *
     * @param seam the new voxel seam radius ({@link FarTerrainState#fogStart()})
     */
    void onRenderDistanceChanged(float seam) {
        material.setFloat("InnerRadius", seam);
    }

    // --- Per-frame -----------------------------------------------------------------------------------

    /**
     * Swaps in a finished sampling pass and starts a new one when the player has left the current snap
     * cell. Render thread ; a no-op on the vast majority of frames.
     */
    void update() {
        if (ready) {
            ready = false;
            applySampled();
            building.set(false);
        }
        if (building.get()) {
            return;
        }
        Vector3f eye = camera.getLocation();
        float wantX = snap(eye.x);
        float wantZ = snap(eye.z);
        if (wantX == snapX && wantZ == snapZ) {
            return;
        }
        buildX = wantX;
        buildZ = wantZ;
        building.set(true);
        sampler.submit(() -> {
            try {
                sampleLevels();
                ready = true; // publishes the arrays to the render thread
            } catch (Exception e) {
                // Keep retrying (the alternative is a stale ring, i.e. a growing hole as the camera moves)
                // but log only the first one : a systematic failure would otherwise flood at frame rate.
                if (sampleFailed) {
                    log.debug("Near terrain clipmap sampling failed again", e);
                } else {
                    sampleFailed = true;
                    log.warn("Near terrain clipmap sampling failed", e);
                }
                building.set(false);
            }
        });
    }

    /** Uploads the staged vertices and moves both levels onto the origin they were sampled for. */
    private void applySampled() {
        fine.upload();
        coarse.upload();
        snapX = buildX;
        snapZ = buildZ;
        float y = config.getFarTerrainVerticalOffset();
        fine.geometry.setLocalTranslation(snapX, y, snapZ);
        coarse.geometry.setLocalTranslation(snapX, y, snapZ);
    }

    static float snap(float world) {
        return Math.round(world / SNAP_STEP) * SNAP_STEP;
    }

    /**
     * World-unit spacing of the relief lattice this clipmap renders at (wx, wz), or 0 where it does not
     * cover the point (the far quad does). Callable from any thread.
     */
    float stepAt(float wx, float wz) {
        float cheb = Math.max(Math.abs(wx - snapX), Math.abs(wz - snapZ));
        if (cheb < FINE_HALF) {
            return FINE_STEP;
        }
        return cheb < OUTER_RADIUS ? COARSE_STEP : 0f;
    }

    // --- Sampling (worker thread) --------------------------------------------------------------------

    /** Samples both levels around ({@link #buildX}, {@link #buildZ}) into their staging arrays. */
    private void sampleLevels() {
        collectLocalDeltas();
        sampleHeights(fine);
        sampleHeights(coarse);
        // Make the fine level's outer border a straight polyline between the vertices it shares with the
        // coarse level, so the two edges coincide and the T-junctions close. MUST run before the normals
        // and the positions are derived from the heights.
        stitchBorder(fine);
        // Bring the coarse level's outer band onto the far quad's surface, so the handover to it has no
        // step to show. Also before the normals, which must be those of the converged surface.
        morphOntoQuadLattice(coarse);
        // Only the coarse level converges : the fine one lies entirely inside MORPH_START.
        fillVertices(fine, false);
        fillVertices(coarse, true);
    }

    /**
     * Collects the player-edit height deltas that fall inside this build's footprint, so the reshaped
     * relief the far quad shows is reproduced here too (otherwise a levelled hill would spring back to its
     * procedural shape as soon as the clipmap took the near band over).
     *
     * <p>The overrides are stored on the far quad's 16-unit lattice, keyed canonically (wrapped to the
     * world period). Each is converted ONCE into a delta from the procedural height, so the per-vertex
     * bilinear blend below is a plain map lookup. Empty in the common case, which short-circuits the whole
     * mechanism.
     */
    private void collectLocalDeltas() {
        localDeltas.clear();
        if (editOverlay == null || editOverlay.getHeightOverrides().isEmpty()) {
            return;
        }
        float g = far.getStep();
        // Lattice points covering the coarse footprint, plus one cell so the outermost vertices still see
        // both of their bracketing lattice points.
        float x0 = (float) Math.floor((buildX - COARSE_HALF) / g) * g;
        float x1 = (float) Math.ceil((buildX + COARSE_HALF) / g) * g;
        float z0 = (float) Math.floor((buildZ - COARSE_HALF) / g) * g;
        float z1 = (float) Math.ceil((buildZ + COARSE_HALF) / g) * g;
        Map<Long, Float> overrides = editOverlay.getHeightOverrides();
        for (float sz = z0; sz <= z1; sz += g) {
            for (float sx = x0; sx <= x1; sx += g) {
                long key = far.sampleKey(sx, sz);
                Float measured = overrides.get(key);
                if (measured != null) {
                    localDeltas.put(key, measured - generator.getHeight(scratch.set(sx, 0f, sz)));
                }
            }
        }
    }

    /** Samples one level's heights, including the one-cell border ring used by the normals. */
    private void sampleHeights(Level level) {
        int n = level.grid;
        int stride = level.heightStride;
        float step = level.step;
        float half = (n - 1) / 2f;
        for (int j = -1; j <= n; j++) {
            float wz = buildZ + (j - half) * step;
            int row = (j + 1) * stride;
            for (int i = -1; i <= n; i++) {
                float wx = buildX + (i - half) * step;
                level.heights[row + i + 1] = sampleHeight(wx, wz);
            }
        }
    }

    /**
     * The relief height at a world position : the very same generator height the voxel chunks are carved
     * from, plus the player's reshaping, kept clear of the water plane exactly like the far quad's samples.
     * Any divergence here would reopen the seam this whole class exists to close.
     */
    private float sampleHeight(float wx, float wz) {
        float h = generator.getHeight(scratch.set(wx, 0f, wz));
        if (!localDeltas.isEmpty()) {
            h += overrideDelta(wx, wz);
        }
        return far.clampAcrossWaterline(h);
    }

    /** Bilinear blend of the four bracketing lattice deltas (0 where the player has not reshaped). */
    private float overrideDelta(float wx, float wz) {
        float g = far.getStep();
        float x0 = (float) Math.floor(wx / g) * g;
        float z0 = (float) Math.floor(wz / g) * g;
        float d00 = deltaAt(x0, z0);
        float d10 = deltaAt(x0 + g, z0);
        float d01 = deltaAt(x0, z0 + g);
        float d11 = deltaAt(x0 + g, z0 + g);
        if (d00 == 0f && d10 == 0f && d01 == 0f && d11 == 0f) {
            return 0f;
        }
        float tx = (wx - x0) / g;
        float tz = (wz - z0) / g;
        return (d00 * (1f - tx) + d10 * tx) * (1f - tz) + (d01 * (1f - tx) + d11 * tx) * tz;
    }

    private float deltaAt(float sx, float sz) {
        Float d = localDeltas.get(far.sampleKey(sx, sz));
        return d == null ? 0f : d;
    }

    /**
     * Linearises the odd vertices of a level's outer border, so that border becomes the straight polyline
     * the NEXT COARSER level draws along the same line. The even ones are already shared exactly (same
     * world position, same sampled height), so after this the two edges are geometrically identical and
     * the T-junctions cannot crack.
     */
    static void stitchBorder(Level level) {
        int n = level.grid;
        int stride = level.heightStride;
        float[] h = level.heights;
        for (int k = 1; k < n - 1; k += 2) {
            // Top and bottom edges : interpolate along X.
            h[stride + k + 1] = 0.5f * (h[stride + k] + h[stride + k + 2]);
            int lastRow = n * stride;
            h[lastRow + k + 1] = 0.5f * (h[lastRow + k] + h[lastRow + k + 2]);
            // Left and right edges : interpolate along Z.
            h[(k + 1) * stride + 1] = 0.5f * (h[k * stride + 1] + h[(k + 2) * stride + 1]);
            h[(k + 1) * stride + n] = 0.5f * (h[k * stride + n] + h[(k + 2) * stride + n]);
        }
    }

    /**
     * Fades the coarse level's own detail out over {@link #MORPH_START}..{@link #MORPH_END}, so that by
     * the time the handover discard cuts it off it is drawing the far quad's surface exactly.
     *
     * <p>It costs nothing to evaluate. The coarse step is 8 and the snap is a multiple of 32, so a coarse
     * vertex with an EVEN index sits precisely on the quad's 16-unit lattice — its height already is the
     * quad's. Only the odd ones need moving, and the quad's surface at those points is simply the
     * interpolation of the even neighbours : the midpoint of the two along an edge, and the mean of the
     * four corners at a cell centre. Even vertices are therefore never written, which is also what makes
     * this safe to do in place.
     *
     * <p>The cell-centre case is the one residual : the quad triangulates each lattice cell, so its true
     * surface at the centre is the midpoint of one diagonal, not the mean of the four corners. The mean is
     * chosen deliberately — it is off by at most a quarter of the cell's twist whichever diagonal jME
     * picked, where guessing the diagonal wrong would be off by half, and it stays right if the patch
     * drops a LOD level.
     */
    static void morphOntoQuadLattice(Level level) {
        int n = level.grid;
        int stride = level.heightStride;
        float step = level.step;
        float half = (n - 1) / 2f;
        float[] h = level.heights;
        for (int j = 0; j < n; j++) {
            boolean oddZ = (j & 1) == 1;
            float dz = Math.abs((j - half) * step);
            for (int i = 0; i < n; i++) {
                boolean oddX = (i & 1) == 1;
                if (!oddX && !oddZ) {
                    continue; // already a lattice vertex : the quad has the very same height here
                }
                float t = morphFactor(Math.max(Math.abs((i - half) * step), dz));
                if (t > 0f) {
                    int at = (j + 1) * stride + i + 1;
                    float lattice = latticeHeight(h, at, stride, oddX, oddZ);
                    h[at] = t >= 1f ? lattice : h[at] + (lattice - h[at]) * t;
                }
            }
        }
    }

    /**
     * The far quad's own surface at an off-lattice coarse vertex : the interpolation of the lattice
     * vertices around it, which are the even-indexed neighbours. Never called for an even/even vertex —
     * that one already carries the quad's height.
     *
     * @param oddX true if the vertex sits between two lattice columns
     * @param oddZ true if it sits between two lattice rows
     */
    private static float latticeHeight(float[] h, int at, int stride, boolean oddX, boolean oddZ) {
        if (!oddZ) {
            return 0.5f * (h[at - 1] + h[at + 1]);                   // midpoint along X
        }
        if (!oddX) {
            return 0.5f * (h[at - stride] + h[at + stride]);         // midpoint along Z
        }
        return 0.25f * (h[at - stride - 1] + h[at - stride + 1]      // lattice cell centre
                + h[at + stride - 1] + h[at + stride + 1]);
    }

    /**
     * How far the coarse level has converged onto the far quad's lattice at a given (snap-relative)
     * Chebyshev radius : 0 before the band, 1 past it, smoothstepped in between so there is no crease at
     * either end.
     *
     * <p>Shared by the heights ({@link #morphOntoQuadLattice}) and the normals ({@link #fillVertices}).
     * They MUST agree on where the band is : converging the geometry but not the shading is what leaves
     * the two meshes painting the overlap band differently.
     */
    private static float morphFactor(float cheb) {
        if (cheb <= MORPH_START) {
            return 0f;
        }
        if (cheb >= MORPH_END) {
            return 1f;
        }
        float t = (cheb - MORPH_START) / (MORPH_END - MORPH_START);
        return t * t * (3f - 2f * t);
    }

    /**
     * Derives the local positions and the central-difference normals from a level's sampled heights.
     *
     * <p>{@code converge} widens the normal's stencil from one cell to two ({@value #COARSE_STEP} ->
     * 16 units, one lattice step) as {@link #morphFactor} completes, so the coarse level ends up shaded
     * with the far quad's OWN normals and not merely standing on its surface. Without it the overlap band
     * mottles : both meshes are drawn there and the higher one wins per pixel, and although the morph
     * makes their heights agree, a normal taken over 8 units instead of 16 differs sharply on a crest —
     * which {@code FarTerrain.frag} then amplifies through the strata slope term into a swing of the snow
     * and rock lines of up to {@code STRATA_SNOW_SLOPE_RISE} (26) world units. The two candidate surfaces
     * come out one rock, one snow, and the depth winner alternates from cell to cell.
     *
     * <p>The wide stencil is exactly right rather than merely smoother : on an EVEN index it is the quad's
     * own lattice gradient, and on an odd one it expands to the mean of the neighbouring lattice gradients
     * — precisely the interpolation the quad itself performs between its vertex normals. So no separate
     * normal pass is needed. Gradients (not normalised vectors) are what gets blended, so the transition
     * cannot pinch.
     */
    static void fillVertices(Level level, boolean converge) {
        int n = level.grid;
        int stride = level.heightStride;
        float step = level.step;
        float half = (n - 1) / 2f;
        float[] h = level.heights;
        float[] pos = level.positions;
        float[] nor = level.normals;
        float twoStep = 2f * step;
        float fourStep = 4f * step;
        for (int j = 0; j < n; j++) {
            int hrow = (j + 1) * stride;
            int vrow = j * n;
            float z = (j - half) * step;
            boolean wideRow = converge && j > 0 && j < n - 1;
            for (int i = 0; i < n; i++) {
                int hi = hrow + i + 1;
                int at = (vrow + i) * 3;
                float x = (i - half) * step;
                pos[at] = x;
                pos[at + 1] = h[hi];
                pos[at + 2] = z;
                // Surface gradient (-dy/dx, -dy/dz) ; the normal is normalize(gx, 1, gz).
                float gx = (h[hi - 1] - h[hi + 1]) / twoStep;
                float gz = (h[hi - stride] - h[hi + stride]) / twoStep;
                // The wide stencil reaches two cells out, which the one-cell border ring cannot serve on
                // the outermost row/column. Those keep the narrow one : they sit past OUTER_RADIUS and are
                // never drawn.
                if (wideRow && i > 0 && i < n - 1) {
                    float t = morphFactor(Math.max(Math.abs(x), Math.abs(z)));
                    if (t > 0f) {
                        gx += ((h[hi - 2] - h[hi + 2]) / fourStep - gx) * t;
                        gz += ((h[hi - 2 * stride] - h[hi + 2 * stride]) / fourStep - gz) * t;
                    }
                }
                float len = (float) Math.sqrt(gx * gx + 1f + gz * gz);
                nor[at] = gx / len;
                nor[at + 1] = 1f / len;
                nor[at + 2] = gz / len;
            }
        }
    }
}
