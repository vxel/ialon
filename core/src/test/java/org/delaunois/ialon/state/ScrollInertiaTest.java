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

import com.simsilica.lemur.anim.AnimationState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The scroll inertia shared by every list in the game : the worlds and creations grids, the settings
 * columns, and the horizontal block slider. Driven here through the same entry points the listeners use
 * (press / drag / release) and then stepped frame by frame like Lemur's {@code AnimationState} would.
 *
 * <p>What is guarded, in the order the tests come : the fling keeps going after the finger leaves, always
 * comes to a stop, and never moves a list the user is not looking at (a popup can be closed mid-glide) ; the
 * elastic ends let the finger pull past them against a resistance that only ever grows, stay within their
 * limit, and always come back flush and briskly — whether the pull, a fling or a release that never arrived
 * put the list out there. The block slider's input shape gets its own tests : drag samples with no press or
 * release around them, and a scroll axis that does not start at zero.
 *
 * @author Cedric de Launois
 */
class ScrollInertiaTest {

    private static final float TPF = 1 / 60f;
    private static final float SCREEN_HEIGHT = 720f;
    /** How long a simulated frame really sleeps : the speed is measured off the wall clock. */
    private static final long FRAME_MILLIS = 16;

    /**
     * {@link ScrollInertia#release()} registers the glide with Lemur's default animation state, so one must
     * exist. Its constructor is what publishes it — no application or state manager is involved.
     */
    @BeforeAll
    static void ensureAnimationState() {
        if (AnimationState.getDefaultInstance() == null) {
            new AnimationState();
        }
    }

    @Test
    void aFlingKeepsTheListGoingAndThenStops() throws InterruptedException {
        FakeGrid grid = new FakeGrid(1000f);
        ScrollInertia inertia = fling(grid, 30f);

        int frames = glide(inertia, 600);
        assertTrue(grid.offset > 0, "the list did not move after the finger left");
        assertTrue(grid.applied > 0, "the glide moved the offset without ever applying it");
        assertTrue(frames < 600, "the glide never came to a stop");
        // The decay's time constant is 0.2 s, so a fling is over in well under a second.
        assertTrue(frames < 60, "the glide coasted for " + frames + " frames (> 1 s)");
    }

    @Test
    void aFasterFlickCoastsFurther() throws InterruptedException {
        FakeGrid slow = new FakeGrid(1000f);
        FakeGrid fast = new FakeGrid(1000f);
        glide(fling(slow, 5f), 600);
        glide(fling(fast, 40f), 600);
        assertTrue(fast.offset > slow.offset,
                "a faster flick must travel further (slow " + slow.offset + ", fast " + fast.offset + ")");
    }

    @Test
    void theGlideStopsAtTheEndOfTheRangeInsteadOfRunningPastIt() throws InterruptedException {
        FakeGrid grid = new FakeGrid(1000f);
        grid.offset = 990f;
        ScrollInertia inertia = fling(grid, 60f);

        glide(inertia, 600);
        assertEquals(1000f, grid.offset, 0.001f, "the glide overshot the bottom of the list");
    }

    @Test
    void theGlideStopsAtTheTopWhenFlungBackwards() throws InterruptedException {
        FakeGrid grid = new FakeGrid(1000f);
        grid.offset = 10f;
        ScrollInertia inertia = fling(grid, -60f);

        glide(inertia, 600);
        assertEquals(0f, grid.offset, 0.001f, "the glide overshot the top of the list");
    }

    @Test
    void aSlowDragHandsNothingToTheGlide() throws InterruptedException {
        FakeGrid grid = new FakeGrid(1000f);
        ScrollInertia inertia = new ScrollInertia(grid);
        inertia.press();
        // Two samples a frame apart at a hair a frame : way below the speed a fling starts at.
        inertia.drag(0.05f);
        Thread.sleep(FRAME_MILLIS);
        inertia.drag(0.05f);
        Thread.sleep(FRAME_MILLIS);
        inertia.release();

        assertEquals(0.1f, grid.offset, 0.001f, "the drag itself did not move the list");
        assertEquals(0, glide(inertia, 600), "a slow drag still launched a glide");
    }

