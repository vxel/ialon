/**
 * Ialon, a block construction game
 * Copyright (C) 2022 Cédric de Launois
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.delaunois.ialon;

import com.jme3.app.DebugKeysAppState;
import com.jme3.app.DetailedProfilerState;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AppState;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.scene.Spatial.CullHint;
import com.simsilica.lemur.GuiGlobals;
import com.simsilica.lemur.anim.AnimationState;

import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.serialize.IalonConfigRepository;
import org.delaunois.ialon.state.AmbientSoundState;
import org.delaunois.ialon.state.AxesDebugState;
import org.delaunois.ialon.state.BlockSliderSelectionState;
import org.delaunois.ialon.state.ButtonManagerState;
import org.delaunois.ialon.state.ChunkResetState;
import org.delaunois.ialon.state.CreationCaptureState;
import org.delaunois.ialon.state.CreationLibraryState;
import org.delaunois.ialon.state.CreationPlacementState;
import org.delaunois.ialon.state.CreationViewerState;
import org.delaunois.ialon.state.HitchProfilerState;
import org.delaunois.ialon.state.IalonDebugState;
import org.delaunois.ialon.state.LightingState;
import org.delaunois.ialon.state.LoadProgress;
import org.delaunois.ialon.state.MemoryGuardState;
import org.delaunois.ialon.state.MinimapState;
import org.delaunois.ialon.state.MoonState;
import org.delaunois.ialon.state.PhotoModeState;
import org.delaunois.ialon.state.Popup;
import org.delaunois.ialon.state.RegionAddState;
import org.delaunois.ialon.state.RegionDeleteState;
import org.delaunois.ialon.state.RenderReport;
import org.delaunois.ialon.state.ScreenState;
import org.delaunois.ialon.state.SettingsState;
import org.delaunois.ialon.state.SkyState;
import org.delaunois.ialon.state.SplashscreenState;
import org.delaunois.ialon.state.StarState;
import org.delaunois.ialon.state.SunState;
import org.delaunois.ialon.state.TimeFactorState;
import org.delaunois.ialon.state.TimingNode;
import org.delaunois.ialon.state.UnderwaterState;
import org.delaunois.ialon.state.WaterState;
import org.delaunois.ialon.state.WorldMenuState;
import org.delaunois.ialon.state.WorldSelectionState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;


/**
 * Main class for setting up and starting Ialon
 *
 * @author Cedric de Launois
 */
@Slf4j
@SuppressWarnings({"javaarchitecture:S7091", "javaarchitecture:S7027"})
public class Ialon extends SimpleApplication {

    public static final String IALON_STYLE = "ialon";
    @Getter
    @Setter
    private IalonConfig config;

    // Deferred (post-splash) initialisation, run ONE STEP PER FRAME by the pump in simpleUpdate(). See
    // buildInitSteps() for why it is split rather than run in one go. Null once the pump is done.
    private List<InitStep> initSteps;
    private int nextInitStep = 0;
    private boolean splashDrawn = false;
    private long[] initStepMillis;
    private long lastStepEndNanos;

    /**
     * The popup states, innermost first : ESC closes the first one it finds open, so a popup laid over
     * another goes first. Also the roster {@link #updateWorldPresence()} polls.
     */
    private static final List<Class<? extends AppState>> POPUP_STATES = Collections.unmodifiableList(
            Arrays.<Class<? extends AppState>>asList(
                    ChunkResetState.class,
                    RegionDeleteState.class,
                    RegionAddState.class,
                    BlockSliderSelectionState.class,
                    MinimapState.class,
                    SettingsState.class,
                    CreationViewerState.class,
                    CreationLibraryState.class,
                    WorldMenuState.class));

    // Whether the world is currently suspended because a world-hiding popup is up (see
    // updateWorldPresence). Kept so the cull hint is only written when it actually changes.
    private boolean worldHidden = false;
    // Whether the ambient sound was playing when the popup went up, so closing it restores what was there
    // rather than turning ambience on.
    private boolean ambienceWasEnabled = false;

    public Ialon() {
        super((AppState[]) null);
        log.info("Instanciating Ialon");
        config = new IalonConfig();
        maybeInstallTimingNodes();
    }

