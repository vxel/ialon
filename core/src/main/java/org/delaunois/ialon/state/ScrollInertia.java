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

import com.simsilica.lemur.anim.Animation;
import com.simsilica.lemur.anim.AnimationState;
import com.simsilica.lemur.anim.Tween;

/**
 * How every scrollable list in the game moves : the vertical worlds and creations grids, the vertical
 * settings columns, and the horizontal block slider. It owns the scroll position outright — its listeners
 * only feed it a press, the drag deltas and a release — and gives all of them the same three behaviours :
 *
 * <ul>
 * <li><b>Drag</b> : the content follows the finger inside its range.</li>
 * <li><b>Fling</b> : released mid-drag, the content keeps going and coasts to a stop instead of freezing
 *     under the finger.</li>
 * <li><b>Elastic ends</b> : the finger can pull the content past either end, against a resistance that grows
 *     with the stretch, and letting go springs it back flush with that end. A fling that reaches an end
 *     bounces the same way, so an end behaves the same however it is reached.</li>
 * </ul>
 *
 * <p>It knows nothing about the lists beyond {@link Target} : a position along one axis, the two ends it
 * lives between, and whether it is on screen at all.
 *
 * <p>The motion is driven by Lemur's {@link AnimationState} rather than by its host's {@code update()} :
 * that state is already attached and ticking, which spares the hosts — two AppStates, a plain object owned
 * by a popup, and the block slider — from each having to pump a frame loop of their own. Registration is
 * lazy, exactly like Lemur's own {@code EffectControl}, because the animation state does not exist yet when
 * the popups are constructed.
 *
 * <p>{@code ScrollInertia} <b>is</b> the tween it registers : one instance per list, reused by every
 * gesture, so scrolling allocates nothing but Lemur's own animation wrapper.
 *
 * <p>Instances live on the host, not on the listener : a card grid has one listener per card plus one for the
 * gaps, and a press on any of them must take over the motion another one started.
 *
 * <p>Two input shapes feed it :
 *
 * <ul>
 * <li>Lemur cursor events (the grids and columns) come as a press, a run of drags, then a release.</li>
 * <li>jME touch events (the block slider) carry the drag in {@code SCROLL} events, with {@code DOWN} /
 *     {@code UP} around them. Those two are not guaranteed to reach us — the finger may leave outside the
 *     strip, or the gesture detector may swallow them — so a gesture that stops feeding samples for
 *     {@link #GESTURE_TIMEOUT} is taken to be over. Without that, a list pulled past its end and let go
 *     would stay stretched.</li>
 * </ul>
 *
 * @author Cedric de Launois
 */
class ScrollInertia implements Tween {

    /**
     * Time constant of the fling's velocity decay, in seconds : the glide travels about
     * {@code velocity * TAU} pixels. 0.2 s is what the block slider's former impulse covered (it ramped a
     * release speed down to zero over the same 0.2 s), so replacing it with this changed no distance.
     */
    private static final float DECAY_TAU = 0.2f;

    /** Below this speed (GUI px/s) the glide is over : slower than this reads as stopped anyway. */
    private static final float MIN_SPEED = 25f;

    /**
     * Ceiling on the release speed, as a multiple of the screen extent along the scroll axis, so a frantic
     * swipe stays readable.
     */
    private static final float MAX_SPEED_EXTENTS = 4f;

    /**
     * How far past an end the content may ever go, as a multiple of the screen extent along the scroll axis.
     * The resistance reaches a full stop there, so this is a hard limit, not a soft one.
     */
    private static final float MAX_STRETCH_EXTENTS = 0.15f;

    /**
     * Time constant of the spring that absorbs the speed a list arrives at an end with, in seconds. The
     * spring is critically damped : it never oscillates, it just brings the list to a halt — softly for a
     * finger's pull, firmly for a fast fling, since the deeper the stretch the harder it pushes back.
     */
    private static final float SPRING_TAU = 0.11f;

    /**
     * Time constant of the ease that brings a stretched list home once it has stopped, in seconds.
     *
     * <p>The comeback is <b>not</b> the same spring that absorbed the throw. A critically damped spring's
     * tail is asymptotic : measured on the block strip, a 19 px stretch crawled back at barely a pixel a
     * frame for 800 ms, which reads as the list stalling rather than springing. This ease covers the same
     * distance in a fifth of the time and, with {@link #MIN_RETURN_SPEED}, actually arrives.
     */
    private static final float RETURN_TAU = 0.08f;

