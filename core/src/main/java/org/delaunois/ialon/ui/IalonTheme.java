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

package org.delaunois.ialon.ui;

import static org.delaunois.ialon.Ialon.IALON_STYLE;

import com.jme3.asset.AssetManager;
import com.jme3.asset.TextureKey;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.texture.Texture;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Command;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.FillMode;
import com.simsilica.lemur.GuiGlobals;
import com.simsilica.lemur.HAlignment;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.Panel;
import com.simsilica.lemur.VAlignment;
import com.simsilica.lemur.component.DynamicInsetsComponent;
import com.simsilica.lemur.component.InsetsComponent;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.component.SpringGridLayout;
import com.simsilica.lemur.component.TbtQuadBackgroundComponent;
import com.simsilica.lemur.core.GuiLayout;
import com.simsilica.lemur.style.Styles;

import org.delaunois.ialon.IalonConfig;

import java.util.List;
import java.util.Locale;

/**
 * The steampunk look of the UI, in one place : the palette, the copper / brass chrome, and the
 * factories for the widgets the popups are built from.
 *
 * <h2>Chrome that follows the screen size</h2>
 *
 * The frames are Lemur {@link TbtQuadBackgroundComponent}s — nine-patches, or "three-by-three
 * quads" : the four corners keep their size while the edges stretch lengthwise and the centre
 * stretches both ways. Lemur re-applies that on every layout pass ({@code reshape(...)} calls
 * {@code TbtQuad.updateSize(w, h)}), so a frame set on a container that is already sized to the
 * screen follows the window with no further code.
 *
 * <p>What does <em>not</em> follow by itself is the thickness of the chrome : a nine-patch's
 * {@code imageScale} is baked into its mesh at construction, so its corners stay a fixed number of
 * <em>pixels</em>. The artwork is therefore authored for a reference resolution
 * ({@link #REFERENCE_HEIGHT}) and scaled by {@link #imageScale(float)}, and the components are
 * rebuilt when the resolution changes — a rare event (F2 fullscreen, window resize, rotation) that
 * already flows through {@code Ialon.reshape} → {@code ScreenState.onReshape} →
 * {@link org.delaunois.ialon.state.Resizable#onResize} → each state's {@code layout(width, height)}.
 * A state that keeps its widgets across a resize refreshes them there, with
 * {@link #restyleButton} / {@link #applyChromeOver} ; {@code ScreenState} re-runs
 * {@link #registerStyles} so widgets built <em>after</em> the resize are right too.
 *
 * <h2>Why the edges carry no ornament</h2>
 *
 * A nine-patch always <em>stretches</em> — Lemur has no tile mode — so anything drawn on an edge is
 * smeared along it. The artwork keeps its rivets inside the fixed corner zones, and the popup
 * frame's gears are pinned separately as icons. See {@code Textures/UI/README.txt}.
 *
 * @author Cedric de Launois
 */
public final class IalonTheme {

    /**
     * The screen height the nine-patch artwork is authored for. All chrome is scaled from this, so at
     * 720p the image scale is exactly 1 and the art is pixel-perfect.
     */
    public static final float REFERENCE_HEIGHT = 720f;

    /** Aged parchment : body text and button labels. */
    public static final ColorRGBA PARCHMENT = new ColorRGBA().setAsSrgb(0.93f, 0.87f, 0.74f, 1f);
    /** Polished brass : titles. */
    public static final ColorRGBA BRASS = new ColorRGBA().setAsSrgb(0.91f, 0.77f, 0.48f, 1f);
    /** Hot amber : hover / highlight. */
    public static final ColorRGBA AMBER = new ColorRGBA().setAsSrgb(1f, 0.70f, 0.28f, 1f);
    /**
     * The backdrop behind a popup : solid black, so the world does not show through at all. Opaque means
     * it needs no colour-space care — black is black in linear and in sRGB alike, and
     * {@link UiHelper#overlayColor} leaves fully opaque colours untouched, so the Android alpha
     * correction is a no-op here.
     */
    public static final ColorRGBA SOOT = new ColorRGBA(0f, 0f, 0f, 1f);
    /**
     * Sooty iron, translucent : HUD backdrops, which do let the world through. Built with
     * {@code setAsSrgb} like every other displayed colour here — a raw {@code new ColorRGBA(0.05f, ...)}
     * is a LINEAR value, which the sRGB framebuffer encodes back out as a washed-out grey around 24%,
     * not the near-black it reads like.
     */
    public static final ColorRGBA SOOT_LIGHT = new ColorRGBA().setAsSrgb(0.05f, 0.04f, 0.03f, 0.6f);
    /**
     * Fully transparent : the backdrop of a modal laid over a popup that is already opaque. Nothing to
     * hide there — the popup below IS the background — so the veil only has to exist, not to show.
     */
    public static final ColorRGBA CLEAR = new ColorRGBA(0f, 0f, 0f, 0f);

    /** Title / button font size, in vh (1 vh = 1% of the screen height). */
    public static final float FONT_VH = 4f;

    /**
     * Height of a plated text button — menu column, dialog, popup corner alike, in vh. Stated once so the
     * three read as one family, and so the label always keeps the same share of the plate : the text is
     * centred over the plate's full height, which only works while a {@link #FONT_VH} line fits inside it.
     */
    private static final float BUTTON_HEIGHT_VH = 8f;
    /** Gap above each menu-column button, in vh. */
    private static final float MENU_GAP_VH = 3f;

    /**
     * Height of a popup's title signboard, in vh. {@link Frame#TITLE}'s artwork is baked just under it
     * (68 px against the 72 px this comes to at the 720p reference), so lowering this without re-baking
     * the patch would eventually make the patch the taller of the two — which renders it inverted.
     */
    private static final float TITLE_HEIGHT_VH = 10f;
    /**
     * Minimum width of the title signboard, in vw. A floor, not a cap, exactly like
     * {@link #CORNER_BUTTON_VH} : a short title such as "Worlds" still gets a plate worth looking at,
     * and a long one widens past it (up to the room the corner buttons leave).
     */
    private static final float TITLE_WIDTH_VW = 30f;
    /** Gap below the title signboard, before a popup's first row of content, in vh. */
    private static final float TITLE_GAP_VH = 3f;
    /**
     * Width of a popup's corner header button, in vh. A floor, not a cap : it is wider than "Back" or
     * "Close" needs, so every header button comes out the same width whatever its label, with a little
     * air around the text instead of hugging it. A longer label still widens past it.
     */
    private static final float CORNER_BUTTON_VH = 20f;

