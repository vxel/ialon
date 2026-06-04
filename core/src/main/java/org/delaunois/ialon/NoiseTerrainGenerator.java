/*
 * Copyright (C) 2022 Cédric de Launois
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.delaunois.ialon;

import static com.rvandoosselaer.blocks.BlockIds.WATER;
import static com.rvandoosselaer.blocks.BlockIds.WATER_SOURCE;

import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.rvandoosselaer.blocks.Block;
import com.rvandoosselaer.blocks.BlockIds;
import com.rvandoosselaer.blocks.BlockRegistry;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.rvandoosselaer.blocks.Chunk;
import com.rvandoosselaer.blocks.ShapeIds;
import com.rvandoosselaer.blocks.TypeIds;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.fastnoise.FastNoise;
import org.delaunois.ialon.fastnoise.LayeredNoise;
import org.delaunois.ialon.fastnoise.NoiseLayer;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

public class NoiseTerrainGenerator implements TerrainGenerator {

    private static final int CANOPY_RADIUS = 3;
    private static final int TRUNK_HEIGHT = 3;
    private static final int TREE_HEIGHT = TRUNK_HEIGHT + 2 * CANOPY_RADIUS + 1;

    // The heightmap depends only on the chunk's (x, z) column, yet every chunk of a vertical column
    // (gridHeight of them) would recompute the same hundreds of noise samples. This bounded,
    // thread-safe LRU memorizes the heightmap per column so it is computed once and shared by
    // the whole column.
    private static final int HEIGHTS_CACHE_CAPACITY = 256;

    private long seed;
    private float waterHeight;
    private LayeredNoise layeredNoise;
    private final Map<Long, float[]> heightsCache =
            Collections.synchronizedMap(new HeightsCache(HEIGHTS_CACHE_CAPACITY));

    // Cached block references, resolved once on first generation to avoid a String-keyed
    // registry lookup (and, for itemGrass/waterLiquid, a String concatenation) per generated block.
    private volatile boolean blocksResolved = false;
    private Block blockRock;
    private Block blockGrass;
    private Block blockSand;
    private Block blockDirt;
    private Block blockWaterSource;
    private Block blockWaterLiquid;
    private Block blockOakLog;
    private Block blockOakLeaves;
    private Block blockItemGrass;

    public NoiseTerrainGenerator(long seed, float waterHeight) {
        this.seed = seed;
        this.waterHeight = waterHeight;
        createWorldNoise();
    }

    private void resolveBlocks() {
        BlockRegistry registry = BlocksConfig.getInstance().getBlockRegistry();
        blockRock = registry.get(BlockIds.ROCK);
        blockGrass = registry.get(BlockIds.GRASS);
        blockSand = registry.get(BlockIds.SAND);
        blockDirt = registry.get(BlockIds.DIRT);
        blockWaterSource = registry.get(WATER_SOURCE);
        blockWaterLiquid = registry.get(BlockIds.getName(WATER, ShapeIds.LIQUID));
        blockOakLog = registry.get(BlockIds.OAK_LOG);
        blockOakLeaves = registry.get(BlockIds.OAK_LEAVES);
        blockItemGrass = registry.get(BlockIds.getName(TypeIds.ITEM_GRASS, ShapeIds.CROSS_PLANE, 0));
        // Written last: a thread observing blocksResolved == true is guaranteed to see all block fields.
        blocksResolved = true;
    }

    private void ensureBlocksResolved() {
        if (!blocksResolved) {
            resolveBlocks();
        }
    }

    public long getSeed() {
        return seed;
    }

    public void setSeed(long seed) {
        this.seed = seed;
        heightsCache.clear();
    }

    @Override
    public float getWaterHeight() {
        return waterHeight;
    }

    @Override
    public void setWaterHeight(float waterHeight) {
        this.waterHeight = waterHeight;
    }

    @Override
    public Chunk generate(Vec3i location) {
        ensureBlocksResolved();
        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();

        Chunk chunk = Chunk.createAt(location);
        int maxX = chunkSize.x;
        int maxY = chunkSize.y;
        int maxZ = chunkSize.z;
        int minWorldY = chunk.getLocation().y * chunkSize.y;
        int maxWorldY = chunk.getLocation().y * chunkSize.y + chunkSize.y - 1;
        int sizez = maxZ + 2 * CANOPY_RADIUS + 1;
        float[] heights = getHeights(chunk, -CANOPY_RADIUS, maxX + CANOPY_RADIUS, -CANOPY_RADIUS, maxZ + CANOPY_RADIUS);

        float minh = heights[0] - 4; // Include mud depth
        float maxh = heights[1] + TRUNK_HEIGHT + 2 * CANOPY_RADIUS; // Include tree heights !

        if (maxh < minWorldY) {
            // all heights are below the min Y of the chunk : the chunk is empty
            return chunk;
        }

        if (minh > maxWorldY) {
            // all heights are above the max Y of the chunk : the chunk is full with ROCK
            short[] blocks = new short[chunkSize.x * chunkSize.y * chunkSize.z];
            short rockId = blockRock.getId();
            Arrays.fill(blocks, rockId);
            chunk.setBlocks(blocks);
            chunk.setLightMap(new byte[chunkSize.x * chunkSize.y * chunkSize.z]);
            return chunk;
        }

        for (int x = 0; x < maxX; x++) {
            int rowx = (x + CANOPY_RADIUS) * sizez;

            for (int z = 0; z < maxZ; z++) {
                int index = 2 + (z + CANOPY_RADIUS) + rowx;
                float groundh = heights[index];
                float horizon = Math.max(groundh, waterHeight);

                for (int y = maxY - 1; y >= 0; y--) {
                    int worldY = (chunk.getLocation().y * chunkSize.y) + y;
                    generate(chunk, x, y, z, worldY, groundh, horizon);
                }
            }
        }

        generateTrees(chunk, heights);

        chunk.setDirty(true);
        return chunk;
    }

    private void generate(Chunk chunk, int x, int y, int z, int worldY, float groundh, float horizon) {
        Block block;

        if (worldY > horizon) {
            block = generateAboveHorizon(chunk, x, y, z);

        } else if (worldY == (int) horizon) {
            block = generateSurface(chunk, x, y, z, worldY, groundh);

        } else {
            block = generateUnderground(chunk, x, y, z, worldY, groundh, horizon);
        }

        if (block != null) {
            chunk.addBlock(x, y, z, block);
        }
    }

    private Block generateAboveHorizon(Chunk chunk, int x, int y, int z) {
        chunk.setSunlight(x, y, z, 15);
        return null;
    }

    private Block generateSurface(Chunk chunk, int x, int y, int z, int worldY, float groundh) {
        chunk.setSunlight(x, y, z, 0);

        Block block;
        if (worldY > waterHeight) {
            block = blockGrass;
            if (y < 15 && (groundh * 10000) % 1 == 0) {
                chunk.addBlock(x, y + 1, z, blockItemGrass);
            }
        } else if (worldY == (int) waterHeight && worldY == (int) groundh) {
            block = blockSand;
        } else {
            block = blockWaterSource;
            chunk.setSunlight(x, y, z, 14);
        }
        return block;
    }

    private Block generateUnderground(Chunk chunk, int x, int y, int z, int worldY, float groundh, float horizon) {
        Block block;
        if (worldY > groundh) {
            // Above ground but below horizon => in water
            block = blockWaterLiquid;
            chunk.setSunlight(x, y, z, Math.max(0, 14 - ((int) horizon - worldY)));

        } else {
            chunk.setSunlight(x, y, z, 0);
            if (waterHeight - worldY < 3 && worldY == (int) groundh) {
                block = blockSand;
            } else if (groundh - worldY < 3) {
                block = blockDirt;
            } else {
                block = blockRock;
            }
        }
        return block;
    }

    private float[] getHeights(Chunk chunk, int minx, int maxx, int minz, int maxz) {
        // Memorize per column : the heightmap is identical for every chunk sharing the same (x, z).
        // The returned array is treated as immutable by all callers, so it is safe to share.
        long key = (((long) chunk.getLocation().x) << 32) | (chunk.getLocation().z & 0xFFFFFFFFL);
        float[] cached = heightsCache.get(key);
        if (cached != null) {
            return cached;
        }
        float[] heights = computeHeights(chunk, minx, maxx, minz, maxz);
        heightsCache.put(key, heights);
        return heights;
    }

    private float[] computeHeights(Chunk chunk, int minx, int maxx, int minz, int maxz) {
        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();
        int worldOffsetX = chunk.getLocation().x * chunkSize.x;
        int worldOffsetZ = chunk.getLocation().z * chunkSize.z;
        int sizex = maxx - minx + 1;
        int sizez = maxz - minz + 1;
        float min = Float.MAX_VALUE;
        float max = Float.MIN_VALUE;
        float[] heights = new float[2 + sizex * sizez];
        // A single Vector2f is reused for every noise sample (the noise layers never mutate it,
        // they clone internally) : one allocation per chunk instead of two per sampled column.
        Vector2f sample = new Vector2f();
        for (int x = minx; x <= maxx; x++) {
            int rowx = (x - minx) * sizez;
            for (int z = minz; z <= maxz; z++) {
                int index = (z - minz) + rowx;
                float h = getHeight(worldOffsetX + x, worldOffsetZ + z, sample);
                heights[index] = h;
                if (h < min) {
                    min = h;
                }
                if (h > max) {
                    max = h;
                }
            }
        }
        heights[0] = min;
        heights[1] = max;
        return heights;
    }

    private void generateTrees(Chunk chunk, float[] heights) {
        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();
        int maxX = chunkSize.x;
        int maxZ = chunkSize.z;
        int sizez = maxZ + 2 * CANOPY_RADIUS + 1;

        for (int x = -CANOPY_RADIUS; x <= maxX + CANOPY_RADIUS; x++) {
            int rowx = (x + CANOPY_RADIUS) * sizez;
            for (int z = -CANOPY_RADIUS; z <= maxZ + CANOPY_RADIUS; z++) {
                int index = (z + CANOPY_RADIUS) + rowx;
                float groundh = heights[index];
                int y = (int) groundh - (chunk.getLocation().y * chunkSize.y);
                if (y > -TREE_HEIGHT && y < chunkSize.z && groundh > (waterHeight + 1) && ((groundh * 100) % 1 == 0)) {
                    createTree(chunk, x, y, z);
                }
            }
        }
    }

    private void createTree(Chunk chunk, int x, int y, int z) {
        createTrunk(chunk, x, y, z);
        createCanopy(chunk, x, y, z);
        createTreeShadow(chunk, x, y, z);
    }

    private void createTrunk(Chunk chunk, int posx, int posy, int posz) {
        Block trunk = blockOakLog;
        Vec3i treeLocation = new Vec3i(posx, posy, posz);
        for (int y = 0; y < TRUNK_HEIGHT; y++) {
            addBlock(chunk, treeLocation.add(0, y, 0), trunk);
        }
    }

    private void createCanopy(Chunk chunk, int posx, int posy, int posz) {
        Block leaves = blockOakLeaves;
        Vector3f locf = new Vector3f();
        Vec3i loci = new Vec3i();
        Vec3i canopyCenter = new Vec3i(posx, posy + TRUNK_HEIGHT + CANOPY_RADIUS - 1, posz);

        for (int y = canopyCenter.y - CANOPY_RADIUS; y <= canopyCenter.y + CANOPY_RADIUS; y++) {
            for (int x = canopyCenter.x - CANOPY_RADIUS; x <= canopyCenter.x + CANOPY_RADIUS; x++) {
                for (int z = canopyCenter.z - CANOPY_RADIUS; z <= canopyCenter.z + CANOPY_RADIUS; z++) {
                    locf.set(x, y, z);
                    loci.set(x, y, z);
                    float distance = locf.distance(canopyCenter.toVector3f());
                    if (distance <= CANOPY_RADIUS && y > canopyCenter.y - CANOPY_RADIUS) {
                        addBlock(chunk, loci, leaves);
                    }
                }
            }
        }
    }

    private void createTreeShadow(Chunk chunk, int posx, int posy, int posz) {
        Vector3f locf = new Vector3f();
        Vec3i treeLocation = new Vec3i(posx, posy, posz);

        for (int y = treeLocation.y; y < treeLocation.y + TRUNK_HEIGHT + CANOPY_RADIUS - 1; y++) {
            for (int x = treeLocation.x - CANOPY_RADIUS; x <= treeLocation.x + CANOPY_RADIUS; x++) {
                for (int z = treeLocation.z - CANOPY_RADIUS; z <= treeLocation.z + CANOPY_RADIUS; z++) {
                    if (!Chunk.isInsideChunk(x, y, z)) {
                        continue;
                    }
                    locf.set(x, y, z);
                    float distance = locf.distance(new Vector3f(treeLocation.x, y, treeLocation.z));
                    if (distance <= CANOPY_RADIUS) {
                        chunk.setSunlight(x, y, z, Math.max(0, 11 + ((int) distance)));
                    }
                }
            }
        }
    }

    private void addBlock(Chunk chunk, Vec3i location, Block block) {
        if (Chunk.isInsideChunk(location.x, location.y, location.z)) {
            chunk.addBlock(location, block);
            chunk.setSunlight(location.x, location.y, location.z, 0);
        }
    }

    private void createWorldNoise() {
        // The noise definition changes : any memorized heightmap is now stale.
        heightsCache.clear();
        Random random = new Random(seed);
        layeredNoise = new LayeredNoise();

        layeredNoise.setHardFloor(true);
        layeredNoise.setHardFloorHeight(waterHeight);
        layeredNoise.setHardFloorStrength(0.6f);

        NoiseLayer mountains = new NoiseLayer("mountains");
        mountains.setSeed(random.nextInt());
        mountains.setNoiseType(FastNoise.NoiseType.SimplexFractal);
        mountains.setStrength(82);
        mountains.setFrequency(mountains.getFrequency() / 8);
        layeredNoise.addLayer(mountains);

        NoiseLayer hills = new NoiseLayer("Hills");
        hills.setSeed(random.nextInt());
        hills.setNoiseType(FastNoise.NoiseType.SimplexFractal);
        hills.setStrength(41);
        hills.setFrequency(hills.getFrequency() / 3);
        layeredNoise.addLayer(hills);

        NoiseLayer details = new NoiseLayer("Details");
        details.setSeed(random.nextInt());
        details.setNoiseType(FastNoise.NoiseType.SimplexFractal);
        details.setStrength(21);
        layeredNoise.addLayer(details);

    }

    @Override
    public float getHeight(Vector3f blockLocation) {
        return getHeight(blockLocation.x, blockLocation.z, new Vector2f());
    }

    private float getHeight(float worldX, float worldZ, Vector2f sample) {
        float height = 32f;
        return layeredNoise.evaluate(sample.set(worldX, worldZ)) + height;
    }

    /**
     * Bounded LRU (access-ordered) cache of per-column heightmaps. Wrapped in a synchronized map by
     * the owner, since chunk generation runs on a thread pool.
     */
    private static final class HeightsCache extends LinkedHashMap<Long, float[]> {
        private static final long serialVersionUID = 1L;
        private final transient int capacity;

        HeightsCache(int capacity) {
            super(capacity * 4 / 3 + 1, 0.75f, true);
            this.capacity = capacity;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, float[]> eldest) {
            return size() > capacity;
        }
    }

}