package org.delaunois.ialon;

import org.delaunois.ialon.sound.AmbienceScheduler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the rhythm of the ambience without a sound device. {@link AmbienceScheduler} exists as a
 * separate, audio-free class precisely so these properties — how many voices, how often, how loud —
 * can be asserted instead of being judged by ear in a running game.
 */
class AmbienceSchedulerTest {

    private static final float TPF = 1f / 60f;

    /** The bird profile as IalonConfig ships it. */
    private static AmbienceScheduler.Profile birds() {
        return new AmbienceScheduler.Profile(7, 3, 1.0f, 2.5f, 14f, 8f, 30f, 0.07f);
    }

    /** The owl profile : a single layer, a 2-clip bank, very long gaps. */
    private static AmbienceScheduler.Profile owls() {
        return new AmbienceScheduler.Profile(2, 1, 2.2f, 25f, 70f, 20f, 45f, 0.04f);
    }

    /** One recorded call. */
    private static class Call {
        final int clip;
        final float gain;
        final float pitch;
        final float azimuth;
        final float distance;
        final float height;
        final float time;

        Call(int clip, float gain, float pitch, float azimuth, float distance, float height, float time) {
            this.clip = clip;
            this.gain = gain;
            this.pitch = pitch;
            this.azimuth = azimuth;
            this.distance = distance;
            this.height = height;
            this.time = time;
        }
    }

    /** Runs the scheduler for {@code seconds} at a fixed intensity and returns everything it fired. */
    private static List<Call> run(AmbienceScheduler.Profile profile, float intensity, float seconds, long seed) {
        AmbienceScheduler scheduler = new AmbienceScheduler(profile, new Random(seed));
        List<Call> calls = new ArrayList<>();
        float[] clock = {0f};
        for (float t = 0; t < seconds; t += TPF) {
            clock[0] = t;
            scheduler.update(TPF, intensity,
                    (clip, gain, pitch, azimuth, distance, height) ->
                            calls.add(new Call(clip, gain, pitch, azimuth, distance, height, clock[0])));
        }
        return calls;
    }

    @Test
    void silentWhenIntensityIsZero() {
        assertTrue(run(birds(), 0f, 600f, 1).isEmpty(), "a clearing must be silent");
        assertTrue(run(owls(), 0f, 600f, 1).isEmpty());
    }

    @Test
    void goesFullySilentAsTheSmoothedIntensityDecays() {
        // The case a plain "intensity == 0" test cannot catch, and the one that actually shipped broken :
        // AmbientSoundState smooths the intensity exponentially towards its target, so at nightfall the
        // TARGET is a clean zero but the smoothed VALUE only approaches it — staying a tiny positive
        // float for minutes. Layer 0's threshold used to be 0, so it kept firing, and the gain floor
        // meant those calls were still played at 40% volume. Birds all night.
        //
        // So : decay exactly as the state does, and require real silence well before the night is over.
        AmbienceScheduler scheduler = new AmbienceScheduler(birds(), new Random(31));
        float intensity = 1f;
        float lastCall = -1f;
        float[] clock = {0f};
        for (float t = 0; t < 300f; t += TPF) {
            clock[0] = t;
            intensity += (0f - intensity) * Math.min(1f, TPF / 2f);   // SMOOTHING_TIME = 2 s
            final float[] at = {-1f};
            scheduler.update(TPF, intensity, (c, g, p, a, d, h) -> at[0] = clock[0]);
            if (at[0] >= 0f) {
                lastCall = at[0];
            }
        }
        assertTrue(lastCall >= 0f, "nothing played at all — the test is not exercising anything");
        assertTrue(lastCall < 30f,
                "still calling " + lastCall + "s after the intensity started decaying towards zero");
    }

    @Test
    void layerCountFollowsIntensity() {
        // Counting overlapping calls would NOT measure this : a single layer whose gap is shorter than
        // a clip already overlaps itself. What "layer k is on" means is that its timer contributes to
        // the call rate, so the rate is the thing to measure — with a flat-gap profile (minGap ==
        // maxGap) so that intensity moves the layer count and nothing else.
        //
        // Layer k re-arms every gap * (1 + 0.6k), so the expected rate in units of 1/gap is
        //   1 layer -> 1.000     2 layers -> 1.625     3 layers -> 2.080
        float gap = 4f;
        float seconds = 40000f;
        AmbienceScheduler.Profile flat = new AmbienceScheduler.Profile(7, 3, 1f, gap, gap, 8f, 30f, 0.07f);

        assertRate(run(flat, 0.2f, seconds, 3).size(), seconds / gap, 1.000f, "edge of a wood : one layer");
        assertRate(run(flat, 0.5f, seconds, 3).size(), seconds / gap, 1.625f, "half-way : two layers");
        assertRate(run(flat, 1.0f, seconds, 3).size(), seconds / gap, 2.080f, "heart of a wood : three layers");
    }

    private static void assertRate(int calls, float perLayer, float expectedLayers, String what) {
        float measured = calls / perLayer;
        assertTrue(Math.abs(measured - expectedLayers) < 0.08f,
                what + " : expected ~" + expectedLayers + " layers' worth of calls, measured " + measured);
    }

    /** Highest number of calls whose {@code clipLength} windows overlap. */
    private static int maxConcurrent(List<Call> calls, float clipLength) {
        int max = 0;
        for (Call a : calls) {
            int n = 0;
            for (Call b : calls) {
                if (b.time <= a.time && a.time < b.time + clipLength) {
                    n++;
                }
            }
            max = Math.max(max, n);
        }
        return max;
    }