    @Test
    void aListThatDoesNotScrollNeverFlings() throws InterruptedException {
        FakeGrid grid = new FakeGrid(0f);
        ScrollInertia inertia = fling(grid, 30f);

        assertEquals(0, glide(inertia, 600), "a list with nothing to scroll launched a glide");
        assertEquals(0f, grid.offset, 0.001f);
    }

    @Test
    void aPressStopsTheGlideUnderTheFinger() throws InterruptedException {
        FakeGrid grid = new FakeGrid(1000f);
        ScrollInertia inertia = fling(grid, 30f);
        inertia.interpolate(TPF);
        float caught = grid.offset;
        assertTrue(caught > 0, "the glide had not started");

        inertia.press();
        // The tween keeps ticking under a finger — it is waiting on the next drag — but must not move a
        // pixel by itself while the list is held.
        for (int frame = 1; frame <= 10; frame++) {
            inertia.interpolate(TPF * (frame + 1));
        }
        assertEquals(caught, grid.offset, 0.001f, "the list moved on after the press caught it");
    }

    @Test
    void aHiddenListIsNeverScrolled() throws InterruptedException {
        FakeGrid grid = new FakeGrid(1000f);
        ScrollInertia inertia = fling(grid, 30f);
        grid.visible = false;
        float whereTheDragLeftIt = grid.offset;
        int appliedWhileDragging = grid.applied;

        assertEquals(0, glide(inertia, 600), "the glide kept running on a closed popup");
        assertEquals(whereTheDragLeftIt, grid.offset, 0.001f);
        assertEquals(appliedWhileDragging, grid.applied, "a hidden list was scrolled");
    }

    /**
     * The elastic ends : how far past its end a finger may pull a list, how the resistance grows as it does,
     * and that letting go always brings it back flush. The stretch limit is 15 % of the screen extent, so
     * 108 px on the 720 px-tall fake grid below.
     */
    @Test
    void aFingerCanPullTheListPastItsEndAgainstAGrowingResistance() throws InterruptedException {
        FakeGrid grid = new FakeGrid(1000f);
        ScrollInertia inertia = new ScrollInertia(grid);
        inertia.press();

        // Pull well past the end, 20 px a frame, and watch each pull do less than the one before.
        grid.offset = 1000f;
        float previousStep = Float.MAX_VALUE;
        for (int frame = 0; frame < 12; frame++) {
            float before = grid.offset;
            inertia.drag(20f);
            float step = grid.offset - before;
            assertTrue(step <= previousStep + 0.001f,
                    "pull " + frame + " moved " + step + " px, more than the previous " + previousStep);
            assertTrue(step >= 0, "a pull past the end moved the list backwards");
            previousStep = step;
            Thread.sleep(FRAME_MILLIS);
        }
        float stretch = grid.offset - 1000f;
        assertTrue(stretch > 0, "the finger could not pull the list past its end at all");
        assertTrue(stretch <= 108f + 0.001f, "the list stretched " + stretch + " px, past the 108 px limit");
        assertTrue(previousStep < 20f, "the resistance never held the finger back");
    }

    @Test
    void lettingGoOfAStretchedListSpringsItBackFlushWithItsEnd() throws InterruptedException {
        FakeGrid grid = new FakeGrid(1000f);
        ScrollInertia inertia = new ScrollInertia(grid);
        inertia.press();
        grid.offset = 1000f;
        for (int frame = 0; frame < 6; frame++) {
            inertia.drag(20f);
            Thread.sleep(FRAME_MILLIS);
        }
        assertTrue(grid.offset > 1000f, "the list was not stretched to begin with");

        inertia.release();
        int frames = glide(inertia, 600);
        assertEquals(1000f, grid.offset, 0.001f, "the spring did not bring the list flush with its end");
        assertTrue(frames > 0 && frames < 25, "the spring took " + frames + " frames to settle");
    }

