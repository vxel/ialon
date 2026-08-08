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
import org.delaunois.ialon.state.CreationCaptureState;
import org.delaunois.ialon.state.CreationLibraryState;
import org.delaunois.ialon.state.CreationPlacementState;
import org.delaunois.ialon.state.HitchProfilerState;
import org.delaunois.ialon.state.IalonDebugState;
import org.delaunois.ialon.state.LightingState;
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

    // Deferred (post-splash) initialisation : the heavy world setup is delayed until the splashscreen
    // has actually been drawn, so it appears immediately instead of after the whole init blocks the
    // render thread. Counts simpleUpdate() calls ; the work runs once at least one frame has rendered.
    private boolean worldInitialized = false;
    private int splashFramesRendered = 0;

    /**
     * The popup states, innermost first : ESC closes the first one it finds open, so a popup laid over
     * another goes first. Also the roster {@link #updateWorldRendering()} polls.
     */
    private static final List<Class<? extends AppState>> POPUP_STATES = Collections.unmodifiableList(
            Arrays.<Class<? extends AppState>>asList(
                    RegionDeleteState.class,
                    RegionAddState.class,
                    BlockSliderSelectionState.class,
                    MinimapState.class,
                    SettingsState.class,
                    CreationLibraryState.class,
                    WorldMenuState.class));

    // Whether the world is currently culled because a world-hiding popup is up (see
    // updateWorldRendering). Kept so the cull hint is only written when it actually changes.
    private boolean worldHidden = false;

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
        // (atlas packing, block framework, world states, GUI styles) is deferred to initWorld(), run from
        // simpleUpdate() once the splash has been rendered at least once.
        IalonInitializer.setupLogging();
        IalonInitializer.setupCamera(this);
        IalonInitializer.setupViewPort(this);
        GuiGlobals.initialize(this); // required by the splashscreen's Lemur UI ; moved out of setupGui()
        setupExitAction();
        stateManager.attach(new SplashscreenState(config));
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
     * Stops rendering the 3D scene while a popup that hides the world is open (see
     * {@link Popup#hidesWorld()}), and resumes it when the popup closes. Nothing in jME relates "covered
     * by the GUI" to "culled" : the world is drawn in the main viewport, the popup in the GUI post
     * viewport, so without this the whole scene — chunks, far terrain, sky — keeps being drawn behind an
     * opaque veil for nobody, several hundred draw calls' worth. It costs frames where they are least
     * useful (a menu is open) and, on a phone, battery and heat.
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
    private void updateWorldRendering() {
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
            log.debug("World rendering {}", hide ? "suspended (popup open)" : "resumed");
        }
    }

    /**
     * Heavy, world-building initialisation deferred out of {@link #simpleInitApp()} so it runs only after
     * the splashscreen has been drawn (see {@link #simpleUpdate(float)}). Functionally identical to the
     * original single-pass init, just delayed by one rendered frame.
     */
    private void initWorld() {
        // Audio listener first : every sound state below is a no-op without it, and it must be attached
        // before anything tries to place a sound.
        IalonInitializer.setupAudio(this, config);
        IalonInitializer.setupAtlasManager(this, config);
        IalonInitializer.setupAtlasFont(this, config);
        IalonInitializer.setupBlockFramework(this, config);
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
        stateManager.attach(new CreationLibraryState(config)); // Library of creations (opens from WorldMenuState)
        stateManager.attach(new WorldMenuState(config)); // Create / switch worlds (uses WorldSelectionState)
        stateManager.attach(new PhotoModeState(config)); // Hides all UI for a clean screenshot
        stateManager.attach(new WorldSelectionState(config)); // Runtime world switch service
        // Attach the world-dependent states (chunk paging, physics, far terrain, world builder) last :
        // they are also re-attached as a group by WorldSelectionState when the player switches world.
        IalonInitializer.attachWorldStates(this, config);
        // Memory safety net : lowers the render distance under heap pressure (depends on the chunk pager
        // attached just above). Mostly relevant on Android, harmless on desktop.
        stateManager.attach(new MemoryGuardState(config));
        stateManager.attach(new AnimationState());

        IalonInitializer.setupGui(this, config); // Must be after block framework is initialized

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
        if (!worldInitialized) {
            // simpleUpdate() runs before the frame is drawn, so we wait for the SECOND call : by then the
            // first frame (with the splashscreen) has been fully rendered and the heavy init won't hide it.
            splashFramesRendered++;
            if (splashFramesRendered >= 2) {
                worldInitialized = true;
                initWorld();
            }
            return;
        }
        // Polled rather than hooked on show/hide : popups open and close from a dozen places (buttons, ESC,
        // Back, a world switch), and a single check here catches them all. simpleUpdate() also runs before
        // updateGeometricState(), so the cull hint is set well clear of the render pass.
        updateWorldRendering();
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
