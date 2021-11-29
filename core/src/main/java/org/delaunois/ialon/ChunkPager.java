package org.delaunois.ialon;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.rvandoosselaer.blocks.Chunk;
import com.rvandoosselaer.blocks.ChunkManagerListener;
import com.simsilica.mathd.Vec3i;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

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

    private final Queue<Chunk> pagesToAttach = new ConcurrentLinkedQueue<>();
    private final Queue<Vec3i> pagesToDetach = new LinkedList<>();
    private final Queue<Vec3i> pagesToUnfetch = new ConcurrentLinkedQueue<>();
    private final ChunkManagerListener listener = new ChunkPagerListener();
    private final Node node;
    private ExecutorService requestExecutor;

    public ChunkPager(Node node, ChunkManager chunkManager) {
        this.node = node;
        this.gridSize = BlocksConfig.getInstance().getGrid();
        this.chunkManager = chunkManager;
    }

    public void initialize() {
        if (log.isTraceEnabled()) {
            log.trace("{} initialize()", getClass().getSimpleName());
        }

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
        Vec3i meshMin = new Vec3i(
                Math.max(centerPage.x - ((gridSize.x - 1) / 2), gridLowerBounds.x),
                Math.max(centerPage.y - ((gridSize.y - 1) / 2), gridLowerBounds.y),
                Math.max(centerPage.z - ((gridSize.z - 1) / 2), gridLowerBounds.z)
        );
        Vec3i meshMax = new Vec3i(
                Math.min(centerPage.x + ((gridSize.x - 1) / 2), gridUpperBounds.x),
                Math.min(centerPage.y + ((gridSize.y - 1) / 2), gridUpperBounds.y),
                Math.min(centerPage.z + ((gridSize.z - 1) / 2), gridUpperBounds.z)
        );
        Vec3i fetchMin = new Vec3i(
                Math.max(meshMin.x - 1, gridLowerBounds.x),
                Math.max(meshMin.y - 1, gridLowerBounds.y),
                Math.max(meshMin.z - 1, gridLowerBounds.z)
        );
        Vec3i fetchMax = new Vec3i(
                Math.min(meshMax.x + 1, gridUpperBounds.x),
                Math.min(meshMax.y + 1, gridUpperBounds.y),
                Math.min(meshMax.z + 1, gridUpperBounds.z)
        );

        if (log.isDebugEnabled()) {
            log.debug("Grid is set to ({}:{}, {}:{}, {}:{})", meshMin.x, meshMax.x, meshMin.y, meshMax.y, meshMin.z, meshMax.z);
        }

        Set<Vec3i> pagesToMesh = new HashSet<>();
        Set<Vec3i> pagesToFetch = new HashSet<>();
        for (int x = fetchMin.x; x <= fetchMax.x; x++) {
            for (int y = fetchMin.y; y <= fetchMax.y; y++) {
                for (int z = fetchMin.z; z <= fetchMax.z; z++) {
                    Vec3i location = new Vec3i(x, y, z);
                    pagesToFetch.add(location);
                    if (x >= meshMin.x && x <= meshMax.x && y >= meshMin.y && y <= meshMax.y && z >= meshMin.z && z <= meshMax.z) {
                        pagesToMesh.add(location);
                    }
                }
            }
        }

        // detach pages outside of the grid
        pagesToDetach.clear();
        for (Vec3i page : attachedPages.keySet()) {
            if (!pagesToMesh.contains(page)) {
                pagesToDetach.offer(page);
            }
        }

        // detach fetched pages outside of the grid
        pagesToUnfetch.clear();
        for (Vec3i page : fetchedPages.keySet()) {
            if (!pagesToFetch.contains(page)) {
                pagesToUnfetch.add(page);
            }
        }

        // request the new pages pages to load/generate and mesh
        chunkManager.requestChunks(
                pagesToFetch.stream()
                        .filter((location) -> !fetchedPages.containsKey(location))
                        .collect(Collectors.toList()),
                pagesToMesh.stream()
                        .filter((location) -> !attachedPages.containsKey(location))
                        .sorted(Comparator.comparingInt(vec -> vec.getDistanceSq(centerPage)))
                        .collect(Collectors.toList()));

        pagesToMesh.clear();
        pagesToFetch.clear();
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
                // Timeout for this frame. Stop work now.
                pageLocation = null;
            }
        }

        if (removed > 0) {
            log.info("{} pages removed", removed);
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
                chunkManager.fastRemoveChunk(pageLocation);
                unfetched += 1;
            }

            if (unfetched < maxUpdatePerFrame) {
                pageLocation = pagesToUnfetch.poll();
            } else {
                // Timeout for this frame. Stop work now.
                pageLocation = null;
            }
        }

        if (unfetched > 0) {
            log.info("{} pages unfetched", unfetched);
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
            Node node = createPage(chunk);
            if (node != null) {
                attachPage(node);
                attachedPages.put(chunk.getLocation(), node);
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
            log.info("{} pages attached", attached);
        }
    }

    protected Node createPage(Chunk chunk) {
        return chunk.getNode();
    }

    protected void detachPage(Node page) {
        if (log.isTraceEnabled()) {
            log.trace("Detaching {} from {}", page, node);
        }
        node.detachChild(page);
    }

    protected void attachPage(Node page) {
        if (log.isTraceEnabled()) {
            log.trace("Attaching {} to {}", page, node);
        }
        node.attachChild(page);
    }

    public void cleanup() {
        if (log.isTraceEnabled()) {
            log.trace("{} cleanup()", getClass().getSimpleName());
        }
        requestExecutor.shutdown();
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
