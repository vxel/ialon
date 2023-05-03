package org.delaunois.ialon;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.rvandoosselaer.blocks.Chunk;
import com.rvandoosselaer.blocks.ChunkMeshGenerator;
import com.simsilica.mathd.Vec3i;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

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

    @Test
    @Disabled("Performance test")
    void testChunkMeshGeneratorPerf() {
        IalonConfig config = new IalonConfig();
        config.setTerrainGenerator(new NoiseTerrainGenerator(2, 50f));
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), config);

        ChunkMeshGenerator meshGenerator = new FacesMeshGenerator(config);

        // warmup
        performGeneration(config, meshGenerator);

        // test
        long[] durations = new long[20];
        long total = 0;
        for (int i = 0; i < durations.length; i++) {
            durations[i] = performGeneration(config, meshGenerator);
            System.out.println("#" + i + " mean mesh generation per block : " + durations[i] / 1000000 + " µs");
            total += durations[i];
        }

        double mean = total / (double)durations.length;
        double varsum = 0;
        for (long duration : durations) {
            varsum = varsum + (duration - mean) * (duration - mean);
        }
        double var = varsum / (double)durations.length;
        double stddev = Math.sqrt(var);
        System.out.println("Mean: " + (int)(mean / 1000000f) + "µs, Stddev: " + (int)(stddev / 1000000) + "µs");

    }

    private long performGeneration(IalonConfig config, ChunkMeshGenerator meshGenerator) {
        Chunk[] chunks = new Chunk[1000];
        Vec3i location = new Vec3i(0, 0, 0);
        for (int i = 0; i < 1000; i++) {
            location.set(i, 0, i);
            chunks[i] = config.getTerrainGenerator().generate(location);
        }

        long start = System.nanoTime();
        for (Chunk chunk : chunks) {
            meshGenerator.createNode(chunk);
        }
        return System.nanoTime() - start;
    }

    private void generateAndSave(Vec3i chunkLocation, String path, ChunkManager chunkManager) {
        Chunk chunk = chunkManager.generateChunk(chunkLocation);
        assertNotNull(chunk);
        ZipFileRepository repository = new ZipFileRepository();
        repository.setPath(Paths.get(path));
        repository.save(chunk);
    }
}
