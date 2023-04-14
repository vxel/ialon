package org.delaunois.ialon;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.simsilica.mathd.Vec3i;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ChunkPagerTest {

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    @BeforeAll
    public static void setUp() {
        Ialon.configureBlocksFramework(new DesktopAssetManager(true), new TextureAtlasManager());
    }

    @AfterEach
    public void reset() {
        Ialon.configureBlocksFramework(new DesktopAssetManager(true), new TextureAtlasManager());
    }

    @Test
    void testChunkPager() throws InterruptedException {
        ChunkManager chunkManager = ChunkManager.builder()
                .generator(new FlatTerrainGenerator())
                .poolSize(1)
                .build();
        assertNotNull(chunkManager);
        chunkManager.initialize();

        ChunkPager chunkPager = new ChunkPager(new Node(), chunkManager);
        chunkPager.setMaxUpdatePerFrame(10000);
        chunkPager.initialize();

        Vector3f location = new Vector3f();
        chunkPager.setLocation(location);
        chunkPager.update();

        Vec3i grid = BlocksConfig.getInstance().getGrid();
        int numPages = grid.x * grid.y * grid.z;

        Thread.sleep(1000);

        while (chunkPager.getAttachedPages().size() < numPages) {
            chunkPager.update();
        }

        assertEquals(1215, chunkPager.getAttachedPages().size());

        chunkPager.cleanup(100);
        chunkManager.cleanup(100);
    }

}
