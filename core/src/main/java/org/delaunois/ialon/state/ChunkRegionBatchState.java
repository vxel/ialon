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
import com.jme3.bounding.BoundingBox;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.ChunkPager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

/**
 * Merges the opaque terrain mesh of neighbouring chunks into one geometry per <b>region</b> of
 * {@code regionSize} chunks per axis — trading draw calls for culling granularity.
 *
 * <p><b>Why regions and not one big batch.</b> The opaque terrain was measured as the frame's dominant
 * cost, and measured to be <i>per-draw CPU</i>, not geometry : with {@code -Dialon.vpscale} a quarter of the
 * pixels left it unchanged (ratio 1.01x), and with {@code -Dialon.rendercpu} the render thread's CPU landed
 * on the "all work is CPU" anchor at both vantages. A render report then showed <b>122 draws for the chunk
 * terrain, all under a single material</b>, costing ~45-56 us each — around 5.5 ms of the 8-10.7 ms opaque
 * pass. jME 3.9 does not use vertex array objects (the call is commented out in {@code GLRenderer}) and its
 * attribute cache is keyed on the {@code VertexBuffer} instance, which differs for every chunk, so each draw
 * re-issues a bind plus a pointer per attribute buffer — about a dozen GL calls through ANGLE, per chunk.
 *
 * <p>But merging <i>everything</i> would be a bad trade, and that is why the naive version was rejected
 * before : the pager holds ~550 pages of which only ~122 are drawn, so cave culling and frustum culling
 * already remove ~78% of the geometry, and one batch would draw all of it. Regions keep both : each batch
 * has its own bound (so jME still frustum-culls it) and is hidden when none of its pages is visible (so cave
 * culling still applies), just at region granularity instead of per chunk.
 *
 * <p><b>Why it pays despite costing the same per source.</b> Merging a source costs about what drawing it
 * costs (~50 us). The difference is the rate : a merge happens only when a page changes, a draw happens every
 * frame. A region that survives one second at 60 fps repays its merge sixty times.
 *
 * <p><b>No holes, ever.</b> A page's own geometry stays visible until its region's batch actually contains
 * it, and the batch stays hidden until then. So a region being rebuilt simply renders per-chunk, exactly as
 * before this class existed — the worst case degrades to the old behaviour instead of flashing a hole in the
 * terrain. That also bounds the damage when paging is faster than merging.
 *
 * @author Cedric de Launois
 */
@Slf4j
public class ChunkRegionBatchState extends BaseAppState implements ChunkPager.ScenePageListener {

    /** A rebuild pass slower than this is logged : it runs on the render thread. */
    private static final long SLOW_REBUILD_MS = 6;

    /**
     * Wall-clock a rebuild pass may spend per frame. 3 ms of a 16.7 ms frame, leaving room for the render
     * and for paging, which are the other two claimants on this thread.
     */
    private static final long REBUILD_BUDGET_NANOS = 3_000_000L;

    private final String meshType;
    private final int regionSize;
    private final int maxRegionsPerFrame;
    // Non-null only for the offline harnesses, which build their own pager instead of a ChunkPagerState.
    private final ChunkPager providedPager;

    private ChunkPager chunkPager;
    private Node batchNode;

    private final MeshMerger merger = new MeshMerger();
    private final Map<Vec3i, Region> regions = new LinkedHashMap<>();
    // Region key per page, so untracking finds its region without recomputing the (negative-safe) division.
    private final Map<Vec3i, Vec3i> regionOfPage = new HashMap<>();
    private final List<Vec3i> dirtyOrder = new ArrayList<>();
    private final List<Geometry> scratchSources = new ArrayList<>();

    /** One merged geometry and the pages that feed it. */
    private static final class Region {
        // Insertion-ordered so a rebuild concatenates sources in a stable order : a page re-meshed in place
        // keeps its slot, which keeps the merged triangle order stable and the output diff-able.
        final Map<Vec3i, Geometry> sources = new LinkedHashMap<>();
        final Map<Vec3i, Node> pages = new LinkedHashMap<>();
        final Mesh mesh = new Mesh();
        // Owned per region : the Mesh keeps a reference to it, so one shared instance would make every
        // merge mutate the previous region's bound (see MeshMerger#merge).
        final BoundingBox bound = new BoundingBox();
        Geometry geometry;
        boolean dirty = true;
    }

