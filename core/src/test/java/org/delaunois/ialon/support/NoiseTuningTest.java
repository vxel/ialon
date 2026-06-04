package org.delaunois.ialon.support;

import com.jme3.app.SimpleApplication;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Quad;
import com.jme3.terrain.geomipmap.TerrainQuad;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;

import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.GuiGlobals;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.Slider;
import com.simsilica.lemur.DefaultRangedValueModel;

import org.delaunois.ialon.fastnoise.FastNoise;
import org.delaunois.ialon.fastnoise.LayeredNoise;
import org.delaunois.ialon.fastnoise.NoiseLayer;

import java.nio.ByteBuffer;
import java.util.Random;

/**
 * Manual (interactive) tool to fine-tune the terrain noise — NOT part of the automated suite.
 * Run its {@link #main(String[])} from the IDE.
 *
 * <p>Lemur sliders adjust the {@link LayeredNoise} layers (mountains / hills / details strength &amp;
 * frequency, hard floor) and live-rebuild BOTH previews from the SAME CPU noise that
 * {@code NoiseTerrainGenerator} uses :
 * <ul>
 *   <li>a 3D {@link TerrainQuad} (shaded relief, the FarTerrain material),</li>
 *   <li>a 2D top-down minimap (altitude-coloured), in the corner.</li>
 * </ul>
 * The current values are printed on screen : once a pleasing relief is found, copy them into
 * {@code NoiseTerrainGenerator.createWorldNoise()}.
 */
public class NoiseTuningTest extends SimpleApplication {

    private static final int PREVIEW_SIZE = 257;     // (2^n + 1) for TerrainQuad
    private static final int PATCH_SIZE = 65;
    private static final int MAP_SIZE = 256;         // minimap resolution
    private static final float EXTENT = 4096f;       // world span of the preview
    private static final float WATER_HEIGHT = 50f;
    private static final long SEED = 2;

    // Tunable parameters (defaults mirror the current NoiseTerrainGenerator.createWorldNoise()).
    private final Param mountainsStrength = new Param("Mountains strength", 0, 160, 82);
    private final Param mountainsFreqDiv = new Param("Mountains freq div", 1, 8, 8);
    private final Param hillsStrength = new Param("Hills strength", 0, 96, 41);
    private final Param hillsFreqDiv = new Param("Hills freq div", 1, 8, 3);
    private final Param detailsStrength = new Param("Details strength", 0, 48, 21);
    private final Param hardFloorStrength = new Param("Hard floor strength", 0, 1, 0.6f);
    private final Param baseHeight = new Param("Base height", 0, 64, 32);
    private final Param[] params = {
            mountainsStrength, mountainsFreqDiv, hillsStrength, hillsFreqDiv,
            detailsStrength, hardFloorStrength, baseHeight
    };

    private LayeredNoise noise;
    private TerrainQuad terrain;
    private Geometry minimap;
    private Material minimapMat;
    private Label valuesLabel;

    private boolean dirty = true;
    private float settleTimer = 0f;

    public static void main(String[] args) {
        NoiseTuningTest app = new NoiseTuningTest();
        app.start();
    }

    @Override
    public void simpleInitApp() {
        GuiGlobals.initialize(this);
        viewPort.setBackgroundColor(new ColorRGBA(0.5f, 0.65f, 0.85f, 1f));
        // Keep the cursor free for the sliders : the camera only rotates while the left button is held.
        flyCam.setDragToRotate(true);
        flyCam.setMoveSpeed(400f);
        cam.setFrustumFar(10000f);
        cam.setLocation(new Vector3f(0f, 220f, 900f));
        cam.lookAt(new Vector3f(0f, 40f, 0f), Vector3f.UNIT_Y);

        buildSliders();
        rebuildAll();
    }

    private void buildSliders() {
        Container panel = new Container();
        panel.addChild(new Label("Noise tuning"));
        for (Param p : params) {
            Container row = panel.addChild(new Container());
            row.setLayout(new com.simsilica.lemur.component.SpringGridLayout(Axis.X, Axis.Y));
            row.addChild(new Label(p.name + " "));
            p.slider = row.addChild(new Slider(new DefaultRangedValueModel(p.min, p.max, p.value), Axis.X));
        }
        valuesLabel = panel.addChild(new Label(""));
        panel.setLocalTranslation(10f, cam.getHeight() - 10f, 0f);
        guiNode.attachChild(panel);

        // Minimap quad (top-right corner).
        float size = cam.getHeight() / 3f;
        minimap = new Geometry("minimap", new Quad(size, size));
        minimapMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        minimap.setMaterial(minimapMat);
        minimap.setLocalTranslation(cam.getWidth() - size - 10f, cam.getHeight() - 10f, 0f);
        guiNode.attachChild(minimap);
    }

    @Override
    public void simpleUpdate(float tpf) {
        boolean changed = false;
        for (Param p : params) {
            float v = (float) p.slider.getModel().getValue();
            if (v != p.value) {
                p.value = v;
                changed = true;
            }
        }
        if (changed) {
            dirty = true;
            settleTimer = 0f;
        }
        // Debounce : rebuild shortly after the last slider movement (the 257^2 rebuild is not free).
        if (dirty) {
            settleTimer += tpf;
            if (settleTimer >= 0.15f) {
                rebuildAll();
                dirty = false;
            }
        }
    }

