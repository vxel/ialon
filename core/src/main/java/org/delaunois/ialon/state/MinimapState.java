/*
 * Copyright (C) 2022 Cédric de Launois
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.material.Material;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.shape.Quad;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.blocks.generator.NoiseTerrainGenerator;
import org.delaunois.ialon.blocks.generator.TerrainGenerator;
import org.delaunois.ialon.ui.UiHelper;

import java.nio.ByteBuffer;

import lombok.extern.slf4j.Slf4j;

/**
 * On-screen minimap : a small square, to the left of the Jump button and of the same height, showing a
 * <b>top-down view of the far terrain only</b> (no voxels). The relief is baked once into a texture from
 * the same procedural heightmap the {@link FarTerrainState} already computed (no second costly sampling of
 * the generator), coloured with the same altitude palette as {@code Shaders/FarTerrain.frag} (water / sand
 * / grass / rock / snow). A small triangular marker tracks the player's position and view heading.
 *
 * <p>On a finite (torus) world the map shows one full world period ({@code worldSize}) and the marker wraps
 * around the edges (modulo the period). On an infinite world it shows a fixed window centered on the origin.
 * Toggled by {@code config.showMinimap} via {@link SettingsState}.</p>
 *
 * @author Cedric de Launois
 */
@Slf4j
public class MinimapState extends BaseAppState implements Resizable {

    // Gap (GUI pixels) between the minimap and the Jump button, matching ButtonManagerState's button gap.
    private static final float SPACING = 10f;
    // Baked minimap texture resolution. Small : it is a thumbnail, and the source heightmap is coarser still.
    private static final int TEX_SIZE = 192;
    // Player marker size as a fraction of the minimap side.
    private static final float MARKER_RATIO = 0.16f;
    // Dark frame thickness as a fraction of the whole footprint (min 2px). The frame is inside the footprint :
    // frame + map together occupy exactly the Jump button size.
    private static final float BORDER_RATIO = 0.04f;

    private final IalonConfig config;
    private SimpleApplication app;

    private Node minimapNode;
    private Node marker;

    // Fixed footprint of the whole widget (frame included) : the Jump button height (camera height / 6),
    // computed once at init and kept across resolution changes — exactly like the control buttons (only the
    // margin scales). The inner map is inset by the frame border, so the widget matches the button's size.
    private int buttonSize;
    private float border;
    private float mapSize;

    // World span shown by the map : worldSize (one period) on the torus, farTerrainExtent on infinite worlds.
    private float worldExtent;
    private boolean torus;

    // Reusable temporaries (no per-frame allocation in update()).
    private final Vector3f tmpDir = new Vector3f();
    private final Quaternion tmpRot = new Quaternion();

