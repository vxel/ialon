package org.delaunois.ialon.state;

import com.jme3.scene.VertexBuffer;

import org.delaunois.ialon.state.NearTerrainClipmap.Level;
import org.junit.jupiter.api.Test;

import java.nio.ShortBuffer;

import static org.delaunois.ialon.state.NearTerrainClipmap.COARSE_GRID;
import static org.delaunois.ialon.state.NearTerrainClipmap.COARSE_HALF;
import static org.delaunois.ialon.state.NearTerrainClipmap.COARSE_STEP;
import static org.delaunois.ialon.state.NearTerrainClipmap.FINE_GRID;
import static org.delaunois.ialon.state.NearTerrainClipmap.FINE_HALF;
import static org.delaunois.ialon.state.NearTerrainClipmap.FINE_STEP;
import static org.delaunois.ialon.state.NearTerrainClipmap.HANDOVER_OVERLAP;
import static org.delaunois.ialon.state.NearTerrainClipmap.MORPH_END;
import static org.delaunois.ialon.state.NearTerrainClipmap.MORPH_START;
import static org.delaunois.ialon.state.NearTerrainClipmap.OUTER_RADIUS;
import static org.delaunois.ialon.state.NearTerrainClipmap.SNAP_STEP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the geometry of the near-relief clipmap — the part that, if it drifts, reopens exactly the
 * artifact the clipmap was built to close.
 * <p>
 * A clipmap draws one surface with two different vertex densities, so it has two places where the relief
 * can crack open :
 * <ul>
 *   <li>the <b>internal</b> seam, where the fine level's border meets the coarse level's hole. The fine
 *       level puts a vertex halfway along every coarse edge ; unless that vertex is pushed back onto the
 *       coarse edge, it is a T-junction and the surface splits. This holds only because the two lattices
 *       are aligned and the fine border is linearised — asserted below ; and</li>
 *   <li>the <b>handover</b> to the far {@code TerrainQuad}, which is a fragment discard at a fixed
 *       camera-relative radius. It cannot leave a hole as long as the coarse geometry always reaches past
 *       that radius, however far behind the player a rebuild is lagging.</li>
 * </ul>
 * These are pure geometry, so they are checked here rather than in a screenshot : a crack is one pixel
 * wide until the player stands in the wrong place.
 */
class NearTerrainClipmapTest {

    /**
     * The arithmetic the whole stitching rests on. Each of these can be broken by a well-meaning tweak of
     * one constant (a finer step, a wider ring, a cheaper snap), and nothing else would notice.
     */
    @Test
    void latticeConstantsSatisfyTheStitchingInvariants() {
        assertEquals(2f * FINE_STEP, COARSE_STEP, 1e-6f,
                "the coarse step must be exactly twice the fine one, or the levels share no vertex at all");

        assertEquals(0f, FINE_HALF % COARSE_STEP, 1e-6f,
                "the fine footprint must end on the coarse lattice, or the hole cannot be cut on a vertex");

        // Odd vertex counts -> the last index (grid - 1) is even, so the four CORNERS of the fine border
        // are shared vertices and not interpolated ones.
        assertEquals(1, FINE_GRID % 2, "fine grid must be odd");
        assertEquals(1, COARSE_GRID % 2, "coarse grid must be odd");

        // Both levels are drawn with 16-bit indices.
        assertTrue(FINE_GRID * FINE_GRID <= 65536, "fine level exceeds the 16-bit index range");
        assertTrue(COARSE_GRID * COARSE_GRID <= 65536, "coarse level exceeds the 16-bit index range");

        // Snap alignment : a multiple of the coarse step keeps the two levels on the same lattice wherever
        // the player stands, and a multiple of 16 keeps them on the far quad's lattice too (which is what
        // lets the player-edit overrides, stored on that lattice, be interpolated here without a shift).
        assertEquals(0f, SNAP_STEP % COARSE_STEP, 1e-6f, "the snap must land on the coarse lattice");
        assertEquals(0f, SNAP_STEP % 16f, 1e-6f, "the snap must land on the far quad's 16-unit lattice");

        // Rebuild-lag margin : the clipmap is translated to the snap point, so the camera can be up to half
        // a snap step away from it, plus a whole snap step for each frame the pending rebuild has not been
        // swapped in. Two full snap steps of slack means the geometry still reaches the handover radius
        // even if a sampling pass is late.
        assertTrue(COARSE_HALF - OUTER_RADIUS >= 2f * SNAP_STEP,
                "not enough coarse geometry beyond the handover radius (" + (COARSE_HALF - OUTER_RADIUS)
                        + " units) to survive a lagging rebuild");

        // The convergence band must not reach back into the fine level, or it would move the very border
        // vertices the fine/coarse stitch depends on.
        assertTrue(MORPH_START > FINE_HALF, "the morph band overlaps the fine level");
        assertTrue(MORPH_END > MORPH_START, "empty morph band");

        // The overlap with the far quad is only safe where the clipmap has already converged onto the
        // quad's surface — otherwise two DIFFERENT surfaces are drawn on top of each other and z-fight.
        // Worst case, the camera sits half a snap step off the snap point with a snap step of rebuild lag.
        assertTrue(HANDOVER_OVERLAP > 0f, "no overlap : a residual surface mismatch becomes a visible crack");
        assertTrue(OUTER_RADIUS - HANDOVER_OVERLAP - 1.5f * SNAP_STEP >= MORPH_END,
                "the overlap band starts before the clipmap has converged on the far quad");
    }

