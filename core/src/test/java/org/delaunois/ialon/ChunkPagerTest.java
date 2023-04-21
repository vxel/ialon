package org.delaunois.ialon;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.rvandoosselaer.blocks.Chunk;
import com.simsilica.mathd.Vec3i;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ChunkPagerTest {

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    @BeforeAll
    public static void setUp() {
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), new IalonConfig());
    }

    @AfterEach
    public void reset() {
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), new IalonConfig());
    }

    @Test
    void testChunkPager() {
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

        while (chunkPager.getAttachedPages().size() < numPages) {
            chunkPager.update();
        }

        assertEquals(1215, chunkPager.getAttachedPages().size());

        chunkPager.cleanup(100);
        chunkManager.cleanup(100);
    }

    @Test
    void testChunkPager2() {
        ChunkManager chunkManager = ChunkManager.builder()
                .poolSize(1)
                .build();
        assertNotNull(chunkManager);
        chunkManager.initialize();

        generateAndSave(new Vec3i(), ".", chunkManager);
    }

    private Chunk load(String path) throws URISyntaxException {
        URL resource = this.getClass().getResource("/chunk_0_0_0.zblock");
        assertNotNull(resource);
        ZipFileRepository repository = new ZipFileRepository();
        return repository.load(Paths.get(resource.toURI()));
    }

    private void generateAndSave(Vec3i chunkLocation, String path, ChunkManager chunkManager) {
        Chunk chunk = chunkManager.generateChunk(new Vec3i());
        assertNotNull(chunk);
        ZipFileRepository repository = new ZipFileRepository();
        repository.setPath(Paths.get(path));
        repository.save(chunk);
    }
}
