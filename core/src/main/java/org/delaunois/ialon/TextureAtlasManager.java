package org.delaunois.ialon;

import com.jme3.system.JmeSystem;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.image.ColorSpace;

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
            diffuseMap = atlas.getAtlasTexture("DiffuseMap");
            diffuseMap.setName("TextureAtlasRepository/DiffuseMap");
            diffuseMap.getImage().setColorSpace(ColorSpace.sRGB);
            log.info("Atlas texture {} generated", diffuseMap);
        }
        return diffuseMap;
    }

    public Texture getOverlayMap() {
        if (overlayMap == null) {
            overlayMap = atlas.getAtlasTexture("OverlayMap");
            overlayMap.setName("TextureAtlasRepository/OverlayMap");
            overlayMap.getImage().setColorSpace(ColorSpace.sRGB);
            log.info("Atlas texture {} generated", overlayMap);
        }
        return overlayMap;
    }

    public void dump() {
        dump(getDiffuseMap().getImage(), "atlas-diffuse.png");
        dump(getOverlayMap().getImage(), "atlas-overlay.png");
    }

    public void dump(Image img, String filename) {
        ByteBuffer sourceData = img.getData(0);
        ByteBuffer outData = ByteBuffer.allocate(sourceData.capacity());
        OutputStream out = null;
        try {
            out = new FileOutputStream(filename);
            for (int i = 0; i < sourceData.limit(); i++) {
                outData.put(i, sourceData.get(i));
            }
            JmeSystem.writeImageFile(out, "png", outData, img.getWidth(), img.getHeight());
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

}