    public Ialon(IalonConfig config) {
        super((AppState[]) null);
        log.info("Instanciating Ialon");
        this.config = config;
        maybeInstallTimingNodes();
    }

    // When the hitch profiler is enabled (-Dialon.hitch), swap the plain root/gui nodes for TimingNodes
    // so HitchProfilerState can split the SpatialUpdate step into world-vs-HUD and controls-vs-bounds.
    // Must run before start()/initialize() attaches rootNode/guiNode to the viewports.
    private void maybeInstallTimingNodes() {
        if (System.getProperty("ialon.hitch") != null) {
            rootNode = new TimingNode("Root Node");
            guiNode = new TimingNode("Gui Node");
        }
    }

    @Override
    public void simpleInitApp() {
        log.info("Initializing Ialon");
        config.getInputActionManager().setInputManager(inputManager);

        // sRGB pipeline : gamma correction is requested in the AppSettings and is performed by the
        // hardware sRGB framebuffer on desktop GL. On Android GLES the default framebuffer does not
        // sRGB-encode on write (GLES has no GL_FRAMEBUFFER_SRGB and the EGL window surface is not
        // sRGB), so we emulate the sRGB encode/decode inside our own world shaders to keep colours
        // consistent.
        // NB: do NOT key this off Caps.Srgb. Since jME 3.7 the GLES3 renderer advertises Caps.Srgb
        // even though its default framebuffer performs no encode (see GLRenderer: the cap is now
        // granted for OpenGLES30). Trusting it wrongly disables the in-shader emulation and renders
        // the whole world far too dark. Detect the GLES backend instead -- desktop GL never reports
        // Caps.OpenGLES20.
        config.setManualGammaEncode(getRenderer().getCaps().contains(com.jme3.renderer.Caps.OpenGLES20));
        if (config.isManualGammaEncode()) {
            // jME 3.9 now advertises Caps.Srgb on GLES3 and consequently flips the main framebuffer to
            // "sRGB", emitting glEnable(GL_FRAMEBUFFER_SRGB) every frame -- an enum GLES does not honour
            // for the default framebuffer (jME even logs "Enabling anyway"). Turn it back off so the
            // pipeline is fully manual (as it was pre-3.7) and jME does not half-apply a hardware sRGB
            // encode that we already perform in-shader.
            getRenderer().setMainFrameBufferSrgb(false);
        }
        log.info("Hardware sRGB framebuffer: {} (manual gamma encode: {})",
                !config.isManualGammaEncode(), config.isManualGammaEncode());

        // Minimal, fast setup so the splashscreen can be drawn on the very first frame. Everything heavy
        // (atlas packing, block framework, world states, GUI styles) is deferred to the init steps, run
        // one per frame from simpleUpdate() once the splash has been rendered at least once.
        IalonInitializer.setupLogging();
        IalonInitializer.setupCamera(this);
        IalonInitializer.setupViewPort(this);
        GuiGlobals.initialize(this); // required by the splashscreen's Lemur UI ; moved out of setupGui()
        setupExitAction();
        stateManager.attach(new SplashscreenState(config));

        initSteps = buildInitSteps();
        initStepMillis = new long[initSteps.size()];
        config.getLoadProgress().begin(loadSteps());
        config.getLoadProgress().beginStep(initSteps.get(0).step);
    }

    /**
     * Overrides jME's default ESC-quits-the-game binding : when a UI popup (world menu, settings, minimap,
     * block picker...) is open, ESC closes that popup instead of stopping the application. With no popup
     * open, ESC still quits, preserving the previous behaviour.
     */
    private void setupExitAction() {
        if (inputManager == null) {
            return; // headless context (e.g. screenshot harness) : no input, nothing to rebind
        }
        // SimpleApplication.initialize() already mapped INPUT_MAPPING_EXIT to ESC with a listener that
        // calls stop(). Drop that mapping (which unbinds jME's listener) and rebind ESC to our own handler.
        if (inputManager.hasMapping(INPUT_MAPPING_EXIT)) {
            inputManager.deleteMapping(INPUT_MAPPING_EXIT);
        }
        inputManager.addMapping(INPUT_MAPPING_EXIT, new KeyTrigger(KeyInput.KEY_ESCAPE));
        inputManager.addListener(exitActionListener, INPUT_MAPPING_EXIT);
    }