    private void rebuildAll() {
        buildNoise();
        rebuildTerrain();
        rebuildMinimap();
        updateValuesLabel();
    }

    /** Mirrors NoiseTerrainGenerator.createWorldNoise() but with the slider-driven parameters. */
    private void buildNoise() {
        Random random = new Random(SEED);
        noise = new LayeredNoise();
        noise.setHardFloor(true);
        noise.setHardFloorHeight(WATER_HEIGHT);
        noise.setHardFloorStrength(hardFloorStrength.value);

        noise.addLayer(layer(random, "mountains", mountainsStrength.value, mountainsFreqDiv.value));
        noise.addLayer(layer(random, "hills", hillsStrength.value, hillsFreqDiv.value));
        noise.addLayer(layer(random, "details", detailsStrength.value, 1f));
    }

    private NoiseLayer layer(Random random, String name, float strength, float freqDiv) {
        NoiseLayer layer = new NoiseLayer(name);
        layer.setSeed(random.nextInt());
        layer.setNoiseType(FastNoise.NoiseType.SimplexFractal);
        layer.setStrength(strength);
        layer.setFrequency(layer.getFrequency() / freqDiv);
        return layer;
    }

    private float height(float worldX, float worldZ, Vector2f sample) {
        return noise.evaluate(sample.set(worldX, worldZ)) + baseHeight.value;
    }

    private void rebuildTerrain() {
        float step = EXTENT / (PREVIEW_SIZE - 1);
        float half = (PREVIEW_SIZE - 1) / 2f;
        float[] heightmap = new float[PREVIEW_SIZE * PREVIEW_SIZE];
        Vector2f sample = new Vector2f();
        for (int j = 0; j < PREVIEW_SIZE; j++) {
            float worldZ = (j - half) * step;
            int row = j * PREVIEW_SIZE;
            for (int i = 0; i < PREVIEW_SIZE; i++) {
                // Pre-divided by step : uniform scale keeps the normals correct (see FarTerrainState).
                heightmap[row + i] = height((i - half) * step, worldZ, sample) / step;
            }
        }

        if (terrain != null) {
            terrain.removeFromParent();
        }
        terrain = new TerrainQuad("NoisePreview", PATCH_SIZE, PREVIEW_SIZE, heightmap);
        Material mat = new Material(assetManager, "Shaders/FarTerrain.j3md");
        mat.setColor("BaseColor", new ColorRGBA(0.30f, 0.45f, 0.22f, 1f));
        mat.setColor("FogColor", new ColorRGBA(0.5f, 0.65f, 0.85f, 1f));
        mat.setFloat("FogDistance", 4000f);
        mat.setFloat("FogDensity", 1f);
        mat.setFloat("DepthBias", 0f); // no voxels in this preview -> no bias needed
        mat.setVector3("LightDir", new Vector3f(-0.5f, -0.7f, -0.5f).normalizeLocal());
        terrain.setMaterial(mat);
        terrain.setLocalScale(step, step, step);
        rootNode.attachChild(terrain);
    }

    private void rebuildMinimap() {
        float step = EXTENT / (MAP_SIZE - 1);
        float half = (MAP_SIZE - 1) / 2f;
        ByteBuffer buffer = BufferUtils.createByteBuffer(MAP_SIZE * MAP_SIZE * 4);
        Vector2f sample = new Vector2f();
        for (int j = 0; j < MAP_SIZE; j++) {
            float worldZ = (j - half) * step;
            for (int i = 0; i < MAP_SIZE; i++) {
                ColorRGBA c = altitudeColor(height((i - half) * step, worldZ, sample));
                buffer.put((byte) (c.r * 255)).put((byte) (c.g * 255)).put((byte) (c.b * 255)).put((byte) 255);
            }
        }
        buffer.flip();
        Image img = new Image(Image.Format.RGBA8, MAP_SIZE, MAP_SIZE, buffer, ColorSpace.sRGB);
        minimapMat.setTexture("ColorMap", new Texture2D(img));
    }

    private ColorRGBA altitudeColor(float h) {
        if (h < WATER_HEIGHT) {
            float t = Math.max(0f, Math.min(1f, (h - (WATER_HEIGHT - 30f)) / 30f));
            return new ColorRGBA(0.03f, 0.15f + 0.25f * t, 0.35f + 0.3f * t, 1f);
        }
        float t = Math.max(0f, Math.min(1f, (h - WATER_HEIGHT) / 80f));
        // green -> gray -> white with altitude
        return new ColorRGBA(0.25f + 0.7f * t, 0.45f + 0.45f * t, 0.18f + 0.75f * t, 1f);
    }

    private void updateValuesLabel() {
        valuesLabel.setText(String.format(
                "mountains=%.0f /%.0f  hills=%.0f /%.0f  details=%.0f  hardFloor=%.2f  base=%.0f",
                mountainsStrength.value, mountainsFreqDiv.value,
                hillsStrength.value, hillsFreqDiv.value,
                detailsStrength.value, hardFloorStrength.value, baseHeight.value));
    }

    private static final class Param {
        final String name;
        final float min;
        final float max;
        float value;
        Slider slider;

        Param(String name, float min, float max, float value) {
            this.name = name;
            this.min = min;
            this.max = max;
            this.value = value;
        }
    }
}
