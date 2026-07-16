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

package org.delaunois.ialon.blocks;

import com.jme3.math.Vector3f;
import com.simsilica.mathd.Vec3i;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static org.delaunois.ialon.blocks.shapes.Liquid.LEVEL_MAX;

/**
 * Basic simulation of fluid
 *
 * @author Cedric de Launois
 */
@Slf4j
public class ChunkLiquidManager {

    private final WorldSettings config;
    private final ChunkManager chunkManager;
    // Used to clear a fire block's torchlight when water floods (extinguishes) it. A private instance
    // is fine : the light state lives in the chunks, and ChunkLightManager keeps no cross-call state.
    private final ChunkLightManager chunkLightManager;
    // Flow queues are split per liquid type so the simulation can be paced independently (lava flows
    // slower than water — see ChunkLiquidManagerState). The removal (un-flow) queue is shared : it is
    // level-based, type-agnostic, and driven by the water cadence.
    private final Queue<LiquidNode> liquidBfsQueue = new LinkedList<>();
    private final Queue<LiquidNode> lavaBfsQueue = new LinkedList<>();
    private final Queue<LiquidNode> liquidRemovalBfsQueue = new LinkedList<>();

    /** The flow queue feeding the given liquid type (lava has its own, slower-paced queue). */
    private Queue<LiquidNode> flowQueue(String liquidType) {
        return TypeIds.LAVA.equals(liquidType) ? lavaBfsQueue : liquidBfsQueue;
    }

    /**
     * The liquid TYPE a liquid-bearing block belongs to : lava only when the block's own type is lava
     * (lava occupies pure cells), otherwise water. A structural block logged with water keeps its own
     * type (e.g. {@code birch_log}) but its liquid is water — so we must derive the liquid type here
     * rather than read {@code block.getType()} directly.
     */
    private static String liquidTypeOf(Block block) {
        return block != null && TypeIds.LAVA.equals(block.getType()) ? TypeIds.LAVA : TypeIds.WATER;
    }

    public ChunkLiquidManager(WorldSettings config) {
        this.chunkManager = config.getChunkManager();
        this.chunkLightManager = new ChunkLightManager(config);
        this.config = config;
    }

    public int queueSize() {
        return liquidRemovalBfsQueue.isEmpty() ? liquidBfsQueue.size() : liquidRemovalBfsQueue.size();
    }

    public int lavaQueueSize() {
        return lavaBfsQueue.size();
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

        // The source block was just placed by the caller : route the flow to the queue of its type.
        Block source = chunk.getBlock(blockLocationInsideChunk);
        flowQueue(liquidTypeOf(source)).offer(new LiquidNode(chunk, blockLocationInsideChunk.x, blockLocationInsideChunk.y, blockLocationInsideChunk.z, LEVEL_MAX - 1));
    }

    public void addSource(Vector3f location) {
        chunkManager.getChunk(ChunkManager.getChunkLocation(location)).ifPresent(chunk -> {
            Vec3i blockLocationInsideChunk = chunk.toLocalLocation(toVec3i(getScaledBlockLocation(location)));
            addSource(chunk, blockLocationInsideChunk);
        });
    }

    /**
     * Starts a liquid <b>removal</b> (un-flow) cascade from the given cell at the given level : the
     * simulation recedes the liquid originating here. Used both when a real liquid source is removed
     * and when a placed/changed block displaces or cuts off liquid (the cascade is level-based, not
     * source-specific — hence the name).
     * @param chunk the chunk where the cell is located
     * @param blockLocationInsideChunk the cell to recede from
     * @param liquidLevel the level to recede (e.g. the level the cell was carrying)
     */
    public void unflow(Chunk chunk, Vec3i blockLocationInsideChunk, int liquidLevel) {
        if (log.isDebugEnabled()) {
            log.debug("Unflowing liquid at ({}, {}, {}) in chunk {}", blockLocationInsideChunk.x, blockLocationInsideChunk.y, blockLocationInsideChunk.z, this);
        }

        liquidRemovalBfsQueue.offer(new LiquidNode(chunk, blockLocationInsideChunk.x, blockLocationInsideChunk.y, blockLocationInsideChunk.z, liquidLevel));
    }

    public void removeSource(Vector3f location) {
        chunkManager.getChunk(ChunkManager.getChunkLocation(location)).ifPresent(chunk -> {
            Vec3i blockLocationInsideChunk = chunk.toLocalLocation(toVec3i(getScaledBlockLocation(location)));
            Block block = chunk.getBlock(blockLocationInsideChunk);
            if (block != null) {
                unflow(chunk, blockLocationInsideChunk, LEVEL_MAX);
            }
        });
    }