    /**
     * Depth given to a frame over whatever it sits on. Lemur's own backgrounds use the same trick : with
     * a zero offset a button's plate is coplanar with the dialog plate behind it and z-fighting makes it
     * disappear entirely.
     */
    private static final float Z_OFFSET = 0.01f;

    private static final String BACKGROUND = "background";
    private static final String TEXT = "text";
    private static final String PREFERRED_SIZE = "preferredSize";

    /**
     * The nine-patch chrome. Each entry carries the slice metrics of its artwork, and a size budget :
     * the mesh is built at {@code size * imageScale}, so a frame must never back a widget narrower or
     * shorter than that, or the folds cross over and the background renders inverted (see
     * {@link #minimumSize(float)}). That is why {@link #PLATE_GOLD} and {@link #BUTTON} are not square — a
     * text button is far wider than it is tall, and its patch has to fit inside its height too.
     */
    public enum Frame {

        /** Full-screen popups. */
        POPUP("Textures/UI/frame-popup.png", 256, 256, 96, 96, 160, 160, Inset.NONE),

        /**
         * SELECTED card of either grid. Insets its content by the border width.
         *
         * <p>This plate and the two below are the same engraving in three metals, and a card's state is
         * which one it wears — there is no colour multiply on top. Their geometry is deliberately
         * identical, so a card keeps its layout whatever its state : the {@link #margin},
         * {@link #minimumSize} and {@link #horizontalChrome} a caller derives from one hold for all three.
         */
        PLATE_GOLD("Textures/UI/plate-gold.png", 128, 96, 32, 32, 96, 64, Inset.BOTH),

        /**
         * The card of the world currently loaded, while it is NOT the selected one : the same plate one
         * shade back. Gold follows the selection, so this is only ever the "you are here" marker left
         * behind once the player taps another world.
         */
        PLATE_BRONZE("Textures/UI/plate-copper.png", 128, 96, 32, 32, 96, 64, Inset.BOTH),

        /** Dialog boxes and every other card : the same plate in dull steel. */
        PLATE_SILVER("Textures/UI/plate-silver.png", 128, 96, 32, 32, 96, 64, Inset.BOTH),

        /**
         * The signboard behind a popup's title. {@link Inset#SIDES} for the same reason a button uses
         * it : reserving the border vertically as well would leave a {@link #FONT_VH} line less room
         * than it needs, and jME would stop centring it and drop it at the top of the plate instead.
         *
         * <p>Baked just under the size it is displayed at : {@link #TITLE_HEIGHT_VH} shows the plate at
         * 10 vh, which is 72 px at the 720p reference, and 68 keeps the patch below that with room to
         * spare — a patch TALLER than its widget renders inverted (see {@link #minimumSize(float)}),
         * while a shorter one merely stretches its centre band a little further. 156 then holds the
         * source artwork's aspect ratio, and the 24 px corner is what keeps the gold border and its
         * rivet out of the stretched zones.
         */
        TITLE("Textures/UI/title-plate.png", 156, 68, 48, 24, 108, 44, Inset.SIDES),

        /** Text buttons, raised. */
        BUTTON("Textures/UI/button.png", 64, 48, 16, 16, 48, 32, Inset.SIDES),

        /**
         * Text buttons, held down. Its geometry MUST match {@link #BUTTON}'s : the press only swaps the
         * texture on the mesh {@link #BUTTON} built, so different slices would misplace the corners.
         */
        BUTTON_PRESSED("Textures/UI/button-pressed.png", 64, 48, 16, 16, 48, 32, Inset.SIDES);

        /** Which sides of a frame hold their content off the artwork's border. */
        private enum Inset {
            /** None : the content fills the frame edge to edge. */
            NONE,
            /** Both axes : a dialog's or a card's content stays inside the border. */
            BOTH,
            /**
             * Sides only : a text button's label is centred over the plate's FULL height. Reserving the
             * border vertically too would leave a 4 vh label about 3.5 vh of room, and jME only centres a
             * line that fits ({@code Letters.align}, {@code totalHeight < height}) — it gives up and drops
             * the text at the top of the region instead, which reads as a label sliding off the bottom of
             * the plate. The label is far shorter than the plate, so it clears the rivets anyway.
             */
            SIDES
        }

        private final String texture;
        private final int imageWidth;
        private final int imageHeight;
        private final int x1;
        private final int y1;
        private final int x2;
        private final int y2;
        private final Inset inset;

        Frame(String texture, int imageWidth, int imageHeight,
              int x1, int y1, int x2, int y2, Inset inset) {
            this.texture = texture;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.inset = inset;
        }

        /**
         * How much room this frame keeps for its own border, in GUI pixels — the margin handed to the
         * nine-patch component.
         *
         * <p>Scaled by {@link #imageScale(float)}, like the mesh it belongs to. Lemur's
         * {@code TbtQuadBackgroundComponent.create} sets this margin from the RAW slice widths, so a frame
         * kept a border-sized hole of a fixed pixel count while the border it stood for shrank with the
         * screen : on a short window a button reserved 32 px for a 18 px border, ran out of room for its
         * label, and pushed it out of the plate.
         *
         * @param screenHeight the screen height in GUI pixels
         * @return the margin, one side each in x and y
         */
        private Vector2f margin(float screenHeight) {
            float scale = imageScale(screenHeight);
            return new Vector2f(
                    inset == Inset.NONE ? 0f : x1 * scale,
                    inset == Inset.BOTH ? y1 * scale : 0f);
        }

        /**
         * The smallest widget this frame can back at the given screen height, in GUI pixels. A widget
         * smaller than this in either axis renders inverted.
         *
         * @param screenHeight the screen height in GUI pixels
         * @return the minimum widget size
         */
        public Vector3f minimumSize(float screenHeight) {
            float scale = imageScale(screenHeight);
            return new Vector3f(imageWidth * scale, imageHeight * scale, 0);
        }

