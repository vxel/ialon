package org.delaunois.ialon;

import com.jme3.math.Vector3f;
import com.rvandoosselaer.blocks.Block;
import com.rvandoosselaer.blocks.BlockIds;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.rvandoosselaer.blocks.Chunk;
import com.rvandoosselaer.blocks.ShapeIds;
import com.rvandoosselaer.blocks.TypeIds;
import com.rvandoosselaer.blocks.shapes.Liquid;
import com.simsilica.mathd.Vec3i;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import static com.rvandoosselaer.blocks.shapes.Liquid.LEVEL_MAX;

/**
 * Basic simulation of fluid
 *
 * @author Cedric de Launois
 */
@Slf4j
@AllArgsConstructor
public class ChunkLiquidManager {

    @Setter
    private ChunkManager chunkManager;

    private final Queue<LiquidNode> liquidBfsQueue = new LinkedList<>();

    public int queueSize() {
        return liquidBfsQueue.size();
    }

    /**
     * Add liquid at the given location
     * @param blockLocationInsideChunk the start location (e.g. the location of the updated block)
     */
    public void addLiquid(Chunk chunk, Vec3i blockLocationInsideChunk) {
        if (log.isDebugEnabled()) {
            log.debug("Adding liquid at ({}, {}, {}) in chunk {}", blockLocationInsideChunk.x, blockLocationInsideChunk.y, blockLocationInsideChunk.z, this);
        }

        addLiquid(chunk, blockLocationInsideChunk, LEVEL_MAX - 1);
        liquidBfsQueue.offer(new LiquidNode(chunk, blockLocationInsideChunk.x, blockLocationInsideChunk.y, blockLocationInsideChunk.z, LEVEL_MAX - 1));
    }

    public void flowLiquid(Vector3f location) {
        if (log.isDebugEnabled()) {
            log.debug("Flowing liquid starting at ({}, {}, {}) in chunk {}", location.x, location.y, location.z, this);
        }

        Set<Vector3f> neighborBlockLocations = new HashSet<>();
        for (float x = location.x - 1; x <= location.x + 1; x++) {
            for (float y = location.y - 1; y <= location.y + 1; y++) {
                for (float z = location.z - 1; z <= location.z + 1; z++) {
                    neighborBlockLocations.add(new Vector3f(x, y, z));
                }
            }
        }

        neighborBlockLocations.forEach(loc ->
                chunkManager.getChunk(ChunkManager.getChunkLocation(loc)).ifPresent(chunk -> {
                    Vec3i blockLocationInsideChunk = chunk.toLocalLocation(toVec3i(getScaledBlockLocation(loc)));
                    Block block = chunk.getBlock(blockLocationInsideChunk);
                    if (block != null && TypeIds.WATER.equals(block.getType())) {
                        liquidBfsQueue.offer(new LiquidNode(chunk, blockLocationInsideChunk.x, blockLocationInsideChunk.y, blockLocationInsideChunk.z, getLiquidLevel(block)));
                    }
                }));
    }

    public Set<Vec3i> step() {
        LiquidNode node = liquidBfsQueue.poll();
        if (node == null) {
            return Collections.emptySet();
        }

        log.debug("Updating liquid");
        LiquidRunningContext context = new LiquidRunningContext();
        if (log.isDebugEnabled()) {
            log.debug("Processing liquid node({}, {}, {})", node.x, node.y, node.z);
        }

        if (!propagateLiquid(node.chunk, node.x, node.y - 1, node.z, node.level, false, context)) {
            // If the liquid can't propagate downwards, try horizontally
            propagateLiquid(node.chunk, node.x - 1, node.y, node.z, node.level, true, context);
            propagateLiquid(node.chunk, node.x + 1, node.y, node.z, node.level, true, context);
            propagateLiquid(node.chunk, node.x, node.y, node.z - 1, node.level, true, context);
            propagateLiquid(node.chunk, node.x, node.y, node.z + 1, node.level, true, context);
        }

        return context.chunkMeshUpdateRequests;
    }

