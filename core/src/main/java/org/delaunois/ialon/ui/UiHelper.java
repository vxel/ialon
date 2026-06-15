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

    public static void addBackground(Panel panel) {
        addBackground(panel, new ColorRGBA(0f, 0, 0, 0.5f));
    }

    public static void addBackground(Panel panel, ColorRGBA colorRGBA) {
        QuadBackgroundComponent quadBackgroundComponent = new QuadBackgroundComponent(colorRGBA);
        quadBackgroundComponent.getMaterial().getMaterial().clearParam(ALPHA_DISCARD_THRESHOLD);
        panel.setBackground(quadBackgroundComponent);
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
        QuadBackgroundComponent background = new QuadBackgroundComponent(new ColorRGBA(0, 0, 0, 0.5f));
        // Clear AlphaDiscardThreshold because it is useless here and generates a new specific Shader
        background.getMaterial().getMaterial().clearParam(ALPHA_DISCARD_THRESHOLD);
        iconButton.background.setBackground(background);
        iconButton.background.setLocalTranslation(posx, posy, -1);

        return iconButton;
    }

    public static class IconButton {
        @Getter
        public Panel icon;

        @Getter
        public Panel background;
    }

}