    /**
     * Floor on the comeback speed, in GUI px/s. What kills the asymptotic tail : below this the ease is
     * driven at the floor instead, so the last few pixels take a few frames rather than half a second.
     */
    private static final float MIN_RETURN_SPEED = 400f;

    /** How close to its end (GUI pixels) a returning list is taken to have arrived. */
    private static final float SETTLE_PIXELS = 0.5f;

    /**
     * A gap this long (seconds) between two drag samples ends the gesture : the finger was held still, so
     * the speed it arrived with is stale and releasing must not fling.
     */
    private static final float SAMPLE_TIMEOUT = 0.12f;

    /**
     * The shortest span a sample may be timed over, in seconds. Events closer together than this are taken
     * to be one batch : their deltas are summed and timed as one sample.
     *
     * <p>This is not a division guard, it is the fix for a real 2× overspeed. jME dispatches all the touch
     * events queued during a frame back to back, so the wall clock between two of them is microseconds while
     * the movement they carry spans the whole frame ; timing each one on its own read the finger as several
     * times faster than it was (Lemur's cursor events do not show this — the block slider's touch events
     * do). Well under a frame, so a genuinely high touch sampling rate still gets one sample per event.
     */
    private static final float MIN_SAMPLE_SPAN = 1f / 240f;

    /**
     * A gesture with no drag sample for this long (seconds) is taken to be over, standing in for the release
     * the touch path may never send. Long enough not to cut a finger that pauses mid-drag short, short enough
     * that a stretched list does not sit waiting.
     */
    private static final float GESTURE_TIMEOUT = 0.1f;

    /**
     * How far back the release speed is measured, in seconds : the finger's average speed over its last
     * {@code 0.1 s} of travel, not its speed in the last event.
     *
     * <p>This window is the whole point of the estimator, and a finger's LIFT is why. Nobody stops moving and
     * then lets go : over the last few milliseconds the finger rolls off the glass, slowing as its contact
     * patch shrinks, and the screen keeps sampling all the way through. Weight the newest sample heavily —
     * an exponential average did, at 65 % — and those last near-still samples decide the release speed : one
     * of them cut a 768 px fling to 282, three left it below the point where a fling starts at all, which is
     * a strip that stops dead the instant the finger leaves. Averaged over a window they count for their
     * share of the time and no more. This is what Android's own VelocityTracker does, and for this reason.
     *
     * <p>Long enough to span a whole flick (a flick IS about a tenth of a second), short enough that a finger
     * that slows down on purpose before letting go is believed.
     */
    private static final float VELOCITY_WINDOW = 0.1f;

    /**
     * How many samples the window can hold : it is closed after {@link #VELOCITY_WINDOW} and samples are
     * timed no closer than {@link #MIN_SAMPLE_SPAN}, so this is exactly the most that can fall inside it.
     * A faster stream is batched into fewer samples, never more.
     */
    private static final int SAMPLE_CAPACITY = (int) (VELOCITY_WINDOW / MIN_SAMPLE_SPAN);

    /**
     * Cap on one frame's step, in seconds. A hitch must not let the spring integrate a step so large that it
     * diverges, nor the glide teleport the list.
     */
    private static final float MAX_STEP = 1f / 20f;

    /** Backstop on how long the motion may run with no finger on it, in seconds. */
    private static final double MAX_IDLE = 3.0;

    private static final double NANOS_TO_SECONDS = 1 / 1_000_000_000.0;

    /**
     * What a scroll moves : one axis, whatever it belongs to. Vertical lists map it to their scroll offset
     * (see {@link CardGrid.ScrollableGrid}, which adapts this interface for all of them at once) ; the block
     * slider maps it to its container's x.
     */
    interface Target {

        /** Where the content sits along the scroll axis, in GUI pixels. */
        float getScrollPosition();

        /**
         * Moves the content along the scroll axis. May be called with a position <b>outside</b> the two ends
         * — that is the elastic stretch, bounded by {@link #MAX_STRETCH_EXTENTS} — so an implementation must
         * not clamp what it is handed.
         */
        void setScrollPosition(float position);

        float getMinScrollPosition();

        float getMaxScrollPosition();

