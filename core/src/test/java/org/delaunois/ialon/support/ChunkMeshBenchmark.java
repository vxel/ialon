package org.delaunois.ialon.support;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.Vector4f;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import com.rvandoosselaer.blocks.Block;
import com.rvandoosselaer.blocks.BlockIds;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.rvandoosselaer.blocks.Chunk;
import com.rvandoosselaer.blocks.ChunkResolver;
import com.rvandoosselaer.blocks.Direction;
import com.rvandoosselaer.blocks.ShapeIds;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.BlockNeighborhood;
import org.delaunois.ialon.FacesMeshGenerator;
import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.IalonInitializer;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manual (interactive) micro-benchmark for chunk meshing — NOT part of the automated suite.
 * Run its {@link #main(String[])} from the IDE.
 *
 * <p>Goal : quantify the opportunity for "option 4" (a per-chunk padded neighbour/light buffer).
 * It measures, on a realistic surface chunk fully surrounded by its 26 neighbour chunks :
 * <ul>
 *   <li><b>T_full</b> : full meshing time (render mesh + greedy collision mesh), the production path;</li>
 *   <li><b>T_gather</b> : the neighbour/light gathering sub-path in isolation (what option 4 would
 *       optimise) — replicates exactly what {@code Cube.softShadow} does per visible face;</li>
 *   <li><b>resolver lookups / chunk</b> : the number of cross-chunk {@link ChunkResolver} hits, i.e.
 *       the expensive border work option 4 would collapse to ~the chunk surface area.</li>
 * </ul>
 * Light values are irrelevant to timing (the code path runs identically whatever the light level),
 * so sunlight is not propagated — only the block layout (hence the face-visibility pattern) matters.
 */
public class ChunkMeshBenchmark {

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    private static final long WARMUP_NANOS = 2_000_000_000L;
    private static final long MEASURE_NANOS = 4_000_000_000L;

    public static void main(String[] args) {
        IalonConfig config = new IalonConfig();
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), config);

        Vec3i cs = BlocksConfig.getInstance().getChunkSize();
        Block rock = BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.ROCK);

        CountingResolver resolver = new CountingResolver();
        // Build the center chunk and its full 3x3x3 neighbourhood so every border query resolves.
        for (int cx = -1; cx <= 1; cx++) {
            for (int cy = -1; cy <= 1; cy++) {
                for (int cz = -1; cz <= 1; cz++) {
                    Vec3i loc = new Vec3i(cx, cy, cz);
                    resolver.put(loc, buildChunk(loc, cs, rock, resolver));
                }
            }
        }

        Chunk center = resolver.unsafeFastGet(new Vec3i(0, 0, 0));
        FacesMeshGenerator generator = new FacesMeshGenerator(config);

        // Describe the workload.
        center.createNode(generator); // ensure node built once for vertex count
        int vertices = center.getNode().getVertexCount();
        int faces = vertices / 4;
        System.out.printf("Chunk size %s, block scale %.2f%n", cs, BlocksConfig.getInstance().getBlockScale());
        System.out.printf("Center chunk : empty=%b full=%b, render vertices=%d (~%d visible faces)%n",
                center.isEmpty(), center.isFull(), vertices, faces);

        // ---- T_full : production meshing path (render mesh + greedy collision mesh) ----
        runWarmup(() -> generator.createAndSetNodeAndCollisionMesh(center));
        resolver.reset();
        Result full = run(() -> generator.createAndSetNodeAndCollisionMesh(center));
        long resolverFull = resolver.count.get() / full.iterations;

        // ---- T_render : render mesh only (no collision mesh) ----
        runWarmup(() -> generator.createNode(center));
        Result render = run(() -> generator.createNode(center));

        // ---- T_collscan : the visibility scan done by the collision pass (buildCollisionMask) ----
        // This is exactly the work a shared visibility mask would eliminate from the collision pass :
        // the render pass already computes face visibility, so the collision pass could read it
        // instead of re-running isFaceVisible over all blocks x 6 directions.
        runWarmup(() -> collisionVisibilityScan(center, cs));
        Result collScan = run(() -> collisionVisibilityScan(center, cs));

        // ---- residual : what buildCollisionMask costs reading a pre-built shared mask instead ----
        boolean[] sharedMask = new boolean[6 * cs.x * cs.y * cs.z];
        runWarmup(() -> collisionMaskRead(cs, sharedMask));
        Result collRead = run(() -> collisionMaskRead(cs, sharedMask));

        // ---- Decompose the gather sub-path into traversal+visibility / light fetch / blend ----
        // MODE_VISIT  : setLocation + isFaceVisible only          (block-neighbour reads)
        // MODE_FETCH  : VISIT + getFaceLight + getNeighbourLights  (+ light fetch — option-4-addressable)
        // MODE_BLEND  : FETCH + 4x vertexColor per visible face    (+ unavoidable blend arithmetic)
        BlockNeighborhood neighborhood = new BlockNeighborhood(new Vec3i(0, 0, 0), center);
        runWarmup(() -> gatherPass(center, cs, neighborhood, MODE_BLEND));

        Result visit = run(() -> gatherPass(center, cs, neighborhood, MODE_VISIT));
        Result fetch = run(() -> gatherPass(center, cs, neighborhood, MODE_FETCH));
        resolver.reset();
        Result gather = run(() -> gatherPass(center, cs, neighborhood, MODE_BLEND));
        long resolverGather = resolver.count.get() / gather.iterations;

        double tVisit = visit.usPerOp();
        double tFetch = fetch.usPerOp();
        double tBlend = gather.usPerOp();
        double fetchCost = tFetch - tVisit;
        double blendCost = tBlend - tFetch;

        System.out.println();
        System.out.printf("T_full        : %8.1f us/chunk   (render mesh + greedy collision)%n", full.usPerOp());
        System.out.printf("T_render      : %8.1f us/chunk   -> %4.1f%% of full (render mesh only)%n",
                render.usPerOp(), 100.0 * render.usPerOp() / full.usPerOp());
        double tCollision = full.usPerOp() - render.usPerOp();
        System.out.printf("T_collision   : %8.1f us/chunk   -> %4.1f%% of full (greedy collision mesh)%n",
                tCollision, 100.0 * tCollision / full.usPerOp());
        System.out.printf("   - visibility scan (shared-mask-addressable) : %7.1f us   (%4.1f%% of full)%n",
                collScan.usPerOp(), 100.0 * collScan.usPerOp() / full.usPerOp());
        System.out.printf("   - greedy merge + emit + generateMesh        : %7.1f us   (%4.1f%% of full)%n",
                tCollision - collScan.usPerOp(), 100.0 * (tCollision - collScan.usPerOp()) / full.usPerOp());
        double netSaving = collScan.usPerOp() - collRead.usPerOp();
        System.out.printf("   - residual scan reading shared mask         : %7.1f us%n", collRead.usPerOp());
        System.out.printf("   => shared-mask NET saving                   : %7.1f us   (%4.1f%% of full meshing)%n",
                netSaving, 100.0 * netSaving / full.usPerOp());
        System.out.printf("T_gather      : %8.1f us/chunk   -> %4.1f%% of T_full%n",
                tBlend, 100.0 * tBlend / full.usPerOp());
        System.out.println("  decomposition of the gather path :");
        System.out.printf("   - traversal + face visibility : %7.1f us   (%4.1f%% of full)%n",
                tVisit, 100.0 * tVisit / full.usPerOp());
        System.out.printf("   - light fetch (addressable)    : %7.1f us   (%4.1f%% of full)%n",
                fetchCost, 100.0 * fetchCost / full.usPerOp());
        System.out.printf("   - vertexColor blend (fixed)    : %7.1f us   (%4.1f%% of full)%n",
                blendCost, 100.0 * blendCost / full.usPerOp());
        System.out.println();
        System.out.printf("Resolver (cross-chunk) lookups / chunk : full=%d  gather=%d%n", resolverFull, resolverGather);
        System.out.printf("Padded shell size (lights fetched once by option 4) : %d   (%dx%dx%d - %dx%dx%d)%n",
                (cs.x + 2) * (cs.y + 2) * (cs.z + 2) - cs.x * cs.y * cs.z,
                cs.x + 2, cs.y + 2, cs.z + 2, cs.x, cs.y, cs.z);
    }

    /**
     * Replicates the neighbour/light gathering the cube mesher performs for every visible face
     * ({@code Cube.add} -> {@code softShadow}) without writing any mesh buffer. This is precisely
     * the work a per-chunk padded light buffer (option 4) would replace.
     */
    private static final int MODE_VISIT = 0;
    private static final int MODE_FETCH = 1;
    private static final int MODE_BLEND = 2;

    private static long gatherPass(Chunk chunk, Vec3i cs, BlockNeighborhood n, int mode) {
        long checksum = 0;
        Vector4f store = n.getColorScratch();
        Vec3i loc = new Vec3i();
        for (int x = 0; x < cs.x; x++) {
            for (int y = 0; y < cs.y; y++) {
                for (int z = 0; z < cs.z; z++) {
                    Block block = chunk.getBlock(x, y, z);
                    if (block == null) {
                        continue;
                    }
                    n.setLocation(loc.set(x, y, z));
                    for (Direction face : Direction.values()) {
                        if (!chunk.isFaceVisible(n, face)) {
                            continue;
                        }
                        if (mode == MODE_VISIT) {
                            checksum++;
                            continue;
                        }
                        Vector4f color = n.getFaceLight(face);
                        Vector4f[] nb = n.getNeighbourLights(face);
                        if (mode == MODE_FETCH) {
                            checksum += (long) (color.w + nb[0].w + nb[7].w);
                            continue;
                        }
                        checksum += (long) chunk.vertexColor(nb[7], nb[1], nb[0], color, store).w;
                        checksum += (long) chunk.vertexColor(nb[1], nb[3], nb[2], color, store).w;
                        checksum += (long) chunk.vertexColor(nb[5], nb[7], nb[6], color, store).w;
                        checksum += (long) chunk.vertexColor(nb[3], nb[5], nb[4], color, store).w;
                    }
                }
            }
        }
        return checksum;
    }

    /**
     * Faithful replica of {@code FacesMeshGenerator.buildCollisionMask} over all 6 face directions :
     * for every block, getBlock + isCollisionCube + isFaceVisible. This is the per-chunk visibility
     * scan the collision pass performs today, and exactly what a shared visibility mask would replace
     * with a single array read per (block, direction).
     */
    private static long collisionVisibilityScan(Chunk chunk, Vec3i cs) {
        long checksum = 0;
        int[] dims = {cs.x, cs.y, cs.z};
        int[] coord = new int[3];
        Vec3i loc = new Vec3i();
        for (Direction direction : Direction.values()) {
            Vec3i dir = direction.getVector();
            int faceAxis = dir.x != 0 ? 0 : (dir.y != 0 ? 1 : 2);
            int uAxis = (faceAxis == 0) ? 1 : 0;
            int vAxis = (faceAxis == 2) ? 1 : 2;
            int uSize = dims[uAxis];
            int vSize = dims[vAxis];
            int layers = dims[faceAxis];
            for (int s = 0; s < layers; s++) {
                for (int vv = 0; vv < vSize; vv++) {
                    for (int uu = 0; uu < uSize; uu++) {
                        coord[faceAxis] = s;
                        coord[uAxis] = uu;
                        coord[vAxis] = vv;
                        Block b = chunk.getBlock(coord[0], coord[1], coord[2]);
                        boolean exposed = isCollisionCube(b)
                                && chunk.isFaceVisible(loc.set(coord[0], coord[1], coord[2]), direction);
                        if (exposed) {
                            checksum++;
                        }
                    }
                }
            }
        }
        return checksum;
    }

    /**
     * Cost of the same per-(block,direction) traversal when reading a pre-built shared visibility
     * mask (the residual cost of buildCollisionMask under the shared-mask design).
     */
    private static long collisionMaskRead(Vec3i cs, boolean[] sharedMask) {
        long checksum = 0;
        int[] dims = {cs.x, cs.y, cs.z};
        int volume = cs.x * cs.y * cs.z;
        for (Direction direction : Direction.values()) {
            int d = direction.ordinal();
            int faceAxis = direction.getVector().x != 0 ? 0 : (direction.getVector().y != 0 ? 1 : 2);
            int uAxis = (faceAxis == 0) ? 1 : 0;
            int vAxis = (faceAxis == 2) ? 1 : 2;
            int uSize = dims[uAxis];
            int vSize = dims[vAxis];
            int layers = dims[faceAxis];
            int[] coord = new int[3];
            for (int s = 0; s < layers; s++) {
                for (int vv = 0; vv < vSize; vv++) {
                    for (int uu = 0; uu < uSize; uu++) {
                        coord[faceAxis] = s;
                        coord[uAxis] = uu;
                        coord[vAxis] = vv;
                        int index = coord[2] + (coord[1] + coord[0] * cs.y) * cs.z;
                        if (sharedMask[d * volume + index]) {
                            checksum++;
                        }
                    }
                }
            }
        }
        return checksum;
    }

    private static boolean isCollisionCube(Block block) {
        return block != null && block.isSolid() && ShapeIds.CUBE.equals(block.getShape());
    }

    private static Chunk buildChunk(Vec3i chunkLoc, Vec3i cs, Block rock, ChunkResolver resolver) {
        Chunk chunk = Chunk.createAt(chunkLoc);
        for (int x = 0; x < cs.x; x++) {
            for (int z = 0; z < cs.z; z++) {
                int worldX = chunkLoc.x * cs.x + x;
                int worldZ = chunkLoc.z * cs.z + z;
                double h = heightAt(worldX, worldZ);
                for (int y = 0; y < cs.y; y++) {
                    int worldY = chunkLoc.y * cs.y + y;
                    if (worldY <= h) {
                        chunk.addBlock(x, y, z, rock);
                    }
                }
            }
        }
        chunk.setLightMap(new byte[cs.x * cs.y * cs.z]); // non-null lightMap so getLightLevel works
        chunk.update();
        chunk.setChunkResolver(resolver);
        return chunk;
    }

    // Deterministic rolling-hills heightmap (sum of sines), centered so the surface crosses chunk y=0.
    private static double heightAt(int worldX, int worldZ) {
        return 8.0 + 4.0 * Math.sin(worldX * 0.15) + 3.0 * Math.cos(worldZ * 0.18)
                + 1.5 * Math.sin((worldX + worldZ) * 0.07);
    }

    private static void runWarmup(Runnable op) {
        long end = System.nanoTime() + WARMUP_NANOS;
        while (System.nanoTime() < end) {
            op.run();
        }
    }

    private static Result run(Runnable op) {
        long iterations = 0;
        long start = System.nanoTime();
        long end = start + MEASURE_NANOS;
        long now;
        do {
            op.run();
            iterations++;
            now = System.nanoTime();
        } while (now < end);
        return new Result(iterations, now - start);
    }

    private static final class Result {
        final long iterations;
        final long nanos;

        Result(long iterations, long nanos) {
            this.iterations = iterations;
            this.nanos = nanos;
        }

        double usPerOp() {
            return (nanos / 1000.0) / iterations;
        }
    }

    /** Map-backed resolver that counts cross-chunk lookups (every out-of-chunk neighbour/light query). */
    private static final class CountingResolver implements ChunkResolver {
        final Map<Vec3i, Chunk> chunks = new HashMap<>();
        final AtomicLong count = new AtomicLong();

        void put(Vec3i loc, Chunk chunk) {
            chunks.put(loc, chunk);
        }

        void reset() {
            count.set(0);
        }

        @Override
        public Optional<Chunk> get(Vec3i location) {
            count.incrementAndGet();
            return Optional.ofNullable(chunks.get(location));
        }

        @Override
        public Chunk unsafeFastGet(Vec3i location) {
            count.incrementAndGet();
            return chunks.get(location);
        }
    }
}
