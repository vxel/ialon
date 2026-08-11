package org.delaunois.ialon.state;

import org.delaunois.ialon.state.LoadProgress.Step;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadProgressTest {

    private static final float TPF = 1f / 60f;

    /** Runs the easing for the given number of simulated seconds. */
    private static float settle(LoadProgress progress, float seconds) {
        float painted = progress.getDisplayed();
        for (int i = 0; i < (int) (seconds * 60); i++) {
            painted = progress.update(TPF);
        }
        return painted;
    }

    @Test
    void coldStartReachesOneHundredPercent() {
        LoadProgress progress = new LoadProgress();
        progress.begin(Step.values());

        assertEquals(0, progress.getDisplayedPercent());
        assertFalse(progress.isComplete());

        for (Step step : Step.values()) {
            progress.completeStep(step);
        }

        assertTrue(progress.isComplete());
        settle(progress, 2f);
        assertEquals(100, progress.getDisplayedPercent());
    }

    @Test
    void worldSwitchSpansItsOwnStepsOverTheWholeBar() {
        LoadProgress progress = new LoadProgress();
        progress.beginWorldSwitch();

        progress.completeStep(Step.WORLD_STATES);
        progress.completeStep(Step.WORLD_INIT);
        progress.completeStep(Step.CHUNK_FETCH);
        progress.completeStep(Step.CHUNK_MESH);

        // The steps a switch does not replay (atlas, blocks, GUI) are inactive, so they must not weigh
        // on the denominator : the four world steps alone are the whole load.
        assertTrue(progress.isComplete());
        settle(progress, 2f);
        assertEquals(100, progress.getDisplayedPercent());
    }

    @Test
    void inactiveStepsAreIgnored() {
        LoadProgress progress = new LoadProgress();
        progress.beginWorldSwitch();

        progress.completeStep(Step.BLOCKS);
        progress.completeStep(Step.TEXTURES);

        settle(progress, 2f);
        assertEquals(0, progress.getDisplayedPercent());
    }

    @Test
    void beginResetsEverything() {
        LoadProgress progress = new LoadProgress();
        progress.begin(Step.values());
        for (Step step : Step.values()) {
            progress.completeStep(step);
        }
        settle(progress, 2f);
        assertEquals(100, progress.getDisplayedPercent());

        // A world switch must restart from zero, or the previous world's completed build would flash
        // ~100% on the frame the menu closes.
        progress.beginWorldSwitch();
        assertEquals(0, progress.getDisplayedPercent());
        assertEquals(0f, progress.getDisplayed(), 0f);
        assertFalse(progress.isComplete());
        assertEquals(LoadProgress.INITIAL_LABEL, progress.getLabel());
    }

    /** The pager's fetched-page count shrinks when it evicts pages ; the bar must not follow it down. */
    @Test
    void decreasingStepFractionNeverLowersProgress() {
        LoadProgress progress = new LoadProgress();
        progress.begin(Step.values());

        progress.setStepFraction(Step.CHUNK_FETCH, 0.8f);
        settle(progress, 2f);
        int highWater = progress.getDisplayedPercent();
        assertTrue(highWater > 0);

        progress.setStepFraction(Step.CHUNK_FETCH, 0.2f);
        assertEquals(highWater, progress.getDisplayedPercent());
        settle(progress, 2f);
        assertEquals(highWater, progress.getDisplayedPercent());
    }

    @Test
    void paintedValueIsMonotonicAndConverges() {
        LoadProgress progress = new LoadProgress();
        progress.begin(Step.values());

        Random random = new Random(42);
        float previous = 0f;
        for (int i = 0; i < 200; i++) {
            progress.setStepFraction(Step.CHUNK_FETCH, random.nextFloat());
            progress.setStepFraction(Step.CHUNK_MESH, random.nextFloat());
            float painted = progress.update(TPF);
            assertTrue(painted >= previous, "painted value went backwards at iteration " + i);
            assertTrue(painted <= 1f);
            previous = painted;
        }

        progress.completeStep(Step.CHUNK_FETCH);
        progress.completeStep(Step.CHUNK_MESH);
        float before = progress.getDisplayed();
        float after = settle(progress, 1f);
        assertTrue(after > before);
    }

    /** The floor on the chase exists so a nearly-reached target is actually reached. */
    @Test
    void smallRemainderIsEventuallyReached() {
        LoadProgress progress = new LoadProgress();
        progress.begin(Step.values());
        for (Step step : Step.values()) {
            progress.completeStep(step);
        }

        assertEquals(1f, settle(progress, 3f), 1e-4f);
    }

    @Test
    void labelFollowsTheCurrentStep() {
        LoadProgress progress = new LoadProgress();
        progress.begin(Step.values());
        assertEquals(LoadProgress.INITIAL_LABEL, progress.getLabel());

        progress.beginStep(Step.BLOCKS);
        assertEquals(Step.BLOCKS.getLabel(), progress.getLabel());

        progress.beginStep(Step.CHUNK_MESH);
        assertEquals(Step.CHUNK_MESH.getLabel(), progress.getLabel());
    }

    /** Nobody announces the hand-off from generating chunks to meshing them : completing it must. */
    @Test
    void completingTheCurrentStepAdvancesTheLabel() {
        LoadProgress progress = new LoadProgress();
        progress.begin(Step.values());
        progress.beginStep(Step.CHUNK_FETCH);

        progress.completeStep(Step.CHUNK_FETCH);
        assertEquals(Step.CHUNK_MESH.getLabel(), progress.getLabel());

        // The last step has nothing to advance to : the label stays put rather than going blank.
        progress.completeStep(Step.CHUNK_MESH);
        assertEquals(Step.CHUNK_MESH.getLabel(), progress.getLabel());
    }

    /** Completing a step that is NOT the one being announced must not move the label. */
    @Test
    void completingAnotherStepLeavesTheLabelAlone() {
        LoadProgress progress = new LoadProgress();
        progress.begin(Step.values());
        progress.beginStep(Step.STATES);

        progress.completeStep(Step.CHUNK_FETCH);
        assertEquals(Step.STATES.getLabel(), progress.getLabel());
    }

    /** On a world switch the label must skip the steps the switch does not replay. */
    @Test
    void labelSkipsInactiveSteps() {
        LoadProgress progress = new LoadProgress();
        progress.beginWorldSwitch();
        progress.beginStep(Step.WORLD_INIT);

        progress.completeStep(Step.WORLD_INIT);
        assertEquals(Step.CHUNK_FETCH.getLabel(), progress.getLabel());
    }

    @Test
    void fractionIsClampedToUnitRange() {
        LoadProgress progress = new LoadProgress();
        progress.begin(Step.values());

        progress.setStepFraction(Step.CHUNK_MESH, 12f);
        progress.setStepFraction(Step.CHUNK_FETCH, -3f);
        settle(progress, 2f);

        int expected = Math.round(100f * Step.CHUNK_MESH.getWeight() / totalWeight());
        assertEquals(expected, progress.getDisplayedPercent(), 1);
    }

    private static float totalWeight() {
        float total = 0f;
        for (Step step : Step.values()) {
            total += step.getWeight();
        }
        return total;
    }

    @Test
    void nothingHappensBeforeBegin() {
        LoadProgress progress = new LoadProgress();

        progress.completeStep(Step.BLOCKS);
        settle(progress, 1f);

        assertEquals(0, progress.getDisplayedPercent());
        assertFalse(progress.isComplete());
    }
}
