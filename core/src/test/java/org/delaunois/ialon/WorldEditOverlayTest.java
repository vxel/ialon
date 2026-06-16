package org.delaunois.ialon;

import org.delaunois.ialon.blocks.WorldEditOverlay;
import org.delaunois.ialon.blocks.generator.NoiseTerrainGenerator;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the world-edit overlay primitives and the generator hooks that make a felled tree stop
 * being regenerated as a far-horizon billboard. These paths are pure functions of the seed +
 * coordinates (no chunks, no atlas), so the generator can be exercised standalone.
 */
class WorldEditOverlayTest {

    private static final long SEED = 2L;
    private static final float WATER_HEIGHT = 30f;
    private static final int WORLD_HEIGHT = 112;
    private static final float WORLD_SIZE = 4096f;

    private static final int CELL = 4; // NoiseTerrainGenerator.TREE_CELL_SIZE

    private NoiseTerrainGenerator generator() {
        return new NoiseTerrainGenerator(SEED, WATER_HEIGHT, WORLD_HEIGHT, WORLD_SIZE);
    }

    /** Collects the (x, z) of every far-tree anchor the generator scatters in the square region. */
    private List<int[]> anchors(NoiseTerrainGenerator gen, int min, int max) {
        List<int[]> out = new ArrayList<>();
        gen.forEachTreeAnchor(min, max, min, max, (wx, wz, gy, species, height) ->
                out.add(new int[]{Math.round(wx), Math.round(wz)}));
        return out;
    }

    @Test
    void packRoundTripsIncludingNegatives() {
        long key = WorldEditOverlay.pack(-1234, 5678);
        assertEquals(-1234, WorldEditOverlay.unpackX(key));
        assertEquals(5678, WorldEditOverlay.unpackZ(key));
    }

    @Test
    void columnKeyWrapsToWorldPeriod() {
        NoiseTerrainGenerator gen = generator();
        // Two columns a whole world period apart canonicalise to the same key (torus consistency).
        assertEquals(gen.columnKey(10, 20), gen.columnKey(10 + (int) WORLD_SIZE, 20 - (int) WORLD_SIZE));
    }

    @Test
    void trunkAnchorDetectedOnlyAtTheActualTrunkColumn() {
        NoiseTerrainGenerator gen = generator();
        List<int[]> found = anchors(gen, 0, 1024);
        assertFalse(found.isEmpty(), "expected the default world to scatter trees in this region");

        int[] a = found.get(0);
        long key = gen.trunkAnchorCellKeyAt(a[0], a[1]);
        assertNotEquals(-1L, key, "the exact trunk column must resolve to its scatter cell");
        assertEquals(gen.treeCellKey(a[0], a[1]), key, "trunk key must match the cell key used by the filter");

        // A column two cells away is (overwhelmingly) not this tree's jittered trunk -> no match.
        assertEquals(-1L, gen.trunkAnchorCellKeyAt(a[0] + 2 * CELL, a[1]));
    }

    @Test
    void removedTreeIsSkippedByForEachTreeAnchor() {
        NoiseTerrainGenerator gen = generator();
        List<int[]> before = anchors(gen, 0, 1024);
        int[] victim = before.get(0);

        WorldEditOverlay overlay = new WorldEditOverlay();
        overlay.removeTree(gen.trunkAnchorCellKeyAt(victim[0], victim[1]));
        gen.setWorldEditOverlay(overlay);

        for (int[] a : anchors(gen, 0, 1024)) {
            assertFalse(a[0] == victim[0] && a[1] == victim[1],
                    "the felled tree must no longer be emitted as a far billboard");
        }
        assertTrue(overlay.isDirty());
        assertEquals(1, overlay.getTreeVersion());
    }

    @Test
    void dirtyColumnsAreDrainedOnce() {
        WorldEditOverlay overlay = new WorldEditOverlay();
        assertFalse(overlay.hasDirtyColumns());
        overlay.addDirtyColumn(WorldEditOverlay.pack(100, 200));
        overlay.addDirtyColumn(WorldEditOverlay.pack(100, 200)); // duplicate collapses
        overlay.addDirtyColumn(WorldEditOverlay.pack(-5, 7));
        assertTrue(overlay.hasDirtyColumns());

        long[] drained = overlay.pollDirtyColumns();
        assertEquals(2, drained.length);
        assertFalse(overlay.hasDirtyColumns(), "draining must clear the queue");
        assertEquals(0, overlay.pollDirtyColumns().length);
    }

    @Test
    void heightOverridePutThenClearWithNaN() {
        WorldEditOverlay overlay = new WorldEditOverlay();
        long key = WorldEditOverlay.pack(48, 64);
        overlay.putHeight(key, 42f);
        assertEquals(42f, overlay.getHeightOverrides().get(key));
        assertTrue(overlay.isDirty());

        overlay.putHeight(key, Float.NaN); // NaN removes the override (terrain restored to procedural)
        assertFalse(overlay.getHeightOverrides().containsKey(key));
    }
}