        /**
         * Whether scrolling means anything right now : {@code false} while the list is off screen or has
         * nothing to scroll, and any motion in flight then settles and ends. The ends are only read when this
         * holds, so an implementation may leave them inverted ({@code min > max}) when the content fits.
         */
        boolean isScrollActive();

        /**
         * The screen extent along the scroll axis, in GUI pixels — the screen height for a vertical list, its
         * width for a horizontal one. What the speed ceiling and the elastic stretch are scaled from.
         */
        float getScrollExtent();
    }

    private final Target target;

    /** Scroll speed in GUI px/s, in the same sign convention as the drag deltas it is sampled from. */
    private float velocity;

    /** Set from the press (or the first drag) until the release, real or {@link #GESTURE_TIMEOUT}-inferred. */
    private boolean dragging;

    private boolean sampled;
    /** Drag pixels not timed yet : events that arrived too soon after the last sample to be timed alone. */
    private float pending;
    private long lastSampleNanos;

    // The recent drag samples the release speed is averaged over : how far each moved, how long it took, and
    // when it ended. A ring, oldest overwritten first — which is what the window would drop anyway.
    private final float[] samplePixels = new float[SAMPLE_CAPACITY];
    private final float[] sampleSeconds = new float[SAMPLE_CAPACITY];
    private final long[] sampleEndNanos = new long[SAMPLE_CAPACITY];
    private int sampleWrite;
    private int sampleCount;

    private double lastTime;
    private double idleTime;
    private Animation motion;

    ScrollInertia(Target target) {
        this.target = target;
    }

    /**
     * A press grabs the list. Whatever it was doing stops right where it is — including mid-spring, so a
     * press on a list still coming back from its end catches it stretched instead of snapping it home.
     */
    void press() {
        cancelMotion();
        velocity = 0;
        sampled = false;
        pending = 0;
        clearSamples();
        dragging = true;
    }

    /**
     * Moves the list by one drag event, and tracks how fast it is going.
     *
     * <p>Inside its range the list follows the finger exactly. Past an end it follows it less and less : the
     * delta is scaled by {@link #resistance}, which reaches zero at the stretch limit. It is the
     * <b>movement actually applied</b> that is sampled for the speed, not the raw finger delta, so a finger
     * still pulling hard on an already stretched list hands the spring almost no speed to fight.
     *
     * @param delta the drag delta of this event along the scroll axis, in GUI pixels
     */
    void drag(float delta) {
        dragging = true;
        sample(move(delta));
        keepRunning();
    }

    /** Release : hands the finger's speed to the fling, or springs a stretched list home. */
    void release() {
        dragging = false;
        velocity = releaseSpeed();
        if (Math.abs(velocity) < MIN_SPEED) {
            velocity = 0;
        } else {
            float limit = MAX_SPEED_EXTENTS * target.getScrollExtent();
            velocity = Math.max(-limit, Math.min(velocity, limit));
        }
        if (velocity != 0 || overshoot() != 0) {
            keepRunning();
        }
    }

    /**
     * Stops everything and settles the list back within its range : for a host tearing the list down, or
     * swapping the view out from under it.
     */
    void stop() {
        cancelMotion();
        dragging = false;
        velocity = 0;
        settle();
    }

    @Override
    public double getLength() {
        return MAX_IDLE;
    }

    /**
     * One frame of motion. {@code t} is the time since this run started (Lemur hands the tween absolute time,
     * so the per-frame step is the difference), and returning {@code false} ends the run.
     *
     * <p>Three regimes, in this order : a finger is on the list and moves it itself ; the list is stretched
     * past an end and springs back ; the list is coasting.
     */
    @Override
    public boolean interpolate(double t) {
        if (t < 0) {
            return true;
        }
        float dt = (float) Math.min(Math.max(0, t - lastTime), MAX_STEP);
        lastTime = t;
        if (!target.isScrollActive()) {
            settle();
            return end();
        }
        if (dragging) {
            idleTime = 0;
            if (sinceLastSample() > GESTURE_TIMEOUT) {
                // The touch path sends no release of its own, and the one it does send may not reach us.
                release();
            }
            return true;
        }
        idleTime += dt;
        float over = overshoot();
        if (over != 0) {
            return springBack(over, dt);
        }
        return coast(dt);
    }