    /**
     * Gets the liquid level for the given block
     * @param block the block
     * @return 0 for null or non water block, 1 - 5 for water blocks
     */
    public static int getLiquidLevel(Block block) {
        if (block == null || !TypeIds.WATER.equals(block.getType())) {
            // Level is 0 if no block or if non water block
            return 0;
        }
        Liquid shape = (Liquid) BlocksConfig.getInstance().getShapeRegistry().get(block.getShape());
        return shape.getLevel();
    }

    private boolean propagateLiquid(Chunk c, int x, int y, int z, int liquidLevel, boolean dims, LiquidRunningContext context) {
        Chunk chunk = c;

        if (isOutsideChunk(x, y, z)) {
            if (c.getChunkResolver() != null) {
                Vec3i location = new Vec3i(x, y, z);
                Vec3i chunkLocation = calculateNeighbourChunkLocation(c, location);
                chunk = c.getChunkResolver().get(chunkLocation).orElse(null);
                Vec3i neighbourBlockLocation = calculateNeighbourChunkBlockLocation(location);
                x = neighbourBlockLocation.x;
                y = neighbourBlockLocation.y;
                z = neighbourBlockLocation.z;
            }
        }

        Block block = chunk.getBlock(x, y, z);
        if (block != null && !TypeIds.WATER.equals(block.getType())) {
            if (log.isDebugEnabled()) {
                log.debug("PAW2 - Liquid blocked at ({}, {}, {})", x, y, z);
            }
            return false;
        }

        if (!dims) {
            if (log.isDebugEnabled()) {
                log.debug("PAS3 - Flowing vertically in ({}, {}, {}) to {}. LL={}", x, y, z, LEVEL_MAX, liquidLevel);
            }

            addLiquid(chunk, new Vec3i(x, y, z), LEVEL_MAX);
            updateChunkMeshUpdateRequests(chunk, x, y, z, context);
            liquidBfsQueue.offer(new LiquidNode(chunk, x, y, z, LEVEL_MAX));
            return true;
        }

        int previousLiquidLevel = getLiquidLevel(chunk.getBlock(x, y, z));
        if (previousLiquidLevel + 2 <= liquidLevel) {
            if (log.isDebugEnabled()) {
                log.debug("PAS4 - Flowing horizontally in ({}, {}, {}) to {}. PL={} LL={}", x, y, z, liquidLevel - 1, previousLiquidLevel, liquidLevel);
            }

            addLiquid(chunk, new Vec3i(x, y, z), liquidLevel - 1);
            updateChunkMeshUpdateRequests(chunk, x, y, z, context);
            liquidBfsQueue.offer(new LiquidNode(chunk, x, y, z, liquidLevel - 1));

        } else {
            if (log.isDebugEnabled()) {
                log.debug("PAS5 - Stop flowing in ({}, {}, {}). PL={} LL={}", x, y, z, previousLiquidLevel, liquidLevel);
            }
        }
        return false;
    }

    /**
     * Updates the set of chunks to be updated due to the water propagation.
     * This method adds the current chunks and the neighbour chunks if the water block
     * is adjacent to them.
     * @param chunk the chunk where the water is updated
     * @param x the x location of the water
     * @param y the y location of the water
     * @param z the z location of the water
     * @param context the processing context
     */
    private void updateChunkMeshUpdateRequests(Chunk chunk, int x, int y, int z, LiquidRunningContext context) {
        context.chunkMeshUpdateRequests.add(chunk.getLocation());

        Vec3i size = BlocksConfig.getInstance().getChunkSize();

        // Request chunk updates of neighbour blocks only if block is at the border of the chunk
        if (x == size.x - 1) {
            context.chunkMeshUpdateRequests.add(chunk.getLocation().add(1, 0, 0));
        }
        if (x == 0) {
            context.chunkMeshUpdateRequests.add(chunk.getLocation().add(-1, 0, 0));
        }
        if (y == size.y - 1) {
            context.chunkMeshUpdateRequests.add(chunk.getLocation().add(0, 1, 0));
        }
        if (y == 0) {
            context.chunkMeshUpdateRequests.add(chunk.getLocation().add(0, -1, 0));
        }
        if (z == size.z - 1) {
            context.chunkMeshUpdateRequests.add(chunk.getLocation().add(0, 0, 1));
        }
        if (z == 0) {
            context.chunkMeshUpdateRequests.add(chunk.getLocation().add(0, 0, -1));
        }

    }

