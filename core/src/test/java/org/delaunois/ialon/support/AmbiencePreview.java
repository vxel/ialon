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

package org.delaunois.ialon.support;

import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AppState;
import com.jme3.audio.AudioData;
import com.jme3.audio.AudioListenerState;
import com.jme3.audio.AudioNode;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.system.AppSettings;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.sound.AmbienceScheduler;
import org.delaunois.ialon.sound.Voices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

/**
 * Auditions the wildlife ambience for real — actual OpenAL, actual 3D placement — without loading a
 * world. It runs the same {@link AmbienceScheduler} and the same positioned {@code playInstance()} as
 * {@code AmbientSoundState}, but feeds it a scripted intensity instead of the forest field, so the
 * whole day → dusk → night arc plays out in a minute.
 *
 * <p>This is the step {@code local/tools/preview-ambience.sh} cannot do : that one mixes offline and
 * fakes the spatialisation with a stereo pan, which is enough to settle the rhythm but says nothing
 * about whether the clips decode, whether OpenAL accepts them, or what the placement actually sounds
 * like. Here the listener slowly turns, so a call really does move across the stereo field.
 *
 * <p>Run it through the Gradle task (needs a sound device, and a display for the jME window) :
 * <pre>
 * ./gradlew :core:ambiencePreview                        # 60 s : day, dusk, night
 * ./gradlew :core:ambiencePreview --args="--night"       # owls only, at full intensity
 * ./gradlew :core:ambiencePreview --args="--day 30"      # birds only, 30 s
 * </pre>
 *
 * @author Cedric de Launois
 */
public class AmbiencePreview extends SimpleApplication {

    private static final Logger log = LoggerFactory.getLogger(AmbiencePreview.class);

    private static final float REF_DISTANCE = 10f;
    private static final float MAX_DISTANCE = 80f;
    /** The listener turns a full circle over this many seconds, so placement is audible. */
    private static final float TURN_PERIOD = 20f;
    /** Must match AmbientSoundState.SMOOTHING_TIME — see simpleUpdate. */
    private static final float SMOOTHING_TIME = 2f;

    private final IalonConfig config = new IalonConfig();
    private final Random random = new Random();
    private final Mode mode;
    private final float duration;

    private Voices birdVoices;
    private Voices owlVoices;
    private float gate = 1f;
    private final Vector3f placement = new Vector3f();
    private AmbienceScheduler birds;
    private AmbienceScheduler owls;
    private float elapsed = 0f;
    private float dayIntensity = 0f;
    private float nightIntensity = 0f;
    private int calls = 0;
    private AudioNode waterBed;
    private AudioNode toneNode;
    private boolean waterPlaying = false;
    private float waterLevel = 0f;

    private enum Mode { ARC, DAY, NIGHT, WATER, DIVE, TONE, BEDONLY }

    public AmbiencePreview(Mode mode, float duration) {
        super((AppState[]) null);
        this.mode = mode;
        this.duration = duration;
    }

