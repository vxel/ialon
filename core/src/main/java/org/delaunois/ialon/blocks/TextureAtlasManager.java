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

package org.delaunois.ialon.blocks;

import com.jme3.system.JmeSystem;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.image.ColorSpace;

import org.delaunois.ialon.blocks.jme.TextureAtlas;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TextureAtlasManager {

    public static final String DIFFUSE = "DiffuseMap";

    @Getter
    private final TextureAtlas atlas;

    private Texture diffuseMap;

    public TextureAtlasManager() {
        atlas = new TextureAtlas(2048, 2048);
    }

    public void addTexture(Texture texture, String mapName) {
        atlas.addTexture(texture, mapName);
    }

    public Texture getDiffuseMap() {
        if (diffuseMap == null) {
            long start = System.nanoTime();
            diffuseMap = atlas.getAtlasTexture(DIFFUSE);
            diffuseMap.setName("TextureAtlasRepository/DiffuseMap");
            diffuseMap.getImage().setColorSpace(ColorSpace.sRGB);
            log.info("Atlas texture {} generated (packing took {} ms)",
                    diffuseMap, (System.nanoTime() - start) / 1_000_000);
        }
        return diffuseMap;
    }

    public void dump() {
        dump(getDiffuseMap().getImage(), "atlas-diffuse.png");
    }

    public void dump(Image img, String filename) {
        ByteBuffer sourceData = img.getData(0);
        ByteBuffer outData = ByteBuffer.allocate(sourceData.capacity());
        try (OutputStream out = new FileOutputStream(filename)) {
            int size = sourceData.limit();
            boolean abgr = img.getFormat() == Image.Format.ABGR8;
            for (int i = 0; i < size; i += 4) {
                if (abgr) {
                    // ABGR -> RGBA
                    outData.put(i, sourceData.get(i + 3));
                    outData.put(i + 1, sourceData.get(i + 2));
                    outData.put(i + 2, sourceData.get(i + 1));
                    outData.put(i + 3, sourceData.get(i));
                } else {
                    // already RGBA8 (the atlas GPU image) : copy as-is
                    outData.put(i, sourceData.get(i));
                    outData.put(i + 1, sourceData.get(i + 1));
                    outData.put(i + 2, sourceData.get(i + 2));
                    outData.put(i + 3, sourceData.get(i + 3));
                }
            }
            JmeSystem.writeImageFile(out, "png", outData, img.getWidth(), img.getHeight());
        } catch (IOException e) {
            log.error("Failed to dump image", e);
        }
    }

}
