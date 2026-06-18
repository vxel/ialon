package org.delaunois.ialon.ui;

import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.simsilica.lemur.HAlignment;
import com.simsilica.lemur.Panel;
import com.simsilica.lemur.VAlignment;
import com.simsilica.lemur.component.QuadBackgroundComponent;

import org.delaunois.ialon.IalonConfig;

import lombok.Getter;

public class UiHelper {

    public static final String ALPHA_DISCARD_THRESHOLD = "AlphaDiscardThreshold";

    /**
     * Margin (in GUI pixels) between the screen edges and the UI buttons, expressed as a fraction
     * of the screen height so it scales with the resolution. {@code 1/24} reproduces the historical
     * 30px margin at 720p while keeping a comparable proportion in fullscreen / high resolutions.
     */
    public static final float SCREEN_MARGIN_RATIO = 1f / 24f;

    /**
     * Proportional screen margin for the given screen height (see {@link #SCREEN_MARGIN_RATIO}).
     *
     * @param height the screen height in GUI pixels
     * @return the margin in GUI pixels
     */
    public static float screenMargin(float height) {
        return height * SCREEN_MARGIN_RATIO;
    }

    /**
     * Repositions and resizes an existing (non-batched) {@link IconButton}, e.g. on a resolution
     * change. Both the icon and its background panel are updated, preserving their relative depth.
     *
     * @param button the button to update
     * @param size   the new icon/background size
     * @param posx   the new x translation
     * @param posy   the new y translation
     */
    public static void resizeTextureButton(IconButton button, float size, float posx, float posy) {
        button.icon.setPreferredSize(new Vector3f(size, size, 0));
        button.icon.setLocalTranslation(posx, posy, 0);
        button.background.setPreferredSize(new Vector3f(size, size, 0));
        button.background.setLocalTranslation(posx, posy, -1);
    }

    public static void addBackground(Panel panel, ColorRGBA colorRGBA, IalonConfig config) {
        QuadBackgroundComponent quadBackgroundComponent = new QuadBackgroundComponent(overlayColor(colorRGBA, config));
        quadBackgroundComponent.getMaterial().getMaterial().clearParam(ALPHA_DISCARD_THRESHOLD);
        panel.setBackground(quadBackgroundComponent);
    }

    /**
     * Adjusts the alpha of a translucent black UI overlay so it darkens the scene by the same perceived
     * amount on Android as on desktop.
     *
     * <p>Desktop renders through a hardware sRGB framebuffer, so GL alpha-blends in LINEAR space : a black
     * overlay of alpha {@code a} darkens the (sRGB-displayed) backdrop by a factor {@code (1-a)^(1/2.2)}.
     * Android has no hardware sRGB framebuffer (see {@link IalonConfig#isManualGammaEncode()}) ; the world
     * shaders write already-sRGB-encoded colours into a plain framebuffer, so GL blends in sRGB space and
     * the same overlay darkens the backdrop by {@code (1-a)} — noticeably more, which is why the popup /
     * button backgrounds looked darker on Android. To reproduce the desktop appearance we lower the alpha
     * to {@code a' = 1 - (1-a)^(1/2.2)} when manual gamma encoding is active.</p>
     *
     * <p>Exact for black overlays (the only translucent UI backgrounds here). Opaque or fully transparent
     * colours, and the desktop path, are returned unchanged.</p>
     *
     * @param colorRGBA the intended overlay colour (must be black for an exact match)
     * @param config    the config holding the {@code manualGammaEncode} flag
     * @return a colour with platform-corrected alpha (a copy when adjusted, the input otherwise)
     */
    public static ColorRGBA overlayColor(ColorRGBA colorRGBA, IalonConfig config) {
        if (!config.isManualGammaEncode() || colorRGBA.a <= 0f || colorRGBA.a >= 1f) {
            return colorRGBA;
        }
        return new ColorRGBA(colorRGBA.r, colorRGBA.g, colorRGBA.b,
                1f - (float) Math.pow(1f - colorRGBA.a, 1f / 2.2f));
    }

    public static IconButton createTextureButton(IalonConfig config, String textureName, float size, float posx, float posy) {
        IconButton iconButton = new IconButton();

        // Icon
        iconButton.icon = new Panel();
        iconButton.icon.setPreferredSize(new Vector3f(size, size, 0));

        AtlasIconComponent icon = new AtlasIconComponent("Textures/" + textureName);
        icon.setVAlignment(VAlignment.Center);
        icon.setHAlignment(HAlignment.Center);
        config.getTextureAtlasManager().getAtlas().applyCoords(icon.getIconGeometry(), 0.1f);
        icon.getMaterial().setTexture(config.getTextureAtlasManager().getDiffuseMap());
        icon.getMaterial().getMaterial().clearParam(ALPHA_DISCARD_THRESHOLD);

        iconButton.icon.setBackground(icon);
        iconButton.icon.setLocalTranslation(posx, posy, 0);

        // Background
        iconButton.background = new Panel();
        iconButton.background.setPreferredSize(new Vector3f(size, size, 0));
        QuadBackgroundComponent background = new QuadBackgroundComponent(overlayColor(new ColorRGBA(0, 0, 0, 0.6f), config));
        // Clear AlphaDiscardThreshold because it is useless here and generates a new specific Shader
        background.getMaterial().getMaterial().clearParam(ALPHA_DISCARD_THRESHOLD);
        iconButton.background.setBackground(background);
        iconButton.background.setLocalTranslation(posx, posy, -1);

        return iconButton;
    }

    /**
     * Positions two header buttons at the top corners of a full-screen popup, level with the title : the
     * left one against the left edge (screen margin in), the right one against the right edge. Both tops
     * are placed at the same screen margin as the title's top spacer, so they line up with the title.
     * Uses the popup-local convention (origin at the top-left, +x right, -y down) — the same one the
     * worlds grid uses. The buttons must have a preferred size set (the right one is placed from its width).
     *
     * @param left   the top-left button (e.g. Back)
     * @param right  the top-right button (e.g. Close)
     * @param width  the popup / screen width
     * @param height the popup / screen height
     */
    public static void placeCornerButtons(Panel left, Panel right, float width, float height) {
        float margin = screenMargin(height);
        left.setLocalTranslation(margin, -margin, 10);
        right.setLocalTranslation(width - margin - right.getPreferredSize().x, -margin, 10);
    }

    public static class IconButton {
        @Getter
        public Panel icon;

        @Getter
        public Panel background;
    }

}
