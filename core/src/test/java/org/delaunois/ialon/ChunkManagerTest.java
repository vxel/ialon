package org.delaunois.ialon;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.Vector3f;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.simsilica.mathd.Vec3i;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ChunkManagerTest {

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    @BeforeAll
    public static void setUp() {
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), IalonConfig.getInstance());
    }

    @AfterEach
    public void reset() {
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), IalonConfig.getInstance());
    }

    @Test
    void testLocationCalculation() {
        BlocksConfig.getInstance().setChunkSize(new Vec3i(16, 16, 16));

        Vector3f location = new Vector3f(0, 0, 0);
        Vec3i chunkLocation = ChunkManager.getChunkLocation(location);

        assertEquals(new Vec3i(0, 0, 0), chunkLocation);

        location = new Vector3f(13, 10, 5);
        chunkLocation = ChunkManager.getChunkLocation(location);

        assertEquals(new Vec3i(0, 0, 0), chunkLocation);

        location = new Vector3f(-5, 3, -9);
        chunkLocation = ChunkManager.getChunkLocation(location);

        assertEquals(new Vec3i(-1, 0, -1), chunkLocation);

        location = new Vector3f(16, 15, 2);
        chunkLocation = ChunkManager.getChunkLocation(location);

        assertEquals(new Vec3i(1, 0, 0), chunkLocation);

        location = new Vector3f(16, 32, 2);
        chunkLocation = ChunkManager.getChunkLocation(location);

        assertEquals(new Vec3i(1, 2, 0), chunkLocation);
    }

    @Test
    void testAddBlock() {
        ChunkManager chunkManager = ChunkManager.builder().poolSize(1).build();
        assertNotNull(chunkManager);
        chunkManager.initialize();

        Vector3f location = new Vector3f(16, 32, 2);
        chunkManager.addBlock(location, BlocksConfig.getInstance().getBlockRegistry().get((short)1));
    }
}