    private void addLiquid(Chunk chunk, Vec3i relativeBlockLocation, int level) {
        String shapeId = ShapeIds.LIQUID;

        if (level <= 0) {
            // No more water
            level = 0;
        }

        if (level < LEVEL_MAX) {
            // Partial water
            shapeId = shapeId + "_" + level;
        }

        chunk.addBlock(relativeBlockLocation,
                BlocksConfig.getInstance().getBlockRegistry()
                        .get(BlockIds.getName(TypeIds.WATER, shapeId)));
    }

    /**
     * Checks if the given block coordinate is inside the chunk.
     *
     * @param x coordinate of the block in this chunk
     * @param y coordinate of the block in this chunk
     * @param z coordinate of the block in this chunk
     * @return true if the coordinate of the block is inside the chunk, false otherwise.
     */
    private static boolean isOutsideChunk(int x, int y, int z) {
        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();
        return x < 0 || x >= chunkSize.x || y < 0 || y >= chunkSize.y || z < 0 || z >= chunkSize.z;
    }

    private static Vec3i calculateNeighbourChunkLocation(Chunk chunk, Vec3i blockLocation) {
        Vec3i chunkLocation = new Vec3i(chunk.getLocation());
        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();

        if (blockLocation.x < 0) {
            chunkLocation.addLocal(-1, 0, 0);
        }
        if (blockLocation.x >= chunkSize.x) {
            chunkLocation.addLocal(1, 0, 0);
        }
        if (blockLocation.y < 0) {
            chunkLocation.addLocal(0, -1, 0);
        }
        if (blockLocation.y >= chunkSize.y) {
            chunkLocation.addLocal(0, 1, 0);
        }
        if (blockLocation.z < 0) {
            chunkLocation.addLocal(0, 0, -1);
        }
        if (blockLocation.z >= chunkSize.z) {
            chunkLocation.addLocal(0, 0, 1);
        }
        return chunkLocation;
    }

    private static Vec3i calculateNeighbourChunkBlockLocation(Vec3i blockLocation) {
        Vec3i toReturn = new Vec3i(blockLocation);
        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();
        if (blockLocation.x < 0) {
            toReturn.x = chunkSize.x - 1;
        }
        if (blockLocation.x >= chunkSize.x) {
            toReturn.x = 0;
        }
        if (blockLocation.y < 0) {
            toReturn.y = chunkSize.y - 1;
        }
        if (blockLocation.y >= chunkSize.y) {
            toReturn.y = 0;
        }
        if (blockLocation.z < 0) {
            toReturn.z = chunkSize.z - 1;
        }
        if (blockLocation.z >= chunkSize.z) {
            toReturn.z = 0;
        }
        return toReturn;
    }

    private static Vec3i toVec3i(Vector3f location) {
        return new Vec3i((int) Math.floor(location.x), (int) Math.floor(location.y), (int) Math.floor(location.z));
    }

    private static Vector3f getScaledBlockLocation(Vector3f location) {
        return location.mult(1f / BlocksConfig.getInstance().getBlockScale());
    }

    @AllArgsConstructor
    private static class LiquidNode {
        Chunk chunk;
        int x;
        int y;
        int z;
        int level;
    }

    private static class LiquidRunningContext {
        final Set<Vec3i> chunkMeshUpdateRequests = new HashSet<>();
    }

}
