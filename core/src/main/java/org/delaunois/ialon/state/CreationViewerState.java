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

package org.delaunois.ialon.state;

import static org.delaunois.ialon.Ialon.IALON_STYLE;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.input.event.MouseButtonEvent;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.FrameBuffer.FrameBufferTarget;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.HAlignment;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.Panel;
import com.simsilica.lemur.VAlignment;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.core.GuiComponent;
import com.simsilica.lemur.event.CursorButtonEvent;
import com.simsilica.lemur.event.CursorEventControl;
import com.simsilica.lemur.event.CursorMotionEvent;
import com.simsilica.lemur.event.DefaultCursorListener;
import com.simsilica.lemur.event.DefaultMouseListener;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.IalonInitializer;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.serialize.Creation;
import org.delaunois.ialon.serialize.CreationRepository;
import org.delaunois.ialon.ui.IalonTheme;
import org.delaunois.ialon.ui.UiHelper;

import java.nio.file.Path;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

/**
 * The creation viewer : a modal popup showing one {@link Creation} alone, in 3D, on a black background, which
 * the player turns freely with a finger (or the mouse) to examine it from every side. Opened from the
 * "Preview" button of the creation library ({@link CreationLibraryState}), which it returns to on "Back".
 *
 * <p>The model is the real meshed blocks ({@link CreationMesh}, the same node the placement ghost and the
 * card thumbnail are built from), rendered by a <b>private pre-view viewport into a framebuffer</b> whose
 * texture is what the popup displays. It is not drawn straight over the screen because the popup's veil is
 * opaque and would cover it ; going through a texture leaves Lemur in charge of what is drawn over what, and
 * it is the path the card thumbnails already validate ({@link CreationPreview} renders exactly this scene,
 * one frame of it, to a PNG).</p>
 *
 * <p>The camera never moves : it sits on +Z, framed to fit the creation's bounding box, and it is the model
 * that turns (see {@link CreationTumble}). Screen right is therefore always world X and screen up always
 * world Y, which is what makes the drag-to-turn gesture straightforward.</p>
 *
 * @author Cedric de Launois
 */
@Slf4j
public class CreationViewerState extends BaseAppState implements Resizable, Popup {

    /** Vertical field of view of the viewer camera, in degrees — the thumbnail's, so the framing matches. */
    private static final float FOV = 45f;

    /** Slack left around the creation's bounding sphere, so it never touches the panel edges. */
    private static final float FIT_MARGIN = 1.05f;

    /** Bounds on the render target's size in pixels : big enough to read, small enough for a phone. */
    private static final int MIN_RENDER_SIZE = 256;
    private static final int MAX_RENDER_SIZE = 1024;

    /** How long the "card image updated" note stays on screen, in seconds. */
    private static final float CAPTURE_MESSAGE_SECONDS = 2.5f;

    /** The three-quarter view the model starts at, matching the card thumbnail's viewpoint. */
    private static final float INITIAL_YAW = 35f * FastMath.DEG_TO_RAD;
    private static final float INITIAL_PITCH = 20f * FastMath.DEG_TO_RAD;

    private final IalonConfig config;
    private final CreationTumble tumble = new CreationTumble();

    private SimpleApplication app;
    private Container popup;
    private Panel viewPanel;
    private Label emptyLabel;
    private Button cornerBack;
    private Button cornerClose;
    private Button setImage;
    private Label captureLabel;
    private float captureMessageSeconds;

    /** The creation on show : its name is the popup's title, its size the camera's framing. */
    private Creation creation;

    // The offscreen render : its own viewport, camera, framebuffer and colour texture. Built at the panel's
    // size when the popup first opens and REUSED from then on (see ensureRenderTarget) ; only the scene below
    // is per-creation.
    private ViewPort viewPort;
    private Camera camera;
    private FrameBuffer frameBuffer;
    private Texture2D texture;
    private Node scene;
    private Node pivot;

