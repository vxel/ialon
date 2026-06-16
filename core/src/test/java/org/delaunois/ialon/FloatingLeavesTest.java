package org.delaunois.ialon;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import com.simsilica.mathd.Vec3i;
import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlockIds;
import org.delaunois.ialon.blocks.BlockRegistry;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.Chunk;
import org.delaunois.ialon.blocks.generator.NoiseTerrainGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Regression for the "floating leaves" bug : on steep terrain (high relief) a tree's canopy could end
 * up disconnected from any trunk, leaving leaf blocks floating in the air. Root cause was a write/read
 * mismatch in the per-chunk heightmap (it was written at {@code heights[index]} but read at
 * {@code heights[2 + index]}), so the tree base height was sampled 2 cells away in z -- and near a chunk
 * z-edge that offset wrapped into another row, making neighbouring chunks place the same tree's canopy
 * at different heights.
 * <p>
 * Reproduces the reported world's parameters (steep relief, max tree density), generates a region the
 * way the renderer pages it (absolute chunk coords), flood-fills from every log through 6-connected
 * log/leaf blocks, and asserts that no leaf block is left unreachable from a log.
 */
class FloatingLeavesTest {

    static { System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName()); }

    @BeforeAll
    static void setUp() { IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), new IalonConfig()); }

    private static long key(int x, int y, int z) {
        return ((long) (x + 100000) << 42) | ((long) (y + 1000) << 21) | (z + 100000);
    }

    @Test
    void leavesAreNeverFloatingOnSteepTerrain() {
        IalonConfig config = new IalonConfig();
        int ch = config.getChunkHeight();
        int gh = config.getGridHeight();
        int cs = BlocksConfig.getInstance().getChunkSize().x;

        BlockRegistry reg = BlocksConfig.getInstance().getBlockRegistry();
        Set<Short> logIds = new HashSet<>();
        Set<Short> leafIds = new HashSet<>();
        for (String n : new String[]{BlockIds.OAK_LOG, BlockIds.BIRCH_LOG, BlockIds.SPRUCE_LOG, BlockIds.PALM_TREE_LOG})
            logIds.add(reg.get(n).getId());
        for (String n : new String[]{BlockIds.OAK_LEAVES, BlockIds.BIRCH_LEAVES, BlockIds.SPRUCE_LEAVES, BlockIds.PALM_TREE_LEAVES})
            leafIds.add(reg.get(n).getId());

        // The reported world : steep relief + max tree density makes the bug fire densely.
        NoiseTerrainGenerator gen = new NoiseTerrainGenerator(6870, 30f, config.getMaxy(), 256 * cs);
        gen.setReliefAmplitude(2.5f);
        gen.setReliefFrequency(2.0f);
        gen.setTreeMaxProb(1.0f);

        Map<Long, Byte> occ = new HashMap<>();           // 1 = log, 2 = leaf
        Deque<long[]> logSeeds = new ArrayDeque<>();

        int cxMin = 0, cxMax = 16, czMin = -14, czMax = 2;
        for (int cx = cxMin; cx <= cxMax; cx++)
            for (int cz = czMin; cz <= czMax; cz++)
                for (int cy = 0; cy < gh; cy++) {
                    Chunk c = gen.generate(new Vec3i(cx, cy, cz));
                    if (c.getBlocks() == null) continue;
                    for (int lx = 0; lx < cs; lx++)
                        for (int ly = 0; ly < ch; ly++)
                            for (int lz = 0; lz < cs; lz++) {
                                Block b = c.getBlock(lx, ly, lz);
                                if (b == null) continue;
                                short id = b.getId();
                                boolean log = logIds.contains(id), leaf = leafIds.contains(id);
                                if (!log && !leaf) continue;
                                int wx = cx * cs + lx, wy = cy * ch + ly, wz = cz * cs + lz;
                                occ.put(key(wx, wy, wz), log ? (byte) 1 : (byte) 2);
                                if (log) logSeeds.add(new long[]{wx, wy, wz});
                            }
                }

        Set<Long> reached = new HashSet<>();
        Deque<long[]> q = new ArrayDeque<>(logSeeds);
        for (long[] s : logSeeds) reached.add(key((int) s[0], (int) s[1], (int) s[2]));
        int[][] d6 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!q.isEmpty()) {
            long[] p = q.poll();
            int x = (int) p[0], y = (int) p[1], z = (int) p[2];
            for (int[] d : d6) {
                int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                long k = key(nx, ny, nz);
                if (occ.containsKey(k) && reached.add(k)) q.add(new long[]{nx, ny, nz});
            }
        }

        // Leaves in the outermost ring of generated chunks are skipped : their supporting trunk may sit
        // in a chunk just outside the scanned region.
        List<int[]> floating = new ArrayList<>();
        for (Map.Entry<Long, Byte> e : occ.entrySet()) {
            if (e.getValue() != 2 || reached.contains(e.getKey())) continue;
            long k = e.getKey();
            int z = (int) (k & ((1 << 21) - 1)) - 100000;
            int y = (int) ((k >> 21) & ((1 << 21) - 1)) - 1000;
            int x = (int) ((k >> 42) & ((1 << 21) - 1)) - 100000;
            int bcx = Math.floorDiv(x, cs), bcz = Math.floorDiv(z, cs);
            if (bcx <= cxMin || bcx >= cxMax || bcz <= czMin || bcz >= czMax) continue;
            floating.add(new int[]{x, y, z});
        }

        floating.sort((a, b) -> a[0] != b[0] ? a[0] - b[0] : (a[2] != b[2] ? a[2] - b[2] : a[1] - b[1]));
        StringBuilder msg = new StringBuilder("found " + floating.size() + " floating leaf block(s):");
        for (int i = 0; i < Math.min(20, floating.size()); i++)
            msg.append("\n  world(").append(floating.get(i)[0]).append(',').append(floating.get(i)[1])
                    .append(',').append(floating.get(i)[2]).append(')');
        org.junit.jupiter.api.Assertions.assertTrue(floating.isEmpty(), msg.toString());
    }
}
