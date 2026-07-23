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

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingVolume;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.mesh.IndexBuffer;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.ChunkPager;
import org.delaunois.ialon.blocks.jme.LayerComparator;
import com.simsilica.mathd.Vec3i;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

/**
 * Merges one chunk-mesh type across every loaded chunk into a single geometry — one draw call for the
 * whole loaded area instead of one per chunk.
 *
 * <p>Worth doing for the mesh types that are <em>numerous but tiny</em>. The calm-water surface is the
 * extreme case : it is one flat greedy-merged quad sheet per chunk, so an open sea costs as many draw
 * calls as there are visible water chunks for a handful of triangles each — on a mid-range Android
 * device, where the frame is bound by per-draw driver/CPU cost and not by geometry, that is the single
 * most expensive thing on screen. Fire billboards are the same shape of problem, smaller.
 *
 * <p>It deliberately does <b>not</b> apply to the generic (opaque terrain) mesh : those chunks are few,
 * big, and genuinely benefit from the pager's per-chunk cave-culling and from jME's frustum culling,
 * all of which a merged geometry throws away.
 *
 * <p>The per-chunk source geometries stay in the scene graph (they are the source of truth, rebuilt by
 * the mesher on every edit) but are hidden with {@link Spatial.CullHint#Always}. Hiding happens in
 * {@link #onPageTracked}, i.e. on the exact frame the pager puts a page in the scene — attach, detach
 * and in-place re-mesh all funnel through it — so a freshly meshed chunk is never drawn on top of the
 * merged copy. The merge itself is the expensive half and is throttled in proportion to its own measured
 * cost (see {@link #DUTY_FACTOR}).
 *
 * <p>The source index is maintained by those callbacks rather than rebuilt. It used to be recollected by
 * walking every loaded page whenever the pager reported activity — which it does on nearly every frame
 * while the player moves — and that walk measured 7-13 ms on device, per state, for ~620 pages of which
 * two had changed. Reacting to the two is the whole point.
 *
 * <p>Chunks hidden by cave culling are merged in all the same : excluding them would mean rebuilding on
 * every visibility change, and their few quads are depth-tested away behind the opaque terrain anyway.
 *
 * @author Cedric de Launois
 */
@Slf4j
public class ChunkMeshBatchState extends BaseAppState implements ChunkPager.ScenePageListener {

    /**
     * Self-limiting throttle : after a merge, wait this many times its own duration before allowing the
     * next one, so merging can never eat more than 1/DUTY_FACTOR of the wall clock however big the
     * merged sheet gets.
     *
     * <p>A FIXED period would be wrong here, and specifically wrong for block edits. Placing a block
     * re-meshes its chunk (and its neighbours, at a chunk boundary) and re-attaches the pages ; the
     * per-chunk geometry is hidden immediately so it cannot double-draw over the batch, so until the
     * merge runs that chunk's water is simply missing. With a 250 ms floor, an edit landing right after
     * a paging merge would leave a hole for a quarter of a second. Scaled to the real cost, a merge that
     * takes a few hundred microseconds — which is what a few thousand vertices costs — allows the next
     * one within a handful of milliseconds, i.e. the very next frame.
     */
    private static final long DUTY_FACTOR = 20;

    /** Never wait longer than this between merges, whatever the measured cost. */
    private static final long MAX_COOLDOWN_MS = 250;

    /** A merge slower than this is logged : it would be a frame-time problem, not a throttling one. */
    private static final long SLOW_MERGE_MS = 6;

    /** Spare capacity kept when a buffer has to grow, so steady-state paging reallocates nothing. */
    private static final float GROWTH_FACTOR = 1.25f;

    /** Largest vertex count addressable with 16-bit (unsigned) indices. */
    private static final int MAX_USHORT_VERTICES = 65536;

    /*
     * There is deliberately NO "copy small sources element by element instead" threshold any more. One
     * existed (64 vertices), on the reasoning that a bulk transfer's per-call overhead outweighs its
     * per-element saving once a source is only ~10 vertices — the shape a calm sea surface has. That was
     * calibrated on the desktop, the platform where it does not matter, and the device disagreed by 18x per
     * vertex on the side that took the element path (water_calm 98 sources x 14 vertices : 8.6 us/vertex
     * element, against 0.48 us/vertex staged). Array staging wins at every size here ; see copyBulk for why
     * that is a property of ART's nio implementation rather than a tuning accident.
     */

