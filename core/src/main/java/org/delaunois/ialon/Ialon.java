/**
 * Ialon, a block construction game
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
package org.delaunois.ialon;

import com.jme3.app.DebugKeysAppState;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AppState;
import com.rvandoosselaer.blocks.BlocksConfig;

import org.delaunois.ialon.serialize.IalonConfigRepository;
import org.delaunois.ialon.state.BlockSelectionState;
import org.delaunois.ialon.state.GridSettingsState;
import org.delaunois.ialon.state.IalonDebugState;
import org.delaunois.ialon.state.LightingState;
import org.delaunois.ialon.state.MoonState;
import org.delaunois.ialon.state.ScreenState;
import org.delaunois.ialon.state.SkyState;
import org.delaunois.ialon.state.SunState;
import org.delaunois.ialon.state.TimeFactorState;
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
public class Ialon extends SimpleApplication {

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

        //stateManager.attach(new SplashscreenState(config));

        IalonInitializer.setupLogging();
        IalonInitializer.setupCamera(this, config);
        IalonInitializer.setupViewPort(this);
        IalonInitializer.setupAtlasManager(this, config);
        IalonInitializer.setupAtlasFont(this, config);
        IalonInitializer.setupBlockFramework(this, config);
        stateManager.attach(IalonInitializer.setupBulletAppState(config));
        stateManager.attach(IalonInitializer.setupChunkSaverState(config));
        stateManager.attach(IalonInitializer.setupPlayerState(config));
        stateManager.attach(IalonInitializer.setupStatsAppState(config));
        stateManager.attach(IalonInitializer.setupChunkManager(config));
        stateManager.attach(IalonInitializer.setupChunkPager(this, config)); // Depends on PlayerState
        stateManager.attach(IalonInitializer.setupPhysicsChunkPager(this, config)); // Depends on PlayerState and BulletAppState
        stateManager.attach(IalonInitializer.setupChunkLiquidManager(config));
        stateManager.attach(new LightingState(config));
        stateManager.attach(new GridSettingsState(config));
        stateManager.attach(new ScreenState(settings, config));
        stateManager.attach(new SunState(config));
        stateManager.attach(new MoonState(config));
        stateManager.attach(new SkyState(config));
        stateManager.attach(new BlockSelectionState(config));
        stateManager.attach(new TimeFactorState(config));
        stateManager.attach(new WorldBuilderState(config));

        IalonInitializer.setupGui(this, config); // Must be after block framework is initialized

        if (config.isDevMode()) {
            stateManager.attach(new IalonDebugState(config));
            stateManager.attach(new DebugKeysAppState());
            stateManager.attach(new WireframeState());
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