    private final ActionListener exitActionListener = (name, isPressed, tpf) -> {
        if (!isPressed && !closeOpenPopup()) {
            stop();
        }
    };

    /**
     * Closes the top open popup, if any. Only one modal is normally open at a time, so a single popup is
     * closed per ESC press.
     *
     * @return {@code true} if a popup was open and got closed ; {@code false} if none was open.
     */
    private boolean closeOpenPopup() {
        for (int i = 0; i < POPUP_STATES.size(); i++) {
            Popup popup = popupState(POPUP_STATES.get(i));
            if (popup != null && popup.isPopupOpen()) {
                popup.closePopup();
                return true;
            }
        }
        return false;
    }

    /** The popup owned by an attached state, or {@code null} if that state is absent. */
    private Popup popupState(Class<? extends AppState> type) {
        AppState state = stateManager.getState(type);
        return state instanceof Popup ? (Popup) state : null;
    }

    /**
     * Takes the world away — out of sight and out of earshot — while a popup that hides it is open (see
     * {@link Popup#hidesWorld()}), and brings it back when the popup closes.
     *
     * <p><b>Sight.</b> Nothing in jME relates "covered by the GUI" to "culled" : the world is drawn in the
     * main viewport, the popup in the GUI post viewport, so without this the whole scene — chunks, far
     * terrain, sky — keeps being drawn behind an opaque veil for nobody, several hundred draw calls' worth.
     * It costs frames where they are least useful (a menu is open) and, on a phone, battery and heat.
     *
     * <p><b>Earshot.</b> The ambient wildlife and the water bed are the world's voice, and a full-screen menu
     * is the player having stepped out of it, so they stop too (see {@link #setAmbienceSuspended}). Only for
     * the popups that <em>hide</em> the world, which is why this hangs off the same test rather than off
     * "any popup is open" : the in-world modes — the block picker, the region selection, the chunk reset —
     * leave the player standing in the world looking at it, and going silent there would be the odd
     * behaviour, not the birds carrying on.
     *
     * <p>Culls the root node rather than disabling the viewport, so the viewport still <em>clears</em> the
     * framebuffer. Disabling it would leave the previous frame's pixels wherever the popup does not paint
     * — which is nowhere, in theory, its veil being screen-sized, but that would make correctness rest on
     * the popup and the framebuffer agreeing on their size at every instant, including the frame a resize
     * lands on. A cull hint on the root returns from {@code renderSubScene} before a single geometry is
     * looked at, which is the whole saving anyway.
     *
     * <p>The states keep running : paging, meshing and physics carry on, so the world is live again the
     * moment the popup closes, and only the drawing is skipped.
     */
    private void updateWorldPresence() {
        boolean hide = false;
        for (int i = 0; i < POPUP_STATES.size(); i++) {
            Popup popup = popupState(POPUP_STATES.get(i));
            if (popup != null && popup.hidesWorld() && popup.isPopupOpen()) {
                hide = true;
                break;
            }
        }
        if (hide != worldHidden) {
            worldHidden = hide;
            // Back to Inherit, not Dynamic : it is what the root node starts as, and for a node with no
            // parent jME resolves the two identically.
            rootNode.setCullHint(hide ? CullHint.Always : CullHint.Inherit);
            setAmbienceSuspended(hide);
            log.debug("World {} (rendering and ambient sound)", hide ? "suspended : popup open" : "resumed");
        }
    }

    /**
     * Silences the world's ambience while a popup hides it, and starts it again afterwards.
     *
     * <p>Done by disabling {@link AmbientSoundState}, whose {@code onDisable} already stops every voice and
     * the water bed outright, tail included — so there is nothing to fade here and no second way of muting
     * to keep in step with the first.</p>
     *
     * <p>What it was doing beforehand is remembered rather than assumed : the state disables itself when the
     * player has turned ambient sound off in the settings, and again when there is no audio device at all
     * (the headless harnesses), and a popup must not be what switches it back on.</p>
     */
    private void setAmbienceSuspended(boolean suspend) {
        AmbientSoundState ambience = stateManager.getState(AmbientSoundState.class);
        if (ambience == null) {
            return;
        }
        if (suspend) {
            ambienceWasEnabled = ambience.isEnabled();
            ambience.setEnabled(false);
        } else if (ambienceWasEnabled) {
            ambience.setEnabled(true);
        }
    }

