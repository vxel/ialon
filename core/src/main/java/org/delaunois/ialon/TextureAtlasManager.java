/*
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

import com.jme3.system.JmeSystem;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.image.ColorSpace;

import org.delaunois.ialon.jme.TextureAtlas;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TextureAtlasManager {

    public static final String DIFFUSE = "DiffuseMap";
    public static final String OVERLAY = "OverlayMap";

    @Getter
    private final TextureAtlas atlas;

    private Texture diffuseMap;

    private Texture overlayMap;

    public TextureAtlasManager() {
        atlas = new TextureAtlas(2048, 2048);
    }

    public Texture getDiffuseMap() {
        if (diffuseMap == null) {
            diffuseMap = atlas.getAtlasTexture(DIFFUSE);
            diffuseMap.setName("TextureAtlasRepository/DiffuseMap");
            diffuseMap.getImage().setColorSpace(ColorSpace.sRGB);
            log.info("Atlas texture {} generated", diffuseMap);
        }
        return diffuseMap;
    }

    public Texture getOverlayMap() {
        if (overlayMap == null) {
            overlayMap = atlas.getAtlasTexture(OVERLAY);
            overlayMap.setName("TextureAtlasRepository/OverlayMap");
            overlayMap.getImage().setColorSpace(ColorSpace.sRGB);
            log.info("Atlas texture {} generated", overlayMap);
        }
        return overlayMap;
    }

    public void dump() {
        dump(getDiffuseMap().getImage(), "atlas-diffuse.png");
    }

    public void dump(Image img, String filename) {
        ByteBuffer sourceData = img.getData(0);
        ByteBuffer outData = ByteBuffer.allocate(sourceData.capacity());
        try (OutputStream out = new FileOutputStream(filename)) {
            for (int i = 0; i < sourceData.limit(); i++) {
                outData.put(i, sourceData.get(i));
            }
            JmeSystem.writeImageFile(out, "png", outData, img.getWidth(), img.getHeight());
        } catch (IOException e) {
            log.error("Failed to dump image", e);
        }
    }

}
