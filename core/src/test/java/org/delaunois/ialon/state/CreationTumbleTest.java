/**
 * Copyright (C) 2022 Cédric de Launois
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.delaunois.ialon.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;

import org.junit.jupiter.api.Test;

/**
 * Guards what the creation viewer's rotation promises : that it is <b>free</b> on every axis, and that the
 * two motions the player never asks for explicitly — the spin kept after a release, the turntable at rest —
 * start and stop when they should.
 *
 * <p>The freedom is the point of these tests. Storing a yaw/pitch pair and rebuilding the orientation from
 * them, which is the obvious implementation, turns the model about its <em>own</em> axes : at a quarter turn
 * of pitch the two axes coincide and a horizontal drag stops doing anything new (gimbal lock), and past it
 * the yaw is mirrored, so dragging right turns the model left. {@link #aDragAlwaysTurnsByTheAngleItIsWorth}
 * and {@link #theModelKeepsRespondingOnceUpsideDown} are the two that such an implementation fails.
 *
 * @author Cedric de Launois
 */
class CreationTumbleTest {

    /** A frame at 60 fps, the step every test drives the tumble with. */
    private static final float FRAME = 1f / 60f;

    private static final float EPSILON = 1e-3f;

    /** A drag over the whole panel width is worth one revolution. */
    private static final float FULL_TURN_DRAG = 1f;

    @Test
    void resetPointsTheModelAtItsStartingView() {
        CreationTumble tumble = new CreationTumble();
        tumble.reset(0, 0);
        assertQuaternionTurns(new Quaternion(), tumble.getRotation(), "reset(0, 0) is the identity");

        tumble.reset(FastMath.HALF_PI, 0);
        // A quarter turn about the world Y : the model's +Z face has come round to +X.
        assertVector(new Vector3f(1, 0, 0), tumble.getRotation().mult(Vector3f.UNIT_Z));
    }

    @Test
    void aFullDragTurnsTheModelOneRevolution() {
        CreationTumble tumble = tumbleAtRest();
        tumble.press();
        tumble.drag(FULL_TURN_DRAG, 0);
        tumble.update(FRAME);
        assertVector(Vector3f.UNIT_Z, tumble.getRotation().mult(Vector3f.UNIT_Z));

        tumble.drag(FULL_TURN_DRAG / 2f, 0);
        tumble.update(FRAME);
        assertVector(Vector3f.UNIT_Z.negate(), tumble.getRotation().mult(Vector3f.UNIT_Z));
    }

    /**
     * Dragging back along the same axis undoes the drag. Only along the <b>same axis</b> : a rotation about
     * Y followed by one about X is not the same as the two the other way round, so undoing a diagonal drag
     * with its exact opposite does not return the model to where it started. That is a property of turning
     * something in three dimensions, not a defect — and asserting otherwise is the trap here.
     */
    @Test
    void draggingBackAlongTheSameAxisUndoesTheDrag() {
        CreationTumble tumble = tumbleAtRest();
        tumble.press();
        tumble.drag(0.3f, 0);
        tumble.update(FRAME);
        tumble.drag(-0.3f, 0);
        tumble.update(FRAME);
        assertQuaternionTurns(new Quaternion(), tumble.getRotation(), "a horizontal drag and its opposite");

        tumble = tumbleAtRest();
        tumble.press();
        tumble.drag(0, 0.2f);
        tumble.update(FRAME);
        tumble.drag(0, -0.2f);
        tumble.update(FRAME);
        assertQuaternionTurns(new Quaternion(), tumble.getRotation(), "a vertical drag and its opposite");
    }

    /**
     * The model follows the finger : the face turned towards the player moves the way the finger does. Both
     * signs at once, because getting either backwards is the easiest mistake here and neither shows up in a
     * test that only measures angles.
     */
    @Test
    void theModelFollowsTheFinger() {
        CreationTumble tumble = tumbleAtRest();
        tumble.press();
        tumble.drag(0.05f, 0);
        tumble.update(FRAME);
        Vector3f front = tumble.getRotation().mult(Vector3f.UNIT_Z);
        assertTrue(front.x > 0.1f, "dragging right carries the near face right, was " + front);

        tumble = tumbleAtRest();
        tumble.press();
        tumble.drag(0, 0.05f);
        tumble.update(FRAME);
        front = tumble.getRotation().mult(Vector3f.UNIT_Z);
        assertTrue(front.y > 0.1f, "dragging up carries the near face up, was " + front);
    }

    /**
     * However the model is already turned, a horizontal drag turns it by exactly the angle that drag is
     * worth, about the world's vertical. No orientation is a dead spot — which is what a yaw/pitch pair
     * cannot promise, its horizontal drag being about the model's own vertical.
     */
    @Test
    void aDragAlwaysTurnsByTheAngleItIsWorth() {
        float[] pitches = {0f, 0.1f, 0.25f, 0.5f, 0.75f, 1.3f};
        for (float pitch : pitches) {
            CreationTumble tumble = tumbleAtRest();
            tumble.press();
            tumble.drag(0, pitch);
            tumble.update(FRAME);
            Quaternion before = tumble.getRotation().clone();

            tumble.drag(0.1f, 0);
            tumble.update(FRAME);
            Quaternion step = tumble.getRotation().mult(before.inverse());

            Vector3f axis = new Vector3f();
            float angle = step.toAngleAxis(axis);
            assertEquals(0.1f * FastMath.TWO_PI, angle, EPSILON,
                    "a 0.1 drag turns by a tenth of a revolution, pitched by " + pitch);
            assertVector(Vector3f.UNIT_Y, axis);
        }
    }

