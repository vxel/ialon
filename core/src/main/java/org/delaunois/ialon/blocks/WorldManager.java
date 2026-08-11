package org.delaunois.ialon.blocks;

import static org.delaunois.ialon.blocks.BlockIds.RAIL;
import static org.delaunois.ialon.blocks.BlockIds.RAIL_CURVED;
import static org.delaunois.ialon.blocks.BlockIds.WATER_SOURCE;
import static org.delaunois.ialon.blocks.TypeIds.RAIL_SLOPE;
import static org.delaunois.ialon.blocks.shapes.Liquid.LEVEL_MAX;

import com.jme3.math.Vector3f;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.blocks.generator.NoiseTerrainGenerator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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

    /** Topmost indestructible layer : blocks at or below this Y can never be removed by the player. */
    private static final int BEDROCK_TOP = 1;

    // Door type naming convention : "door_<hinge>_<look>" (default look = "door_left"/"door_right").
    private static final String DOOR_PREFIX = "door";

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

        if (TypeIds.FIRE.equals(block.getType())) {
            // Fire cannot be placed inside water : the target cell would be flooded.
            if (previousBlock != null && TypeIds.WATER.equals(previousBlock.getType())) {
                log.debug("Can't place fire in water");
                return emptyChunkSet;
            }

        } else if (TypeIds.RAIL.equals(block.getType())) {
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

        log.debug("Adding block {}", block.getName());

        if (Objects.equals(previousBlock, block)) {
            // 1. Adding the same block : nothing to do
            log.debug("Previous block at location {} was already {}", location, previousBlock);
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

        if (isWaterSource(block) && canHoldLiquid(previousBlock)) {
            // 4. Waterlogging : a water source floods a dry, non-full (non-cube) block, co-habiting
            //    with it exactly as a source dropped into already-standing water would (see 3.1.1).
            //    The block keeps its shape/type and gains the liquid-source level. Full cubes and
            //    blocks that can't carry liquid fall through to the "not allowed" case below.
            return addSourceBlockInWaterLocation(block, previousBlock, chunk, blockLocationInsideChunk);
        }

        // 5. Adding block on an existing non-water block is not allowed
        log.debug("Existing block {} at location {} prevents adding the new block", previousBlock, location);
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
            return orientateBlockDoor(block.getType(), blockLocation, camDirection);

        } else {
            return orientateBlockDefault(block, blockLocation, direction);
        }
    }

    /**
     * Orientates a door at placement time : a <b>closed</b> panel barring the passage the player faces,
     * with its <b>hinge against an existing solid block</b> when there is one, so the door opens flat
     * against the wall. The closed panel always uses the same orientation for a given passage axis, so
     * two doors placed side by side stay <b>coplanar</b> (a double-leaf door) ; only the hinge side
     * varies - encoded by the door type ({@code door_left_<look>} vs {@code door_right_<look>}, which
     * pivot around opposite edges). We keep the selected door's look and pick the hinge whose edge abuts
     * a solid neighbour.
     *
     * @param selectedType the type of the door block being placed (any {@code door_<hinge>_<look>})
     */
    public Block orientateBlockDoor(String selectedType, Vector3f blockLocation, Vector3f camDirection) {
        // mult(1f) copies : camDirection is the live, per-frame head direction vector ; do not mutate it.
        boolean facingNorthSouth = Math.abs(camDirection.mult(1f).setY(0).normalizeLocal().dot(NORTH)) > 0.5f;
        // A single, fixed closed orientation per passage axis keeps both leaves of a double door coplanar.
        String shape = facingNorthSouth ? ShapeIds.DOOR_NORTH : ShapeIds.DOOR_EAST;

        // Choose the hinge (left/right variant of this same look) whose edge abuts a wall.
        String look = doorLookSuffix(selectedType);
        String type = leftType(look);
        for (String candidate : new String[]{leftType(look), rightType(look)}) {
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

    /** The {@link Direction} encoded in a door shape id, e.g. {@code "door_north"} -> NORTH. */
    private static Direction doorShapeDirection(String shape) {
        return Direction.valueOf(shape.substring(shape.indexOf('_') + 1).toUpperCase(Locale.ROOT));
    }

    /**
     * True for any door type, of any look. Door types follow the convention {@code door_<hinge>_<look>}
     * with {@code <hinge>} = left|right (the default look has no suffix : {@code door_left} /
     * {@code door_right}), so they are all recognised by a simple {@code startsWith("door")}.
     */
    public static boolean isDoor(String type) {
        return type != null && type.startsWith(DOOR_PREFIX);
    }

    /** True for a right-hinged door type ({@code door_right_<look>}). */
    private static boolean isRightHinged(String type) {
        return type.startsWith(TypeIds.DOOR_RIGHT);
    }

    /** The look suffix of a door type : everything after the {@code door_<hinge>} prefix ("" for the default look). */
    private static String doorLookSuffix(String type) {
        String hingePrefix = isRightHinged(type) ? TypeIds.DOOR_RIGHT : TypeIds.DOOR_LEFT;
        return type.substring(hingePrefix.length());
    }

    private static String leftType(String lookSuffix) {
        return TypeIds.DOOR_LEFT + lookSuffix;
    }

    private static String rightType(String lookSuffix) {
        return TypeIds.DOOR_RIGHT + lookSuffix;
    }

    /** A solid block that can host a door hinge : any solid block that is not itself a door. */
    private boolean isSolidWall(Vector3f location) {
        Block b = chunkManager.getBlock(location).orElse(null);
        return b != null && b.isSolid() && !isDoor(b.getType());
    }

    public Block orientateBlockScale(Block block, Vector3f blockLocation, Direction direction) {
        // A scale can only be added if a block exists behind it
        if (Direction.UP.equals(direction) || Direction.DOWN.equals(direction)) {
            log.debug("Can't add an horizontal scale");
            return null;
        }
        Block behind = chunkManager.getBlock(blockLocation.subtract(direction.getVector().toVector3f())).orElse(null);
        if (behind == null || !ShapeIds.CUBE.equals(behind.getShape())) {
            log.debug("No cube block behind scale");
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
            // Toggle to the DRY variant (level 0). The cell must not keep its pass-through water :
            // a door whose supplying side is the one it now blocks would otherwise stay an orphaned
            // reservoir and re-feed the cut-off side (water then only drained when the source was on
            // the open side). Dried, the cell only refills from an uncovered, genuinely-sourced face.
            // (The generic addBlock pipeline can't be reused here : it preserves the water level, which
            // is right for a fence but leaves a door's stale pass-through water in place.)
            int previousLevel = door.getLiquidLevel();
            Block toggled = registry.get(BlockIds.getName(
                    door.getType(), toggledDoorShape(door.getType(), door.getShape()), 0));
            if (toggled == null || Objects.equals(toggled, door)) {
                continue;
            }
            chunk.addBlock(local, toggled);
            chunks.add(chunk.getLocation());
            chunks.addAll(getAdjacentChunks(chunk, local, chunkSize));

            // Re-evaluate the surrounding liquid : recede the water the cell carried (a closed door then
            // blocks the re-flow, so the cut-off side drains as if a full block were placed), then
            // re-flow from the remaining sources (an open door lets water back through). Processed and
            // re-meshed by the liquid manager's own steps, exactly like add/removeBlock.
            if (chunkLiquidManager != null) {
                if (previousLevel > 0) {
                    chunkLiquidManager.unflow(chunk, local, previousLevel);
                }
                chunkLiquidManager.flowLiquid(loc);
            }
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
     * The 90° hinge swap between a door's closed and open orientations (its own inverse).
     * DOOR_LEFT and DOOR_RIGHT pivot around opposite vertical edges :
     * DOOR_LEFT pairs north&lt;-&gt;east / south&lt;-&gt;west,
     * DOOR_RIGHT pairs north&lt;-&gt;west / south&lt;-&gt;east.
     * Two coplanar leaves (same closed shape) of the two types therefore open onto opposite jambs.
     */
    private static String toggledDoorShape(String type, String shape) {
        boolean rightHinged = isRightHinged(type);
        return switch (shape) {
            case ShapeIds.DOOR_NORTH -> rightHinged ? ShapeIds.DOOR_WEST : ShapeIds.DOOR_EAST;
            case ShapeIds.DOOR_EAST -> rightHinged ? ShapeIds.DOOR_SOUTH : ShapeIds.DOOR_NORTH;
            case ShapeIds.DOOR_SOUTH -> rightHinged ? ShapeIds.DOOR_EAST : ShapeIds.DOOR_WEST;
            case ShapeIds.DOOR_WEST -> rightHinged ? ShapeIds.DOOR_NORTH : ShapeIds.DOOR_SOUTH;
            default -> shape;
        };
    }

    public Set<Vec3i> removeBlock(Vector3f location) {
        if (location == null) {
            // No location, nothing removed. Stated here rather than assumed : the block lookups below
            // tolerate a null location (ChunkManager#getBlock returns an empty Optional), so without this
            // the null would travel on to doRemoveBlock, which dereferences it.
            return Collections.emptySet();
        }
        // Capture the block before removal so the edit recorder can tell whether a tree trunk was cut.
        Block removed = (worldEditOverlay != null) ? getBlock(location) : null;
        Set<Vec3i> result = doRemoveBlock(location);
        if (worldEditOverlay != null && !result.isEmpty()) {
            recordEdit(location, removed, false);
        }
        return result;
    }

    private Set<Vec3i> doRemoveBlock(Vector3f location) {
        log.debug("Removing block at {}", location);

        if (location.y <= BEDROCK_TOP) {
            log.warn("Cannot remove bedrock.");
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
        boolean removedPureLiquid = TypeIds.WATER.equals(previousBlock.getType())
                || TypeIds.LAVA.equals(previousBlock.getType());

        if (removedPureLiquid) {
            // Removing the liquid itself (a source or a pure water/lava cell) : recede the whole flow
            // it fed instead of re-creating it. A source recedes everything downstream (LEVEL_MAX) ;
            // a flowing cell recedes only the lower-level flow below it. unflow also re-flows from any
            // remaining source to refill the gap (so digging a flowing cell next to a source refills).
            if (chunkLiquidManager != null) {
                chunkLiquidManager.unflow(chunk, blockLocationInsideChunk,
                        previousBlock.isLiquidSource() ? LEVEL_MAX : lvl);
            }

        } else if (lvl > 0) {
            // Optimisation : removing a structural block logged with liquid (e.g. a fence post under
            // water) leaves pure liquid behind at the same level — the liquid co-habiting the block.
            String shapeId = ShapeIds.LIQUID;

            if (lvl < LEVEL_MAX) {
                // Partial liquid
                shapeId = shapeId + "_" + lvl;
            }

            chunk.addBlock(blockLocationInsideChunk,
                    BlocksConfig.getInstance().getBlockRegistry()
                            .get(BlockIds.getName(liquidTypeOf(previousBlock), shapeId)));

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
     * Removes <b>every</b> block inside an inclusive box of block coordinates, in one pass.
     *
     * <p>This is deliberately not a loop over {@link #removeBlock(Vector3f)} : that path runs a full,
     * unbudgeted light flood <i>and</i> a blocking {@code requestOrderedMeshChunks} <i>and</i> a chunk
     * file rewrite for every single block. Over a region that is tens of thousands of floods re-lighting
     * the same cells again and again. Here the work is done once for the whole box :</p>
     * <ul>
     *   <li>one sweep writing straight into the chunks (chunk lookups are memoised — the sweep is
     *       contiguous, so consecutive cells almost always share a chunk);</li>
     *   <li>one sunlight flood seeded from the whole carved volume plus a one-cell shell;</li>
     *   <li>one overlay marker per distinct chunk column, instead of one per block;</li>
     *   <li>one non-blocking re-mesh request for the touched chunks and their axis neighbours.</li>
     * </ul>
     *
     * <p>The liquid simulation is only <b>seeded</b> here (recede what the removed sources fed, then
     * re-flow from the boundary) and drained later by {@code ChunkLiquidManagerState} under its
     * per-frame budget, so a carved-open reservoir refills progressively instead of in one frame.</p>
     *
     * <p>Cells whose chunk is not currently paged in are skipped, like every other edit path.</p>
     *
     * @param min the inclusive lower corner, in block coordinates
     * @param max the inclusive upper corner, in block coordinates
     * @return the locations of the chunks that were actually modified (empty if nothing was removed)
     */
    public Set<Vec3i> removeRegion(Vec3i min, Vec3i max) {
        // Bedrock : the single-block path refuses y <= 1, so the region simply starts above it rather
        // than failing as a whole.
        int minY = Math.max(min.y, BEDROCK_TOP + 1);
        if (minY > max.y || min.x > max.x || min.z > max.z) {
            return Collections.emptySet();
        }

        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();
        Set<Vec3i> touched = new LinkedHashSet<>();
        Set<Long> columns = new HashSet<>();
        // Cells to re-seed the sunlight flood from : the carved volume plus a one-cell shell, so the
        // flood starts from the still-lit cells around the hole and pours in.
        List<Vector3f> sunlightSeeds = new ArrayList<>();
        List<LiquidCell> removedSources = new ArrayList<>();
        int removed = 0;

        ChunkLookup lookup = new ChunkLookup(chunkSize);
        for (int y = minY; y <= max.y; y++) {
            for (int z = min.z; z <= max.z; z++) {
                for (int x = min.x; x <= max.x; x++) {
                    Chunk chunk = lookup.at(x, y, z);
                    if (chunk == null) {
                        continue;
                    }
                    Block previous = chunk.removeBlock(lookup.localX, lookup.localY, lookup.localZ);
                    if (previous == null) {
                        continue;
                    }
                    removed++;
                    touched.add(chunk.getLocation());
                    columns.add(WorldEditOverlay.pack(lookup.chunkX, lookup.chunkZ));

                    if (previous.isLiquidSource() && chunkLiquidManager != null) {
                        // The flow this source fed may extend outside the box : recede it explicitly.
                        // (Plain flowing cells inside the box need nothing — they are gone, and the
                        // boundary re-flow below refills whatever a remaining source still reaches.)
                        removedSources.add(new LiquidCell(chunk,
                                new Vec3i(lookup.localX, lookup.localY, lookup.localZ)));
                    }
                    if (previous.isTorchlight() && chunkLightManager != null) {
                        touched.addAll(chunkLightManager.removeTorchlight(
                                new Vec3i(lookup.localX, lookup.localY, lookup.localZ), chunk));
                    }
                    if (noiseGenerator != null && isLog(previous.getType())) {
                        recordLogEdit(x, z, false);
                    }
                }
            }
        }

        if (removed == 0) {
            return Collections.emptySet();
        }

        // Walk the box plus its one-cell shell once : collect the sunlight seeds and, on the shell only,
        // re-seed the liquid flow from whatever liquid still borders the hole.
        seedLightAndLiquid(min.x, minY, min.z, max, lookup, sunlightSeeds);

        for (LiquidCell source : removedSources) {
            chunkLiquidManager.unflow(source.chunk, source.local, LEVEL_MAX);
        }

        if (chunkLightManager != null) {
            touched.addAll(chunkLightManager.restoreSunlight(sunlightSeeds));
        }

        finishRegionEdit(touched, columns);

        log.info("Removed {} blocks in region {}..{} : {} chunks touched, {} columns",
                removed, min, max, touched.size(), columns.size());
        return touched;
    }

    /**
     * Common tail of the bulk edits : records the edited chunk columns in the far-horizon overlay, then
     * requests one re-mesh for the touched chunks and their axis neighbours.
     *
     * <p>The non-blocking mesh variant is deliberate : the worst-case region spans dozens of chunks, and
     * making the render thread wait for all of them on the single edit thread would freeze the frame.
     * The liquid simulation already re-meshes at this scale the same way.</p>
     */
    private void finishRegionEdit(Set<Vec3i> touched, Set<Long> columns) {
        if (noiseGenerator != null) {
            for (Long column : columns) {
                recordColumnEdit(WorldEditOverlay.unpackX(column), WorldEditOverlay.unpackZ(column));
            }
        }

        requestRegionMesh(touched, true);
    }

    /**
     * Requests one non-blocking re-mesh of the given chunks and their axis neighbours.
     *
     * @param notifyUpdated whether the re-meshed chunks are flagged as updated, i.e. saved. True for the
     *                      bulk edits ; false for the chunk reset, which deletes their saved files instead
     */
    private void requestRegionMesh(Set<Vec3i> touched, boolean notifyUpdated) {
        // Faces at chunk borders change too, so the axis neighbours must be re-meshed as well.
        Set<Vec3i> toMesh = new LinkedHashSet<>(touched);
        for (Vec3i c : touched) {
            toMesh.add(new Vec3i(c.x + 1, c.y, c.z));
            toMesh.add(new Vec3i(c.x - 1, c.y, c.z));
            toMesh.add(new Vec3i(c.x, c.y + 1, c.z));
            toMesh.add(new Vec3i(c.x, c.y - 1, c.z));
            toMesh.add(new Vec3i(c.x, c.y, c.z + 1));
            toMesh.add(new Vec3i(c.x, c.y, c.z - 1));
        }
        chunkManager.requestMeshChunksNoWait(toMesh, notifyUpdated);
    }

    /**
     * Reverts a whole chunk column to the terrain the generator produces : every edit the player made
     * anywhere in the column — blocks placed, blocks removed, at any height — is discarded at once.
     *
     * <p>A <b>column</b>, not a single 16³ chunk, because a column is the unit the rest of the game
     * already thinks in : the minimap marks edited columns, the far horizon samples columns, and the tree
     * scatter is per column. Resetting less would leave the column half-reverted and still flagged as
     * edited everywhere else.</p>
     *
     * <p>The blocks are not recomputed here : the caller hands over the pristine chunks, generated off the
     * update thread (see {@link ChunkManager#generatePristineChunk}), and this only installs them — an
     * array swap per chunk — then puts the column's overlay records back to their pristine state :</p>
     * <ul>
     *   <li>the persisted <b>modified</b> marker is dropped, so the minimap stops tinting the column;</li>
     *   <li>the transient <b>edited</b> marker is set, so the far relief re-measures the column when it
     *       leaves the loaded grid and drops any height override the edits had left;</li>
     *   <li>the far-tree billboards of the trees standing in the column are restored — they grow back
     *       with the terrain, so their distant silhouettes must come back too.</li>
     * </ul>
     *
     * <p><b>Deliberately not done :</b> no light flood and no liquid seeding across the column's border.
     * The pristine column is internally consistent (the generator computes its own sunlight), and the
     * neighbours keep theirs. What the player can still see afterwards is confined to a border they had
     * themselves edited through — sunlight that poured into a neighbouring chunk through a tunnel now
     * walled up again, for instance — and it heals as soon as that neighbour is paged out and back in.
     * Fixing it properly would mean re-flooding the light of a 16 x 160 x 16 border, which costs far more
     * than the artifact.</p>
     *
     * @param chunkX  the column's chunk X coordinate
     * @param chunkZ  the column's chunk Z coordinate
     * @param pristine the freshly generated chunks of the column, in any order
     * @return the locations actually reset (empty when none of them is paged in any more)
     */
    public Set<Vec3i> resetColumn(int chunkX, int chunkZ, Collection<Chunk> pristine) {
        Set<Vec3i> touched = new LinkedHashSet<>();
        for (Chunk chunk : pristine) {
            if (chunkManager.resetChunkData(chunk)) {
                touched.add(chunk.getLocation());
            }
        }

        if (touched.isEmpty()) {
            log.warn("Chunk column ({}, {}) is not loaded any more : nothing reset", chunkX, chunkZ);
            return Collections.emptySet();
        }

        if (worldEditOverlay != null && noiseGenerator != null) {
            worldEditOverlay.clearModifiedColumn(noiseGenerator.modifiedColumnKey(chunkX, chunkZ));
            worldEditOverlay.markColumnEdited(WorldEditOverlay.pack(chunkX, chunkZ));
            restoreTrees(chunkX, chunkZ);
        }

        requestRegionMesh(touched, false);

        log.info("Reset chunk column ({}, {}) : {} chunks", chunkX, chunkZ, touched.size());
        return touched;
    }

    /**
     * Puts back the far-horizon billboards of every procedural tree whose trunk stands in a reset chunk
     * column. Only the trunk column of a scattered tree yields a key ({@code -1} otherwise), and that test
     * is two hashes deep before it does any real work, so scanning the chunk's 256 columns actually costs
     * the handful of anchors it finds.
     */
    private void restoreTrees(int chunkX, int chunkZ) {
        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();
        int x0 = chunkX * chunkSize.x;
        int z0 = chunkZ * chunkSize.z;
        for (int z = z0; z < z0 + chunkSize.z; z++) {
            for (int x = x0; x < x0 + chunkSize.x; x++) {
                long cellKey = noiseGenerator.trunkAnchorCellKeyAt(x, z);
                if (cellKey != -1L) {
                    worldEditOverlay.restoreTree(cellKey);
                }
            }
        }
    }

    /**
     * Fills every <b>empty</b> cell of an inclusive box of block coordinates with {@code block}, in one
     * pass. Cells already holding something — terrain, buildings, water — are left exactly as they are,
     * so a fill drapes itself around what is already there instead of overwriting it.
     *
     * <p>The counterpart of {@link #removeRegion} and built the same way, for the same reason : one
     * sweep, one light flood, one overlay marker per column, one non-blocking re-mesh. The lighting is
     * the mirror image — a fill <b>blocks</b> the sky, so the flood is a sunlight <i>removal</i> seeded
     * from every filled cell (or a torchlight flood, when the block is itself an emitter).</p>
     *
     * <p>The block is placed as chosen : none of the single-block placement rules (orientation to the
     * player, slab stacking, waterlogging) apply, since there is no aimed face nor camera direction to
     * derive them from. The one exception is a <b>liquid source</b>, which fills as a body of liquid
     * rather than a stack of surfaces — see {@link #submergedVariantOf}. Cells whose chunk is not
     * currently paged in are skipped, like every other edit path.</p>
     *
     * @param min the inclusive lower corner, in block coordinates
     * @param max the inclusive upper corner, in block coordinates
     * @param block the block to place in every empty cell
     * @param playerBlockLocation the block the player stands in, or {@code null} for no protection : a
     *                            solid fill skips it and the cell above, so the player cannot entomb
     *                            themselves (the same guard the single-block placement applies)
     * @return the locations of the chunks that were actually modified (empty if nothing was placed)
     */
    public Set<Vec3i> fillRegion(Vec3i min, Vec3i max, Block block, Vec3i playerBlockLocation) {
        if (block == null || min.x > max.x || min.y > max.y || min.z > max.z) {
            return Collections.emptySet();
        }

        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();
        Set<Vec3i> touched = new LinkedHashSet<>();
        Set<Long> columns = new HashSet<>();
        List<Vector3f> filledCells = new ArrayList<>();
        float scale = BlocksConfig.getInstance().getBlockScale();
        boolean protectPlayer = block.isSolid() && playerBlockLocation != null;
        int filled = 0;

        // Filling with a liquid source builds a body of liquid, and one is not a stack of surfaces : only
        // the topmost cell of each column is a source (drawn as the familiar slightly-sunken surface),
        // everything under it is the brim-full variant. This is exactly how the generator lays out the
        // sea, and without it every cell would draw its own sunken top face — a stack of visible plates
        // inside what should be solid water.
        Block submerged = submergedVariantOf(block);
        int width = max.x - min.x + 1;
        boolean[] liquidAbove = new boolean[width * (max.z - min.z + 1)];
        primeLiquidAbove(liquidAbove, min, max, width);

        ChunkLookup lookup = new ChunkLookup(chunkSize);
        // Top-down, so each cell already knows what ended up above it.
        for (int y = max.y; y >= min.y; y--) {
            for (int z = min.z; z <= max.z; z++) {
                for (int x = min.x; x <= max.x; x++) {
                    int col = (z - min.z) * width + (x - min.x);
                    if (protectPlayer && x == playerBlockLocation.x && z == playerBlockLocation.z
                            && (y == playerBlockLocation.y || y == playerBlockLocation.y + 1)) {
                        liquidAbove[col] = false;
                        continue;
                    }
                    Chunk chunk = lookup.at(x, y, z);
                    if (chunk == null) {
                        liquidAbove[col] = false;
                        continue; // not paged in
                    }
                    Block existing = chunk.getBlock(lookup.localX, lookup.localY, lookup.localZ);
                    if (existing != null) {
                        // A fill never overwrites : the cell keeps whatever stood there, and tells the
                        // cell below whether it is submerged.
                        liquidAbove[col] = existing.getLiquidLevel() > 0;
                        continue;
                    }

                    Block placed = (submerged != null && liquidAbove[col]) ? submerged : block;
                    chunk.addBlock(lookup.localX, lookup.localY, lookup.localZ, placed);
                    liquidAbove[col] = placed.getLiquidLevel() > 0;
                    filled++;
                    touched.add(chunk.getLocation());
                    columns.add(WorldEditOverlay.pack(lookup.chunkX, lookup.chunkZ));
                    filledCells.add(new Vector3f((x + 0.5f) * scale, (y + 0.5f) * scale, (z + 0.5f) * scale));

                    if (noiseGenerator != null && isLog(placed.getType())) {
                        recordLogEdit(x, z, true);
                    }
                }
            }
        }

        if (filled == 0) {
            return Collections.emptySet();
        }

        if (chunkLightManager != null) {
            if (block.isTorchlight()) {
                touched.addAll(chunkLightManager.addTorchlight(filledCells, 15));
            } else {
                // Mirrors the single-block placement : the new blocks stop both the sky and any
                // torchlight that used to pass through their cells.
                touched.addAll(chunkLightManager.removeSunlight(filledCells));
                touched.addAll(chunkLightManager.removeTorchlight(filledCells));
            }
        }

        // A fill can dam or divert a flow it now borders : let the simulation re-evaluate the boundary.
        seedShellLiquid(min.x, min.y, min.z, max, lookup);

        finishRegionEdit(touched, columns);

        log.info("Filled {} cells with {} in region {}..{} : {} chunks touched, {} columns",
                filled, block.getName(), min, max, touched.size(), columns.size());
        return touched;
    }

    /**
     * The brim-full form of a liquid source — {@code water_source} to {@code water-liquid}, and likewise
     * for lava — used for the cells of a filled body that end up under its surface. Returns {@code null}
     * for anything that is not a liquid source, which is what turns the whole surface/submerged rule off
     * for ordinary blocks.
     */
    private static Block submergedVariantOf(Block block) {
        if (!block.isLiquidSource()) {
            return null;
        }
        return BlocksConfig.getInstance().getBlockRegistry()
                .get(BlockIds.getName(block.getType(), ShapeIds.LIQUID));
    }

    /**
     * Seeds the "is this column submerged" state with what already stands <b>one cell above the box</b>,
     * so filling under an existing lake produces full cells right up to the box's top face instead of a
     * stray surface layer buried in the water.
     */
    private void primeLiquidAbove(boolean[] liquidAbove, Vec3i min, Vec3i max, int width) {
        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();
        ChunkLookup probe = new ChunkLookup(chunkSize);
        for (int z = min.z; z <= max.z; z++) {
            for (int x = min.x; x <= max.x; x++) {
                Chunk chunk = probe.at(x, max.y + 1, z);
                if (chunk == null) {
                    continue;
                }
                Block above = chunk.getBlock(probe.localX, probe.localY, probe.localZ);
                liquidAbove[(z - min.z) * width + (x - min.x)] = above != null && above.getLiquidLevel() > 0;
            }
        }
    }

    /** Re-seeds the liquid flow from every cell of the box's one-cell shell that still holds liquid. */
    private void seedShellLiquid(int minX, int minY, int minZ, Vec3i max, ChunkLookup lookup) {
        if (chunkLiquidManager == null) {
            return;
        }
        for (int y = minY - 1; y <= max.y + 1; y++) {
            for (int z = minZ - 1; z <= max.z + 1; z++) {
                for (int x = minX - 1; x <= max.x + 1; x++) {
                    boolean shell = x < minX || x > max.x || y < minY || y > max.y || z < minZ || z > max.z;
                    if (!shell) {
                        continue;
                    }
                    Chunk chunk = lookup.at(x, y, z);
                    if (chunk != null) {
                        chunkLiquidManager.flowFrom(chunk,
                                new Vec3i(lookup.localX, lookup.localY, lookup.localZ));
                    }
                }
            }
        }
    }

    /**
     * Walks the carved box plus a one-cell shell, collecting every cell as a sunlight seed and, for the
     * shell cells only, re-seeding the liquid flow from any liquid still bordering the hole.
     */
    private void seedLightAndLiquid(int minX, int minY, int minZ, Vec3i max, ChunkLookup lookup,
                                    List<Vector3f> sunlightSeeds) {
        float scale = BlocksConfig.getInstance().getBlockScale();
        for (int y = minY - 1; y <= max.y + 1; y++) {
            for (int z = minZ - 1; z <= max.z + 1; z++) {
                for (int x = minX - 1; x <= max.x + 1; x++) {
                    boolean shell = x < minX || x > max.x || y < minY || y > max.y || z < minZ || z > max.z;
                    Chunk chunk = lookup.at(x, y, z);
                    if (chunk == null) {
                        continue;
                    }
                    sunlightSeeds.add(new Vector3f((x + 0.5f) * scale, (y + 0.5f) * scale, (z + 0.5f) * scale));
                    if (shell && chunkLiquidManager != null) {
                        chunkLiquidManager.flowFrom(chunk,
                                new Vec3i(lookup.localX, lookup.localY, lookup.localZ));
                    }
                }
            }
        }
    }

    /** A liquid cell captured during the sweep, so its flow can be receded once the sweep is done. */
    private static final class LiquidCell {
        private final Chunk chunk;
        private final Vec3i local;

        private LiquidCell(Chunk chunk, Vec3i local) {
            this.chunk = chunk;
            this.local = local;
        }
    }

    /**
     * Resolves block coordinates to (chunk, local coordinates) with the previous chunk memoised, and
     * with plain integer arithmetic rather than the {@code Vector3f}-based static helpers.
     *
     * <p>A region sweep visits tens of thousands of cells, and the general path would allocate a
     * {@code Vector3f} and two {@code Vec3i} for each of them while re-querying the chunk cache. The
     * sweep is contiguous along X, so consecutive cells nearly always land in the same chunk and the
     * memo hits.</p>
     */
    private final class ChunkLookup {
        private final Vec3i chunkSize;
        private int chunkX = Integer.MIN_VALUE;
        private int chunkY = Integer.MIN_VALUE;
        private int chunkZ = Integer.MIN_VALUE;
        private Chunk chunk;
        private int localX;
        private int localY;
        private int localZ;

        private ChunkLookup(Vec3i chunkSize) {
            this.chunkSize = chunkSize;
        }

        /**
         * Positions the lookup on a block cell and returns its chunk, or {@code null} when that chunk is
         * not paged in. On success {@code localX/Y/Z} hold the cell's coordinates inside the chunk.
         */
        private Chunk at(int x, int y, int z) {
            int cx = Math.floorDiv(x, chunkSize.x);
            int cy = Math.floorDiv(y, chunkSize.y);
            int cz = Math.floorDiv(z, chunkSize.z);
            if (cx != chunkX || cy != chunkY || cz != chunkZ) {
                chunkX = cx;
                chunkY = cy;
                chunkZ = cz;
                chunk = chunkManager.getChunk(new Vec3i(cx, cy, cz)).orElse(null);
            }
            localX = x - cx * chunkSize.x;
            localY = y - cy * chunkSize.y;
            localZ = z - cz * chunkSize.z;
            return chunk;
        }
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
        recordColumnEdit(cl.x, cl.z);

        if (block != null && isLog(block.getType())) {
            Vec3i bl = ChunkManager.getBlockLocation(location);
            recordLogEdit(bl.x, bl.z, added);
        }
    }

    /**
     * Marks one chunk column as edited in the far-horizon overlay. Split out of {@link #recordEdit} so
     * the bulk path can mark each distinct column <b>once</b> instead of once per removed block.
     * Both markers are idempotent set adds, but the key packing is not free at region scale.
     */
    private void recordColumnEdit(int chunkX, int chunkZ) {
        // Transient marker (raw key) : the far terrain refreshes this column's relief at unfetch.
        worldEditOverlay.markColumnEdited(WorldEditOverlay.pack(chunkX, chunkZ));
        // Persisted marker (canonical key) : any edit, terrain OR building, so the minimap can show
        // where the player has built. Cheap : a single set add, deduplicated, written once per column.
        worldEditOverlay.markModifiedColumn(noiseGenerator.modifiedColumnKey(chunkX, chunkZ));
    }

    /** Fells (or restores) the far-horizon tree billboard whose trunk stands at this block column. */
    private void recordLogEdit(int blockX, int blockZ, boolean added) {
        long cellKey = noiseGenerator.trunkAnchorCellKeyAt(blockX, blockZ);
        if (cellKey != -1L) {
            if (added) {
                worldEditOverlay.restoreTree(cellKey);
            } else {
                worldEditOverlay.removeTree(cellKey);
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
            log.debug("No cube block below rail");
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

    private static boolean isLiquid(Block block) {
        return block != null && block.getLiquidLevel() > 0;
    }

    private static boolean isLiquidSource(Block block) {
        return block != null && block.isLiquidSource();
    }

    /**
     * The liquid type a liquid-bearing block belongs to. A block is lava only when its own type is
     * lava (lava occupies pure cells, so it never rides a structural block) ; every other
     * liquid-bearing block — pure water or a structure logged with water — counts as water.
     */
    private static String liquidTypeOf(Block block) {
        return TypeIds.LAVA.equals(block.getType()) ? TypeIds.LAVA : TypeIds.WATER;
    }

    private static boolean isWaterSource(Block block) {
        return block != null && block.isLiquidSource() && TypeIds.WATER.equals(block.getType());
    }

    /**
     * True for a dry, non-full block that can be flooded (waterlogged) : any registered non-cube shape
     * carries the full range of liquid-level variants (see {@code IalonBlockCatalog}), so it can host a
     * source. Full cubes fill their cell and blocks flagged {@code LIQUID_DISABLED} never take liquid.
     */
    private static boolean canHoldLiquid(Block block) {
        return block != null
                && !ShapeIds.CUBE.equals(block.getShape())
                && block.getLiquidLevelId() != Block.LIQUID_DISABLED;
    }

    private Set<Vec3i> addBlockInEmptyNonWaterLocation(Block block, Chunk chunk, Vec3i blockLocationInsideChunk) {
        chunk.addBlock(blockLocationInsideChunk, block);
        if (isLiquidSource(block) && chunkLiquidManager != null) {
            chunkLiquidManager.addSource(chunk, blockLocationInsideChunk);
        }

        return updateChunksAfterAddBlock(block, chunk, blockLocationInsideChunk);
    }

    private Set<Vec3i> addBlockInWater(Block block, Block previousBlock, Chunk chunk, Vec3i blockLocationInsideChunk, Vector3f location) {
        String prevLiquidType = liquidTypeOf(previousBlock);

        if (isLiquidSource(block)) {
            // Placing a liquid source where another liquid already is : the two liquids are
            // incompatible, so a source of a different liquid is refused (no mixing).
            if (!liquidTypeOf(block).equals(prevLiquidType)) {
                log.debug("Incompatible liquids : cannot place {} source in {}", liquidTypeOf(block), prevLiquidType);
                return new LinkedHashSet<>();
            }
            return addSourceBlockInWaterLocation(block, previousBlock, chunk, blockLocationInsideChunk);
        }

        // Placing a non-liquid block where there is LAVA : lava occupies pure cells only (it does not
        // co-habit with structural blocks), so the lava is displaced — the block is placed dry and the
        // lava recedes.
        if (TypeIds.LAVA.equals(prevLiquidType)) {
            return addBlockDisplacingLiquid(block, previousBlock, chunk, blockLocationInsideChunk, location);
        }

        if (previousBlock.isLiquidSource()) {
            return addBlockInSourceWaterLocation(block, previousBlock, chunk, blockLocationInsideChunk);

        } else if (isLiquid(previousBlock)) {
            return addBlockInWaterLocation(block, previousBlock, chunk, blockLocationInsideChunk, location);

        } else {
            // There is already a block at this location
            log.debug("Existing block {} at location {} prevents adding the new block", previousBlock, location);
            return new LinkedHashSet<>();
        }
    }

    /**
     * Places a (non-liquid) block where lava currently is : lava does not co-habit with structural
     * blocks (pure-cell rule), so the lava block is overwritten (placed "dry") and the surrounding
     * lava is told to recede / re-flow. Mirrors how a cube dropped on a water source displaces it.
     */
    private Set<Vec3i> addBlockDisplacingLiquid(Block block, Block previousBlock, Chunk chunk, Vec3i blockLocationInsideChunk, Vector3f location) {
        chunk.addBlock(blockLocationInsideChunk, block);
        if (chunkLiquidManager != null) {
            chunkLiquidManager.unflow(chunk, blockLocationInsideChunk, previousBlock.getLiquidLevel());
            chunkLiquidManager.flowLiquid(location);
        }
        return updateChunksAfterAddBlock(block, chunk, blockLocationInsideChunk);
    }

    private Set<Vec3i> addSourceBlockInWaterLocation(Block block, Block previousBlock, Chunk chunk, Vec3i blockLocationInsideChunk) {
        // 3.1 Adding a source where there is already non-source liquid (of the SAME type — incompatible
        // types are refused upstream).
        if (!TypeIds.WATER.equals(previousBlock.getType()) && !TypeIds.LAVA.equals(previousBlock.getType())) {
            // 3.1.1 If a non liquid (structural) block exists there, log the source liquid into it
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
                chunkLiquidManager.unflow(chunk, blockLocationInsideChunk, previousBlock.getLiquidLevel());
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
                chunkLiquidManager.unflow(chunk, blockLocationInsideChunk, previousBlock.getLiquidLevel());
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

        // Auto-bridge a WATER source between two opposite water sources. Kept water-only : lava sources
        // do not auto-merge (the WATER_SOURCE block below would be wrong for lava anyway).
        if ((isWaterSource(south) && isWaterSource(north))
                || (isWaterSource(west) && isWaterSource(east))) {
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