        /**
         * How much wider than its content a widget backed by this frame ends up, in GUI pixels : the
         * nine-patch margin, on both sides. Callers that must fit a frame into a given footprint (the grid
         * cards) subtract this rather than restate the number, so it cannot drift from the artwork.
         *
         * @param screenHeight the screen height in GUI pixels
         * @return the total horizontal chrome, both sides included
         */
        public float horizontalChrome(float screenHeight) {
            return 2 * margin(screenHeight).x;
        }
    }

    private IalonTheme() {
    }

    /**
     * How much to scale the chrome artwork for a screen of this height. Keeping the chrome a constant
     * fraction of the screen is the whole point : a border of a fixed pixel count looks twice as thick
     * at 720p as it does at 1440p.
     *
     * @param screenHeight the screen height in GUI pixels
     * @return the nine-patch image scale
     */
    public static float imageScale(float screenHeight) {
        return screenHeight / REFERENCE_HEIGHT;
    }

    /**
     * Builds a fresh nine-patch component for the given chrome.
     *
     * <p>Always returns a NEW instance : a {@link TbtQuadBackgroundComponent} owns its mesh, so sharing
     * one between panels makes them fight over its size. (Components handed to Lemur through a style
     * selector are safe — {@code Styles.applyStyles} clones them per widget.)
     *
     * <p>This deliberately avoids {@code TbtQuadBackgroundComponent.create(String, ...)}, which loads
     * its texture with mipmaps disabled : the same frame is used at image scales below 1 (small
     * dialogs, low resolutions) and its edges would shimmer without a mip chain.
     *
     * @param assetManager the asset manager
     * @param frame        which chrome
     * @param screenHeight the screen height in GUI pixels
     * @return a new nine-patch background component
     */
    public static TbtQuadBackgroundComponent frame(AssetManager assetManager, Frame frame, float screenHeight) {
        TbtQuadBackgroundComponent component = TbtQuadBackgroundComponent.create(
                texture(assetManager, frame), imageScale(screenHeight),
                frame.x1, frame.y1, frame.x2, frame.y2, Z_OFFSET, false);
        // Always overrides what create() set : it takes the margin from the raw slice widths, which does
        // not follow the screen, and it insets both axes even where only the sides should be (see
        // Frame.margin). A full-screen popup gets no margin at all — its content already keeps a screen
        // margin of its own and its veil must reach the screen edges.
        component.setMargin(frame.margin(screenHeight));
        // Clear AlphaDiscardThreshold : useless here, and it forces a separate shader permutation.
        component.getMaterial().getMaterial().clearParam(UiHelper.ALPHA_DISCARD_THRESHOLD);
        return component;
    }

    /**
     * Loads a frame's artwork. Mipmapped on purpose : {@code TbtQuadBackgroundComponent.create(String,…)}
     * would load it with mips disabled, and the same frame is used at image scales below 1 (small dialogs,
     * low resolutions) where its edges would shimmer without a mip chain. The asset manager caches these,
     * so repeated calls are free.
     *
     * <p>The key is built with the ONE-ARGUMENT constructor on purpose. {@code TextureKey}'s second
     * argument is {@code flipY}, not anything to do with mips, and passing {@code false} there rendered
     * every nine-patch upside down — the popup frame's pressure gauge hung off the bottom-left corner
     * instead of the top-left one, and the plates and buttons were lit from below. jME's own
     * {@code loadTexture(String)} and Lemur's {@code GuiGlobals.loadTexture} both leave {@code flipY} at
     * its {@code true} default, so this is the only way the hand-rolled load matches the rest of the UI
     * (the ornaments and the slider parts never had the bug — they go through {@link #quad}).
     *
     * @param assetManager the asset manager
     * @param frame        which chrome
     * @return the frame's texture
     */
    private static Texture texture(AssetManager assetManager, Frame frame) {
        TextureKey key = new TextureKey(frame.texture);
        key.setGenerateMips(true);
        Texture texture = assetManager.loadTexture(key);
        texture.setMinFilter(Texture.MinFilter.Trilinear);
        texture.setMagFilter(Texture.MagFilter.Bilinear);
        return texture;
    }

    /**
     * Sets the chrome as a panel's background, for plates that have no separate veil.
     *
     * @param panel        the panel to plate
     * @param frame        which chrome
     * @param assetManager the asset manager
     * @param screenHeight the screen height in GUI pixels
     */
    public static void applyPlate(Panel panel, Frame frame, AssetManager assetManager, float screenHeight) {
        panel.setBackground(frame(assetManager, frame, screenHeight));
    }

    /**
     * The backdrop behind a full-screen popup : black, so the world is fully hidden while a menu is open,
     * with steam escaping from a leak in the frame's bottom pipe and spreading as it rises
     * ({@link SteamBackground}).
     *
     * <p>Still opaque, so {@code Popup.hidesWorld} keeps holding — the steam is drawn ON the black, never
     * through it. It is the popup's background layer, which is why it comes out under both the content and
     * the tube : the plume's base disappears behind the very pipe it escapes from.
     *
     * @param panel        the popup panel
     * @param assetManager the asset manager
     * @param config       the config, which carries the {@code manualGammaEncode} flag
     */
    public static void applyVeil(Panel panel, AssetManager assetManager, IalonConfig config) {
        panel.setBackground(new SteamBackground(assetManager, config));
    }

    /**
     * The backdrop of a modal shown on top of a popup (a confirmation dialog over the worlds grid, say) :
     * invisible, so the popup underneath stays fully visible around the dialog.
     *
     * <p>Invisible, not absent : the quad is what Lemur picks against, so it is still what keeps the popup
     * below inert — a panel with no background has no geometry, and clicks would fall straight through to
     * the grid behind. Alpha plays no part in picking (this is the same trick as the photo mode's
     * click catcher), and the dialog's own listener swallows what lands on the veil.
     *
     * @param panel  the modal overlay panel
     * @param config the config, which carries the {@code manualGammaEncode} flag
     */
    public static void applyClearVeil(Panel panel, IalonConfig config) {
        UiHelper.addBackground(panel, CLEAR, config);
    }