    /** One unit of deferred initialisation : what to run, and what to tell the player while it runs. */
    private static final class InitStep {

        private final LoadProgress.Step step;
        private final Runnable action;

        InitStep(LoadProgress.Step step, Runnable action) {
            this.step = step;
            this.action = action;
        }
    }

    /**
     * The heavy, world-building initialisation, cut into steps that the pump in {@link #simpleUpdate}
     * runs ONE PER FRAME. It used to be a single {@code initWorld()} call, which is why the loading
     * screen said "Loading..." for most of a load and then swept 0 to 100% at the end : no frame is
     * rendered while that call runs, so the splash was frozen and only the chunk phase — the tail of the
     * load — could ever move the bar. A step per frame lets the splash repaint between them, so the bar
     * and the phase label follow the whole load.
     *
     * <p>The grouping is not free to change. What must hold :
     * <ul>
     *   <li>{@code setupGui} runs BEFORE any state is attached (it registers the Lemur theme, and every
     *       UI state builds its widgets from that theme in its {@code initialize()}). This is a change
     *       from the original order, and it is safe : the atlas holds no block tile and the theme reads
     *       no block registry, so the GUI does not depend on the block framework despite what the old
     *       comment claimed.</li>
     *   <li><b>every state is attached in ONE step</b>, in the original order. Splitting the shared
     *       states from the world states looks tempting — two shorter frozen frames instead of one long
     *       one — but it does not work : states attached first also start <em>updating</em> a frame
     *       earlier, and {@code SkyState.update} queries the world through the chunk manager, which is
     *       only initialised by {@code ChunkManagerState}, one of the world states. It throws. Several
     *       states also resolve each other in their {@code initialize()}, and {@code AppStateManager}
     *       only finds states attached in the same or an earlier frame : {@code ScreenState} must
     *       precede the states that register with it, {@code PlayerState} reads
     *       {@code ChunkLiquidManagerState} (attached after it in {@code attachWorldStates}, and
     *       silently yielding a world manager with no liquid simulation if it were not in the same
     *       batch), and {@code MinimapState} reuses {@code FarTerrainState}'s heightmap.</li>
     * </ul>
     *
     * <p>Which is why the attach step is cheap and the step AFTER it carries the weight : what the attaches
     * cost is not the {@code attach()} calls (microseconds) but the {@code initialize()} pass they trigger
     * at the start of the next frame — the player model, the popups, the far-terrain heightmap, the
     * minimap bake. That pass runs before anything is painted again, so on screen it falls under the
     * following step's label, and the timings below attribute it to the same place.
     */
    private List<InitStep> buildInitSteps() {
        List<InitStep> steps = new ArrayList<>();
        // Audio listener first : every sound state below is a no-op without it, and it must be attached
        // before anything tries to place a sound.
        steps.add(new InitStep(LoadProgress.Step.AUDIO, () -> IalonInitializer.setupAudio(this, config)));
        steps.add(new InitStep(LoadProgress.Step.TEXTURES, () -> IalonInitializer.setupAtlasManager(this, config)));
        steps.add(new InitStep(LoadProgress.Step.GUI, () -> {
            IalonInitializer.setupAtlasFont(this, config);
            IalonInitializer.setupGui(this, config);
        }));
        // The catalog only : slicing the tiles into the block texture array is the step after, so the two
        // halves of what the player sees as "loading blocks" report separately.
        steps.add(new InitStep(LoadProgress.Step.BLOCKS,
                () -> IalonInitializer.setupBlockFramework(this, config, false)));
        steps.add(new InitStep(LoadProgress.Step.BLOCK_ARRAY,
                () -> BlocksConfig.getInstance().getTypeRegistry().getBlockTextureArray()));
        steps.add(new InitStep(LoadProgress.Step.STATES, () -> {
            attachSharedStates();
            attachWorldStates();
        }));
        steps.add(new InitStep(LoadProgress.Step.STATES_INIT, this::finishInit));
        return steps;
    }

