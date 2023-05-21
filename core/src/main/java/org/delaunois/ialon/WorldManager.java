package org.delaunois.ialon;

import com.jme3.math.Vector3f;
import com.rvandoosselaer.blocks.Block;
import com.rvandoosselaer.blocks.BlockIds;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.rvandoosselaer.blocks.Chunk;
import com.rvandoosselaer.blocks.Direction;
import com.rvandoosselaer.blocks.Shape;
import com.rvandoosselaer.blocks.ShapeIds;
import com.rvandoosselaer.blocks.TypeIds;
import com.simsilica.mathd.Vec3i;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import static com.rvandoosselaer.blocks.BlockIds.RAIL;
import static com.rvandoosselaer.blocks.BlockIds.RAIL_CURVED;
import static com.rvandoosselaer.blocks.BlockIds.WATER_SOURCE;
import static com.rvandoosselaer.blocks.TypeIds.RAIL_SLOPE;
import static com.rvandoosselaer.blocks.shapes.Liquid.LEVEL_MAX;

/**
 * Handles interactions between blocks, water and lights
 *
 * @author Cedric de Launois
 */
@Slf4j
public class WorldManager {

    @Getter
    private final ChunkManager chunkManager;

    @Getter
    private final ChunkLightManager chunkLightManager;

    @Getter
    private final ChunkLiquidManager chunkLiquidManager;

    private static final Vector3f NORTH = new Vector3f(0, 0, -1);

    public WorldManager(ChunkManager chunkManager, ChunkLightManager chunkLightManager, ChunkLiquidManager chunkLiquidManager) {
        this.chunkManager = chunkManager;
        this.chunkLightManager = chunkLightManager;
        this.chunkLiquidManager = chunkLiquidManager;
        if (chunkLiquidManager == null) {
            log.warn("No ChunkLiquidManager given. Liquid flow will not be supported.");
        }
        if (chunkLightManager == null) {
            log.warn("No ChunkLightManager given. Light will not propagate.");
        }
    }

    public Block getBlock(Vector3f location) {
        return chunkManager.getBlock(location).orElse(null);
    }

    public Set<Vec3i> addBlock(Vector3f location, Block block) {
        // Preserves the order of the location to generate
        Set<Vec3i> chunks = new LinkedHashSet<>();
        Vec3i chunkLocation = ChunkManager.getChunkLocation(location);
        Chunk chunk = chunkManager.getChunk(chunkLocation).orElse(null);
        if (chunk == null) {
            return chunks;
        }

        Vec3i blockLocationInsideChunk = chunk.toLocalLocation(ChunkManager.getBlockLocation(location));

        if (TypeIds.RAIL.equals(block.getType())) {
            block = selectRailBlock(block, location);
            if (block == null) {
                return chunks;
            }
        }

        log.info("Adding block {}", block.getName());

        Block previousBlock = chunk.getBlock(blockLocationInsideChunk);
        if (Objects.equals(previousBlock, block)) {
            // 1. Adding the same block : nothing to do
            log.info("Previous block at location {} was already {}", location, previousBlock);
            return chunks;
        }

        if (previousBlock == null) {
            // 2. Adding a block on empty non-water location : add the block
            addBlockInEmptyNonWaterLocation(block, location, chunk, blockLocationInsideChunk);

        } else if (previousBlock.getLiquidLevel() > 0) {
            // 3. Adding block in water
            if (WATER_SOURCE.equals(block.getName())) {
                addSourceBlockInWaterLocation(block, previousBlock, chunk, blockLocationInsideChunk);

            } else if (WATER_SOURCE.equals(previousBlock.getName())) {
                addBlockInSourceWaterLocation(block, previousBlock, chunk, blockLocationInsideChunk);

            } else if (TypeIds.WATER.equals(previousBlock.getType())) {
                addBlockInWaterLocation(block, previousBlock, chunk, blockLocationInsideChunk, location);

            } else {
                // There is already a block at this location
                log.info("Existing block {} at location {} prevents adding the new block", previousBlock, location);
                return chunks;
            }

        } else {
            // 4. Adding block on an existing non-water block is not allowed
            log.info("Existing block {} at location {} prevents adding the new block", previousBlock, location);
            return chunks;
        }

        // When adding a block, redraw chunk of added block first to avoid
        // seeing holes in adjacent chunks when the chunks are added to the world in different
        // frames. This requires the set keeping the order.
        chunks.add(chunk.getLocation());

        // Request chunk updates of neighbour blocks only if block is at the border of the chunk
        chunks.addAll(getAdjacentChunks(chunk, blockLocationInsideChunk, BlocksConfig.getInstance().getChunkSize()));

        if (chunkLightManager != null) {
            // Computes the light if the block is a torch
            if (block.isTorchlight()) {
                chunks.addAll(chunkLightManager.addTorchlight(blockLocationInsideChunk, chunk, 15));
            } else {
                chunks.addAll(chunkLightManager.removeSunlight(blockLocationInsideChunk, chunk));
                chunks.addAll(chunkLightManager.removeTorchlight(blockLocationInsideChunk, chunk));
            }
        }

        chunkManager.requestOrderedMeshChunks(chunks);

        return chunks;
    }