    @Override
    public void simpleInitApp() {
        if (getAudioRenderer() == null) {
            log.error("No audio renderer — nothing to preview");
            stop();
            return;
        }
        stateManager.attach(new AudioListenerState());
        getListener().setVolume(1f);

        // The REAL Voices, not a copy : this harness exists to exercise the shipped playback path.
        birdVoices = new Voices(assetManager, "Sounds/bird-%d.ogg", 7);
        owlVoices = new Voices(assetManager, "Sounds/owl-%d.ogg", 2);
        birds = new AmbienceScheduler(new AmbienceScheduler.Profile(
                birdVoices.clipCount(), config.getBirdMaxLayers(), config.getBirdVolume(),
                config.getBirdMinGapSeconds(), config.getBirdMaxGapSeconds(),
                config.getBirdMinDistance(), config.getBirdMaxDistance(),
                config.getBirdPitchSpread()), random);
        owls = new AmbienceScheduler(new AmbienceScheduler.Profile(
                owlVoices.clipCount(), config.getOwlMaxLayers(), config.getOwlVolume(),
                config.getOwlMinGapSeconds(), config.getOwlMaxGapSeconds(),
                config.getOwlMinDistance(), config.getOwlMaxDistance(),
                config.getOwlPitchSpread()), random);

        waterBed = new AudioNode(assetManager, "Sounds/loop-water.ogg", AudioData.DataType.Buffer);
        waterBed.setName("Sounds/loop-water.ogg");
        waterBed.setPositional(false);
        waterBed.setLooping(true);
        waterBed.setReverbEnabled(false);
        waterBed.setVolume(0f);
        // A plain continuous tone, generated outside the project, for the isolating --tone case.
        String tonePath = System.getProperty("ialon.tone");
        if (tonePath != null) {
            java.io.File f = new java.io.File(tonePath);
            assetManager.registerLocator(f.getParent(), com.jme3.asset.plugins.FileLocator.class);
            toneNode = new AudioNode(assetManager, f.getName(), AudioData.DataType.Buffer);
            toneNode.setName(f.getName());
        }

        AudioData bedData = waterBed.getAudioData();
        log.info("  Sounds/loop-water.ogg : {} Hz, {} ch, {} s (loop period)",
                bedData.getSampleRate(), bedData.getChannels(), bedData.getDuration());

        log.info("Ambience preview : {} for {}s — {} bird clips, {} owl clips",
                mode, (int) duration, birdVoices.clipCount(), owlVoices.clipCount());
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (birds == null) {
            return;
        }
        elapsed += tpf;

        // TONE / BEDONLY : the isolating cases. Nothing is touched after play() — no listener rotation,
        // no per-frame setVolume, no scheduler. Whatever ticks here ticks below our code.
        if (mode == Mode.TONE || mode == Mode.BEDONLY) {
            if (!waterPlaying) {
                AudioNode n = mode == Mode.TONE ? toneNode : waterBed;
                n.setLooping(true);
                n.setPositional(false);
                n.setVolume(mode == Mode.TONE ? 0.35f : config.getWaterBedVolume());
                n.play();
                waterPlaying = true;
                log.info("playing {} — nothing will be touched from now on", n.getName());
            }
            if (elapsed > duration) {
                log.info("Done");
                stop();
            }
            return;
        }

        if (elapsed > duration) {
            log.info("Done — {} calls played", calls);
            stop();
            return;
        }

        // Turn the camera (and so the listener, via AudioListenerState) so placement is audible.
        float angle = elapsed / TURN_PERIOD * FastMath.TWO_PI;
        cam.lookAtDirection(new Vector3f(FastMath.cos(angle), 0, FastMath.sin(angle)), Vector3f.UNIT_Y);

        // DIVE : birds at full density, then the listener goes under half way through and comes back
        // up near the end. This is the one that shows whether a call ALREADY SOUNDING is cut : if the
        // gate did not reach the playing voices, a bird would keep singing past the dive.
        if (mode == Mode.DIVE) {
            boolean submerged = elapsed > duration * 0.5f && elapsed < duration * 0.85f;
            float target = submerged ? 0f : 1f;
            gate += (target - gate) * Math.min(1f, tpf / 0.12f);
            if (gate < 0.02f) {
                gate = 0f;
                birdVoices.stopAll();
            } else {
                birdVoices.applyGate(gate);
            }
            updateWaterBed(tpf, submerged ? config.getWaterBedVolume() : 0f);
            float audible = gate <= 0f ? 0f : 1f;
            birds.update(tpf, audible, (c, g, p, a, d, h) -> play(birdVoices, c, g, p, a, d, h));
            return;
        }

        // WATER : dry for a moment, then submerged for the bulk of the take (long enough to hear the
        // loop wrap several times), then dry again — so both fades and the seam get exercised.
        if (mode == Mode.WATER) {
            boolean submerged = elapsed > 3f && elapsed < duration - 3f;
            updateWaterBed(tpf, submerged ? config.getWaterBedVolume() : 0f);
            return;
        }

        float day;
        float night;
        switch (mode) {
            case DAY:
                day = 1f;
                night = 0f;
                break;
            case NIGHT:
                day = 0f;
                night = 1f;
                break;
            default:
                // The arc : full day for the first third, a crossfade over the middle, night for the rest.
                float t = elapsed / duration;
                day = 1f - clamp01((t - 0.33f) / 0.34f);
                night = clamp01((t - 0.33f) / 0.34f);
                break;
        }
        // Smooth exactly as AmbientSoundState does. Not cosmetic : feeding the raw curve would reach a
        // clean zero, whereas the real state only ever approaches it — which is precisely the case that
        // once left birds audible all night. The preview has to exercise the same path.
        float k = Math.min(1f, tpf / SMOOTHING_TIME);
        dayIntensity += (day - dayIntensity) * k;
        nightIntensity += (night - nightIntensity) * k;

        birds.update(tpf, dayIntensity, (c, g, p, a, d, h) -> play(birdVoices, c, g, p, a, d, h));
        owls.update(tpf, nightIntensity, (c, g, p, a, d, h) -> play(owlVoices, c, g, p, a, d, h));
    }

    /** The same fade-and-release logic AmbientSoundState.Bed runs, so this previews the real thing. */
    private void updateWaterBed(float tpf, float target) {
        waterLevel += (target - waterLevel) * Math.min(1f, tpf / 0.35f);
        if (waterLevel < 0.005f) {
            waterLevel = 0f;
            if (waterPlaying) {
                waterBed.stop();
                waterPlaying = false;
                log.info("t={}s  water bed OUT", String.format("%5.1f", elapsed));
            }
            return;
        }
        waterBed.setVolume(waterLevel);
        if (!waterPlaying) {
            waterBed.play();
            waterPlaying = true;
            log.info("t={}s  water bed IN", String.format("%5.1f", elapsed));
        }
    }

    private void play(Voices voices, int clip, float gain, float pitch, float azimuth, float distance,
                      float height) {
        Vector3f eye = cam.getLocation();
        placement.set(eye.x + FastMath.cos(azimuth) * distance,
                eye.y + height,
                eye.z + FastMath.sin(azimuth) * distance);
        voices.trigger(clip, gain, pitch, placement, gate);
        calls++;
        log.info("t={}s  clip {}  gain={} pitch={} az={}deg dist={}",
                String.format("%5.1f", elapsed), clip,
                String.format("%.2f", gain), String.format("%.2f", pitch),
                Math.round(azimuth * FastMath.RAD_TO_DEG), Math.round(distance));
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(1f, v);
    }

    public static void main(String[] args) {
        Mode mode = Mode.ARC;
        float duration = 60f;
        for (String arg : args) {
            if ("--day".equals(arg)) {
                mode = Mode.DAY;
            } else if ("--night".equals(arg)) {
                mode = Mode.NIGHT;
            } else if ("--water".equals(arg)) {
                mode = Mode.WATER;
            } else if ("--dive".equals(arg)) {
                mode = Mode.DIVE;
            } else if ("--tone".equals(arg)) {
                mode = Mode.TONE;
            } else if ("--bedonly".equals(arg)) {
                mode = Mode.BEDONLY;
            } else {
                duration = Float.parseFloat(arg);
            }
        }

        AmbiencePreview app = new AmbiencePreview(mode, duration);
        AppSettings settings = new AppSettings(true);
        settings.setResolution(480, 270);
        settings.setTitle("Ialon — ambience preview");
        settings.setAudioRenderer(AppSettings.LWJGL_OPENAL);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false);
        app.start();
    }
}
