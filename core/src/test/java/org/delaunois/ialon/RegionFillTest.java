package org.delaunois.ialon;

import com.jme3.math.Vector3f;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlockIds;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.ShapeIds;
import org.delaunois.ialon.blocks.TypeIds;
import org.delaunois.ialon.blocks.shapes.Liquid;
import org.delaunois.ialon.support.BaseSceneryTest;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bulk region fill ({@code WorldManager.fillRegion}) : every empty cell of the box receives the block,
 * whatever already stood there is preserved, the sunlight below is shut out, and the player is never
 * entombed by a solid fill.
 */
class RegionFillTest extends BaseSceneryTest {

    // A fresh, empty world (no saved chunk under this scenery path -> EmptyGenerator).
    private static final String UT_PATH = "regionfill-ut";

    private static Block block(String name) {
        return BlocksConfig.getInstance().getBlockRegistry().get(name);
    }

    @Test
    void fillsEveryEmptyCellOfTheRegion() {
        init(UT_PATH);

        Set<Vec3i> touched = worldManager.fillRegion(
                new Vec3i(4, 4, 4), new Vec3i(7, 7, 7), block(BlockIds.GRASS), null);

        assertFalse(touched.isEmpty(), "the edited chunk must be reported as touched");
        for (int y = 4; y <= 7; y++) {
            for (int z = 4; z <= 7; z++) {
                for (int x = 4; x <= 7; x++) {
                    assertNotNull(blockAt(x, y, z), "cell (" + x + "," + y + "," + z + ") must be filled");
                }
            }
        }
    }

    @Test
    void neverOverwritesAnExistingBlock() {
        init(UT_PATH);

        addBlock(BlockIds.OAK_LOG, 5, 5, 5);
        worldManager.fillRegion(new Vec3i(4, 4, 4), new Vec3i(7, 7, 7), block(BlockIds.GRASS), null);

        Block kept = blockAt(5, 5, 5);
        assertNotNull(kept);
        assertEquals(BlockIds.OAK_LOG, kept.getName(), "the existing block must be preserved");
        assertEquals(BlockIds.GRASS, blockAt(6, 5, 5).getName(), "its empty neighbour must be filled");
    }

    @Test
    void leavesCellsOutsideTheRegionEmpty() {
        init(UT_PATH);

        worldManager.fillRegion(new Vec3i(4, 4, 4), new Vec3i(7, 7, 7), block(BlockIds.GRASS), null);

        assertNull(blockAt(3, 5, 5), "the cell just outside on -X stays empty");
        assertNull(blockAt(8, 5, 5), "the cell just outside on +X stays empty");
        assertNull(blockAt(5, 3, 5), "the cell just below stays empty");
        assertNull(blockAt(5, 8, 5), "the cell just above stays empty");
    }

    @Test
    void returnsNothingWhenTheRegionIsAlreadyFull() {
        init(UT_PATH);

        worldManager.fillRegion(new Vec3i(4, 4, 4), new Vec3i(5, 5, 5), block(BlockIds.GRASS), null);
        Set<Vec3i> touched = worldManager.fillRegion(
                new Vec3i(4, 4, 4), new Vec3i(5, 5, 5), block(BlockIds.GRASS), null);

        assertTrue(touched.isEmpty(), "there was no empty cell left to fill");
    }

    @Test
    void returnsNothingWithoutABlock() {
        init(UT_PATH);

        Set<Vec3i> touched = worldManager.fillRegion(new Vec3i(4, 4, 4), new Vec3i(5, 5, 5), null, null);

        assertTrue(touched.isEmpty(), "no block selected, nothing to place");
        assertNull(blockAt(4, 4, 4));
    }

    @Test
    void shutsOutTheSunlightUnderTheFill() {
        init(UT_PATH);

        assertEquals(15, worldManager.getSunlightLevel(new Vector3f(7, 5, 7)),
                "the cell starts under open sky");

        // A wide lid over it : the cell below must go dark, the sunlight removal having propagated.
        worldManager.fillRegion(new Vec3i(2, 6, 2), new Vec3i(13, 6, 13), block(BlockIds.GRASS), null);

        int shadowed = worldManager.getSunlightLevel(new Vector3f(7, 5, 7));
        assertTrue(shadowed < 15, "the covered cell must be darkened, got " + shadowed);
    }

