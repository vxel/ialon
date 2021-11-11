package org.delaunois.ialon;

import com.jayfella.fastnoise.FastNoise;
import com.jayfella.fastnoise.LayeredNoise;
import com.jayfella.fastnoise.NoiseLayer;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.rvandoosselaer.blocks.Block;
import com.rvandoosselaer.blocks.BlockIds;
import com.rvandoosselaer.blocks.BlockRegistry;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.rvandoosselaer.blocks.Chunk;
import com.rvandoosselaer.blocks.ShapeIds;
import com.simsilica.mathd.Vec3i;

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
        Block itemGrass = BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.ITEM_GRASS);

        Chunk chunk = Chunk.createAt(location);
        int maxX = chunkSize.x;
        int maxY = chunkSize.y;
        int maxZ = chunkSize.z;

        for (int x = 0; x < maxX; x++) {
            for (int z = 0; z < maxZ; z++) {
                float groundh = getHeight(getWorldLocation(new Vector3f(x, 0, z), chunk));
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
                        chunk.setSunlight(x, y, z, 15);

                        if (worldY > waterHeight) {
                            block = BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.GRASS);
                            if ((groundh * 10000) % 1 == 0) {
                                chunk.addBlock(x, y + 1, z, itemGrass);
                            }
                        } else if (worldY == (int) waterHeight && worldY == (int) groundh) {
                            block = BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.SAND);
                        } else {
                            block = BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.getName(WATER, ShapeIds.LIQUID5));
                            chunk.setSunlight(x, y, z, 15);
                        }

                    } else {
                        // Under ground / under water
                        if (worldY > groundh) {
                            // Above ground but below horizon => in water
                            block = BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.getName(WATER, ShapeIds.LIQUID));
                            chunk.setSunlight(x, y, z, Math.max(0, 13 - ((int)horizon - worldY) * 2));

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

        generateTrees(chunk);

        chunk.setDirty(true);
        return chunk;
    }

    private void generateTrees(Chunk chunk) {
        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();
        int maxX = chunkSize.x;
        int maxZ = chunkSize.z;

        for (int x = -CANOPY_RADIUS; x <= maxX + CANOPY_RADIUS; x++) {
            for (int z = -CANOPY_RADIUS; z <= maxZ + CANOPY_RADIUS; z++) {

                float groundh = getHeight(getWorldLocation(new Vector3f(x, 0, z), chunk));
                int y = (int)groundh - (chunk.getLocation().y * chunkSize.y);
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