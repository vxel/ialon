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

    /** Replaces the page at the given location by one with the given face-connectivity. */
    private void setConnectivity(int x, int z, short conn) {
        Node page = new Node("page " + x + "," + z);
        page.setUserData(Chunk.USERDATA_FACE_CONNECTIVITY, conn & 0xFFFF);
        pager.trackPage(new Vec3i(x, 0, z), page);
    }

    /** The single connectivity bit linking the two given faces, derived from {@link Chunk#isConnected}. */
    private static short pairMask(Direction a, Direction b) {
        for (int bit = 0; bit < 15; bit++) {
            short candidate = (short) (1 << bit);
            if (Chunk.isConnected(candidate, a, b)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no connectivity bit for " + a + "/" + b);
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

    /**
     * A chunk with several disconnected pockets (a tunnel and a separate cave) lets sight through from
     * one face and not from another : reaching it once must not decide for every other face. The BFS
     * therefore expands an occluder once per face it is entered through.
     */
    @Test
    void chunkEnteredFromTwoFacesPropagatesThroughBoth() {
        // (1,0,0) is entered twice : from the player chunk through its WEST face (a dead-end pocket,
        // WEST connects only to SOUTH), and through its NORTH face from (1,0,-1) — that pocket runs
        // NORTH to EAST and is the only way to (2,0,0).
        setConnectivity(1, 0, (short) (pairMask(Direction.WEST, Direction.SOUTH)
                | pairMask(Direction.NORTH, Direction.EAST)));
        // Clear the way to (1,0,-1) : the player chunk's north neighbour and then east.
        setConnectivity(0, -1, Chunk.CONNECT_ALL);
        setConnectivity(1, -1, Chunk.CONNECT_ALL);

        pager.applyCaveCullingForTest(new Vec3i(0, 0, 0));

        assertEquals(Spatial.CullHint.Inherit, cullHintAt(1, 0), "the multi-pocket chunk is visible");
        assertEquals(Spatial.CullHint.Inherit, cullHintAt(1, -1), "the detour chunk is visible");
        assertEquals(Spatial.CullHint.Inherit, cullHintAt(2, 0),
                "sight reaches (2,0,0) through the pocket entered from the north");
    }

    /**
     * The BFS grid's vertical extent is clamped to the world's chunk layers, because {@code gridSize.y} is
     * {@code gridHeight * 2 + 1} and more than half of that can never hold a chunk (see
     * {@code ChunkPager#visMinY}). The clamp keeps one empty layer above the top layer <b>on purpose</b> :
     * sight has to be able to leave the world, travel horizontally over it and come back down into a chunk
     * that is sealed on every side except its top. Take that margin away and such a chunk is wrongly culled.
     *
     * <p>Layout : a two-layer world (y bounds 0..1) spanning the whole BFS grid, with the player at (0,0,0)
     * and a fully opaque wall filling the <b>entire x == 1 plane</b> — every z of the grid and both layers.
     * Sight can neither pass through it (it is an occluder) nor go round it (the plane spans the grid in z,
     * and beyond the grid the flood-fill is clipped), so no path east stays inside the world. The only way
     * to (2,1,0) is up out of the world into y == 2, east above the wall, and back down.
     *
     * <p>Both halves of that had to be got right for this to test anything : an earlier version used a
     * single wall <em>column</em> at z == 0, and sight simply went round it through the untracked (hence
     * traversable) z cells, so the test passed with the margin removed.
     */
    @Test
    void sightRoutesOverTheTopOfTheWorldPastAFullHeightWall() {
        BlocksConfig.getInstance().setGrid(new Vec3i(7, 5, 7));
        pager = new ChunkPager(new Node("test-root"), ChunkManager.builder().poolSize(1).build());
        pager.setGridLowerBounds(new Vec3i(Integer.MIN_VALUE, 0, Integer.MIN_VALUE));
        pager.setGridUpperBounds(new Vec3i(Integer.MAX_VALUE, 1, Integer.MAX_VALUE));
        pager.setCaveCullingEnabled(true);

        for (int x = -3; x <= 3; x++) {
            for (int y = 0; y <= 1; y++) {
                for (int z = -3; z <= 3; z++) {
                    short conn = (x == 1) ? 0 : Chunk.CONNECT_ALL;
                    Node page = new Node("page " + x + "," + y + "," + z);
                    page.setUserData(Chunk.USERDATA_FACE_CONNECTIVITY, conn & 0xFFFF);
                    pager.trackPage(new Vec3i(x, y, z), page);
                }
            }
        }

        pager.applyCaveCullingForTest(new Vec3i(0, 0, 0));

        assertEquals(Spatial.CullHint.Inherit,
                pager.getAttachedPages().get(new Vec3i(1, 0, 0)).getLocalCullHint(),
                "the wall itself is visible");
        assertEquals(Spatial.CullHint.Inherit,
                pager.getAttachedPages().get(new Vec3i(2, 1, 0)).getLocalCullHint(),
                "sight must leave the world above its top layer to get past a full-height wall");
    }
}