    public ChunkRegionBatchState(String meshType, int regionSize, int maxRegionsPerFrame) {
        this(meshType, regionSize, maxRegionsPerFrame, null);
    }

    /** Variant for the offline harnesses, which drive a hand-built {@link ChunkPager}. */
    public ChunkRegionBatchState(String meshType, int regionSize, int maxRegionsPerFrame, ChunkPager pager) {
        this.meshType = meshType;
        this.regionSize = Math.max(1, regionSize);
        this.maxRegionsPerFrame = Math.max(1, maxRegionsPerFrame);
        this.providedPager = pager;
    }

    @Override
    protected void initialize(Application app) {
        chunkPager = providedPager != null ? providedPager
                : app.getStateManager().getState(ChunkPagerState.class).getChunkPager();
        batchNode = new Node("RegionBatch - " + meshType);
        ((SimpleApplication) app).getRootNode().attachChild(batchNode);
        for (Map.Entry<Vec3i, Node> entry : chunkPager.getScenePages().entrySet()) {
            onPageTracked(entry.getKey(), entry.getValue());
        }
        chunkPager.addScenePageListener(this);
        log.info("Region batching {} at {}x{}x{} chunks, up to {} regions rebuilt per frame",
                meshType, this.regionSize, this.regionSize, this.regionSize, this.maxRegionsPerFrame);
    }

    @Override
    protected void cleanup(Application app) {
        if (chunkPager != null) {
            chunkPager.removeScenePageListener(this);
        }
        // Give the per-chunk geometries back : this state hid them, and nothing else will un-hide them.
        for (Region region : regions.values()) {
            showSources(region);
        }
        regions.clear();
        regionOfPage.clear();
        dirtyOrder.clear();
        if (batchNode != null) {
            batchNode.removeFromParent();
            batchNode = null;
        }
    }

    @Override
    protected void onEnable() {
        // Nothing to do
    }

    @Override
    protected void onDisable() {
        // Nothing to do
    }

    /** Region a chunk belongs to. Floor division, so it stays correct for negative chunk coordinates. */
    private Vec3i regionKey(Vec3i location) {
        return new Vec3i(Math.floorDiv(location.x, regionSize),
                Math.floorDiv(location.y, regionSize),
                Math.floorDiv(location.z, regionSize));
    }

    @Override
    public void onPageTracked(Vec3i location, Node page) {
        Geometry source = null;
        for (Spatial child : page.getChildren()) {
            if (child instanceof Geometry && meshType.equals(child.getName())) {
                source = (Geometry) child;
                break;
            }
        }
        Vec3i previous = regionOfPage.get(location);
        if (source == null) {
            // The page carries none of this type any more (an edit removed all its opaque blocks).
            if (previous != null) {
                dropPage(previous, location);
            }
            return;
        }
        Vec3i key = regionKey(location);
        if (previous != null && !previous.equals(key)) {
            dropPage(previous, location); // cannot normally happen : a page keeps its coordinates
        }
        Region region = regions.get(key);
        if (region == null) {
            region = new Region();
            regions.put(key.clone(), region);
        }
        region.sources.put(location.clone(), source);
        region.pages.put(location.clone(), page);
        regionOfPage.put(location.clone(), key.clone());
        // Visible until the batch actually contains it — see the class note on holes.
        source.setCullHint(Spatial.CullHint.Inherit);
        markDirty(key, region);
    }

    @Override
    public void onPageUntracked(Vec3i location, Node page) {
        Vec3i key = regionOfPage.remove(location);
        if (key != null) {
            dropPage(key, location);
        }
    }

    private void dropPage(Vec3i key, Vec3i location) {
        Region region = regions.get(key);
        if (region == null) {
            return;
        }
        region.sources.remove(location);
        region.pages.remove(location);
        regionOfPage.remove(location);
        if (region.sources.isEmpty()) {
            if (region.geometry != null) {
                region.geometry.removeFromParent();
            }
            regions.remove(key);
            dirtyOrder.remove(key);
            return;
        }
        markDirty(key, region);
    }

