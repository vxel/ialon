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

import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;

/**
 * How the creation viewer's model turns : the free rotation a drag gives it, the spin it keeps when the
 * finger lets go, and the slow turntable it falls back to when left alone. Owns the orientation outright —
 * its host only feeds it drag deltas and a frame time (see {@link CreationViewerState}).
 *
 * <p>The rotation is <b>free on every axis</b> : each drag is turned into a rotation about the <em>world</em>
 * Y (horizontal drag) and X (vertical drag) axes and <b>pre</b>-multiplied onto the current orientation. There
 * is no pitch/yaw pair to gimbal-lock, and the model may end up upside down — which is the point, since the
 * player is examining it rather than looking through it. That is also why {@link org.delaunois.ialon.util.RotationHelper}
 * is not reused : it deliberately refuses to pass the pole, because it aims a player's head.
 *
 * <p>Deltas are only <b>accumulated</b> by {@link #drag} and applied by {@link #update} : several cursor
 * events in one frame then count as one movement, and the caller needs no frame time of its own.
 *
 * <p>No jME scene graph, no rendering : plain maths, so it is unit-tested directly
 * ({@code CreationTumbleTest}). Nothing is allocated per frame.
 *
 * @author Cedric de Launois
 */
final class CreationTumble {

    /** A drag across the whole panel width turns the model one full revolution. */
    private static final float DRAG_TURNS_PER_EXTENT = 1f;

    /** Time constant of the spin's decay once released, in seconds : it coasts for about three of these. */
    private static final float DECAY_TAU = 0.35f;

    /** Below this speed (rad/s) the coast is over : slower than this reads as stopped anyway. */
    private static final float MIN_SPEED = 0.05f;

    /** Ceiling on the release speed (rad/s), so a frantic swipe stays readable — about two turns a second. */
    private static final float MAX_SPEED = 4f * FastMath.PI;

    /**
     * Time constant of the speed estimate while dragging, in seconds.
     *
     * <p>A plain time-weighted average, not the sliding window {@link ScrollInertia} needs : a finger rolling
     * off the glass sends near-still samples last, which there could kill a fling outright. At 80 ms one such
     * frame weighs about a fifth, and a spin that coasts a little less far is not something anyone sees.
     */
    private static final float VELOCITY_TAU = 0.08f;

    /** How long the model must be left alone (seconds) before the slow turntable takes over. */
    private static final float IDLE_DELAY = 2.5f;

    /** Speed of the turntable, in rad/s : about 20°/s, a full revolution in 18 s. */
    private static final float IDLE_SPEED = 0.35f;

    /** Time constant of the turntable's ramp-up, in seconds, so it eases in rather than snapping on. */
    private static final float IDLE_RAMP_TAU = 1.2f;

    /** Cap on one frame's step, in seconds : a hitch must not teleport the model. */
    private static final float MAX_STEP = 1f / 20f;

    /** The orientation itself. Handed out by reference, read every frame by the host. */
    private final Quaternion rotation = new Quaternion();

    // Scratch, reused : rotate() runs every frame the model moves.
    private final Quaternion delta = new Quaternion();
    private final Quaternion product = new Quaternion();

    /** Drag deltas received since the last frame, in revolutions. */
    private float pendingYaw;
    private float pendingPitch;

    /** Angular speed in rad/s about the world Y (yaw) and X (pitch) axes. */
    private float yawSpeed;
    private float pitchSpeed;

    private boolean dragging;

    /** Seconds since the last thing the player did. Drives the turntable. */
    private float idleTime;

    /**
     * Points the model at its starting three-quarter view and stops every motion. The idle clock starts
     * expired, so the turntable is already turning on the first frame : that is what tells the player at a
     * glance that the model can be moved.
     *
     * @param yaw   the initial rotation about the world Y axis, in radians
     * @param pitch the initial rotation about the world X axis, in radians
     */
    void reset(float yaw, float pitch) {
        rotation.loadIdentity();
        pendingYaw = 0;
        pendingPitch = 0;
        yawSpeed = 0;
        pitchSpeed = 0;
        dragging = false;
        idleTime = IDLE_DELAY;
        rotate(yaw, pitch);
    }

    /** A press grabs the model : whatever it was doing stops right where it is. */
    void press() {
        dragging = true;
        pendingYaw = 0;
        pendingPitch = 0;
        yawSpeed = 0;
        pitchSpeed = 0;
        idleTime = 0;
    }

