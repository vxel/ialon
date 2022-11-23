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

import com.jme3.math.Vector3f;
import com.rvandoosselaer.blocks.Block;
import com.rvandoosselaer.blocks.BlockIds;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.rvandoosselaer.blocks.Chunk;
import com.rvandoosselaer.blocks.ShapeIds;
import com.rvandoosselaer.blocks.TypeIds;
import com.simsilica.mathd.Vec3i;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import static com.rvandoosselaer.blocks.shapes.Liquid.LEVEL_MAX;
import static org.delaunois.ialon.Config.SIMULATE_LIQUID_FLOW_MODEL;

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
    private final Queue<LiquidNode> liquidRemovalBfsQueue = new LinkedList<>();

    public int queueSize() {
        return liquidRemovalBfsQueue.size() > 0 ? liquidRemovalBfsQueue.size() : liquidBfsQueue.size();
    }

    /**
     * Add a liquid source at the given location. This will trigger the liquid simulation that
     * will flow the liquid where possible.
     * @param chunk the chunk where the source is located
     * @param blockLocationInsideChunk the location of the source
     */
    public void addSource(Chunk chunk, Vec3i blockLocationInsideChunk) {
        if (log.isDebugEnabled()) {
            log.debug("Adding liquid source at ({}, {}, {}) in chunk {}", blockLocationInsideChunk.x, blockLocationInsideChunk.y, blockLocationInsideChunk.z, this);
        }

        liquidBfsQueue.offer(new LiquidNode(chunk, blockLocationInsideChunk.x, blockLocationInsideChunk.y, blockLocationInsideChunk.z, LEVEL_MAX - 1));
    }

    public void addSource(Vector3f location) {
        chunkManager.getChunk(ChunkManager.getChunkLocation(location)).ifPresent(chunk -> {
            Vec3i blockLocationInsideChunk = chunk.toLocalLocation(toVec3i(getScaledBlockLocation(location)));
            addSource(chunk, blockLocationInsideChunk);
        });
    }

    /**
     * Remove the liquid source at the given location. This will trigger the liquid simulation
     * that will remove the liquid originating from this liquid source.
     * @param chunk the chunk where the source was located
     * @param blockLocationInsideChunk the location of the source
     */
    public void removeSource(Chunk chunk, Vec3i blockLocationInsideChunk, int liquidLevel) {
        if (log.isDebugEnabled()) {
            log.debug("Removing liquid source at ({}, {}, {}) in chunk {}", blockLocationInsideChunk.x, blockLocationInsideChunk.y, blockLocationInsideChunk.z, this);
        }

        liquidRemovalBfsQueue.offer(new LiquidNode(chunk, blockLocationInsideChunk.x, blockLocationInsideChunk.y, blockLocationInsideChunk.z, liquidLevel));
    }

    public void removeSource(Vector3f location) {
        chunkManager.getChunk(ChunkManager.getChunkLocation(location)).ifPresent(chunk -> {
            Vec3i blockLocationInsideChunk = chunk.toLocalLocation(toVec3i(getScaledBlockLocation(location)));
            Block block = chunk.getBlock(blockLocationInsideChunk);
            if (block != null) {
                removeSource(chunk, blockLocationInsideChunk, LEVEL_MAX);
            }
        });
    }

    /**
     * Starts the liquid flow simulation for blocks surrouding the given location.
     * Used typically when a block is removed.
     * @param location the location
     */
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
                    if (block != null && block.getLiquidLevel() > 0) {
                        liquidBfsQueue.offer(new LiquidNode(chunk, blockLocationInsideChunk.x, blockLocationInsideChunk.y, blockLocationInsideChunk.z, getLiquidLevel(block)));
                    }
                }));
    }

    public Set<Vec3i> step() {
        LiquidRunningContext context = new LiquidRunningContext();
        if (SIMULATE_LIQUID_FLOW_MODEL == 1) {
            if (!stepUnFlow(context))
                stepFlow(context);
        } else {
            stepUnFlow(context);
            stepFlow(context);
        }
        return context.chunkMeshUpdateRequests;
    }

    private boolean stepUnFlow(LiquidRunningContext context) {
        if (!liquidRemovalBfsQueue.isEmpty()) {
            log.debug("Unflowing liquid");

            LiquidNode node = liquidRemovalBfsQueue.poll();
            if (log.isDebugEnabled()) {
                log.debug("Processing liquid node({}, {}, {})", node.x, node.y, node.z);
            }

            if (!propagateRemovedLiquid(node.chunk, node.x, node.y - 1, node.z, node.level, false, context)) {
                propagateRemovedLiquid(node.chunk, node.x - 1, node.y, node.z, node.level, true, context);
                propagateRemovedLiquid(node.chunk, node.x + 1, node.y, node.z, node.level, true, context);
                propagateRemovedLiquid(node.chunk, node.x, node.y, node.z - 1, node.level, true, context);
                propagateRemovedLiquid(node.chunk, node.x, node.y, node.z + 1, node.level, true, context);
            }
            return true;
        }
        return false;
    }

    private void stepFlow(LiquidRunningContext context) {
        if (!liquidBfsQueue.isEmpty()) {
            log.debug("Flowing liquid");

            LiquidNode node = liquidBfsQueue.poll();
            if (log.isDebugEnabled()) {
                log.debug("Processing liquid node({}, {}, {})", node.x, node.y, node.z);
            }
            int liquidLevel = getLiquidLevel(node.chunk, node.x, node.y, node.z);
            if (liquidLevel > 0 && !propagateLiquid(node.chunk, node.x, node.y - 1, node.z, liquidLevel, false, context)) {
                // If the liquid can't propagate downwards, try horizontally
                propagateLiquid(node.chunk, node.x - 1, node.y, node.z, liquidLevel, true, context);
                propagateLiquid(node.chunk, node.x + 1, node.y, node.z, liquidLevel, true, context);
                propagateLiquid(node.chunk, node.x, node.y, node.z - 1, liquidLevel, true, context);
                propagateLiquid(node.chunk, node.x, node.y, node.z + 1, liquidLevel, true, context);
            }
        }
    }

    public int getLiquidLevel(Chunk c, int x, int y, int z) {
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

        if (chunk == null) {
            return 0;
        }

        Block block = chunk.getBlock(x, y, z);
        return block == null ? 0 : block.getLiquidLevel();
    }

    /**
     * Gets the liquid level for the given block
     * @param block the block
     * @return 0 for null or non liquid block, 1 - 6 for liquid blocks
     */
    public static int getLiquidLevel(Block block) {
        if (block == null || block.getLiquidLevel() <= 0) {
            // Level is 0 if no block or if non liquid block
            return 0;
        }
        return block.getLiquidLevel();
    }

    private boolean propagateRemovedLiquid(Chunk c, int x, int y, int z, int liquidLevel, boolean dims, LiquidRunningContext context) {
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

        if (chunk == null) {
            if (log.isDebugEnabled()) {
                log.debug("PRL1 - Chunk is null for liquid ({}, {}, {})", x, y, z);
            }
            return false;
        }


        Block block = chunk.getBlock(x, y, z);
        int previousLiquidLevel = getLiquidLevel(block);
        updateChunkMeshUpdateRequests(chunk, x, y, z, context);

        if ((!dims && previousLiquidLevel == LEVEL_MAX) || previousLiquidLevel > 0 && previousLiquidLevel < liquidLevel) {
            if (log.isDebugEnabled()) {
                log.debug("PRL2 - Setting liquid ({}, {}, {}) to {}. PL={} LL={} D={}", x, y, z, 0, previousLiquidLevel, liquidLevel, dims);
            }

            setLiquid(block, chunk, new Vec3i(x, y, z), 0);
            liquidRemovalBfsQueue.offer(new LiquidNode(chunk, x, y, z, previousLiquidLevel));
            return true;

        } else if (previousLiquidLevel >= liquidLevel) {
            if (log.isDebugEnabled()) {
                log.debug("PRL3 - Enqueuing flowing in ({}, {}, {}). PL={} LL={} D={}", x, y, z, previousLiquidLevel, liquidLevel, dims);
            }
            // Add it to the update queue, so it can propagate to fill in the gaps
            // left behind by this removal. We should update the lightBfsQueue after
            // the lightRemovalBfsQueue is empty.
            liquidBfsQueue.offer(new LiquidNode(chunk, x, y, z, previousLiquidLevel));

        } else {
            if (log.isDebugEnabled()) {
                log.debug("PRL3 - Stop unflowing in ({}, {}, {}). PL={} LL={}", x, y, z, previousLiquidLevel, liquidLevel);
            }
        }
        return false;
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

        if (chunk == null) {
            if (log.isDebugEnabled()) {
                log.debug("PL1 - Chunk is null for liquid ({}, {}, {})", x, y, z);
            }
            return false;
        }

        Block block = chunk.getBlock(x, y, z);
        if (block != null && block.getLiquidLevel() == Block.LIQUID_DISABLED) {
            // Block does not allow liquid to flow
            if (log.isDebugEnabled()) {
                log.debug("PL2 - Liquid blocked at ({}, {}, {})", x, y, z);
            }
            return false;
        }

        updateChunkMeshUpdateRequests(chunk, x, y, z, context);

        if (!dims) {
            if (log.isDebugEnabled()) {
                log.debug("PL3 - Flowing vertically in ({}, {}, {}) to {}", x, y, z, liquidLevel);
            }

            setLiquid(block, chunk, new Vec3i(x, y, z), LEVEL_MAX);
            liquidBfsQueue.offer(new LiquidNode(chunk, x, y, z, LEVEL_MAX));
            return true;
        }

        int previousLiquidLevel = getLiquidLevel(chunk.getBlock(x, y, z));
        if (previousLiquidLevel < liquidLevel - 1) {
            if (log.isDebugEnabled()) {
                log.debug("PL4 - Flowing horizontally in ({}, {}, {}) to {}. PL={} LL={}", x, y, z, liquidLevel - 1, previousLiquidLevel, liquidLevel);
            }

            setLiquid(block, chunk, new Vec3i(x, y, z), liquidLevel - 1);
            liquidBfsQueue.offer(new LiquidNode(chunk, x, y, z, liquidLevel - 1));

        } else {
            if (log.isDebugEnabled()) {
                log.debug("PL5 - Stop flowing in ({}, {}, {}). PL={} LL={}", x, y, z, previousLiquidLevel, liquidLevel);
            }
        }
        return false;
    }

    /**
     * Updates the set of chunks to be updated due to the liquid propagation.
     * This method adds the current chunks and the neighbour chunks if the liquid block
     * is adjacent to them.
     * @param chunk the chunk where the liquid is updated
     * @param x the x location of the liquid
     * @param y the y location of the liquid
     * @param z the z location of the liquid
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

    /**
     * Sets the liquid level to the block at the given location
     * @param existingBlock the existing block at this location or null
     * @param chunk the chunk
     * @param relativeBlockLocation the location of the block inside the chunk
     * @param level the liquid level
     */
    private void setLiquid(Block existingBlock, Chunk chunk, Vec3i relativeBlockLocation, int level) {
        if (level <= 0) {
            // No more liquid
            level = 0;
        }

        if (existingBlock == null || TypeIds.WATER.equals(existingBlock.getType())) {
            if (level <= 0) {
                chunk.removeBlock(relativeBlockLocation);

            } else {
                String shapeId = ShapeIds.LIQUID;

                if (level < LEVEL_MAX) {
                    // Partial liquid
                    shapeId = shapeId + "_" + level;
                }

                chunk.addBlock(relativeBlockLocation,
                        BlocksConfig.getInstance().getBlockRegistry()
                                .get(BlockIds.getName(TypeIds.WATER, shapeId)));
            }

        } else if (existingBlock.getLiquidLevel() != Block.LIQUID_DISABLED) {
            Block newBlock = BlocksConfig.getInstance().getBlockRegistry()
                    .get(BlockIds.getName(existingBlock.getType(), existingBlock.getShape(), level));
            if (newBlock != null) {
                chunk.addBlock(relativeBlockLocation, newBlock);
            }
        }
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
