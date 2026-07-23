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

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Quad;
import com.simsilica.lemur.component.AbstractGuiComponent;
import com.simsilica.lemur.core.GuiControl;

import org.delaunois.ialon.IalonConfig;

import lombok.Getter;

/**
 * The backdrop of a full-screen popup : the black veil, with a jet of steam escaping from a leak in the
 * bottom pipe of the popup's frame and spreading as it rises.
 *
 * <p>A Lemur background component, exactly like the {@code QuadBackgroundComponent} it replaces — one
 * quad the size of the popup, drawn behind everything the popup holds — but wearing the procedural
 * {@code Shaders/Steam.j3md} material instead of a flat colour. Nothing else changes : the veil still
 * hides the world completely (the steam is drawn ON the veil colour, never over a hole in it), so
 * {@code Popup.hidesWorld} keeps holding and the 3D scene is still not rendered while a popup is up.
 *
 * <h2>Why the steam is part of the backdrop</h2>
 *
 * The obvious alternative — a separate quad laid over the veil — cannot work with the popups' depth
 * order : a popup's content sits above its background and the chrome above that, so a steam quad would
 * either hide the content or be hidden by the veil. Drawing the steam INTO the backdrop puts it exactly
 * where the black used to be, under the content and under the tube, which is also what makes the leak
 * convincing : the plume's base disappears behind the pipe it comes out of.
 *
 * <h2>Where the leak is</h2>
 *
 * The valve standing beside the pressure gauge in the frame's bottom-left corner, at
 * ({@link #ORIGIN_X}, {@link #ORIGIN_Y}) artwork pixels from that corner. Both are scaled with the chrome
 * ({@link IalonTheme#imageScale}), so the jet stays on the valve at every resolution — see
 * {@link #ORIGIN_X} for why that has to be artwork pixels and not a fraction of the popup.
 *
 * @author Cedric de Launois
 */
public class SteamBackground extends AbstractGuiComponent implements Cloneable {

    /**
     * Where the steam escapes, in the frame artwork's own pixels ({@code Textures/UI/frame-popup.png},
     * 256 x 256), measured from the popup's BOTTOM-LEFT corner : the valve stem standing on the pipe
     * beside the pressure gauge, whose axis is at x 66 and whose cap is at y 40. Sitting the source a
     * couple of pixels INSIDE the cap is what makes the jet appear to come out of the valve : its first
     * rows are hidden by the valve itself, which is drawn over the backdrop.
     *
     * <p>Artwork pixels, and not a fraction of the popup, because the valve is part of the artwork. Both
     * are scaled by {@link IalonTheme#imageScale} like the chrome itself, so the jet stays on the valve at
     * every resolution — which a fraction of the WIDTH cannot do : it drifts with the aspect ratio, and a
     * value tuned on a 16:9 window lands somewhere else entirely on a phone in portrait.
     *
     * <p>Both must stay within the frame's corner zone, 96 artwork pixels square ({@code Frame.POPUP}'s
     * slice metrics). That zone is the part of a nine-patch that does NOT stretch, so a feature inside it
     * keeps a fixed offset from the corner whatever the window size. Past 96 the artwork is in a stretched
     * edge, where nothing keeps a fixed position and this convention stops meaning anything.
     */
    private static final float ORIGIN_X = 66f;

    /** Height of the leak above the popup's bottom edge, in artwork pixels (see {@link #ORIGIN_X}). */
    private static final float ORIGIN_Y = 40f;

    /** Steam, lit by the popup's own brass warmth rather than pure white — colder white reads as fog. */
    private static final ColorRGBA STEAM = new ColorRGBA().setAsSrgb(0.94f, 0.92f, 0.88f, 1f);

    // The knobs worth turning. Everything else about the plume's shape — how it curls, how its silhouette
    // wanders, how tight the jet is at the leak — is authored in the shader itself (Shaders/Steam.frag).