    /**
     * Brings a stretched list back onto its end, in two phases : as long as something is still carrying it
     * outwards a critically damped spring absorbs that speed, and once it has stopped an ease takes it home
     * (see {@link #RETURN_TAU} for why the spring does not do both).
     */
    private boolean springBack(float over, float dt) {
        float bound = over > 0 ? target.getMaxScrollPosition() : target.getMinScrollPosition();
        if (idleTime >= MAX_IDLE) {
            target.setScrollPosition(bound);
            return end();
        }
        // Still being carried outwards, fast enough to see : absorb it. The threshold is what keeps the
        // turning point from dragging on — a spring's last frames outwards move a fraction of a pixel each,
        // and three of those in a row is exactly what reads as a stall.
        if (Math.abs(velocity) >= MIN_SPEED && Math.signum(velocity) == Math.signum(over)) {
            float omega = 1f / SPRING_TAU;
            velocity += (-2f * omega * velocity - omega * omega * over) * dt;
            float wanted = target.getScrollPosition() + velocity * dt;
            if (applyPosition(wanted) != wanted) {
                // The stretch limit : whatever was still pushing the list outwards is absorbed there.
                velocity = 0;
            }
            return true;
        }
        // Coming home. Driven by distance rather than by force : the stretch left eases away, never slower
        // than the floor speed, and the last half pixel is simply snapped so the list ends up exactly on
        // its end rather than approaching it forever.
        velocity = 0;
        float remaining = Math.abs(over);
        float step = Math.max(remaining * (1f - (float) Math.exp(-dt / RETURN_TAU)), MIN_RETURN_SPEED * dt);
        if (step >= remaining - SETTLE_PIXELS) {
            target.setScrollPosition(bound);
            return end();
        }
        target.setScrollPosition(target.getScrollPosition() - Math.signum(over) * step);
        return true;
    }

    /**
     * Coasts the list along, the fling's speed decaying as it goes. Running past an end is not stopped here :
     * the step is applied as it comes, and the next frame finds the list stretched and springs it back —
     * which is what makes a fling bounce off an end the way a finger does.
     */
    private boolean coast(float dt) {
        if (Math.abs(velocity) < MIN_SPEED || idleTime >= MAX_IDLE) {
            settle();
            return end();
        }
        applyPosition(target.getScrollPosition() + velocity * dt);
        velocity *= (float) Math.exp(-dt / DECAY_TAU);
        return true;
    }

    /**
     * Applies one drag delta and returns the movement it actually produced : the whole delta inside the range
     * or on the way back in, a fraction of it when pulling further past an end.
     */
    private float move(float delta) {
        if (!target.isScrollActive()) {
            return 0;
        }
        float from = target.getScrollPosition();
        float over = overshoot(from);
        float pull = over != 0 && Math.signum(delta) == Math.signum(over)
                ? delta * resistance(over)
                : delta;
        return applyPosition(from + pull) - from;
    }

    /**
     * How much of the finger's movement a list stretched by {@code over} still takes : all of it at the end
     * itself, none of it at the stretch limit.
     */
    private float resistance(float over) {
        return Math.max(0, 1f - Math.abs(over) / maxStretch());
    }

    /** Moves the list, never further past an end than the stretch limit. Returns where it ended up. */
    private float applyPosition(float wanted) {
        float stretch = maxStretch();
        float clamped = Math.max(target.getMinScrollPosition() - stretch,
                Math.min(wanted, target.getMaxScrollPosition() + stretch));
        target.setScrollPosition(clamped);
        return clamped;
    }

    private float maxStretch() {
        return MAX_STRETCH_EXTENTS * target.getScrollExtent();
    }

    /** How far the list sits past one of its ends : positive past the max, negative past the min, else zero. */
    private float overshoot() {
        return overshoot(target.getScrollPosition());
    }

    private float overshoot(float position) {
        float min = target.getMinScrollPosition();
        float max = target.getMaxScrollPosition();
        if (position > max) {
            return position - max;
        }
        if (position < min) {
            return position - min;
        }
        return 0;
    }

    /** Brings a stretched list back within its range at once, with no spring. */
    private void settle() {
        float min = target.getMinScrollPosition();
        float max = target.getMaxScrollPosition();
        if (min > max) {
            // Nothing to scroll : the host owns where the content rests (it centres it, typically).
            return;
        }
        float position = target.getScrollPosition();
        float clamped = Math.max(min, Math.min(position, max));
        if (clamped != position) {
            target.setScrollPosition(clamped);
        }
    }