    private final String meshType;
    // A second mesh type whose per-chunk geometries are CLONES of this one's (same Mesh object, only the
    // cull mode and layer differ) : the volumetric water's inside faces. Merging it separately would
    // redo exactly the same buffer copies for the same vertices, so instead the merged mesh is shared by
    // two output geometries. Null when the type has no twin.
    private final String twinType;
    // Non-null only for the offline harnesses, which build their own pager instead of a ChunkPagerState.
    private final ChunkPager providedPager;

    private ChunkPager chunkPager;
    private Node batchNode;
    private Geometry batchGeometry;
    private Geometry twinGeometry;
    // One twin source, kept only for the frame of the merge : the model its material and layer come from.
    private Geometry twinModel;
    private final Mesh batchMesh = new Mesh();
    // Filled and consumed within a single update() : never holds references between frames, so a page
    // detached in the meantime can never be merged from.
    private final List<Geometry> sources = new ArrayList<>();
    // The live index this state's sources come from, keyed by page location and maintained by the pager's
    // attach/detach callbacks (see onPageTracked). Keyed rather than listed so a detach is a single
    // removal : the previous design rebuilt the whole thing by walking every loaded page, which measured
    // 7-13 ms per walk on device, per state, on most frames.
    private final Map<Vec3i, Geometry> sourceByPage = new LinkedHashMap<>();
    private final Map<Vec3i, Geometry> twinByPage = new LinkedHashMap<>();

    // The concatenation itself, shared with the per-region batcher : see MeshMerger for why writing nio
    // buffers correctly on ART is worth a class of its own.
    private final MeshMerger merger = new MeshMerger();
    private final BoundingBox batchBound = new BoundingBox();

    private long lastRebuildMs = 0;
    private long cooldownMs = 0; // derived from the last merge's own duration, see DUTY_FACTOR
    private boolean rebuildPending = false;
    // Time spent in getWorldTranslation() during the last merge, reported by the slow-merge line.
    private long parentLookupNanos = 0;

    /**
     * @param meshType the chunk-mesh type to merge, i.e. the name the mesher gives the geometry inside
     *                 each chunk node (see {@code FacesMeshGenerator.CHUNK_MESH_TYPE_*})
     */
    public ChunkMeshBatchState(String meshType) {
        this(meshType, null, null);
    }

    /**
     * @param twinType a mesh type whose per-chunk geometries share their {@code Mesh} with this one's
     *                 (the front-culled inside faces of the volumetric water). It is hidden and drawn
     *                 from the SAME merged mesh, with its own material and layer, instead of being
     *                 merged a second time over identical data.
     */
    public ChunkMeshBatchState(String meshType, String twinType) {
        this(meshType, twinType, null);
    }

    /**
     * Variant for the offline harnesses (screenshot / scenery), which drive a hand-built
     * {@link ChunkPager} rather than the game's {@link ChunkPagerState}.
     */
    public ChunkMeshBatchState(String meshType, String twinType, ChunkPager pager) {
        this.meshType = meshType;
        this.twinType = twinType;
        this.providedPager = pager;
    }

    @Override
    protected void initialize(Application app) {
        chunkPager = providedPager != null ? providedPager
                : app.getStateManager().getState(ChunkPagerState.class).getChunkPager();
        indexExistingPages();
        chunkPager.addScenePageListener(this);
        batchNode = new Node("Batch - " + meshType);
        ((SimpleApplication) app).getRootNode().attachChild(batchNode);
    }

    @Override
    protected void cleanup(Application app) {
        if (chunkPager != null) {
            chunkPager.removeScenePageListener(this);
        }
        sourceByPage.clear();
        twinByPage.clear();
        if (batchNode != null) {
            batchNode.removeFromParent();
            batchNode = null;
        }
        batchGeometry = null;
        twinGeometry = null;
        twinModel = null;
        sources.clear();
        rebuildPending = false;
    }

    @Override
    public void update(float tpf) {
        // No page scanning here any more : the source index is maintained by onPageTracked /
        // onPageUntracked as the pager works, so update() only has to decide whether the throttle window
        // has opened. Hiding is done by the listener too, on the very frame a page appears — earlier and
        // more exactly than the old "rescan everything when pageOps moved" ever managed.
        long now = System.currentTimeMillis();
        if (rebuildPending && now - lastRebuildMs >= cooldownMs) {
            lastRebuildMs = now;
            rebuildPending = false;
            rebuild();
        }
        sources.clear();
    }

