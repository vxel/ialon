package org.delaunois.ialon;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlockIds;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.Chunk;
import org.delaunois.ialon.blocks.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the per-chunk face-connectivity bitset that drives the renderer's cave-culling
 * visibility graph ({@link Chunk#computeFaceConnectivity()} / {@link Chunk#isConnected}).
 */
class ChunkConnectivityTest {

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    private static int sx;
    private static int sy;
    private static int sz;
    private static Block rock;

    @BeforeAll
    static void setUp() {
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), new IalonConfig());
        Vec3i size = BlocksConfig.getInstance().getChunkSize();
        sx = size.x;
        sy = size.y;
        sz = size.z;
        rock = BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.ROCK);
    }

    private Chunk solidChunk() {
        Chunk chunk = Chunk.createAt(new Vec3i(0, 0, 0));
        for (int x = 0; x < sx; x++) {
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    chunk.addBlock(x, y, z, rock);
                }
            }
        }
        chunk.update();
        return chunk;
    }

    @Test
    void emptyChunkIsFullyConnected() {
        Chunk chunk = Chunk.createAt(new Vec3i(0, 0, 0));
        chunk.computeFaceConnectivity();

        assertEquals(Chunk.CONNECT_ALL, chunk.getFaceConnectivity());
        for (Direction a : Direction.values()) {
            for (Direction b : Direction.values()) {
                assertTrue(Chunk.isConnected(chunk.getFaceConnectivity(), a, b));
            }
        }
    }

    @Test
    void solidChunkConnectsNothing() {
        Chunk chunk = solidChunk();
        chunk.computeFaceConnectivity();

        assertEquals(0, chunk.getFaceConnectivity());
        assertFalse(Chunk.isConnected(chunk.getFaceConnectivity(), Direction.WEST, Direction.EAST));
        assertFalse(Chunk.isConnected(chunk.getFaceConnectivity(), Direction.UP, Direction.DOWN));
        assertFalse(Chunk.isConnected(chunk.getFaceConnectivity(), Direction.NORTH, Direction.SOUTH));
        // A face is always connected to itself.
        assertTrue(Chunk.isConnected(chunk.getFaceConnectivity(), Direction.UP, Direction.UP));
    }

    @Test
    void straightTunnelConnectsOnlyItsTwoFaces() {
        Chunk chunk = solidChunk();
        int my = sy / 2;
        int mz = sz / 2;
        // Carve an air tunnel along the X axis : links the WEST (x==0) and EAST (x==sx-1) faces.
        for (int x = 0; x < sx; x++) {
            chunk.removeBlock(x, my, mz);
        }
        chunk.computeFaceConnectivity();

        short conn = chunk.getFaceConnectivity();
        assertTrue(Chunk.isConnected(conn, Direction.WEST, Direction.EAST));
        assertTrue(Chunk.isConnected(conn, Direction.EAST, Direction.WEST)); // symmetric
        assertFalse(Chunk.isConnected(conn, Direction.UP, Direction.DOWN));
        assertFalse(Chunk.isConnected(conn, Direction.WEST, Direction.UP));
        assertFalse(Chunk.isConnected(conn, Direction.NORTH, Direction.SOUTH));
    }

    @Test
    void bentTunnelConnectsItsEndsOnly() {
        Chunk chunk = solidChunk();
        int mx = sx / 2;
        int my = sy / 2;
        int mz = sz / 2;
        // Carve an L : from the WEST face inward, then turn up to the UP face.
        for (int x = 0; x <= mx; x++) {
            chunk.removeBlock(x, my, mz);
        }
        for (int y = my; y < sy; y++) {
            chunk.removeBlock(mx, y, mz);
        }
        chunk.computeFaceConnectivity();

        short conn = chunk.getFaceConnectivity();
        assertTrue(Chunk.isConnected(conn, Direction.WEST, Direction.UP));
        assertFalse(Chunk.isConnected(conn, Direction.WEST, Direction.EAST));
        assertFalse(Chunk.isConnected(conn, Direction.UP, Direction.DOWN));
    }

    @Test
    void disjointTunnelsDoNotCrossConnect() {
        Chunk chunk = solidChunk();
        // Tunnel 1 : along X near the bottom-front corner (WEST <-> EAST).
        for (int x = 0; x < sx; x++) {
            chunk.removeBlock(x, 1, 1);
        }
        // Tunnel 2 : along Y near the opposite corner (UP <-> DOWN), not touching tunnel 1.
        for (int y = 0; y < sy; y++) {
            chunk.removeBlock(sx - 2, y, sz - 2);
        }
        chunk.computeFaceConnectivity();

        short conn = chunk.getFaceConnectivity();
        assertTrue(Chunk.isConnected(conn, Direction.WEST, Direction.EAST));
        assertTrue(Chunk.isConnected(conn, Direction.UP, Direction.DOWN));
        // The two tunnels are separate components : their faces must not be reported connected.
        assertFalse(Chunk.isConnected(conn, Direction.WEST, Direction.UP));
        assertFalse(Chunk.isConnected(conn, Direction.EAST, Direction.DOWN));
    }
}
