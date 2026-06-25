package org.delaunois.ialon.blocks;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.IalonInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration test for the cave-culling visibility BFS in {@link ChunkPager} : a wall of fully
 * opaque chunks must hide the chunks behind it, while the wall and the camera chunk stay visible
 * and chunks behind the camera are frustum-culled.
 */
class ChunkPagerCaveCullingTest {

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    private ChunkPager pager;

    @BeforeEach
    void setUp() {
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), new IalonConfig());
        BlocksConfig config = BlocksConfig.getInstance();
        // A flat, odd-sized grid keeps the BFS 2D and deterministic.
        config.setGrid(new Vec3i(7, 1, 7));

        Vec3i chunkSize = config.getChunkSize();
        float scale = config.getBlockScale();

        pager = new ChunkPager(new Node("test-root"), ChunkManager.builder().poolSize(1).build());

        // Populate the grid : every chunk see-through except a solid wall plane at x == 2 (so x == 1
        // is open ground directly in front of the camera).
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                short conn = (x == 2) ? 0 : Chunk.CONNECT_ALL;
                Node page = new Node("page " + x + "," + z);
                page.setUserData(Chunk.USERDATA_FACE_CONNECTIVITY, conn & 0xFFFF);
                pager.getAttachedPages().put(new Vec3i(x, 0, z), page);
            }
        }

        // Camera at the centre of chunk (0,0,0), looking towards +X (straight at the wall).
        Camera camera = new Camera(800, 600);
        camera.setFrustumPerspective(90f, 800f / 600f, 0.1f, 100_000f);
        camera.setLocation(new Vector3f(
                0.5f * chunkSize.x * scale,
                0.5f * chunkSize.y * scale,
                0.5f * chunkSize.z * scale));
        camera.lookAtDirection(new Vector3f(1, 0, 0), Vector3f.UNIT_Y);

        pager.setCamera(camera);
        pager.setCaveCullingEnabled(true);
    }

    private Spatial.CullHint cullHintAt(int x, int z) {
        return pager.getAttachedPages().get(new Vec3i(x, 0, z)).getLocalCullHint();
    }

    @Test
    void wallHidesChunksBehindIt() {
        pager.applyCaveCullingForTest(new Vec3i(0, 0, 0));

        // Camera chunk, the open ground in front, and the wall itself stay drawn.
        assertEquals(Spatial.CullHint.Inherit, cullHintAt(0, 0), "camera chunk must be visible");
        assertEquals(Spatial.CullHint.Inherit, cullHintAt(1, 0), "open chunk in front of the wall must be visible");
        assertEquals(Spatial.CullHint.Inherit, cullHintAt(2, 0), "the wall itself is visible");

        // Chunks behind the opaque wall cannot be seen through : hidden.
        assertEquals(Spatial.CullHint.Always, cullHintAt(3, 0), "chunk behind the wall must be culled");

        // Chunks behind the camera are outside the frustum : hidden.
        assertEquals(Spatial.CullHint.Always, cullHintAt(-2, 0), "chunk behind the camera must be culled");
    }
}
