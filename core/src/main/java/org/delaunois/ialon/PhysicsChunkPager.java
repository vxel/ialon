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
    private boolean ready = false;

    @Getter
    private final Map<Vec3i, PhysicsRigidBody> attachedPages = new ConcurrentHashMap<>();

    private final ChunkManagerListener listener = new PhysicsChunkPagerListener();
    private final Queue<Vec3i> pagesToCreate = new ConcurrentLinkedQueue<>();
    private final Queue<CreatedPage> pagesToAttach = new ConcurrentLinkedQueue<>();
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

    public void update() {
        if (location == null) {
            return;
        }

        updateCenterPage();

        detachNextPages();

        createNextPages();

        attachNextPages();
    }

    protected void updateCenterPage() {
        Vec3i newCenterPage = ChunkManager.getChunkLocation(location);
        if (!Objects.equals(newCenterPage, centerPage)) {
            ready = false;
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

        Set<Vec3i> pages = new HashSet<>();
        for (int x = min.x; x <= max.x; x++) {
            for (int y = min.y; y <= max.y; y++) {
                for (int z = min.z; z <= max.z; z++) {
                    pages.add(new Vec3i(x, y, z));
                }
            }
        }

        // detach pages outside of the grid
        pagesToDetach.clear();
        for (Vec3i page : attachedPages.keySet()) {
            if (!pages.contains(page)) {
                pagesToDetach.offer(page);
            }
        }

        for (Vec3i page : pages) {
            if (!attachedPages.containsKey(page)) {
                pagesToCreate.offer(page);
            }
        }

        pages.clear();
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

    protected void createNextPages() {
        Vec3i page = pagesToCreate.poll();
        int attached = 0;

        while (page != null) {
            Chunk chunk = chunkManager.getChunk(page).orElse(null);
            if (chunk == null || chunk.getNode() == null) {
                // Chunk is not ready, try again later
                pagesToCreate.offer(page);
                return;
            }

            // Create the new page
            requestExecutor.submit(() -> createPage(chunk));

            attached += 1;

            if (attached < maxUpdatePerFrame) {
                page = pagesToCreate.poll();
            } else {
                // Timeout for this frame. Stop work now.
                page = null;
            }
        }

        if (attached > 0) {
            log.info("{} physic pages sent to create", attached);
        }
    }

    protected void attachNextPages() {
        CreatedPage page = pagesToAttach.poll();
        int attached = 0;

        while (page != null) {

            // detach the old page if any
            PhysicsRigidBody oldPage = attachedPages.remove(page.location);
            if (oldPage != null) {
                detachPage(oldPage);
            }

            // Create the new page
            PhysicsRigidBody node = page.physicsRigidBody;
            if (node != null) {
                attachPage(node);
                attachedPages.put(page.location, node);
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

    protected void createPage(Chunk chunk) {
        Vec3i location = chunk.getLocation();
        if (!ready && location.x == centerPage.x && location.y <= centerPage.y && location.z == centerPage.z) {
            log.info("Physic page {} is ready", chunk.getLocation());
            ready = true;
        }

        if (chunk.getCollisionMesh() == null || chunk.getCollisionMesh().getTriangleCount() < 1 || physicsSpace == null) {
            return;
        }

        PhysicsRigidBody physicsRigidBody = new PhysicsRigidBody(new MeshCollisionShape(chunk.getCollisionMesh()), 0);
        physicsRigidBody.setPhysicsLocation(chunk.getWorldLocation());
        pagesToAttach.offer(new CreatedPage(chunk.getLocation(), physicsRigidBody));
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
        pagesToCreate.clear();
        pagesToDetach.clear();
    }

    private class PhysicsChunkPagerListener implements ChunkManagerListener {

        @Override
        public void onChunkUpdated(Chunk chunk) {
        }

        @Override
        public void onChunkAvailable(Chunk chunk) {
            if (attachedPages.containsKey(chunk.getLocation())) {
                pagesToCreate.offer(chunk.getLocation());
            }
        }

        @Override
        public void onChunkFetched(Chunk chunk) {
        }
    }

    private static final class CreatedPage {
        Vec3i location;
        PhysicsRigidBody physicsRigidBody;

        public CreatedPage(Vec3i location, PhysicsRigidBody physicsRigidBody) {
            this.location = location;
            this.physicsRigidBody = physicsRigidBody;
        }
    }

}
