package org.delaunois.ialon.blocks;

import static org.delaunois.ialon.blocks.BlockIds.RAIL;
import static org.delaunois.ialon.blocks.BlockIds.RAIL_CURVED;
import static org.delaunois.ialon.blocks.BlockIds.WATER_SOURCE;
import static org.delaunois.ialon.blocks.TypeIds.RAIL_SLOPE;
import static org.delaunois.ialon.blocks.shapes.Liquid.LEVEL_MAX;

import com.jme3.math.Vector3f;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.blocks.generator.NoiseTerrainGenerator;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

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

    // Optional far-horizon edit overlay : records felled trees and reshaped relief so the distant
    // (procedurally regenerated) terrain/trees honour the player's edits. Null in tests / when no
    // NoiseTerrainGenerator is in use ; recording is then a no-op.
    private final WorldEditOverlay worldEditOverlay;
    private final NoiseTerrainGenerator noiseGenerator;

    private static final Vector3f NORTH = new Vector3f(0, 0, -1);

    public WorldManager(ChunkManager chunkManager, ChunkLightManager chunkLightManager, ChunkLiquidManager chunkLiquidManager) {
        this(chunkManager, chunkLightManager, chunkLiquidManager, null, null);
    }

    public WorldManager(ChunkManager chunkManager, ChunkLightManager chunkLightManager,
                        ChunkLiquidManager chunkLiquidManager, WorldEditOverlay worldEditOverlay,
                        NoiseTerrainGenerator noiseGenerator) {
        this.chunkManager = chunkManager;
        this.chunkLightManager = chunkLightManager;
        this.chunkLiquidManager = chunkLiquidManager;
        this.worldEditOverlay = worldEditOverlay;
        this.noiseGenerator = noiseGenerator;
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
        Set<Vec3i> result = doAddBlock(location, block);
        if (worldEditOverlay != null && !result.isEmpty()) {
            recordEdit(location, block, true);
        }
        return result;
    }

    private Set<Vec3i> doAddBlock(Vector3f location, Block block) {
        Set<Vec3i> emptyChunkSet = new LinkedHashSet<>();
        Vec3i chunkLocation = ChunkManager.getChunkLocation(location);
        Chunk chunk = chunkManager.getChunk(chunkLocation).orElse(null);
        if (chunk == null) {
            return emptyChunkSet;
        }

        Vec3i blockLocationInsideChunk = chunk.toLocalLocation(ChunkManager.getBlockLocation(location));
        Block previousBlock = chunk.getBlock(blockLocationInsideChunk);

        if (TypeIds.RAIL.equals(block.getType())) {
            // Select rail block according to the neighbour blocks
            block = selectRailBlock(block, location);
            if (block == null) {
                return emptyChunkSet;
            }

        } else if (ShapeIds.SLAB.equals(block.getShape())) {
            // Select slab block according to the block below
            BlockWithLocation blockWithLocation = selectSlabBlock(block, location);
            if (blockWithLocation != null) {
                block = blockWithLocation.block;
                blockLocationInsideChunk = blockWithLocation.blockLocationInsideChunk;
                chunk = blockWithLocation.chunk;
                previousBlock = null;
            }
        }

        log.info("Adding block {}", block.getName());

        if (Objects.equals(previousBlock, block)) {
            // 1. Adding the same block : nothing to do
            log.info("Previous block at location {} was already {}", location, previousBlock);
            return emptyChunkSet;
        }

        if (previousBlock == null) {
            // 2. Adding a block on empty non-water location : add the block
            return addBlockInEmptyNonWaterLocation(block, chunk, blockLocationInsideChunk);
        }

        if (previousBlock.getLiquidLevel() > 0) {
            // 3. Adding block in water
            return addBlockInWater(block, previousBlock, chunk, blockLocationInsideChunk, location);
        }

        // 4. Adding block on an existing non-water block is not allowed
        log.info("Existing block {} at location {} prevents adding the new block", previousBlock, location);
        return emptyChunkSet;
    }

    private Set<Vec3i> updateChunksAfterAddBlock(Block block, Chunk chunk, Vec3i blockLocationInsideChunk) {
        Set<Vec3i> chunks = new LinkedHashSet<>();

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
        if (TypeIds.SCALE.equals(block.getType())) {
            // Turn the block according to where the user clicked
            return orientateBlockScale(block, blockLocation, direction);

        } else if (TypeIds.RAIL.equals(block.getType())) {
            // Turn the rail in the direction of the cam
            return orientateBlockRail(camDirection);

        } else if (isDoor(block.getType())) {
            // Place the door closed, facing the player (otherwise clicking the ground would lay it flat)
            return orientateBlockDoor(blockLocation, camDirection);

        } else {
            return orientateBlockDefault(block, blockLocation, direction);
        }
    }

    /**
     * Orientates a door at placement time : a <b>closed</b> panel barring the passage the player faces,
     * with its <b>hinge against an existing solid block</b> when there is one, so the door opens flat
     * against the wall. The closed panel always uses the same orientation for a given passage axis, so
     * two doors placed side by side stay <b>coplanar</b> (a double-leaf door) ; only the hinge side
     * varies - encoded by the door type (DOOR_LEFT vs DOOR_RIGHT, which pivot around opposite edges). We
     * pick the type whose hinge abuts a solid neighbour.
     */
    public Block orientateBlockDoor(Vector3f blockLocation, Vector3f camDirection) {
        // mult(1f) copies : camDirection is the live, per-frame head direction vector ; do not mutate it.
        boolean facingNorthSouth = Math.abs(camDirection.mult(1f).setY(0).normalizeLocal().dot(NORTH)) > 0.5f;
        // A single, fixed closed orientation per passage axis keeps both leaves of a double door coplanar.
        String shape = facingNorthSouth ? ShapeIds.PLATE_NORTH : ShapeIds.PLATE_EAST;

        String type = TypeIds.DOOR_LEFT;
        for (String candidate : new String[]{TypeIds.DOOR_LEFT, TypeIds.DOOR_RIGHT}) {
            Vec3i hinge = doorHingeNeighbour(candidate, shape).getVector();
            if (isSolidWall(blockLocation.add(hinge.x, hinge.y, hinge.z))) {
                type = candidate;
                break;
            }
        }
        return BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.getName(type, shape, 0));
    }

    /**
     * The neighbour cell a closed door (of the given type and orientation) hinges against. The door
     * opens onto the wall its toggle partner lies flush against, so the hinge is on that side. Derived
     * from the engine geometry (flush face of the open partner) rather than hard-coded, so it can never
     * silently invert.
     */
    static Direction doorHingeNeighbour(String type, String closedShape) {
        Direction openDirection = doorShapeDirection(toggledDoorShape(type, closedShape));
        // Door plates are thin and bottom-flush (startY == -0.5), so their flush face is the rotated DOWN face.
        return Shape.getFaceDirection(Direction.DOWN, openDirection);
    }

    /** The {@link Direction} encoded in a door plate shape id, e.g. {@code "plate_north"} -> NORTH. */
    private static Direction doorShapeDirection(String shape) {
        return Direction.valueOf(shape.substring(shape.indexOf('_') + 1).toUpperCase(Locale.ROOT));
    }

    /** True for both door types (DOOR and its mirror-hinge variant DOOR_RIGHT). */
    public static boolean isDoor(String type) {
        return TypeIds.DOOR_LEFT.equals(type) || TypeIds.DOOR_RIGHT.equals(type);
    }

    /** A solid block that can host a door hinge : any solid block that is not itself a door. */
    private boolean isSolidWall(Vector3f location) {
        Block b = chunkManager.getBlock(location).orElse(null);
        return b != null && b.isSolid() && !isDoor(b.getType());
    }

    public Block orientateBlockScale(Block block, Vector3f blockLocation, Direction direction) {
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
        return orientateBlockDefault(block, blockLocation, direction);
    }

    public Block orientateBlockRail(Vector3f camDirection) {
        return BlocksConfig
                .getInstance()
                .getBlockRegistry()
                .get(BlockIds.getName(
                        RAIL,
                        Math.abs(camDirection.setY(0).normalizeLocal().dot(NORTH)) > 0.5f ? ShapeIds.SQUARE_HS : ShapeIds.SQUARE_HE,
                        0)
                );
    }

    public Block orientateBlockDefault(Block block, Vector3f blockLocation, Direction direction) {
        String type = block.getType();
        String[] shapeProperties = block.getShape().split("_");
        String shape = shapeProperties[0];

        String subShape = shapeProperties.length <= 2 ? "" : String.join("_", Arrays.copyOfRange(shapeProperties, 1, shapeProperties.length - 1));
        Block above = chunkManager.getBlock(blockLocation.add(0, 1, 0)).orElse(null);
        Block below = chunkManager.getBlock(blockLocation.add(0, -1, 0)).orElse(null);
        if (above != null && below == null
                && (Objects.equals("stairs", shape) || Objects.equals("wedge", shape))) {
            shape = String.join("_", shape, "inverted");
        }

        if (!subShape.isEmpty()) {
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

    /**
     * Opens or closes a door : swaps the targeted door block's Plate orientation by 90° (the hinge
     * rotation north&lt;-&gt;east / south&lt;-&gt;west) and applies the same swap to the <b>whole contiguous
     * vertical run</b> of door blocks above and below it, so a door several blocks tall toggles as one.
     * No-op (empty set) when the location is not a door. Returns the chunks to re-mesh.
     */
    public Set<Vec3i> toggleDoor(Vector3f location) {
        Block target = getBlock(location);
        if (target == null || !isDoor(target.getType())) {
            return Collections.emptySet();
        }

        Vec3i base = ChunkManager.getBlockLocation(location);

        // Scan the whole contiguous vertical run of door blocks.
        int yMin = base.y;
        while (isDoorAt(base.x, yMin - 1, base.z)) {
            yMin--;
        }
        int yMax = base.y;
        while (isDoorAt(base.x, yMax + 1, base.z)) {
            yMax++;
        }

        Set<Vec3i> chunks = new LinkedHashSet<>();
        Vector3f loc = new Vector3f();
        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();
        BlockRegistry registry = BlocksConfig.getInstance().getBlockRegistry();
        for (int y = yMin; y <= yMax; y++) {
            loc.set(base.x, y, base.z);
            Chunk chunk = chunkManager.getChunk(ChunkManager.getChunkLocation(loc)).orElse(null);
            if (chunk == null) {
                continue;
            }
            Vec3i local = chunk.toLocalLocation(ChunkManager.getBlockLocation(loc));
            Block door = chunk.getBlock(local);
            if (door == null || !isDoor(door.getType())) {
                continue;
            }
            Block toggled = registry.get(BlockIds.getName(
                    door.getType(), toggledDoorShape(door.getType(), door.getShape()), 0));
            if (toggled == null || Objects.equals(toggled, door)) {
                continue;
            }
            chunk.addBlock(local, toggled);
            chunks.add(chunk.getLocation());
            chunks.addAll(getAdjacentChunks(chunk, local, chunkSize));
        }

        if (!chunks.isEmpty()) {
            chunkManager.requestOrderedMeshChunks(chunks);
        }
        return chunks;
    }

    private boolean isDoorAt(int x, int y, int z) {
        Block b = chunkManager.getBlock(new Vector3f(x, y, z)).orElse(null);
        return b != null && isDoor(b.getType());
    }

    /**
     * The 90° hinge swap between a door's closed and open Plate orientations (its own inverse).
     * DOOR_LEFT and DOOR_RIGHT pivot around opposite vertical edges :
     * DOOR_LEFT pairs north&lt;-&gt;east / south&lt;-&gt;west,
     * DOOR_RIGHT pairs north&lt;-&gt;west / south&lt;-&gt;east.
     * Two coplanar leaves (same closed shape) of the two types therefore open onto opposite jambs.
     */
    private static String toggledDoorShape(String type, String shape) {
        boolean rightHinged = TypeIds.DOOR_RIGHT.equals(type);
        return switch (shape) {
            case ShapeIds.PLATE_NORTH -> rightHinged ? ShapeIds.PLATE_WEST : ShapeIds.PLATE_EAST;
            case ShapeIds.PLATE_EAST -> rightHinged ? ShapeIds.PLATE_SOUTH : ShapeIds.PLATE_NORTH;
            case ShapeIds.PLATE_SOUTH -> rightHinged ? ShapeIds.PLATE_EAST : ShapeIds.PLATE_WEST;
            case ShapeIds.PLATE_WEST -> rightHinged ? ShapeIds.PLATE_NORTH : ShapeIds.PLATE_SOUTH;
            default -> shape;
        };
    }

    public Set<Vec3i> removeBlock(Vector3f location) {
        // Capture the block before removal so the edit recorder can tell whether a tree trunk was cut.
        Block removed = (worldEditOverlay != null) ? getBlock(location) : null;
        Set<Vec3i> result = doRemoveBlock(location);
        if (worldEditOverlay != null && !result.isEmpty()) {
            recordEdit(location, removed, false);
        }
        return result;
    }

    private Set<Vec3i> doRemoveBlock(Vector3f location) {
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

    /**
     * Records a player edit into the far-horizon overlay. Cheap : it only <b>marks the chunk column</b>
     * as edited (so the far terrain refreshes it later, when the column is unfetched and becomes visible
     * at the horizon - never on the edit itself, since the far terrain is hidden while the chunk is
     * loaded) and, for a trunk LOG, fells/restores the far tree billboard. No relief scan, no mesh work.
     * A no-op when no overlay/generator is wired (tests, non-noise generators).
     */
    private void recordEdit(Vector3f location, Block block, boolean added) {
        if (noiseGenerator == null) {
            return;
        }
        Vec3i cl = ChunkManager.getChunkLocation(location);
        // Transient marker (raw key) : the far terrain refreshes this column's relief at unfetch.
        worldEditOverlay.markColumnEdited(WorldEditOverlay.pack(cl.x, cl.z));
        // Persisted marker (canonical key) : any edit, terrain OR building, so the minimap can show
        // where the player has built. Cheap : a single set add, deduplicated, written once per column.
        worldEditOverlay.markModifiedColumn(noiseGenerator.modifiedColumnKey(cl.x, cl.z));

        if (block != null && isLog(block.getType())) {
            Vec3i bl = ChunkManager.getBlockLocation(location);
            long cellKey = noiseGenerator.trunkAnchorCellKeyAt(bl.x, bl.z);
            if (cellKey != -1L) {
                if (added) {
                    worldEditOverlay.restoreTree(cellKey);
                } else {
                    worldEditOverlay.removeTree(cellKey);
                }
            }
        }
    }

    /**
     * Ground height of a world column : the topmost block flagged as natural terrain (see
     * {@link Block#isTerrain()} — grass / dirt / sand / rock / snow cubes), scanned from {@code ceiling}
     * down. Building blocks, vegetation (logs, leaves, plants) and water are therefore ignored, so only
     * digging/stacking actual ground reshapes the far horizon. The "+1" puts the result at the top
     * surface, matching the generator's height convention. Returns {@link Float#NaN} when no terrain
     * block is loaded in the column (treat as "unknown / keep the procedural height", not a hole).
     * Used by {@code FarTerrainState} to re-measure samples.
     */
    public static float groundHeight(ChunkManager chunkManager, int worldX, int worldZ, int ceiling) {
        Vector3f scan = new Vector3f();
        for (int y = ceiling - 1; y >= 0; y--) {
            Block b = chunkManager.getBlock(scan.set(worldX, y, worldZ)).orElse(null);
            if (b != null && b.isTerrain()) {
                return y + 1f;
            }
        }
        return Float.NaN;
    }

    private static boolean isLog(String type) {
        return TypeIds.OAK_LOG.equals(type) || TypeIds.BIRCH_LOG.equals(type)
                || TypeIds.SPRUCE_LOG.equals(type) || TypeIds.PALM_TREE_LOG.equals(type);
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

    private BlockWithLocation selectSlabBlock(Block block, Vector3f location) {
        BlockWithLocation blockWithLocation = new BlockWithLocation();
        blockWithLocation.blockLocation = location.add(0, -1, 0);
        blockWithLocation.chunkLocation = ChunkManager.getChunkLocation(blockWithLocation.blockLocation);
        blockWithLocation.chunk = chunkManager.getChunk(blockWithLocation.chunkLocation).orElse(null);

        if (blockWithLocation.chunk != null) {
            blockWithLocation.blockLocationInsideChunk = blockWithLocation.chunk.toLocalLocation(ChunkManager.getBlockLocation(blockWithLocation.blockLocation));
            blockWithLocation.block = blockWithLocation.chunk.getBlock(blockWithLocation.blockLocationInsideChunk);
        }

        if (blockWithLocation.block != null) {
            if (ShapeIds.SLAB.equals(blockWithLocation.block.getShape())) {
                blockWithLocation.block = BlocksConfig.getInstance()
                        .getBlockRegistry()
                        .get(BlockIds.getName(block.getType(), ShapeIds.DOUBLE_SLAB, 0));
                return blockWithLocation;

            } else if (ShapeIds.DOUBLE_SLAB.equals(blockWithLocation.block.getShape())) {
                blockWithLocation.block = BlocksConfig.getInstance()
                        .getBlockRegistry()
                        .get(BlockIds.getName(block.getType(), ShapeIds.CUBE, 0));
                return blockWithLocation;
            }
        }

        // No change
        return null;
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
        if (!isSolidBlock(below)) {
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

        byte bits = booleanToByte(n, s, w, e);
        switch (bits) {
            case 0b1100:
            case 0b0100:
            case 0b1000:
                // |
                if (isBlockNoRail(north)) {
                    return getRailBlock(RAIL_SLOPE, ShapeIds.WEDGE_NORTH);
                }
                if (isBlockNoRail(south)) {
                    return getRailBlock(RAIL_SLOPE, ShapeIds.WEDGE_SOUTH);
                }
                return getRailBlock(RAIL, ShapeIds.SQUARE_HS);
            case 0b0011:
            case 0b0010:
            case 0b0001:
                // _
                if (isBlockNoRail(east)) {
                    return getRailBlock(RAIL_SLOPE, ShapeIds.WEDGE_EAST);
                }
                if (isBlockNoRail(west)) {
                    return getRailBlock(RAIL_SLOPE, ShapeIds.WEDGE_WEST);
                }
                return getRailBlock(RAIL, ShapeIds.SQUARE_HE);
            case 0b1001:
                // |_
                return getRailBlock(RAIL_CURVED, ShapeIds.SQUARE_HW);
            case 0b0110:
                // "|
                return getRailBlock(RAIL_CURVED, ShapeIds.SQUARE_HE);
            case 0b0101:
                // |"
                return getRailBlock(RAIL_CURVED, ShapeIds.SQUARE_HS);
            case 0b1010:
                // _|
                return getRailBlock(RAIL_CURVED, ShapeIds.SQUARE_HN);
            default:
                // Not determined
                return block;
        }
    }

    private byte booleanToByte(boolean... booleans) {
        byte bits = 0; // 8-bits
        int length = Math.min(booleans.length, 8); // Limit to 8 booleans
        for (int i = 0; i < length; i++) {
            bits = (byte) (bits << 1 | (booleans[i] ? 1 : 0));
        }

        return bits;
    }

    private Block getRailBlock(String railType, String shapeId) {
        return BlocksConfig.getInstance().getBlockRegistry()
                .get(BlockIds.getName(railType, shapeId, 0));
    }

    private boolean isRail(Block block, Block bottomBlock, String bottomBlockShapeId) {
        return (block != null && block.getType().startsWith(RAIL))
                || (bottomBlock != null && bottomBlock.getShape().equals(bottomBlockShapeId));
    }

    private boolean isBlockNoRail(Block block) {
        return isSolidBlock(block) && !block.getType().startsWith(RAIL);
    }

    private boolean isSolidBlock(Block block) {
        return block != null && block.isSolid();
    }

    private Set<Vec3i> addBlockInEmptyNonWaterLocation(Block block, Chunk chunk, Vec3i blockLocationInsideChunk) {
        chunk.addBlock(blockLocationInsideChunk, block);
        if (WATER_SOURCE.equals(block.getName()) && chunkLiquidManager != null) {
            chunkLiquidManager.addSource(chunk, blockLocationInsideChunk);
        }

        return updateChunksAfterAddBlock(block, chunk, blockLocationInsideChunk);
    }

    private Set<Vec3i> addBlockInWater(Block block, Block previousBlock, Chunk chunk, Vec3i blockLocationInsideChunk, Vector3f location) {
        if (WATER_SOURCE.equals(block.getName())) {
            return addSourceBlockInWaterLocation(block, previousBlock, chunk, blockLocationInsideChunk);

        } else if (WATER_SOURCE.equals(previousBlock.getName())) {
            return addBlockInSourceWaterLocation(block, previousBlock, chunk, blockLocationInsideChunk);

        } else if (TypeIds.WATER.equals(previousBlock.getType())) {
            return addBlockInWaterLocation(block, previousBlock, chunk, blockLocationInsideChunk, location);

        } else {
            // There is already a block at this location
            log.info("Existing block {} at location {} prevents adding the new block", previousBlock, location);
            return new LinkedHashSet<>();
        }
    }

    private Set<Vec3i> addSourceBlockInWaterLocation(Block block, Block previousBlock, Chunk chunk, Vec3i blockLocationInsideChunk) {
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
        return updateChunksAfterAddBlock(block, chunk, blockLocationInsideChunk);
    }

    private Set<Vec3i> addBlockInSourceWaterLocation(Block block, Block previousBlock, Chunk chunk, Vec3i blockLocationInsideChunk) {
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
        return updateChunksAfterAddBlock(block, chunk, blockLocationInsideChunk);
    }

    private Set<Vec3i> addBlockInWaterLocation(Block block, Block previousBlock, Chunk chunk, Vec3i blockLocationInsideChunk, Vector3f location) {
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
        return updateChunksAfterAddBlock(block, chunk, blockLocationInsideChunk);
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

    private static class BlockWithLocation {
        private Chunk chunk;
        private Vec3i chunkLocation;
        private Block block;
        private Vector3f blockLocation;
        private Vec3i blockLocationInsideChunk;
    }

}
