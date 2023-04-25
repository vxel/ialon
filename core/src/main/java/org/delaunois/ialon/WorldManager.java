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

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import static com.rvandoosselaer.blocks.BlockIds.WATER_SOURCE;
import static com.rvandoosselaer.blocks.shapes.Liquid.LEVEL_MAX;

/**
 * Handles interactions between blocks, water and lights
 *
 * @author Cedric de Launois
 */
@Slf4j
@AllArgsConstructor
public class WorldManager {

    @Getter
    private ChunkManager chunkManager;

    @Getter
    private ChunkLightManager chunkLightManager;

    @Getter
    private ChunkLiquidManager chunkLiquidManager;

    public Set<Vec3i> addBlock(Vector3f location, Block block) {
        // Preserves the order of the location to generate
        Set<Vec3i> chunks = new LinkedHashSet<>();
        Vec3i chunkLocation = ChunkManager.getChunkLocation(location);
        Chunk chunk = chunkManager.getChunk(chunkLocation).orElse(null);
        if (chunk == null) {
            return chunks;
        }

        Vec3i blockLocationInsideChunk = chunk.toLocalLocation(ChunkManager.getBlockLocation(location));

        Block previousBlock = chunk.getBlock(blockLocationInsideChunk);
        if (Objects.equals(previousBlock, block)) {
            // 1. Adding the same block : nothing to do
            log.info("Previous block at location {} was already {}", location, previousBlock);
            return chunks;
        }

        if (previousBlock == null) {
            // 2. Adding a block on empty non-water location : add the block
            addBlockInEmptyNonWaterLocation(block, chunk, blockLocationInsideChunk);

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
            chunks.addAll(chunkLightManager.removeSunlight(location));
            chunks.addAll(chunkLightManager.removeTorchlight(location));
        }

        return chunks;
    }

    public Set<Vec3i> removeBlock(Vector3f location) {
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

        Vec3i size = BlocksConfig.getInstance().getChunkSize();

        // Request chunk updates of neighbour blocks only if block is at the border of the chunk
        if (blockLocationInsideChunk.x == size.x - 1) {
            chunks.add(chunk.getLocation().add(1, 0, 0));
        }
        if (blockLocationInsideChunk.x == 0) {
            chunks.add(chunk.getLocation().add(-1, 0, 0));
        }
        if (blockLocationInsideChunk.y == size.y - 1) {
            chunks.add(chunk.getLocation().add(0, 1, 0));
        }
        if (blockLocationInsideChunk.y == 0) {
            chunks.add(chunk.getLocation().add(0, -1, 0));
        }
        if (blockLocationInsideChunk.z == size.z - 1) {
            chunks.add(chunk.getLocation().add(0, 0, 1));
        }
        if (blockLocationInsideChunk.z == 0) {
            chunks.add(chunk.getLocation().add(0, 0, -1));
        }

        // When removing a block, redraw chunk of removed block last to avoid
        // seeing holes in adjacent chunks when the chunks are added to the world in different
        // frames. This requires the set keeping the order.
        chunks.add(chunk.getLocation());

        if (chunkLightManager != null) {
            chunks.addAll(chunkLightManager.removeTorchlight(location));
            chunks.addAll(chunkLightManager.restoreSunlight(location));
        }

        return chunks;
    }

    public Set<Vec3i> addTorchlight(Vector3f location, int intensity) {
        return chunkLightManager.addTorchlight(location, intensity);
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

    private void addBlockInEmptyNonWaterLocation(Block block, Chunk chunk, Vec3i blockLocationInsideChunk) {
        chunk.addBlock(blockLocationInsideChunk, block);
        if (WATER_SOURCE.equals(block.getName())) {
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
        chunkLiquidManager.addSource(chunk, blockLocationInsideChunk);
    }

    private void addBlockInSourceWaterLocation(Block block, Block previousBlock, Chunk chunk, Vec3i blockLocationInsideChunk) {
        // 3.2 Adding a block on a water source removes the source if the block is a cube
        if (ShapeIds.CUBE.equals(block.getShape())) {
            chunk.addBlock(blockLocationInsideChunk, block);
            chunkLiquidManager.removeSource(chunk, blockLocationInsideChunk, previousBlock.getLiquidLevel());
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
            if (shape.fullyCoversFace(Direction.DOWN)
                    || shape.fullyCoversFace(Direction.NORTH)
                    || shape.fullyCoversFace(Direction.SOUTH)
                    || shape.fullyCoversFace(Direction.EAST)
                    || shape.fullyCoversFace(Direction.WEST)) {
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


}
