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
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.texture.Texture;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.FillMode;
import com.simsilica.lemur.HAlignment;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.Panel;
import com.simsilica.lemur.VAlignment;
import com.simsilica.lemur.component.DynamicInsetsComponent;
import com.simsilica.lemur.component.InsetsComponent;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.component.SpringGridLayout;
import com.simsilica.lemur.event.CursorEventControl;
import com.simsilica.lemur.event.DefaultMouseListener;
import com.jme3.input.event.MouseButtonEvent;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.serialize.Creation;
import org.delaunois.ialon.serialize.CreationRepository;
import org.delaunois.ialon.ui.UiHelper;

import java.util.List;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

/**
 * The creation library : a modal popup, modelled on the worlds menu, listing the saved {@link Creation}s
 * as selectable thumbnail cards. Selecting a card then pressing "Build" closes the popup and starts the
 * placement mode ({@link CreationPlacementState}) to stamp the creation into the current world.
 *
 * <p>Opened from the worlds menu ("Creations" button) via {@link #showPopup()} ; it owns no top-of-screen
 * button of its own.</p>
 *
 * @author Cedric de Launois
 */
@Slf4j
public class CreationLibraryState extends BaseAppState implements Resizable, CardGrid.ScrollableGrid {

    private final IalonConfig config;

    private SimpleApplication app;
    private Node popup;
    private Node view;
    private Container gridContainer;
    private Container deleteOverlay;
    // Header buttons at the popup's top corners (like the settings / new-world popups) : Back (left)
    // returns to the worlds menu, Close (right) returns to the game.
    private Button cornerBack;
    private Button cornerClose;

    private String selectedId;
    private float gridLeftX;
    private float gridTopBase;
    private float scrollOffset;
    private float maxScroll;

    public CreationLibraryState(IalonConfig config) {
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
    }

    @Override
    protected void onEnable() {
        // Nothing : the popup is shown/hidden explicitly.
    }

    @Override
    protected void onDisable() {
        hidePopup();
    }

    private Node createPopup() {
        Container container = new Container(IALON_STYLE);
        container.setName("creationLibraryPopup");
        container.setLocalTranslation(0, app.getCamera().getHeight(), 100);
        container.setPreferredSize(new Vector3f(app.getCamera().getWidth(), app.getCamera().getHeight(), 0));
        UiHelper.addBackground(container, new ColorRGBA(0f, 0f, 0f, 0.95f), config);
        CursorEventControl.addListenersToSpatial(container, new CardGrid.ScrollListener(this, null));
        view = new Node("creationLibraryView");

        // Header buttons at the top corners, exactly like the settings / new-world popups. Their font is
        // set now (not only in layoutCornerButtons) so their width is known when rebuildGrid offsets the
        // grid to the right of the Back button.
        float vh = app.getCamera().getHeight() / 100f;
        cornerBack = new Button("Back", IALON_STYLE);
        cornerBack.setFontSize(4 * vh);
        cornerBack.setTextVAlignment(VAlignment.Center);
        cornerBack.addClickCommands(source -> backToWorldMenu());
        container.attachChild(cornerBack);

        cornerClose = new Button("Close", IALON_STYLE);
        cornerClose.setFontSize(4 * vh);
        cornerClose.setTextVAlignment(VAlignment.Center);
        cornerClose.addClickCommands(source -> hidePopup());
        container.attachChild(cornerClose);

        return container;
    }

    public void showPopup() {
        if (popup.getParent() != null) {
            return;
        }
        rebuildGrid();
        app.getGuiNode().attachChild(popup);
        sizePopupToScreen();
        layoutCornerButtons();
        setPlayerTouchEnabled(false);
    }

    /** Closes the library and reopens the worlds menu (owned by {@link WorldMenuState}). */
    private void backToWorldMenu() {
        hidePopup();
        Optional.ofNullable(app.getStateManager().getState(WorldMenuState.class))
                .ifPresent(WorldMenuState::showPopup);
    }

    /**
     * Sizes (font only, so each button hugs its text) and places the corner buttons : Back flush to the
     * left edge, Close flush to the right edge, both at the screen margin (see {@link UiHelper}).
     */
    private void layoutCornerButtons() {
        float vh = app.getCamera().getHeight() / 100f;
        cornerBack.setFontSize(4 * vh);
        cornerClose.setFontSize(4 * vh);
        UiHelper.placeCornerButtons(cornerBack, cornerClose,
                app.getCamera().getWidth(), app.getCamera().getHeight());
    }

    public void hidePopup() {
        if (popup.getParent() != null) {
            popup.removeFromParent();
            setPlayerTouchEnabled(true);
        }
        closeDeleteConfirm();
    }

    private void setPlayerTouchEnabled(boolean enabled) {
        Optional.ofNullable(app.getStateManager().getState(PlayerState.class))
                .ifPresent(ps -> ps.setTouchEnabled(enabled));
    }