    /**
     * Starts the liquid flow simulation for blocks surrounding the given location.
     * Used typically when a block is removed.
     * @param location the location
     */
    public void flowLiquid(Vector3f location) {
        if (log.isDebugEnabled()) {
            log.debug("Flowing liquid starting at ({}, {}, {}) in chunk {}", location.x, location.y, location.z, this);
        }

        Set<Vector3f> neighborBlockLocations = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    neighborBlockLocations.add(new Vector3f(location.x + dx, location.y + dy, location.z + dz));
                }
            }
        }

        neighborBlockLocations.forEach(loc ->
                chunkManager.getChunk(ChunkManager.getChunkLocation(loc)).ifPresent(chunk -> {
                    Vec3i blockLocationInsideChunk = chunk.toLocalLocation(toVec3i(getScaledBlockLocation(loc)));
                    Block block = chunk.getBlock(blockLocationInsideChunk);
                    if (block != null && block.getLiquidLevel() > 0) {
                        flowQueue(liquidTypeOf(block)).offer(new LiquidNode(chunk, blockLocationInsideChunk.x, blockLocationInsideChunk.y, blockLocationInsideChunk.z, getLiquidLevel(block)));
                    }
                }));
    }

    public Set<Vec3i> step() {
        LiquidRunningContext context = new LiquidRunningContext();
        if (config.getSimulateLiquidFlowModel() == 1) {
            if (!stepUnFlow(context))
                stepFlow(liquidBfsQueue, context);
        } else {
            stepUnFlow(context);
            stepFlow(liquidBfsQueue, context);
        }
        return context.chunkMeshUpdateRequests;
    }

    /**
     * Advances the lava flow by one node. Lava has its own queue so it can be paced slower than water
     * (see ChunkLiquidManagerState). Recession (un-flow) is handled by the shared {@link #step()}.
     */
    public Set<Vec3i> stepLava() {
        LiquidRunningContext context = new LiquidRunningContext();
        stepFlow(lavaBfsQueue, context);
        return context.chunkMeshUpdateRequests;
    }

    private boolean stepUnFlow(LiquidRunningContext context) {
        LiquidNode node = liquidRemovalBfsQueue.poll();
        if (node == null) {
            return false;
        }

        log.debug("stepUnFlow: Processing liquid node({}, {}, {})", node.x, node.y, node.z);

        if (!propagateRemovedLiquid(node.chunk, node.x, node.y - 1, node.z, node.level, false, context)) {
            propagateRemovedLiquid(node.chunk, node.x - 1, node.y, node.z, node.level, true, context);
            propagateRemovedLiquid(node.chunk, node.x + 1, node.y, node.z, node.level, true, context);
            propagateRemovedLiquid(node.chunk, node.x, node.y, node.z - 1, node.level, true, context);
            propagateRemovedLiquid(node.chunk, node.x, node.y, node.z + 1, node.level, true, context);
        }
        return true;
    }

    private void stepFlow(Queue<LiquidNode> queue, LiquidRunningContext context) {
        LiquidNode node = queue.poll();
        if (node == null) {
            return;
        }

        log.debug("stepFlow: Processing liquid node({}, {}, {})", node.x, node.y, node.z);

        int liquidLevel = getLiquidLevel(node.chunk, node.x, node.y, node.z);
        if (liquidLevel <= 0) {
            return;
        }

        // The liquid type currently flowing : drives empty-cell creation and the incompatibility rule.
        // Derived as the LIQUID type (water/lava), not the raw block type — a structure logged with
        // water keeps its own type but flows water.
        String flowingType = liquidTypeOf(node.chunk.getBlock(node.x, node.y, node.z));

        if (!propagateLiquid(node, Direction.DOWN, liquidLevel, false, flowingType, context)) {
            // If the liquid can't propagate downwards, try horizontally
            propagateLiquid(node, Direction.WEST, liquidLevel, true, flowingType, context);
            propagateLiquid(node, Direction.EAST, liquidLevel, true, flowingType, context);
            propagateLiquid(node, Direction.NORTH, liquidLevel, true, flowingType, context);
            propagateLiquid(node, Direction.SOUTH, liquidLevel, true, flowingType, context);
        }
    }

    public int getLiquidLevel(Chunk c, int x, int y, int z) {
        Chunk chunk = c;

        if (isOutsideChunk(x, y, z) && c.getChunkResolver() != null) {
            Vec3i location = new Vec3i(x, y, z);
            Vec3i chunkLocation = calculateNeighbourChunkLocation(c, location);
            chunk = c.getChunkResolver().get(chunkLocation).orElse(null);
            Vec3i neighbourBlockLocation = calculateNeighbourChunkBlockLocation(location);
            x = neighbourBlockLocation.x;
            y = neighbourBlockLocation.y;
            z = neighbourBlockLocation.z;
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

    public void updateChunkMesh(Collection<Vec3i> locations) {
        chunkManager.requestOrderedMeshChunks(locations);
    }

    private boolean propagateRemovedLiquid(Chunk c, int x, int y, int z, int liquidLevel, boolean dims, LiquidRunningContext context) {
        Chunk chunk = c;

        if (isOutsideChunk(x, y, z) && c.getChunkResolver() != null) {
            Vec3i location = new Vec3i(x, y, z);
            Vec3i chunkLocation = calculateNeighbourChunkLocation(c, location);
            chunk = c.getChunkResolver().get(chunkLocation).orElse(null);
            Vec3i neighbourBlockLocation = calculateNeighbourChunkBlockLocation(location);
            x = neighbourBlockLocation.x;
            y = neighbourBlockLocation.y;
            z = neighbourBlockLocation.z;
        }

        if (chunk == null) {
            log.debug("PRL1 - Chunk is null for liquid ({}, {}, {})", x, y, z);
            return false;
        }


        Block block = chunk.getBlock(x, y, z);
        int previousLiquidLevel = getLiquidLevel(block);
        updateChunkMeshUpdateRequests(chunk, x, y, z, context);

        if (block != null && block.isLiquidSource()) {
            return false;
        }

        if ((!dims && previousLiquidLevel == LEVEL_MAX) || previousLiquidLevel > 0 && previousLiquidLevel < liquidLevel) {
            log.debug("PRL2 - Setting liquid ({}, {}, {}) to {}. PL={} LL={} D={}", x, y, z, 0, previousLiquidLevel, liquidLevel, dims);
            setLiquid(block, chunk, new Vec3i(x, y, z), 0, block == null ? null : liquidTypeOf(block));
            liquidRemovalBfsQueue.offer(new LiquidNode(chunk, x, y, z, previousLiquidLevel));
            return true;

        } else if (previousLiquidLevel >= liquidLevel) {
            log.debug("PRL3 - Enqueuing flowing in ({}, {}, {}). PL={} LL={} D={}", x, y, z, previousLiquidLevel, liquidLevel, dims);
            // Add it to the update queue, so it can propagate to fill in the gaps
            // left behind by this removal. We should update the lightBfsQueue after
            // the lightRemovalBfsQueue is empty.
            flowQueue(liquidTypeOf(block)).offer(new LiquidNode(chunk, x, y, z, previousLiquidLevel));

        } else {
            log.debug("PRL3 - Stop unflowing in ({}, {}, {}). PL={} LL={}", x, y, z, previousLiquidLevel, liquidLevel);
        }
        return false;
    }

    private boolean propagateLiquid(LiquidNode node, Direction direction, int liquidLevel, boolean dims, String flowingType, LiquidRunningContext context) {
        Vec3i dir = direction.getVector();
        Chunk chunk = node.chunk;
        int x = node.x + dir.x;
        int y = node.y + dir.y;
        int z = node.z + dir.z;

        if (BlocksConfig
                .getInstance()
                .getShapeRegistry()
                .get(chunk.getBlock(node.x, node.y, node.z).getShape())
                .fullyCoversFace(direction)) {
            // Block does not allow liquid to flow
            log.debug("PL0 - Liquid blocked by face at ({}, {}, {})", x, y, z);
            return false;
        }

        if (isOutsideChunk(x, y, z) && node.chunk.getChunkResolver() != null) {
            Vec3i location = new Vec3i(x, y, z);
            Vec3i chunkLocation = calculateNeighbourChunkLocation(node.chunk, location);
            chunk = node.chunk.getChunkResolver().get(chunkLocation).orElse(null);
            Vec3i neighbourBlockLocation = calculateNeighbourChunkBlockLocation(location);
            x = neighbourBlockLocation.x;
            y = neighbourBlockLocation.y;
            z = neighbourBlockLocation.z;
        }

        if (chunk == null) {
            log.debug("PL1 - Chunk is null for liquid ({}, {}, {})", x, y, z);
            return false;
        }

        Block block = chunk.getBlock(x, y, z);
        // Whether the flowing lava is about to destroy a non-solid item (grass, flower, ...) it flows
        // into. Decided in the type-check below, acted on at flow-commit (so the item is only removed
        // when the lava actually spreads into the cell, not when it merely reaches it).
        boolean lavaConsumesItem = false;
        if (block != null) {
            if (block.getLiquidLevel() == Block.LIQUID_DISABLED) {
                // Block does not allow liquid to flow
                log.debug("PL2.1 - Liquid blocked at ({}, {}, {})", x, y, z);
                return false;
            }

            if (BlocksConfig
                        .getInstance()
                        .getShapeRegistry()
                        .get(block.getShape())
                        .fullyCoversFace(direction.opposite())) {
                // Block does not allow liquid to flow
                log.debug("PL2.2 - Liquid blocked by face at ({}, {}, {})", x, y, z);
                return false;
            }

            boolean targetIsLiquid = block.getLiquidLevel() > 0;
            String targetLiquidType = liquidTypeOf(block);
            if (TypeIds.LAVA.equals(flowingType)) {
                // Lava only occupies pure lava cells. It flows into empty cells (block == null, handled
                // below) or cells already holding lava. Anything else either burns away or blocks it :
                //  - a non-solid, non-liquid item (grass, seaweed, mushroom, flower, fire, ...) is
                //    destroyed and the lava flows into the freed cell ;
                //  - a solid block (no co-habitation, unlike water) or any other liquid (water — no
                //    mixing) stops the lava.
                if (!(targetIsLiquid && TypeIds.LAVA.equals(targetLiquidType))) {
                    if (targetIsLiquid) {
                        // Lava reaching water : the WATER cell solidifies into gravel_dark
                        // (Minecraft-style lava + water → stone), the lava stays liquid — no mixing.
                        log.debug("PL2.3 - Lava meets water at ({}, {}, {}), solidifying water", x, y, z);
                        solidifyToGravel(chunk, x, y, z, Math.max(liquidLevel, getLiquidLevel(block)), context);
                        return false;
                    } else if (!block.isSolid()) {
                        lavaConsumesItem = true;
                    } else {
                        log.debug("PL2.3b - Lava stops against {} at ({}, {}, {})", block.getType(), x, y, z);
                        return false;
                    }
                }
            } else if (targetIsLiquid && !flowingType.equals(targetLiquidType)) {
                // Water meeting a different liquid (lava) : the flowing WATER cell solidifies into
                // gravel_dark (Minecraft-style lava + water → stone), the lava stays liquid — no mixing.
                // (A structural block logged with water has targetLiquidType == water, so water still
                // flows through it — co-habitation is preserved.)
                log.debug("PL2.4 - Water meets lava at ({}, {}, {}), solidifying water", node.x, node.y, node.z);
                solidifyToGravel(node.chunk, node.x, node.y, node.z, Math.max(liquidLevel, getLiquidLevel(block)), context);
                return false;
            }
        }

        updateChunkMeshUpdateRequests(chunk, x, y, z, context);

        if (!dims) {
            log.debug("PL3 - Flowing vertically in ({}, {}, {}) to {}", x, y, z, liquidLevel);
            block = clearObstructingBlock(block, chunk, x, y, z, lavaConsumesItem, context);
            setLiquid(block, chunk, new Vec3i(x, y, z), LEVEL_MAX, flowingType);
            flowQueue(flowingType).offer(new LiquidNode(chunk, x, y, z, LEVEL_MAX));
            return true;
        }

        int previousLiquidLevel = getLiquidLevel(chunk.getBlock(x, y, z));
        if (previousLiquidLevel < liquidLevel - 1) {
            if (log.isDebugEnabled()) {
                log.debug("PL4 - Flowing horizontally in ({}, {}, {}) to {}. PL={} LL={}", x, y, z, liquidLevel - 1, previousLiquidLevel, liquidLevel);
            }

            block = clearObstructingBlock(block, chunk, x, y, z, lavaConsumesItem, context);
            setLiquid(block, chunk, new Vec3i(x, y, z), liquidLevel - 1, flowingType);
            flowQueue(flowingType).offer(new LiquidNode(chunk, x, y, z, liquidLevel - 1));

        } else {
            log.debug("PL5 - Stop flowing in ({}, {}, {}). PL={} LL={}", x, y, z, previousLiquidLevel, liquidLevel);
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
     * Clears a block standing in the way of a liquid that is about to flow into its cell, so the freed
     * cell can be filled by the caller (setLiquid with a null existing block). Two cases are removed :
     *  - a fire block, for any flowing liquid : water (and lava) extinguish flames ;
     *  - any non-solid item, when {@code lavaConsumesItem} is set : lava burns away grass, seaweed,
     *    mushrooms, flowers, ... it flows into.
     * Torchlight is cleared first for light-emitting blocks (e.g. fire), otherwise the propagated light
     * would linger after the block is gone. Any block that should stay is returned unchanged.
     *
     * @return the (possibly removed) block : {@code null} when the block was cleared, else the input
     */
    private Block clearObstructingBlock(Block block, Chunk chunk, int x, int y, int z, boolean lavaConsumesItem, LiquidRunningContext context) {
        if (block == null || (!lavaConsumesItem && !TypeIds.FIRE.equals(block.getType()))) {
            return block;
        }
        Vec3i location = new Vec3i(x, y, z);
        if (block.isTorchlight()) {
            context.chunkMeshUpdateRequests.addAll(chunkLightManager.removeTorchlight(location, chunk));
        }
        chunk.removeBlock(location);
        return null;
    }

    /**
     * Replaces the water cell at the given location with a gravel_dark block, used when water and lava
     * meet : the WATER block solidifies (Minecraft-style lava + water → stone) while the lava stays
     * liquid. Solidifying the water rather than the lava avoids a thin lava stream (a low-level, non-cube
     * liquid shape) suddenly turning into a full gravel cube. The gravel shape is picked to approximate
     * the height of the <b>tallest</b> of the two meeting liquids (see {@link #gravelShapeForLevel(int)}) :
     * the crust fills the space both liquids occupied, so a deep flow does not leave a suspiciously thin
     * plate. The relevant mesh update requests (this cell and its chunk neighbours) are recorded in the
     * context.
     *
     * <p>A liquid <b>source</b> is never solidified : sources are permanent, so a water source and a
     * lava source that meet simply block each other at the boundary (no gravel crust), and only the
     * <em>flowing</em> water between them freezes. Solidifying a source would destroy it and orphan the
     * flow it feeds (that water would float, never receding), so this method leaves sources untouched.
     *
     * @param chunk the chunk holding the water cell
     * @param x the x location of the water inside the chunk
     * @param y the y location of the water inside the chunk
     * @param z the z location of the water inside the chunk
     * @param level the frozen liquid level to approximate : {@code max(waterLevel, lavaLevel)}
     */
    private void solidifyToGravel(Chunk chunk, int x, int y, int z, int level, LiquidRunningContext context) {
        Block existing = chunk.getBlock(x, y, z);
        if (existing != null && existing.isLiquidSource()) {
            // Never solidify a source : it stays and simply blocks the other liquid at the boundary.
            log.debug("Not solidifying liquid source at ({}, {}, {})", x, y, z);
            return;
        }
        String shape = gravelShapeForLevel(level);
        Block gravel = BlocksConfig.getInstance().getBlockRegistry()
                .get(BlockIds.getName(BlockIds.GRAVEL_DARK, shape, 0));
        if (gravel == null) {
            // Defensive fallback : the plain cube is always registered.
            gravel = BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.GRAVEL_DARK);
        }
        chunk.addBlock(new Vec3i(x, y, z), gravel);
        updateChunkMeshUpdateRequests(chunk, x, y, z, context);
    }

    /**
     * Maps a frozen water level to the gravel_dark shape whose height best approximates it, so a
     * shallow water sheet solidifies into a thin plate rather than a full cube. The thresholds follow
     * the liquid heights ({@code Liquid.HEIGHTS}) versus the shape heights (plate 0.1, slab 1/3,
     * double-slab 2/3, cube 1) by nearest height :
     * <pre>
     *   level 1-2 (h ≤ 0.2)  → plate_up
     *   level 3   (h = 0.4)  → slab_up
     *   level 4-5 (h ≤ 0.8)  → double_slab_up
     *   level 6-7 (h = 1.0)  → cube_up  (a full source freezes into a full cube)
     * </pre>
     */
    private static String gravelShapeForLevel(int level) {
        if (level <= 2) {
            return ShapeIds.PLATE;
        } else if (level == 3) {
            return ShapeIds.SLAB;
        } else if (level <= 5) {
            return ShapeIds.DOUBLE_SLAB;
        }
        return ShapeIds.CUBE;
    }

    /**
     * Sets the liquid level to the block at the given location
     * @param existingBlock the existing block at this location or null
     * @param chunk the chunk
     * @param relativeBlockLocation the location of the block inside the chunk
     * @param level the liquid level
     */
    private void setLiquid(Block existingBlock, Chunk chunk, Vec3i relativeBlockLocation, int level, String flowingType) {
        if (level <= 0) {
            // No more liquid
            level = 0;
        }

        // Empty cell, or a cell already holding the SAME liquid type : (re)create / clear it with the
        // flowing type. (flowingType may be null on a level-0 removal of an empty cell.)
        if (existingBlock == null || (flowingType != null && flowingType.equals(existingBlock.getType()))) {
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
                                .get(BlockIds.getName(flowingType, shapeId)));
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