    /**
     * The steps this launch will go through : the init steps, then the two chunk phases the splashscreen
     * feeds from the pager. Only these weigh on the bar, so it spans exactly the work that will happen.
     */
    private LoadProgress.Step[] loadSteps() {
        int initCount = initSteps.size();
        LoadProgress.Step[] steps = new LoadProgress.Step[initCount + 2];
        for (int i = 0; i < initCount; i++) {
            steps[i] = initSteps.get(i).step;
        }
        steps[initCount] = LoadProgress.Step.CHUNK_FETCH;
        steps[initCount + 1] = LoadProgress.Step.CHUNK_MESH;
        return steps;
    }

    /**
     * Runs the next init step. Called from {@link #simpleUpdate}, which runs before the frame is drawn —
     * hence the one-frame wait at the top : the splash has to be on screen before the first step blocks it.
     */
    private void runNextInitStep(float tpf) {
        if (!splashDrawn) {
            splashDrawn = true;
            lastStepEndNanos = System.nanoTime();
            return;
        }

        InitStep current = initSteps.get(nextInitStep);
        long start = System.nanoTime();
        current.action.run();
        long now = System.nanoTime();
        // Two numbers, and the weights are calibrated from the FIRST. It is measured from the end of the
        // previous step, not from the start of this one, because in between sits the initialize() pass of
        // whatever that step attached — no frame is drawn during it, so the player waits through it under
        // this step's label. The second number is the step's own work, which tells the two apart when the
        // machine is drawing frames slowly for reasons of its own.
        initStepMillis[nextInitStep] = (now - lastStepEndNanos) / 1_000_000L;
        lastStepEndNanos = now;
        log.info("Init step {} took {} ms ({} ms of work)",
                current.step, initStepMillis[nextInitStep], (now - start) / 1_000_000L);
        config.getLoadProgress().completeStep(current.step);

        nextInitStep++;
        if (nextInitStep < initSteps.size()) {
            config.getLoadProgress().beginStep(initSteps.get(nextInitStep).step);
        } else {
            logInitProfile();
            initSteps = null;
            config.getLoadProgress().beginStep(LoadProgress.Step.CHUNK_FETCH);
        }

        // Repaint now rather than at the start of the next frame : this runs before the frame is drawn,
        // so the bar and the label land on screen with the step they describe instead of one step late.
        SplashscreenState splash = stateManager.getState(SplashscreenState.class);
        if (splash != null && splash.isEnabled()) {
            splash.refresh(tpf);
        }
    }

    /**
     * The measured cost of each step, once per launch. This is the input for calibrating the step weights
     * in {@link LoadProgress.Step} : they only have to be right relative to each other, but they have to
     * be measured on the target device, where the ratios differ from the desktop's.
     */
    private void logInitProfile() {
        long total = 0;
        for (long ms : initStepMillis) {
            total += ms;
        }
        StringBuilder profile = new StringBuilder();
        for (int i = 0; i < initStepMillis.length; i++) {
            profile.append(i == 0 ? "" : ", ")
                    .append(initSteps.get(i).step)
                    .append('=')
                    .append(initStepMillis[i])
                    .append("ms");
        }
        log.info("Init profile ({} ms total) : {}", total, profile);
    }

    /** The states that do not depend on which world is loaded. Kept in one step : see buildInitSteps(). */
    private void attachSharedStates() {
        stateManager.attach(new AnimationState());
        stateManager.attach(IalonInitializer.setupBulletAppState(config));
        stateManager.attach(IalonInitializer.setupStatsAppState(config));
        stateManager.attach(new LightingState(config));
        stateManager.attach(new SettingsState(config));
        stateManager.attach(new ScreenState(settings, config));
        stateManager.attach(new SunState(config));
        stateManager.attach(new MoonState(config));
        stateManager.attach(new SkyState(config));
        stateManager.attach(new StarState(config)); // Night star field, depends on SunState; drawn over the sky, under sun/moon
        stateManager.attach(new WaterState(config)); // Animates the calm-water shader, depends on SunState + SkyState
        stateManager.attach(new UnderwaterState(config)); // Underwater post-process (bluish fog + ripple) when submerged, depends on SkyState
        stateManager.attach(new AmbientSoundState(config)); // Birds by day / owls by night in the woods, depends on SunState + the terrain generator (both resolved lazily)
        stateManager.attach(new ButtonManagerState(config));
        stateManager.attach(new BlockSliderSelectionState(config));
        stateManager.attach(new TimeFactorState(config));
        stateManager.attach(new CreationCaptureState(config)); // "New creation" capture mode (uses ButtonManagerState)
        stateManager.attach(new CreationPlacementState(config)); // Places a saved creation into the world
        stateManager.attach(new RegionDeleteState(config)); // Bulk-delete mode, entered by holding "-" (uses ButtonManagerState)
        stateManager.attach(new RegionAddState(config)); // Bulk-fill mode, entered by holding "+"
        stateManager.attach(new ChunkResetState(config)); // Reverts a chunk column to generated terrain (offered by RegionDeleteState)
        stateManager.attach(new CreationLibraryState(config)); // Library of creations (opens from WorldMenuState)
        stateManager.attach(new CreationViewerState(config)); // 3D viewer of one creation (opens from the library)
        stateManager.attach(new WorldMenuState(config)); // Create / switch worlds (uses WorldSelectionState)
        stateManager.attach(new PhotoModeState(config)); // Hides all UI for a clean screenshot
        stateManager.attach(new WorldSelectionState(config)); // Runtime world switch service
        // The splash outlives this step and the load can straddle a resize, so it re-lays-out like the
        // rest of the GUI. Registered here rather than from its own initialize() : it is attached long
        // before ScreenState exists, and registerOn() silently does nothing when there is none.
        ScreenState.registerOn(this, stateManager.getState(SplashscreenState.class));
    }

