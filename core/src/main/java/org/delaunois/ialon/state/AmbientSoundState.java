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

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.audio.AudioData;
import com.jme3.audio.AudioNode;
import com.jme3.math.FastMath;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.blocks.TypeIds;
import org.delaunois.ialon.blocks.generator.NoiseTerrainGenerator;
import org.delaunois.ialon.blocks.generator.TerrainGenerator;
import org.delaunois.ialon.control.SunControl;
import org.delaunois.ialon.sound.AmbienceScheduler;
import org.delaunois.ialon.sound.Voices;

import java.util.Random;

import lombok.extern.slf4j.Slf4j;

/**
 * Plays the ambience of the world around the player, in two mechanisms that share nothing but this
 * state's environment probe :
 * <ul>
 *   <li><b>Wildlife one-shots</b> — birds by day, owls by night, placed in 3D. Two banks, each driven
 *       by its own {@link AmbienceScheduler} (the audio-free class that owns the rhythm — see it for
 *       the layer model). This state does the two things the scheduler cannot : it works out how much
 *       ambience belongs here and now, and it turns a triggered call into a positioned, held voice
 *       (see {@link org.delaunois.ialon.sound.Voices} for why they are held rather than fired off with {@code playInstance}).</li>
 *   <li><b>Beds</b> — a single looping, NON-positional node faded in and out : right now the water
 *       heard while submerged. A bed is the opposite of a one-shot in every respect (continuous,
 *       stereo, enveloping, level-controlled rather than scheduled), which is why it goes through
 *       {@link Bed} below rather than through the scheduler.</li>
 * </ul>
 *
 * <h2>Two questions, deliberately kept apart</h2>
 * <b>Are birds calling out there?</b> That is the scheduler's intensity — how often a call fires and
 * how many layers are active. It moves at walking pace, so it is recomputed on a slow throttle and
 * smoothed over {@link #SMOOTHING_TIME} :
 * <ul>
 *   <li><b>forest</b> — {@code getForestDensity * biome.treeDensityMul}, exactly the product
 *       {@link NoiseTerrainGenerator} itself uses to decide whether to plant a tree, times
 *       {@link #shoreFade} for the OTHER half of the generator's rule : nothing grows at or below the
 *       shore. Without it the wildlife followed the forest-density field straight out over the sea,
 *       where that field is perfectly happy to read 1 and no tree has ever stood. So the ambience
 *       tracks the trees the player can actually see, and it is a pure function of the seed and the
 *       coordinates : no allocation worth the name, and available even where no chunk is paged in.</li>
 *   <li><b>day</b> — a smoothstep on the sun height. Birds scale with it, owls with {@code 1 - day}, so
 *       dusk is a crossfade rather than a cut : the last birds fall silent while the first owl starts.</li>
 * </ul>
 *
 * <b>Can the listener hear anything at all?</b> That is the GATE, and it is a different thing : it
 * multiplies the volume of calls ALREADY SOUNDING, not just future ones, and it moves fast
 * ({@link #GATE_FADE_TIME}). Two terms, both properties of where the ear is rather than of the wood :
 * <ul>
 *   <li><b>submersion</b> — water or lava, read per frame from {@link UnderwaterState}. Diving is
 *       instantaneous, and this is the term that stops a bird finishing its call under water.</li>
 *   <li><b>altitude</b> — {@link #altitudeFade}, a band : silent in a cave, silent in flight.</li>
 * </ul>
 * Keeping the two apart is what makes both behave : walking out of a wood must NOT cut a call short
 * (that bird is still singing behind you), while diving must cut every call at once.
 *
 * The water bed is the mirror image of the wildlife : it plays only while submerged, and only in water
 * ({@link UnderwaterState#getSubmergedLiquid()}), never in lava.
 *
 * <h2>Positional playback</h2>
 * The one-shot nodes are mono (OpenAL will not spatialise anything else — see local/tools/build-ambience-sounds.sh)
 * and are deliberately NOT attached to the scene graph. The renderer reads {@code getWorldTranslation()},
 * and a spatial with no parent copies its local transform straight into the world one, so
 * {@link AudioNode#updateGeometricState()} right before the play is exact and free. Attaching them to
 * the rootNode instead would place every call at the position it held on the PREVIOUS frame, because
 * the application's own updateGeometricState runs after the app states.
 */
