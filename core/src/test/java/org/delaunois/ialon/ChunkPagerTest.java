package org.delaunois.ialon;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.Chunk;
import org.delaunois.ialon.blocks.ChunkMeshGenerator;
import com.simsilica.mathd.Vec3i;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.delaunois.ialon.blocks.ChunkManager;
import org.delaunois.ialon.blocks.ChunkManagerListener;
import org.delaunois.ialon.blocks.ChunkPager;
import org.delaunois.ialon.blocks.FacesMeshGenerator;
import org.delaunois.ialon.blocks.generator.FlatTerrainGenerator;
import org.delaunois.ialon.blocks.generator.NoiseTerrainGenerator;
import org.delaunois.ialon.blocks.ZipFileRepository;

class ChunkPagerTest {

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    @BeforeAll
    static void setUp() {
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), new IalonConfig());
    }

    @AfterEach
    void reset() {
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

        // The pager (no grid bounds set here) must attach exactly the whole grid, with no overshoot.
        // Derived from the configured grid (gridSize x (gridHeight*2+1) x gridSize) so it stays correct
        // when those defaults change -- the previous hard-coded count went stale after gridHeight moved.
        assertEquals(numPages, chunkPager.getAttachedPages().size());

        chunkPager.cleanup(100);
        chunkManager.cleanup(100);
    }

    /**
     * A pager that cannot attach as fast as chunks are meshed must not re-request the meshes it is still
     * waiting on. It used to : {@code updateQueues} diffed the grid box against the <b>attached</b> pages
     * only, so every chunk between "meshed" and "attached" looked missing and was submitted again. The
     * duplicated meshing made the pager fall further behind, which duplicated more meshing — measured on a
     * simulated phone (30 fps, render distance 8) as 92% of all meshing wasted, single chunks re-meshed 17
     * times, and a grid that never filled in. See {@code support.ChunkPagingBenchmark}.
     *
     * <p>The player here crosses several chunk boundaries with a per-frame attach budget of 1, i.e. a pager
     * guaranteed to lag far behind, and every location must still be meshed exactly once.
     */
    @Test
    void noRedundantMeshingWhenAttachingLagsBehind() {
        ChunkManager chunkManager = ChunkManager.builder()
                .generator(new FlatTerrainGenerator())
                .poolSize(2)
                .build();
        chunkManager.initialize();

        Map<Vec3i, Integer> meshCount = new ConcurrentHashMap<>();
        chunkManager.addListener(new ChunkManagerListener() {
            @Override
            public void onChunkAvailable(Chunk chunk) {
                meshCount.merge(chunk.getLocation().clone(), 1, Integer::sum);
            }

            @Override
            public void onChunkFetched(Chunk chunk) {
                // not under test
            }

            @Override
            public void onChunkUpdated(Chunk chunk) {
                // not under test
            }
        });

        ChunkPager chunkPager = new ChunkPager(new Node(), chunkManager);
        chunkPager.initialize();

        // Load the initial grid without throttling, so the walk below starts from a settled state.
        Vec3i grid = BlocksConfig.getInstance().getGrid();
        int numPages = grid.x * grid.y * grid.z;
        chunkPager.setMaxUpdatePerFrame(numPages);
        Vector3f location = new Vector3f();
        chunkPager.setLocation(location);
        while (chunkPager.getAttachedPages().size() < numPages) {
            chunkPager.update();
        }
        meshCount.clear();

        // Now walk, one chunk per frame, with a budget that can never keep up.
        chunkPager.setMaxUpdatePerFrame(1);
        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();
        for (int crossing = 1; crossing <= 6; crossing++) {
            location.x = (float) crossing * chunkSize.x;
            chunkPager.setLocation(location);
            chunkPager.update();
            // Let the meshing threads drain, so a duplicate request would have time to show up.
            sleep(200);
            chunkPager.update();
        }

        assertFalse(meshCount.isEmpty(), "the walk should have meshed new chunks");
        Map.Entry<Vec3i, Integer> worst = meshCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow(IllegalStateException::new);
        assertEquals(1, worst.getValue().intValue(),
                "chunk " + worst.getKey() + " was meshed " + worst.getValue() + " times : the pager"
                        + " re-requested a mesh it had already asked for");

        chunkPager.cleanup(100);
        chunkManager.cleanup(100);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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
        double variance = varsum / (double)durations.length;
        double stddev = Math.sqrt(variance);
        System.out.println("Mean: " + (int)(mean / 1000000f) + "µs, Stddev: " + (int)(stddev / 1000000) + "µs");
        assertTrue(mean > 0);
    }

    private long performGeneration(IalonConfig config, ChunkMeshGenerator meshGenerator) {
        Chunk[] chunks = new Chunk[1000];
        Vec3i location = new Vec3i(0, 3, 0);
        for (int i = 0; i < 1000; i++) {
            location.set(i, 3, i);
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