    /**
     * The states that depend on which world is loaded (chunk paging, physics, far terrain, world builder).
     * Attached as one group, exactly as {@code WorldSelectionState} re-attaches them on a world switch.
     */
    private void attachWorldStates() {
        IalonInitializer.attachWorldStates(this, config);
        // Memory safety net : lowers the render distance under heap pressure (depends on the chunk pager
        // attached just above). Mostly relevant on Android, harmless on desktop.
        stateManager.attach(new MemoryGuardState(config));
        stateManager.attach(new AnimationState());
    }

    /**
     * The tail of the init : the opt-in diagnostic states and the startup summary. Also the step under
     * which the world states' initialize() pass (far-terrain heightmap, minimap bake) is accounted, since
     * that pass runs at the start of this very frame.
     */
    private void finishInit() {
        if (config.isDevMode()) {
            stateManager.attach(new AxesDebugState());
            stateManager.attach(new IalonDebugState(config));
            stateManager.attach(new DebugKeysAppState());
            // jME's per-step CPU/GPU frame profiler. It self-binds to F6 (toggles its own visibility)
            // and starts hidden, so it only installs the AppProfiler overhead once actually shown.
            DetailedProfilerState detailedProfiler = new DetailedProfilerState();
            detailedProfiler.setEnabled(false);
            stateManager.attach(detailedProfiler);
        }

        // Opt-in render-thread hitch profiler : -Dialon.hitch=<ms> logs each frame whose work exceeds
        // <ms>, attributing the spike to a phase (update vs render), GC, and chunk-page churn.
        // -Dialon.rendercpu=<seconds> adds, once per window, the render thread's CPU share of wall clock :
        // the computing-versus-waiting discriminator that tells per-draw CPU cost apart from GPU
        // vertex/triangle throughput. Needed because -Dialon.vpscale established the opaque pass is not
        // fragment bound (a quarter of the pixels left Opaque unchanged, ratio 1.01x) and neither survivor
        // scales with pixels. Set it with ialon.hitch, whose state hosts the measurement.
        String hitch = System.getProperty("ialon.hitch");
        if (hitch != null) {
            long thresholdMs = hitch.trim().isEmpty() ? 15L : Long.parseLong(hitch.trim());
            String cpuWindow = System.getProperty("ialon.rendercpu");
            long windowSeconds = cpuWindow == null ? 0
                    : (cpuWindow.trim().isEmpty() ? 2L : Long.parseLong(cpuWindow.trim()));
            stateManager.attach(new HitchProfilerState(thresholdMs, windowSeconds));
        }

        // Opt-in fill-rate discriminator : -Dialon.vpscale=<0..1> squeezes the WORLD viewport into that
        // fraction of the screen, in each axis. It answers one question and nothing else — is the render
        // pass paying per DRAW or per FRAGMENT?
        //
        // The camera's frustum is untouched, so the scene, the culling and therefore the draw and triangle
        // counts are all identical ; only the number of shaded pixels changes, by the square of the factor.
        // So with vpscale=0.5, a quarter of the fragments :
        //   * Opaque falls by ~4x  -> fragment bound. The lever is overdraw / shader cost / resolution.
        //   * Opaque barely moves  -> per-draw CPU bound. The lever is the draw and GL call count.
        // Needed because the numbers alone cannot tell : Opaque correlates only weakly with the draw count
        // (r = 0.31 over 104 device frames, and it varies up to 2.2x at a FIXED draw count), which hints at
        // fragments, while a small 'gap' hints at CPU — except a saturated GLES/ANGLE command queue blocks
        // inside glDrawElements, i.e. inside the Opaque bucket, so that hint is not conclusive either.
        //
        // The GUI viewport is deliberately left full-size : the HUD stays readable and its (small, constant)
        // Gui bucket keeps acting as a control on the same frame.
        String vpScale = System.getProperty("ialon.vpscale");
        if (vpScale != null) {
            float s = Math.max(0.05f, Math.min(1f, Float.parseFloat(vpScale.trim())));
            cam.setViewPort(0f, s, 0f, s);
            log.warn("Fill-rate test ON : world viewport squeezed to {}x{} of the screen ({}% of the"
                    + " fragments, identical draws/triangles). Opaque /4 => fragment bound ; Opaque"
                    + " unchanged => per-draw bound.", s, s, Math.round(s * s * 100));
        }

        // Opt-in draw-call breakdown : -Dialon.renderreport=<seconds> logs which part of the scene owns
        // the frame's draw calls. On desktop with devMode, F7 does the same on demand.
        String renderReport = System.getProperty("ialon.renderreport");
        if (renderReport != null) {
            float period = renderReport.trim().isEmpty() ? 5f : Float.parseFloat(renderReport.trim());
            if (period > 0) {
                stateManager.attach(new RenderReport.PeriodicState(period));
            }
        }

        // Opt-in memory breakdown : -Dialon.memreport=<seconds>. The report itself lives in
        // IalonDebugState (it is what F9 prints), and that state is only attached in devMode — so asking
        // for the report attaches it regardless, otherwise the property silently does nothing, which is
        // exactly what happened the first time this was wired. The on-screen overlay comes with it : a
        // deliberate trade, since a diagnostic run wants the numbers and the overlay's extra GUI draws
        // are known, constant and easy to subtract.
        if (System.getProperty("ialon.memreport") != null
                && stateManager.getState(IalonDebugState.class) == null) {
            log.warn("-Dialon.memreport is set : attaching IalonDebugState (and its overlay) although devMode is off");
            stateManager.attach(new IalonDebugState(config));
        }

        int typeSize = BlocksConfig.getInstance().getTypeRegistry().getAll().size();
        int shapeSize = BlocksConfig.getInstance().getShapeRegistry().getAll().size();
        log.info("{} block types registered", typeSize);
        log.info("{} block shapes registered", shapeSize);
        log.info("{} blocks registered", BlocksConfig.getInstance().getBlockRegistry().size());
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (initSteps != null) {
            runNextInitStep(tpf);
            return; // No world yet : nothing else to poll.
        }
        // Polled rather than hooked on show/hide : popups open and close from a dozen places (buttons, ESC,
        // Back, a world switch), and a single check here catches them all. simpleUpdate() also runs before
        // updateGeometricState(), so the cull hint is set well clear of the render pass.
        updateWorldPresence();
    }

    @Override
    public void start() {
        super.start();
        log.info("Starting Ialon");
    }

    @Override
    public void restart() {
        super.restart();
        log.info("Restarting Ialon");
    }

    /**
     * Native jME reshape callback : fired on window resize and on the context restart triggered by
     * the fullscreen toggle. After letting jME resize the cameras, re-lays-out every registered GUI
     * state via {@link ScreenState}.
     */
    @Override
    public void reshape(int w, int h) {
        super.reshape(w, h);
        Optional.ofNullable(stateManager.getState(ScreenState.class))
                .ifPresent(screenState -> screenState.onReshape(w, h));
    }

    @Override
    public void stop() {
        super.stop();
        log.info("Stopping Ialon");
        if (config.isSaveUserSettingsOnStop()) {
            IalonConfigRepository.saveConfig(this, config);
        }
    }

}