    /**
     * The convergence onto the far quad's lattice. Two properties matter : it must be EXACT once past
     * {@link NearTerrainClipmap#MORPH_END} (that is what makes the overlap with the quad a tie between two
     * coincident surfaces rather than a z-fight), and it must leave the lattice vertices themselves alone
     * (they already carry the quad's height, and they are what the in-place single pass reads).
     */
    @Test
    void theOuterBandConvergesExactlyOntoTheQuadLattice() {
        Level level = new Level("test-morph", COARSE_GRID, COARSE_STEP, 0);
        int n = level.grid;
        int stride = level.heightStride;
        float half = (n - 1) / 2f;
        float[] raw = fillWithRelief(level);

        NearTerrainClipmap.morphOntoQuadLattice(level);

        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int at = (j + 1) * stride + i + 1;
                float cheb = Math.max(Math.abs((i - half) * COARSE_STEP), Math.abs((j - half) * COARSE_STEP));
                boolean oddX = (i & 1) == 1;
                boolean oddZ = (j & 1) == 1;
                String where = "(" + i + "," + j + ") at " + cheb;

                if (!oddX && !oddZ) {
                    assertEquals(raw[at], level.heights[at], 1e-6f, "lattice vertex " + where + " was moved");
                } else if (cheb <= MORPH_START) {
                    assertEquals(raw[at], level.heights[at], 1e-6f, "vertex " + where + " moved inside the band");
                } else if (cheb >= MORPH_END) {
                    float expected;
                    if (!oddZ) {
                        expected = 0.5f * (raw[at - 1] + raw[at + 1]);
                    } else if (!oddX) {
                        expected = 0.5f * (raw[at - stride] + raw[at + stride]);
                    } else {
                        expected = 0.25f * (raw[at - stride - 1] + raw[at - stride + 1]
                                + raw[at + stride - 1] + raw[at + stride + 1]);
                    }
                    assertEquals(expected, level.heights[at], 1e-5f,
                            "vertex " + where + " has not converged onto the quad's lattice");
                }
            }
        }
    }

    /**
     * The stitch itself : after it, the fine level's border is the straight polyline the coarse level
     * draws along the same line. The even vertices are the ones the two levels share, so they must come
     * out of it untouched — they carry the true sampled height, and moving them would move the seam
     * instead of closing it.
     */
    @Test
    void stitchLinearisesTheOddBorderVerticesAndLeavesTheSharedOnesAlone() {
        Level level = new Level("test-fine", FINE_GRID, FINE_STEP, 0);
        int n = level.grid;
        int stride = level.heightStride;
        float[] raw = fillWithRelief(level);

        NearTerrainClipmap.stitchBorder(level);

        for (int k = 0; k < n; k++) {
            assertBorder(level, raw, k, 0);          // top edge
            assertBorder(level, raw, k, n - 1);      // bottom edge
            assertBorder(level, raw, 0, k);          // left edge
            assertBorder(level, raw, n - 1, k);      // right edge
        }

        // The interior is the real relief and must not be touched at all.
        for (int j = 1; j < n - 1; j++) {
            for (int i = 1; i < n - 1; i++) {
                int at = (j + 1) * stride + i + 1;
                assertEquals(raw[at], level.heights[at], 1e-6f, "interior vertex (" + i + "," + j + ") moved");
            }
        }
    }

    /** Asserts one border vertex : even = untouched sample, odd = midpoint of its two neighbours. */
    private static void assertBorder(Level level, float[] raw, int i, int j) {
        int n = level.grid;
        int stride = level.heightStride;
        boolean onBorder = i == 0 || j == 0 || i == n - 1 || j == n - 1;
        if (!onBorder) {
            return;
        }
        int at = (j + 1) * stride + i + 1;
        // Which way the edge runs : along X on the top/bottom edges, along Z on the left/right ones.
        boolean alongX = j == 0 || j == n - 1;
        int index = alongX ? i : j;
        int neighbourOffset = alongX ? 1 : stride;
        if (index % 2 == 0) {
            assertEquals(raw[at], level.heights[at], 1e-6f,
                    "shared border vertex (" + i + "," + j + ") must keep its sampled height");
        } else {
            assertEquals(0.5f * (raw[at - neighbourOffset] + raw[at + neighbourOffset]), level.heights[at], 1e-5f,
                    "border vertex (" + i + "," + j + ") is not on the coarse edge : T-junction");
        }
    }

    /**
     * The hole must expose exactly the fine level's footprint — no more (the fine level would not reach)
     * and no less (the two levels would overlap and z-fight). Checked through the index buffer, since that
     * is what actually decides which quads are drawn.
     */
    @Test
    void theCoarseHoleMatchesTheFineFootprintExactly() {
        int holeCells = (int) (FINE_HALF / COARSE_STEP);
        Level coarse = new Level("test-coarse", COARSE_GRID, COARSE_STEP, holeCells);
        int cells = COARSE_GRID - 1;
        int lo = cells / 2 - holeCells;
        int hi = cells / 2 + holeCells;

        ShortBuffer indices = (ShortBuffer) coarse.getGeometry().getMesh()
                .getBuffer(VertexBuffer.Type.Index).getData();
        assertEquals((cells * cells - (2 * holeCells) * (2 * holeCells)) * 6, indices.limit(),
                "wrong quad count around the hole");

        boolean[] used = new boolean[COARSE_GRID * COARSE_GRID];
        for (int k = 0; k < indices.limit(); k++) {
            used[indices.get(k) & 0xFFFF] = true;
        }

        for (int j = 0; j < COARSE_GRID; j++) {
            for (int i = 0; i < COARSE_GRID; i++) {
                boolean strictlyInside = i > lo && i < hi && j > lo && j < hi;
                if (strictlyInside) {
                    assertFalse(used[j * COARSE_GRID + i],
                            "vertex (" + i + "," + j + ") is inside the hole but still drawn : overlap");
                } else {
                    assertTrue(used[j * COARSE_GRID + i],
                            "vertex (" + i + "," + j + ") is not drawn : gap in the coarse ring");
                }
            }
        }

        // ... and the hole's border sits exactly on the fine level's outer edge, in world units.
        assertEquals(FINE_HALF, (cells / 2f - lo) * COARSE_STEP, 1e-6f);
    }

    /**
     * Positions and normals derived from the sampled heights. A flat field is the one case with an exact
     * expected answer, and it catches a swapped axis or an unnormalised normal — which would light the
     * near relief differently from the far one and make the handover visible even with the geometry right.
     */
    @Test
    void aFlatFieldGivesTheLatticePositionsAndStraightUpNormals() {
        Level level = new Level("test-flat", 9, FINE_STEP, 0);
        java.util.Arrays.fill(level.heights, 12.5f);

        NearTerrainClipmap.fillVertices(level, false);

        int n = 9;
        float half = (n - 1) / 2f;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int at = (j * n + i) * 3;
                assertEquals((i - half) * FINE_STEP, level.positions[at], 1e-6f);
                assertEquals(12.5f, level.positions[at + 1], 1e-6f);
                assertEquals((j - half) * FINE_STEP, level.positions[at + 2], 1e-6f);
                assertEquals(0f, level.normals[at], 1e-6f);
                assertEquals(1f, level.normals[at + 1], 1e-6f);
                assertEquals(0f, level.normals[at + 2], 1e-6f);
            }
        }
    }

    /** A constant slope in X must come out as a constant, correctly signed and unit-length normal. */
    @Test
    void aSlopeGivesTheNormalThatLeansAwayFromIt() {
        Level level = new Level("test-slope", 9, FINE_STEP, 0);
        int stride = level.heightStride;
        for (int j = 0; j < stride; j++) {
            for (int i = 0; i < stride; i++) {
                level.heights[j * stride + i] = i * FINE_STEP; // rises 1 unit per unit of +X
            }
        }

        NearTerrainClipmap.fillVertices(level, false);

        for (int v = 0; v < 9 * 9; v++) {
            int at = v * 3;
            float x = level.normals[at];
            float y = level.normals[at + 1];
            float z = level.normals[at + 2];
            assertEquals(1f, x * x + y * y + z * z, 1e-5f, "normal is not unit length");
            assertTrue(x < 0f, "an uphill-toward-+X slope must tilt its normal toward -X");
            assertEquals(0f, z, 1e-6f);
            assertEquals(-x, y, 1e-5f, "a 45 degree slope must split the normal evenly");
        }
    }

    /**
     * The shading half of the convergence. Getting the heights onto the quad's surface but keeping an
     * 8-unit normal is what mottled the overlap band : both meshes are drawn there, the higher one wins per
     * pixel, and the strata slope term turns a normal disagreement into up to 26 world units of snow-line
     * shift — so the two candidates come out one rock, one snow. Past MORPH_END the normal must therefore
     * be the gradient over a full 16-unit lattice step, which is the quad's own.
     */
    @Test
    void theOuterBandIsShadedWithTheQuadsNormals() {
        Level level = new Level("test-normals", COARSE_GRID, COARSE_STEP, 0);
        int n = level.grid;
        int stride = level.heightStride;
        float half = (n - 1) / 2f;
        fillWithRelief(level);
        float[] h = level.heights;

        NearTerrainClipmap.fillVertices(level, true);

        int checkedNear = 0;
        int checkedFar = 0;
        for (int j = 1; j < n - 1; j++) {
            for (int i = 1; i < n - 1; i++) {
                int hi = (j + 1) * stride + i + 1;
                int at = (j * n + i) * 3;
                float x = (i - half) * COARSE_STEP;
                float z = (j - half) * COARSE_STEP;
                float cheb = Math.max(Math.abs(x), Math.abs(z));

                float gx;
                float gz;
                if (cheb <= MORPH_START) {
                    gx = (h[hi - 1] - h[hi + 1]) / (2f * COARSE_STEP);
                    gz = (h[hi - stride] - h[hi + stride]) / (2f * COARSE_STEP);
                    checkedNear++;
                } else if (cheb >= MORPH_END) {
                    gx = (h[hi - 2] - h[hi + 2]) / (4f * COARSE_STEP);
                    gz = (h[hi - 2 * stride] - h[hi + 2 * stride]) / (4f * COARSE_STEP);
                    checkedFar++;
                } else {
                    continue; // inside the ramp : covered by the two ends
                }
                float len = (float) Math.sqrt(gx * gx + 1f + gz * gz);
                String where = "(" + i + "," + j + ") at " + cheb;
                assertEquals(gx / len, level.normals[at], 1e-5f, "normal X at " + where);
                assertEquals(1f / len, level.normals[at + 1], 1e-5f, "normal Y at " + where);
                assertEquals(gz / len, level.normals[at + 2], 1e-5f, "normal Z at " + where);
            }
        }
        assertTrue(checkedNear > 0 && checkedFar > 0, "the grid did not span both sides of the morph band");
    }

    @Test
    void snappingLandsOnTheSnapLatticeAndIsStable() {
        for (float w : new float[]{-5000f, -33f, -1f, 0f, 1f, 15.9f, 16.1f, 4321f}) {
            float s = NearTerrainClipmap.snap(w);
            assertEquals(0f, s % SNAP_STEP, 1e-6f, w + " snapped off the lattice, to " + s);
            assertTrue(Math.abs(w - s) <= SNAP_STEP / 2f + 1e-4f, w + " snapped too far, to " + s);
            assertEquals(s, NearTerrainClipmap.snap(s), 1e-6f, "snapping is not idempotent");
        }
    }

    /** Fills a level's height ring with a deterministic, decidedly non-linear relief and returns a copy. */
    private static float[] fillWithRelief(Level level) {
        int stride = level.heightStride;
        for (int j = 0; j < stride; j++) {
            for (int i = 0; i < stride; i++) {
                level.heights[j * stride + i] =
                        (float) (30.0 + 12.0 * Math.sin(i * 0.37) + 7.0 * Math.cos(j * 0.53));
            }
        }
        return level.heights.clone();
    }
}