    // The panel's size in GUI pixels, kept so a drag can be measured as a fraction of it.
    private float panelWidth;
    private float panelHeight;

    public CreationViewerState(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void initialize(Application application) {
        this.app = (SimpleApplication) application;
        popup = createPopup();
        ScreenState.registerOn(app, this);
    }

    @Override
    protected void cleanup(Application application) {
        ScreenState.unregisterFrom(application, this);
        detachScene();
        releaseRenderTarget();
    }

    @Override
    protected void onEnable() {
        // Nothing : the popup is shown/hidden explicitly.
    }

    @Override
    protected void onDisable() {
        hidePopup();
    }

    private Container createPopup() {
        Container container = new Container(IALON_STYLE);
        container.setName("creationViewerPopup");
        container.setLocalTranslation(0, app.getCamera().getHeight(), 100);
        container.setPreferredSize(new Vector3f(app.getCamera().getWidth(), app.getCamera().getHeight(), 0));
        IalonTheme.applyVeil(container, app.getAssetManager(), config);
        // The veil swallows what lands on it, so nothing reaches the game behind the popup.
        container.addMouseListener(new IgnoreMouseClickListener());

        float height = app.getCamera().getHeight();
        cornerBack = IalonTheme.cornerButton("Back", app.getAssetManager(), height);
        cornerBack.addClickCommands(source -> backToLibrary());
        container.attachChild(cornerBack);

        cornerClose = IalonTheme.cornerButton("Close", app.getAssetManager(), height);
        cornerClose.addClickCommands(source -> hidePopup());
        container.attachChild(cornerClose);

        // Takes the creation's card image from what is on screen right now (see captureCardImage).
        setImage = IalonTheme.cornerButton("Set image", app.getAssetManager(), height);
        setImage.addClickCommands(source -> captureCardImage());
        container.attachChild(setImage);

        // The panel the render is shown on. Built once ; its texture and size are (re)installed by
        // showCreation, so a resolution change does not rebuild the widget. Black to start with, and
        // black again whenever there is no render : the backdrop the creation is shown on is the panel's.
        viewPanel = new Panel(IALON_STYLE);
        paintPanelBlack();
        CursorEventControl.addListenersToSpatial(viewPanel, new TumbleListener());
        container.attachChild(viewPanel);

        return container;
    }

    /**
     * Shows the given creation. Meshing it is the same synchronous cost as entering the placement mode, which
     * builds the very same node.
     *
     * @param creation a fully loaded creation (its {@code blocks} grid must be present)
     */
    public void enter(Creation creation) {
        if (creation == null || creation.getBlocks() == null) {
            log.warn("Nothing to preview : the creation carries no block grid");
            return;
        }
        this.creation = creation;
        tumble.reset(INITIAL_YAW, INITIAL_PITCH);
        if (popup.getParent() == null) {
            app.getGuiNode().attachChild(popup);
        }
        layout(app.getCamera().getWidth(), app.getCamera().getHeight());
        setPlayerTouchEnabled(false);
    }

    public void hidePopup() {
        captureMessageSeconds = 0;
        if (captureLabel != null) {
            captureLabel.removeFromParent();
        }
        if (popup != null && popup.getParent() != null) {
            IalonTheme.removeChromeFrom((Node) popup.getParent());
            popup.removeFromParent();
            setPlayerTouchEnabled(true);
        }
        detachScene();
        creation = null;
    }

    @Override
    public boolean isPopupOpen() {
        return popup != null && popup.getParent() != null;
    }

    /**
     * Full screen, opaque veil : nothing of the world shows through, so it need not be drawn. The model is
     * unaffected — it lives in its own scene, rendered by its own viewport, not in the world's root node
     * (which is what {@code Ialon.updateWorldPresence} culls).
     */
    @Override
    public boolean hidesWorld() {
        return true;
    }

    @Override
    public void closePopup() {
        hidePopup();
    }

    /**
     * Replaces the creation's card image in the library with what the viewer is showing : same orientation,
     * framed as tightly as the card's shape allows (see
     * {@link CreationPreview#render(SimpleApplication, Node, Vector3f, Quaternion, Path, Runnable)}).
     *
     * <p>Rendered from a freshly meshed node rather than from the one on screen, which the thumbnail renderer
     * would take over (it hangs what it is given in its own scene, under its own lights). Meshing again costs
     * what opening the popup costs, once, on a button press — and it is what
     * {@code CreationLibraryState.rerenderPreview} already does after an edit.</p>
     *
     * <p>The orientation is snapshotted here, on the click, so the turntable turning on afterwards cannot
     * change what was captured.</p>
     */
    private void captureCardImage() {
        if (creation == null || creation.getBlocks() == null) {
            return;
        }
        Node mesh = CreationMesh.build(creation.getBlocks(),
                creation.getSizeX(), creation.getSizeY(), creation.getSizeZ());
        if (mesh == null) {
            return;
        }
        String id = creation.getId();
        float scale = BlocksConfig.getInstance().getBlockScale();
        // Framed on the BLOCKS, not on the grid : a creation is captured as a box and stripping its ground
        // only empties cells, so the grid's own extent would spend the card's room on air.
        int[] bounds = CreationMesh.contentBounds(creation.getBlocks(),
                creation.getSizeX(), creation.getSizeY(), creation.getSizeZ());
        if (bounds == null) {
            return;
        }
        Vector3f size = new Vector3f((bounds[3] - bounds[0] + 1) * scale,
                (bounds[4] - bounds[1] + 1) * scale, (bounds[5] - bounds[2] + 1) * scale);
        Vector3f centre = new Vector3f(bounds[0] * scale, bounds[1] * scale, bounds[2] * scale)
                .addLocal(size.mult(0.5f));
        Path png = CreationRepository.previewPath(config.getSavePath(), id);
        CreationPreview.render(app, mesh, centre, size, tumble.getRotation().clone(), png, () -> {
            // The library reloads the card image by key, so the stale one has to leave the asset cache or
            // the grid would show the old thumbnail from memory.
            WorldPreview.dropFromCacheByKey(app.getAssetManager(), CreationRepository.previewAssetKey(id));
            log.info("Card image of creation '{}' updated from the viewer", id);
            showCaptureMessage();
        });
    }

    /** Closes the viewer and reopens the library it was opened from. */
    private void backToLibrary() {
        hidePopup();
        Optional.ofNullable(app.getStateManager().getState(CreationLibraryState.class))
                .ifPresent(CreationLibraryState::showPopup);
    }

    private void setPlayerTouchEnabled(boolean enabled) {
        Optional.ofNullable(app.getStateManager().getState(PlayerState.class))
                .ifPresent(ps -> ps.setTouchEnabled(enabled));
    }

    /**
     * Says so, briefly, when the card image has been taken. Worth the few lines : the card it changed lives in
     * the library, which is closed while the viewer is up, so without this the press has no visible effect at
     * all.
     */
    private void showCaptureMessage() {
        if (captureLabel == null) {
            captureLabel = new Label("Card image updated", IALON_STYLE);
            captureLabel.setColor(IalonTheme.BRASS);
            captureLabel.setTextHAlignment(HAlignment.Center);
            captureLabel.setTextVAlignment(VAlignment.Center);
        }
        float noteHeight = 6f * app.getCamera().getHeight() / 100f;
        captureLabel.setFontSize(3.2f * app.getCamera().getHeight() / 100f);
        captureLabel.setPreferredSize(new Vector3f(panelWidth, noteHeight, 0));
        // A plate under the text : the note lies over the render, and the model is light enough in places
        // that brass on black alone would not always read.
        UiHelper.addBackground(captureLabel, IalonTheme.SOOT_LIGHT, config);
        IalonTheme.sizeToPreferred(captureLabel);
        // Just inside the panel's bottom edge, where the framing's own margin keeps the model from reaching.
        captureLabel.setLocalTranslation(viewPanel.getLocalTranslation().x,
                viewPanel.getLocalTranslation().y - panelHeight + noteHeight, 4);
        if (captureLabel.getParent() == null) {
            popup.attachChild(captureLabel);
        }
        captureMessageSeconds = CAPTURE_MESSAGE_SECONDS;
    }

    @Override
    public void update(float tpf) {
        if (captureMessageSeconds > 0) {
            captureMessageSeconds -= tpf;
            if (captureMessageSeconds <= 0 && captureLabel != null) {
                captureLabel.removeFromParent();
            }
        }
        if (!isPopupOpen() || scene == null) {
            return;
        }
        tumble.update(tpf);
        pivot.setLocalRotation(tumble.getRotation());
        // Mandatory : a scene attached to a private viewport gets no update pass from SimpleApplication,
        // so nothing else would ever push the new rotation into the world transforms.
        scene.updateGeometricState();
    }

    @Override
    public void onResize(int width, int height) {
        if (isPopupOpen()) {
            layout(width, height);
        }
    }

    /**
     * Lays the popup out for the given screen size and (re)builds the render target at the panel's new size.
     * The orientation survives : it lives in {@link CreationTumble}, not in the scene being rebuilt.
     */
    private void layout(int width, int height) {
        Vector3f screen = new Vector3f(width, height, 0);
        popup.setPreferredSize(screen);
        popup.setSize(screen);
        popup.setLocalTranslation(0, height, 100);

        // A SQUARE panel, centred in the band left free below the title signboard — not the whole band. The
        // model is no smaller for it : the camera fits the creation's bounding sphere to the narrower of the
        // two frustum half-angles, which on any landscape panel is the vertical one, so the band's height is
        // what sets the model's size whatever the panel's width. All the extra width bought was black paint
        // over the popup's own backdrop, and it covered the steam rising in the bottom-left corner.
        float margin = UiHelper.screenMargin(height);
        float top = IalonTheme.contentTopOffset(height);
        // The "Set image" button sits under the panel, centred, and the band gives up a row for it. Under
        // rather than beside : a menu button is 26 vw wide and the space either side of a square panel is
        // narrower than that on a 4:3 screen, so a column there would only fit on some screens.
        float buttonRow = IalonTheme.menuRowHeight(height);
        float band = Math.max(1f, height - top - margin - buttonRow);
        float side = Math.max(1f, Math.min(width - 2 * margin, band));
        panelWidth = side;
        panelHeight = side;
        viewPanel.setLocalTranslation((width - side) / 2f, -top - (band - side) / 2f, 2);

        // Same widget as Back and Close (plated, hugging its label), so the popup keeps one button grammar.
        IalonTheme.restyleCornerButton(setImage, app.getAssetManager(), height);
        setImage.setLocalTranslation((width - setImage.getPreferredSize().x) / 2f, -top - band, 3);

        showCreation();

        IalonTheme.restyleCornerButton(cornerBack, app.getAssetManager(), height);
        IalonTheme.restyleCornerButton(cornerClose, app.getAssetManager(), height);
        UiHelper.placeCornerButtons(cornerBack, cornerClose, width, height);

        // Rebuilt rather than reused : a nine-patch bakes its scale into its mesh. It carries the title
        // signboard, hence the creation's name being passed on every layout.
        IalonTheme.applyChromeOver(popup, app.getAssetManager(), width, height,
                creation != null ? creation.getName() : null);
    }

    /**
     * Puts the current creation on show : meshes it, hangs it off a rotating pivot in a lit scene, points the
     * (reused, see {@link #ensureRenderTarget()}) offscreen target at that scene and shows its texture on the
     * panel.
     *
     * <p>An all-air creation (everything stripped by the library's "Edit") meshes to nothing : it gets a
     * message instead of a render.</p>
     *
     * <p>Called again on a resolution change, which re-meshes the creation rather than keeping the node : a
     * resize costs what opening the popup costs, and it happens once in a while rather than once a frame.</p>
     */
    private void showCreation() {
        detachScene();
        if (creation == null) {
            return;
        }

        Node creationNode = CreationMesh.build(creation.getBlocks(),
                creation.getSizeX(), creation.getSizeY(), creation.getSizeZ());
        if (creationNode == null) {
            showEmptyMessage();
            return;
        }
        hideEmptyMessage();

        float scale = BlocksConfig.getInstance().getBlockScale();
        Vector3f boxSize = new Vector3f(creation.getSizeX() * scale,
                creation.getSizeY() * scale, creation.getSizeZ() * scale);

        // The model turns about the centre of its box, so the pivot holds it shifted by half the box and
        // itself sits at the origin — which is also what the camera aims at.
        pivot = new Node("creationViewerPivot");
        creationNode.setLocalTranslation(boxSize.mult(-0.5f));
        pivot.attachChild(creationNode);

        scene = new Node("creationViewerScene");
        scene.attachChild(pivot);
        // BOTH lights are required : the block shader multiplies its result by the ambient sum AND by the
        // directional accumulation (IalonArray.vert), so a scene missing either renders pure black. Same
        // values as the card thumbnail, so the model looks there as it looks here.
        scene.addLight(new AmbientLight(ColorRGBA.White.mult(0.55f)));
        scene.addLight(new DirectionalLight(
                new Vector3f(-0.6f, -1f, -0.8f).normalizeLocal(), ColorRGBA.White.mult(1.3f)));

        ensureRenderTarget();
        aimCamera(boxSize);
        viewPort.attachScene(scene);
        showOnPanel(guiQuad(texture));
        pivot.setLocalRotation(tumble.getRotation());
        scene.updateGeometricState();
    }

    /**
     * Makes sure there is a framebuffer to render into, at the panel's current size, and a viewport pointed
     * at it. <b>Built once and then kept</b> — reopening the viewer, or showing another creation, reuses the
     * very same framebuffer, texture and camera ; only the scene changes.
     *
     * <p>That reuse is not an optimisation, it is the fix for a real defect. Allocating a framebuffer per
     * viewing rendered correctly the first time and then, from the second viewing on, produced a model with
     * <b>no depth testing</b> — faces drawn in submission order, a tree with its trunk painted over the
     * leaves in front of it. Freeing the old target explicitly, freeing it not at all, and changing the depth
     * format each moved the point at which it appeared without curing it : what the failure needs is a second
     * offscreen target coming and going while the first is still around. So the viewer keeps exactly one, for
     * as long as it lives, and the churn is gone. It costs one target's worth of GPU memory (a few MB at the
     * {@code MAX_RENDER_SIZE} ceiling) held while the popup is closed, released in {@code cleanup}.</p>
     *
     * <p>A resolution change is the one case that must allocate again, since the texel size follows the
     * panel — see {@link #releaseRenderTarget()} for how the old one is let go without taking a live
     * renderbuffer with it.</p>
     */
    private void ensureRenderTarget() {
        float renderScale = renderScale();
        int renderWidth = Math.max(1, Math.round(panelWidth * renderScale));
        int renderHeight = Math.max(1, Math.round(panelHeight * renderScale));
        if (texture != null && texture.getImage().getWidth() == renderWidth
                && texture.getImage().getHeight() == renderHeight) {
            registerViewPort();
            return;
        }
        releaseRenderTarget();

        // Sized to the panel, so not a power of two : GLES rejects such a texture unless it is mip-less and
        // edge-clamped, which is also exactly what sampling a render target wants.
        texture = new Texture2D(renderWidth, renderHeight, Image.Format.RGBA8);
        texture.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
        texture.setMagFilter(Texture.MagFilter.Bilinear);
        texture.setWrap(Texture.WrapMode.EdgeClamp);
        frameBuffer = new FrameBuffer(renderWidth, renderHeight, 1);
        frameBuffer.setDepthTarget(FrameBufferTarget.newTarget(Image.Format.Depth));
        frameBuffer.addColorTarget(FrameBufferTarget.newTarget(texture));

        camera = new Camera(renderWidth, renderHeight);
        registerViewPort();
    }

    /**
     * Puts the viewer's viewport back in the render manager's pre-view list, building it on first use.
     *
     * <p>No sRGB on the framebuffer (the default) : the block shaders already write what the GUI expects, and
     * encoding here would encode a second time. Same target as the card thumbnail, which is why the model
     * looks in the viewer exactly as it looks on its card.</p>
     */
    private void registerViewPort() {
        // A fresh ViewPort every time, even though the camera and the framebuffer behind it are the same ones :
        // a ViewPort is a plain object with no GL resource of its own, and the render manager's pre-view list
        // is read-only from the outside, so re-registering means asking for a new one.
        if (viewPort != null) {
            app.getRenderManager().removePreView(viewPort);
        }
        viewPort = app.getRenderManager().createPreView("creationViewer", camera);
        viewPort.setClearFlags(true, true, true);
        viewPort.setBackgroundColor(ColorRGBA.Black);
        viewPort.setOutputFrameBuffer(frameBuffer);
        // Water, glass and fire faces carry a layer only this comparator honours ; a private viewport does
        // not get the one the main viewport is given.
        IalonInitializer.installLayerComparator(viewPort);
    }

    /**
     * Puts a background on the view panel and sizes the panel to the current band.
     *
     * <p>The sizing is not a leftover from the layout : it is what <b>makes the background appear at all</b>.
     * The panel is attached with {@code attachChild}, so nobody lays it out — Lemur's {@code revalidate}
     * bails out for a widget whose parent carries a {@code GuiControl}, and the parent's layout only knows
     * the children given to it with {@code addChild}. {@code setSize} is the call that walks the component
     * stack and hands each component its rectangle, which is when a background builds its quad. So it has to
     * be re-applied every time the background changes, not only when the screen is resized : install a new
     * background after the last {@code setSize} and it silently draws nothing.</p>
     */
    private void showOnPanel(GuiComponent background) {
        viewPanel.setBackground(background);
        Vector3f panelSize = new Vector3f(Math.max(1f, panelWidth), Math.max(1f, panelHeight), 0);
        viewPanel.setPreferredSize(panelSize);
        viewPanel.setSize(panelSize.clone());
    }

    /**
     * The viewer's camera : on +Z, aimed at the origin the model turns about, far enough back for the whole
     * box to stay in frame <b>at every orientation</b>.
     *
     * <p>That bound is the box's <b>circumscribed sphere</b> — half its diagonal — precisely because the box
     * turns : a distance fitted to one orientation clips the model in another, and the sphere is the only
     * bound a rotation leaves alone. The sphere is then fitted to the narrower of the two half-angles (the
     * vertical one is the field of view, the horizontal one follows from the aspect ratio), so a panel of any
     * shape frames it.</p>
     *
     * <p>The frustum brackets that sphere instead of spanning the world : the depth buffer is a 16-bit one
     * (all a GLES2 renderbuffer guarantees) and a tight range is what keeps it precise.</p>
     *
     * <p>Package-private and static so the framing can be checked without a GL context — a {@code Camera} is
     * pure maths (see {@code CreationViewerFramingTest}).</p>
     *
     * @param boxSize the creation's box extent in world units
     * @param width   the render target's width in pixels
     * @param height  the render target's height in pixels
     * @return the camera, updated and ready to render with
     */
    static Camera framedCamera(Vector3f boxSize, int width, int height) {
        Camera cam = new Camera(width, height);
        aimCamera(cam, boxSize);
        return cam;
    }

    /** Aims the viewer's own (reused) camera at the creation currently on show. */
    private void aimCamera(Vector3f boxSize) {
        aimCamera(camera, boxSize);
    }

    private static void aimCamera(Camera cam, Vector3f boxSize) {
        float aspect = cam.getWidth() / (float) cam.getHeight();
        float radius = boxSize.length() * 0.5f;
        float halfVertical = FOV * FastMath.DEG_TO_RAD * 0.5f;
        float halfHorizontal = FastMath.atan(FastMath.tan(halfVertical) * aspect);
        float distance = radius / FastMath.sin(Math.min(halfVertical, halfHorizontal)) * FIT_MARGIN;

        cam.setFrustumPerspective(FOV, aspect,
                Math.max(0.05f, distance - radius * 1.5f), distance + radius * 3f);
        cam.setLocation(new Vector3f(0, 0, distance));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        cam.update();
    }

    /**
     * How the render target is scaled from the panel : one factor for both axes, so its <b>aspect ratio is
     * the panel's</b>. That is the whole point of a single factor — clamping each axis on its own is what
     * stretches the model, since a wide panel is capped on its width while its height still fits.
     *
     * <p>The factor only ever leaves 1 to bring the longer side inside the bounds : an upper one, because at
     * RGBA8 plus a depth buffer this popup is not allowed to cost a phone more than a few megabytes while it
     * is open, and a lower one, so a small window still renders something readable.</p>
     */
    private float renderScale() {
        float longSide = Math.max(panelWidth, panelHeight);
        if (longSide > MAX_RENDER_SIZE) {
            return MAX_RENDER_SIZE / longSide;
        }
        if (longSide < MIN_RENDER_SIZE) {
            return MIN_RENDER_SIZE / longSide;
        }
        return 1f;
    }

    /**
     * Stops the offscreen render and drops the model, keeping the target itself (see
     * {@link #ensureRenderTarget()} for why it is kept). Called whenever the popup closes and before a new
     * creation is put on show.
     *
     * <p>The viewport leaves the render manager's list rather than staying on with an empty scene : the
     * framebuffer would still be cleared every frame for nothing. No {@code app.enqueue} around
     * {@code removePreView} : every caller runs inside {@code Application.update()} — an app state's update,
     * a Lemur click command, the ESC action — which completes before the render pass walks the viewport list.
     * ({@link CreationPreview} does need the enqueue : it removes its own viewport from inside
     * {@code postFrame}, mid-render.)</p>
     *
     * <p>The meshed blocks are simply dropped, and the collector reclaims their vertex buffers ; their
     * materials and the block texture array are shared with the world and must not be touched.</p>
     */
    private void detachScene() {
        if (viewPort != null) {
            viewPort.clearScenes();
            app.getRenderManager().removePreView(viewPort);
        }
        paintPanelBlack();
        if (pivot != null) {
            pivot.detachAllChildren();
        }
        scene = null;
        pivot = null;
    }

    /**
     * Frees the offscreen target for good : on {@code cleanup}, or when a resolution change calls for another
     * size. Only place that does, hence the render target's whole life fitting in the state's.
     *
     * <p>The colour texture is freed at once, by name, which is safe. The <b>framebuffer is only dropped</b>,
     * left for the collector — and that is deliberate. Deleting it eagerly (whether through
     * {@code Renderer.deleteFrameBuffer} or through {@code dispose()}) makes jME walk the real object's
     * attachments and call {@code glDeleteRenderbuffers} on <em>each</em> of them, the colour one included —
     * but our colour attachment is a texture, not a renderbuffer, so that call goes out with a name from the
     * wrong namespace and deletes whatever renderbuffer happens to carry that number. A live framebuffer's
     * depth renderbuffer, for instance. Letting the collector do it goes through jME's destructible clone,
     * which carries the framebuffer's own name and no attachments, so exactly one correct name is deleted. The
     * price is the depth renderbuffer staying allocated — jME's own limitation, and one target's worth.</p>
     */
    private void releaseRenderTarget() {
        if (viewPort != null) {
            app.getRenderManager().removePreView(viewPort);
            viewPort = null;
        }
        frameBuffer = null;
        if (texture != null) {
            texture.getImage().dispose();
            texture = null;
        }
        camera = null;
    }

    /** The panel with no render on it : plain black, which is also what the render clears to. */
    private void paintPanelBlack() {
        if (viewPanel != null) {
            showOnPanel(guiQuad(UiHelper.overlayColor(IalonTheme.SOOT, config)));
        }
    }

    /** A background quad, with the alpha discard the rest of the UI drops too (it costs its own shader). */
    private static QuadBackgroundComponent guiQuad(Texture2D texture) {
        return withoutAlphaDiscard(new QuadBackgroundComponent(texture));
    }

    private static QuadBackgroundComponent guiQuad(ColorRGBA color) {
        return withoutAlphaDiscard(new QuadBackgroundComponent(color));
    }

    private static QuadBackgroundComponent withoutAlphaDiscard(QuadBackgroundComponent quad) {
        quad.getMaterial().getMaterial().clearParam(UiHelper.ALPHA_DISCARD_THRESHOLD);
        return quad;
    }

    private void showEmptyMessage() {
        if (emptyLabel == null) {
            emptyLabel = new Label("This creation is empty.", IALON_STYLE);
            emptyLabel.setColor(IalonTheme.PARCHMENT);
            emptyLabel.setTextHAlignment(HAlignment.Center);
            emptyLabel.setTextVAlignment(VAlignment.Center);
        }
        emptyLabel.setFontSize(3 * app.getCamera().getHeight() / 100f);
        emptyLabel.setPreferredSize(new Vector3f(panelWidth, panelHeight, 0));
        // Sized by hand for the same reason the view panel is (see showOnPanel) : nothing lays out a widget
        // that was attached rather than added.
        IalonTheme.sizeToPreferred(emptyLabel);
        emptyLabel.setLocalTranslation(viewPanel.getLocalTranslation().x,
                viewPanel.getLocalTranslation().y, 3);
        if (emptyLabel.getParent() == null) {
            popup.attachChild(emptyLabel);
        }
    }

    private void hideEmptyMessage() {
        if (emptyLabel != null) {
            emptyLabel.removeFromParent();
        }
    }

    /**
     * Turns a drag on the panel into a rotation of the model. A Lemur <b>cursor</b> listener, which is what
     * every drag gesture in the game uses : it is the one input path delivered the same way for a touch and
     * for a mouse, once a frame, to the spatial that was pressed, with a guaranteed release (see
     * {@code CardGrid.ScrollListener} and the note in {@code BlockSliderSelectionState}).
     */
    private class TumbleListener extends DefaultCursorListener {

        private boolean capturing;
        private float lastX;
        private float lastY;

        @Override
        public void cursorButtonEvent(CursorButtonEvent event, Spatial target, Spatial capture) {
            event.setConsumed();
            if (event.isPressed()) {
                capturing = true;
                lastX = event.getX();
                lastY = event.getY();
                tumble.press();
            } else if (capturing) {
                capturing = false;
                tumble.release();
            }
        }

        @Override
        public void cursorMoved(CursorMotionEvent event, Spatial target, Spatial capture) {
            if (!capturing) {
                return;
            }
            float dx = event.getX() - lastX;
            float dy = event.getY() - lastY;
            lastX = event.getX();
            lastY = event.getY();
            // Measured as a fraction of the panel, so the same swipe turns the model as far whatever the
            // resolution or the panel's size.
            tumble.drag(dx / panelWidth, dy / panelHeight);
            event.setConsumed();
        }
    }

    private static class IgnoreMouseClickListener extends DefaultMouseListener {
        @Override
        public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
            event.setConsumed();
        }
    }
}
