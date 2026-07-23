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

package org.delaunois.ialon.blocks;

import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.simsilica.mathd.Vec3i;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ChunkPager {

    @Getter
    @NonNull
    private final ChunkManager chunkManager;

    @Getter
    private final Vec3i gridSize;

    @Getter
    private Vec3i centerPage = null;

    @Getter
    @Setter
    private Vector3f location = null;

    @Getter
    @Setter
    private int maxUpdatePerFrame = 10;

    @Getter
    @Setter
    private Vec3i gridLowerBounds = new Vec3i(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);

    @Getter
    @Setter
    private Vec3i gridUpperBounds = new Vec3i(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);

    @Getter
    private final Map<Vec3i, Node> attachedPages = new ConcurrentHashMap<>();

    // Derived views of attachedPages, maintained at trackPage/untrackPage so the cave-culling pass scans
    // only the relevant subsets instead of the whole grid VOLUME. attachedPages also holds the thousands
    // of air/empty pages (kept for connectivity + paging), so scanning it wholesale costs 16-27 ms on a
    // chunk-cross. scenePages = pages actually in the scene graph (have a mesh to cull) ; occluders =
    // pages whose connectivity is not CONNECT_ALL (the only cells that can block the BFS).
    @Getter
    private final Map<Vec3i, Node> scenePages = new ConcurrentHashMap<>();
    // Occluders : pages whose connectivity is not CONNECT_ALL, i.e. the only cells that can block the BFS.
    // Their connectivity bitset is stored directly (computed once at trackPage) so the per-pass
    // cacheConnectivity avoids a String-keyed getUserData per occluder — there can be thousands when the
    // grid spans dense/underground terrain. Held as flat slots rather than a map for the same reason as the
    // cull list below : see ChunkSlots.
    private final ChunkSlots occluderSlots = new ChunkSlots();
    private short[] occluderConn = new short[0];

    // --- Flat mirror of scenePages, for applyVisibility only. -----------------------------------------
    // The cull-hint pass has to visit every scene page, and it was doing so by iterating the
    // ConcurrentHashMap : ~660 entries, each costing a Map.Entry plus a key and a value dereference into
    // objects scattered across the heap. That is pointer chasing, not arithmetic — and it measured 0.3 us
    // per page on desktop against 5 us on device, i.e. ~20x the CPU ratio between the two, because ART's
    // concurrent-copying collector puts a read barrier on every reference load. The phase split added to
    // the slow-paging line is what exposed it (apply 1.9-4.3 ms, as large as the flood-fill itself).
    //
    // So the pass now walks primitive arrays instead : three int arrays for the coordinates (contiguous,
    // no barriers, no cache misses) and a shadow of the cull state we last applied, so a page's Node is
    // only ever dereferenced when its visibility actually flips — a handful per pass rather than all 660.
    //
    // Maintained incrementally by trackPage/untrackPage (a few calls per frame) rather than rebuilt, since
    // rebuilding would mean doing the very map scan this replaces. scenePages stays the source of truth
    // and keeps its public getter.
    private final ChunkSlots cullSlots = new ChunkSlots();
    private Node[] cullNodes = new Node[0];
    private boolean[] cullApplied = new boolean[0];

    @Getter
    private final Map<Vec3i, Chunk> fetchedPages = new ConcurrentHashMap<>();

    // Cumulative count of scene-graph page mutations (attach + detach). Used by profiling/diagnostics
    // to tell a genuinely idle frame (delta 0) from one that re-meshed a chunk in place (detach+attach,
    // net child count 0) — the latter still dirties the chunk node and forces a world-bound refresh.
    @Getter
    private long pageOps;

    private final Queue<Chunk> pagesToAttach = new ConcurrentLinkedQueue<>();
    private final Queue<Vec3i> pagesToDetach = new ConcurrentLinkedQueue<>();
    private final Queue<Vec3i> pagesToUnfetch = new ConcurrentLinkedQueue<>();
    // Locations sitting in pagesToAttach : meshed, but not yet in the scene graph (the per-frame budget
    // spreads attaching over several frames). They must count as "already handled" when updateQueues diffs
    // the grid box, otherwise every pass re-requests them and the duplicated meshing snowballs — see
    // ChunkManager#meshInFlight, which covers the earlier half of the same window (mesh running).
    private final Set<Vec3i> pagesPendingAttach = ConcurrentHashMap.newKeySet();
    private final ChunkManagerListener listener = new ChunkPagerListener();
    private final Node node;
    private ExecutorService requestExecutor;

    // Scratch buffers reused across updateQueues() invocations to avoid per-crossing allocation.
    // Only ever touched on the single-threaded requestExecutor, so no synchronization is needed.
    private final Set<Vec3i> pagesToMesh = new HashSet<>();
    private final Set<Vec3i> pagesToFetch = new HashSet<>();
    private final Comparator<Vec3i> meshDistanceComparator = Comparator.comparingInt(vec -> vec.getDistanceSq(centerPage));

    // --- Cave culling : occlusion culling via the chunk visibility graph. A connectivity-only BFS from
    // the chunk holding the player marks every chunk sight can reach THROUGH open faces (air/glass/...),
    // and hides the rest (chunks sealed behind opaque rock — underground pockets, behind walls) with
    // CullHint.Always. View-direction (frustum) culling is left to jME's per-geometry pass, so this BFS
    // is independent of camera rotation and only needs to re-run when the player crosses a chunk
    // boundary or the world is edited — NOT every frame. ---
    @Getter
    @Setter
    private boolean caveCullingEnabled = true;

    private static final Direction[] DIRS = Direction.values();
    // Direction data flattened to primitive arrays so the BFS inner loop avoids per-node method calls
    // (getVector()/opposite()) and the connectivity check avoids Chunk.isConnected()'s call + swap.
    private static final int[] DIR_DX = new int[6];
    private static final int[] DIR_DY = new int[6];
    private static final int[] DIR_DZ = new int[6];
    private static final int[] DIR_OPP = new int[6]; // opposite direction's ordinal
    private static final int[] PAIR_BIT = new int[36]; // bit index for each (faceA, faceB) ordinal pair
    static {
        for (Direction d : DIRS) {
            int o = d.ordinal();
            DIR_DX[o] = d.getVector().x;
            DIR_DY[o] = d.getVector().y;
            DIR_DZ[o] = d.getVector().z;
            DIR_OPP[o] = d.opposite().ordinal();
        }
        for (int a = 0; a < 6; a++) {
            for (int b = 0; b < 6; b++) {
                PAIR_BIT[a * 6 + b] = pairBitOf(a, b);
            }
        }
    }

    /** Same mapping as {@link Chunk#isConnected}'s private pairBit, precomputed into {@link #PAIR_BIT}. */
    private static int pairBitOf(int a, int b) {
        if (a > b) {
            int t = a;
            a = b;
            b = t;
        }
        return a * 5 - (a * (a - 1)) / 2 + (b - a - 1);
    }
    // Minimum spacing between BFS re-runs triggered by paging/edits (a chunk crossing always re-runs
    // immediately). Coalesces the per-frame attach churn during movement into a few runs per second.
    private static final long VISIBILITY_MIN_INTERVAL_NANOS = 200_000_000L;

    private boolean visibilityComputed = false;
    // Set when a page is attached/detached (new chunks streamed in, or an edit remeshed a chunk, which
    // can change connectivity). Honoured on a throttle so it does not force a BFS every frame.
    private volatile boolean visibilityDirty = false;
    private final Vec3i lastVisibilityRoot = new Vec3i(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
    private long lastVisibilityNanos = 0;
    // Last visibility pass's split, for the slow-paging log only (see updateChunkVisibility).
    private long visibilityBfsNanos;
    private long visibilityApplyNanos;

    // BFS scratch, sized to the mesh grid and reused across runs. Stamps are epoch-marked so the
    // arrays never need clearing. Only ever touched on the update thread (single-threaded).
    private int visGridX;
    private int visGridY;
    private int visGridZ;
    private int visOriginX;
    private int visOriginY;
    private int visOriginZ;
    private int[] bfsQueue = new int[0];
    private int[] bfsCoord = new int[0];    // packed (lx,ly,lz) parallel to bfsQueue : avoids div decode
    private byte[] bfsEnterQueue = new byte[0]; // enter-face ordinal + 1 (0 = root), parallel to bfsQueue
    private final int[] visDli = new int[6]; // neighbour linear-index deltas for the current grid stride
    private int[] bfsStamp = new int[0];    // == bfsEpoch  : visited (enqueued at least once)
    private int[] reachStamp = new int[0];  // == reachEpoch : sight reaches this chunk (keep visible)
    // Bitset (by Direction ordinal) of the faces this cell has already been entered through. An occluder
    // is expanded once per enter face : a chunk with several disconnected pockets lets sight through
    // from one face and not from another, so the first arrival does not decide for the others. Cells
    // with no gate (air, see-through, not loaded) expand the same way whatever the enter face, so they
    // are marked all-faces-done on their first visit and stay a single-visit BFS node.
    private byte[] bfsEnterMask = new byte[0];
    private static final int ALL_FACES = 0x3F;
    // Per-cell connectivity cached into a flat array once per pass (one entrySet scan), so the BFS reads
    // it by index instead of doing a ConcurrentHashMap.get(Vec3i) + getUserData per node. Air / not-yet-
    // loaded cells (the bulk of the grid volume) carry no stamp and read as CONNECT_ALL (traversable),
    // turning thousands of map misses per pass into free array reads.
    private short[] connGrid = new short[0];
    private int[] connStamp = new int[0];   // == connEpoch : this cell's connGrid entry is populated
    private int bfsEpoch = 0;
    private int reachEpoch = 0;
    private int connEpoch = 0;

    public ChunkPager(Node node, @NonNull ChunkManager chunkManager) {
        this.node = node;
        this.chunkManager = chunkManager;
        this.gridSize = BlocksConfig.getInstance().getGrid();
    }

    public void initialize() {
        if (log.isTraceEnabled()) {
            log.trace("{} initialize()", getClass().getSimpleName());
        }

        this.gridSize.set(BlocksConfig.getInstance().getGrid());
        chunkManager.addListener(listener);
        requestExecutor = Executors.newSingleThreadExecutor(
                ChunkThreads.factory("chunk-pager-%d", ChunkThreads.PAGING_PRIORITY));
    }

    /**
     * A paging update slower than this is logged with its phase split. All of this runs on the render
     * thread, so it is frame time : the device profiler reported {@code sub=ChunkPagerState} at 4-13 ms
     * routinely and once at 29 ms, which is 2-4x what the water merge costs, and a single number cannot say
     * which phase is responsible. The candidates are genuinely different problems : the cave-culling BFS is
     * O(grid volume) and throttled, while unfetching is O(budget) and its budget was deliberately raised —
     * see {@link #maxHandledPerFrame()}. Same reasoning as {@code ChunkMeshBatchState}'s slow-merge line.
     */
    private static final long SLOW_UPDATE_MS = 5;

    public void update() {
        if (location == null) {
            return;
        }

        long t0 = System.nanoTime();
        updateCenterPage();
        long t1 = System.nanoTime();
        int unfetched = unfetchNextPages();
        long t2 = System.nanoTime();
        int detached = detachNextPages();
        long t3 = System.nanoTime();
        int attached = attachNextPages();
        long t4 = System.nanoTime();
        boolean visibilityRan = updateChunkVisibility();
        long t5 = System.nanoTime();

        if (t5 - t0 >= SLOW_UPDATE_MS * 1_000_000L) {
            logSlowUpdate(t0, t1, t2, t3, t4, t5, unfetched, detached, attached, visibilityRan);
        }
    }

    /**
     * The phase split of one slow paging update. The <b>queue remainders</b> matter as much as the times :
     * they are what distinguishes "this phase had a lot to do" from "this phase hit its per-frame budget and
     * is now the pacing limit". The visibility phase is split into its flood-fill and its cull-hint
     * application, because the first is O(grid volume) and the second O(scene pages) — different fixes.
     */
    private void logSlowUpdate(long t0, long t1, long t2, long t3, long t4, long t5,
                               int unfetched, int detached, int attached, boolean visibilityRan) {
        log.warn("Slow chunk paging : {}ms [center {} / unfetch {} ({} done, {} left)"
                        + " / detach {} ({} done, {} left) / attach {} ({} done, {} left)"
                        + " / visibility {} ({})] scenePages {} occluders {}",
                ms(t5 - t0), ms(t1 - t0),
                ms(t2 - t1), unfetched, pagesToUnfetch.size(),
                ms(t3 - t2), detached, pagesToDetach.size(),
                ms(t4 - t3), attached, pagesToAttach.size(),
                ms(t5 - t4), visibilityRan
                        ? "bfs " + ms(visibilityBfsNanos) + " / apply " + ms(visibilityApplyNanos)
                        : "skipped",
                scenePages.size(), occluderSlots.size());
    }

    private static String ms(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1_000_000.0);
    }

    public void updateGridSize() {
        this.gridSize.set(BlocksConfig.getInstance().getGrid());
        requestExecutor.submit(this::updateQueues);
    }

    protected void updateCenterPage() {
        Vec3i newCenterPage = ChunkManager.getChunkLocation(location);
        if (!Objects.equals(newCenterPage, centerPage)) {
            this.centerPage = newCenterPage;
            if (log.isDebugEnabled()) {
                log.debug("New center page {}", newCenterPage);
            }
            requestExecutor.submit(this::updateQueues);
        }
    }

    protected void updateQueues() {
        final int halfGridSizeX = (gridSize.x - 1) / 2;
        final int halfGridSizeY = (gridSize.y - 1) / 2;
        final int halfGridSizeZ = (gridSize.z - 1) / 2;
        final int meshMinX = Math.max(centerPage.x - halfGridSizeX, gridLowerBounds.x);
        final int meshMinY = Math.max(centerPage.y - halfGridSizeY, gridLowerBounds.y);
        final int meshMinZ = Math.max(centerPage.z - halfGridSizeZ, gridLowerBounds.z);
        final int meshMaxX = Math.min(centerPage.x + halfGridSizeX, gridUpperBounds.x);
        final int meshMaxY = Math.min(centerPage.y + halfGridSizeY, gridUpperBounds.y);
        final int meshMaxZ = Math.min(centerPage.z + halfGridSizeZ, gridUpperBounds.z);
        final int fetchMinX = Math.max(meshMinX - 1, gridLowerBounds.x);
        final int fetchMinY = Math.max(meshMinY - 1, gridLowerBounds.y);
        final int fetchMinZ = Math.max(meshMinZ - 1, gridLowerBounds.z);
        final int fetchMaxX = Math.min(meshMaxX + 1, gridUpperBounds.x);
        final int fetchMaxY = Math.min(meshMaxY + 1, gridUpperBounds.y);
        final int fetchMaxZ = Math.min(meshMaxZ + 1, gridUpperBounds.z);
        final Vec3i meshMin = new Vec3i(meshMinX, meshMinY, meshMinZ);
        final Vec3i meshMax = new Vec3i(meshMaxX, meshMaxY, meshMaxZ);

        if (log.isDebugEnabled()) {
            log.debug("Grid is set to ({}:{}, {}:{}, {}:{})", meshMinX, meshMaxX, meshMinY, meshMaxY, meshMinZ, meshMaxZ);
        }

        pagesToMesh.clear();
        pagesToFetch.clear();

        for (int x = fetchMinX; x <= fetchMaxX; x++) {
            for (int y = fetchMinY; y <= fetchMaxY; y++) {
                for (int z = fetchMinZ; z <= fetchMaxZ; z++) {
                    Vec3i pageLocation = new Vec3i(x, y, z);
                    pagesToFetch.add(pageLocation);
                    addPageToMesh(pageLocation, meshMin, meshMax, pagesToMesh);
                }
            }
        }

        pagesToDetach.clear();
        for (Vec3i page : attachedPages.keySet()) {
            if (pagesToMesh.contains(page)) {
                // page already meshed
                pagesToMesh.remove(page);
            } else {
                // detach pages outside of the grid
                pagesToDetach.offer(page);
            }
        }

        // Pages already meshed and waiting for their turn to be attached need no new mesh request.
        pagesToMesh.removeAll(pagesPendingAttach);

        pagesToUnfetch.clear();
        for (Vec3i page : fetchedPages.keySet()) {
            if (pagesToFetch.contains(page)) {
                // page already fetched
                pagesToFetch.remove(page);
            } else {
                // detach fetched pages outside of the grid
                pagesToUnfetch.add(page);
            }
        }

        // request the new pages to load/generate and mesh
        // Mesh pages sorted by distance to the center so the closest chunks appear first.
        List<Vec3i> meshList = new ArrayList<>(pagesToMesh);
        meshList.sort(meshDistanceComparator);
        chunkManager.requestChunks(new ArrayList<>(pagesToFetch), meshList);

        pagesToMesh.clear();
        pagesToFetch.clear();
    }

    private void addPageToMesh(Vec3i pageLocation, Vec3i meshMin, Vec3i meshMax, Set<Vec3i> pagesToMesh) {
        if (pageLocation.x >= meshMin.x && pageLocation.x <= meshMax.x
                && pageLocation.y >= meshMin.y && pageLocation.y <= meshMax.y
                && pageLocation.z >= meshMin.z && pageLocation.z <= meshMax.z) {
            pagesToMesh.add(pageLocation);
        }
    }

    /** @return pages examined this frame (air pages included : they cost bookkeeping, not scene work) */
    private int detachNextPages() {
        Vec3i pageLocation = pagesToDetach.poll();
        int removed = 0;
        int handled = 0;

        while (pageLocation != null) {
            Node page = attachedPages.get(pageLocation);
            if (page == null) {
                log.warn("Trying to detach page at location {} that isn't attached.", pageLocation);
            } else {
                detachPage(page);
                untrackPage(pageLocation);
                // Air pages are not in the scene graph : same reasoning as attachNextPages. Detaching must
                // also keep pace with attaching, or pages linger outside the grid.
                if (!(page instanceof EmptyNode)) {
                    removed += 1;
                }
                handled += 1;
            }

            if (removed < maxUpdatePerFrame && handled < maxHandledPerFrame()) {
                pageLocation = pagesToDetach.poll();
            } else {
                // Spread large detach bursts (e.g. grid shrink or teleport) over several frames.
                pageLocation = null;
            }
        }

        if (handled > 0) {
            pageOps += removed;
            visibilityDirty = true;
            log.trace("{} pages removed ({} handled)", removed, handled);
        }
        return handled;
    }

    /** @return pages unfetched (cache-evicted) this frame */
    private int unfetchNextPages() {
        Vec3i pageLocation = pagesToUnfetch.poll();
        int unfetched = 0;

        while (pageLocation != null) {
            Chunk page = fetchedPages.get(pageLocation);
            if (page == null) {
                log.warn("Trying to unfetch page at location {} that isn't attached.", pageLocation);
            } else {
                fetchedPages.remove(pageLocation);
                // Notify (chunk still cached) so e.g. the far terrain can capture an edited chunk's
                // final relief as it leaves the grid, then evict.
                chunkManager.triggerListenerChunkUnfetched(page);
                chunkManager.fastRemoveChunk(pageLocation);
                unfetched += 1;
            }

            // Unfetching costs a map removal and a cache eviction — no scene-graph work, no meshing — so it
            // gets a far larger budget than attaching. It has to : a chunk crossing unfetches as many
            // chunks as it fetches, and if eviction cannot keep up the cache keeps growing behind a walking
            // player until the memory guard cuts the render distance.
            if (unfetched < maxHandledPerFrame()) {
                pageLocation = pagesToUnfetch.poll();
            } else {
                // Spread large unfetch bursts (e.g. grid shrink or teleport) over several frames.
                pageLocation = null;
            }
        }

        if (unfetched > 0) {
            log.trace("{} pages unfetched", unfetched);
        }
        return unfetched;
    }

    /** @return pages examined this frame (air pages included : see the budget note inside) */
    protected int attachNextPages() {
        Chunk chunk = pagesToAttach.poll();
        int attached = 0;
        int handled = 0;

        while (chunk != null) {
            pagesPendingAttach.remove(chunk.getLocation());

            // detach the old page if any
            Node oldPage = untrackPage(chunk.getLocation());
            if (oldPage != null) {
                detachPage(oldPage);
            }

            // Create the new page
            Node newPage = createPage(chunk);
            trackPage(chunk.getLocation(), newPage);
            if (newPage != null) {
                attachPage(newPage);
                // Air / fully-hidden pages never enter the scene graph (see attachPage) : tracking one
                // costs a map put, so charging it against the per-frame budget would be paying scene-graph
                // prices for nothing. They are the MAJORITY of a paged-in slab (typically 3 of every 4
                // chunks are empty), so counting them used to throttle real geometry to a quarter of the
                // configured rate — which is what let the grid fall behind a walking player.
                if (!(newPage instanceof EmptyNode)) {
                    attached += 1;
                }
            }
            handled += 1;

            if (attached < maxUpdatePerFrame && handled < maxHandledPerFrame()) {
                chunk = pagesToAttach.poll();
            } else {
                // Timeout for this frame. Stop work now.
                chunk = null;
            }
        }

        if (handled > 0) {
            pageOps += attached;
            visibilityDirty = true;
            log.trace("{} pages attached ({} handled)", attached, handled);
        }
        return handled;
    }

    /**
     * Hard ceiling on pages examined in one frame, whatever their cost. The per-frame budget only counts
     * pages that actually reach the scene graph, so this keeps a huge burst of air pages (a teleport, a
     * render-distance change) from being drained in a single frame.
     */
    private int maxHandledPerFrame() {
        return maxUpdatePerFrame * 8;
    }

    protected Node createPage(Chunk chunk) {
        return chunk.getNode();
    }

    protected void detachPage(Node page) {
        if (log.isTraceEnabled()) {
            log.trace("Detaching {} from {}", page, node);
        }
        // Empty (air) pages are never put in the scene graph (see attachPage) — nothing to detach.
        if (page instanceof EmptyNode || node instanceof EmptyNode) {
            return;
        }
        node.detachChild(page);
    }

    /**
     * Records a page in {@link #attachedPages} and updates the derived {@link #scenePages} /
     * {@link #occluders} views used by the cave-culling pass. Package-private so cave-culling tests can
     * populate the pager through the same path the real paging uses.
     */
    void trackPage(Vec3i location, Node page) {
        attachedPages.put(location, page);
        if (page instanceof EmptyNode) {
            scenePages.remove(location); // air / fully-internal chunk : nothing in the scene to cull
            cullRemove(location);
        } else {
            scenePages.put(location, page);
            cullPut(location, page);
            firePageTracked(location, page);
        }
        short conn = connectivityOf(page);
        if (conn != Chunk.CONNECT_ALL) {
            int slot = occluderSlots.put(location); // can block sight : the BFS must see its connectivity
            if (slot == occluderConn.length) {
                occluderConn = Arrays.copyOf(occluderConn, Math.max(64, slot * 2));
            }
            occluderConn[slot] = conn;
        } else {
            occluderRemove(location);
        }
    }

    /** Removes a page from {@link #attachedPages} and the derived views, returning the removed node. */
    private Node untrackPage(Vec3i location) {
        Node removed = attachedPages.remove(location);
        Node fromScene = scenePages.remove(location);
        cullRemove(location);
        occluderRemove(location);
        if (fromScene != null) {
            firePageUntracked(location, fromScene);
        }
        return removed;
    }

    /**
     * Notified as pages enter and leave the scene graph. Every page change funnels through
     * {@link #trackPage}/{@link #untrackPage} — including an in-place re-mesh, which untracks the old
     * node and tracks the new one — so a listener sees the complete history and can maintain a derived
     * view incrementally instead of rescanning {@link #getScenePages()}.
     *
     * <p>That is the whole point : the merged-mesh states used to walk all ~620 pages on every frame the
     * pager reported activity, which measured 7-13 ms on device, to find the two pages that had actually
     * changed. Listeners are called on the render thread, inside the paging pass.
     */
    /**
     * <p>Not to be confused with {@link ChunkManagerListener} (which {@link ChunkPagerListener}, this
     * pager's own private inner class, implements) : that one reports on chunk DATA — available, fetched,
     * updated — and fires before any page node exists, with no notion of detachment. This one reports on
     * the SCENE PAGE, one step later and one level down, which is what a consumer needs when it cares
     * about the geometries rather than the blocks.
     */
    public interface ScenePageListener {

        void onPageTracked(Vec3i location, Node page);

        void onPageUntracked(Vec3i location, Node page);
    }

    private final List<ScenePageListener> pageListeners = new CopyOnWriteArrayList<>();

    public void addScenePageListener(ScenePageListener listener) {
        pageListeners.add(listener);
    }

    public void removeScenePageListener(ScenePageListener listener) {
        pageListeners.remove(listener);
    }

    private void firePageTracked(Vec3i location, Node page) {
        for (ScenePageListener listener : pageListeners) {
            listener.onPageTracked(location, page);
        }
    }

    private void firePageUntracked(Vec3i location, Node page) {
        for (ScenePageListener listener : pageListeners) {
            listener.onPageUntracked(location, page);
        }
    }

    private static short connectivityOf(Node page) {
        Object ud = page.getUserData(Chunk.USERDATA_FACE_CONNECTIVITY);
        return (ud instanceof Integer) ? (short) ((Integer) ud).intValue() : Chunk.CONNECT_ALL;
    }

    protected void attachPage(Node page) {
        if (log.isTraceEnabled()) {
            log.trace("Attaching {} to {}", page, node);
        }
        // Air chunks render nothing and carry no controls : keep them out of the scene graph (they stay
        // tracked in attachedPages for paging + cave-culling connectivity). This shrinks the graph jME
        // recurses through when it rebuilds its update list on every paging frame — the residual
        // updateLogicalState ('rootLog') hitch, which scales with the attached chunk-node count.
        if (page instanceof EmptyNode || node instanceof EmptyNode) {
            return;
        }
        node.attachChild(page);
    }

    /**
     * Cave culling. Recomputes which loaded chunks sight can reach through the connectivity graph and
     * hides the rest (occluded behind opaque terrain) with {@link Spatial.CullHint#Always}. Occlusion
     * is view-independent, so this runs only when the player crosses a chunk boundary, or — throttled —
     * when paging/edits dirtied the graph ; NOT on camera rotation and NOT every frame. Cheap to call
     * every frame : it early-outs when nothing relevant changed. Frustum culling stays jME's job.
     */
    private boolean updateChunkVisibility() {
        if (!caveCullingEnabled) {
            if (visibilityComputed) {
                // Culling was just turned off : un-hide everything.
                showAllPages();
                visibilityComputed = false;
            }
            return false;
        }
        if (centerPage == null) {
            return false;
        }

        boolean rootChanged = !centerPage.equals(lastVisibilityRoot);
        long now = System.nanoTime();
        boolean dirtyDue = visibilityDirty && (now - lastVisibilityNanos) >= VISIBILITY_MIN_INTERVAL_NANOS;
        if (visibilityComputed && !rootChanged && !dirtyDue) {
            return false;
        }
        lastVisibilityRoot.set(centerPage);
        lastVisibilityNanos = now;
        visibilityDirty = false;

        // Timed apart because the two halves scale with different things and would need different fixes :
        // the flood-fill is O(grid volume) (17x21x17 cells here) while applying the result is O(scene pages)
        // (~700). Only ever read by logSlowUpdate, on the frame they were measured.
        computeReachable(centerPage);
        long afterBfs = System.nanoTime();
        applyVisibility();
        visibilityBfsNanos = afterBfs - now;
        visibilityApplyNanos = System.nanoTime() - afterBfs;
        visibilityComputed = true;
        return true;
    }

    /**
     * Test seam : runs the cave-culling visibility pass for a given player-chunk root, against the
     * pages currently in {@link #attachedPages} (each carrying its connectivity as userData). Bypasses
     * the throttle and the paging executor.
     */
    void applyCaveCullingForTest(Vec3i root) {
        computeReachable(root);
        applyVisibility();
    }

    /**
     * Connectivity flood-fill from the chunk holding the player. A chunk is traversed from the face it
     * was entered to a neighbour face only if its {@link Chunk#getFaceConnectivity()} links them (so a
     * chunk of solid opaque rock blocks sight). Reachable chunks are stamped in {@link #reachStamp} ;
     * everything else is occluded. No frustum test (jME culls the view direction per geometry) and no
     * monotonic-direction pruning (which could falsely occlude a chunk visible only via a bent tunnel).
     */
    private void computeReachable(Vec3i root) {
        final int gx = gridSize.x;
        final int gz = gridSize.z;
        final int minY = visMinY(root);
        final int maxY = visMaxY(root);
        final int gy = maxY - minY + 1;
        ensureVisScratch(gx, gy, gz);
        visGridX = gx;
        visGridY = gy;
        visGridZ = gz;
        visOriginX = root.x - (gx - 1) / 2;
        visOriginY = minY;
        visOriginZ = root.z - (gz - 1) / 2;

        bfsEpoch++;
        reachEpoch++;
        cacheConnectivity();

        int rootLocal = localIndex(root.x, root.y, root.z);
        if (rootLocal < 0) {
            return; // camera chunk outside its own grid : should not happen
        }

        // Neighbour linear-index deltas for this grid (constant stride per direction), computed once.
        final int strideZ = visGridX * visGridY;
        for (int di = 0; di < 6; di++) {
            visDli[di] = DIR_DX[di] + DIR_DY[di] * visGridX + DIR_DZ[di] * strideZ;
        }

        int head = 0;
        int tail = 0;
        bfsQueue[tail] = rootLocal;
        bfsCoord[tail] = pack(root.x - visOriginX, root.y - visOriginY, root.z - visOriginZ);
        bfsEnterQueue[tail] = 0; // root : no enter face, connectivity gate disabled
        tail++;
        bfsStamp[rootLocal] = bfsEpoch;
        bfsEnterMask[rootLocal] = (byte) ALL_FACES; // the root is expanded ungated : never re-expand it

        while (head < tail) {
            int li = bfsQueue[head];
            int code = bfsCoord[head];
            int enter = bfsEnterQueue[head];
            head++;
            reachStamp[li] = reachEpoch;

            // lx/ly/lz come packed in the queue : avoids three integer divisions per popped node.
            int lx = code & 0x3FF;
            int ly = (code >> 10) & 0x3FF;
            int lz = (code >> 20) & 0x3FF;

            short conn = connStamp[li] == connEpoch ? connGrid[li] : Chunk.CONNECT_ALL;
            int enterOrd = enter - 1; // meaningful only when enter != 0

            for (int di = 0; di < 6; di++) {
                // Connectivity gate (disabled for the root, enter == 0). Inlined Chunk.isConnected : the
                // same face is always connected, otherwise test the precomputed (enter, di) pair bit.
                if (enter != 0 && enterOrd != di && (conn & (1 << PAIR_BIT[enterOrd * 6 + di])) == 0) {
                    continue;
                }
                int nlx = lx + DIR_DX[di];
                int nly = ly + DIR_DY[di];
                int nlz = lz + DIR_DZ[di];
                if (nlx < 0 || nlx >= visGridX || nly < 0 || nly >= visGridY || nlz < 0 || nlz >= visGridZ) {
                    continue;
                }
                int ni = li + visDli[di];
                int nEnter = DIR_OPP[di]; // face of the neighbour sight enters through
                int mask = bfsStamp[ni] == bfsEpoch ? bfsEnterMask[ni] : 0;
                if ((mask & (1 << nEnter)) != 0) {
                    continue; // already expanded from that face
                }
                bfsStamp[ni] = bfsEpoch;
                // Only an occluder (a cell with a connectivity gate) needs one expansion per enter
                // face ; anything else propagates identically whatever face it was entered by.
                bfsEnterMask[ni] = (byte) (connStamp[ni] == connEpoch ? (mask | (1 << nEnter)) : ALL_FACES);
                bfsQueue[tail] = ni;
                bfsCoord[tail] = pack(nlx, nly, nlz);
                bfsEnterQueue[tail] = (byte) (nEnter + 1);
                tail++;
            }
        }
    }

    /*
     * Vertical extent of the flood-fill grid. X and Z use the full grid size (the player really can be
     * anywhere horizontally), but Y is bounded by the world : {@code gridSize.y} is gridHeight*2+1 — 21
     * layers for a 10-layer world — centred on the player's chunk, so MORE THAN HALF the volume the BFS
     * walked was cells that can never hold a chunk. Measured as the dominant phase of the paging update
     * (desktop: bfs 0.8-1.3 ms of a 1.0-1.5 ms total, so 5-10 ms on device at 5-8x), which is what the
     * slow-paging line was added to establish.
     *
     * <p>One empty layer is kept on each side ON PURPOSE : sight must still be able to travel over the top
     * of the world and come back down into a chunk whose top face is open, and unloaded cells are what lets
     * the BFS route through. And the window always contains the root, because the player can fly above the
     * world — clamping them out would make localIndex reject the root and silently disable cave culling
     * exactly when the most chunks are on screen.
     */
    private int visMinY(Vec3i root) {
        int lo = gridLowerBounds.y == Integer.MIN_VALUE
                ? root.y - (gridSize.y - 1) / 2
                : gridLowerBounds.y - 1;
        return Math.min(lo, root.y);
    }

    private int visMaxY(Vec3i root) {
        int hi = gridUpperBounds.y == Integer.MAX_VALUE
                ? root.y + (gridSize.y - 1) / 2
                : gridUpperBounds.y + 1;
        return Math.max(hi, root.y);
    }

    /** Packs a local grid coordinate (each axis &lt; 1024) into one int for the BFS queue. */
    private static int pack(int lx, int ly, int lz) {
        return lx | (ly << 10) | (lz << 20);
    }

    /** Sets each scene page's cull hint from the BFS result, touching only the pages that changed. Only
     * scene pages (non-empty, actually in the graph) have a mesh to cull — air/empty pages are skipped. */
    /**
     * Adds (or re-points, after an in-place re-mesh) a scene page's slot in the flat cull arrays. A fresh
     * page node starts at {@code CullHint.Inherit}, so the shadow starts "not culled" to match.
     */
    private void cullPut(Vec3i location, Node page) {
        int slot = cullSlots.put(location);
        if (slot == cullNodes.length) {
            int capacity = Math.max(64, slot * 2);
            cullNodes = Arrays.copyOf(cullNodes, capacity);
            cullApplied = Arrays.copyOf(cullApplied, capacity);
        }
        cullNodes[slot] = page;
        // False whether the slot is new or re-pointed : a replacement node carries the default (visible) hint.
        cullApplied[slot] = false;
    }

    /** Follows {@link ChunkSlots#remove}'s swap with this pass's own payload arrays. */
    private void cullRemove(Vec3i location) {
        long removal = cullSlots.remove(location);
        if (removal == ChunkSlots.ABSENT) {
            return;
        }
        int vacated = ChunkSlots.vacated(removal);
        int movedFrom = ChunkSlots.movedFrom(removal);
        if (movedFrom >= 0) {
            cullNodes[vacated] = cullNodes[movedFrom];
            cullApplied[vacated] = cullApplied[movedFrom];
        }
        cullNodes[cullSlots.size()] = null; // do not pin a detached page
    }

    /** Same, for the occluder list. */
    private void occluderRemove(Vec3i location) {
        long removal = occluderSlots.remove(location);
        if (removal == ChunkSlots.ABSENT) {
            return;
        }
        int movedFrom = ChunkSlots.movedFrom(removal);
        if (movedFrom >= 0) {
            occluderConn[ChunkSlots.vacated(removal)] = occluderConn[movedFrom];
        }
    }

    private void applyVisibility() {
        final int[] xs = cullSlots.xs();
        final int[] ys = cullSlots.ys();
        final int[] zs = cullSlots.zs();
        final boolean[] applied = cullApplied;
        final int n = cullSlots.size();
        for (int i = 0; i < n; i++) {
            int li = localIndex(xs[i], ys[i], zs[i]);
            // Pages outside the current BFS grid (transient, about to be detached) are left visible.
            boolean cull = li >= 0 && reachStamp[li] != reachEpoch;
            if (cull != applied[i]) {
                applied[i] = cull;
                cullNodes[i].setCullHint(cull ? Spatial.CullHint.Always : Spatial.CullHint.Inherit);
            }
        }
    }

    private void showAllPages() {
        for (int i = 0; i < cullSlots.size(); i++) {
            if (cullApplied[i]) {
                cullApplied[i] = false;
                cullNodes[i].setCullHint(Spatial.CullHint.Inherit);
            }
        }
    }

    /**
     * Caches every attached chunk's face-connectivity into the flat {@link #connGrid}, indexed by the
     * current BFS grid, in a single {@link #attachedPages} scan. Cells left unstamped (air, or chunks not
     * yet meshed/loaded) read back as {@link Chunk#CONNECT_ALL} — they do not occlude. Replaces the
     * per-BFS-node {@code ConcurrentHashMap.get(Vec3i)} + {@code getUserData} that dominated the pass when
     * the grid volume (thousands of mostly-air cells) far exceeds the ~hundreds of loaded chunks.
     */
    private void cacheConnectivity() {
        connEpoch++;
        // Only occluders (connectivity != CONNECT_ALL) need stamping : every other cell — air, open
        // terrain, not-yet-loaded — reads back as CONNECT_ALL (traversable) from the unstamped default.
        final int[] xs = occluderSlots.xs();
        final int[] ys = occluderSlots.ys();
        final int[] zs = occluderSlots.zs();
        final short[] conn = occluderConn;
        final int n = occluderSlots.size();
        for (int i = 0; i < n; i++) {
            int li = localIndex(xs[i], ys[i], zs[i]);
            if (li < 0) {
                continue; // outside the current BFS grid (transient, about to be detached)
            }
            connGrid[li] = conn[i];
            connStamp[li] = connEpoch;
        }
    }

    private int localIndex(int wx, int wy, int wz) {
        int lx = wx - visOriginX;
        int ly = wy - visOriginY;
        int lz = wz - visOriginZ;
        if (lx < 0 || lx >= visGridX || ly < 0 || ly >= visGridY || lz < 0 || lz >= visGridZ) {
            return -1;
        }
        return lx + visGridX * (ly + visGridY * lz);
    }

    private void ensureVisScratch(int gx, int gy, int gz) {
        int volume = gx * gy * gz;
        if (bfsStamp.length < volume) {
            // An occluder can be enqueued once per enter face, so the queue holds up to 6 entries per
            // cell. In practice far fewer : see-through cells (the bulk of the grid) are enqueued once,
            // and the BFS stops at the first shell of rock.
            int capacity = volume * 6;
            bfsQueue = new int[capacity];
            bfsCoord = new int[capacity];
            bfsEnterQueue = new byte[capacity];
            bfsStamp = new int[volume];
            reachStamp = new int[volume];
            bfsEnterMask = new byte[volume];
            connGrid = new short[volume];
            connStamp = new int[volume];
            // Freshly-zeroed stamps : reset epochs so they cannot match a stale value.
            bfsEpoch = 0;
            reachEpoch = 0;
            connEpoch = 0;
        }
    }

    public void cleanup() {
        cleanup(0);
    }

    public void cleanup(long timeout) {
        if (log.isTraceEnabled()) {
            log.trace("{} cleanup()", getClass().getSimpleName());
        }
        requestExecutor.shutdown();
        try {
            if (!requestExecutor.awaitTermination(timeout, TimeUnit.MILLISECONDS) && timeout > 0) {
                log.warn("Executors did not terminate properly within {}ms timeout", timeout);
            }
        } catch (InterruptedException e) {
            log.error("Interrupted while cleaning up {}", getClass().getSimpleName());
            Thread.currentThread().interrupt();
        }
        attachedPages.forEach((loc, page) -> detachPage(page));
        attachedPages.clear();
        scenePages.clear();
        Arrays.fill(cullNodes, 0, cullSlots.size(), null);
        cullSlots.clear();
        occluderSlots.clear();
        fetchedPages.clear();
        pagesToAttach.clear();
        pagesPendingAttach.clear();
        pagesToDetach.clear();
        pagesToUnfetch.clear();
        chunkManager.removeListener(listener);
    }

    private class ChunkPagerListener implements ChunkManagerListener {

        @Override
        public void onChunkUpdated(Chunk chunk) {
            // Nothing to do
        }

        @Override
        public void onChunkAvailable(Chunk chunk) {
            pagesPendingAttach.add(chunk.getLocation());
            pagesToAttach.offer(chunk);
        }

        @Override
        public void onChunkFetched(Chunk chunk) {
            fetchedPages.put(chunk.getLocation(), chunk);
        }
    }

}
