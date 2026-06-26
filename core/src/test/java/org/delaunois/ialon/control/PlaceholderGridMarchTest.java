package org.delaunois.ialon.control;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.collision.CollisionResult;
import com.jme3.math.Vector3f;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.IalonInitializer;
import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlockIds;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.ChunkManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit test for {@link PlaceholderControl#marchGrid}, the voxel grid-march (DDA) that replaced the
 * per-triangle {@code chunkNode.collideWith} block picking (which built each crossed chunk's BIHTree on
 * the main thread, costing 20-60 ms updateLogicalState hitches while flying). The march must :
 * <ul>
 *   <li>stop on the first non-liquid cell the ray enters (cell-granular, Minecraft-style picking),</li>
 *   <li>pass straight through liquid cells (water/lava) so the ray reaches the solid behind them,</li>
 *   <li>report the entered face as the contact normal so adjacent placement targets the empty cell in
 *       front (the "add" location),</li>
 *   <li>return {@code null} when nothing solid is within reach.</li>
 * </ul>
 */
class PlaceholderGridMarchTest {

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    private static float scale;
    private static Block rock;
    private static Block water;

    @BeforeAll
    static void setUp() {
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), new IalonConfig());
        BlocksConfig config = BlocksConfig.getInstance();
        scale = config.getBlockScale();
        rock = config.getBlockRegistry().get(BlockIds.ROCK);
        water = config.getBlockRegistry().get(BlockIds.WATER_SOURCE);
        assertNotNull(rock, "test requires the rock block");
        assertNotNull(water, "test requires the water source block");
    }

    /** A block lookup backed by an explicit cell -> block map, resolving the cell the same way the
     * production march does (so the test exercises the real coordinate maths). */
    private static Function<Vector3f, Block> world(Map<Vec3i, Block> cells) {
        return point -> cells.get(ChunkManager.getBlockLocation(point));
    }

    private static CollisionResult march(Function<Vector3f, Block> world, Vector3f origin, Vector3f dir) {
        return PlaceholderControl.marchGrid(world, origin, dir, 20f, scale,
                new Vector3f(), new Vector3f(), new CollisionResult());
    }

    /** A ray from the centre of cell (0,0,0) along +Z, with a rock wall 5 cells ahead. */
    private static Vector3f originAtCell0() {
        return new Vector3f(0.5f * scale, 0.5f * scale, 0.5f * scale);
    }

    @Test
    void hitsFirstSolidCellAndReportsEntryFace() {
        Map<Vec3i, Block> cells = new HashMap<>();
        cells.put(new Vec3i(0, 0, 5), rock);

        CollisionResult result = march(world(cells), originAtCell0(), new Vector3f(0, 0, 1));

        assertNotNull(result, "the ray must hit the rock wall");
        assertEquals(new Vec3i(0, 0, 5), ChunkManager.getBlockLocation(result), "targeted (remove) cell");
        assertEquals(new Vec3i(0, 0, 4), ChunkManager.getNeighbourBlockLocation(result), "adjacent (add) cell");
        // Entered through the -Z face : the contact normal points back toward the camera.
        assertEquals(new Vector3f(0, 0, -1), result.getContactNormal(), "entry-face normal");
    }

    @Test
    void passesThroughLiquidToTheSolidBehind() {
        Map<Vec3i, Block> cells = new HashMap<>();
        cells.put(new Vec3i(0, 0, 2), water);
        cells.put(new Vec3i(0, 0, 3), water);
        cells.put(new Vec3i(0, 0, 5), rock);

        CollisionResult result = march(world(cells), originAtCell0(), new Vector3f(0, 0, 1));

        assertNotNull(result, "liquid must not stop the ray");
        assertEquals(new Vec3i(0, 0, 5), ChunkManager.getBlockLocation(result),
                "the ray must skip the water cells and stop on the rock");
    }

    @Test
    void returnsNullWhenNothingSolidWithinReach() {
        Map<Vec3i, Block> cells = new HashMap<>();
        cells.put(new Vec3i(0, 0, 2), water);
        cells.put(new Vec3i(0, 0, 3), water);

        CollisionResult result = march(world(cells), originAtCell0(), new Vector3f(0, 0, 1));

        assertNull(result, "only liquid (and air) within reach : nothing targetable");
    }
}
