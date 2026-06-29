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
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AppState;
import com.simsilica.lemur.GuiGlobals;
import com.simsilica.lemur.anim.AnimationState;

import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.serialize.IalonConfigRepository;
import org.delaunois.ialon.state.AxesDebugState;
import org.delaunois.ialon.state.BlockSliderSelectionState;
import org.delaunois.ialon.state.ButtonManagerState;
import org.delaunois.ialon.state.CreationCaptureState;
import org.delaunois.ialon.state.CreationLibraryState;
import org.delaunois.ialon.state.CreationPlacementState;
import org.delaunois.ialon.state.HitchProfilerState;
import org.delaunois.ialon.state.IalonDebugState;
import org.delaunois.ialon.state.TimingNode;
import org.delaunois.ialon.state.LightingState;
import org.delaunois.ialon.state.MoonState;
import org.delaunois.ialon.state.ScreenState;
import org.delaunois.ialon.state.MemoryGuardState;
import org.delaunois.ialon.state.PhotoModeState;
import org.delaunois.ialon.state.SettingsState;
import org.delaunois.ialon.state.SkyState;
import org.delaunois.ialon.state.StarState;
import org.delaunois.ialon.state.SplashscreenState;
import org.delaunois.ialon.state.SunState;
import org.delaunois.ialon.state.TimeFactorState;
import org.delaunois.ialon.state.UnderwaterState;
import org.delaunois.ialon.state.WaterState;
import org.delaunois.ialon.state.WireframeState;
import org.delaunois.ialon.state.WorldMenuState;
import org.delaunois.ialon.state.WorldSelectionState;

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
        stateManager.attach(new SplashscreenState(config));
    }

    /**
     * Heavy, world-building initialisation deferred out of {@link #simpleInitApp()} so it runs only after
     * the splashscreen has been drawn (see {@link #simpleUpdate(float)}). Functionally identical to the
     * original single-pass init, just delayed by one rendered frame.
     */
    private void initWorld() {
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
        stateManager.attach(new ButtonManagerState(config));
        stateManager.attach(new BlockSliderSelectionState(config));
        stateManager.attach(new TimeFactorState(config));
        stateManager.attach(new CreationCaptureState(config)); // "New creation" capture mode (uses ButtonManagerState)
        stateManager.attach(new CreationPlacementState(config)); // Places a saved creation into the world
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
            stateManager.attach(new WireframeState());
            //stateManager.attach(new WagonState());
        }

        // Opt-in render-thread hitch profiler : -Dialon.hitch=<ms> logs each frame whose work exceeds
        // <ms>, attributing the spike to a phase (update vs render), GC, and chunk-page churn.
        String hitch = System.getProperty("ialon.hitch");
        if (hitch != null) {
            long thresholdMs = hitch.trim().isEmpty() ? 15L : Long.parseLong(hitch.trim());
            stateManager.attach(new HitchProfilerState(thresholdMs));
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
        }
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
