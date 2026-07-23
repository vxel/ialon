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

import static org.delaunois.ialon.Ialon.IALON_STYLE;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.input.event.MouseButtonEvent;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.shape.Quad;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.HAlignment;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.VAlignment;
import com.simsilica.lemur.event.DefaultMouseListener;
import com.simsilica.lemur.event.MouseEventControl;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.WorldEditOverlay;
import org.delaunois.ialon.blocks.generator.NoiseTerrainGenerator;
import org.delaunois.ialon.blocks.generator.TerrainGenerator;
import org.delaunois.ialon.ui.UiHelper;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

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
public class MinimapState extends BaseAppState implements Resizable, Popup {

    // Gap (GUI pixels) between the minimap and the Jump button, matching ButtonManagerState's button gap.
    private static final float SPACING = 10f;
    // Baked minimap texture resolution. Small : it is a thumbnail, and the source heightmap is coarser still.
    private static final int TEX_SIZE = 192;
    // Higher resolution baked once for the enlarged popup map, so it isn't blurry when scaled up. Baked
    // lazily on first open (slight one-time cost). ~3 MB at RGBA8 — negligible against the app footprint.
    private static final int POPUP_TEX_SIZE = 768;
    // Player marker size as a fraction of the minimap side.
    private static final float MARKER_RATIO = 0.16f;
    // Dark frame thickness as a fraction of the whole footprint (min 2px). The frame is inside the footprint :
    // frame + map together occupy exactly the Jump button size.
    private static final float BORDER_RATIO = 0.04f;

    // Fraction of the large popup map side used for the player marker (smaller than MARKER_RATIO since the
    // popup map is much bigger than the thumbnail).
    private static final float POPUP_MARKER_RATIO = 0.05f;
    // Side of the large popup map as a fraction of the smaller screen dimension (leaving room for margins).
    private static final float POPUP_MAP_RATIO = 0.75f;
    // Period (seconds) of the player-position dot's sinusoidal alpha pulse.
    private static final float DOT_PULSE_PERIOD = 2f;

    // Tint (and its opacity) blended over the baked terrain to mark a chunk column the player has edited.
    private static final ColorRGBA EDIT_MARKER_COLOR = new ColorRGBA(1f, 0f, 0f, 1f); //new ColorRGBA(0.85f, 0.22f, 0.10f, 1f);
    private static final float EDIT_MARKER_ALPHA = 0.5f;

    private final IalonConfig config;
    private SimpleApplication app;

    private Node minimapNode;
    private Geometry marker;     // camera-heading triangle (compass needle riding the inscribed circle)
    private Node positionDot;    // player's exact position on the map
    private Texture2D mapTex;
    private Texture2D popupMapTex; // the popup's higher-res bake, kept so its edit markers stay in sync

    // Modified-chunk markers : the persisted overlay set is the source of truth ; markedColumns are the
    // ones already tinted into the textures (so a repaint never double-blends the same column), and
    // lastModifiedCount lets update() detect new edits with a single O(1) size compare per frame.
    private WorldEditOverlay editOverlay;
    private final Set<Long> markedColumns = new HashSet<>();
    private int lastModifiedCount;
    private boolean manualSrgb;
    private int markerR; // EDIT_MARKER_COLOR quantised to bytes the same way the bake encodes texels
    private int markerG;
    private int markerB;
    private int chunkSizeX;

    // Full-screen popup showing the minimap in large, opened by clicking the thumbnail. Built lazily.
    private Container minimapPopup;
    private Geometry popupMarker;
    private Node popupDot;
    private Button popupClose;
    private Label popupTitle;   // live player-position readout, centred above the big map
    // Cached popup-local placement of the big map (its bottom-left corner and side), so update() can place
    // the popup marker without recomputing the layout each frame.
    private float popupMapLeft;
    private float popupMapBottom;
    private float popupMapSide;

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
    private final ColorRGBA tmpDotColor = new ColorRGBA(1f, 1f, 1f, 1f);

    // Materials of the (white) position dots, whose alpha is pulsed each frame ; popupDotMat is rebuilt
    // with the popup map on resize. Accumulated time driving the sine pulse.
    private Material dotMat;
    private Material popupDotMat;
    private float pulseTime;

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

        mapTex = bakeTexture(TEX_SIZE);
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

