package org.delaunois.ialon.state;

import com.simsilica.mathd.Vec3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CreationCaptureStateTest {

    @Test
    void baseCornersFixTheFootprintAndHeightPointOnlyExtendsY() {
        Vec3i a = new Vec3i(2, 5, 3);
        Vec3i b = new Vec3i(6, 5, -1);
        // Height point far away in X/Z : must NOT widen the footprint, only the Y range.
        Vec3i height = new Vec3i(100, 9, 100);

        Vec3i min = new Vec3i();
        Vec3i max = new Vec3i();
        CreationCaptureState.computeVolume(a, b, height, min, max);

        // Footprint comes from a and b only.
        assertEquals(2, min.x);
        assertEquals(6, max.x);
        assertEquals(-1, min.z);
        assertEquals(3, max.z);
        // Y spans the base (5) and the height point (9).
        assertEquals(5, min.y);
        assertEquals(9, max.y);
    }

    @Test
    void heightPointBelowTheBaseExtendsDownward() {
        Vec3i a = new Vec3i(0, 10, 0);
        Vec3i b = new Vec3i(2, 10, 2);
        Vec3i height = new Vec3i(1, 4, 1);

        Vec3i min = new Vec3i();
        Vec3i max = new Vec3i();
        CreationCaptureState.computeVolume(a, b, height, min, max);

        assertEquals(4, min.y);
        assertEquals(10, max.y);
    }

    @Test
    void nullHeightPointKeepsTheBaseAabb() {
        Vec3i a = new Vec3i(1, 2, 3);
        Vec3i b = new Vec3i(4, 8, 0);

        Vec3i min = new Vec3i();
        Vec3i max = new Vec3i();
        CreationCaptureState.computeVolume(a, b, null, min, max);

        assertEquals(new Vec3i(1, 2, 0), min);
        assertEquals(new Vec3i(4, 8, 3), max);
    }

    @Test
    void coincidentCornersGiveASingleColumn() {
        Vec3i p = new Vec3i(3, 3, 3);
        Vec3i min = new Vec3i();
        Vec3i max = new Vec3i();
        CreationCaptureState.computeVolume(p, p, p, min, max);
        assertEquals(1, max.x - min.x + 1);
        assertEquals(1, max.y - min.y + 1);
        assertEquals(1, max.z - min.z + 1);
    }
}
