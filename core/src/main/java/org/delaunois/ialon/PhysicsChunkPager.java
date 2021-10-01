package org.delaunois.ialon;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.shapes.MeshCollisionShape;
import com.jme3.bullet.objects.PhysicsRigidBody;
import com.jme3.math.Vector3f;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.rvandoosselaer.blocks.Chunk;
import com.rvandoosselaer.blocks.ChunkManagerListener;
import com.simsilica.mathd.Vec3i;

import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PhysicsChunkPager {

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
    @Setter
    private PhysicsSpace physicsSpace;

    @Getter
    private final Map<Vec3i, PhysicsRigidBody> attachedPages = new ConcurrentHashMap<>();

    private final ChunkManagerListener listener = new PhysicsChunkPagerListener();
    private final Queue<Vec3i> pagesToAttach = new ConcurrentLinkedQueue<>();
    private final Queue<Vec3i> pagesToDetach = new LinkedList<>();
    private ExecutorService requestExecutor;

    public PhysicsChunkPager(PhysicsSpace physicsSpace, ChunkManager chunkManager) {
        this.physicsSpace = physicsSpace;
        this.gridSize = BlocksConfig.getInstance().getPhysicsGrid();
        this.chunkManager = chunkManager;
    }

    public void initialize() {
        if (log.isTraceEnabled()) {
            log.trace("{} initialize()", getClass().getSimpleName());
        }
        chunkManager.addListener(listener);
        requestExecutor = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("physicschunk-pager-%d").build());
    }

    public boolean isIdle() {
        return pagesToAttach.size() == 0 && pagesToDetach.size() == 0;
    }

    public void update() {
        if (location == null) {
            return;
        }

        updateCenterPage();

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
        Vec3i min = new Vec3i(
                Math.max(centerPage.x - ((gridSize.x - 1) / 2), gridLowerBounds.x),
                Math.max(centerPage.y - ((gridSize.y - 1) / 2), gridLowerBounds.y),
                Math.max(centerPage.z - ((gridSize.z - 1) / 2), gridLowerBounds.z)
        );
        Vec3i max = new Vec3i(
                Math.min(centerPage.x + ((gridSize.x - 1) / 2), gridUpperBounds.x),
                Math.min(centerPage.y + ((gridSize.y - 1) / 2), gridUpperBounds.y),
                Math.min(centerPage.z + ((gridSize.z - 1) / 2), gridUpperBounds.z)
        );

        if (log.isDebugEnabled()) {
            log.debug("PhysicsGrid is set to ({}:{}, {}:{}, {}:{})", min.x, max.x, min.y, max.y, min.z, max.z);
        }

        pagesToAttach.clear();
        for (int x = min.x; x <= max.x; x++) {
            for (int y = min.y; y <= max.y; y++) {
                for (int z = min.z; z <= max.z; z++) {
                    pagesToAttach.offer(new Vec3i(x, y, z));
                }
            }
        }

        // detach pages outside of the grid
        pagesToDetach.clear();
        for (Vec3i page : attachedPages.keySet()) {
            if (!pagesToAttach.contains(page)) {
                pagesToDetach.offer(page);
            }
        }
    }

    private void detachNextPages() {
        Vec3i pageLocation = pagesToDetach.poll();
        int removed = 0;

        while (pageLocation != null) {
            PhysicsRigidBody page = attachedPages.get(pageLocation);
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

    protected void attachNextPages() {
        Vec3i page = pagesToAttach.poll();
        int attached = 0;

        while (page != null) {
            Chunk chunk = chunkManager.getChunk(page).orElse(null);
            if (chunk == null || chunk.getNode() == null) {
                // Chunk is not ready, try again later
                pagesToAttach.offer(page);
                return;
            }

            // detach the old page if any
            PhysicsRigidBody oldPage = attachedPages.remove(chunk.getLocation());
            if (oldPage != null) {
                detachPage(oldPage);
            }

            // Create the new page
            PhysicsRigidBody node = createPage(chunk);
            if (node != null) {
                attachPage(node);
                attachedPages.put(chunk.getLocation(), node);
                attached += 1;
            }

            if (attached < maxUpdatePerFrame) {
                page = pagesToAttach.poll();
            } else {
                // Timeout for this frame. Stop work now.
                page = null;
            }
        }

        if (attached > 0) {
            log.info("{} physic pages attached", attached);
        }
    }

    protected PhysicsRigidBody createPage(Chunk chunk) {
        if (chunk == null || chunk.getCollisionMesh() == null || chunk.getCollisionMesh().getTriangleCount() < 1 || physicsSpace == null) {
            return null;
        }

        PhysicsRigidBody physicsRigidBody = new PhysicsRigidBody(new MeshCollisionShape(chunk.getCollisionMesh()), 0);
        physicsRigidBody.setPhysicsLocation(chunk.getWorldLocation());

        return physicsRigidBody;
    }

    protected void detachPage(PhysicsRigidBody page) {
        if (physicsSpace == null) {
            return;
        }

        if (log.isTraceEnabled()) {
            log.trace("Detaching {} from {}", page, physicsSpace);
        }
        physicsSpace.removeCollisionObject(page);
    }

    protected void attachPage(PhysicsRigidBody page) {
        if (physicsSpace == null) {
            return;
        }

        if (log.isTraceEnabled()) {
            log.trace("Attaching {} to {}", page, physicsSpace);
        }
        physicsSpace.addCollisionObject(page);
    }

    public void cleanup() {
        if (log.isTraceEnabled()) {
            log.trace("{} cleanup()", getClass().getSimpleName());
        }
        requestExecutor.shutdown();
        attachedPages.forEach((loc, page) -> detachPage(page));
        attachedPages.clear();
        pagesToAttach.clear();
        pagesToDetach.clear();
    }

    private class PhysicsChunkPagerListener implements ChunkManagerListener {

        @Override
        public void onChunkUpdated(Chunk chunk) {
        }

        @Override
        public void onChunkAvailable(Chunk chunk) {
            if (attachedPages.containsKey(chunk.getLocation())) {
                pagesToAttach.offer(chunk.getLocation());
            }
        }

        @Override
        public void onChunkFetched(Chunk chunk) {
        }
    }

}