    /**
     * Flags a region for rebuild and hands its chunks back to per-chunk rendering until then : the batch is
     * hidden and every source shown, so the region keeps drawing correctly (just less cheaply) meanwhile.
     */
    private void markDirty(Vec3i key, Region region) {
        if (!region.dirty) {
            region.dirty = true;
            showSources(region);
            if (region.geometry != null) {
                region.geometry.setCullHint(Spatial.CullHint.Always);
            }
        }
        if (!dirtyOrder.contains(key)) {
            dirtyOrder.add(key);
        }
    }

    private static void showSources(Region region) {
        for (Geometry source : region.sources.values()) {
            source.setCullHint(Spatial.CullHint.Inherit);
        }
    }

    @Override
    public void update(float tpf) {
        rebuildDirtyRegions();
        applyRegionVisibility();
    }

    /**
     * Rebuilds dirty regions, oldest request first, until a TIME budget is spent.
     *
     * <p>A count of regions was the first budget and it was the wrong unit : device measurement showed one
     * pass merging 12 sources in 11.5 ms and another merging 4 in 8.7 ms — ~1-2 ms per source, because a
     * generic terrain chunk carries ~1800 vertices where the calm-water sheet this merger was first tuned
     * for carries ~12. So "two regions" can mean 2 ms or 12 ms, and capping the count caps nothing that
     * matters. A time budget caps the spike directly, whatever the regions happen to contain.
     *
     * <p>Checked <b>after</b> each region rather than before, so a single region that overruns the whole
     * budget still makes progress instead of starving for ever. {@link #maxRegionsPerFrame} stays as a
     * secondary cap for the opposite case : many tiny regions, where the budget alone would let one frame
     * churn through hundreds of map lookups.
     *
     * <p>Regions still waiting keep rendering per chunk (see the class note on holes), so spreading the work
     * costs nothing but leaving them un-batched for a few more frames.
     */
    private void rebuildDirtyRegions() {
        if (dirtyOrder.isEmpty()) {
            return;
        }
        long start = System.nanoTime();
        int rebuilt = 0;
        int merged = 0;
        while (rebuilt < maxRegionsPerFrame && !dirtyOrder.isEmpty()) {
            Vec3i key = dirtyOrder.remove(0);
            Region region = regions.get(key);
            if (region == null || !region.dirty) {
                continue;
            }
            merged += rebuild(region);
            rebuilt++;
            if (System.nanoTime() - start >= REBUILD_BUDGET_NANOS) {
                break;
            }
        }
        long micros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - start);
        if (micros >= SLOW_REBUILD_MS * 1000) {
            log.warn("Slow {} region rebuild : {} regions, {} sources, {}us ({} regions still dirty)",
                    meshType, rebuilt, merged, micros, dirtyOrder.size());
        } else if (log.isDebugEnabled() && rebuilt > 0) {
            log.debug("Rebuilt {} {} regions ({} sources) in {}us ({} dirty)",
                    rebuilt, meshType, merged, micros, dirtyOrder.size());
        }
    }

    /** @return the number of sources merged */
    private int rebuild(Region region) {
        scratchSources.clear();
        scratchSources.addAll(region.sources.values());
        int verts = merger.merge(scratchSources, region.mesh, region.bound);
        region.dirty = false;
        if (merger.getMismatchedSources() > 0) {
            // Heterogeneous attribute sets within one region : the batch cannot represent them all, so the
            // narrow sources would render with another chunk's leftover attributes. Refuse to batch this
            // region rather than draw it wrong — it keeps rendering per chunk, which is always correct.
            log.warn("Region batch abandoned : {} of {} {} sources lack an attribute the batch carries",
                    merger.getMismatchedSources(), scratchSources.size(), meshType);
            if (region.geometry != null) {
                region.geometry.removeFromParent();
                region.geometry = null;
            }
            showSources(region);
            return 0;
        }
        if (verts == 0) {
            // Every source empty : nothing to draw, so leave the chunks to themselves.
            if (region.geometry != null) {
                region.geometry.setCullHint(Spatial.CullHint.Always);
            }
            return 0;
        }
        if (region.geometry == null) {
            region.geometry = new Geometry(meshType + "-region", region.mesh);
            Geometry model = scratchSources.get(0);
            region.geometry.setQueueBucket(model.getQueueBucket());
            batchNode.attachChild(region.geometry);
        }
        // Kept in sync every rebuild : the terrain material is shared, but re-reading it costs nothing and
        // means a material swap (a theme change, a shader reload) cannot leave the batch on the old one.
        region.geometry.setMaterial(scratchSources.get(0).getMaterial());
        publishBound(region);
        // Now, and only now, the batch stands for these chunks : hide them.
        for (Geometry source : region.sources.values()) {
            source.setCullHint(Spatial.CullHint.Always);
        }
        return scratchSources.size();
    }

    /**
     * Publishes the freshly merged bound to the geometry.
     *
     * <p>This has to go through {@link Geometry#setModelBound} : the merger writes the bound on the MESH, and
     * mutating a mesh bound in place does not invalidate the geometry's cached WORLD bound — jME only
     * recomputes that when the spatial is flagged, and a batch's transform never changes. Miss this and a
     * region keeps the bound of its FIRST merge for ever, so a region whose first merge held one chunk of
     * eight is frustum-culled as soon as that eighth leaves the view : chunks vanish while plainly on screen.
     * Guarded by {@code ChunkRegionBatchBoundTest}, which is a unit test because the screenshot harness
     * attaches 100 pages a frame and so never produces a partial first merge. {@code ChunkMeshBatchState}
     * needs the identical call for the identical reason.
     *
     * <p>Grown by one block as cheap insurance : the terrain mesh does not expand in the vertex shader (the
     * fire billboards that motivated the sibling's margin do), but over-culling is the failure mode here and
     * a block of slack costs nothing measurable.
     */
    private void publishBound(Region region) {
        float margin = BlocksConfig.getInstance().getBlockScale();
        region.bound.setXExtent(region.bound.getXExtent() + margin);
        region.bound.setYExtent(region.bound.getYExtent() + margin);
        region.bound.setZExtent(region.bound.getZExtent() + margin);
        region.geometry.setModelBound(region.bound);
    }

    /**
     * Mirrors the pager's cave-culling verdict onto the batches : a region draws when at least one of its
     * pages does. The pager writes its verdict on the page <b>node</b> and this class writes on the source
     * <b>geometry</b>, so the two never fight — reading the node here is reading cave culling's own answer.
     * Frustum culling needs nothing : each region's bound is its own, so jME culls it as usual.
     */
    private void applyRegionVisibility() {
        for (Region region : regions.values()) {
            if (region.dirty || region.geometry == null) {
                continue; // still rendering per chunk
            }
            boolean anyVisible = false;
            for (Node page : region.pages.values()) {
                if (page.getLocalCullHint() != Spatial.CullHint.Always) {
                    anyVisible = true;
                    break;
                }
            }
            Spatial.CullHint desired = anyVisible ? Spatial.CullHint.Inherit : Spatial.CullHint.Always;
            if (region.geometry.getLocalCullHint() != desired) {
                region.geometry.setCullHint(desired);
            }
        }
    }

    /** Regions currently holding a merged geometry, for tests and diagnostics. */
    public int getRegionCount() {
        return regions.size();
    }

    /**
     * Region size, from {@code -Dialon.batchregion=<n>} when set, else from the config. The override exists
     * so the setting can be A/B'd on device without a rebuild — 1 turns merging off — the same way
     * {@code ialon.vpscale} let the fragment-bound question be settled. It has to be consulted from one
     * place, since both the game and the offline harnesses construct this state.
     */
    public static int regionSizeFrom(IalonConfig config) {
        String override = System.getProperty("ialon.batchregion");
        if (override != null && !override.trim().isEmpty()) {
            try {
                return Integer.parseInt(override.trim());
            } catch (NumberFormatException e) {
                log.warn("Ignoring malformed -Dialon.batchregion={}", override);
            }
        }
        return config.getChunkBatchRegionSize();
    }

    /** Convenience factory reading the region size and per-frame budget from the config. */
    public static ChunkRegionBatchState of(String meshType, IalonConfig config) {
        return new ChunkRegionBatchState(meshType, regionSizeFrom(config),
                config.getChunkBatchRegionsPerFrame());
    }

}
