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

package org.delaunois.ialon.sound;

import com.jme3.math.FastMath;

import java.util.Random;

/**
 * Decides WHEN an ambience one-shot fires, WHICH clip it uses and HOW it is placed — and nothing else.
 * It touches no jME audio class, which is what makes the whole rhythm of the ambience unit-testable
 * without a sound device : {@link org.delaunois.ialon.state.AmbientSoundState} owns the AudioNodes and
 * merely executes what this hands it.
 *
 * The model is a small set of INDEPENDENT layers, each with its own timer. A layer switches on once the
 * intensity passes its share of the range ({@code k / maxLayers}), so a clearing is silent, a wood edge
 * has one voice and the heart of a wood has {@code maxLayers} — the "1 to 3 layers" this was built for.
 * Layers are never in phase : each starts at a random offset and re-arms with an independently jittered
 * gap, so the calls interleave instead of pulsing together.
 *
 * Everything else is randomised per call — gain (the point of the exercise), pitch, azimuth and distance
 * — which is what lets a bank of 7 clips read as a whole wood rather than a 7-item loop.
 *
 * Allocation-free once constructed : it is stepped every frame, and the Android target does not forgive
 * garbage in the render loop.
 */
public class AmbienceScheduler {

    /** Gain spread : a call is drawn between BASE and BASE + SPREAD of the profile volume. */
    private static final float GAIN_BASE = 0.55f;
    private static final float GAIN_SPREAD = 0.45f;
    /** ... and then scaled by the intensity, but never all the way to zero — a lone bird is still a bird. */
    private static final float INTENSITY_GAIN_FLOOR = 0.4f;
    /**
     * Intensity below which NOTHING fires, however faint.
     *
     * Not a micro-optimisation — without it the ambience never actually stops. The caller smooths the
     * intensity exponentially, so it approaches zero asymptotically and stays a tiny positive float for
     * minutes; layer 0's threshold being 0, {@code intensity > 0} kept firing, and INTENSITY_GAIN_FLOOR
     * means those calls still play at 40% gain. That is why birds could still be heard well into the
     * night: the target was a clean zero, the smoothed value never was.
     */
    private static final float SILENCE_FLOOR = 0.02f;
    /** Gap jitter : the computed gap is multiplied by [1 - JITTER, 1 + JITTER]. */
    private static final float GAP_JITTER = 0.4f;
    /** Each deeper layer is this much sparser than the one before, so they do not pile up. */
    private static final float LAYER_SPARSITY = 0.6f;
    /** Vertical offset range of a call relative to the listener : birds are up in the canopy. */
    private static final float MIN_HEIGHT = 2f;
    private static final float MAX_HEIGHT = 10f;

    /** What a triggered call is : receives everything needed to place and play it. */
    @FunctionalInterface
    public interface TriggerConsumer {
        /**
         * @param clip     index into the caller's clip bank
         * @param gain     linear volume, already including the profile volume and the intensity scaling
         * @param pitch    playback rate multiplier
         * @param azimuth  radians around the listener, 0 = +X
         * @param distance world units from the listener
         * @param height   world units above the listener
         */
        void trigger(int clip, float gain, float pitch, float azimuth, float distance, float height);
    }

    /** The tuning of one bank of clips. Two exist : birds by day, owls by night. */
    public static class Profile {
        private final int clipCount;
        private final int maxLayers;
        private final float volume;
        private final float minGap;
        private final float maxGap;
        private final float minDistance;
        private final float maxDistance;
        private final float pitchSpread;

        public Profile(int clipCount, int maxLayers, float volume, float minGap, float maxGap,
                       float minDistance, float maxDistance, float pitchSpread) {
            this.clipCount = clipCount;
            this.maxLayers = Math.max(1, maxLayers);
            this.volume = volume;
            this.minGap = minGap;
            this.maxGap = Math.max(maxGap, minGap);
            this.minDistance = minDistance;
            this.maxDistance = Math.max(maxDistance, minDistance);
            this.pitchSpread = pitchSpread;
        }
    }

    private final Profile profile;
    private final Random random;
    /** Seconds until each layer next fires. */
    private final float[] countdown;
    /** Last clip played by each layer, so a layer never repeats itself back to back. */
    private final int[] lastClip;