@Slf4j
public class AmbientSoundState extends BaseAppState {

    /** How often the environment is probed. The signal moves at walking pace ; 4 Hz is plenty. */
    private static final long PROBE_THRESHOLD_MS = 250;
    /**
     * Seconds for the smoothed intensity to cover ~63% of a step. Leaving a wood should fade the birds
     * out over a couple of seconds, not switch them off at the last tree.
     */
    private static final float SMOOTHING_TIME = 2f;

    /** Sun height at which the day/night crossfade starts and ends (sin of the sun angle). */
    private static final float DAY_FADE_LOW = -0.05f;
    private static final float DAY_FADE_HIGH = 0.15f;

    /** Depth below the surface over which the ambience fades out, and where the fade starts. */
    private static final float UNDERGROUND_FADE_START = -2f;
    private static final float UNDERGROUND_FADE_DEPTH = 6f;

    /** Height above the terrain at which the ambience starts fading, and over how many blocks it dies. */
    private static final float FLY_FADE_START = 8f;
    private static final float FLY_FADE_DEPTH = 10f;

    /**
     * How far the sea floor has to drop below the tree line before the wildlife is gone : a shallow shelf
     * a couple of blocks off the beach still has the shore's woods within earshot, the open sea does not.
     */
    private static final float SHORE_FADE_DEPTH = 5f;

    /**
     * Seconds over which a call ALREADY IN THE AIR is faded when the listener stops being able to hear
     * it. Deliberately much faster than SMOOTHING_TIME : diving is instantaneous, and a bird carrying on
     * for its remaining three seconds under water is the artefact this exists to kill. Not zero, because
     * cutting a source dead clicks.
     */
    private static final float GATE_FADE_TIME = 0.12f;
    /** Below this the wildlife is off : the voices are stopped outright and no new call is started. */
    private static final float GATE_SILENCE = 0.02f;

    /**
     * Seconds for a bed to fade in or out. Much shorter than SMOOTHING_TIME : ducking under water is an
     * abrupt event and should sound like one, but a hard cut on a running loop is an audible click.
     */
    private static final float BED_FADE_TIME = 0.35f;
    /** Below this the bed is considered off and the source is released. */
    private static final float BED_SILENCE = 0.005f;

    private final IalonConfig config;
    // Reused every probe : the render thread must not allocate (see CLAUDE.md).
    private final Vector2f sample = new Vector2f();
    private final Vector3f probe = new Vector3f();
    private final Vector3f placement = new Vector3f();
    private final Random random = new Random();

    private SimpleApplication app;
    private NoiseTerrainGenerator noise;
    private SunControl sunControl;
    private UnderwaterState underwaterState;

    private Voices birdVoices;
    private Voices owlVoices;
    private AmbienceScheduler birds;
    private AmbienceScheduler owls;
    private Bed waterBed;

    private long lastProbe = 0;
    private float dayTarget = 0f;
    private float nightTarget = 0f;
    private float dayIntensity = 0f;
    private float nightIntensity = 0f;
    private float waterBedTarget = 0f;
    /** Altitude term, refreshed by the throttled probe and consumed by the (per-frame) gate. */
    private float skyFactor = 1f;
    private float gate = 1f;
    private float appliedGate = 1f;