    @Test
    void doesNotEntombThePlayer() {
        init(UT_PATH);

        Vec3i player = new Vec3i(5, 5, 5);
        worldManager.fillRegion(new Vec3i(4, 4, 4), new Vec3i(7, 7, 7), block(BlockIds.GRASS), player);

        assertNull(blockAt(5, 5, 5), "the player's own cell stays free");
        assertNull(blockAt(5, 6, 5), "and so does the one above their head");
        assertNotNull(blockAt(5, 7, 5), "the rest of the column is filled");
        assertNotNull(blockAt(6, 5, 5), "as is the cell beside them");
    }

    @Test
    void fillsThePlayerCellWithANonSolidBlock() {
        init(UT_PATH);

        // The guard only exists to keep the player from being trapped : a block they can walk through
        // traps nobody, so it goes in like any other.
        Block plant = findNonSolidBlock();
        assertNotNull(plant, "test requires a registered non-solid block");

        Vec3i player = new Vec3i(5, 5, 5);
        worldManager.fillRegion(new Vec3i(5, 5, 5), new Vec3i(5, 6, 5), plant, player);

        assertNotNull(blockAt(5, 5, 5), "a non-solid block may share the player's cell");
    }

    /**
     * A body of water is not a stack of surfaces : only its topmost cell is the source block, whose
     * shape sinks slightly to draw the waterline. Everything under it must be the brim-full variant, or
     * every cell draws its own sunken top face and the water looks like stacked plates.
     */
    @Test
    void fillsWaterAsABodyNotAStackOfSurfaces() {
        init(UT_PATH);

        worldManager.fillRegion(new Vec3i(4, 4, 4), new Vec3i(5, 8, 5),
                block(BlockIds.WATER_SOURCE), null);

        Block surface = blockAt(4, 8, 4);
        assertNotNull(surface, "the top of the column must be filled");
        assertEquals(BlockIds.WATER_SOURCE, surface.getName(), "the top cell carries the waterline");

        for (int y = 4; y <= 7; y++) {
            Block under = blockAt(4, y, 4);
            assertNotNull(under, "cell at y=" + y + " must be filled");
            assertEquals(BlockIds.getName(TypeIds.WATER, ShapeIds.LIQUID), under.getName(),
                    "the submerged cell at y=" + y + " must be the brim-full variant");
            assertEquals(Liquid.LEVEL_MAX, under.getLiquidLevel(),
                    "a submerged cell is full to the brim");
        }
    }

    @Test
    void fillsWaterAsFullCellsRightUnderAnExistingWaterSurface() {
        init(UT_PATH);

        // An existing water cell sits directly above the box : nothing inside it is a surface.
        addBlock(BlockIds.WATER_SOURCE, 4, 7, 4);
        worldManager.fillRegion(new Vec3i(4, 4, 4), new Vec3i(4, 6, 4),
                block(BlockIds.WATER_SOURCE), null);

        Block top = blockAt(4, 6, 4);
        assertNotNull(top);
        assertEquals(BlockIds.getName(TypeIds.WATER, ShapeIds.LIQUID), top.getName(),
                "the box's top cell is submerged under the pre-existing water, so it is full too");
    }

    @Test
    void keepsTheSurfaceUnderASolidLid() {
        init(UT_PATH);

        // A stone lid is not liquid : the cell below it is still the top of the water body.
        addBlock(BlockIds.GRASS, 4, 7, 4);
        worldManager.fillRegion(new Vec3i(4, 4, 4), new Vec3i(4, 6, 4),
                block(BlockIds.WATER_SOURCE), null);

        Block top = blockAt(4, 6, 4);
        assertNotNull(top);
        assertEquals(BlockIds.WATER_SOURCE, top.getName(), "a solid lid does not submerge the cell below");
    }

    @Test
    void leavesNonLiquidBlocksUntouchedByTheSurfaceRule() {
        init(UT_PATH);

        worldManager.fillRegion(new Vec3i(4, 4, 4), new Vec3i(4, 6, 4), block(BlockIds.GRASS), null);

        for (int y = 4; y <= 6; y++) {
            assertEquals(BlockIds.GRASS, blockAt(4, y, 4).getName(),
                    "an ordinary block is placed as chosen at every height");
        }
    }

    private static Block findNonSolidBlock() {
        for (Block b : BlocksConfig.getInstance().getBlockRegistry().getAll()) {
            if (!b.isSolid() && b.getLiquidLevel() <= 0) {
                return b;
            }
        }
        return null;
    }

    private Block blockAt(int x, int y, int z) {
        return worldManager.getBlock(new Vector3f(x, y, z));
    }
}
