package org.delaunois.ialon.fastnoise;

import com.jme3.app.SimpleApplication;
import com.jme3.material.Material;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Quad;
import com.jme3.system.AppSettings;
import com.jme3.texture.Texture2D;

public class Main extends SimpleApplication {

    public static void main(String... args) {

        Main main = new Main();

        AppSettings appSettings = new AppSettings(true);
        appSettings.setResolution(1280, 720);

        main.setSettings(appSettings);
        main.start();


    }

    @Override
    public void simpleInitApp() {

        NoiseLayer noiseLayer = new NoiseLayer();
        noiseLayer.setSeed(213);
        noiseLayer.setNoiseType(FastNoise.NoiseType.PerlinFractal);

        Texture2D texture2D = noiseLayer.generateTexture(720);

        Material unshaded = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        unshaded.setTexture("ColorMap", texture2D);

        Geometry geometry = new Geometry("Noise Geometry", new Quad(cam.getHeight(), cam.getHeight()));
        geometry.setMaterial(unshaded);

        guiNode.attachChild(geometry);

    }

}