    /**
     * Times one drag sample and files it in the window the release speed is averaged over.
     *
     * <p>What is timed is the pixels covered <b>divided by the span they took</b>, never one event's delta
     * over one event's clock read : events too close together to time are summed and carried over to the next
     * sample (see {@link #MIN_SAMPLE_SPAN}), so how many events a movement is cut into does not change the
     * speed it reads as.
     */
    private void sample(float delta) {
        long now = System.nanoTime();
        pending += delta;
        if (!sampled) {
            // First event of the gesture : only starts the clock, there is no span to time it over yet.
            sampled = true;
            lastSampleNanos = now;
            pending = 0;
            return;
        }
        float span = (float) ((now - lastSampleNanos) * NANOS_TO_SECONDS);
        if (span < MIN_SAMPLE_SPAN) {
            return;
        }
        lastSampleNanos = now;
        float moved = pending;
        pending = 0;
        if (span > SAMPLE_TIMEOUT) {
            // The finger was held still : it is a new throw from here, and everything before the pause is
            // stale — including how fast the finger was going into it.
            clearSamples();
            return;
        }
        record(moved, span, now);
    }

    /**
     * How fast the finger was going as it left, in GUI px/s : its average speed over the last
     * {@link #VELOCITY_WINDOW} of travel.
     *
     * <p>Zero when the last thing the finger did was hold still — a list placed by hand and let go must stay
     * where it was put, and a stale speed from before the pause would throw it instead.
     */
    private float releaseSpeed() {
        long now = System.nanoTime();
        if (!sampled) {
            return 0;
        }
        float since = (float) ((now - lastSampleNanos) * NANOS_TO_SECONDS);
        if (since > SAMPLE_TIMEOUT) {
            clearSamples();
            return 0;
        }
        // Whatever arrived after the last timed sample is part of the throw too : the finger's last fraction
        // of a frame is exactly what a release is made of. Timed like any other sample, so it weighs what it
        // is worth and no more.
        if (pending != 0 && since >= MIN_SAMPLE_SPAN) {
            record(pending, since, now);
            pending = 0;
            lastSampleNanos = now;
        }
        // The window closes on the finger's LAST movement, not on the clock now. They are the same thing for
        // a release that arrives with the finger, and not at all the same for one the touch path never sent :
        // that one is inferred a GESTURE_TIMEOUT late, and measured from now the window would have expired by
        // the time it fires — a flick that ends with the finger leaving the strip would never throw at all.
        long horizon = lastSampleNanos - (long) (VELOCITY_WINDOW / NANOS_TO_SECONDS);
        float pixels = 0;
        float seconds = 0;
        for (int age = 1; age <= sampleCount; age++) {
            int slot = (sampleWrite - age + SAMPLE_CAPACITY) % SAMPLE_CAPACITY;
            if (sampleEndNanos[slot] < horizon) {
                break;
            }
            pixels += samplePixels[slot];
            seconds += sampleSeconds[slot];
        }
        return seconds > 0 ? pixels / seconds : 0;
    }

    private void record(float pixels, float seconds, long endNanos) {
        samplePixels[sampleWrite] = pixels;
        sampleSeconds[sampleWrite] = seconds;
        sampleEndNanos[sampleWrite] = endNanos;
        sampleWrite = (sampleWrite + 1) % SAMPLE_CAPACITY;
        if (sampleCount < SAMPLE_CAPACITY) {
            sampleCount++;
        }
    }

    private void clearSamples() {
        sampleWrite = 0;
        sampleCount = 0;
    }

    private float sinceLastSample() {
        return sampled ? (float) ((System.nanoTime() - lastSampleNanos) * NANOS_TO_SECONDS) : 0;
    }

    /**
     * Makes sure the tween is registered and ticking. A run already in flight is left alone rather than
     * replaced — it reads the speed every frame, so it picks up a new one, and stacking a second animation on
     * the same tween would step it several times a frame.
     */
    private void keepRunning() {
        AnimationState anim = AnimationState.getDefaultInstance();
        if (anim == null || (motion != null && anim.isRunning(motion))) {
            return;
        }
        lastTime = 0;
        idleTime = 0;
        motion = anim.add(this);
    }

    private void cancelMotion() {
        if (motion != null) {
            AnimationState anim = AnimationState.getDefaultInstance();
            if (anim != null) {
                anim.cancel(motion);
            }
            motion = null;
        }
    }

    private boolean end() {
        velocity = 0;
        motion = null;
        return false;
    }
}
