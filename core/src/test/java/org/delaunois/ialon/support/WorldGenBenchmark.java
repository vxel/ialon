package org.delaunois.ialon.support;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.Chunk;
import org.delaunois.ialon.blocks.ChunkResolver;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.FacesMeshGenerator;
import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.IalonInitializer;
import org.delaunois.ialon.NoiseTerrainGenerator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manual (interactive) micro-benchmark for the FULL world generation performed at application
 * startup — NOT part of the automated suite. Run its {@link #main(String[])} from the IDE.
 *
 * <p>Reproduces, single-threaded, what the chunk pager does on startup for the default grid
 * (gridRadius / gridHeight) : generate every chunk (blocks + sunlight) with the default
 * {@link NoiseTerrainGenerator}, then mesh the chunks the pager would actually mesh (skipping empty
 * chunks and full chunks surrounded by full chunks, exactly like ChunkManager#requestMeshChunks).
 *
 * <p>It reports the generation vs meshing split, the per-chunk costs, and — crucially for spotting
 * easy wins — the generation cost broken down by chunk category (empty / full / surface), since
 * empty and full chunks essentially only pay the noise heightmap cost.
 */
public class WorldGenBenchmark {

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    private static final int WARMUP_PASSES = 2;
    private static final int MEASURE_PASSES = 3;

    public static void main(String[] args) {
        IalonConfig config = new IalonConfig();
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), config);

        Vec3i cs = BlocksConfig.getInstance().getChunkSize();
        int gridRadius = config.getGridRadius();
        int gridHeight = config.getGridHeight();
        List<Vec3i> locations = new ArrayList<>();
        for (int x = -gridRadius; x <= gridRadius; x++) {
            for (int y = 0; y < gridHeight; y++) {
                for (int z = -gridRadius; z <= gridRadius; z++) {
                    locations.add(new Vec3i(x, y, z));
                }
            }
        }

        System.out.printf("Chunk size %s, water height %.0f%n", cs, config.getWaterHeight());
        System.out.printf("Startup grid : %d x %d x %d = %d chunks (gridRadius=%d, gridHeight=%d)%n",
                gridRadius * 2 + 1, gridHeight, gridRadius * 2 + 1, locations.size(), gridRadius, gridHeight);

        for (int i = 0; i < WARMUP_PASSES; i++) {
            runPass(config, locations, null);
        }

        Stats agg = new Stats();
        for (int i = 0; i < MEASURE_PASSES; i++) {
            runPass(config, locations, agg);
        }
        agg.print(MEASURE_PASSES, locations.size());
    }

    private static void runPass(IalonConfig config, List<Vec3i> locations, Stats stats) {
        NoiseTerrainGenerator generator = new NoiseTerrainGenerator(2, config.getWaterHeight());
        FacesMeshGenerator mesher = new FacesMeshGenerator(config);
        Map<Vec3i, Chunk> world = new HashMap<>();
        MapResolver resolver = new MapResolver(world);

        // ---- Phase 1 : generation (blocks + sunlight), per-category timing ----
        long genEmptyNs = 0;
        long genFullNs = 0;
        long genSurfaceNs = 0;
        int nEmpty = 0;
        int nFull = 0;
        int nSurface = 0;
        for (Vec3i loc : locations) {
            long t0 = System.nanoTime();
            Chunk chunk = generator.generate(loc);
            chunk.update();
            long dt = System.nanoTime() - t0;

            if (chunk.isEmpty()) {
                genEmptyNs += dt;
                nEmpty++;
            } else if (chunk.isFull()) {
                genFullNs += dt;
                nFull++;
            } else {
                genSurfaceNs += dt;
                nSurface++;
            }
            chunk.setChunkResolver(resolver);
            world.put(loc, chunk);
        }

        // ---- Phase 2 : meshing (only the chunks the pager would actually mesh) ----
        long meshNs = 0;
        int nMeshed = 0;
        int nSkippedFull = 0;
        for (Vec3i loc : locations) {
            Chunk chunk = world.get(loc);
            if (chunk.isEmpty()) {
                continue;
            }
            if (chunk.isFull() && isSurroundedByFullChunks(world, loc)) {
                nSkippedFull++;
                continue;
            }
            long t0 = System.nanoTime();
            mesher.createAndSetNodeAndCollisionMesh(chunk);
            meshNs += System.nanoTime() - t0;
            nMeshed++;
        }

        if (stats != null) {
            stats.genEmptyNs += genEmptyNs;
            stats.genFullNs += genFullNs;
            stats.genSurfaceNs += genSurfaceNs;
            stats.meshNs += meshNs;
            stats.nEmpty = nEmpty;
            stats.nFull = nFull;
            stats.nSurface = nSurface;
            stats.nMeshed = nMeshed;
            stats.nSkippedFull = nSkippedFull;
        }
    }

    private static boolean isSurroundedByFullChunks(Map<Vec3i, Chunk> world, Vec3i loc) {
        return isFullOrAbsent(world, loc.add(0, 1, 0))
                && isFullOrAbsent(world, loc.add(0, -1, 0))
                && isFullOrAbsent(world, loc.add(1, 0, 0))
                && isFullOrAbsent(world, loc.add(-1, 0, 0))
                && isFullOrAbsent(world, loc.add(0, 0, 1))
                && isFullOrAbsent(world, loc.add(0, 0, -1));
    }

    private static boolean isFullOrAbsent(Map<Vec3i, Chunk> world, Vec3i loc) {
        Chunk c = world.get(loc);
        return c == null || c.isFull();
    }

    private static final class Stats {
        long genEmptyNs;
        long genFullNs;
        long genSurfaceNs;
        long meshNs;
        int nEmpty;
        int nFull;
        int nSurface;
        int nMeshed;
        int nSkippedFull;

        void print(int passes, int totalChunks) {
            double genEmpty = genEmptyNs / 1e6 / passes;
            double genFull = genFullNs / 1e6 / passes;
            double genSurface = genSurfaceNs / 1e6 / passes;
            double gen = genEmpty + genFull + genSurface;
            double mesh = meshNs / 1e6 / passes;
            double total = gen + mesh;

            System.out.println();
            System.out.printf("Chunk categories : %d empty, %d full, %d surface (total %d)%n",
                    nEmpty, nFull, nSurface, totalChunks);
            System.out.printf("Meshing          : %d chunks meshed, %d full chunks skipped (surrounded)%n",
                    nMeshed, nSkippedFull);
            System.out.println();
            System.out.printf("TOTAL startup (single thread) : %8.1f ms%n", total);
            System.out.printf("  generation : %8.1f ms  (%4.1f%%)%n", gen, 100 * gen / total);
            System.out.printf("     - empty chunks   : %7.1f ms  (%d chunks, %.1f us/chunk)  <- noise heightmap only%n",
                    genEmpty, nEmpty, nEmpty == 0 ? 0 : genEmpty * 1000 / nEmpty);
            System.out.printf("     - full chunks    : %7.1f ms  (%d chunks, %.1f us/chunk)  <- noise heightmap + fill%n",
                    genFull, nFull, nFull == 0 ? 0 : genFull * 1000 / nFull);
            System.out.printf("     - surface chunks : %7.1f ms  (%d chunks, %.1f us/chunk)  <- full per-block + trees%n",
                    genSurface, nSurface, nSurface == 0 ? 0 : genSurface * 1000 / nSurface);
            System.out.printf("  meshing    : %8.1f ms  (%4.1f%%)  (%d chunks, %.1f us/chunk)%n",
                    mesh, 100 * mesh / total, nMeshed, nMeshed == 0 ? 0 : mesh * 1000 / nMeshed);
        }
    }

    private static final class MapResolver implements ChunkResolver {
        private final Map<Vec3i, Chunk> world;

        MapResolver(Map<Vec3i, Chunk> world) {
            this.world = world;
        }

        @Override
        public Optional<Chunk> get(Vec3i location) {
            return Optional.ofNullable(world.get(location));
        }

        @Override
        public Chunk unsafeFastGet(Vec3i location) {
            return world.get(location);
        }
    }
}
