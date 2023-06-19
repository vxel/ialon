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

package org.delaunois.ialon;

import com.jme3.system.AppSettings;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;

import org.delaunois.ialon.serialize.IalonConfigRepository;


public class DesktopLauncher {

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    public static void main(String[] args) {
        IalonConfig config = new IalonConfig();
        IalonConfigRepository.loadConfig(config);
        config.setGridRadiusMin(2);
        config.setGridRadiusMax(11);
        config.setDevMode(false);
        config.setGammaCorrection(1.1f);
        Ialon app = new Ialon(config);

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
}