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
import com.simsilica.lemur.Label;
import com.simsilica.lemur.event.DefaultMouseListener;
import com.simsilica.lemur.event.MouseEventControl;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.WorldEditOverlay;
import org.delaunois.ialon.blocks.generator.NoiseTerrainGenerator;
import org.delaunois.ialon.blocks.generator.TerrainGenerator;
import org.delaunois.ialon.ui.IalonTheme;
import org.delaunois.ialon.ui.UiHelper;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
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
    // Higher resolution baked once for the enlarged popup map, so it isn't blurry when scaled up. ~2 MB at
    // RGBA8 — negligible against the app footprint. Baked at WORLD LOAD, on a background thread (see
    // startPopupBake) : it is 16x the thumbnail's texel count (~200 ms on desktop, seconds on a mid-range
    // Android), which used to be paid on the render thread the first time the popup was opened.
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
    private static final ColorRGBA EDIT_MARKER_COLOR = new ColorRGBA(1f, 0f, 0f, 1f);
    private static final float EDIT_MARKER_ALPHA = 0.5f;

    private final IalonConfig config;
    private SimpleApplication app;

    private Node minimapNode;
    // Frame ring + heading triangle + position dot, merged into ONE vertex-coloured geometry (they are all
    // flat untextured shapes over the map, so only the scene graph ever made them three draw calls).
    // Rewritten in place each frame : 23 vertices, far cheaper than the two draw calls it replaces.
    private Geometry overlay;
    private FloatBuffer overlayPos;
    private FloatBuffer overlayCol;
    private final ColorRGBA frameColor = new ColorRGBA();
    private float markerHeight;
    private float dotHalf;
    private Texture2D mapTex;
    private Texture2D popupMapTex; // the popup's higher-res bake, kept so its edit markers stay in sync

    // Terrain source of a bake, captured on the render thread at init : the popup bake runs on a background
    // thread and must never reach into the state manager (nor re-sample the generator when the far-terrain
    // heightmap is there to reuse). Heights are the FarTerrainState array, pre-divided by step.
    private float[] bakeHeights;
    private int bakeHmSize;
    private float bakeHmStep;
    private TerrainGenerator bakeGenerator;
    private boolean manualSrgb;

    // Popup bake handed over by the background thread : the raw texels, published when ready, then turned
    // into popupMapTex on the render thread by installPopupTexture() (only the render thread may touch a
    // material). bakeCancelled stops a bake still running when the state is cleaned up (world switch), so
    // its result is dropped instead of installed into the next world's minimap.
    private volatile ByteBuffer pendingPopupTexels;
    private volatile boolean popupBakeDone;
    private volatile boolean bakeCancelled;

    // Modified-chunk markers : the persisted overlay set is the source of truth ; markedColumns are the
    // ones already tinted into the textures (so a repaint never double-blends the same column), and
    // lastModifiedCount lets update() detect new edits with a single O(1) size compare per frame.
    private WorldEditOverlay editOverlay;
    private final Set<Long> markedColumns = new HashSet<>();
    private int lastModifiedCount;
    private int markerR; // EDIT_MARKER_COLOR quantised to bytes the same way the bake encodes texels
    private int markerG;
    private int markerB;
    private int chunkSizeX;

    // Full-screen popup showing the minimap in large, opened by clicking the thumbnail. Built lazily.
    private Container minimapPopup;
    // Popup counterparts of the thumbnail's heading triangle and position dot. Kept as two separate
    // geometries (unlike the thumbnail's merged overlay) : the popup is open only while the player looks
    // at it, so its two draw calls never sit in a gameplay frame.
    private Geometry popupMarker;
    private Node popupDot;
    private Button popupClose;
    // Live player-position readout : this popup's title, so it is the label the chrome's title signboard
    // is built from (handed back by IalonTheme.applyChromeOver) rather than one of our own. Fixed width and
    // centre-aligned, so setting its text each frame neither resizes nor re-centres anything.
    private Label popupTitle;
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
    private final ColorRGBA tmpDotColor = new ColorRGBA(1f, 0f, 0f, 1f);

    // Material of the POPUP's position-marker triangle, whose (red) colour alpha is pulsed each frame ;
    // rebuilt with the popup map on resize. Accumulated time driving the sine pulse. The thumbnail's own
    // marker needs no material : its pulse is written straight into the merged overlay's vertex colours.
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

        // Captured before any bake : both the thumbnail (here, on the render thread) and the popup (on the
        // background thread below) read this snapshot rather than the state manager.
        this.manualSrgb = config.isManualGammaEncode();
        captureTerrainSource();

        mapTex = bakeTexture(TEX_SIZE);
        if (mapTex == null) {
            log.warn("No terrain data available : minimap disabled");
            return;
        }

        minimapNode = new Node("Minimap");

        // The baked top-down terrain, opaque, inset by the frame border.
        Material mapMat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        mapMat.setTexture("ColorMap", mapTex);
        Geometry mapQuad = new Geometry("MinimapMap", new Quad(mapSize, mapSize));
        mapQuad.setMaterial(mapMat);
        mapQuad.setLocalTranslation(border, border, 0);
        minimapNode.attachChild(mapQuad);

        // Everything that is NOT the map, in one geometry : the translucent dark frame, the heading
        // triangle at the player's position, and the small dot marking that exact position over it.
        // They differ only by colour, so a vertex-coloured mesh merges three draw calls into one.
        //
        // The frame used to be a full quad BEHIND the map ; here it is its visible border RING, in FRONT.
        // Identical on screen -- the map is opaque and covers exactly the ring's hole -- and it is what
        // lets the frame share a geometry with the marker, which has to be in front of the map.
        overlay = createOverlay();
        minimapNode.attachChild(overlay);

        // Clicking the thumbnail opens the large minimap popup. Consume the event so it doesn't fall
        // through to the game (look/place) behind the widget.
        MouseEventControl.addListenersToSpatial(minimapNode, new OpenPopupMouseListener());

        layout(app.getCamera().getWidth(), app.getCamera().getHeight());

        // Modified-chunk markers : encode the tint to bytes once (matching bakeTexture's encoding), then
        // paint the columns already on disk into the freshly baked thumbnail. Live edits are picked up in
        // update(). chunkSizeX cached for paintColumn (no per-edit lookups).
        this.markerR = toByte(EDIT_MARKER_COLOR.r, manualSrgb) & 0xFF;
        this.markerG = toByte(EDIT_MARKER_COLOR.g, manualSrgb) & 0xFF;
        this.markerB = toByte(EDIT_MARKER_COLOR.b, manualSrgb) & 0xFF;
        this.chunkSizeX = BlocksConfig.getInstance().getChunkSize().x;
        this.editOverlay = config.getWorldEditOverlay();
        refreshEditMarkers();

        // Everything the popup needs is built HERE, at world load, rather than on the click that opens it :
        // the widget tree (Lemur containers, nine-patch plates, their textures) now, and the big terrain bake
        // on a background thread. Until that bake lands the popup shows the thumbnail texture (blurry, but
        // instant) ; installPopupTexture() swaps in the sharp one as soon as it is ready.
        createPopup();
        startPopupBake();

        if (app.getStateManager().getState(ScreenState.class) != null) {
            app.getStateManager().getState(ScreenState.class).register(this);
        }
    }

    @Override
    protected void cleanup(Application application) {
        // Drop a bake still running (world switch) : its texels belong to the world being left behind.
        bakeCancelled = true;
        pendingPopupTexels = null;
        if (app.getStateManager().getState(ScreenState.class) != null) {
            app.getStateManager().getState(ScreenState.class).unregister(this);
        }
    }

    /**
     * Captures what {@link #bakeTexture} reads from the rest of the app, on the render thread : the
     * {@link FarTerrainState} heightmap (reused as-is — no second, costly sampling of the generator) and the
     * generator itself as a fallback. Kept in fields so the background popup bake never touches the state
     * manager. Called at init, after FarTerrainState has replayed the persisted player edits onto its
     * heightmap (it is attached, hence initialised, before us — see IalonInitializer.attachWorldStates).
     */
    private void captureTerrainSource() {
        FarTerrainState far = app.getStateManager().getState(FarTerrainState.class);
        float[] heights = far != null ? far.getHeightmap() : null;
        int hmSize = heights != null ? (int) Math.round(Math.sqrt(heights.length)) : 0;
        // sqrt-rounding may overshoot on a non-square length : keep hmSize*hmSize within the array bounds
        // so the bilinear sampler's max index (hmSize*hmSize - 1) can never overrun heights.
        if (hmSize > 0 && hmSize * hmSize > heights.length) {
            hmSize--;
        }
        this.bakeHeights = heights;
        this.bakeHmSize = hmSize;
        this.bakeHmStep = far != null ? far.getStep() : 0f;
        this.bakeGenerator = config.getTerrainGenerator();
    }

    /**
     * Bakes the big popup texture in the background, at world load, so opening the popup costs nothing.
     * The bake is pure arithmetic over the captured heightmap and the (read-only, thread-safe) noise fields
     * — no jME scene, no GL — so it is safe off the render thread ; only the {@link Texture2D} it feeds is
     * built back on the render thread, by {@link #installPopupTexture}. Runs at minimum priority so it never
     * competes with chunk generation, which is what the player is actually waiting for while loading.
     */
    private void startPopupBake() {
        Thread bake = new Thread(() -> {
            long start = System.nanoTime();
            ByteBuffer texels = bakeTexels(POPUP_TEX_SIZE);
            if (bakeCancelled) {
                return;
            }
            pendingPopupTexels = texels;
            popupBakeDone = true;
            log.info("Minimap popup texture baked ({}^2) in {} ms",
                    POPUP_TEX_SIZE, (System.nanoTime() - start) / 1_000_000);
        }, "minimap-bake");
        bake.setDaemon(true);
        bake.setPriority(Thread.MIN_PRIORITY);
        bake.start();
    }

    /**
     * Installs the background bake into the popup, once (render thread) : wraps the texels in a texture,
     * replays the edit tints already shown on the thumbnail, and swaps it under the popup's map quad in
     * place of the thumbnail placeholder. A no-op until the bake lands, and after it has been installed.
     */
    private void installPopupTexture() {
        if (!popupBakeDone || popupMapTex != null) {
            return;
        }
        popupBakeDone = false; // consume the hand-over, whatever it holds : a failed bake must not retry
        ByteBuffer texels = pendingPopupTexels;
        pendingPopupTexels = null;
        if (texels == null) {
            return; // no terrain source : the popup keeps the thumbnail texture
        }
        popupMapTex = toTexture(texels, POPUP_TEX_SIZE);
        for (long key : markedColumns) {
            paintColumn(popupMapTex, POPUP_TEX_SIZE, key);
        }
        Geometry bigMap = minimapPopup == null ? null : (Geometry) minimapPopup.getChild("MinimapPopupMap");
        if (bigMap != null) {
            bigMap.getMaterial().setTexture("ColorMap", popupMapTex);
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
        if (overlay == null) {
            return;
        }
        // Hand-over point of the background popup bake started at world load : one flag test per frame.
        installPopupTexture();

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

        // View heading : camera forward projected on XZ (u along +X, v along +Z). The triangle points +Y by
        // default ; this rotation turns +Y onto the view direction so the tip points where the camera looks.
        Vector3f dir = app.getCamera().getDirection(tmpDir);
        float angle = FastMath.atan2(dir.x, dir.z);
        tmpRot.fromAngleAxis(-angle, Vector3f.UNIT_Z);

        // Position dot : white, with a sinusoidal alpha pulse (period DOT_PULSE_PERIOD), so it gently
        // blinks. alpha = (1 + sin) / 2 sweeps the full [0,1] range.
        pulseTime += tpf;
        tmpDotColor.a = 0.5f + 0.5f * FastMath.sin(FastMath.TWO_PI * pulseTime / DOT_PULSE_PERIOD);

        // Thumbnail : triangle at the player's position pointing to the view heading, white dot just over
        // it at the same (exact) position, both rewritten into the merged overlay (see writeOverlay).
        float px = border + u * mapSize;
        float py = border + v * mapSize;
        writeOverlay(px, py, -angle, tmpDotColor);

        // Same on the large popup while it is open.
        if (popupMarker != null && minimapPopup != null && minimapPopup.getParent() != null) {
            popupDotMat.setColor("Color", tmpDotColor);
            float ppx = popupMapLeft + u * popupMapSide;
            float ppy = popupMapBottom + v * popupMapSide;
            popupMarker.setLocalTranslation(ppx, ppy, 2f);
            popupMarker.setLocalRotation(tmpRot);
            popupDot.setLocalTranslation(ppx, ppy, 2.1f);

            // Title : the live player position, on the popup's signboard. No placement to redo — the plate
            // is a fixed size and the text is centre-aligned inside it, so a new reading cannot shift it.
            if (popupTitle != null) {
                popupTitle.setText(formatPosition(p));
            }
        }
    }

    /** Formats the player's world position as a one-line title, e.g. {@code "X 123   Y 64   Z -45"}. */
    private static String formatPosition(Vector3f p) {
        return String.format(Locale.ENGLISH, "X: %d   Y: %d   Z: %d",
                Math.round(p.x), Math.round(p.y), Math.round(p.z));
    }

    /**
     * The title to build the signboard with. Taken from the current position rather than left blank, so the
     * plate is never shown empty for the frame between opening the popup and the first {@link #update}.
     */
    private String popupTitleText() {
        Vector3f p = config.getPlayerLocation();
        return p == null ? "Map" : formatPosition(p);
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
            // The chrome is a sibling of the popup, so it can only be placed once the popup is attached.
            // It hands back the title label, which update() then fills with the live player position.
            popupTitle = IalonTheme.applyChromeOver(minimapPopup, app.getAssetManager(),
                    app.getCamera().getWidth(), app.getCamera().getHeight(), popupTitleText(),
                    IalonTheme.Ornament.COMPASS_ROSE);
            setPlayerTouchEnabled(false);
        }
    }

    /** Closes the large minimap popup (no-op if not open) and re-enables player touch input. */
    private void hidePopup() {
        if (minimapPopup != null && minimapPopup.getParent() != null) {
            IalonTheme.removeChromeFrom((Node) minimapPopup.getParent());
            minimapPopup.removeFromParent();
            setPlayerTouchEnabled(true);
        }
    }

    @Override
    public boolean isPopupOpen() {
        return minimapPopup != null && minimapPopup.getParent() != null;
    }

    /**
     * Full screen, opaque veil : nothing of the world shows through, so it need not be drawn. The map
     * itself is a baked texture, not a live render of the scene.
     */
    @Override
    public boolean hidesWorld() {
        return true;
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
     * {@link #update}, and a "Close" button in the top-right corner. Built once, at world load (so the click
     * that opens the popup only has to attach it) ; {@link #showPopup} still guards, in case it isn't.
     */
    private void createPopup() {
        int width = app.getCamera().getWidth();
        int height = app.getCamera().getHeight();

        minimapPopup = new Container(IALON_STYLE);
        minimapPopup.setName("minimapPopup");
        minimapPopup.setLocalTranslation(0, height, 100);
        minimapPopup.setPreferredSize(new Vector3f(width, height, 0));
        IalonTheme.applyVeil(minimapPopup, app.getAssetManager(), config);
        // Clicking the backdrop (beside the enlarged map) closes the popup ; the click is consumed either
        // way so it doesn't reach the game.
        minimapPopup.addMouseListener(new CloseOnClickListener());

        // Large map on a higher-resolution bake (so it isn't blurry when enlarged). That bake runs in the
        // background from world load, so at this point it is usually not ready yet : start on the thumbnail
        // texture, which installPopupTexture() replaces as soon as the sharp one lands (and which stays for
        // good if the bake found no terrain source). A unit quad scaled in layoutPopup() so resolution
        // changes don't rebuild the mesh. Clicks on the map itself are consumed (so they don't bubble up to
        // the backdrop and close the popup).
        Material mapMat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        mapMat.setTexture("ColorMap", popupMapTex != null ? popupMapTex : mapTex);
        Geometry bigMap = new Geometry("MinimapPopupMap", new Quad(1, 1));
        bigMap.setMaterial(mapMat);
        MouseEventControl.addListenersToSpatial(bigMap, new IgnoreMouseClickListener());
        minimapPopup.attachChild(bigMap);

        // Close button, top-right corner, level with where popup titles sit.
        popupClose = IalonTheme.cornerButton("Close", app.getAssetManager(), height);
        popupClose.addClickCommands(source -> hidePopup());
        minimapPopup.attachChild(popupClose);

        // The title is not built here : it is the signboard the chrome carries, and showPopup /
        // layoutPopup take the label back from IalonTheme.applyChromeOver.

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

        // Centred in the band left BELOW the title signboard, not on the screen : the signboard belongs to
        // the chrome, which is drawn over the popup, so a map centred on the screen had its top edge
        // covered by the plate.
        float titleOffset = IalonTheme.contentTopOffset(height);
        float bandHeight = height - titleOffset;
        popupMapSide = Math.min(width, bandHeight) * POPUP_MAP_RATIO;
        popupMapLeft = width * 0.5f - popupMapSide * 0.5f;
        popupMapBottom = -titleOffset - bandHeight * 0.5f - popupMapSide * 0.5f;

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
        popupMarker = createPositionMarker(popupMapSide * POPUP_MARKER_RATIO);
        popupDot = createDot(popupMapSide * POPUP_MARKER_RATIO * 0.1f);
        popupDotMat = popupMarker.getMaterial();
        // They sit over the map : consume their clicks too so they don't bubble up and close the popup.
        MouseEventControl.addListenersToSpatial(popupMarker, new IgnoreMouseClickListener());
        MouseEventControl.addListenersToSpatial(popupDot, new IgnoreMouseClickListener());
        minimapPopup.attachChild(popupMarker);
        minimapPopup.attachChild(popupDot);

        // The chrome is rebuilt, not just repositioned : a nine-patch bakes its scale into its mesh. That
        // discards the previous title label with it, hence taking the new one back here — otherwise
        // update() would keep writing the position into a label no longer on screen.
        popupTitle = IalonTheme.applyChromeOver(minimapPopup, app.getAssetManager(), width, height,
                popupTitleText(), IalonTheme.Ornament.COMPASS_ROSE);
        IalonTheme.restyleCornerButton(popupClose, app.getAssetManager(), height);
        float margin = UiHelper.screenMargin(height);
        popupClose.setLocalTranslation(width - margin - popupClose.getPreferredSize().x, -margin, 10);
    }

    /**
     * Builds the player-position marker : a <b>filled white</b> isosceles triangle with a <b>30° apex
     * angle</b>, pointing +Y by default and centred on its centroid (so {@link #update} places it directly
     * at the player's map coordinate and rotates it about that point to the view heading). Alpha-blended :
     * its alpha is pulsed sinusoidally each frame in {@link #update} ; face culling off so winding never
     * hides it. {@code size} is the apex-to-base height, clamped to at least 4 px.
     */
    // Vertex layout of the merged overlay mesh, in DRAW order -- with alpha blending the index order IS
    // the paint order, so the ring goes down first, the heading triangle over it, the dot last on top.
    private static final int RING_VERTS = 16;   // 4 border strips x 4 corners
    private static final int MARKER_VERTS = 3;
    private static final int DOT_VERTS = 4;
    private static final int OVERLAY_VERTS = RING_VERTS + MARKER_VERTS + DOT_VERTS;

    /**
     * Builds the merged overlay geometry : frame ring, heading triangle and position dot in one
     * vertex-coloured mesh. Positions and colours are (re)written every frame by {@link #writeOverlay} ;
     * the index buffer is fixed, so it is built once here.
     */
    private Geometry createOverlay() {
        markerHeight = Math.max(4f, mapSize * MARKER_RATIO);
        dotHalf = Math.max(2f, mapSize * MARKER_RATIO * 0.1f) * 0.5f;
        frameColor.set(UiHelper.overlayColor(new ColorRGBA(0f, 0f, 0f, 0.6f), config));

        overlayPos = BufferUtils.createFloatBuffer(OVERLAY_VERTS * 3);
        overlayCol = BufferUtils.createFloatBuffer(OVERLAY_VERTS * 4);

        short[] indices = new short[4 * 6 + 3 + 6];
        int at = 0;
        for (int quad = 0; quad < 5; quad++) { // 4 ring strips, then the dot -- same winding
            int base = quad < 4 ? quad * 4 : RING_VERTS + MARKER_VERTS;
            if (quad == 4) {
                // the marker triangle sits between the ring and the dot in the buffer
                indices[at++] = (short) RING_VERTS;
                indices[at++] = (short) (RING_VERTS + 1);
                indices[at++] = (short) (RING_VERTS + 2);
            }
            indices[at++] = (short) base;
            indices[at++] = (short) (base + 1);
            indices[at++] = (short) (base + 2);
            indices[at++] = (short) base;
            indices[at++] = (short) (base + 2);
            indices[at++] = (short) (base + 3);
        }

        Mesh mesh = new Mesh();
        mesh.setBuffer(VertexBuffer.Type.Position, 3, overlayPos);
        mesh.setBuffer(VertexBuffer.Type.Color, 4, overlayCol);
        mesh.setBuffer(VertexBuffer.Type.Index, 3, indices);
        mesh.setDynamic();

        Material mat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setBoolean("VertexColor", true);
        mat.setColor("Color", ColorRGBA.White);
        mat.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        // Face culling off, as the standalone marker had : the triangle is rewritten with a heading-
        // dependent winding, so a culled back face would make it vanish for half the compass.
        mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);

        overlay = new Geometry("MinimapOverlay", mesh);
        overlay.setMaterial(mat);
        overlay.setLocalTranslation(0, 0, 1); // in FRONT of the opaque map (Gui bucket sorts on geometry z)
        // Assigned before the first write : writeOverlay reads the field back to update the buffers.
        writeOverlay(border + mapSize * 0.5f, border + mapSize * 0.5f, 0f, ColorRGBA.White);
        return overlay;
    }

    /**
     * Rewrites the overlay's vertices : the (static) frame ring, then the heading triangle rotated by
     * {@code angle} about the player's map position, then the dot over it. Called every frame — 23
     * vertices of plain arithmetic, no allocation.
     */
    private void writeOverlay(float px, float py, float angle, ColorRGBA markerColor) {
        overlayPos.clear();
        overlayCol.clear();

        // Frame ring : the border strips left visible around the inset map. Drawn in front of the map
        // rather than behind it, which is identical on screen since the map is opaque.
        float outer = buttonSize;
        float in0 = border;
        float in1 = border + mapSize;
        putQuad(0, 0, outer, in0);            // bottom
        putQuad(0, in1, outer, outer);        // top
        putQuad(0, in0, in0, in1);            // left
        putQuad(in1, in0, outer, in1);        // right
        for (int i = 0; i < RING_VERTS; i++) {
            putColor(frameColor);
        }

        // Heading triangle : apex angle 30 degrees, centred on its centroid so it rotates about the
        // player's exact position. Same shape as createPositionMarker, rotated here on the CPU instead
        // of by a geometry transform (three points -- cheaper than the draw call it saves).
        float h = markerHeight;
        float halfBase = h * FastMath.tan(15f * FastMath.DEG_TO_RAD);
        float cy = h / 3f;
        float cos = FastMath.cos(angle);
        float sin = FastMath.sin(angle);
        putRotated(0f, h - cy, cos, sin, px, py);
        putRotated(-halfBase, -cy, cos, sin, px, py);
        putRotated(halfBase, -cy, cos, sin, px, py);
        for (int i = 0; i < MARKER_VERTS; i++) {
            putColor(markerColor);
        }

        // Position dot : a small solid white square centred on the same point, painted last.
        putQuad(px - dotHalf, py - dotHalf, px + dotHalf, py + dotHalf);
        for (int i = 0; i < DOT_VERTS; i++) {
            putColor(ColorRGBA.White);
        }

        overlayPos.position(0);
        overlayCol.position(0);
        Mesh mesh = overlay.getMesh();
        mesh.getBuffer(VertexBuffer.Type.Position).updateData(overlayPos);
        mesh.getBuffer(VertexBuffer.Type.Color).updateData(overlayCol);
        // updateModelBound(), not mesh.updateBound() : the geometry's transform never changes, so nothing
        // else would ever flag its world bound dirty and it would keep the bound of the very first frame.
        overlay.updateModelBound();
    }

    /** Appends a quad's four corners, counter-clockwise from the bottom-left. */
    private void putQuad(float x0, float y0, float x1, float y1) {
        overlayPos.put(x0).put(y0).put(0f);
        overlayPos.put(x1).put(y0).put(0f);
        overlayPos.put(x1).put(y1).put(0f);
        overlayPos.put(x0).put(y1).put(0f);
    }

    /** Appends one point of the heading triangle, rotated about the origin then moved to (px, py). */
    private void putRotated(float x, float y, float cos, float sin, float px, float py) {
        overlayPos.put(px + x * cos - y * sin).put(py + x * sin + y * cos).put(0f);
    }

    private void putColor(ColorRGBA c) {
        overlayCol.put(c.r).put(c.g).put(c.b).put(c.a);
    }

    private Geometry createPositionMarker(float size) {
        float h = Math.max(4f, size);
        // Apex angle 30° -> half-angle 15° : the base half-width is h * tan(15°).
        float halfBase = h * FastMath.tan(15f * FastMath.DEG_TO_RAD);
        float cy = h / 3f; // centroid height above the base : subtract it so the origin is the centroid

        float[] verts = {
                0f, h - cy, 0f,     // tip (apex)
                -halfBase, -cy, 0f, // base-left
                halfBase, -cy, 0f   // base-right
        };
        Mesh tri = new Mesh();
        tri.setBuffer(VertexBuffer.Type.Position, 3, verts);
        tri.setBuffer(VertexBuffer.Type.Index, 3, new short[]{0, 1, 2});
        tri.updateBound();
        Material mat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", new ColorRGBA(1f, 1f, 1f, 1f));
        mat.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        Geometry geom = new Geometry("MinimapMarker", tri);
        geom.setMaterial(mat);
        return geom;
    }

    /**
     * Builds the exact-position dot : a small <b>solid white</b> square centred on the node origin (so
     * {@link #update} places it directly at the player's map coordinate, over the heading triangle). The
     * diameter is clamped to at least 2 px.
     */
    private Node createDot(float diameter) {
        float d = Math.max(2f, diameter);
        Material mat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", new ColorRGBA(1f, 1f, 1f, 1f));
        Geometry quad = new Geometry("MinimapDot", new Quad(d, d));
        quad.setMaterial(mat);
        quad.setLocalTranslation(-d * 0.5f, -d * 0.5f, 0f); // centre the quad on the node origin
        Node node = new Node("MinimapDotNode");
        node.attachChild(quad);
        return node;
    }

    /**
     * Bakes the top-down terrain texture once, on the calling thread. Convenience wrapper : {@link
     * #bakeTexels} then {@link #toTexture}. Returns {@code null} if no terrain source is available.
     */
    private Texture2D bakeTexture(int texSize) {
        ByteBuffer data = bakeTexels(texSize);
        return data == null ? null : toTexture(data, texSize);
    }

    /**
     * Bakes the top-down terrain texels. Reuses the {@link FarTerrainState} heightmap captured by
     * {@link #captureTerrainSource} (no second generator sampling) ; otherwise samples the generator
     * directly over the shown extent. Each texel is coloured by altitude with the same palette as
     * {@code FarTerrain.frag} (terrain colour only — no lighting / fog / reflection).
     *
     * <p><b>Thread-safe</b> (no jME scene, no GL, no state-manager access ; the noise fields it reads are
     * immutable after the generator is built, like the chunk-generation pool relies on) : the big popup bake
     * runs on a background thread. Bails out early — returning {@code null} — when {@link #bakeCancelled}.
     *
     * @return the RGBA8 texels, flipped and ready to wrap, or {@code null} if there is no terrain source
     *         (or the bake was cancelled)
     */
    private ByteBuffer bakeTexels(int texSize) {
        float[] heights = bakeHeights;
        int hmSize = bakeHmSize;
        float hmStep = bakeHmStep;
        TerrainGenerator generator = bakeGenerator;
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
        // Per-column rock & snow lines (the generator warps them by noise and climate, see
        // NoiseTerrainGenerator.strataLinesAt), so the map's grey/white caps sit where the world's do.
        // The slope term is left out : a cliff is far below one texel here.
        Vector2f strataLines = new Vector2f();

        // Android (no hardware sRGB framebuffer : manualGammaEncode) does not sRGB-encode on output, and the
        // plain Unshaded GUI shader doesn't emulate it (unlike the world shaders' MANUAL_SRGB) ; so the linear
        // palette would display far too dark. Pre-encode the texels to sRGB in that case (manualSrgb, cached
        // at init). On desktop the hardware framebuffer does the encode, so we keep the texels linear.
        // (See memory: srgb-color-pipeline.)
        ByteBuffer data = BufferUtils.createByteBuffer(texSize * texSize * 4);
        Vector3f sample = new Vector3f();
        ColorRGBA c = new ColorRGBA();
        for (int j = 0; j < texSize; j++) {
            if (bakeCancelled) {
                return null; // world switched while baking : the texels are already worthless
            }
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
                ColorRGBA grassColor = grass;
                float rockLine = rockHeight;
                float snowLine = snowHeight;
                if (noise != null) {
                    grassColor = noise.biomeColorAt(worldX, worldZ, biomeSample, biomeGrass);
                    noise.strataLinesAt(worldX, worldZ, biomeSample, strataLines);
                    snowLine = strataLines.x;
                    rockLine = strataLines.y;
                }
                colorAt(c, height, waterHeight, rockLine, snowLine, sand, grassColor, rock, snow, water);
                data.put(toByte(c.r, manualSrgb));
                data.put(toByte(c.g, manualSrgb));
                data.put(toByte(c.b, manualSrgb));
                data.put((byte) 0xFF);
            }
        }
        data.flip();
        return data;
    }

    /**
     * Wraps baked texels in the minimap's texture. Render thread (it is handed straight to a material).
     *
     * <p>The image is flagged Linear so GL never decodes it on sample : the bytes are already what we want on
     * the wire. Desktop : linear bytes + the hardware sRGB framebuffer encodes on output. Android : bytes
     * pre-encoded to sRGB by {@link #bakeTexels} + no framebuffer encode. Both paths display the same shade
     * as the far terrain. (See memory: srgb-color-pipeline.)
     */
    private static Texture2D toTexture(ByteBuffer data, int texSize) {
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
        if (h.length == 0) {
            return 0f;
        }
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
        float h00 = at(h, y0 * size + x0);
        float h10 = at(h, y0 * size + x1);
        float h01 = at(h, y1 * size + x0);
        float h11 = at(h, y1 * size + x1);
        float top = h00 + (h10 - h00) * tx;
        float bot = h01 + (h11 - h01) * tx;
        // Heights are pre-divided by step (see FarTerrainState.sampleHeightmap) : un-scale to world Y.
        return (top + (bot - top) * ty) * step;
    }

    /** Reads {@code h[i]} with the flat index clamped to {@code [0, h.length-1]} (h is never empty here). */
    private static float at(float[] h, int i) {
        int j = i < 0 ? 0 : (i >= h.length ? h.length - 1 : i);
        return h[j];
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