    /**
     * The comeback must always be moving : it was a critically damped spring, whose tail is asymptotic, and
     * on the block strip a 19 px stretch crawled back at barely a pixel a frame for 800 ms — which reads as
     * the list stalling at its end, not as elasticity. Every frame of the return now covers real ground and
     * the whole thing is over in a few frames.
     */
    @Test
    void theComebackNeverCrawls() {
        FakeGrid grid = new FakeGrid(1000f);
        ScrollInertia inertia = new ScrollInertia(grid);
        inertia.press();
        grid.offset = 1090f; // stretched 90 px and let go from a standstill
        inertia.release();

        // The floor speed is 400 px/s, so a 60 fps frame may never cover much less than 6.6 px.
        float floorStep = 6f;
        double t = 0;
        int frames = 0;
        while (frames < 600) {
            t += TPF;
            frames++;
            float before = grid.offset;
            boolean running = inertia.interpolate(t);
            if (!running) {
                break;
            }
            assertTrue(before - grid.offset >= floorStep,
                    "frame " + frames + " of the return crawled " + (before - grid.offset) + " px");
        }
        assertEquals(1000f, grid.offset, 0.001f, "the return did not land on the end");
        assertTrue(frames <= 21, "the return took " + frames + " frames, over 350 ms");
    }

    @Test
    void aStretchedListSpringsBackFromTheTopEndToo() throws InterruptedException {
        FakeGrid grid = new FakeGrid(1000f);
        ScrollInertia inertia = new ScrollInertia(grid);
        inertia.press();
        for (int frame = 0; frame < 6; frame++) {
            inertia.drag(-20f);
            Thread.sleep(FRAME_MILLIS);
        }
        assertTrue(grid.offset < 0f, "the list was not stretched past the top");

        inertia.release();
        glide(inertia, 600);
        assertEquals(0f, grid.offset, 0.001f, "the spring did not bring the list flush with its top");
    }

    /**
     * A fling that reaches an end bounces instead of stopping dead : it carries past the end, into the same
     * elastic zone a finger would stretch, and the spring brings it home.
     */
    @Test
    void aFlingThatReachesAnEndBouncesAndComesBack() throws InterruptedException {
        FakeGrid grid = new FakeGrid(1000f);
        grid.offset = 900f;
        ScrollInertia inertia = fling(grid, 40f);

        float deepest = grid.offset;
        double t = 0;
        int frames = 0;
        for (; frames < 600; frames++) {
            t += TPF;
            boolean running = inertia.interpolate(t);
            deepest = Math.max(deepest, grid.offset);
            if (!running) {
                break;
            }
        }
        assertTrue(deepest > 1000f, "the fling stopped dead at the end instead of bouncing (" + deepest + ")");
        assertTrue(deepest <= 1108f + 0.001f, "the bounce went " + deepest + " px, past the stretch limit");
        assertEquals(1000f, grid.offset, 0.001f, "the bounce did not settle back on the end");
        assertTrue(frames < 40, "the bounce took " + frames + " frames to settle");
    }

    /**
     * A gesture whose release never arrives — the block slider's touch path, where the finger may leave
     * outside the strip — must not leave the list stretched : no drag sample for a tenth of a second means
     * the finger is gone.
     */
    @Test
    void aStretchedListSpringsBackEvenIfTheReleaseNeverArrives() throws InterruptedException {
        FakeGrid grid = new FakeGrid(1000f);
        ScrollInertia inertia = new ScrollInertia(grid);
        inertia.press();
        grid.offset = 1000f;
        for (int frame = 0; frame < 6; frame++) {
            inertia.drag(20f);
            Thread.sleep(FRAME_MILLIS);
        }
        assertTrue(grid.offset > 1000f, "the list was not stretched to begin with");

        // No release, just a finger that stops sending anything.
        Thread.sleep(120);
        glide(inertia, 600);
        assertEquals(1000f, grid.offset, 0.001f, "a gesture with no release left the list stretched");
    }

