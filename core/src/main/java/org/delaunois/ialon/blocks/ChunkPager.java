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
import com.jme3.bounding.BoundingBox;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
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

    @Setter
    private Camera camera;

    // --- Cave culling : a per-frame chunk visibility graph (BFS through each chunk's face
    // connectivity, gated by the camera frustum) that hides chunks the camera cannot see through
    // (behind walls, underground). Minecraft-style "advanced occlusion culling". ---
    @Getter
    @Setter
    private boolean caveCullingEnabled = true;

    private static final Direction[] DIRS = Direction.values();
    // Re-run the BFS when the camera turns past ~2.5° (dot of the normalised view vectors).
    private static final float VISIBILITY_DIR_DOT = 0.999f;

    private final int chunkSizeX;
    private final int chunkSizeY;
    private final int chunkSizeZ;
    private final float blockScale;

    private boolean visibilityComputed = false;
    // Set when a page is attached/detached so the BFS refreshes even if the camera did not move
    // (e.g. an edit remeshed a chunk, or new chunks streamed in).
    private volatile boolean visibilityDirty = false;
    private final Vec3i lastVisibilityRoot = new Vec3i(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
    private final Vector3f lastVisibilityDir = new Vector3f(Float.NaN, Float.NaN, Float.NaN);
    private final Vector3f visibilityDirScratch = new Vector3f();

    // BFS scratch, sized to the mesh grid and reused across runs. Stamps are epoch-marked so the
    // arrays never need clearing. Only ever touched on the update thread (single-threaded).
    private int visGridX;
    private int visGridY;
    private int visGridZ;
    private int visOriginX;
    private int visOriginY;
    private int visOriginZ;
    private int[] bfsQueue = new int[0];
    private int[] bfsStamp = new int[0];    // == bfsEpoch  : visited (enqueued or frustum-rejected)
    private int[] reachStamp = new int[0];  // == reachEpoch : sight reaches this chunk (keep visible)
    private int[] bfsDirs = new int[0];     // dirsTravelled bitmask reaching this cell
    private byte[] bfsEnter = new byte[0];  // enter-face ordinal + 1 (0 = root)
    private int bfsEpoch = 0;
    private int reachEpoch = 0;
    private final Vec3i visLookup = new Vec3i();
    private final BoundingBox visBound = new BoundingBox();
    private final Vector3f visBoundCenter = new Vector3f();

    public ChunkPager(Node node, @NonNull ChunkManager chunkManager) {
        this.node = node;
        this.chunkManager = chunkManager;
        this.gridSize = BlocksConfig.getInstance().getGrid();
        Vec3i cs = BlocksConfig.getInstance().getChunkSize();
        this.chunkSizeX = cs.x;
        this.chunkSizeY = cs.y;
        this.chunkSizeZ = cs.z;
        this.blockScale = BlocksConfig.getInstance().getBlockScale();
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
                attachedPages.remove(pageLocation);
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
            Node oldPage = attachedPages.remove(chunk.getLocation());
            if (oldPage != null) {
                detachPage(oldPage);
            }

            // Create the new page
            Node newPage = createPage(chunk);
            attachedPages.put(chunk.getLocation(), newPage);
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
        if (!(node instanceof EmptyNode)) {
            node.detachChild(page);
        }
    }

    protected void attachPage(Node page) {
        if (log.isTraceEnabled()) {
            log.trace("Attaching {} to {}", page, node);
        }
        if (!(node instanceof EmptyNode)) {
            node.attachChild(page);
        }
    }

    /**
     * Cave culling. Recomputes (when the camera moved chunk, rotated enough, or a page changed)
     * which loaded chunks the camera can actually see through the chunk visibility graph, and hides
     * the rest with {@link Spatial.CullHint#Always}. Cheap to call every frame : it early-outs when
     * nothing relevant changed, and otherwise does a bounded BFS over the mesh grid plus a reconcile
     * that only toggles the pages whose visibility flipped.
     */
    private void updateChunkVisibility() {
        if (!caveCullingEnabled || camera == null) {
            if (visibilityComputed) {
                // Culling was just turned off (or the camera is gone) : un-hide everything.
                showAllPages();
                visibilityComputed = false;
            }
            return;
        }
        if (centerPage == null) {
            return;
        }

        camera.getDirection(visibilityDirScratch);
        boolean rootChanged = !centerPage.equals(lastVisibilityRoot);
        boolean rotated = visibilityDirScratch.dot(lastVisibilityDir) < VISIBILITY_DIR_DOT;
        if (visibilityComputed && !visibilityDirty && !rootChanged && !rotated) {
            return;
        }
        lastVisibilityRoot.set(centerPage);
        lastVisibilityDir.set(visibilityDirScratch);
        visibilityDirty = false;

        computeReachable(centerPage);
        applyVisibility();
        visibilityComputed = true;
    }

    /**
     * Test seam : runs the cave-culling visibility pass for a given camera-chunk root, against the
     * pages currently in {@link #attachedPages} (each carrying its connectivity as userData) and the
     * configured {@link #camera}. Bypasses the throttle and the paging executor.
     */
    void applyCaveCullingForTest(Vec3i root) {
        computeReachable(root);
        applyVisibility();
    }

    /**
     * BFS through the chunk visibility graph from the chunk holding the camera. A chunk is traversed
     * from the face it was entered to a neighbour face only if its {@link Chunk#getFaceConnectivity()}
     * links them (so solid rock blocks sight), the BFS never doubles back (monotonic direction set),
     * and the neighbour lies inside the camera frustum (a chunk the camera cannot see cannot be seen
     * through). Reachable chunks are stamped in {@link #reachStamp}.
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

        int rootLocal = localIndex(root.x, root.y, root.z);
        if (rootLocal < 0) {
            return; // camera chunk outside its own grid : should not happen
        }

        int head = 0;
        int tail = 0;
        bfsQueue[tail++] = rootLocal;
        bfsStamp[rootLocal] = bfsEpoch;
        bfsEnter[rootLocal] = 0; // root : no enter face, connectivity gate disabled
        bfsDirs[rootLocal] = 0;

        while (head < tail) {
            int li = bfsQueue[head++];
            reachStamp[li] = reachEpoch;

            int lz = li / (visGridX * visGridY);
            int rem = li - lz * visGridX * visGridY;
            int ly = rem / visGridX;
            int lx = rem - ly * visGridX;

            short conn = connectivityAt(visOriginX + lx, visOriginY + ly, visOriginZ + lz);
            int enter = bfsEnter[li];
            int dirs = bfsDirs[li];

            for (Direction d : DIRS) {
                int oppOrd = d.opposite().ordinal();
                // monotonicity : never travel opposite to a direction already taken
                if ((dirs & (1 << oppOrd)) != 0) {
                    continue;
                }
                // connectivity gate (disabled for the root, where enter == 0)
                if (enter != 0 && !Chunk.isConnected(conn, DIRS[enter - 1], d)) {
                    continue;
                }
                int nlx = lx + d.getVector().x;
                int nly = ly + d.getVector().y;
                int nlz = lz + d.getVector().z;
                if (nlx < 0 || nlx >= visGridX || nly < 0 || nly >= visGridY || nlz < 0 || nlz >= visGridZ) {
                    continue;
                }
                int ni = nlx + visGridX * (nly + visGridY * nlz);
                if (bfsStamp[ni] == bfsEpoch) {
                    continue; // already visited
                }
                bfsStamp[ni] = bfsEpoch;
                // frustum gate : a chunk the camera cannot see also cannot be seen through
                if (!inFrustum(visOriginX + nlx, visOriginY + nly, visOriginZ + nlz)) {
                    continue;
                }
                bfsEnter[ni] = (byte) (oppOrd + 1);
                bfsDirs[ni] = dirs | (1 << d.ordinal());
                bfsQueue[tail++] = ni;
            }
        }
    }

    /** Sets each attached page's cull hint from the BFS result, touching only the pages that changed. */
    private void applyVisibility() {
        for (Map.Entry<Vec3i, Node> entry : attachedPages.entrySet()) {
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
        for (Node page : attachedPages.values()) {
            if (page.getLocalCullHint() != Spatial.CullHint.Inherit) {
                page.setCullHint(Spatial.CullHint.Inherit);
            }
        }
    }

    /** Face-connectivity bitset of the chunk at the given grid location, read from its node userData. */
    private short connectivityAt(int wx, int wy, int wz) {
        visLookup.set(wx, wy, wz);
        Node page = attachedPages.get(visLookup);
        if (page == null) {
            return Chunk.CONNECT_ALL; // not meshed/loaded yet : do not occlude
        }
        Object ud = page.getUserData(Chunk.USERDATA_FACE_CONNECTIVITY);
        if (ud instanceof Integer) {
            return (short) ((Integer) ud).intValue();
        }
        return Chunk.CONNECT_ALL;
    }

    /** True if the chunk at the given grid location intersects the camera frustum. */
    private boolean inFrustum(int wx, int wy, int wz) {
        float ex = 0.5f * chunkSizeX * blockScale;
        float ey = 0.5f * chunkSizeY * blockScale;
        float ez = 0.5f * chunkSizeZ * blockScale;
        visBoundCenter.set(
                wx * chunkSizeX * blockScale + ex,
                wy * chunkSizeY * blockScale + ey,
                wz * chunkSizeZ * blockScale + ez);
        visBound.setCenter(visBoundCenter);
        visBound.setXExtent(ex);
        visBound.setYExtent(ey);
        visBound.setZExtent(ez);
        camera.setPlaneState(0);
        return camera.contains(visBound) != Camera.FrustumIntersect.Outside;
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
            bfsStamp = new int[volume];
            reachStamp = new int[volume];
            bfsDirs = new int[volume];
            bfsEnter = new byte[volume];
            // Freshly-zeroed stamps : reset epochs so they cannot match a stale value.
            bfsEpoch = 0;
            reachEpoch = 0;
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