    public AmbienceScheduler(Profile profile, Random random) {
        this.profile = profile;
        this.random = random;
        this.countdown = new float[profile.maxLayers];
        this.lastClip = new int[profile.maxLayers];
        for (int k = 0; k < countdown.length; k++) {
            lastClip[k] = -1;
            // Start out of phase, and never all at t=0 : otherwise the first seconds in a wood are a
            // burst of every layer at once. Seeded from minGap rather than maxGap so the ambience is
            // there within seconds of spawning, not up to a full maxGap later.
            countdown[k] = random.nextFloat() * profile.minGap * sparsity(k);
        }
    }

    /**
     * Advances the layer timers by {@code tpf} and fires whatever is due. {@code intensity} is the
     * clamped [0, 1] "how much of this ambience should be heard here and now" — forest density times the
     * day/night curve times the above-ground curve, as computed by the AppState.
     *
     * A layer that is off (intensity below its share) still counts down : it simply does not fire. That
     * is deliberate — it means walking into a wood does not queue up a backlog of calls that all land at
     * once, and walking out does not leave a layer frozen mid-gap.
     */
    public void update(float tpf, float intensity, TriggerConsumer out) {
        float clamped = intensity < 0f ? 0f : Math.min(1f, intensity);
        for (int k = 0; k < countdown.length; k++) {
            // A layer that re-armed while the ambience was quiet is sitting on a maxGap-sized countdown.
            // Walking into a wood must not then be silent for another maxGap : cap what is left at the
            // gap the CURRENT intensity calls for. No jitter here on purpose — the caps differ per layer
            // (sparsity), so the layers stay out of phase, and a deterministic clamp keeps the seeded
            // tests meaningful.
            float cap = maxJitteredGap(k, clamped);
            if (countdown[k] > cap) {
                countdown[k] = cap;
            }
            countdown[k] -= tpf;
            if (countdown[k] > 0f) {
                continue;
            }
            // Re-arm first, so an inactive layer keeps cycling rather than firing on every frame.
            countdown[k] = nextGap(k, clamped);
            if (clamped <= layerThreshold(k)) {
                continue;
            }
            out.trigger(pickClip(k), gain(clamped), pitch(), random.nextFloat() * FastMath.TWO_PI,
                    lerp(profile.minDistance, profile.maxDistance, random.nextFloat()),
                    lerp(MIN_HEIGHT, MAX_HEIGHT, random.nextFloat()));
        }
    }

    /**
     * The intensity above which layer {@code k} is heard. Layer 0's share is 0, so it is the one that
     * needs {@link #SILENCE_FLOOR} to have a real floor to sit on.
     */
    private float layerThreshold(int k) {
        return Math.max(SILENCE_FLOOR, (float) k / countdown.length);
    }

    private float sparsity(int k) {
        return 1f + k * LAYER_SPARSITY;
    }

    /** Denser as the intensity rises, sparser on the deeper layers, jittered so nothing is metronomic. */
    private float nextGap(int k, float intensity) {
        return maxJitteredGap(k, intensity) * (1f - GAP_JITTER + random.nextFloat() * 2f * GAP_JITTER)
                / (1f + GAP_JITTER);
    }

    /** The longest gap layer {@code k} can draw at this intensity — the jitter's upper bound. */
    private float maxJitteredGap(int k, float intensity) {
        return lerp(profile.maxGap, profile.minGap, intensity) * sparsity(k) * (1f + GAP_JITTER);
    }

    private int pickClip(int k) {
        int clip = random.nextInt(profile.clipCount);
        if (profile.clipCount > 1 && clip == lastClip[k]) {
            // One re-draw, shifted rather than looped : with a 2-clip bank (the owls) a "keep drawing
            // until different" loop is a coin flip that can run long, and this simply alternates.
            clip = (clip + 1 + random.nextInt(profile.clipCount - 1)) % profile.clipCount;
        }
        lastClip[k] = clip;
        return clip;
    }

    private float gain(float intensity) {
        return profile.volume
                * (GAIN_BASE + GAIN_SPREAD * random.nextFloat())
                * (INTENSITY_GAIN_FLOOR + (1f - INTENSITY_GAIN_FLOOR) * intensity);
    }

    private float pitch() {
        return 1f + (random.nextFloat() * 2f - 1f) * profile.pitchSpread;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