    /**
     * Registers the theme's Lemur selectors for the {@code ialon} style. Without them Lemur falls back
     * to its class defaults, which is why every button used to be an invisible-background label with a
     * yellow hover tint.
     *
     * <p>Called once from {@code IalonInitializer.setupGui}, and again from
     * {@code ScreenState.onReshape} so that the chrome prototypes match the new resolution.
     *
     * @param assetManager the asset manager
     * @param screenHeight the screen height in GUI pixels
     */
    public static void registerStyles(AssetManager assetManager, float screenHeight) {
        Styles styles = GuiGlobals.getInstance().getStyles();

        styles.getSelector(Label.ELEMENT_ID, IALON_STYLE).set("color", PARCHMENT);

        styles.getSelector(Button.ELEMENT_ID, IALON_STYLE).set("color", PARCHMENT);
        styles.getSelector(Button.ELEMENT_ID, IALON_STYLE).set("highlightColor", AMBER);
        styles.getSelector(Button.ELEMENT_ID, IALON_STYLE).set("focusColor", BRASS);
        styles.getSelector(Button.ELEMENT_ID, IALON_STYLE).set("shadowColor", new ColorRGBA(0f, 0f, 0f, 0.75f));

        registerSliderStyles(styles, assetManager, screenHeight);
    }

    /**
     * The slider's rail, its filled part and its thumb. Moved here from {@code IalonInitializer} so all
     * the chrome lives together ; the three textures keep their original names and sizes, so this is the
     * same wiring, only re-pointed at the steampunk art.
     */
    private static void registerSliderStyles(Styles styles, AssetManager assetManager, float screenHeight) {
        float thumbSize = 8 * screenHeight / 100f;

        styles.getSelector(Slider.VALUE_ID, IALON_STYLE).set(BACKGROUND,
                quad(assetManager, "Textures/range-filled.png"));
        styles.getSelector(Slider.RANGE_ID, IALON_STYLE).set(BACKGROUND,
                quad(assetManager, "Textures/range.png"));

        styles.getSelector(Slider.THUMB_ID, IALON_STYLE).set(TEXT, "", false);
        styles.getSelector(Slider.THUMB_ID, IALON_STYLE).set(PREFERRED_SIZE,
                new Vector3f(thumbSize, thumbSize, 0), false);
        styles.getSelector(Slider.THUMB_ID, IALON_STYLE).set(BACKGROUND,
                quad(assetManager, "Textures/cursor.png"));

        styles.getSelector(Slider.LEFT_ID, IALON_STYLE).set(TEXT, "", false);
        styles.getSelector(Slider.RIGHT_ID, IALON_STYLE).set(TEXT, "", false);
    }

    private static QuadBackgroundComponent quad(AssetManager assetManager, String texture) {
        QuadBackgroundComponent component = new QuadBackgroundComponent(assetManager.loadTexture(texture));
        component.getMaterial().getMaterial().clearParam(UiHelper.ALPHA_DISCARD_THRESHOLD);
        return component;
    }

    // -------------------------------------------------------------------------------------------
    // Widget factories. menuButton / dialogButton / dialogTitle / dialogBox were duplicated verbatim
    // between WorldMenuState and CreationLibraryState ; they live here so the popups cannot drift.
    // -------------------------------------------------------------------------------------------

    /**
     * A right-column menu button : fixed size, brass plate, with a top inset spacing it from the button
     * above it.
     *
     * <p>The inset is added to the preferred size rather than taken out of it. Lemur applies the insets
     * layer BEFORE the background layer, so with a plain {@code 8 vh} preferred size the gap would be
     * carved out of the button itself and the plate would come out a third too short — invisible while
     * buttons had a transparent background, obvious now that they carry one.
     *
     * @param text         the label
     * @param assetManager the asset manager
     * @param screenWidth  the screen width in GUI pixels
     * @param screenHeight the screen height in GUI pixels
     * @return the button
     */
    public static Button menuButton(String text, AssetManager assetManager,
                                    float screenWidth, float screenHeight) {
        float gap = MENU_GAP_VH * screenHeight / 100f;
        Button button = plated(text, assetManager, screenHeight);
        button.setPreferredSize(new Vector3f(26 * screenWidth / 100f, menuRowHeight(screenHeight), 0));
        button.setInsetsComponent(new InsetsComponent(gap, 0, 0, 0));
        return sizeToPreferred(button);
    }

    /**
     * The vertical pitch of a menu column : one {@link #menuButton} plus the gap above it. Popups that
     * need to line their menu up with another one offset by this, rather than restating the arithmetic.
     *
     * @param screenHeight the screen height in GUI pixels
     * @return one menu row's height in GUI pixels
     */
    public static float menuRowHeight(float screenHeight) {
        return (BUTTON_HEIGHT_VH + MENU_GAP_VH) * screenHeight / 100f;
    }

    /**
     * A dialog button (Delete / Cancel / Play ...) : fixed size, brass plate, centred label.
     *
     * @param text         the label
     * @param assetManager the asset manager
     * @param screenWidth  the screen width in GUI pixels
     * @param screenHeight the screen height in GUI pixels
     * @return the button
     */
    public static Button dialogButton(String text, AssetManager assetManager,
                                      float screenWidth, float screenHeight) {
        Button button = plated(text.toUpperCase(Locale.FRENCH), assetManager, screenHeight);
        button.setPreferredSize(
                new Vector3f(25 * screenWidth / 100f, BUTTON_HEIGHT_VH * screenHeight / 100f, 0));
        return sizeToPreferred(button);
    }

