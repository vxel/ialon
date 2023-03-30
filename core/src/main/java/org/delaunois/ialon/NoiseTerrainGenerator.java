/**
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
import java.util.Random;

import static com.rvandoosselaer.blocks.TypeIds.WATER;

public class NoiseTerrainGenerator implements TerrainGenerator {

    private static final int CANOPY_RADIUS = 3;
    private static final int TRUNK_HEIGHT = 3;
    private static final int TREE_HEIGHT = TRUNK_HEIGHT + 2 * CANOPY_RADIUS + 1;

    private long seed;
    private float waterHeight = Config.WATER_HEIGHT;
    private LayeredNoise layeredNoise;

    public NoiseTerrainGenerator(long seed) {
        this.seed = seed;
        createWorldNoise();
    }

    public long getSeed() {
        return seed;
    }

    public void setSeed(long seed) {
        this.seed = seed;
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
        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();
        Block itemGrass = BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.getName(TypeIds.ITEM_GRASS, ShapeIds.CROSS_PLANE, 0));

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
            short rockId = BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.ROCK).getId();
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

                    Block block;

                    if (worldY > horizon) {
                        // Above horizon
                        chunk.setSunlight(x, y, z, 15);
                        block = null;

                    } else if (worldY == (int) horizon) {
                        // Surface
                        chunk.setSunlight(x, y, z, 0);

                        if (worldY > waterHeight) {
                            block = BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.GRASS);
                            if (y < 15 && (groundh * 10000) % 1 == 0) {
                                chunk.addBlock(x, y + 1, z, itemGrass);
                            }
                        } else if (worldY == (int) waterHeight && worldY == (int) groundh) {
                            block = BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.SAND);
                        } else {
                            block = BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.getName(WATER, ShapeIds.LIQUID5));
                            chunk.setSunlight(x, y, z, 14);
                        }

                    } else {
                        // Under ground / under water
                        if (worldY > groundh) {
                            // Above ground but below horizon => in water
                            block = BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.getName(WATER, ShapeIds.LIQUID));
                            chunk.setSunlight(x, y, z, Math.max(0, 14 - ((int) horizon - worldY)));

                        } else {
                            chunk.setSunlight(x, y, z, 0);
                            if (waterHeight - worldY < 3 && worldY == (int) groundh) {
                                block = BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.SAND);
                            } else if (groundh - worldY < 3) {
                                block = BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.DIRT);
                            } else {
                                block = BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.ROCK);
                            }
                        }
                    }

                    if (block != null) {
                        chunk.addBlock(x, y, z, block);
                    }
                }
            }
        }

        generateTrees(chunk, heights);

        chunk.setDirty(true);
        return chunk;
    }

    private float[] getHeights(Chunk chunk, int minx, int maxx, int minz, int maxz) {
        int sizex = maxx - minx + 1;
        int sizez = maxz - minz + 1;
        float min = Float.MAX_VALUE;
        float max = Float.MIN_VALUE;
        float[] heights = new float[2 + sizex * sizez];
        for (int x = minx; x <= maxx; x++) {
            int rowx = (x - minx) * sizez;
            for (int z = minz; z <= maxz; z++) {
                int index = (z - minz) + rowx;
                float h = getHeight(getWorldLocation(new Vector3f(x, 0, z), chunk));
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
                    createTree(chunk, new Vec3i(x, y, z));
                }
            }
        }
    }

    private void createTree(Chunk chunk, Vec3i treeLocation) {
        BlockRegistry blockRegistry = BlocksConfig.getInstance().getBlockRegistry();
        Block trunk = blockRegistry.get(BlockIds.OAK_LOG);
        Block leaves = blockRegistry.get(BlockIds.OAK_LEAVES);

        // create a tree
        for (int i = 0; i < TRUNK_HEIGHT; i++) {
            addBlock(chunk, treeLocation, trunk);
            treeLocation.addLocal(0, 1, 0);
        }

        Vec3i canopyCenter = treeLocation.addLocal(0, CANOPY_RADIUS - 1, 0);
        for (int x = canopyCenter.x - CANOPY_RADIUS; x <= canopyCenter.x + CANOPY_RADIUS; x++) {
            for (int y = canopyCenter.y - CANOPY_RADIUS; y <= canopyCenter.y + CANOPY_RADIUS; y++) {
                for (int z = canopyCenter.z - CANOPY_RADIUS; z <= canopyCenter.z + CANOPY_RADIUS; z++) {
                    Vector3f location = new Vector3f(x, y, z);
                    float distance = location.distance(canopyCenter.toVector3f());
                    if (distance <= CANOPY_RADIUS && y > canopyCenter.y - CANOPY_RADIUS) {
                        addBlock(chunk, new Vec3i(x, y, z), leaves);
                    }
                }
            }
        }
    }

    private void addBlock(Chunk chunk, Vec3i location, Block block) {
        if (Chunk.isInsideChunk(location.x, location.y, location.z)) {
            chunk.addBlock(location, block);
        }
    }

    private Vector3f getWorldLocation(Vector3f blockLocation, Chunk chunk) {
        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();

        return new Vector3f((chunk.getLocation().x * chunkSize.x) + blockLocation.x,
                (chunk.getLocation().y * chunkSize.y) + blockLocation.y,
                (chunk.getLocation().z * chunkSize.z) + blockLocation.z);
    }

    private void createWorldNoise() {
        Random random = new Random(seed);
        layeredNoise = new LayeredNoise();

        layeredNoise.setHardFloor(true);
        layeredNoise.setHardFloorHeight(waterHeight);
        layeredNoise.setHardFloorStrength(0.6f);

        NoiseLayer mountains = new NoiseLayer("mountains");
        mountains.setSeed(random.nextInt());
        mountains.setNoiseType(FastNoise.NoiseType.SimplexFractal);
        mountains.setStrength(64);
        mountains.setFrequency(mountains.getFrequency() / 4);
        layeredNoise.addLayer(mountains);

        NoiseLayer hills = new NoiseLayer("Hills");
        hills.setSeed(random.nextInt());
        hills.setNoiseType(FastNoise.NoiseType.SimplexFractal);
        hills.setStrength(32);
        hills.setFrequency(hills.getFrequency() / 2);
        layeredNoise.addLayer(hills);

        NoiseLayer details = new NoiseLayer("Details");
        details.setSeed(random.nextInt());
        details.setNoiseType(FastNoise.NoiseType.SimplexFractal);
        details.setStrength(15);
        layeredNoise.addLayer(details);

    }

    @Override
    public float getHeight(Vector3f blockLocation) {
        float height = 32f;
        return layeredNoise.evaluate(new Vector2f(blockLocation.x, blockLocation.z)) + height;
    }

}