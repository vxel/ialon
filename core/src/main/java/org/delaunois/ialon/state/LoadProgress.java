/*
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

/**
 * How far the game is through loading, as one number in [0, 1] plus the name of what it is doing.
 * The model the splashscreen paints.
 *
 * <p>Startup is a sequence of {@link Step}s, each carrying a relative {@code weight}. Progress is the
 * weighted mean of the steps' completion, so a step that takes a second of wall clock occupies a second's
 * worth of the bar rather than "one step out of nine". Weights are arbitrary units (they are milliseconds
 * as measured, but only their ratios matter) and do <em>not</em> need to sum to anything : the denominator
 * is the sum of the <em>active</em> steps. That is what lets a runtime world switch, which replays only
 * the world steps, still span a full 0 to 100%, and it means adding a step later never forces the others
 * to be re-tuned.
 *
 * <p>Two properties the bar must have, both enforced here rather than at the call sites :
 * <ul>
 *   <li><b>monotonic</b> - a step's fraction only ever grows ({@link #setStepFraction} keeps the maximum),
 *       and so does the overall target. The chunk phase feeds this from {@code ChunkPager.getFetchedPages()},
 *       which <em>shrinks</em> when the pager evicts pages because the player moved ; without the clamp the
 *       bar would visibly walk backwards.</li>
 *   <li><b>eased</b> - the painted value chases the target instead of jumping to it, so the coarse,
 *       step-sized increments of the init phase read as movement rather than as a slideshow.</li>
 * </ul>
 *
 * <p><b>Threading</b> : not synchronized, and deliberately so. Every mutator is called from the render
 * thread : the init steps from {@code Ialon.simpleUpdate}, the chunk fractions from
 * {@code SplashscreenState.update}, which polls the pager's counters rather than being pushed to from the
 * worker threads. Keep it that way : it also keeps the {@code blocks} package free of any game-side import.
 *
 * @author Cedric de Launois
 */
public class LoadProgress {

    /**
     * A named unit of startup work, with what to tell the player and what it costs relative to the others.
     *
     * <p>Weights are wall-clock milliseconds. Only their ratios matter, and they were measured on desktop
     * (default render distance) from the {@code Init step X took Y ms} lines the init pump logs and the
     * {@code World built in Xms} line the world builder logs. To re-calibrate (in particular on the
     * Android target, where decoding textures is relatively dearer than generating terrain) run the game
     * and read those same lines back:
     * <pre>
     *   Init profile (2255 ms total) : AUDIO=0ms, TEXTURES=22ms, GUI=42ms, BLOCKS=123ms,
     *                                  BLOCK_ARRAY=69ms, STATES=29ms, STATES_INIT=700ms
     *   World built in 1279ms (907ms generating, 372ms meshing)
     * </pre>
     *
     * <p>Several steps deliberately share a label : the player does not care that the texture array is
     * built after the catalog, only that blocks are being loaded.
     */
    public enum Step {

        /** Audio listener + master volume. Trivial, but it is the first thing that runs. */
        AUDIO("Loading sounds...", 5),

        /** Loading and decoding the ~20 source textures that go into the UI atlas. */
        TEXTURES("Loading textures...", 25),

        /** Packing the atlas, remapping the font onto it, and registering the Lemur theme. */
        GUI("Loading textures...", 45),

        /** Block catalog : the YAML, then one type/material/texture per definition. */
        BLOCKS("Loading blocks...", 125),

        /** Slicing every registered block tile into the layers of the block texture array. */
        BLOCK_ARRAY("Loading blocks...", 70),

        /** Attaching every state. Cheap : what they cost lands in {@link #STATES_INIT}. */
        STATES("Preparing world...", 30),

        /**
         * The {@code initialize()} pass the {@link #STATES} attaches trigger at the start of the next
         * frame (the player model, the popups, the far-terrain heightmap, the minimap bake) plus the
         * diagnostics tail. No work of its own : it is a placeholder for that pass, which no frame is
         * drawn during and which would otherwise weigh nothing on the bar.
         */
        STATES_INIT("Preparing world...", 700),

        /** Re-attaching the world states on a runtime world switch (cold start uses {@link #STATES}). */
        WORLD_STATES("Preparing world...", 30),

        /** Their {@code initialize()} pass : the far-terrain heightmap and the minimap bake dominate. */
        WORLD_INIT("Generating horizon...", 700),

        /** Chunks generated or read back from the save. Fed from the pager's fetched pages. */
        CHUNK_FETCH("Generating terrain...", 900),

        /** Chunks meshed and attached to the scene. Fed from the pager's attached pages. */
        CHUNK_MESH("Building meshes...", 370);

        private final String label;
        private final int weight;

        Step(String label, int weight) {
            this.label = label;
            this.weight = weight;
        }

        public String getLabel() {
            return label;
        }

        public int getWeight() {
            return weight;
        }
    }

    /** Shown until the first {@link #beginStep(Step)} : the very first frames have nothing to report yet. */
    public static final String INITIAL_LABEL = "Loading...";

    /**
     * How fast the painted value chases the target, per second. 6 gives a ~0.17 s time constant : fast
     * enough that the bar is never perceived as lagging behind, slow enough that a whole step landing at
     * once reads as a sweep rather than a jump.
     */
    private static final float EASE_RATE = 6f;