    /** Turned upside down, the model still answers a drag the same way — it is not mirrored, nor stuck. */
    @Test
    void theModelKeepsRespondingOnceUpsideDown() {
        CreationTumble tumble = tumbleAtRest();
        tumble.press();
        tumble.drag(0, 0.5f); // half a revolution of pitch : the model is on its head
        tumble.update(FRAME);
        assertVector(Vector3f.UNIT_Y.negate(), tumble.getRotation().mult(Vector3f.UNIT_Y));

        // Whichever part of the model has come round to face the player, a drag right still carries it
        // right — not left, which is what a mirrored yaw past the pole would do.
        Vector3f facing = tumble.getRotation().inverse().mult(Vector3f.UNIT_Z);
        tumble.drag(0.05f, 0);
        tumble.update(FRAME);
        Vector3f moved = tumble.getRotation().mult(facing);
        assertTrue(moved.x > 0.1f, "dragging right still carries the near face right, was " + moved);
    }

    @Test
    void theOrientationStaysAUnitRotation() {
        CreationTumble tumble = tumbleAtRest();
        tumble.press();
        // Deterministic pseudo-random walk : composing thousands of increments is where a missing
        // normalisation shows up, as a model that slowly grows or shrinks.
        float angle = 0.37f;
        for (int i = 0; i < 5000; i++) {
            angle += 0.61803399f;
            tumble.drag(FastMath.sin(angle) * 0.05f, FastMath.cos(angle * 1.7f) * 0.05f);
            tumble.update(FRAME);
        }
        assertEquals(1f, tumble.getRotation().norm(), 1e-3f, "the orientation is still a unit quaternion");
        assertEquals(1f, tumble.getRotation().mult(Vector3f.UNIT_X).length(), 1e-3f,
                "and still scales the model by one");
    }

    /** A finger let go mid-drag leaves the model spinning, and that spin dies out on its own. */
    @Test
    void aReleaseMidDragLeavesTheModelSpinning() {
        CreationTumble tumble = tumbleAtRest();
        tumble.press();
        for (int i = 0; i < 10; i++) {
            tumble.drag(0.005f, 0);
            tumble.update(FRAME);
        }
        Quaternion atRelease = tumble.getRotation().clone();
        tumble.release();

        run(tumble, 0.2f);
        assertTrue(turnBetween(atRelease, tumble.getRotation()) > 0.05f,
                "the model kept turning after the release");

        // Still before the turntable is due (2.5 s), the coast must be over : nothing moves at all.
        run(tumble, 1.4f);
        Quaternion settled = tumble.getRotation().clone();
        run(tumble, 0.5f);
        assertEquals(0f, turnBetween(settled, tumble.getRotation()), EPSILON,
                "the coast has died out and the turntable is not due yet");
    }

    /** A finger held still hands the coast nothing : the model stays where it was put. */
    @Test
    void aFingerHeldStillDoesNotFling() {
        CreationTumble tumble = tumbleAtRest();
        tumble.press();
        for (int i = 0; i < 10; i++) {
            tumble.drag(0.02f, 0);
            tumble.update(FRAME);
        }
        run(tumble, 0.5f); // still pressed, no more movement
        Quaternion held = tumble.getRotation().clone();
        tumble.release();
        run(tumble, 0.5f);
        assertEquals(0f, turnBetween(held, tumble.getRotation()), EPSILON,
                "released after a pause, the model stays put");
    }

    @Test
    void theTurntableTurnsFromTheStartAndStopsUnderTheFinger() {
        CreationTumble tumble = new CreationTumble();
        tumble.reset(0, 0);
        Quaternion opened = tumble.getRotation().clone();
        run(tumble, 1f);
        assertTrue(turnBetween(opened, tumble.getRotation()) > 0.01f,
                "the model turns by itself as soon as it is shown");

        tumble.press();
        Quaternion grabbed = tumble.getRotation().clone();
        run(tumble, 1f);
        assertEquals(0f, turnBetween(grabbed, tumble.getRotation()), EPSILON,
                "a finger on the model stops the turntable");
    }

    @Test
    void theTurntableWaitsBeforeTakingOverAgain() {
        CreationTumble tumble = tumbleAtRest();
        tumble.press();
        tumble.release();
        Quaternion released = tumble.getRotation().clone();
        run(tumble, 1.5f);
        assertEquals(0f, turnBetween(released, tumble.getRotation()), EPSILON,
                "the model is left alone for a moment after a release");

        run(tumble, 3f);
        assertTrue(turnBetween(released, tumble.getRotation()) > 0.01f,
                "left alone long enough, it starts turning again");
    }

    /**
     * A tumble showing a model and standing perfectly still : the turntable's idle clock is cleared, so a
     * test can measure a drag without the slow spin adding to it.
     */
    private static CreationTumble tumbleAtRest() {
        CreationTumble tumble = new CreationTumble();
        tumble.reset(0, 0);
        tumble.press();
        tumble.release();
        return tumble;
    }

    private static void run(CreationTumble tumble, float seconds) {
        for (int i = 0; i < Math.round(seconds / FRAME); i++) {
            tumble.update(FRAME);
        }
    }

    /** The angle, in radians, of the rotation that takes {@code from} to {@code to}. */
    private static float turnBetween(Quaternion from, Quaternion to) {
        return to.mult(from.inverse()).toAngleAxis(new Vector3f());
    }

    private static void assertQuaternionTurns(Quaternion expected, Quaternion actual, String message) {
        assertEquals(0f, turnBetween(expected, actual), EPSILON, message);
    }

    private static void assertVector(Vector3f expected, Vector3f actual) {
        assertEquals(0f, expected.distance(actual), EPSILON, "expected " + expected + " but was " + actual);
    }
}