    /**
     * Presses, drags {@code dy} pixels a frame for three frames, and releases : one flick of the finger, at
     * {@code dy} px every {@link #FRAME_MILLIS}. The inertia moves the list as it goes, so the offset it
     * leaves behind is the drag itself ; what the glide adds on top is measured from there.
     */
    private static ScrollInertia fling(FakeGrid grid, float dy) throws InterruptedException {
        ScrollInertia inertia = new ScrollInertia(grid);
        inertia.press();
        // The first event only seeds the clock ; the following ones, a real frame apart, set the speed.
        for (int frame = 0; frame < 3; frame++) {
            inertia.drag(dy);
            Thread.sleep(FRAME_MILLIS);
        }
        inertia.release();
        return inertia;
    }

    /**
     * Steps the glide like the animation state does, until it ends or {@code maxFrames} have run.
     *
     * @return the number of frames the glide actually ran
     */
    private static int glide(ScrollInertia inertia, int maxFrames) {
        double t = 0;
        for (int frame = 0; frame < maxFrames; frame++) {
            t += TPF;
            if (!inertia.interpolate(t)) {
                return frame;
            }
        }
        return maxFrames;
    }

    /** A scrollable list reduced to its scroll state : what the inertia actually drives. */
    private static class FakeGrid implements CardGrid.ScrollableGrid {

        private final float maxScroll;
        private float offset;
        private boolean visible = true;
        private int applied;

        FakeGrid(float maxScroll) {
            this.maxScroll = maxScroll;
        }

        @Override
        public float getMaxScroll() {
            return maxScroll;
        }

        @Override
        public boolean isGridVisible() {
            return visible;
        }

        @Override
        public float getScrollOffset() {
            return offset;
        }

        @Override
        public void setScrollOffset(float offset) {
            this.offset = offset;
        }

        @Override
        public void applyScroll() {
            applied++;
        }

        @Override
        public float getScreenHeight() {
            return SCREEN_HEIGHT;
        }

        @Override
        public void selectCard(String cardId) {
            // not exercised here : the inertia never selects
        }

        @Override
        public ScrollInertia getInertia() {
            throw new UnsupportedOperationException("the test drives the inertia directly");
        }
    }

    /**
     * A strip scrolled the way the block slider is : jME touch scroll events, which come with no press or
     * release around them, so every event is a sample followed by a {@code release()} that re-arms the
     * glide. The strip must move (here the glide is the only thing that moves it) and still coast to a stop
     * once the events dry up.
     */
    @Test
    void aTouchScrollWithNoPressStillMovesAndCoasts() throws InterruptedException {
        FakeStrip strip = new FakeStrip(-2000f, 100f);
        ScrollInertia inertia = new ScrollInertia(strip);
        for (int event = 0; event < 6; event++) {
            inertia.drag(-12f);
            inertia.release();
            Thread.sleep(FRAME_MILLIS);
        }

        int frames = glide(inertia, 600);
        assertTrue(strip.position < 0, "the strip did not move on touch scroll");
        assertTrue(frames > 0 && frames < 600, "the touch fling never coasted to a stop");
    }

    /**
     * The block slider's range is not anchored at zero — it runs from a negative x (the far end of the
     * strip) up to its left margin — so the bounds are the target's to state, not the inertia's to assume.
     */
    @Test
    void theGlideRespectsARangeThatDoesNotStartAtZero() throws InterruptedException {
        FakeStrip strip = new FakeStrip(-2000f, 100f);
        strip.position = -1950f;
        ScrollInertia inertia = new ScrollInertia(strip);
        inertia.press();
        for (int frame = 0; frame < 3; frame++) {
            inertia.drag(-60f);
            Thread.sleep(FRAME_MILLIS);
        }
        inertia.release();

        glide(inertia, 600);
        assertEquals(-2000f, strip.position, 0.001f, "the glide ran past the end of the strip");
    }

