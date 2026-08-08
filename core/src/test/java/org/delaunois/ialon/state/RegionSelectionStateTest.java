package org.delaunois.ialon.state;

import com.simsilica.mathd.Vec3i;

import org.junit.jupiter.api.Test;

import static org.delaunois.ialon.state.RegionSelectionState.MAX_REGION_SIDE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The bulk-edit size cap : a selection never exceeds {@link RegionSelectionState#MAX_REGION_SIDE}
 * blocks per axis, and it is always the side <b>away from the first picked corner</b> that stops
 * growing — the anchor itself stays in the box, whichever direction the player aims in.
 */
class RegionSelectionStateTest {

    private static void clamp(Vec3i anchor, Vec3i min, Vec3i max) {
        RegionSelectionState.clampToMaxSide(anchor, min, max);
    }

    private static int side(int min, int max) {
        return max - min + 1;
    }

    @Test
    void leavesASmallBoxUntouched() {
        Vec3i anchor = new Vec3i(10, 20, 30);
        Vec3i min = new Vec3i(10, 20, 30);
        Vec3i max = new Vec3i(12, 25, 31);

        clamp(anchor, min, max);

        assertEquals(new Vec3i(10, 20, 30), min, "min must not move");
        assertEquals(new Vec3i(12, 25, 31), max, "max must not move");
    }

    @Test
    void leavesABoxExactlyAtTheLimitUntouched() {
        Vec3i anchor = new Vec3i(0, 0, 0);
        Vec3i min = new Vec3i(0, 0, 0);
        Vec3i max = new Vec3i(MAX_REGION_SIDE - 1, MAX_REGION_SIDE - 1, MAX_REGION_SIDE - 1);

        clamp(anchor, min, max);

        assertEquals(new Vec3i(0, 0, 0), min);
        assertEquals(new Vec3i(MAX_REGION_SIDE - 1, MAX_REGION_SIDE - 1, MAX_REGION_SIDE - 1), max);
    }

    @Test
    void clampsTheFarSideWhenTheAnchorIsTheLowerCorner() {
        // The player picked the low corner and aimed far away in +X/+Y/+Z.
        Vec3i anchor = new Vec3i(5, 5, 5);
        Vec3i min = new Vec3i(5, 5, 5);
        Vec3i max = new Vec3i(200, 100, 60);

        clamp(anchor, min, max);

        assertEquals(new Vec3i(5, 5, 5), min, "the anchor corner stays put");
        assertEquals(MAX_REGION_SIDE, side(min.x, max.x));
        assertEquals(MAX_REGION_SIDE, side(min.y, max.y));
        assertEquals(MAX_REGION_SIDE, side(min.z, max.z));
    }

    @Test
    void clampsTheFarSideWhenTheAnchorIsTheUpperCorner() {
        // Same, aiming in -X/-Y/-Z : now it is min that has to move.
        Vec3i anchor = new Vec3i(100, 100, 100);
        Vec3i min = new Vec3i(0, 0, 0);
        Vec3i max = new Vec3i(100, 100, 100);

        clamp(anchor, min, max);

        assertEquals(new Vec3i(100, 100, 100), max, "the anchor corner stays put");
        assertEquals(MAX_REGION_SIDE, side(min.x, max.x));
        assertEquals(MAX_REGION_SIDE, side(min.y, max.y));
        assertEquals(MAX_REGION_SIDE, side(min.z, max.z));
    }

    @Test
    void clampsEachAxisIndependently() {
        // Only X is too long : Y and Z must be left exactly as they are.
        Vec3i anchor = new Vec3i(0, 3, 7);
        Vec3i min = new Vec3i(0, 3, 7);
        Vec3i max = new Vec3i(80, 9, 8);

        clamp(anchor, min, max);

        assertEquals(MAX_REGION_SIDE, side(min.x, max.x), "X is capped");
        assertEquals(3, min.y);
        assertEquals(9, max.y, "Y was within the limit");
        assertEquals(7, min.z);
        assertEquals(8, max.z, "Z was within the limit");
    }

    @Test
    void keepsTheAnchorInsideWhenItSitsBetweenTheCorners() {
        // The height point can push the box past the anchor on both sides at once. Whatever gets
        // trimmed, the anchor must remain part of the selection.
        Vec3i anchor = new Vec3i(0, 50, 0);
        Vec3i min = new Vec3i(0, 0, 0);
        Vec3i max = new Vec3i(0, 100, 0);

        clamp(anchor, min, max);

        assertEquals(MAX_REGION_SIDE, side(min.y, max.y));
        org.junit.jupiter.api.Assertions.assertTrue(min.y <= anchor.y && anchor.y <= max.y,
                "the anchor must stay inside the clamped box");
    }

    @Test
    void handlesNegativeCoordinates() {
        Vec3i anchor = new Vec3i(-5, -5, -5);
        Vec3i min = new Vec3i(-500, -5, -5);
        Vec3i max = new Vec3i(-5, -5, -5);

        clamp(anchor, min, max);

        assertEquals(new Vec3i(-5, -5, -5), max, "the anchor corner stays put");
        assertEquals(MAX_REGION_SIDE, side(min.x, max.x));
        assertEquals(-5 - MAX_REGION_SIDE + 1, min.x);
    }
}
