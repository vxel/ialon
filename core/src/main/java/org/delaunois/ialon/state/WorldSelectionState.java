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

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.IalonInitializer;
import org.delaunois.ialon.serialize.IalonConfigRepository;
import org.delaunois.ialon.serialize.WorldRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Switches the loaded world at runtime, without restarting the application. The current world is saved
 * and its world-dependent states are torn down ({@link IalonInitializer#detachWorldStates}), the config
 * is repointed at the new world (dropping the cached chunk manager / repository / terrain generator so
 * they rebuild), and a fresh set of world states is attached ({@link IalonInitializer#attachWorldStates}),
 * with the splash screen shown until the world builder re-enables the player.
 *
 * @author Cedric de Launois
 */
@Slf4j
public class WorldSelectionState extends BaseAppState {

    private SimpleApplication app;
    private final IalonConfig config;

    public WorldSelectionState(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void initialize(Application application) {
        this.app = (SimpleApplication) application;
    }

    /**
     * Requests a switch to the given world. No-op if it is already the current world or does not exist.
     * The actual teardown/rebuild is enqueued on the render thread so it runs at a safe point between
     * frames (not while the AppStateManager is iterating its states).
     */
    public void switchTo(String worldId) {
        if (worldId == null || worldId.equals(config.getWorldId())) {
            return;
        }
        if (!WorldRepository.worldExists(config.getSavePath(), worldId)) {
            log.warn("Cannot switch to unknown world '{}'", worldId);
            return;
        }
        app.enqueue(() -> doSwitch(worldId));
    }

    private void doSwitch(String worldId) {
        log.info("Switching from world '{}' to '{}'", config.getWorldId(), worldId);

        // 1. Persist the current world (player state + global game settings) while its repository is still
        //    the active one (block edits are already saved live by ChunkSaverState).
        IalonConfigRepository.saveConfig(app, config);

        // 2. Tear down the current world's states : cleanup() detaches the chunk pages, removes the
        //    physics bodies and shuts down the chunk manager pool.
        IalonInitializer.detachWorldStates(app);

        // 3. Repoint the config at the new world and drop the cached data sources so the lazy getters
        //    rebuild them for the new save directory and generation parameters.
        config.setWorldId(worldId);
        config.setChunkManager(null);
        config.setChunkRepository(null);
        config.setTerrainGenerator(null);
        IalonConfigRepository.loadWorldState(config);

        // 4. Show the loading screen until the world builder re-enables the player.
        IalonInitializer.getOrAttachSplashscreen(app, config).setEnabled(true);

        // 5. Build the new world.
        IalonInitializer.attachWorldStates(app, config);
        log.info("Switched to world '{}'", worldId);
    }

    @Override
    protected void cleanup(Application application) {
        // Nothing to do
    }

    @Override
    protected void onEnable() {
        // Nothing to do
    }

    @Override
    protected void onDisable() {
        // Nothing to do
    }
}