    public MinimapState(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void initialize(Application application) {
        this.app = (SimpleApplication) application;
        // Whole footprint = Jump button size ; the dark frame is inset, so frame + map together match it.
        this.buttonSize = app.getCamera().getHeight() / 6;
        this.border = Math.max(2f, buttonSize * BORDER_RATIO);
        this.mapSize = buttonSize - 2 * border;

        float worldSize = config.getWorldSize();
        this.torus = worldSize > 0f;
        this.worldExtent = torus ? worldSize : config.getFarTerrainExtent();

        Texture2D mapTex = bakeTexture();
        if (mapTex == null) {
            log.warn("No terrain data available : minimap disabled");
            return;
        }

        minimapNode = new Node("Minimap");

        // Translucent dark frame filling the whole footprint (like the button backgrounds), behind the map.
        Material frameMat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        frameMat.setColor("Color", UiHelper.overlayColor(new ColorRGBA(0f, 0f, 0f, 0.6f), config));
        frameMat.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        Geometry frame = new Geometry("MinimapFrame", new Quad(buttonSize, buttonSize));
        frame.setMaterial(frameMat);
        frame.setLocalTranslation(0, 0, -1);
        minimapNode.attachChild(frame);

        // The baked top-down terrain, opaque, inset by the frame border.
        Material mapMat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        mapMat.setTexture("ColorMap", mapTex);
        Geometry mapQuad = new Geometry("MinimapMap", new Quad(mapSize, mapSize));
        mapQuad.setMaterial(mapMat);
        mapQuad.setLocalTranslation(border, border, 0);
        minimapNode.attachChild(mapQuad);

        // Player marker : an outlined arrow pointing +Y (up) by default ; update() rotates it to the view
        // heading and places it over the player's exact position (the dot at its centre).
        marker = createMarker(mapSize * MARKER_RATIO);
        minimapNode.attachChild(marker);

        layout(app.getCamera().getWidth(), app.getCamera().getHeight());

        if (app.getStateManager().getState(ScreenState.class) != null) {
            app.getStateManager().getState(ScreenState.class).register(this);
        }
    }

    @Override
    protected void cleanup(Application application) {
        if (app.getStateManager().getState(ScreenState.class) != null) {
            app.getStateManager().getState(ScreenState.class).unregister(this);
        }
    }

    @Override
    protected void onEnable() {
        if (minimapNode != null && minimapNode.getParent() == null) {
            app.getGuiNode().attachChild(minimapNode);
        }
    }

    @Override
    protected void onDisable() {
        if (minimapNode != null && minimapNode.getParent() != null) {
            minimapNode.removeFromParent();
        }
    }

    @Override
    public void update(float tpf) {
        if (marker == null) {
            return;
        }
        Vector3f p = config.getPlayerLocation();
        if (p == null) {
            return;
        }
        // Player position -> map UV. Same (u, v) convention as bakeTexture(), which samples the relief
        // CENTERED on the origin (world [-extent/2, +extent/2) -> u,v in [0,1]) : u along +X (east, right),
        // v along +Z (south, up). The +0.5 shift puts the origin at the map center. On the torus the shifted
        // position wraps with the world period (the relief is periodic, so the shown period == the world).
        float u;
        float v;
        if (torus) {
            u = floorModF(p.x / worldExtent + 0.5f, 1f);
            v = floorModF(p.z / worldExtent + 0.5f, 1f);
        } else {
            u = clamp01(p.x / worldExtent + 0.5f);
            v = clamp01(p.z / worldExtent + 0.5f);
        }
        marker.setLocalTranslation(border + u * mapSize, border + v * mapSize, 1f);

        // Heading : camera forward projected on XZ. The marker points +Y (towards +Z on the map) at angle 0.
        Vector3f dir = app.getCamera().getDirection(tmpDir);
        float angle = FastMath.atan2(dir.x, dir.z);
        tmpRot.fromAngleAxis(-angle, Vector3f.UNIT_Z);
        marker.setLocalRotation(tmpRot);
    }

    @Override
    public void onResize(int width, int height) {
        if (minimapNode != null) {
            layout(width, height);
        }
    }

    /**
     * Positions the minimap to the left of the Jump button, sharing its vertical band. Reproduces
     * {@link ButtonManagerState}'s layout maths : the Jump button sits at {@code (width - margin - size)}
     * with its bottom at {@code margin}; the minimap right edge is one {@code SPACING} to its left. Only the
     * margin scales with the resolution — the square side is fixed (like the buttons).
     */
    private void layout(int width, int height) {
        float margin = UiHelper.screenMargin(height);
        float jumpX = width - margin - buttonSize;
        float x = jumpX - SPACING - buttonSize;
        float y = margin;
        minimapNode.setLocalTranslation(x, y, 2);
    }

    /**
     * Builds the player marker : an <b>outlined</b> (unfilled) arrow pointing +Y by default, plus a small
     * filled dot at the origin marking the player's exact position. The marker is translated to that exact
     * position and rotated to the view heading in {@link #update}.
     */
    private Node createMarker(float size) {
        Node node = new Node("MinimapMarker");
        // Like the baked texture, sRGB-encode the solid colour on Android so it isn't shown too dark.
        ColorRGBA color = encodeSrgb(new ColorRGBA(1f, 0.15f, 0.15f, 1f));
        float h = size;
        float w = size * 0.7f;

        // Arrow outline : the triangle's three edges drawn as lines (not a filled face).
        float[] verts = {
                0f, h * 0.5f, 0f,         // tip
                -w * 0.5f, -h * 0.5f, 0f, // bottom-left
                w * 0.5f, -h * 0.5f, 0f   // bottom-right
        };
        Mesh outline = new Mesh();
        outline.setMode(Mesh.Mode.Lines);
        outline.setBuffer(VertexBuffer.Type.Position, 3, verts);
        outline.setBuffer(VertexBuffer.Type.Index, 2, new short[]{0, 1, 1, 2, 2, 0});
        outline.updateBound();
        Material outlineMat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        outlineMat.setColor("Color", color);
        Geometry arrow = new Geometry("MinimapMarkerArrow", outline);
        arrow.setMaterial(outlineMat);
        node.attachChild(arrow);

        // Precise position : a tiny filled dot at the marker origin (the player's exact location).
        float d = Math.max(2f, size * 0.16f);
        Material dotMat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        dotMat.setColor("Color", color);
        Geometry dot = new Geometry("MinimapMarkerDot", new Quad(d, d));
        dot.setMaterial(dotMat);
        dot.setLocalTranslation(-d * 0.5f, -d * 0.5f, 0.01f);
        node.attachChild(dot);

        return node;
    }

    /**
     * Bakes the top-down terrain texture once. Reuses the {@link FarTerrainState} heightmap when present
     * (no second generator sampling) ; otherwise samples the generator directly over the shown extent. Each
     * texel is coloured by altitude with the same palette as {@code FarTerrain.frag} (terrain colour only —
     * no lighting / fog / reflection). Returns {@code null} if no terrain source is available.
     */
    private Texture2D bakeTexture() {
        FarTerrainState far = app.getStateManager().getState(FarTerrainState.class);
        float[] heights = far != null ? far.getHeightmap() : null;
        int hmSize = heights != null ? (int) Math.round(Math.sqrt(heights.length)) : 0;
        float hmStep = far != null ? far.getStep() : 0f;

        TerrainGenerator generator = config.getTerrainGenerator();
        if (heights == null && generator == null) {
            return null;
        }

        float waterHeight = config.getWaterHeight();
        float rockHeight = NoiseTerrainGenerator.ROCK_LINE_RATIO * config.getMaxy();
        float snowHeight = NoiseTerrainGenerator.SNOW_LINE_RATIO * config.getMaxy();
        ColorRGBA sand = config.getFarTerrainSandColor();
        ColorRGBA grass = config.getFarTerrainBaseColor();
        ColorRGBA rock = config.getFarTerrainRockColor();
        ColorRGBA snow = config.getFarTerrainSnowColor();
        ColorRGBA water = config.getCalmWaterColor();

        // Android (no hardware sRGB framebuffer : manualGammaEncode) does not sRGB-encode on output, and the
        // plain Unshaded GUI shader doesn't emulate it (unlike the world shaders' MANUAL_SRGB) ; so the linear
        // palette would display far too dark. Pre-encode the texels to sRGB here in that case. On desktop the
        // hardware framebuffer does the encode, so we keep the texels linear. (See memory: srgb-color-pipeline.)
        boolean manualSrgb = config.isManualGammaEncode();
        ByteBuffer data = BufferUtils.createByteBuffer(TEX_SIZE * TEX_SIZE * 4);
        Vector3f sample = new Vector3f();
        ColorRGBA c = new ColorRGBA();
        for (int j = 0; j < TEX_SIZE; j++) {
            float v = (j + 0.5f) / TEX_SIZE;
            for (int i = 0; i < TEX_SIZE; i++) {
                float u = (i + 0.5f) / TEX_SIZE;
                float height;
                if (heights != null) {
                    height = sampleHeightmap(heights, hmSize, hmStep, u, v);
                } else {
                    // Fallback : sample the generator over the shown extent, centered on the origin.
                    float worldX = (u - 0.5f) * worldExtent;
                    float worldZ = (v - 0.5f) * worldExtent;
                    height = generator.getHeight(sample.set(worldX, 0f, worldZ));
                }
                colorAt(c, height, waterHeight, rockHeight, snowHeight, sand, grass, rock, snow, water);
                data.put(toByte(c.r, manualSrgb));
                data.put(toByte(c.g, manualSrgb));
                data.put(toByte(c.b, manualSrgb));
                data.put((byte) 0xFF);
            }
        }
        data.flip();
        // Flag the image Linear so GL never decodes it on sample : the bytes are already what we want on the
        // wire. Desktop : linear bytes + the hardware sRGB framebuffer encodes on output. Android : bytes
        // pre-encoded to sRGB above + no framebuffer encode. Both paths display the same shade as the far
        // terrain. (See memory: srgb-color-pipeline.)
        Image img = new Image(Image.Format.RGBA8, TEX_SIZE, TEX_SIZE, data, ColorSpace.Linear);
        Texture2D tex = new Texture2D(img);
        tex.setMagFilter(Texture.MagFilter.Bilinear);
        tex.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
        return tex;
    }

    /**
     * Bilinearly samples the (pre-divided-by-step) far-terrain heightmap at map coordinate {@code (u, v)} in
     * {@code [0,1]} and returns the world Y. The shown span ({@code worldExtent}) is the central sub-grid of
     * the heightmap (which covers {@code 2*worldSize} on the torus, or the whole {@code farTerrainExtent} on
     * infinite worlds), so a period maps to {@code worldExtent/step} cells centered on the heightmap center.
     */
    private float sampleHeightmap(float[] h, int size, float step, float u, float v) {
        float periodCells = worldExtent / step;
        float start = (size - 1) * 0.5f - periodCells * 0.5f;
        float fx = start + u * periodCells;
        float fy = start + v * periodCells;
        int x0 = clampIdx((int) Math.floor(fx), size);
        int y0 = clampIdx((int) Math.floor(fy), size);
        int x1 = clampIdx(x0 + 1, size);
        int y1 = clampIdx(y0 + 1, size);
        float tx = fx - (float) Math.floor(fx);
        float ty = fy - (float) Math.floor(fy);
        float h00 = h[y0 * size + x0];
        float h10 = h[y0 * size + x1];
        float h01 = h[y1 * size + x0];
        float h11 = h[y1 * size + x1];
        float top = h00 + (h10 - h00) * tx;
        float bot = h01 + (h11 - h01) * tx;
        // Heights are pre-divided by step (see FarTerrainState.sampleHeightmap) : un-scale to world Y.
        return (top + (bot - top) * ty) * step;
    }

    /** Altitude palette, ported from {@code FarTerrain.frag} (terrain colour only). Writes into {@code out}. */
    private void colorAt(ColorRGBA out, float height, float waterHeight, float rockHeight, float snowHeight,
                         ColorRGBA sand, ColorRGBA grass, ColorRGBA rock, ColorRGBA snow, ColorRGBA water) {
        if (height < waterHeight) {
            // Coastal gradient : sand at the shoreline shallows -> calm-water colour with depth.
            float depth = clamp01(waterHeight - height);
            mix(out, sand, water, depth);
        } else {
            // Sand fringe -> grass -> bare rock -> snow caps, matching the voxel generator's tiers.
            mix(out, sand, grass, smoothstep(waterHeight, waterHeight + 2f, height));
            mix(out, out, rock, smoothstep(rockHeight - 2f, rockHeight + 2f, height));
            mix(out, out, snow, smoothstep(snowHeight - 2f, snowHeight + 2f, height));
        }
    }

    /** out = a + (b - a) * t, component-wise (RGB ; alpha forced to 1). Safe when {@code out == a}. */
    private static void mix(ColorRGBA out, ColorRGBA a, ColorRGBA b, float t) {
        out.r = a.r + (b.r - a.r) * t;
        out.g = a.g + (b.g - a.g) * t;
        out.b = a.b + (b.b - a.b) * t;
        out.a = 1f;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        if (edge0 == edge1) {
            return x < edge0 ? 0f : 1f;
        }
        float t = clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3f - 2f * t);
    }

    private static float clamp01(float x) {
        return x < 0f ? 0f : (x > 1f ? 1f : x);
    }

    /** Quantises a linear channel to a byte, sRGB-encoding it first when the framebuffer won't (Android). */
    private static byte toByte(float linear, boolean manualSrgb) {
        float x = clamp01(linear);
        if (manualSrgb) {
            x = (float) Math.pow(x, 1f / 2.2f);
        }
        return (byte) Math.round(x * 255f);
    }

    /** sRGB-encodes a solid (linear) colour when manual gamma encoding is active ; returns it unchanged else. */
    private ColorRGBA encodeSrgb(ColorRGBA c) {
        if (!config.isManualGammaEncode()) {
            return c;
        }
        float ig = 1f / 2.2f;
        return new ColorRGBA((float) Math.pow(c.r, ig), (float) Math.pow(c.g, ig), (float) Math.pow(c.b, ig), c.a);
    }

    private static int clampIdx(int i, int size) {
        return i < 0 ? 0 : (i >= size ? size - 1 : i);
    }

    /** Floating-point floor-mod : the non-negative remainder of {@code x / w} (for {@code w > 0}). */
    private static float floorModF(float x, float w) {
        return x - (float) Math.floor(x / w) * w;
    }
}