    private void rebuildGrid() {
        if (view.getParent() == null) {
            popup.attachChild(view);
        }
        view.detachAllChildren();

        float vh = app.getCamera().getHeight() / 100f;
        float vw = app.getCamera().getWidth() / 100f;
        float w = app.getCamera().getWidth();

        List<Creation> creations = CreationRepository.listCreations(config.getSavePath());
        if (creations.stream().noneMatch(c -> c.getId().equals(selectedId))) {
            selectedId = creations.isEmpty() ? null : creations.get(0).getId();
        }

        // Offset the grid to the RIGHT of the top-left Back button (its measured width + a gap) so the
        // cards never share its column — otherwise, while scrolling, rows slide across the Back button.
        float margin = UiHelper.screenMargin(app.getCamera().getHeight());
        gridLeftX = margin + cornerBack.getPreferredSize().x + 3 * vw;
        gridTopBase = -6 * vh;
        // Columns sized to the space actually left between the grid's left edge and the right button column.
        float cardFootprintW = 26 * vw + 4.8f * vh;
        float rightColumnX = w - 30 * vw;
        int cols = Math.max(1, (int) ((rightColumnX - gridLeftX - 2 * vw) / cardFootprintW));
        gridContainer = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        if (creations.isEmpty()) {
            Label empty = new Label("No creation yet.\nUse the world menu : New creation.", IALON_STYLE);
            empty.setColor(ColorRGBA.White);
            empty.setFontSize(3 * vh);
            gridContainer.addChild(empty, 0, 0);
        } else {
            for (int i = 0; i < creations.size(); i++) {
                gridContainer.addChild(createCard(creations.get(i), vw, vh), i / cols, i % cols);
            }
        }
        CursorEventControl.addListenersToSpatial(gridContainer, new CardGrid.ScrollListener(this, null));
        view.attachChild(gridContainer);

        float gridH = gridContainer.getPreferredSize().y;
        maxScroll = Math.max(0, gridH - (app.getCamera().getHeight() + gridTopBase));
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        applyScroll();

        Container right = createButtonColumn(creations, vw, vh);
        right.setLocalTranslation(w - 30 * vw, -8 * vh, 3);
        view.attachChild(right);

        sizePopupToScreen();
    }

    @Override
    public void applyScroll() {
        if (gridContainer != null) {
            gridContainer.setLocalTranslation(gridLeftX, gridTopBase + scrollOffset, 2);
        }
    }

    @Override
    public float getMaxScroll() {
        return maxScroll;
    }

    @Override
    public boolean isGridVisible() {
        return gridContainer != null && gridContainer.getParent() != null;
    }

    @Override
    public float getScrollOffset() {
        return scrollOffset;
    }

    @Override
    public void setScrollOffset(float offset) {
        this.scrollOffset = offset;
    }

    @Override
    public float getScreenHeight() {
        return app.getCamera().getHeight();
    }

    @Override
    public void selectCard(String cardId) {
        if (!cardId.equals(selectedId)) {
            selectedId = cardId;
            app.enqueue(this::rebuildGrid);
        }
    }

    private Container createCard(Creation creation, float vw, float vh) {
        boolean selected = creation.getId().equals(selectedId);
        float border = 0.4f * vh;
        float pad = 1.2f * vh;
        float halfGap = 0.8f * vh;
        float contentW = 26 * vw;
        float previewH = 17 * vh;
        float captionH = 5.5f * vh;

        ColorRGBA frameColor = selected ? ColorRGBA.White : ColorRGBA.Black;
        Texture tex = WorldPreview.loadFromKey(app.getAssetManager(), CreationRepository.previewAssetKey(creation.getId()));
        String captionText = creation.getName() + "\n"
                + creation.getSizeX() + "x" + creation.getSizeY() + "x" + creation.getSizeZ();
        Container frame = CardGrid.buildCard(config, frameColor, border, pad, halfGap, contentW, previewH,
                captionH, tex, captionText, 2.2f * vh);
        CursorEventControl.addListenersToSpatial(frame, new CardGrid.ScrollListener(this, creation.getId()));
        return frame;
    }

    private Container createButtonColumn(List<Creation> creations, float vw, float vh) {
        Container buttons = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        int row = 0;

        // New creation : closes this popup and starts the creation-capture mode. Always available.
        Button create = menuButton("New creation", vw, vh);
        create.addClickCommands(source -> startNewCreation());
        buttons.addChild(create, row++, 0);

        Button build = menuButton("Build", vw, vh);
        if (selectedId == null) {
            build.setCullHint(Spatial.CullHint.Always);
        } else {
            build.addClickCommands(source -> buildSelected());
        }
        buttons.addChild(build, row++, 0);

        Button delete = menuButton("Delete", vw, vh);
        if (selectedId == null) {
            delete.setCullHint(Spatial.CullHint.Always);
        } else {
            delete.addClickCommands(source -> showDeleteConfirm(selectedId));
        }
        buttons.addChild(delete, row, 0);

        // Back / Close live at the top corners (see createPopup / layoutCornerButtons), like the other popups.
        return buttons;
    }