    /** Animation speed multiplier. */
    private static final float SPEED = .5f;
    /**
     * Opacity of the plume at its thickest. Deliberately short of 1 : this is a BACKDROP, and the popups
     * put parchment text straight on it — a plume that goes white steals the contrast the text needs.
     */
    private static final float DENSITY = 0.45f;
    /** Noise frequency : how many puffs fit in one screen height at the hole (they swell as they climb). */
    private static final float SCALE = 8.0f;
    /** How far up the plume reaches, in screen heights : short of the title signboard. */
    private static final float REACH = 0.60f;
    /** How fast the column widens as it climbs, in screen heights per screen height. */
    private static final float SPREAD = 0.25f;
    /** Half-width of the jet at the hole, in screen heights : about a tube's thickness at any resolution. */
    private static final float WIDTH = 0.005f;

    /**
     * Depth given to the backdrop, so the popup's own content does not end up coplanar with it. Same value
     * and same reason as Lemur's own backgrounds (see {@code QuadBackgroundComponent}).
     */
    private static final float Z_OFFSET = 0.01f;

    @Getter
    private Material material;

    private Geometry background;

    // Reused across layouts rather than reallocated ; re-instantiated on clone, since the clone must not
    // write into the original's vectors (the material params hold these very instances).
    private Vector2f resolution = new Vector2f(1, 1);
    private Vector2f origin = new Vector2f();

    /**
     * @param assetManager the asset manager
     * @param config       the config, which says whether the platform needs the in-shader sRGB encode
     */
    public SteamBackground(AssetManager assetManager, IalonConfig config) {
        material = new Material(assetManager, "Shaders/Steam.j3md");
        material.setColor("BackColor", UiHelper.overlayColor(IalonTheme.SOOT, config));
        material.setColor("SteamColor", STEAM);
        material.setFloat("Speed", SPEED);
        material.setFloat("Density", DENSITY);
        material.setFloat("Scale", SCALE);
        material.setFloat("Reach", REACH);
        material.setFloat("Spread", SPREAD);
        material.setFloat("Width", WIDTH);
        material.setBoolean("ManualSrgb", config.isManualGammaEncode());
        // Blended like every other Lemur background : the veil is opaque today, but a popup that wants to
        // let the world through only has to lower BackColor's alpha for it to work.
        material.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        // Placeholders until the first reshape() : a Resolution left unset would reach the shader as a
        // zero vector, and the plume is laid out by DIVIDING by its height.
        material.setVector2("Resolution", resolution);
        material.setVector2("Origin", origin);
    }

    @Override
    public SteamBackground clone() {
        SteamBackground result = (SteamBackground) super.clone();
        result.material = material.clone();
        result.background = null;
        result.resolution = new Vector2f(1, 1);
        result.origin = new Vector2f();
        result.material.setVector2("Resolution", result.resolution);
        result.material.setVector2("Origin", result.origin);
        return result;
    }

    @Override
    public void detach(GuiControl parent) {
        if (background != null) {
            getNode().detachChild(background);
            background = null;
        }
        super.detach(parent);
    }

    @Override
    public void calculatePreferredSize(Vector3f size) {
        size.z += Z_OFFSET;
    }

    @Override
    public void reshape(Vector3f pos, Vector3f size) {
        refreshBackground(size);

        background.setLocalTranslation(pos.x, pos.y - size.y, pos.z);
        pos.z += Z_OFFSET;
        size.z -= Z_OFFSET;
    }

    /**
     * Builds or resizes the backdrop quad, and tells the shader the two things it cannot know : how big
     * the quad is, and where on it the leak is.
     *
     * <p>Both are in GUI pixels and both are re-sent here rather than once at construction, because a
     * resolution change reaches a background component ONLY through {@code reshape} — and the origin
     * depends on the screen height, which is what scales the chrome the valve is drawn in.
     */
    private void refreshBackground(Vector3f size) {
        if (background == null) {
            background = new Geometry("steam", new Quad(size.x, size.y));
            background.setMaterial(material);
            getNode().attachChild(background);
        } else {
            Quad quad = (Quad) background.getMesh();
            if (size.x != quad.getWidth() || size.y != quad.getHeight()) {
                quad.updateGeometry(size.x, size.y);
                quad.clearCollisionData();
            }
        }

        float scale = IalonTheme.imageScale(size.y);
        resolution.set(size.x, size.y);
        origin.set(ORIGIN_X * scale, ORIGIN_Y * scale);
        material.setVector2("Resolution", resolution);
        material.setVector2("Origin", origin);
    }
}