        // Camera-heading triangle (white, filled) that rides the minimap's inscribed circle near the edge,
        // plus a dot at the player's exact position. Both are placed each frame in update().
        marker = createMarker(mapSize * MARKER_RATIO);
        positionDot = createDot(mapSize * MARKER_RATIO * 0.4f);
        dotMat = ((Geometry) positionDot.getChild("MinimapDot")).getMaterial();
        minimapNode.attachChild(marker);
        minimapNode.attachChild(positionDot);

        // Clicking the thumbnail opens the large minimap popup. Consume the event so it doesn't fall
        // through to the game (look/place) behind the widget.
        MouseEventControl.addListenersToSpatial(minimapNode, new OpenPopupMouseListener());

        layout(app.getCamera().getWidth(), app.getCamera().getHeight());

        // Modified-chunk markers : encode the tint to bytes once (matching bakeTexture's encoding), then
        // paint the columns already on disk into the freshly baked thumbnail. Live edits are picked up in
        // update(). manualSrgb / chunkSizeX cached for paintColumn (no per-edit lookups).
        this.manualSrgb = config.isManualGammaEncode();
        this.markerR = toByte(EDIT_MARKER_COLOR.r, manualSrgb) & 0xFF;
        this.markerG = toByte(EDIT_MARKER_COLOR.g, manualSrgb) & 0xFF;
        this.markerB = toByte(EDIT_MARKER_COLOR.b, manualSrgb) & 0xFF;
        this.chunkSizeX = BlocksConfig.getInstance().getChunkSize().x;
        this.editOverlay = config.getWorldEditOverlay();
        refreshEditMarkers();

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
        hidePopup();
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

        // Cheap per-frame check : tint any chunk column edited since last frame. The texture is otherwise
        // static, so this is the only thing that ever changes it (no per-frame rebake, no extra draw call).
        refreshEditMarkers();
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

        // View heading : camera forward projected on XZ, normalised (u along +X, v along +Z). The triangle
        // points +Y by default ; this rotation turns +Y onto that direction so the tip points where the
        // camera looks, and the same (dxn, dzn) pushes it out onto the inscribed circle (see placeHeading).
        Vector3f dir = app.getCamera().getDirection(tmpDir);
        float len = FastMath.sqrt(dir.x * dir.x + dir.z * dir.z);
        float dxn = len > 1e-4f ? dir.x / len : 0f;
        float dzn = len > 1e-4f ? dir.z / len : 1f;
        float angle = FastMath.atan2(dir.x, dir.z);
        tmpRot.fromAngleAxis(-angle, Vector3f.UNIT_Z);

        // Position dot : white, with a sinusoidal alpha pulse (period DOT_PULSE_PERIOD), so it gently
        // blinks. alpha = (1 + sin) / 2 sweeps the full [0,1] range.
        pulseTime += tpf;
        float alpha = 0.5f + 0.5f * FastMath.sin(FastMath.TWO_PI * pulseTime / DOT_PULSE_PERIOD);
        tmpDotColor.a = alpha;
        dotMat.setColor("Color", tmpDotColor);

        // Thumbnail : dot at the player's position, heading triangle on the inscribed circle near the edge.
        positionDot.setLocalTranslation(border + u * mapSize, border + v * mapSize, 1f);
        placeHeading(marker, border + mapSize * 0.5f, border + mapSize * 0.5f, mapSize, mapSize * MARKER_RATIO, dxn, dzn, 1f);