    /**
     * Floor on the chase, in progress units per second. The exponential term alone becomes vanishingly
     * slow near the target, which would leave the bar visibly short of a step it has actually finished.
     */
    private static final float MIN_SLEW = 0.05f;

    private static final Step[] ALL_STEPS = Step.values();

    /** A world switch replays only these : the atlas, the blocks and the GUI theme are already loaded. */
    private static final Step[] WORLD_SWITCH_STEPS = {
            Step.WORLD_STATES, Step.WORLD_INIT, Step.CHUNK_FETCH, Step.CHUNK_MESH
    };

    /** Per-step completion in [0, 1], indexed by ordinal. Only meaningful for the active steps. */
    private final float[] fraction = new float[ALL_STEPS.length];
    private final boolean[] active = new boolean[ALL_STEPS.length];

    /** Sum of the active steps' weights : the denominator. Zero until a begin* call. */
    private int activeWeight;

    private Step current;
    private float target;
    private float displayed;

    /**
     * Starts a load made of exactly these steps, in any order. Only the steps listed weigh on the bar,
     * which is what lets a cold start and a world switch (two different subsets) both span 0 to 100%.
     */
    public void begin(Step... steps) {
        beginSteps(steps);
    }

    /**
     * Starts a runtime world switch : only the world and chunk steps are active, so the bar spans them
     * over the whole 0 to 100%. Also resets the per-step maxima, which is what stops the previous (fully
     * loaded) world from flashing ~100% for the frame between the menu closing and the teardown.
     */
    public void beginWorldSwitch() {
        beginSteps(WORLD_SWITCH_STEPS);
    }

    private void beginSteps(Step[] steps) {
        for (int i = 0; i < ALL_STEPS.length; i++) {
            fraction[i] = 0f;
            active[i] = false;
        }
        activeWeight = 0;
        for (Step step : steps) {
            active[step.ordinal()] = true;
            activeWeight += step.getWeight();
        }
        current = null;
        target = 0f;
        displayed = 0f;
    }

    /**
     * Announces the step now starting : sets the label, nothing else. Callers running blocking work
     * should announce a step <em>before</em> the frame that step will block, not after — see the init pump.
     */
    public void beginStep(Step step) {
        current = step;
    }

    /**
     * Reports how far a step has got, in [0, 1]. Never lowers what was already reported, for that step or
     * for the whole.
     */
    public void setStepFraction(Step step, float value) {
        int i = step.ordinal();
        if (!active[i]) {
            return;
        }
        float clamped = value < 0f ? 0f : Math.min(value, 1f);
        if (clamped <= fraction[i]) {
            return;
        }
        fraction[i] = clamped;
        recomputeTarget();
        if (clamped >= 1f && step == current) {
            advanceLabel();
        }
    }

    /**
     * Moves the label on to the next active step that is not done yet, once the current one completes.
     * Callers that drive their own sequence (the init pump) announce each step explicitly and never rely
     * on this. It is what carries the label through the phases nobody announces : the hand-off from
     * generating chunks to meshing them, and, on a world switch, from the horizon to the chunks.
     */
    private void advanceLabel() {
        for (int i = current.ordinal() + 1; i < ALL_STEPS.length; i++) {
            if (active[i] && fraction[i] < 1f) {
                current = ALL_STEPS[i];
                return;
            }
        }
    }

    /** Marks a step wholly done. */
    public void completeStep(Step step) {
        setStepFraction(step, 1f);
    }

    /**
     * Marks the whole load done. Called when the world builder dismisses the splash : the build is over
     * whatever the counters say, and they routinely say a little less than everything : pages the pager
     * evicted mid-build, or the stall guard cutting a build short. Better a bar that reaches 100% than
     * one abandoned at 97%.
     */
    public void completeAll() {
        for (int i = 0; i < ALL_STEPS.length; i++) {
            if (active[i]) {
                fraction[i] = 1f;
            }
        }
        target = 1f;
    }

    private void recomputeTarget() {
        if (activeWeight <= 0) {
            return;
        }
        float weighted = 0f;
        for (int i = 0; i < ALL_STEPS.length; i++) {
            if (active[i]) {
                weighted += fraction[i] * ALL_STEPS[i].getWeight();
            }
        }
        float value = weighted / activeWeight;
        if (value > target) {
            target = value;
        }
    }

    /**
     * Advances the painted value towards the target and returns it, in [0, 1]. Never decreases.
     *
     * @param tpf time since the previous frame, in seconds
     */
    public float update(float tpf) {
        float delta = target - displayed;
        if (delta > 0f) {
            float eased = delta * Math.min(1f, tpf * EASE_RATE);
            float floor = Math.min(delta, tpf * MIN_SLEW);
            displayed += Math.max(eased, floor);
        }
        return displayed;
    }

    /** The painted value, in [0, 1], without advancing it. */
    public float getDisplayed() {
        return displayed;
    }

    /** The painted value as a whole percentage, clamped to [0, 100]. */
    public int getDisplayedPercent() {
        int percent = (int) (displayed * 100);
        if (percent < 0) {
            return 0;
        }
        return Math.min(percent, 100);
    }

    /** What to tell the player right now. Never null. */
    public String getLabel() {
        return current == null ? INITIAL_LABEL : current.getLabel();
    }

    /** True once every active step is done — the loading itself, not the eased bar catching up. */
    public boolean isComplete() {
        return target >= 1f;
    }
}