    /**
     * The same finger movement must read as the same speed however many events it arrives in. jME dispatches
     * a frame's worth of queued touch events back to back, so four events a frame land microseconds apart
     * while carrying a frame's worth of movement each ; timing each one on its own made the block slider
     * scroll about twice the finger's speed, which is what this pins down.
     */
    @Test
    void theSpeedDoesNotDependOnHowManyEventsTheMovementArrivesIn() throws InterruptedException {
        float oneEventAFrame = flingTravel(1, 20f);
        assertTrue(oneEventAFrame > 0, "the reference fling did not travel");
        for (int events : new int[] {2, 3, 4, 8}) {
            float split = flingTravel(events, 20f / events);
            assertTrue(Math.abs(split - oneEventAFrame) < 0.35f * oneEventAFrame,
                    "the same 20 px a frame travelled " + oneEventAFrame + " px in one event a frame but "
                            + split + " px in " + events);
        }
    }

    /**
     * Drags 20 px a frame for five frames — split into {@code eventsPerFrame} events dispatched back to
     * back, as jME delivers them — and returns how far the fling then coasted.
     */
    private static float flingTravel(int eventsPerFrame, float deltaPerEvent) throws InterruptedException {
        FakeStrip strip = new FakeStrip(-5000f, 0f);
        ScrollInertia inertia = new ScrollInertia(strip);
        inertia.press();
        for (int frame = 0; frame < 5; frame++) {
            for (int event = 0; event < eventsPerFrame; event++) {
                inertia.drag(-deltaPerEvent);
            }
            // A real frame has to elapse : the speed is measured off the wall clock, which is the whole
            // point of the batching this guards.
            Thread.sleep(FRAME_MILLIS);
        }
        inertia.release();
        float start = strip.position;
        glide(inertia, 600);
        return start - strip.position;
    }

    /**
     * A finger never stops moving and then lifts : it slows down over the last few milliseconds as it rolls
     * off the glass, and the touch screen keeps sampling all the way through. Those last near-still samples
     * are part of the lift, not of the throw, and they must not be allowed to decide the speed the list is
     * released at — which is what a release that reads as a dead stop under the finger feels like.
     *
     * <p>Only the block strip shows it badly : it is fed raw touch events, several per frame, so the tail is
     * several samples long. The vertical lists are driven by Lemur's cursor events, which jME emits once a
     * frame, so their tail is one sample at most.
     */
    @Test
    void aFlickSurvivesTheSlowSamplesAFingerLeavesWhileLiftingOff() throws InterruptedException {
        float clean = flickTravel(0);
        assertTrue(clean > 100f, "the reference flick barely travelled (" + clean + " px)");
        for (int tail : new int[] {1, 2, 3, 4}) {
            float lifted = flickTravel(tail);
            assertTrue(lifted > 0.5f * clean,
                    "a flick ending with " + tail + " lift-off samples travelled " + lifted
                            + " px instead of about " + clean);
        }
    }

    /**
     * One flick of the finger — 30 px every 8 ms, the rate a 120 Hz touch screen samples at — followed by
     * {@code tailSamples} events of a single pixel as the finger leaves the glass. Returns how far the strip
     * then coasted.
     */
    private static float flickTravel(int tailSamples) throws InterruptedException {
        FakeStrip strip = new FakeStrip(-5000f, 0f);
        ScrollInertia inertia = new ScrollInertia(strip);
        inertia.press();
        for (int event = 0; event < 6; event++) {
            inertia.drag(-30f);
            Thread.sleep(8);
        }
        for (int event = 0; event < tailSamples; event++) {
            inertia.drag(-1f);
            Thread.sleep(8);
        }
        inertia.release();
        float start = strip.position;
        glide(inertia, 600);
        return start - strip.position;
    }

    /** A scroll axis whose range is neither vertical nor anchored at zero : the block slider's mapping. */
    private static class FakeStrip implements ScrollInertia.Target {

        private final float min;
        private final float max;
        private float position;

        FakeStrip(float min, float max) {
            this.min = min;
            this.max = max;
            this.position = max;
        }

        @Override
        public float getScrollPosition() {
            return position;
        }

        @Override
        public void setScrollPosition(float position) {
            this.position = position;
        }

        @Override
        public float getMinScrollPosition() {
            return min;
        }

        @Override
        public float getMaxScrollPosition() {
            return max;
        }

        @Override
        public boolean isScrollActive() {
            return min < max;
        }

        @Override
        public float getScrollExtent() {
            return 1280f;
        }
    }
}
