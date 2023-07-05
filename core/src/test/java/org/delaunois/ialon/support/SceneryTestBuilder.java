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
import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.Vector3f;
import com.jme3.system.AppSettings;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import com.rvandoosselaer.blocks.BlockIds;
import com.rvandoosselaer.blocks.BlocksConfig;

import org.delaunois.ialon.EmptyGenerator;
import org.delaunois.ialon.FlatTerrainGenerator;
import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.IalonInitializer;

import java.nio.file.Path;
import java.nio.file.Paths;


public class SceneryTestBuilder {

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    public static void main(String[] args) {
        Path saveDir = Paths.get("core/src/test/resources/scenery/saved");
        if (args.length > 0) {
            saveDir = Paths.get(args[0]);
        }

        IalonConfig config = new IalonConfig();
        config.setSavePath(saveDir);
        config.setDevMode(true);
        config.setDebugChunks(true);
        config.setPlayerLocation(new Vector3f(8, 11, 8));
        config.setPlayerStartFly(true);

        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), config);
        config.setTerrainGenerator(new EmptyGenerator());

        SimpleApplication app = new SceneryTestBuilderApplication(config);

        AppSettings settings = new AppSettings(false);
        settings.setFrameRate(IalonConfig.FPS_LIMIT);
        settings.setGammaCorrection(false);
        settings.setResolution(config.getScreenWidth(), config.getScreenHeight());
        settings.setRenderer(AppSettings.LWJGL_OPENGL32);
        settings.setUseInput(true);
        settings.setAudioRenderer(null);
        settings.setVSync(false);

        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }

    public static void setupBaseGenerator(IalonConfig config) {
        config.setGridRadius(1);
        config.setGridHeight(1);
        config.setTerrainGenerator(new FlatTerrainGenerator(8, BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.DIRT)));
    }

}