    /**
     * Re-collects the per-chunk geometries of this mesh type and merges them into the single batch
     * geometry. Runs on the render thread, so it is throttled by {@link #update(float)} ; the copy
     * itself is a handful of buffer transfers over a few thousand vertices.
     */
    private void rebuild() {
        // Phase timestamps. The merge was measured on device costing ~200-360 us PER SOURCE geometry
        // (98 sources / 1344 vertices : 19.5 ms ; 25 sources / 12916 vertices : 9.0 ms), i.e. the cost
        // tracks the source COUNT and not the vertex count -- so the copy itself is not where it goes.
        // These splits say which phase actually pays, instead of leaving it to be guessed at.
        long start = System.nanoTime();
        collectSources();
        long afterCollect = System.nanoTime();

        if (sources.isEmpty()) {
            if (batchGeometry != null) {
                batchGeometry.removeFromParent();
                batchGeometry = null;
            }
            if (twinGeometry != null) {
                twinGeometry.removeFromParent();
                twinGeometry = null;
            }
            return;
        }

        long afterCount = System.nanoTime();
        long afterPrepare = afterCount;
        int totalVerts = merger.merge(sources, batchMesh, batchBound);
        long afterMerge = System.nanoTime();
        finishGeometry();
        long end = System.nanoTime();

        long micros = TimeUnit.NANOSECONDS.toMicros(end - start);
        cooldownMs = Math.min(micros * DUTY_FACTOR / 1000, MAX_COOLDOWN_MS);
        if (micros >= SLOW_MERGE_MS * 1000) {
            log.warn("Slow {} merge : {} chunk geometries, {} vertices, {}us"
                            + " [collect {} / count {} / prepare {} / copy {} (parent {}) / finish {}]"
                            + " (next merge in {}ms)",
                    meshType, sources.size(), totalVerts, micros,
                    TimeUnit.NANOSECONDS.toMicros(afterCollect - start),
                    TimeUnit.NANOSECONDS.toMicros(afterCount - afterCollect),
                    TimeUnit.NANOSECONDS.toMicros(afterPrepare - afterCount),
                    TimeUnit.NANOSECONDS.toMicros(afterMerge - afterPrepare),
                    TimeUnit.NANOSECONDS.toMicros(merger.getParentLookupNanos()),
                    TimeUnit.NANOSECONDS.toMicros(end - afterMerge),
                    cooldownMs);
        } else if (log.isDebugEnabled()) {
            log.debug("Merged {} {} chunk geometries into 1 ({} vertices) in {}us (next merge in {}ms)",
                    sources.size(), meshType, totalVerts, micros, cooldownMs);
        }
    }

    /**
     * Gathers this type's geometries from every scene page, hiding each one behind the merged copy. Twin
     * geometries are hidden too — they are drawn from the merged mesh, not merged themselves — and one
     * of them is kept as the model for the twin output geometry's material and layer.
     */
    private void collectSources() {
        sources.clear();
        sources.addAll(sourceByPage.values());
        twinModel = twinByPage.isEmpty() ? null : twinByPage.values().iterator().next();
    }

    /**
     * Indexes one page's geometries of this type, hiding them behind the merged copy. Called from the
     * pager the moment a page enters the scene — including the re-track half of an in-place re-mesh, so
     * a freshly meshed chunk is hidden on the very frame it appears and can never draw over the batch.
     */
    @Override
    public void onPageTracked(Vec3i location, Node page) {
        boolean changed = false;
        for (Spatial child : page.getChildren()) {
            if (!(child instanceof Geometry)) {
                continue;
            }
            if (meshType.equals(child.getName())) {
                child.setCullHint(Spatial.CullHint.Always);
                changed |= sourceByPage.put(location, (Geometry) child) != child;
            } else if (twinType != null && twinType.equals(child.getName())) {
                child.setCullHint(Spatial.CullHint.Always);
                changed |= twinByPage.put(location, (Geometry) child) != child;
            }
        }
        flagIfChanged(changed);
    }

    @Override
    public void onPageUntracked(Vec3i location, Node page) {
        boolean changed = sourceByPage.remove(location) != null;
        changed |= twinByPage.remove(location) != null;
        flagIfChanged(changed);
    }