    @Test
    void callsGetDenserAsIntensityRises() {
        int sparse = run(birds(), 0.35f, 900f, 5).size();
        int dense = run(birds(), 1.0f, 900f, 5).size();
        assertTrue(dense > sparse * 2,
                "the heart of a wood must be far busier than its edge (" + sparse + " -> " + dense + ")");
    }

    @Test
    void gapsStayWithinTheProfileEvenWithJitter() {
        // Jitter is +/-40% of the interpolated gap, and layer k is 1 + 0.6k times sparser. At full
        // intensity the tightest possible gap is therefore minGap * 0.6 on layer 0.
        List<Call> calls = run(birds(), 1.0f, 900f, 7);
        assertFalse(calls.isEmpty());
        for (int i = 1; i < calls.size(); i++) {
            float dt = calls.get(i).time - calls.get(i - 1).time;
            assertTrue(dt >= 0f, "calls must come out in time order");
        }
        // Per layer we cannot tell which is which from the outside, so assert the aggregate bound : with
        // 3 layers each no tighter than minGap * 0.6, the mean gap cannot collapse below a third of that.
        float span = calls.get(calls.size() - 1).time - calls.get(0).time;
        float meanGap = span / (calls.size() - 1);
        assertTrue(meanGap >= 2.5f * 0.6f / 3f, "mean gap collapsed to " + meanGap + " s");
        assertTrue(meanGap <= 14f, "mean gap of " + meanGap + " s is sparser than maxGap");
    }

    @Test
    void gainPitchAndPlacementStayInRange() {
        for (Call c : run(birds(), 1.0f, 900f, 11)) {
            // volume 1.0 * [0.55, 1.0] * (0.4 + 0.6 * 1.0)
            assertTrue(c.gain >= 0.55f && c.gain <= 1.0f, "gain out of range : " + c.gain);
            assertTrue(c.pitch >= 0.93f && c.pitch <= 1.07f, "pitch out of range : " + c.pitch);
            assertTrue(c.azimuth >= 0f && c.azimuth < (float) (Math.PI * 2), "azimuth out of range");
            assertTrue(c.distance >= 8f && c.distance <= 30f, "distance out of range : " + c.distance);
            assertTrue(c.height >= 2f && c.height <= 10f, "height out of range : " + c.height);
            assertTrue(c.clip >= 0 && c.clip < 7, "clip index out of range : " + c.clip);
        }
    }

    @Test
    void gainScalesDownWithIntensity() {
        float loudest = 0f;
        for (Call c : run(birds(), 0.2f, 900f, 13)) {
            loudest = Math.max(loudest, c.gain);
        }
        // 1.0 * 1.0 * (0.4 + 0.6 * 0.2) = 0.52 -- a distant edge is audibly quieter than a full wood.
        assertTrue(loudest <= 0.52f + 1e-4f, "a low-intensity call reached " + loudest);
    }

    @Test
    void owlProfileIsOneSparseAlternatingVoice() {
        List<Call> calls = run(owls(), 1.0f, 3600f, 17);
        assertFalse(calls.isEmpty());
        assertEquals(1, maxConcurrent(calls, 2f), "owls are solitary : never two at once");

        float span = calls.get(calls.size() - 1).time - calls.get(0).time;
        float meanGap = span / (calls.size() - 1);
        assertTrue(meanGap >= 25f * 0.6f, "owls hooting every " + meanGap + " s is not solitary");

        // With only 2 clips, "never twice in a row" means strict alternation. This is the case the
        // re-draw exists for : a naive "keep drawing until different" is a coin flip on a 2-clip bank.
        for (int i = 1; i < calls.size(); i++) {
            assertTrue(calls.get(i).clip != calls.get(i - 1).clip,
                    "the same owl clip played twice in a row at " + calls.get(i).time + " s");
        }
    }

    @Test
    void aLayerNeverRepeatsItselfBackToBack() {
        // A single layer, so consecutive calls all come from the same one : any repeat is a bug.
        AmbienceScheduler.Profile single = new AmbienceScheduler.Profile(7, 1, 1f, 2.5f, 14f, 8f, 30f, 0.07f);
        List<Call> calls = run(single, 1.0f, 1800f, 19);
        assertTrue(calls.size() > 100, "not enough calls to be conclusive : " + calls.size());
        for (int i = 1; i < calls.size(); i++) {
            assertTrue(calls.get(i).clip != calls.get(i - 1).clip, "clip repeated at " + calls.get(i).time + " s");
        }
    }

    @Test
    void everyClipInTheBankGetsUsed() {
        boolean[] seen = new boolean[7];
        for (Call c : run(birds(), 1.0f, 900f, 23)) {
            seen[c.clip] = true;
        }
        for (int i = 0; i < seen.length; i++) {
            assertTrue(seen[i], "clip " + i + " never played");
        }
    }

    @Test
    void walkingIntoAWoodDoesNotDumpABacklog() {
        // Layers keep counting down while inaudible ; if they did not, arriving in a wood after a long
        // walk in the open would fire every layer at once. Ten minutes in a clearing, then full density.
        AmbienceScheduler scheduler = new AmbienceScheduler(birds(), new Random(29));
        List<Call> calls = new ArrayList<>();
        for (float t = 0; t < 600f; t += TPF) {
            scheduler.update(TPF, 0f, (c, g, p, a, d, h) -> calls.add(new Call(c, g, p, a, d, h, 0f)));
        }
        assertTrue(calls.isEmpty());
        int burst = 0;
        for (float t = 0; t < 1f; t += TPF) {
            final int[] n = {0};
            scheduler.update(TPF, 1f, (c, g, p, a, d, h) -> n[0]++);
            burst += n[0];
        }
        assertTrue(burst <= 3, "burst of " + burst + " calls in the first second after entering a wood");
    }
}