    /**
     * The bottom row of a dialog : the given buttons side by side, with a gap between them so two plates
     * never touch.
     *
     * <p>The gap is an empty spacer cell rather than an inset on the buttons : Lemur applies the insets
     * layer before the background layer, so an inset is carved out of the button itself and would shrink
     * its plate instead of moving it (same trap as {@link #menuButton}).
     *
     * <p>The gap is taken out of the buttons' own widths rather than added to the row, so the row keeps
     * the total width its buttons asked for. Dialog boxes stack their rows with no fill, so a row grown
     * wider than the title above it would leave that title visibly off-centre.
     *
     * @param screenHeight the screen height in GUI pixels
     * @param buttons      the buttons, in display order
     * @return the row container
     */
    public static Container dialogButtonRow(float screenHeight, Button... buttons) {
        Container row = new Container(
                new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        float gap = MENU_GAP_VH * screenHeight / 100f;
        float taken = buttons.length > 1 ? gap * (buttons.length - 1) / buttons.length : 0;
        for (int i = 0; i < buttons.length; i++) {
            if (i > 0) {
                Panel spacer = new Panel(gap, 1, IALON_STYLE);
                spacer.setBackground(null);
                row.addChild(spacer, 0, 2 * i - 1);
            }
            Button button = buttons[i];
            Vector3f preferred = button.getPreferredSize();
            button.setPreferredSize(new Vector3f(preferred.x - taken, preferred.y, preferred.z));
            enforceMinimumSize(button, Frame.BUTTON, screenHeight);
            row.addChild(sizeToPreferred(button), 0, 2 * i);
        }
        return row;
    }

    /**
     * A header button at a popup's top corner : {@link #CORNER_BUTTON_VH} wide and as tall as any other
     * plated button, so Back and Close come out the same size whatever their label, and
     * {@link UiHelper#placeCornerButtons} can sit them flush in the corners.
     *
     * @param text         the label
     * @param assetManager the asset manager
     * @param screenHeight the screen height in GUI pixels
     * @return the button
     */
    public static Button cornerButton(String text, AssetManager assetManager, float screenHeight) {
        Button button = plated(text.toUpperCase(Locale.FRENCH), assetManager, screenHeight);
        sizeCornerButton(button, screenHeight);
        return button;
    }

    /**
     * Gives a header button the common corner-button footprint. The width is a floor rather than a fixed
     * value, so a label longer than "Close" still gets the room it needs ; the height is fixed, like every
     * other plated button's.
     */
    private static void sizeCornerButton(Button button, float screenHeight) {
        Vector3f preferred = button.getPreferredSize();
        button.setPreferredSize(new Vector3f(
                Math.max(preferred.x, CORNER_BUTTON_VH * screenHeight / 100f),
                BUTTON_HEIGHT_VH * screenHeight / 100f,
                preferred.z));
        sizeToPreferred(button);
    }

    private static Button plated(String text, AssetManager assetManager, float screenHeight) {
        Button button = new Button(text, IALON_STYLE);
        button.setTextHAlignment(HAlignment.Center);
        button.setTextVAlignment(VAlignment.Center);
        restyleButton(button, assetManager, screenHeight);
        enforceMinimumSize(button, Frame.BUTTON, screenHeight);
        return button;
    }

    /**
     * Gives a widget a size right away, instead of waiting for a parent layout to hand it one.
     *
     * <p>Needed for anything attached with {@code attachChild} rather than {@code addChild} — the corner
     * header buttons and the pinned ornaments, which are positioned by hand. Outside a layout nothing
     * ever calls {@code setSize}, the widget stays at zero, and its nine-patch background renders
     * inside-out (i.e. not at all). Widgets that ARE inside a layout simply get this overwritten by it.
     *
     * @param panel the widget to size to its preferred size
     * @param <T>   the widget type
     * @return the same widget
     */
    public static <T extends Panel> T sizeToPreferred(T panel) {
        panel.setSize(panel.getPreferredSize().clone());
        return panel;
    }

    /**
     * Raises a widget's preferred size to its frame's floor, in whichever axis falls short.
     *
     * <p>Not cosmetic : a nine-patch mesh is built at {@code image * imageScale}, and on a smaller
     * widget the two folds cross over, which renders the background inside-out — in practice the plate
     * simply vanishes. It bites any button sized by its own text rather than by the theme, so the floor is
     * applied to every plated button rather than left to each caller to remember.
     *
     * @param panel        the widget
     * @param frame        the frame backing it
     * @param screenHeight the screen height in GUI pixels
     */
    public static void enforceMinimumSize(Panel panel, Frame frame, float screenHeight) {
        Vector3f minimum = frame.minimumSize(screenHeight);
        Vector3f preferred = panel.getPreferredSize();
        panel.setPreferredSize(new Vector3f(
                Math.max(preferred.x, minimum.x),
                Math.max(preferred.y, minimum.y),
                preferred.z));
    }

    /**
     * Re-applies a button's font size, plate and press behaviour at the current screen height. Call it
     * from {@code layout(width, height)} : the plate's thickness is baked in at construction, so a
     * button that outlives a resolution change needs a new one.
     *
     * @param button       the button
     * @param assetManager the asset manager
     * @param screenHeight the screen height in GUI pixels
     */
    public static void restyleButton(Button button, AssetManager assetManager, float screenHeight) {
        button.setFontSize(FONT_VH * screenHeight / 100f);
        button.setBackground(frame(assetManager, Frame.BUTTON, screenHeight));
        installPressSwap(button, Button.ButtonAction.Down, Frame.BUTTON_PRESSED, assetManager);
        installPressSwap(button, Button.ButtonAction.Up, Frame.BUTTON, assetManager);
    }

    /**
     * Restyles a header button that a popup positions by hand, and gives it back a size.
     *
     * <p>The size step is not optional. A fresh background component only builds its geometry inside
     * {@code reshape}, and a button attached with {@code attachChild} never gets another layout pass :
     * {@code GuiControl.invalidate()} defers to the parent container, which lays out only the children it
     * was given through {@code addChild}. Without an explicit size right here, the plate this method just
     * installed would never be drawn and — worse — the button would have no geometry left to pick against,
     * so Lemur's {@code target == capture} release test would swallow every click.
     *
     * <p>The same three steps used to be repeated in each popup ; they belong together, hence one method.
     *
     * @param button       the header button
     * @param assetManager the asset manager
     * @param screenHeight the screen height in GUI pixels
     */
    public static void restyleCornerButton(Button button, AssetManager assetManager, float screenHeight) {
        restyleButton(button, assetManager, screenHeight);
        // Drop the size pinned at the previous resolution first : the steps below only ever RAISE the
        // preferred size, so without this a button that has seen a taller screen would keep that size for
        // good, and a shrinking window would leave Back and Close oversized.
        button.setPreferredSize(null);
        enforceMinimumSize(button, Frame.BUTTON, screenHeight);
        sizeCornerButton(button, screenHeight);
    }

    /**
     * Installs, once, the command that shows the pressed plate while the button is held. Lemur has no
     * "pressed background" style attribute, so the down / up commands are the only hook for it.
     *
     * <p>Deliberately NOT {@code setButtonCommands} : that clears the whole command map, which silently
     * takes the button's {@code Click} handler with it. Callers add their click command after building the
     * button and then {@code layout(width, height)} restyles it, so wiping the map here left every Back /
     * Close button looking perfect and doing nothing.
     *
     * <p>And deliberately not a bare {@code addCommands} either : that appends, so every resize would
     * stack another swap on the same button. The command carries no resolution, so installing it once is
     * enough — see {@link PlateSwap}.
     */
    private static void installPressSwap(Button button, Button.ButtonAction action, Frame frame,
                                         AssetManager assetManager) {
        List<Command<? super Button>> existing = button.getCommands(action);
        if (existing != null) {
            for (Command<? super Button> command : existing) {
                if (command instanceof PlateSwap) {
                    return;
                }
            }
        }
        button.addCommands(action, new PlateSwap(frame, assetManager));
    }

    /**
     * Shows a frame's artwork on the button's existing plate, by swapping the TEXTURE on the component
     * already in place.
     *
     * <p>Never {@code setBackground(...)} with a fresh component : that detaches the current background
     * geometry, and the replacement only builds its own inside {@code reshape}, on the next layout pass.
     * A button attached with {@code attachChild} never gets one — {@code GuiControl.invalidate()} defers
     * to the parent container, which does not lay out children it was not given through {@code addChild}.
     * So the plate vanished on press, and with the pick geometry gone Lemur's {@code target == capture}
     * test failed on release, swallowing the click. Swapping the texture touches neither the mesh nor the
     * geometry, and costs nothing per click (the asset manager caches both textures).
     *
     * <p>Resolution-independent by construction : the scale lives in the mesh, which this never touches.
     */
    private static final class PlateSwap implements Command<Button> {

        private final Frame frame;
        private final AssetManager assetManager;

        PlateSwap(Frame frame, AssetManager assetManager) {
            this.frame = frame;
            this.assetManager = assetManager;
        }

        @Override
        public void execute(Button source) {
            if (source.getBackground() instanceof TbtQuadBackgroundComponent) {
                ((TbtQuadBackgroundComponent) source.getBackground()).setTexture(texture(assetManager, frame));
            }
        }
    }

    /** Name of the chrome node, so re-applying it replaces rather than piles up. */
    private static final String CHROME_NAME = "theme-popup-chrome";

    /**
     * Depth of the chrome node. The popups sit at z 100 and their content reaches ~110, so this clears
     * everything they hold.
     */
    private static final float CHROME_Z = 140f;

    /**
     * Depth of the debug overlays (the stats readouts and the {@code IalonDebugState} panel), which are
     * diagnostics and must stay legible whatever is open : a popup sits at z 100, its chrome at
     * {@link #CHROME_Z}, and a modal laid over a popup — a confirmation dialog — is a child of that popup
     * at a further +100, so it reaches ~210. This clears the lot with room to spare.
     *
     * <p>Safe to lay over an interactive popup : a Lemur collision whose hierarchy holds no mouse
     * listener is skipped by {@code PickEventSession}, and the debug overlays register none, so clicks
     * still fall through to the popup below.
     */
    public static final float DEBUG_OVERLAY_Z = 1000f;

    /**
     * The icon's diameter at scale 1, counted in frame corner zones (1 would make it exactly the tube's
     * bend). An ornament's own {@link Ornament#scale} multiplies this.
     */
    private static final float ORNAMENT_CORNER_ZONES = 1.5f;

    /**
     * Depth of an ornament inside the chrome node, for the usual case : over the frame tube. The frame
     * panel sits at 0 and the title plate at 5.
     */
    public static final float ORNAMENT_OVER_FRAME_Z = 5f;

    /**
     * Depth of an ornament that must read as being BEHIND the tube — a cog half swallowed by the frame,
     * say. Still well over the popup's own content : the chrome node is at {@link #CHROME_Z} = 140 and
     * the content below reaches ~110, so anything above -25 here stays over it.
     */
    public static final float ORNAMENT_UNDER_FRAME_Z = -5f;

    /**
     * Depth of an ornament that must read as being BEHIND the popup widgets (buttons, plates...)
     */
    public static final float ORNAMENT_UNDER_WIDGETS_Z = -40f;

    /**
     * An icon pinned somewhere on a popup's frame. Deliberately NOT drawn into the frame artwork : a
     * nine-patch stretches its edges, so anything painted there is smeared the length of the screen (see
     * {@link #applyCornerOrnament}).
     *
     * <p>Each constant states where it sits ({@link #x} / {@link #y}, as a fraction of the popup), how
     * big it is ({@link #scale}) and how deep ({@link #z}) — so a new ornament is one line here and needs
     * no change to the placement code.
     *
     * <p>{@link #GEAR} is the house ornament, worn by every popup unless it has something better to say ;
     * {@link #COMPASS_ROSE} belongs to the map, where a compass reads as part of the instrument rather
     * than as decoration.
     */
    public enum Ornament {

        /**
         * The brass cog : the default popup ornament. Centred almost exactly on the bottom-right corner, so
         * a third of it hangs past the tube and it reads as a cog turning behind the frame rather than as a
         * sticker on it — and the part that is cut off is exactly the part a viewer completes for free, a
         * wheel being round.
         */
        GEAR("Textures/UI/ornament-gear.png", 0.95f, 0.9f, 1f, ORNAMENT_UNDER_FRAME_Z),

        /**
         * An engraved compass rose, for the map popup. Tucked WHOLE inside the frame, unlike the cog : a
         * compass is an instrument to be read, and half a rose with its north point off the screen reads as
         * a clipping bug rather than as a flourish.
         */
        COMPASS_ROSE("Textures/UI/rose.png", 0.927f, 0.870f, 2f, ORNAMENT_OVER_FRAME_Z);

        private final String texture;

        /**
         * Where the icon's CENTRE sits horizontally, as a fraction of the popup's width : 0 is the left
         * edge, 1 the right one, 0.5 the middle. Values outside 0..1 push it off the popup altogether.
         *
         * <p>A fraction and not a pixel count so the icon holds its place at every resolution. The flip
         * side, for a corner ornament : how far it hangs past the tube is a fraction of the popup's
         * <em>width</em> while the icon is sized off its <em>height</em>, so the overlap breathes a little
         * with the aspect ratio. Tuned at 16:9, which is what a phone in landscape gives us.
         */
        private final float x;

        /** Same, vertically : 0 is the top edge of the popup, 1 its bottom edge. */
        private final float y;

        /**
         * Size multiplier over the default {@link #ORNAMENT_CORNER_ZONES} diameter. 1 keeps the icon
         * proportional to the tube's bend at every resolution ; 2 makes it twice that.
         */
        private final float scale;

        /**
         * Depth inside the chrome node : {@link #ORNAMENT_OVER_FRAME_Z} to lay the icon over the tube,
         * {@link #ORNAMENT_UNDER_FRAME_Z} to slip it behind (the frame panel is at 0). Whatever the value,
         * the whole chrome is drawn over the popup's content.
         */
        private final float z;

        Ornament(String texture, float x, float y, float scale, float z) {
            this.texture = texture;
            this.x = x;
            this.y = y;
            this.scale = scale;
            this.z = z;
        }
    }

    /**
     * Lays a popup's chrome — frame, title signboard and corner cog — OVER its content, so a scrolling
     * grid slides under the tube instead of across it.
     *
     * <p>The title lives here, and not in each popup's own content, because this is the one method every
     * popup already calls both when it opens and on every resize : the signboard then follows the
     * resolution and is rebuilt at the new {@code imageScale} with the rest of the chrome, is drawn over
     * the content like the tube, and is taken down with it — none of which needs a line of code in the
     * popups. What they owe in return is a top margin of {@link #contentTopOffset(float)}, since the
     * plate is painted over whatever is up there.
     *
     * <p>The chrome is attached to the popup's <b>parent</b>, as a sibling drawn after it, and not as a
     * child. That is a picking constraint, not a style choice : Lemur's {@code PickEventSession} skips a
     * collision whose hierarchy holds no listener, but it finds one by walking <em>up</em> the parents. A
     * full-popup panel placed inside the popup would therefore resolve every tap to the popup's own
     * scroll catcher — which consumes it — and no card could ever be selected again. As a sibling of the
     * popup its walk-up reaches the GUI node and finds nothing, so the tap falls through to the card.
     *
     * <p>Idempotent : replaces the chrome already in place, so it can be called from
     * {@code layout(width, height)} on every resize. Does nothing while the popup is detached — call it
     * again once the popup is shown.
     *
     * @param popup        the popup node, already attached where it is displayed
     * @param assetManager the asset manager
     * @param width        the popup width in GUI pixels
     * @param height       the popup height in GUI pixels
     * @param title        the popup title, shown on the signboard ; {@code null} or blank for none
     * @return the title label, so a caller that keeps its title live can set its text ({@code null}
     *         when there is no title, or when the popup is detached and no chrome was laid)
     */
    public static Label applyChromeOver(Node popup, AssetManager assetManager, float width, float height,
                                        String title) {
        return applyChromeOver(popup, assetManager, width, height, title, Ornament.GEAR);
    }

    /**
     * Lays a popup's chrome, with a chosen corner ornament instead of the default cog.
     *
     * <p>Same contract as {@link #applyChromeOver(Node, AssetManager, float, float, String)} in every
     * other respect — see it for the whole story.
     *
     * @param popup        the popup node, already attached where it is displayed
     * @param assetManager the asset manager
     * @param width        the popup width in GUI pixels
     * @param height       the popup height in GUI pixels
     * @param title        the popup title, shown on the signboard ; {@code null} or blank for none
     * @param ornament     the icon pinned in the bottom-right corner
     * @return the title label, or {@code null} (see the other overload)
     */
    public static Label applyChromeOver(Node popup, AssetManager assetManager, float width, float height,
                                        String title, Ornament ornament) {
        Node parent = popup.getParent();
        if (parent == null) {
            return null;
        }
        removeChromeFrom(parent);

        Node chrome = new Node(CHROME_NAME);
        // Same local convention as a popup : origin top-left, +x right, -y down.
        chrome.setLocalTranslation(0, height, CHROME_Z);

        Panel framePanel = new Panel(width, height, IALON_STYLE);
        framePanel.setBackground(frame(assetManager, Frame.POPUP, height));
        chrome.attachChild(framePanel);
        sizeToPreferred(framePanel);

        Label titleLabel = applyTitlePlate(chrome, assetManager, width, height, title);
        applyCornerOrnament(chrome, assetManager, width, height, ornament);
        parent.attachChild(chrome);
        return titleLabel;
    }

    /**
     * Pins the title signboard at the top centre of the frame.
     *
     * <p>Its top sits at the screen margin, the same one {@link UiHelper#placeCornerButtons} uses, so the
     * signboard and the Back / Close buttons share a top edge whatever the resolution.
     *
     * @return the title label, or {@code null} if there is no title to show
     */
    private static Label applyTitlePlate(Node chrome, AssetManager assetManager, float width, float height,
                                         String title) {
        if (title == null || title.trim().isEmpty()) {
            return null;
        }
        Label label = titlePlate(title, assetManager, width, height);
        label.setLocalTranslation((width - label.getPreferredSize().x) / 2f,
                0, 5);
        chrome.attachChild(label);
        return label;
    }

    /**
     * Removes the chrome laid over a popup. Call it wherever the popup itself is detached, or it stays on
     * screen framing nothing.
     *
     * @param popupParent the node the popup (and its chrome) were attached to
     */
    public static void removeChromeFrom(Node popupParent) {
        if (popupParent == null) {
            return;
        }
        for (Spatial child : popupParent.getChildren().toArray(new Spatial[0])) {
            if (CHROME_NAME.equals(child.getName())) {
                child.removeFromParent();
            }
        }
    }

    /**
     * Pins the popup's ornament where the ornament says it belongs.
     *
     * <p>This is where the popup's steampunk character comes from, and it is deliberately NOT drawn into
     * the frame artwork : a nine-patch stretches its edges, so a gear painted there would be smeared the
     * length of the screen. A discrete icon laid over the fixed corner stays perfectly round at any
     * resolution, for the cost of one quad.
     *
     * <p>Everything the icon can choose is stated on the enum constant : its centre as a fraction of the
     * popup ({@link Ornament#x} / {@link Ornament#y}), its {@link Ornament#scale} and its
     * {@link Ornament#z}. This method just converts that into a panel — so a new ornament, wherever it
     * goes, is one line in the enum.
     *
     * <p>Sized as a multiple of the frame's corner zone rather than in vh, so icon and tube keep their
     * proportion to each other at every resolution — both are the same multiple of {@code imageScale}.
     *
     * @param chrome       the chrome node (popup-local coordinates : origin top-left, +x right, -y down)
     * @param assetManager the asset manager
     * @param width        the popup width in GUI pixels
     * @param height       the popup height in GUI pixels
     * @param kind         which icon to pin
     */
    private static void applyCornerOrnament(Node chrome, AssetManager assetManager, float width, float height,
                                            Ornament kind) {
        float size = ORNAMENT_CORNER_ZONES * kind.scale * Frame.POPUP.x1 * imageScale(height);
        Panel ornament = new Panel(size, size, IALON_STYLE);
        ornament.setBackground(quad(assetManager, kind.texture));
        // The panel is placed by its top-left corner, but an ornament is aimed by its centre : half a size
        // back on both axes, so scaling one grows it around its spot instead of dragging it off.
        ornament.setLocalTranslation(kind.x * width - size / 2f, -kind.y * height + size / 2f, kind.z);
        chrome.attachChild(ornament);
        sizeToPreferred(ornament);
    }


    /**
     * Where a popup's own content starts, measured down from the top of the popup : clear of the title
     * signboard and its gap.
     *
     * <p>Stated once here because it is what makes the popups line up. The title is part of the chrome,
     * which is drawn OVER the popup, so anything a popup lays out above this offset is not merely
     * badly spaced — it is hidden behind the plate. Both card grids and both scrollable forms take
     * their top margin from this, so a change to the signboard moves all four together.
     *
     * @param screenHeight the screen height in GUI pixels
     * @return the offset in GUI pixels, as a positive distance downwards
     */
    public static float contentTopOffset(float screenHeight) {
        return UiHelper.screenMargin(screenHeight)
                + (TITLE_HEIGHT_VH + TITLE_GAP_VH) * screenHeight / 100f;
    }

    /**
     * The title signboard : a brass title centred on a riveted plate.
     *
     * <p>One widget, not a plate with a label on top — a {@link Label} carrying the nine-patch as its
     * background, the same recipe {@link #plated} uses for buttons.
     *
     * <p>The text is measured BEFORE the background is installed and before the size is pinned :
     * {@code getPreferredSize()} reports the glyphs' own extent only while no explicit preferred size
     * has been set (an explicit one short-circuits Lemur's component walk entirely, which is also why
     * setting it afterwards leaves the outer plate exactly this size and lets the nine-patch margin
     * eat inwards).
     */
    private static Label titlePlate(String text, AssetManager assetManager, float width, float height) {
        Label label = brassLabel(text, height);
        label.setTextVAlignment(VAlignment.Center);
        float textWidth = label.getPreferredSize().x;
        label.setBackground(frame(assetManager, Frame.TITLE, height));
        label.setPreferredSize(new Vector3f(
                titlePlateWidth(textWidth, width, height), TITLE_HEIGHT_VH * height / 100f, 0));
        enforceMinimumSize(label, Frame.TITLE, height);
        return sizeToPreferred(label);
    }

    /**
     * How wide the signboard comes out : at least {@link #TITLE_WIDTH_VW}, wider if the title needs it,
     * but never wider than the room the corner header buttons leave.
     */
    private static float titlePlateWidth(float textWidth, float width, float height) {
        float floor = TITLE_WIDTH_VW * width / 100f;
        float wanted = Math.max(floor, textWidth + Frame.TITLE.horizontalChrome(height));
        return Math.min(wanted, titlePlateBand(width, height));
    }

    /**
     * The horizontal room between the two corner header buttons : the screen margin they sit at, their
     * own width ({@link #CORNER_BUTTON_VH} is their floor), and one more margin of air, on each side.
     *
     * <p>Floored at the patch's own width, because a plate narrower than its nine-patch renders
     * inverted — on a window too narrow for both buttons and a signboard, an overlap is the lesser
     * failure of the two.
     */
    private static float titlePlateBand(float width, float height) {
        float taken = 2 * UiHelper.screenMargin(height) + CORNER_BUTTON_VH * height / 100f;
        return Math.max(Frame.TITLE.minimumSize(height).x, width - 2 * taken);
    }

    /**
     * A dialog title : brass, centred, wide enough to span the buttons below it.
     *
     * @param text         the title
     * @param screenWidth  the screen width in GUI pixels
     * @param screenHeight the screen height in GUI pixels
     * @return the label
     */
    public static Label dialogTitle(String text, float screenWidth, float screenHeight) {
        Label label = brassLabel(text.toUpperCase(Locale.FRENCH), screenHeight);
        label.setPreferredSize(new Vector3f(50 * screenWidth / 100f, 8 * screenHeight / 100f, 0));
        return label;
    }

    private static Label brassLabel(String text, float screenHeight) {
        Label label = new Label(text.toUpperCase(Locale.FRENCH), IALON_STYLE);
        label.setFontSize(FONT_VH * screenHeight / 100f);
        label.setColor(BRASS);
        label.setTextHAlignment(HAlignment.Center);
        return label;
    }

    /**
     * A dialog box : a riveted plate, centred in its overlay, stacking its rows vertically. The plate's
     * nine-patch margin is what pads the content inside the box, so no background margin is needed.
     *
     * @param layout       the dialog's layout
     * @param assetManager the asset manager
     * @param screenHeight the screen height in GUI pixels
     * @return the dialog container
     */
    public static Container dialogBox(GuiLayout layout, AssetManager assetManager, float screenHeight) {
        Container dialog = new Container(layout, IALON_STYLE);
        dialog.setInsetsComponent(new DynamicInsetsComponent(0.5f, 0.5f, 0.5f, 0.5f)); // centre in the overlay
        applyPlate(dialog, Frame.PLATE_SILVER, assetManager, screenHeight);
        return dialog;
    }
}