        // Same on the large popup while it is open.
        if (popupMarker != null && minimapPopup != null && minimapPopup.getParent() != null) {
            float pcx = popupMapLeft + popupMapSide * 0.5f;
            float pcy = popupMapBottom + popupMapSide * 0.5f;
            popupDotMat.setColor("Color", tmpDotColor);
            popupDot.setLocalTranslation(popupMapLeft + u * popupMapSide, popupMapBottom + v * popupMapSide, 2f);
            placeHeading(popupMarker, pcx, pcy, popupMapSide, popupMapSide * POPUP_MARKER_RATIO, dxn, dzn, 2f);

            // Title : live player position, centred over the map width, just above its top edge.
            popupTitle.setText(formatPosition(p));
            Vector3f ps = popupTitle.getPreferredSize();
            float mapTop = popupMapBottom + popupMapSide;
            popupTitle.setLocalTranslation(pcx - ps.x * 0.5f, mapTop + popupMapSide * 0.04f + ps.y, 10);
        }
    }

    /** Formats the player's world position as a one-line title, e.g. {@code "X 123   Y 64   Z -45"}. */
    private static String formatPosition(Vector3f p) {
        return String.format(Locale.ENGLISH, "X %d   Y %d   Z %d",
                Math.round(p.x), Math.round(p.y), Math.round(p.z));
    }

    /**
     * Places the heading triangle on the minimap's inscribed circle : from the map centre {@code (cx, cy)},
     * pushed out along the (normalised) view direction by half the map side — less half the marker, so the
     * triangle's tip just reaches the edge rather than poking past it — and rotated to point outward.
     */
    private void placeHeading(Geometry tri, float cx, float cy, float side, float markerSize,
                              float dxn, float dzn, float z) {
        float radius = side * 0.5f - markerSize * 0.5f;
        tri.setLocalTranslation(cx + dxn * radius, cy + dzn * radius, z);
        tri.setLocalRotation(tmpRot);
    }

    @Override
    public void onResize(int width, int height) {
        if (minimapNode != null) {
            layout(width, height);
        }
        if (minimapPopup != null) {
            layoutPopup(width, height);
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
     * Opens the large minimap popup, building it on first use. Disables player touch input (like the
     * settings / world-menu popups) so the game doesn't react to clicks behind the popup.
     */
    private void showPopup() {
        if (minimapPopup == null) {
            createPopup();
        }
        if (minimapPopup.getParent() == null) {
            app.getGuiNode().attachChild(minimapPopup);
            setPlayerTouchEnabled(false);
        }
    }

    /** Closes the large minimap popup (no-op if not open) and re-enables player touch input. */
    private void hidePopup() {
        if (minimapPopup != null && minimapPopup.getParent() != null) {
            minimapPopup.removeFromParent();
            setPlayerTouchEnabled(true);
        }
    }

    @Override
    public boolean isPopupOpen() {
        return minimapPopup != null && minimapPopup.getParent() != null;
    }

    @Override
    public void closePopup() {
        hidePopup();
    }

    private void setPlayerTouchEnabled(boolean enabled) {
        PlayerState playerState = app.getStateManager().getState(PlayerState.class);
        if (playerState != null) {
            playerState.setTouchEnabled(enabled);
        }
    }

    /**
     * Builds the full-screen popup : a translucent dark backdrop (same as the settings / world-menu popups),
     * a large square map reusing the already-baked terrain texture, a player marker tracked live in
     * {@link #update}, and a "Close" button in the top-right corner. Built once, lazily.
     */
    private void createPopup() {
        int width = app.getCamera().getWidth();
        int height = app.getCamera().getHeight();

        minimapPopup = new Container(IALON_STYLE);
        minimapPopup.setName("minimapPopup");
        minimapPopup.setLocalTranslation(0, height, 100);
        minimapPopup.setPreferredSize(new Vector3f(width, height, 0));
        UiHelper.addBackground(minimapPopup, new ColorRGBA(0f, 0f, 0f, 0.95f), config);
        // Clicking the backdrop (beside the enlarged map) closes the popup ; the click is consumed either
        // way so it doesn't reach the game.
        minimapPopup.addMouseListener(new CloseOnClickListener());

        // Large map on a higher-resolution bake (so it isn't blurry when enlarged), falling back to the
        // thumbnail texture if that bake fails. A unit quad scaled in layoutPopup() so resolution changes
        // don't rebuild the mesh. Clicks on the map itself are consumed (so they don't bubble up to the
        // backdrop and close the popup).
        popupMapTex = bakeTexture(POPUP_TEX_SIZE);
        if (popupMapTex != null && !markedColumns.isEmpty()) {
            // The popup bake is fresh : replay the edit tints already shown on the thumbnail.
            for (long key : markedColumns) {
                paintColumn(popupMapTex, POPUP_TEX_SIZE, key);
            }
            popupMapTex.getImage().setUpdateNeeded();
        }
        Material mapMat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        mapMat.setTexture("ColorMap", popupMapTex != null ? popupMapTex : mapTex);
        Geometry bigMap = new Geometry("MinimapPopupMap", new Quad(1, 1));
        bigMap.setMaterial(mapMat);
        MouseEventControl.addListenersToSpatial(bigMap, new IgnoreMouseClickListener());
        minimapPopup.attachChild(bigMap);

        // Close button, top-right corner, level with where popup titles sit.
        popupClose = new Button("Close", IALON_STYLE);
        popupClose.setTextVAlignment(VAlignment.Center);
        popupClose.addClickCommands(source -> hidePopup());
        minimapPopup.attachChild(popupClose);

        // Title : the live player position, centred above the map (text + placement set in update/layout).
        popupTitle = new Label("", IALON_STYLE);
        popupTitle.setColor(ColorRGBA.White);
        popupTitle.setTextHAlignment(HAlignment.Center);
        minimapPopup.attachChild(popupTitle);

        layoutPopup(width, height);
    }

    /**
     * (Re)positions the popup contents for the given screen size : centers and scales the big map, rebuilds
     * the (size-dependent) heading triangle + position dot, and places the Close button in the top-right
     * corner. Uses the popup-local convention (origin at the top-left, +x right, -y down) — the popup
     * container is translated to {@code (0, height)}.
     */
    private void layoutPopup(int width, int height) {
        minimapPopup.setPreferredSize(new Vector3f(width, height, 0));
        minimapPopup.setLocalTranslation(0, height, 100);

        popupMapSide = Math.min(width, height) * POPUP_MAP_RATIO;
        // Center on screen : screen center (width/2, height/2) maps to popup-local (width/2, -height/2).
        popupMapLeft = width * 0.5f - popupMapSide * 0.5f;
        popupMapBottom = -height * 0.5f - popupMapSide * 0.5f;

        Geometry bigMap = (Geometry) minimapPopup.getChild("MinimapPopupMap");
        bigMap.setLocalScale(popupMapSide, popupMapSide, 1f);
        // Just in front of the container backdrop (the marker sits one step further, see update()).
        bigMap.setLocalTranslation(popupMapLeft, popupMapBottom, 1f);

        // The marker / dot are built at a fixed pixel size, so rebuild them when the map side changes.
        if (popupMarker != null) {
            popupMarker.removeFromParent();
        }
        if (popupDot != null) {
            popupDot.removeFromParent();
        }
        popupMarker = createMarker(popupMapSide * POPUP_MARKER_RATIO);
        popupDot = createDot(popupMapSide * POPUP_MARKER_RATIO * 0.4f);
        popupDotMat = ((Geometry) popupDot.getChild("MinimapDot")).getMaterial();
        // They sit over the map : consume their clicks too so they don't bubble up and close the popup.
        MouseEventControl.addListenersToSpatial(popupMarker, new IgnoreMouseClickListener());
        MouseEventControl.addListenersToSpatial(popupDot, new IgnoreMouseClickListener());
        minimapPopup.attachChild(popupMarker);
        minimapPopup.attachChild(popupDot);

        float vh = height / 100f;
        popupClose.setFontSize(4 * vh);
        float margin = UiHelper.screenMargin(height);
        popupClose.setLocalTranslation(width - margin - popupClose.getPreferredSize().x, -margin, 10);

        // Title font ; its text and centred placement (over the map width, just above it) are set in update().
        popupTitle.setFontSize(4 * vh);
    }

    /**
     * Builds the camera-heading marker : a <b>filled white</b> triangle pointing +Y by default. In
     * {@link #update} it is rotated to the view heading and pushed out onto the minimap's inscribed circle
     * (see {@link #placeHeading}), so it rides near the edge like a compass needle pointing where you look.
     */
    private Geometry createMarker(float size) {
        // Like the baked texture, sRGB-encode the solid colour on Android so it isn't shown too dark.
        ColorRGBA color = encodeSrgb(ColorRGBA.White);
        float h = size * 0.5f;
        float w = size * 0.8f;

        // Filled triangle face (a single tri ; face culling off so winding never hides it).
        float[] verts = {
                0f, h * 0.5f, 0f,         // tip
                -w * 0.5f, -h * 0.5f, 0f, // bottom-left
                w * 0.5f, -h * 0.5f, 0f   // bottom-right
        };
        Mesh tri = new Mesh();
        tri.setBuffer(VertexBuffer.Type.Position, 3, verts);
        tri.setBuffer(VertexBuffer.Type.Index, 3, new short[]{0, 1, 2});
        tri.updateBound();
        Material mat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", color);
        mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        Geometry geom = new Geometry("MinimapMarker", tri);
        geom.setMaterial(mat);
        return geom;
    }

    /**
     * Builds the player-position dot : a small <b>white</b> square centred on the node origin (so
     * {@link #update} can place it directly at the player's map coordinate). Alpha-blended : its alpha is
     * pulsed sinusoidally each frame in {@link #update}. The diameter is clamped to at least 2 px.
     */
    private Node createDot(float diameter) {
        float d = Math.max(2f, diameter);
        Material mat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", new ColorRGBA(1f, 1f, 1f, 1f));
        mat.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        Geometry quad = new Geometry("MinimapDot", new Quad(d, d));
        quad.setMaterial(mat);
        quad.setLocalTranslation(-d * 0.5f, -d * 0.5f, 0f); // centre the quad on the node origin
        Node node = new Node("MinimapDotNode");
        node.attachChild(quad);
        return node;
    }

    /**
     * Bakes the top-down terrain texture once. Reuses the {@link FarTerrainState} heightmap when present
     * (no second generator sampling) ; otherwise samples the generator directly over the shown extent. Each
     * texel is coloured by altitude with the same palette as {@code FarTerrain.frag} (terrain colour only —
     * no lighting / fog / reflection). Returns {@code null} if no terrain source is available.
     */
    private Texture2D bakeTexture(int texSize) {
        FarTerrainState far = app.getStateManager().getState(FarTerrainState.class);
        float[] heights = far != null ? far.getHeightmap() : null;
        int hmSize = heights != null ? (int) Math.round(Math.sqrt(heights.length)) : 0;
        // sqrt-rounding may overshoot on a non-square length : keep hmSize*hmSize within the array bounds
        // so the bilinear sampler's max index (hmSize*hmSize - 1) can never overrun heights.
        if (hmSize > 0 && hmSize * hmSize > heights.length) {
            hmSize--;
        }
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
        // Biome grass-band colour : sampled per texel from the generator (same source as the near voxels
        // and the far horizon), so all three read one palette. Falls back to the flat base colour when the
        // generator isn't a NoiseTerrainGenerator.
        NoiseTerrainGenerator noise = generator instanceof NoiseTerrainGenerator
                ? (NoiseTerrainGenerator) generator : null;
        Vector2f biomeSample = new Vector2f();
        ColorRGBA biomeGrass = new ColorRGBA();

        // Android (no hardware sRGB framebuffer : manualGammaEncode) does not sRGB-encode on output, and the
        // plain Unshaded GUI shader doesn't emulate it (unlike the world shaders' MANUAL_SRGB) ; so the linear
        // palette would display far too dark. Pre-encode the texels to sRGB here in that case. On desktop the
        // hardware framebuffer does the encode, so we keep the texels linear. (See memory: srgb-color-pipeline.)
        boolean manualSrgb = config.isManualGammaEncode();
        ByteBuffer data = BufferUtils.createByteBuffer(texSize * texSize * 4);
        Vector3f sample = new Vector3f();
        ColorRGBA c = new ColorRGBA();
        for (int j = 0; j < texSize; j++) {
            float v = (j + 0.5f) / texSize;
            for (int i = 0; i < texSize; i++) {
                float u = (i + 0.5f) / texSize;
                // World coords for this texel : shown extent, centered on the origin. On the torus the
                // biome field is periodic with worldExtent, so origin-centered coords are correct.
                float worldX = (u - 0.5f) * worldExtent;
                float worldZ = (v - 0.5f) * worldExtent;
                float height;
                if (heights != null) {
                    height = sampleHeightmap(heights, hmSize, hmStep, u, v);
                } else {
                    height = generator.getHeight(sample.set(worldX, 0f, worldZ));
                }
                // Per-texel biome grass colour (continuous blend), or the flat base colour as a fallback.
                ColorRGBA grassColor = noise != null
                        ? noise.biomeColorAt(worldX, worldZ, biomeSample, biomeGrass) : grass;
                colorAt(c, height, waterHeight, rockHeight, snowHeight, sand, grassColor, rock, snow, water);
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
        Image img = new Image(Image.Format.RGBA8, texSize, texSize, data, ColorSpace.Linear);
        Texture2D tex = new Texture2D(img);
        tex.setMagFilter(Texture.MagFilter.Bilinear);
        tex.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
        return tex;
    }

    /**
     * Tints every chunk column the player has edited (the persisted {@link WorldEditOverlay#getModifiedColumns()}
     * set) onto the baked minimap texture(s). Incremental and idempotent : only columns not already painted are
     * blended, so calling it every frame is cheap — the usual case is a single O(1) size compare that finds
     * nothing new (the set only grows during play). It mutates the existing texture in place ({@code
     * setUpdateNeeded} re-uploads it), so there is no rebake and no extra geometry / draw call.
     */
    private void refreshEditMarkers() {
        if (editOverlay == null || mapTex == null) {
            return;
        }
        Set<Long> modified = editOverlay.getModifiedColumns();
        int n = modified.size();
        if (n == lastModifiedCount) {
            return;
        }
        lastModifiedCount = n;
        boolean thumbChanged = false;
        boolean popupChanged = false;
        for (long key : modified) {
            if (markedColumns.add(key)) {
                paintColumn(mapTex, TEX_SIZE, key);
                thumbChanged = true;
                if (popupMapTex != null) {
                    paintColumn(popupMapTex, POPUP_TEX_SIZE, key);
                    popupChanged = true;
                }
            }
        }
        if (thumbChanged) {
            mapTex.getImage().setUpdateNeeded();
        }
        if (popupChanged) {
            popupMapTex.getImage().setUpdateNeeded();
        }
    }

    /**
     * Blends the edit tint over the texels covering one chunk column in a baked map texture. The column's
     * world centre maps to {@code (u, v)} with the SAME convention as {@link #bakeTexture}/the player marker,
     * so the tint lands exactly where the column sits on the map. A sub-pixel chunk (the thumbnail spans the
     * whole world) still tints at least its centre texel ; on the larger popup a chunk covers a few texels.
     * On the torus the texel indices wrap with the map period, matching the seamless relief.
     */
    private void paintColumn(Texture2D tex, int texSize, long key) {
        float worldX = (WorldEditOverlay.unpackX(key) + 0.5f) * chunkSizeX;
        float worldZ = (WorldEditOverlay.unpackZ(key) + 0.5f) * chunkSizeX;
        float u = torus ? floorModF(worldX / worldExtent + 0.5f, 1f) : clamp01(worldX / worldExtent + 0.5f);
        float v = torus ? floorModF(worldZ / worldExtent + 0.5f, 1f) : clamp01(worldZ / worldExtent + 0.5f);
        int cx = (int) (u * texSize);
        int cy = (int) (v * texSize);
        int r = Math.max(0, (int) (chunkSizeX / worldExtent * texSize * 0.5f));
        ByteBuffer data = tex.getImage().getData(0);
        for (int dj = -r; dj <= r; dj++) {
            for (int di = -r; di <= r; di++) {
                int i = cx + di;
                int j = cy + dj;
                if (torus) {
                    i = Math.floorMod(i, texSize);
                    j = Math.floorMod(j, texSize);
                } else if (i < 0 || i >= texSize || j < 0 || j >= texSize) {
                    continue;
                }
                int idx = (j * texSize + i) * 4;
                data.put(idx, blendByte(data.get(idx), markerR));
                data.put(idx + 1, blendByte(data.get(idx + 1), markerG));
                data.put(idx + 2, blendByte(data.get(idx + 2), markerB));
            }
        }
    }

    /** Blends a stored texel channel byte toward the marker channel byte by {@link #EDIT_MARKER_ALPHA}. */
    private static byte blendByte(byte base, int marker) {
        int b = base & 0xFF;
        return (byte) Math.round(b + (marker - b) * EDIT_MARKER_ALPHA);
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

    /** Opens the large minimap popup when the thumbnail is pressed ; consumes the click either way. */
    private class OpenPopupMouseListener extends DefaultMouseListener {
        @Override
        public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
            event.setConsumed();
            if (event.isPressed()) {
                showPopup();
            }
        }
    }

    /** Closes the popup when its backdrop (beside the map) is pressed ; consumes the click either way. */
    private class CloseOnClickListener extends DefaultMouseListener {
        @Override
        public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
            event.setConsumed();
            if (event.isPressed()) {
                hidePopup();
            }
        }
    }

    /** Consumes clicks on the map / marker so they don't bubble up to the backdrop and close the popup. */
    private static class IgnoreMouseClickListener extends DefaultMouseListener {
        @Override
        public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
            event.setConsumed();
        }
    }
}
