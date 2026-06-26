package org.delaunois.ialon.blocks;

import com.jme3.asset.DesktopAssetManager;
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
 * Integration test for the connectivity-only cave-culling BFS in {@link ChunkPager} : a wall of fully
 * opaque chunks must hide the chunks sealed behind it, while the wall itself and every chunk reachable
 * through open faces stay visible. View-direction (frustum) culling is jME's job, so chunks "behind the
 * camera" are NOT culled here — only genuine occlusion is.
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

        pager = new ChunkPager(new Node("test-root"), ChunkManager.builder().poolSize(1).build());

        // Populate the grid : every chunk see-through except a solid wall plane at x == 2.
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                short conn = (x == 2) ? 0 : Chunk.CONNECT_ALL;
                Node page = new Node("page " + x + "," + z);
                page.setUserData(Chunk.USERDATA_FACE_CONNECTIVITY, conn & 0xFFFF);
                pager.trackPage(new Vec3i(x, 0, z), page);
            }
        }
        pager.setCaveCullingEnabled(true);
    }

    private Spatial.CullHint cullHintAt(int x, int z) {
        return pager.getAttachedPages().get(new Vec3i(x, 0, z)).getLocalCullHint();
    }

    @Test
    void wallHidesChunksSealedBehindIt() {
        pager.applyCaveCullingForTest(new Vec3i(0, 0, 0));

        // Player chunk, the open ground around it, and the wall's near face stay drawn.
        assertEquals(Spatial.CullHint.Inherit, cullHintAt(0, 0), "player chunk must be visible");
        assertEquals(Spatial.CullHint.Inherit, cullHintAt(1, 0), "open chunk in front of the wall must be visible");
        assertEquals(Spatial.CullHint.Inherit, cullHintAt(2, 0), "the wall itself is visible");

        // The opaque wall (x==2) seals off x==3 : sight cannot reach it -> culled.
        assertEquals(Spatial.CullHint.Always, cullHintAt(3, 0), "chunk sealed behind the wall must be culled");

        // A chunk on the open side, away from the player ("behind" the look direction) is still reachable
        // through air : occlusion culling leaves it visible (frustum culling is jME's job, not this BFS).
        assertEquals(Spatial.CullHint.Inherit, cullHintAt(-2, 0), "open chunk reachable through air stays visible");
    }
}
