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

package org.delaunois.ialon;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.rvandoosselaer.blocks.Chunk;
import com.rvandoosselaer.blocks.ChunkManagerListener;
import com.simsilica.mathd.Vec3i;

import java.util.ArrayList;
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
import java.util.concurrent.TimeUnit;
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

        Set<Vec3i> pagesToMesh = new HashSet<>();
        Set<Vec3i> pagesToFetch = new HashSet<>();

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

        // request the new pages pages to load/generate and mesh
        // 82%
        chunkManager.requestChunks(
                new ArrayList<>(pagesToFetch),
                // 14%
                pagesToMesh.stream()
                        .sorted(Comparator.comparingInt(vec -> vec.getDistanceSq(centerPage)))
                        .collect(Collectors.toList()));

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

            // No limit when detaching nodes
            pageLocation = pagesToDetach.poll();
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

            // No limit when detaching nodes
            pageLocation = pagesToUnfetch.poll();
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

    public void cleanup() {
        cleanup(0);
    }

    public void cleanup(long timeout) {
        if (log.isTraceEnabled()) {
            log.trace("{} cleanup()", getClass().getSimpleName());
        }
        requestExecutor.shutdown();
        try {
            if (!requestExecutor.awaitTermination(timeout, TimeUnit.MILLISECONDS)) {
                log.warn("Executors did not terminate properly within timeout");
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