    /**
     * Records one drag event. Applied by the next {@link #update}, not here.
     *
     * @param dx the horizontal movement as a fraction of the panel width (right is positive)
     * @param dy the vertical movement as a fraction of the panel height (up is positive)
     */
    void drag(float dx, float dy) {
        dragging = true;
        pendingYaw += dx;
        pendingPitch += dy;
        idleTime = 0;
    }

    /** Release : the speed the finger left with becomes the coast. */
    void release() {
        dragging = false;
        idleTime = 0;
        yawSpeed = clampSpeed(yawSpeed);
        pitchSpeed = clampSpeed(pitchSpeed);
    }

    /**
     * One frame of motion : the drag that arrived since the last one, then — once the finger is off — the
     * coast decaying away and the turntable easing in behind it.
     *
     * @param tpf the frame time in seconds
     */
    void update(float tpf) {
        float dt = Math.min(Math.max(tpf, 0), MAX_STEP);
        if (dt <= 0) {
            return;
        }

        if (pendingYaw != 0 || pendingPitch != 0) {
            // A drag turns the model itself : the deltas are angles, and the speed they imply is only
            // sampled here so a release has something to hand the coast.
            float yaw = revolutionsToRadians(pendingYaw);
            float pitch = -revolutionsToRadians(pendingPitch);
            pendingYaw = 0;
            pendingPitch = 0;
            rotate(yaw, pitch);
            float alpha = 1f - FastMath.exp(-dt / VELOCITY_TAU);
            yawSpeed += (yaw / dt - yawSpeed) * alpha;
            pitchSpeed += (pitch / dt - pitchSpeed) * alpha;
            return;
        }

        if (dragging) {
            // The finger is down but still : it is holding the model, and the speed it was going at is
            // going stale, so a release now must not fling.
            float alpha = 1f - FastMath.exp(-dt / VELOCITY_TAU);
            yawSpeed -= yawSpeed * alpha;
            pitchSpeed -= pitchSpeed * alpha;
            return;
        }

        idleTime += dt;
        boolean coasting = Math.abs(yawSpeed) >= MIN_SPEED || Math.abs(pitchSpeed) >= MIN_SPEED;
        if (coasting) {
            rotate(yawSpeed * dt, pitchSpeed * dt);
            float decay = FastMath.exp(-dt / DECAY_TAU);
            yawSpeed *= decay;
            pitchSpeed *= decay;
            return;
        }
        // Nothing left of the throw : level the pitch off and ease the turntable in, once the model has
        // been left alone long enough that turning it by itself does not fight the player.
        pitchSpeed = 0;
        yawSpeed = 0;
        if (idleTime >= IDLE_DELAY) {
            float ramp = 1f - FastMath.exp(-(idleTime - IDLE_DELAY) / IDLE_RAMP_TAU);
            rotate(IDLE_SPEED * ramp * dt, 0);
        }
    }

    /** The orientation to apply to the model. Live : the same instance every call. */
    Quaternion getRotation() {
        return rotation;
    }

    /**
     * Turns the model by {@code yaw} about the world Y axis and {@code pitch} about the world X axis.
     *
     * <p>Pre-multiplied — {@code delta * rotation}, not the other way round — which is what makes the axes
     * the <b>world's</b> and not the model's : a horizontal drag always spins it about the screen's vertical,
     * whichever way it has been turned before, and no orientation is unreachable.
     */
    private void rotate(float yaw, float pitch) {
        if (yaw != 0) {
            delta.fromAngleNormalAxis(yaw, Vector3f.UNIT_Y);
            rotation.set(delta.mult(rotation, product));
        }
        if (pitch != 0) {
            delta.fromAngleNormalAxis(pitch, Vector3f.UNIT_X);
            rotation.set(delta.mult(rotation, product));
        }
        rotation.normalizeLocal();
    }

    private static float revolutionsToRadians(float revolutions) {
        return revolutions * DRAG_TURNS_PER_EXTENT * FastMath.TWO_PI;
    }

    private static float clampSpeed(float speed) {
        if (Math.abs(speed) < MIN_SPEED) {
            return 0;
        }
        return Math.max(-MAX_SPEED, Math.min(speed, MAX_SPEED));
    }
}
