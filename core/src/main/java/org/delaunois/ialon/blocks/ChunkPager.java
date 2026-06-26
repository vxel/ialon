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

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.simsilica.mathd.Vec3i;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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
    private final Map<Vec3i, Node> scenePages = new ConcurrentHashMap<>();
    // Occluders store their connectivity bitset directly (computed once at trackPage) so the per-pass
    // cacheConnectivity avoids a String-keyed getUserData per occluder — there can be thousands when the
    // grid spans dense/underground terrain.
    private final Map<Vec3i, Short> occluders = new ConcurrentHashMap<>();

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
    private final int[] visDli = new int[6]; // neighbour linear-index deltas for the current grid stride
    private int[] bfsStamp = new int[0];    // == bfsEpoch  : visited (enqueued)
    private int[] reachStamp = new int[0];  // == reachEpoch : sight reaches this chunk (keep visible)
    private byte[] bfsEnter = new byte[0];  // enter-face ordinal + 1 (0 = root)
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
        requestExecutor = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("chunk-pager-%d").build());
    }

    public void update() {
        if (location == null) {
            return;
        }

        updateCenterPage();
        unfetchNextPages();
        detachNextPages();
        attachNextPages();
        updateChunkVisibility();
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

    private void detachNextPages() {
        Vec3i pageLocation = pagesToDetach.poll();
        int removed = 0;

        while (pageLocation != null) {
            Node page = attachedPages.get(pageLocation);
            if (page == null) {
                log.warn("Trying to detach page at location {} that isn't attached.", pageLocation);
            } else {
                detachPage(page);
                untrackPage(pageLocation);
                removed += 1;
            }

            if (removed < maxUpdatePerFrame) {
                pageLocation = pagesToDetach.poll();
            } else {
                // Spread large detach bursts (e.g. grid shrink or teleport) over several frames.
                pageLocation = null;
            }
        }

        if (removed > 0) {
            pageOps += removed;
            visibilityDirty = true;
            log.trace("{} pages removed", removed);
        }
    }

    private void unfetchNextPages() {
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

            if (unfetched < maxUpdatePerFrame) {
                pageLocation = pagesToUnfetch.poll();
            } else {
                // Spread large unfetch bursts (e.g. grid shrink or teleport) over several frames.
                pageLocation = null;
            }
        }

        if (unfetched > 0) {
            log.trace("{} pages unfetched", unfetched);
        }
    }

    protected void attachNextPages() {
        Chunk chunk = pagesToAttach.poll();
        int attached = 0;

        while (chunk != null) {

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
                attached += 1;
            }

            if (attached < maxUpdatePerFrame) {
                chunk = pagesToAttach.poll();
            } else {
                // Timeout for this frame. Stop work now.
                chunk = null;
            }
        }

        if (attached > 0) {
            pageOps += attached;
            visibilityDirty = true;
            log.trace("{} pages attached", attached);
        }
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
        } else {
            scenePages.put(location, page);
        }
        short conn = connectivityOf(page);
        if (conn != Chunk.CONNECT_ALL) {
            occluders.put(location, conn); // can block sight : the BFS must see its connectivity
        } else {
            occluders.remove(location);
        }
    }

    /** Removes a page from {@link #attachedPages} and the derived views, returning the removed node. */
    private Node untrackPage(Vec3i location) {
        Node removed = attachedPages.remove(location);
        scenePages.remove(location);
        occluders.remove(location);
        return removed;
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
    private void updateChunkVisibility() {
        if (!caveCullingEnabled) {
            if (visibilityComputed) {
                // Culling was just turned off : un-hide everything.
                showAllPages();
                visibilityComputed = false;
            }
            return;
        }
        if (centerPage == null) {
            return;
        }

        boolean rootChanged = !centerPage.equals(lastVisibilityRoot);
        long now = System.nanoTime();
        boolean dirtyDue = visibilityDirty && (now - lastVisibilityNanos) >= VISIBILITY_MIN_INTERVAL_NANOS;
        if (visibilityComputed && !rootChanged && !dirtyDue) {
            return;
        }
        lastVisibilityRoot.set(centerPage);
        lastVisibilityNanos = now;
        visibilityDirty = false;

        computeReachable(centerPage);
        applyVisibility();
        visibilityComputed = true;
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
        final int gy = gridSize.y;
        final int gz = gridSize.z;
        ensureVisScratch(gx, gy, gz);
        visGridX = gx;
        visGridY = gy;
        visGridZ = gz;
        visOriginX = root.x - (gx - 1) / 2;
        visOriginY = root.y - (gy - 1) / 2;
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
        tail++;
        bfsStamp[rootLocal] = bfsEpoch;
        bfsEnter[rootLocal] = 0; // root : no enter face, connectivity gate disabled

        while (head < tail) {
            int li = bfsQueue[head];
            int code = bfsCoord[head];
            head++;
            reachStamp[li] = reachEpoch;

            // lx/ly/lz come packed in the queue : avoids three integer divisions per popped node.
            int lx = code & 0x3FF;
            int ly = (code >> 10) & 0x3FF;
            int lz = (code >> 20) & 0x3FF;

            short conn = connStamp[li] == connEpoch ? connGrid[li] : Chunk.CONNECT_ALL;
            int enter = bfsEnter[li];
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
                if (bfsStamp[ni] == bfsEpoch) {
                    continue; // already visited
                }
                bfsStamp[ni] = bfsEpoch;
                bfsEnter[ni] = (byte) (DIR_OPP[di] + 1);
                bfsQueue[tail] = ni;
                bfsCoord[tail] = pack(nlx, nly, nlz);
                tail++;
            }
        }
    }

    /** Packs a local grid coordinate (each axis &lt; 1024) into one int for the BFS queue. */
    private static int pack(int lx, int ly, int lz) {
        return lx | (ly << 10) | (lz << 20);
    }

    /** Sets each scene page's cull hint from the BFS result, touching only the pages that changed. Only
     * scene pages (non-empty, actually in the graph) have a mesh to cull — air/empty pages are skipped. */
    private void applyVisibility() {
        for (Map.Entry<Vec3i, Node> entry : scenePages.entrySet()) {
            Vec3i loc = entry.getKey();
            Node page = entry.getValue();
            int li = localIndex(loc.x, loc.y, loc.z);
            // Pages outside the current BFS grid (transient, about to be detached) are left visible.
            boolean cull = li >= 0 && reachStamp[li] != reachEpoch;
            Spatial.CullHint desired = cull ? Spatial.CullHint.Always : Spatial.CullHint.Inherit;
            if (page.getLocalCullHint() != desired) {
                page.setCullHint(desired);
            }
        }
    }

    private void showAllPages() {
        for (Node page : scenePages.values()) {
            if (page.getLocalCullHint() != Spatial.CullHint.Inherit) {
                page.setCullHint(Spatial.CullHint.Inherit);
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
        for (Map.Entry<Vec3i, Short> entry : occluders.entrySet()) {
            Vec3i loc = entry.getKey();
            int li = localIndex(loc.x, loc.y, loc.z);
            if (li < 0) {
                continue; // outside the current BFS grid (transient, about to be detached)
            }
            connGrid[li] = entry.getValue();
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
        if (bfsQueue.length < volume) {
            bfsQueue = new int[volume];
            bfsCoord = new int[volume];
            bfsStamp = new int[volume];
            reachStamp = new int[volume];
            bfsEnter = new byte[volume];
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
        occluders.clear();
        fetchedPages.clear();
        pagesToAttach.clear();
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
            pagesToAttach.offer(chunk);
        }

        @Override
        public void onChunkFetched(Chunk chunk) {
            fetchedPages.put(chunk.getLocation(), chunk);
        }
    }

}
