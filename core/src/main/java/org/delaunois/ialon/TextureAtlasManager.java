package org.delaunois.ialon;

import com.jme3.texture.Texture;
import com.jme3.texture.image.ColorSpace;

import jme3tools.optimize.TextureAtlas;
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
        atlas = new TextureAtlas(1024, 1024);
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

}