    private Button menuButton(String text, float vw, float vh) {
        Button button = new Button(text, IALON_STYLE);
        button.setFontSize(4 * vh);
        button.setPreferredSize(new Vector3f(26 * vw, 8 * vh, 0));
        button.setInsetsComponent(new InsetsComponent(3 * vh, 0, 0, 0));
        return button;
    }

    /** Closes the library and starts the creation-capture mode (owned by {@link CreationCaptureState}). */
    private void startNewCreation() {
        hidePopup();
        Optional.ofNullable(app.getStateManager().getState(CreationCaptureState.class))
                .ifPresent(CreationCaptureState::enter);
    }

    /** Loads the selected creation fully and hands off to the placement mode. */
    private void buildSelected() {
        if (selectedId == null) {
            return;
        }
        Creation creation = CreationRepository.load(config.getSavePath(), selectedId);
        if (creation == null || creation.getBlocks() == null) {
            log.warn("Could not load creation {}", selectedId);
            return;
        }
        hidePopup();
        Optional.ofNullable(app.getStateManager().getState(CreationPlacementState.class))
                .ifPresent(ps -> ps.enter(creation));
    }

    private void closeDeleteConfirm() {
        if (deleteOverlay != null) {
            deleteOverlay.removeFromParent();
            deleteOverlay = null;
        }
    }

    private void showDeleteConfirm(String id) {
        float vh = app.getCamera().getHeight() / 100f;
        float vw = app.getCamera().getWidth() / 100f;
        Vector3f screen = new Vector3f(app.getCamera().getWidth(), app.getCamera().getHeight(), 0);

        closeDeleteConfirm();
        Container overlay = new Container(IALON_STYLE);
        deleteOverlay = overlay;
        overlay.setLocalTranslation(0, 0, 100);
        UiHelper.addBackground(overlay, new ColorRGBA(0f, 0f, 0f, 0.95f), config);
        overlay.addMouseListener(new IgnoreMouseClickListener());

        Creation c = CreationRepository.loadMeta(config.getSavePath(), id);
        String name = c != null ? c.getName() : id;

        Container dialog = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        dialog.setInsetsComponent(new DynamicInsetsComponent(0.5f, 0.5f, 0.5f, 0.5f)); // centre in the overlay
        QuadBackgroundComponent dialogBg = new QuadBackgroundComponent(ColorRGBA.Black);
        dialogBg.setMargin(3 * vw, 2 * vh);
        dialogBg.getMaterial().getMaterial().clearParam(UiHelper.ALPHA_DISCARD_THRESHOLD);
        dialog.setBackground(dialogBg);

        Label title = new Label("Delete \"" + name + "\" ?", IALON_STYLE);
        title.setFontSize(4 * vh);
        title.setTextHAlignment(HAlignment.Center);
        title.setPreferredSize(new Vector3f(50 * vw, 8 * vh, 0));
        dialog.addChild(title, 0, 0);

        Container row = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        Button confirm = dialogButton("Delete", vw, vh);
        confirm.addClickCommands(source -> {
            CreationRepository.delete(config.getSavePath(), id);
            WorldPreview.dropFromCacheByKey(app.getAssetManager(), CreationRepository.previewAssetKey(id));
            try {
                java.nio.file.Files.deleteIfExists(CreationRepository.previewPath(config.getSavePath(), id));
            } catch (java.io.IOException e) {
                log.warn("Could not delete preview of {}: {}", id, e.getMessage());
            }
            if (id.equals(selectedId)) {
                selectedId = null;
            }
            closeDeleteConfirm();
            rebuildGrid();
        });
        row.addChild(confirm, 0, 0);
        Button cancel = dialogButton("Cancel", vw, vh);
        cancel.addClickCommands(source -> closeDeleteConfirm());
        row.addChild(cancel, 0, 1);
        dialog.addChild(row, 1, 0);

        overlay.addChild(dialog);
        popup.attachChild(overlay);
        // Force the full-screen size AFTER attaching + adding the dialog, otherwise the layout shrinks the
        // overlay to its content (leaving the dialog jammed top-left and the grid undimmed).
        overlay.setPreferredSize(screen);
        overlay.setSize(screen);
    }

    private Button dialogButton(String text, float vw, float vh) {
        Button button = new Button(text, IALON_STYLE);
        button.setFontSize(4 * vh);
        button.setPreferredSize(new Vector3f(25 * vw, 8 * vh, 0));
        button.setTextHAlignment(HAlignment.Center);
        button.setTextVAlignment(VAlignment.Center);
        return button;
    }

    private void sizePopupToScreen() {
        Vector3f screen = new Vector3f(app.getCamera().getWidth(), app.getCamera().getHeight(), 0);
        Panel panel = (Panel) popup;
        panel.setPreferredSize(screen);
        panel.setSize(screen);
        panel.setLocalTranslation(0, app.getCamera().getHeight(), 100);
    }

    @Override
    public void onResize(int width, int height) {
        if (popup.getParent() != null) {
            sizePopupToScreen();
            rebuildGrid();
            layoutCornerButtons();
        }
    }

    private class IgnoreMouseClickListener extends DefaultMouseListener {
        @Override
        public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
            event.setConsumed();
        }
    }

}
