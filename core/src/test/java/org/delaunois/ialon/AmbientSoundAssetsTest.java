package org.delaunois.ialon;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.audio.AudioBuffer;
import com.jme3.audio.AudioData;
import com.jme3.audio.AudioKey;
import com.jme3.math.Vector2f;

import org.delaunois.ialon.blocks.generator.NoiseTerrainGenerator;
import org.junit.jupiter.api.Test;

import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the two things {@code AmbientSoundState} silently depends on and that nothing else would
 * catch : that the baked clips are there and in the format OpenAL needs, and that the forest signal
 * driving them is not degenerate. Both failures are inaudible-by-omission — the game would run fine
 * and simply never make a sound — so they need asserting rather than listening for.
 *
 * No audio device is involved : the Ogg Vorbis decoder (jme3-jogg) is pure Java, so the clips load
 * headless in CI exactly as they do at runtime.
 */
class AmbientSoundAssetsTest {

    private static final int BIRD_CLIPS = 7;
    private static final int OWL_CLIPS = 2;
    /** What local/tools/build-ambience-sounds.sh bakes. */
    private static final int EXPECTED_RATE = 22050;

    @Test
    void everyAmbienceClipIsPresentMonoAndBuffered() {
        DesktopAssetManager assetManager = new DesktopAssetManager(true);
        for (int i = 1; i <= BIRD_CLIPS; i++) {
            assertClip(assetManager, "Sounds/bird-" + i + ".ogg");
        }
        for (int i = 1; i <= OWL_CLIPS; i++) {
            assertClip(assetManager, "Sounds/owl-" + i + ".ogg");
        }
    }

    @Test
    void theWaterBedIsPresentStereoAndLongEnoughToLoop() {
        // A bed's requirements are the INVERSE of a one-shot's, which is why they are asserted apart.
        // Stereo is the point : it is played non-positionally to surround the listener, so a mono file
        // slipping in would flatten it — the exact opposite of the mono rule above.
        AudioData data = waterBed();
        assertEquals(2, data.getChannels(), "the water bed is non-positional : it must stay stereo");
        // The rate is NOT pinned : the bake measures the material and picks the lowest rate that keeps
        // everything 40 dB down, so pinning it here would fail every time the source is swapped. Only
        // the sane band is asserted.
        assertTrue(data.getSampleRate() >= 8000 && data.getSampleRate() <= 48000,
                "odd sample rate : " + data.getSampleRate());
        // Short loops give themselves away by their period. The bake trims LOOP_XFADE off the source,
        // so this also catches a crossfade length that ran away.
        assertTrue(data.getDuration() > 5f, "a " + data.getDuration() + "s loop would be audibly repetitive");
    }

    @Test
    void theWaterBedWrapsWithoutAClick() {
        // The bed plays on repeat, so its last sample runs straight into its first. If those two do not
        // line up, every loop period ticks.
        //
        // This is not hypothetical : the bake used to resample the head and the body SEPARATELY, which
        // gave each its own soxr edge transient, and the body's landed exactly on the loop point. It
        // went unnoticed through a whole source file — the first water.ogg's material had sample steps
        // 40x larger, which buried it — and only showed when the source was replaced by a low-frequency
        // one. Hence this test rather than another visual check.
        AudioData data = waterBed();
        assertEquals(AudioData.DataType.Buffer, data.getDataType(), "need the decoded PCM to look at");
        ShortBuffer pcm = ((AudioBuffer) data).getData().order(ByteOrder.nativeOrder()).asShortBuffer();
        int n = pcm.limit();
        assertTrue(n > 1000, "suspiciously short buffer : " + n);

        // Look at a WINDOW spanning the wrap, not just the single last-to-first step. A resampler edge
        // transient is filter ringing spread over tens of samples after the seam, not a one-sample jump
        // — a first version of this test compared only the two end samples and passed happily on a
        // file whose wrap was plainly ticking.
        int channels = data.getChannels();
        int window = (int) (0.005f * data.getSampleRate()) * channels;   // 5 ms is plenty : see below
        int worst = 0;
        for (int i = 0; i < window; i++) {
            // Circular : index 0's predecessor is the last frame, which is what looping actually does.
            worst = Math.max(worst, Math.abs(pcm.get(i) - pcm.get(Math.floorMod(i - channels, n))));
        }
        int typical = percentileStep(pcm, n, channels, 99.9f);

        // Measured on this very material, good bake vs the broken one : 0.83x and 3.52x the 99.9th
        // percentile respectively, and the numbers do not move with the window size (the offending
        // steps sit in the first few ms). 2x sits cleanly between them.
        assertTrue(worst <= typical * 2,
                "the loop wrap peaks at a step of " + worst + " against a 99.9th-percentile step of "
                        + typical + " (" + (worst / (float) typical) + "x) — an audible tick every loop");
    }

    /** The p-th percentile of the per-channel sample-to-sample step across the whole buffer. */
    private static int percentileStep(ShortBuffer pcm, int n, int channels, float p) {
        int[] steps = new int[n - channels];
        for (int i = channels; i < n; i++) {
            steps[i - channels] = Math.abs(pcm.get(i) - pcm.get(i - channels));
        }
        Arrays.sort(steps);
        return steps[(int) ((steps.length - 1) * (p / 100f))];
    }

    private static AudioData waterBed() {
        AudioData data = new DesktopAssetManager(true)
                .loadAudio(new AudioKey("Sounds/loop-water.ogg", false));
        assertNotNull(data, "Sounds/loop-water.ogg is missing — re-run local/tools/build-ambience-sounds.sh");
        return data;
    }

    private static void assertClip(DesktopAssetManager assetManager, String path) {
        // DataType.Buffer, as the state loads them : only a buffered node can playInstance(), and a
        // Stream would throw at the first call instead of here.
        AudioData data = assetManager.loadAudio(new AudioKey(path, false));
        assertNotNull(data, path + " is missing — re-run local/tools/build-ambience-sounds.sh");
        // Mono is not a preference : OpenAL refuses to spatialise a stereo buffer, it just plays it
        // flat. A stereo clip slipping back in would quietly kill the 3D placement.
        assertEquals(1, data.getChannels(), path + " must be mono for OpenAL to place it in 3D");
        assertEquals(EXPECTED_RATE, data.getSampleRate(), path + " was baked at an unexpected rate");
        assertTrue(data.getDuration() > 0.5f && data.getDuration() < 8f,
                path + " lasts " + data.getDuration() + "s — not a one-shot call");
    }

    @Test
    void theForestSignalSpansItsWholeRange() {
        // treeDensityAt is what decides whether any wildlife is heard at all. If it ever collapsed to a
        // constant — all zero (permanent silence) or all one (birds in the desert) — the ambience would
        // be broken in a way no other test notices.
        NoiseTerrainGenerator generator = new NoiseTerrainGenerator(2, new IalonConfig().getWaterHeight());
        Vector2f sample = new Vector2f();
        int clearings = 0;
        int woods = 0;
        int total = 0;
        for (int z = -600; z < 600; z += 5) {
            for (int x = -600; x < 600; x += 5) {
                float d = generator.treeDensityAt(x, z, sample);
                assertTrue(d >= 0f && d <= 1f, "density out of [0,1] at " + x + "," + z + " : " + d);
                if (d <= 0f) {
                    clearings++;
                } else if (d >= 1f) {
                    woods++;
                }
                total++;
            }
        }
        assertTrue(clearings > total / 10, "barely any clearing : " + clearings + "/" + total);
        assertTrue(woods > total / 20, "barely any dense wood : " + woods + "/" + total);
    }
}
