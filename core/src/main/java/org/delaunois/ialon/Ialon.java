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
import org.delaunois.ialon.blocks.BlocksConfig;
import com.simsilica.lemur.anim.AnimationState;

import org.delaunois.ialon.serialize.IalonConfigRepository;
import org.delaunois.ialon.state.AxesDebugState;
import org.delaunois.ialon.state.BlockSliderSelectionState;
import org.delaunois.ialon.state.ButtonManagerState;
import org.delaunois.ialon.state.IalonDebugState;
import org.delaunois.ialon.state.LightingState;
import org.delaunois.ialon.state.MoonState;
import org.delaunois.ialon.state.ScreenState;
import org.delaunois.ialon.state.SettingsState;
import org.delaunois.ialon.state.SkyState;
import org.delaunois.ialon.state.SplashscreenState;
import org.delaunois.ialon.state.SunState;
import org.delaunois.ialon.state.TimeFactorState;
import org.delaunois.ialon.state.WagonState;
import org.delaunois.ialon.state.WaterState;
import org.delaunois.ialon.state.WireframeState;
import org.delaunois.ialon.state.WorldBuilderState;

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

    public Ialon() {
        super((AppState[]) null);
        log.info("Instanciating Ialon");
        config = new IalonConfig();
    }

    public Ialon(IalonConfig config) {
        super((AppState[]) null);
        log.info("Instanciating Ialon");
        this.config = config;
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

        stateManager.attach(new SplashscreenState(config));

        IalonInitializer.setupLogging();
        IalonInitializer.setupCamera(this);
        IalonInitializer.setupViewPort(this);
        IalonInitializer.setupAtlasManager(this, config);
        IalonInitializer.setupAtlasFont(this, config);
        IalonInitializer.setupBlockFramework(this, config);
        stateManager.attach(new AnimationState());
        stateManager.attach(IalonInitializer.setupBulletAppState(config));
        stateManager.attach(IalonInitializer.setupChunkSaverState(config));
        stateManager.attach(IalonInitializer.setupPlayerState(this, config));
        stateManager.attach(IalonInitializer.setupStatsAppState(config));
        stateManager.attach(IalonInitializer.setupChunkManager(config));
        stateManager.attach(IalonInitializer.setupChunkPager(this, config)); // Depends on PlayerState
        stateManager.attach(IalonInitializer.setupPhysicsChunkPager(this, config)); // Depends on PlayerState and BulletAppState
        stateManager.attach(IalonInitializer.setupChunkLiquidManager(config));
        stateManager.attach(new LightingState(config));
        stateManager.attach(new SettingsState(config));
        stateManager.attach(new ScreenState(settings, config));
        stateManager.attach(new SunState(config));
        stateManager.attach(new MoonState(config));
        stateManager.attach(new SkyState(config));
        stateManager.attach(new WaterState(config)); // Animates the calm-water shader, depends on SunState + SkyState
        if (config.isFarTerrain()) {
            stateManager.attach(IalonInitializer.setupFarTerrain(config)); // Distant horizon, depends on camera + terrain generator
        }
        if (config.isFarTree()) {
            stateManager.attach(IalonInitializer.setupFarTree(config)); // Distant trees on the horizon, depends on camera + terrain generator
        }
        stateManager.attach(new ButtonManagerState(config));
        stateManager.attach(new BlockSliderSelectionState(config));
        stateManager.attach(new TimeFactorState(config));
        stateManager.attach(new WorldBuilderState(config));
        stateManager.attach(new AnimationState());

        IalonInitializer.setupGui(this, config); // Must be after block framework is initialized

        if (config.isDevMode()) {
            stateManager.attach(new AxesDebugState());
            stateManager.attach(new IalonDebugState(config));
            stateManager.attach(new DebugKeysAppState());
            stateManager.attach(new WireframeState());
            stateManager.attach(new WagonState());
        }

        int typeSize = BlocksConfig.getInstance().getTypeRegistry().getAll().size();
        int shapeSize = BlocksConfig.getInstance().getShapeRegistry().getAll().size();
        log.info("{} block types registered", typeSize);
        log.info("{} block shapes registered", shapeSize);
        log.info("{} blocks registered", BlocksConfig.getInstance().getBlockRegistry().size());
    }

    @Override
    public void start() {
        super.start();
        log.info("Starting Ialon");
    }

    @Override
    public void restart() {
        super.restart();
        Optional.ofNullable(stateManager.getState(ScreenState.class))
                .ifPresent(ScreenState::checkResize);
        log.info("Restarting Ialon");
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