    /**
     * Requests a merge, but only when the page event actually altered this state's source set.
     *
     * <p>The merged mesh is a pure function of {@link #sourceByPage} / {@link #twinByPage}, so a page that
     * carries none of this type's geometry cannot change it. <b>Most pages carry none</b> : the calm-water
     * surface exists only in the thin band of chunks at sea level (measured on device : 175-212 sources out
     * of the 2890 pages a render distance of 8 loads, so ~93% of page events are irrelevant to it), and
     * fire in almost none — while paging tracks and untracks EVERY chunk the player moves through.
     *
     * <p>Flagging unconditionally therefore kept a merge scheduled on the throttle's own period (130-250 ms)
     * for as long as the player kept moving, nearly always re-merging byte-identical data. That is what made
     * the cost matter : the merge is only a few milliseconds, but on device it is also a few milliseconds
     * during which a background GC can steal the render thread — the phase timings in the slow-merge log
     * show 5-9 ms landing at random in phases (a {@code clear()} plus {@code addAll} of 175 references, a
     * loop of 175 {@code getVertexCount()} calls) that cannot cost that by computation. Doing the merge an
     * order of magnitude less often is worth more than making it faster again.
     */
    private void flagIfChanged(boolean changed) {
        if (changed) {
            rebuildPending = true;
        }
    }

    /**
     * One full scan, at init only : the pager may already hold pages when this state is attached (world
     * switch, harness). Everything after this is maintained by the listener above.
     */
    private void indexExistingPages() {
        sourceByPage.clear();
        twinByPage.clear();
        for (Map.Entry<Vec3i, Node> entry : chunkPager.getScenePages().entrySet()) {
            onPageTracked(entry.getKey(), entry.getValue());
        }
    }

    private void finishGeometry() {
        // Counts and bound are set by the merger, which is the only code that visits every vertex.
        batchGeometry = publish(batchGeometry, sources.get(0), meshType);
        if (twinModel != null) {
            // Same merged mesh, the twin's own material and layer : the inside faces of the water cost a
            // second draw call (they must blend before the outside ones) but not a second merge.
            twinGeometry = publish(twinGeometry, twinModel, twinType);
        }
        refreshBound();
    }

    /**
     * Creates the output geometry on first use and keeps its material in sync with the per-chunk
     * geometries it stands for, so the merged copy renders exactly like them (and keeps sharing the live
     * material the water/fire states animate).
     */
    private Geometry publish(Geometry geometry, Geometry model, String name) {
        Geometry result = geometry;
        if (result == null) {
            result = new Geometry(name + "-batch", batchMesh);
            result.setQueueBucket(model.getQueueBucket());
            // Cast to Spatial : LayerComparator also has an INSTANCE getLayer(Geometry), which is the
            // one a Geometry-typed argument would resolve to.
            Integer layer = LayerComparator.getLayer((Spatial) model);
            if (layer != null) {
                LayerComparator.setLayer(result, layer);
            }
        }
        result.setMaterial(model.getMaterial());
        if (result.getParent() == null) {
            batchNode.attachChild(result);
        }
        return result;
    }

    /**
     * Publishes the freshly merged mesh's bound to the geometry. This has to go through
     * {@link Geometry#setModelBound} : writing the bound on the mesh alone leaves the geometry's WORLD
     * bound stale — it is only recomputed when the geometry is flagged dirty, and our transform never
     * changes. A stale bound means the whole merged sheet is frustum-culled as soon as the camera looks
     * away from wherever the very first merge happened to be : the sea vanishes when you tilt up.
     *
     * <p>The bound is grown by one block because billboard meshes (fire) collapse their quad corners
     * onto the block centre and only reach their on-screen size once the vertex shader expands them.
     */
    private void refreshBound() {
        BoundingVolume bound = batchMesh.getBound();
        if (bound instanceof BoundingBox) {
            float margin = BlocksConfig.getInstance().getBlockScale();
            BoundingBox bb = (BoundingBox) bound;
            bb.setXExtent(bb.getXExtent() + margin);
            bb.setYExtent(bb.getYExtent() + margin);
            bb.setZExtent(bb.getZExtent() + margin);
        }
        batchGeometry.setModelBound(bound);
        if (twinGeometry != null) {
            twinGeometry.setModelBound(bound);
        }
    }

    @Override
    protected void onEnable() {
        // nothing to do : the merge is driven by the pager's page operations
    }

    @Override
    protected void onDisable() {
        // nothing to do
    }
}