    public Block orientateBlock(Block block, Vector3f blockLocation, Vector3f camDirection, Direction direction) {
        String type = block.getType();
        String[] shapeProperties = block.getShape().split("_");
        String shape = shapeProperties[0];

        // Turn the block according to where the user clicked
        if (TypeIds.SCALE.equals(block.getType())) {
            // A scale can only be added if a block exists behind it
            if (Direction.UP.equals(direction) || Direction.DOWN.equals(direction)) {
                log.info("Can't add an horizontal scale");
                return null;
            }
            Block behind = chunkManager.getBlock(blockLocation.subtract(direction.getVector().toVector3f())).orElse(null);
            if (behind == null || !ShapeIds.CUBE.equals(behind.getShape())) {
                log.info("No cube block behind scale");
                return null;
            }
        }

        // Turn the rail in the direction of the cam
        if (TypeIds.RAIL.equals(block.getType())) {
            if (Math.abs(camDirection.setY(0).normalizeLocal().dot(NORTH)) > 0.5f) {
                block = BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.getName(RAIL, ShapeIds.SQUARE_HS, 0));
            } else {
                block = BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.getName(RAIL, ShapeIds.SQUARE_HE, 0));
            }
        }

        String subShape = shapeProperties.length <= 2 ? "" : String.join("_", Arrays.copyOfRange(shapeProperties, 1, shapeProperties.length - 1));
        Block above = chunkManager.getBlock(blockLocation.add(0, 1, 0)).orElse(null);
        Block below = chunkManager.getBlock(blockLocation.add(0, -1, 0)).orElse(null);
        if (above != null && below == null
                && (Objects.equals("stairs", shape) || Objects.equals("wedge", shape))) {
            shape = String.join("_", shape, "inverted");
        }

        if (subShape.length() > 0) {
            shape = String.join("_", shape, subShape);
        }
        String orientedBlockName = String.format("%s-%s-%s", type, String.join("_", shape, direction.name().toLowerCase()), block.getLiquidLevelId());
        Block orientedBlock = BlocksConfig.getInstance().getBlockRegistry().get(orientedBlockName);

        // Just in case this particulier orientation does not exist...
        if (orientedBlock != null) {
            block = orientedBlock;
        }
        return block;
    }

    public Set<Vec3i> removeBlock(Vector3f location) {
        log.info("Removing block at {}", location);

        if (location.y <= 1) {
            return Collections.emptySet();
        }

        // Preserves the order of the location to generate
        Set<Vec3i> chunks = new LinkedHashSet<>();
        Vec3i chunkLocation = ChunkManager.getChunkLocation(location);
        Chunk chunk = chunkManager.getChunk(chunkLocation).orElse(null);
        if (chunk == null) {
            return chunks;
        }

        Vec3i blockLocationInsideChunk = chunk.toLocalLocation(ChunkManager.getBlockLocation(location));
        Block previousBlock = chunk.removeBlock(blockLocationInsideChunk);
        if (previousBlock == null) {
            log.warn("No block found at location {}", location);
            return chunks;
        }

        int lvl = previousBlock.getLiquidLevel();
        if (lvl > 0) {
            // Optimisation : if removing a block in water, apply directly the water level
            // of the block location
            String shapeId = ShapeIds.LIQUID;

            if (lvl < LEVEL_MAX) {
                // Partial liquid
                shapeId = shapeId + "_" + lvl;
            }

            chunk.addBlock(blockLocationInsideChunk,
                    BlocksConfig.getInstance().getBlockRegistry()
                            .get(BlockIds.getName(TypeIds.WATER, shapeId)));

            if (chunkLiquidManager != null) {
                chunkLiquidManager.flowLiquid(location);
            }

        } else if (chunkLiquidManager != null) {
            Vector3f above = location.add(0, 1, 0);
            chunkLiquidManager.removeSource(above);
            chunkLiquidManager.flowLiquid(above);
        }

        // When adding Square (e.g. a Scale), remove other scales conflicting with the added scale
        chunks.addAll(cleanAroundBlocks(location));

        // Request chunk updates of neighbour blocks only if block is at the border of the chunk
        chunks.addAll(getAdjacentChunks(chunk, blockLocationInsideChunk, BlocksConfig.getInstance().getChunkSize()));

        // When removing a block, redraw chunk of removed block last to avoid
        // seeing holes in adjacent chunks when the chunks are added to the world in different
        // frames. This requires the set keeping the order.
        chunks.add(chunk.getLocation());

        if (chunkLightManager != null) {
            chunks.addAll(chunkLightManager.removeTorchlight(blockLocationInsideChunk, chunk));
            chunks.addAll(chunkLightManager.restoreSunlight(location));
        }

        chunkManager.requestOrderedMeshChunks(chunks);

        return chunks;
    }

    public int getSunlightLevel(Vector3f location) {
        Vec3i chunkLocation = ChunkManager.getChunkLocation(location);
        return chunkManager.getChunk(chunkLocation).map(chunk -> {
            Vec3i loc = chunk.toLocalLocation(ChunkManager.getBlockLocation(location));
            return chunk.getSunlight(loc.x, loc.y, loc.z);
        }).orElse(15);
    }

    public int getTorchlightLevel(Vector3f location) {
        Vec3i chunkLocation = ChunkManager.getChunkLocation(location);
        return chunkManager.getChunk(chunkLocation).map(chunk -> {
            Vec3i loc = chunk.toLocalLocation(ChunkManager.getBlockLocation(location));
            return chunk.getTorchlight(loc.x, loc.y, loc.z);
        }).orElse(0);
    }

    /**
     * Change the current selected rail block to the appropriate variant,
     * according to the neighbour rail blocks
     *
     * @param block the selected rail block
     * @param location the location of the block
     * @return the rail block
     */
    private Block selectRailBlock(Block block, Vector3f location) {
        Block below = chunkManager.getBlock(location.add(0, -1, 0)).orElse(null);
        if (!isCubeBlock(below)) {
            log.info("No cube block below rail");
            return null;
        }

        Block north = chunkManager.getBlock(location.subtract(0, 0, -1)).orElse(null);
        Block south = chunkManager.getBlock(location.subtract(0, 0, 1)).orElse(null);
        Block west = chunkManager.getBlock(location.subtract(-1, 0, 0)).orElse(null);
        Block east = chunkManager.getBlock(location.subtract(1, 0, 0)).orElse(null);
        Block northb = chunkManager.getBlock(location.subtract(0, 1, -1)).orElse(null);
        Block southb = chunkManager.getBlock(location.subtract(0, 1, 1)).orElse(null);
        Block westb = chunkManager.getBlock(location.subtract(-1, 1, 0)).orElse(null);
        Block eastb = chunkManager.getBlock(location.subtract(1, 1, 0)).orElse(null);
        boolean n = isRail(north, northb, ShapeIds.WEDGE_SOUTH);
        boolean s = isRail(south, southb, ShapeIds.WEDGE_NORTH);
        boolean w = isRail(west, westb, ShapeIds.WEDGE_EAST);
        boolean e = isRail(east, eastb, ShapeIds.WEDGE_WEST);

        if ((n || s) && (!e && !w)) {
            // |
            if (isBlockNoRail(north)) {
                return BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.getName(RAIL_SLOPE, ShapeIds.WEDGE_NORTH, 0));
            }
            if (isBlockNoRail(south)) {
                return BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.getName(RAIL_SLOPE, ShapeIds.WEDGE_SOUTH, 0));
            }
            return BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.getName(RAIL, ShapeIds.SQUARE_HS, 0));
        }

        if ((e || w) && (!n && !s)) {
            // _
            if (isBlockNoRail(east)) {
                return BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.getName(RAIL_SLOPE, ShapeIds.WEDGE_EAST, 0));
            }
            if (isBlockNoRail(west)) {
                return BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.getName(RAIL_SLOPE, ShapeIds.WEDGE_WEST, 0));
            }
            return BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.getName(RAIL, ShapeIds.SQUARE_HE, 0));
        }

        if (n && e) {
            // |_
            return BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.getName(RAIL_CURVED, ShapeIds.SQUARE_HW, 0));
        }

        if (s && w) {
            // -|
            return BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.getName(RAIL_CURVED, ShapeIds.SQUARE_HE, 0));
        }

        if (s) {
            // |-
            return BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.getName(RAIL_CURVED, ShapeIds.SQUARE_HS, 0));
        }

        if (n) {
            // _|
            return BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.getName(RAIL_CURVED, ShapeIds.SQUARE_HN, 0));
        }

        return block;
    }

    private boolean isRail(Block block, Block bottomBlock, String bottomBlockShapeId) {
        return (block != null && block.getType().startsWith(RAIL))
                || (bottomBlock != null && bottomBlock.getShape().equals(bottomBlockShapeId));
    }

    private boolean isBlockNoRail(Block block) {
        return isCubeBlock(block) && !block.getType().startsWith(RAIL);
    }

    private boolean isCubeBlock(Block block) {
        return block != null && block.isSolid() && ShapeIds.CUBE.equals(block.getShape());
    }

    private void addBlockInEmptyNonWaterLocation(Block block, Vector3f location, Chunk chunk, Vec3i blockLocationInsideChunk) {
        if (ShapeIds.SLAB.equals(block.getShape())) {
            Vector3f belowLocation = location.add(0, -1, 0);
            Block below = null;
            Vec3i belowBlockLocationInsideChunk = null;
            Vec3i belowBlockChunkLocation = ChunkManager.getChunkLocation(belowLocation);
            Chunk belowBlockChunk = chunkManager.getChunk(belowBlockChunkLocation).orElse(null);
            if (belowBlockChunk != null) {
                belowBlockLocationInsideChunk = belowBlockChunk.toLocalLocation(ChunkManager.getBlockLocation(belowLocation));
                below = belowBlockChunk.getBlock(belowBlockLocationInsideChunk);
            }

            if (below != null) {
                if (ShapeIds.SLAB.equals(below.getShape())) {
                    block = BlocksConfig.getInstance()
                            .getBlockRegistry()
                            .get(BlockIds.getName(block.getType(), ShapeIds.DOUBLE_SLAB, 0));
                    blockLocationInsideChunk = belowBlockLocationInsideChunk;
                    chunk = belowBlockChunk;

                } else if (ShapeIds.DOUBLE_SLAB.equals(below.getShape())) {
                    block = BlocksConfig.getInstance()
                            .getBlockRegistry()
                            .get(BlockIds.getName(block.getType(), ShapeIds.CUBE, 0));

                    blockLocationInsideChunk = belowBlockLocationInsideChunk;
                    chunk = belowBlockChunk;
                }
            }
        }

        chunk.addBlock(blockLocationInsideChunk, block);
        if (WATER_SOURCE.equals(block.getName()) && chunkLiquidManager != null) {
            chunkLiquidManager.addSource(chunk, blockLocationInsideChunk);
        }
    }

    private void addSourceBlockInWaterLocation(Block block, Block previousBlock, Chunk chunk, Vec3i blockLocationInsideChunk) {
        // 3.1 Adding a source where there is already non-source water
        if (!TypeIds.WATER.equals(previousBlock.getType())) {
            // 3.1.1 If a non water block exists there, add source water to it
            String blockName = BlockIds.getName(previousBlock.getType(), previousBlock.getShape(), Block.LIQUID_SOURCE);
            block = BlocksConfig.getInstance().getBlockRegistry().get(blockName);
            if (block == null) {
                log.warn("Source block {} not found", blockName);
            }
        }
        chunk.addBlock(blockLocationInsideChunk, block);
        if (chunkLiquidManager != null) {
            chunkLiquidManager.addSource(chunk, blockLocationInsideChunk);
        }
    }

    private void addBlockInSourceWaterLocation(Block block, Block previousBlock, Chunk chunk, Vec3i blockLocationInsideChunk) {
        // 3.2 Adding a block on a water source removes the source if the block is a cube
        if (ShapeIds.CUBE.equals(block.getShape())) {
            chunk.addBlock(blockLocationInsideChunk, block);
            if (chunkLiquidManager != null) {
                chunkLiquidManager.removeSource(chunk, blockLocationInsideChunk, previousBlock.getLiquidLevel());
            }
        } else {
            String blockName = BlockIds.getName(block.getType(), block.getShape(), Block.LIQUID_SOURCE);
            block = BlocksConfig.getInstance().getBlockRegistry().get(blockName);
            if (block == null) {
                log.warn("Block {} not found", blockName);
            } else {
                chunk.addBlock(blockLocationInsideChunk, block);
            }
        }
    }

    private void addBlockInWaterLocation(Block block, Block previousBlock, Chunk chunk, Vec3i blockLocationInsideChunk, Vector3f location) {
        // 3.3 Adding a regular block where there is already some water
        // Optimisation : if adding a block in water, apply directly the water level
        // of the block location if the block does not cover any of its faces
        String blockName = BlockIds.getName(block.getType(), block.getShape(), previousBlock.getLiquidLevel());
        block = BlocksConfig.getInstance().getBlockRegistry().get(blockName);
        if (block == null) {
            log.warn("Regular block {} not found", blockName);
        } else {
            Shape shape = BlocksConfig.getInstance().getShapeRegistry().get(block.getShape());
            chunk.addBlock(blockLocationInsideChunk, block);
            if (chunkLiquidManager != null
                    && (shape.fullyCoversFace(Direction.DOWN)
                    || shape.fullyCoversFace(Direction.NORTH)
                    || shape.fullyCoversFace(Direction.SOUTH)
                    || shape.fullyCoversFace(Direction.EAST)
                    || shape.fullyCoversFace(Direction.WEST))) {
                chunkLiquidManager.removeSource(chunk, blockLocationInsideChunk, previousBlock.getLiquidLevel());
                chunkLiquidManager.flowLiquid(location);
            }
        }
    }

    private Set<Vec3i> getAdjacentChunks(Chunk chunk, Vec3i blockLocationInsideChunk, Vec3i chunkSize) {
        Set<Vec3i> chunks = new HashSet<>();
        if (blockLocationInsideChunk.x == chunkSize.x - 1) {
            chunks.add(chunk.getLocation().add(1, 0, 0));
        }
        if (blockLocationInsideChunk.x == 0) {
            chunks.add(chunk.getLocation().add(-1, 0, 0));
        }
        if (blockLocationInsideChunk.y == chunkSize.y - 1) {
            chunks.add(chunk.getLocation().add(0, 1, 0));
        }
        if (blockLocationInsideChunk.y == 0) {
            chunks.add(chunk.getLocation().add(0, -1, 0));
        }
        if (blockLocationInsideChunk.z == chunkSize.z - 1) {
            chunks.add(chunk.getLocation().add(0, 0, 1));
        }
        if (blockLocationInsideChunk.z == 0) {
            chunks.add(chunk.getLocation().add(0, 0, -1));
        }
        return chunks;
    }

    private Set<Vec3i> cleanAroundBlocks(Vector3f blockLocation) {
        Vector3f aroundLocation;
        Set<Vec3i> updatedChunks = new HashSet<>();

        // WEST
        aroundLocation = blockLocation.add(-1, 0, 0);
        Block west = chunkManager.getBlock(aroundLocation).orElse(null);
        if (west != null && ShapeIds.SQUARE_WEST.equals(west.getShape())) {
            updatedChunks.addAll(removeBlock(aroundLocation));
        }

        // EAST
        aroundLocation = blockLocation.add(1, 0, 0);
        Block east = chunkManager.getBlock(aroundLocation).orElse(null);
        if (east != null && ShapeIds.SQUARE_EAST.equals(east.getShape())) {
            updatedChunks.addAll(removeBlock(aroundLocation));
        }

        // NORTH
        aroundLocation = blockLocation.add(0, 0, -1);
        Block north = chunkManager.getBlock(aroundLocation).orElse(null);
        if (north != null && ShapeIds.SQUARE_NORTH.equals(north.getShape())) {
            updatedChunks.addAll(removeBlock(aroundLocation));
        }

        // SOUTH
        aroundLocation = blockLocation.add(0, 0, 1);
        Block south = chunkManager.getBlock(aroundLocation).orElse(null);
        if (south != null && ShapeIds.SQUARE_SOUTH.equals(south.getShape())) {
            updatedChunks.addAll(removeBlock(aroundLocation));
        }

        if ((south != null && south.isLiquidSource() && north != null && north.isLiquidSource())
                || (west != null && west.isLiquidSource() && east != null && east.isLiquidSource())) {
            updatedChunks.addAll(addBlock(blockLocation, BlocksConfig.getInstance().getBlockRegistry().get(WATER_SOURCE)));
        }

        return updatedChunks;
    }

}