    public AmbientSoundState(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void initialize(Application application) {
        this.app = (SimpleApplication) application;

        // The headless support harnesses start with no audio renderer. Loading the clips would still
        // work (the decode is pure Java) but playing would throw on the first call, so stay off.
        if (app.getAudioRenderer() == null || !config.isAmbientSound()) {
            setEnabled(false);
            return;
        }

        birdVoices = new Voices(app.getAssetManager(), "Sounds/bird-%d.ogg", 7);
        owlVoices = new Voices(app.getAssetManager(), "Sounds/owl-%d.ogg", 2);
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

        // Buffer, not Stream : ~10 s decodes to about 460 kB of PCM, which is cheaper than the decoder
        // thread a stream would need — and jME's streaming has its own quirks around looping.
        AudioNode water = new AudioNode(app.getAssetManager(), "Sounds/loop-water.ogg",
                AudioData.DataType.Buffer);
        water.setName("Sounds/loop-water.ogg");
        waterBed = new Bed(water);
    }

    @Override
    protected void cleanup(Application application) {
        silenceEverything();
    }

    @Override
    protected void onEnable() {
        // Nothing to do : whether anything plays is decided per-frame in update().
    }

    @Override
    protected void onDisable() {
        dayIntensity = 0f;
        nightIntensity = 0f;
        // Everything is stoppable now that the one-shots go through Voices rather than playInstance :
        // disabling the state leaves no tail at all.
        silenceEverything();
    }

    private void silenceEverything() {
        gate = 0f;
        appliedGate = 0f;
        if (birdVoices != null) {
            birdVoices.stopAll();
            owlVoices.stopAll();
        }
        if (waterBed != null) {
            waterBed.silence();
        }
    }

    @Override
    public void update(float tpf) {
        if (!resolveDependencies()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastProbe >= PROBE_THRESHOLD_MS) {
            lastProbe = now;
            probeEnvironment();
        }

        // Submersion is read EVERY frame, unlike the terrain probe above : it is a free field read off
        // UnderwaterState, and diving in has to be heard now, not up to a probe period later.
        UnderwaterState underwater = underwater();
        // The wildlife stops in ANY liquid ; the bed cares WHICH one. Hence the two questions.
        boolean submerged = underwater != null && underwater.isSubmerged();

        updateGate(tpf, submerged);

        // Smooth towards the probed targets. Frame-rate independent : the same wall-clock fade whether
        // the game runs at 30 or 120 fps.
        float k = Math.min(1f, tpf / SMOOTHING_TIME);
        dayIntensity += (dayTarget - dayIntensity) * k;
        nightIntensity += (nightTarget - nightIntensity) * k;

        // Below the gate's silence floor nothing would be heard anyway, so do not start calls : the
        // scheduler's own timers keep cycling on a zero intensity, which is what stops a backlog
        // building up while submerged and bursting on the way out.
        float audible = gate < GATE_SILENCE ? 0f : 1f;
        birds.update(tpf, dayIntensity * audible, this::playBird);
        owls.update(tpf, nightIntensity * audible, this::playOwl);

        // Water only : lava deserves its own sound, and would be plainly wrong with this one.
        waterBedTarget = underwater != null && TypeIds.WATER.equals(underwater.getSubmergedLiquid())
                ? config.getWaterBedVolume() : 0f;
        waterBed.update(tpf, waterBedTarget);
    }

    /**
     * Advances the gate — how much of the wildlife reaches the ear RIGHT NOW — and re-applies it to
     * every call still sounding.
     *
     * The split this embodies is the point. {@code forest} and {@code day} say whether birds are
     * calling out there ; they belong to the scheduler's intensity, and a call already started must NOT
     * be cut short because the player stepped out of the wood — that bird is still singing behind them.
     * Submersion and altitude say whether the LISTENER can hear anything at all, so they have to apply
     * to sound already in the air, which is what the gate does.
     *
     * The whole loop is skipped while the gate is steady, which is almost always : the cost is only paid
     * during the ~0.12 s it actually moves.
     */
    private void updateGate(float tpf, boolean submerged) {
        float target = submerged ? 0f : skyFactor;
        gate += (target - gate) * Math.min(1f, tpf / GATE_FADE_TIME);
        if (gate < GATE_SILENCE) {
            gate = 0f;
        }
        if (Math.abs(gate - appliedGate) < 1e-3f) {
            return;
        }
        appliedGate = gate;
        if (gate <= 0f) {
            birdVoices.stopAll();
            owlVoices.stopAll();
        } else {
            birdVoices.applyGate(gate);
            owlVoices.applyGate(gate);
        }
    }

    /** Recomputes where on the forest / day / above-ground curves the player currently is. */
    private void probeEnvironment() {
        Vector3f eye = app.getCamera().getLocation();

        probe.set(eye.x, 0, eye.z);
        float ground = noise.getHeight(probe);

        // The same signal the generator's own tree scatter uses, so the ambience follows the trees the
        // player can see rather than a lookalike field — BOTH halves of it : the density field says how
        // thick the wood is, the shore test says whether this column can hold a tree at all.
        float forest = noise.treeDensityAt(eye.x, eye.z, sample)
                * shoreFade(ground, noise.getWaterHeight());

        float day = smoothstep(sunControl.getSunHeight(), DAY_FADE_LOW, DAY_FADE_HIGH);

        // Altitude goes to the GATE, not into the targets below : climbing away from the ground has to
        // take the calls already in the air with it, and only the gate reaches those. Folding it in here
        // as well would apply it twice.
        skyFactor = altitudeFade(eye.y - ground);

        // How much wildlife is out there — nothing about whether the listener can hear it.
        dayTarget = forest * day;
        nightTarget = forest * (1f - day);
    }

    /**
     * The live {@link UnderwaterState}, or null if there is none to consult. The submersion test is
     * delegated to it rather than re-derived here : it already owns the per-block liquid test, and one
     * source of truth means the ambience changes at exactly the moment the screen floods with fog. When
     * it is absent (it is attached just before this one, but a harness may not have it) or disabled, the
     * player counts as dry.
     */
    private UnderwaterState underwater() {
        if (underwaterState == null) {
            underwaterState = getStateManager().getState(UnderwaterState.class);
        }
        return underwaterState != null && underwaterState.isEnabled() ? underwaterState : null;
    }

    private void playBird(int clip, float gain, float pitch, float azimuth, float distance, float height) {
        birdVoices.trigger(clip, gain, pitch, around(azimuth, distance, height), gate);
    }

    private void playOwl(int clip, float gain, float pitch, float azimuth, float distance, float height) {
        owlVoices.trigger(clip, gain, pitch, around(azimuth, distance, height), gate);
    }

    /** A point on the cylinder around the listener, reusing the scratch vector. */
    private Vector3f around(float azimuth, float distance, float height) {
        Vector3f eye = app.getCamera().getLocation();
        return placement.set(
                eye.x + FastMath.cos(azimuth) * distance,
                eye.y + height,
                eye.z + FastMath.sin(azimuth) * distance);
    }

    private boolean resolveDependencies() {
        // Re-read the generator every frame rather than caching it once : switching world nulls it in
        // the config (WorldSelectionState) so the lazy accessor rebuilds one on the NEW seed. A cached
        // reference would leave the wildlife following the previous world's forest map. The compare is
        // a reference test, and this state is not re-created on a world switch (it is not part of the
        // world-state group, since it resolves everything lazily).
        TerrainGenerator generator = config.getTerrainGenerator();
        if (generator != noise) {
            if (!(generator instanceof NoiseTerrainGenerator)) {
                // Flat / empty worlds have no forest field to follow, so there is nothing to be ambient
                // about. Not a hard disable : the next world may well have one.
                noise = null;
                return false;
            }
            noise = (NoiseTerrainGenerator) generator;
            // Fresh world, fresh silence : do not carry the previous one's fade across the switch.
            dayIntensity = 0f;
            nightIntensity = 0f;
            lastProbe = 0;
        }
        if (sunControl == null) {
            SunState sunState = getStateManager().getState(SunState.class);
            if (sunState == null) {
                return false;
            }
            sunControl = sunState.getSunControl();
            if (sunControl != null) {
                // Once per world, off the hot path : the one line that tells a bug report whether the
                // ambience was live at all.
                log.info("Ambient wildlife active : {} bird clips, {} owl clips",
                        birdVoices.clipCount(), owlVoices.clipCount());
            }
        }
        return sunControl != null;
    }

    /**
     * A continuous, non-positional ambience loop that is faded towards a target level instead of being
     * switched. It only holds an OpenAL source while it is actually audible : a loop that is never
     * stopped keeps a voice (and its mixing cost) forever, which on the Android target is not free.
     */
    private static final class Bed {

        private final AudioNode node;
        private float level = 0f;
        /** The level OpenAL was last told about, so a steady bed stops talking to it entirely. */
        private float appliedLevel = -1f;
        private boolean playing = false;

        Bed(AudioNode node) {
            this.node = node;
            node.setPositional(false);   // it surrounds the listener ; this is also what keeps it stereo
            node.setLooping(true);
            node.setReverbEnabled(false);
            node.setVolume(0f);
        }

        void update(float tpf, float target) {
            level += (target - level) * Math.min(1f, tpf / BED_FADE_TIME);
            // Land ON the target rather than approaching it forever. An exponential never arrives, and
            // the first version of this pushed a very slightly different gain to OpenAL on EVERY frame
            // for as long as the bed played — 120 alSourcef(AL_GAIN) per second, each taking the
            // renderer's lock while its mixer was working. The one-shots never did that (they only set
            // a volume when triggered), which is why the artefact belonged to the bed alone.
            if (Math.abs(target - level) < BED_SILENCE) {
                level = target;
            }
            if (level < BED_SILENCE) {
                level = 0f;
                if (playing) {
                    node.stop();
                    playing = false;
                    appliedLevel = -1f;
                }
                return;
            }
            if (Math.abs(level - appliedLevel) >= 1e-3f) {
                node.setVolume(level);
                appliedLevel = level;
            }
            if (!playing) {
                // Volume BEFORE play, so it never starts on a full-level blip.
                node.play();
                playing = true;
            }
        }

        void silence() {
            level = 0f;
            appliedLevel = -1f;
            if (playing) {
                node.stop();
                playing = false;
            }
        }
    }

    /**
     * How much of the surface ambience reaches an ear {@code aboveGround} blocks above the terrain — a
     * BAND, not a floor : it dies underground and it dies in the air, and is 1 only in the layer where
     * the woods actually are.
     *
     * The two ends are the same idea for opposite reasons. Below : birds do not sing in caves. Above :
     * a bird calling from a tree is inaudible once you are flying a long way over it, and hearing the
     * dawn chorus at cruising altitude is the single thing that most gives the ambience away as fake.
     *
     * Package-private and static so the thresholds can be asserted without an audio device or a world.
     */
    static float altitudeFade(float aboveGround) {
        float notUnderground = smoothstep(aboveGround,
                UNDERGROUND_FADE_START - UNDERGROUND_FADE_DEPTH, UNDERGROUND_FADE_START);
        float notFlying = 1f - smoothstep(aboveGround, FLY_FADE_START, FLY_FADE_START + FLY_FADE_DEPTH);
        return notUnderground * notFlying;
    }

    /**
     * How much wildlife belongs to a column whose ground sits at {@code groundHeight}, given the world's
     * {@code waterHeight} : 1 on dry land, 0 out at sea.
     *
     * {@link NoiseTerrainGenerator} plants a tree only where {@code groundh > waterHeight + 1} (its
     * treeBandOk), but the forest-density field it multiplies that test with knows nothing about water —
     * it happily reads 1 in the middle of an ocean. The ambience sampled only the field, so the dawn
     * chorus followed the player out to sea. This is the missing half of the rule, softened into a ramp
     * so that rowing away from a wooded beach fades rather than cuts.
     *
     * It belongs to the INTENSITY and not to the gate, deliberately : swimming out is walking out of a
     * wood, so the calls already in the air are left to finish behind the player. Ducking the head under
     * is the gate's business, and that one does cut.
     *
     * Package-private and static so the rule can be asserted without an audio device or a world.
     */
    static float shoreFade(float groundHeight, float waterHeight) {
        float treeLine = waterHeight + 1f;
        return smoothstep(groundHeight, treeLine - SHORE_FADE_DEPTH, treeLine);
    }

    /** Hermite fade from 0 at {@code edge0} to 1 at {@code edge1}. */
    private static float smoothstep(float x, float edge0, float edge1) {
        float t = (x - edge0) / (edge1 - edge0);
        t = t < 0f ? 0f : Math.min(1f, t);
        return t * t * (3f - 2f * t);
    }
}
