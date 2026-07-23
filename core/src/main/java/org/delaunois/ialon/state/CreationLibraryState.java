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
import com.simsilica.lemur.component.SpringGridLayout;
import com.simsilica.lemur.event.CursorEventControl;
import com.simsilica.lemur.event.DefaultMouseListener;
import com.jme3.input.event.MouseButtonEvent;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.blocks.BlockRegistry;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.serialize.Creation;
import org.delaunois.ialon.serialize.CreationRepository;
import org.delaunois.ialon.ui.IalonTheme;
import org.delaunois.ialon.ui.UiHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

/**
 * The creation library : a modal popup, modelled on the worlds menu, listing the saved {@link Creation}s
 * as selectable thumbnail cards. Selecting a card then pressing "Build" closes the popup and starts the
 * placement mode ({@link CreationPlacementState}) to stamp the creation into the current world.
 *
 * <p>"Terrain" opens a checkbox dialog listing the block types the selected creation contains (the natural
 * ground ones — grass, dirt, rock, sand… — first and pre-checked) and strips the checked ones from it : a
 * creation captured as a box always drags in the ground around and under the structure, which would
 * otherwise overwrite the ground it is later placed on. See {@link CreationBlockFilter}.</p>
 *
 * <p>Opened from the worlds menu ("Creations" button) via {@link #showPopup()} ; it owns no top-of-screen
 * button of its own.</p>
 *
 * @author Cedric de Launois
 */
@Slf4j
public class CreationLibraryState extends BaseAppState implements Resizable, CardGrid.ScrollableGrid, Popup {

    /** Shown on the popup's title signboard (part of the chrome — see {@link IalonTheme#applyChromeOver}). */
    private static final String TITLE = "Creations";

    // Layout of the block-removal dialog's checkbox rows : they are stacked in columns of
    // STRIP_PREFERRED_ROWS rows, never more than STRIP_PREFERRED_COLUMNS of them. The dialog therefore
    // offers at most STRIP_MAX_TYPES block types (the most frequent ones), which at full load is a
    // 3 x 10 grid — about 71% of the screen height, title and buttons included.
    private static final int STRIP_PREFERRED_COLUMNS = 3;
    private static final int STRIP_PREFERRED_ROWS = 8;
    private static final int STRIP_MAX_TYPES = 30;
    private static final float STRIP_ROW_WIDTH_VW = 24f;

    private final IalonConfig config;
    // Types checked in the block-removal dialog, in display order (see showStripDialog).
    private final Map<String, Boolean> stripChecked = new LinkedHashMap<>();

    private SimpleApplication app;
    private Node popup;
    private Node view;
    private Container gridContainer;
    private Container deleteOverlay;
    private Container stripOverlay;
    // The fully loaded creation the open block-removal dialog works on (its grid is edited in place).
    private Creation stripCreation;
    // Header buttons at the popup's top corners (like the settings / new-world popups) : Back (left)
    // returns to the worlds menu, Close (right) returns to the game.
    private Button cornerBack;
    private Button cornerClose;

    private String selectedId;
    private float gridLeftX;
    private float gridTopBase;
    private float scrollOffset;
    private float maxScroll;
    // The grid's fling : one per view, shared by every card's scroll listener (see ScrollInertia).
    private final ScrollInertia inertia = new ScrollInertia(this);

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
        IalonTheme.applyVeil(container, app.getAssetManager(), config);
        CursorEventControl.addListenersToSpatial(container, new CardGrid.ScrollListener(this, null));
        view = new Node("creationLibraryView");

        // Header buttons at the top corners, exactly like the settings / new-world popups. They are styled
        // now (not only in layoutCornerButtons) so their width is known when rebuildGrid offsets the grid
        // to the right of the Back button.
        float height = app.getCamera().getHeight();
        cornerBack = IalonTheme.cornerButton("Back", app.getAssetManager(), height);
        cornerBack.addClickCommands(source -> backToWorldMenu());
        container.attachChild(cornerBack);

        cornerClose = IalonTheme.cornerButton("Close", app.getAssetManager(), height);
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

    @Override
    public boolean isPopupOpen() {
        return popup != null && popup.getParent() != null;
    }

    /**
     * Full screen, opaque veil : nothing of the world shows through, so it need not be drawn. The creation
     * thumbnails are unaffected — {@link CreationPreview} renders its own scene in its own viewport, not
     * the world's root node.
     */
    @Override
    public boolean hidesWorld() {
        return true;
    }

    @Override
    public void closePopup() {
        hidePopup();
    }

    /** Closes the library and reopens the worlds menu (owned by {@link WorldMenuState}). */
    private void backToWorldMenu() {
        hidePopup();
        Optional.ofNullable(app.getStateManager().getState(WorldMenuState.class))
                .ifPresent(WorldMenuState::showPopup);
    }

    /**
     * Restyles (no fixed width, so each button hugs its text) and places the corner buttons : Back flush to
     * the left edge, Close flush to the right edge, both at the screen margin (see {@link UiHelper}). The
     * restyle rebuilds each plate, which a resolution change requires — the chrome's scale is baked into
     * its mesh.
     */
    private void layoutCornerButtons() {
        float height = app.getCamera().getHeight();
        restyleCornerButton(cornerBack, height);
        restyleCornerButton(cornerClose, height);
        UiHelper.placeCornerButtons(cornerBack, cornerClose, app.getCamera().getWidth(), height);
    }

    private void restyleCornerButton(Button button, float height) {
        IalonTheme.restyleCornerButton(button, app.getAssetManager(), height);
    }

    public void hidePopup() {
        if (popup.getParent() != null) {
            IalonTheme.removeChromeFrom((Node) popup.getParent());
            popup.removeFromParent();
            setPlayerTouchEnabled(true);
        }
        closeDeleteConfirm();
        closeStripDialog();
    }

    private void setPlayerTouchEnabled(boolean enabled) {
        Optional.ofNullable(app.getStateManager().getState(PlayerState.class))
                .ifPresent(ps -> ps.setTouchEnabled(enabled));
    }

    private void rebuildGrid() {
        if (view.getParent() == null) {
            popup.attachChild(view);
        }
        // The cards this grid is made of are about to be replaced : end any glide over the old ones.
        inertia.stop();
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
        // Start the first row clear of the title signboard : the chrome that carries it is drawn OVER the
        // grid, so a row any higher would be painted over rather than merely cramped.
        gridTopBase = -IalonTheme.contentTopOffset(app.getCamera().getHeight());
        // The cards are built first so CardGrid can measure one of them and fit as many columns as the
        // space left before the right button column really allows — see CardGrid.paveGrid.
        float rightColumnX = w - 30 * vw;
        float band = rightColumnX - gridLeftX - 2 * vw;
        int cols = CardGrid.columnCount(band, vw);
        float cardWidth = CardGrid.cardWidth(band, cols);
        List<Container> cards = new ArrayList<>(creations.size());
        for (Creation creation : creations) {
            cards.add(createCard(creation, cardWidth, vh));
        }
        gridContainer = CardGrid.paveGrid(cards, cols, band);
        if (creations.isEmpty()) {
            Label empty = new Label("No creation yet.\nUse the world menu : New creation.", IALON_STYLE);
            empty.setFontSize(3 * vh);
            gridContainer.addChild(empty, 0, 0);
        }
        CursorEventControl.addListenersToSpatial(gridContainer, new CardGrid.ScrollListener(this, null));
        view.attachChild(gridContainer);

        float gridH = gridContainer.getPreferredSize().y;
        maxScroll = CardGrid.maxScroll(gridH, app.getCamera().getHeight(), gridTopBase);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        applyScroll();

        Container right = createButtonColumn(creations, vw, vh);
        // Dropped one menu row below the popup's top margin : at the previous offset the first entry ran
        // under the Close button in the top-right corner, which spans the same columns as this menu.
        right.setLocalTranslation(w - 30 * vw, -8 * vh - IalonTheme.menuRowHeight(app.getCamera().getHeight()), 3);
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

    @Override
    public ScrollInertia getInertia() {
        return inertia;
    }

    /** Builds one card at the width the grid allotted it — see {@code WorldMenuState.createCard}. */
    private Container createCard(Creation creation, float cardWidth, float vh) {
        boolean selected = creation.getId().equals(selectedId);
        // No "current" state here (a creation is never loaded the way a world is), so only two of the
        // three plates are used : the tapped card wears the gold one, like the selected world does, the
        // others the dull steel. The bronze plate belongs to the worlds grid alone.
        IalonTheme.Frame plate = selected ? IalonTheme.Frame.PLATE_GOLD : IalonTheme.Frame.PLATE_SILVER;
        float halfGap = 0.8f * vh;
        float contentW = cardWidth - 2 * halfGap
                - plate.horizontalChrome(app.getCamera().getHeight());
        float previewH = 17 * vh;
        float captionH = 5.5f * vh;

        Texture tex = WorldPreview.loadFromKey(app.getAssetManager(), CreationRepository.previewAssetKey(creation.getId()));
        String captionText = creation.getName() + "\n"
                + creation.getSizeX() + "x" + creation.getSizeY() + "x" + creation.getSizeZ();
        Container frame = CardGrid.buildCard(app.getAssetManager(), app.getCamera().getHeight(),
                plate, halfGap, contentW, previewH,
                captionH, tex, captionText, 3.5f * vh);
        CursorEventControl.addListenersToSpatial(frame, new CardGrid.ScrollListener(this, creation.getId()));
        return frame;
    }

    private Container createButtonColumn(List<Creation> creations, float vw, float vh) {
        Container buttons = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        int row = 0;

        // New creation : closes this popup and starts the creation-capture mode. Always available.
        Button create = menuButton("New creation");
        create.addClickCommands(source -> startNewCreation());
        buttons.addChild(create, row++, 0);

        Button build = menuButton("Build");
        if (selectedId == null) {
            build.setCullHint(Spatial.CullHint.Always);
        } else {
            build.addClickCommands(source -> buildSelected());
        }
        buttons.addChild(build, row++, 0);

        // Edit : strips the checked block types (natural ground pre-checked) from the creation.
        Button edit = menuButton("Edit");
        if (selectedId == null) {
            edit.setCullHint(Spatial.CullHint.Always);
        } else {
            edit.addClickCommands(source -> showStripDialog(selectedId));
        }
        buttons.addChild(edit, row++, 0);

        Button delete = menuButton("Delete");
        if (selectedId == null) {
            delete.setCullHint(Spatial.CullHint.Always);
        } else {
            delete.addClickCommands(source -> showDeleteConfirm(selectedId));
        }
        buttons.addChild(delete, row, 0);

        // Back / Close live at the top corners (see createPopup / layoutCornerButtons), like the other popups.
        return buttons;
    }

    private Button menuButton(String text) {
        return IalonTheme.menuButton(text, app.getAssetManager(),
                app.getCamera().getWidth(), app.getCamera().getHeight());
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
        closeDeleteConfirm();
        Container overlay = createModalOverlay();
        deleteOverlay = overlay;

        Creation c = CreationRepository.loadMeta(config.getSavePath(), id);
        String name = c != null ? c.getName() : id;

        Container dialog = createDialogBox();
        dialog.addChild(dialogTitle("Delete \"" + name + "\" ?"), 0, 0);

        Button confirm = dialogButton("Delete");
        confirm.addClickCommands(source -> {
            CreationRepository.delete(config.getSavePath(), id);
            dropPreview(id);
            if (id.equals(selectedId)) {
                selectedId = null;
            }
            closeDeleteConfirm();
            rebuildGrid();
        });
        Button cancel = dialogButton("Cancel");
        cancel.addClickCommands(source -> closeDeleteConfirm());
        dialog.addChild(IalonTheme.dialogButtonRow(app.getCamera().getHeight(), confirm, cancel), 1, 0);

        showOverlay(overlay, dialog);
    }

    // --- Block removal ("Edit") -------------------------------------------------------------------

    private void closeStripDialog() {
        if (stripOverlay != null) {
            stripOverlay.removeFromParent();
            stripOverlay = null;
        }
        stripCreation = null;
        stripChecked.clear();
    }

    /**
     * Opens the block-removal dialog for a creation : one checkbox row per block type it contains, capped
     * at the {@value #STRIP_MAX_TYPES} most frequent ones, the natural-ground types first (see
     * {@link CreationBlockFilter#typesPresent}). Nothing is checked to start with, so an accidental
     * "Remove" is a no-op. "Remove" empties the checked cells and re-saves the creation.
     */
    private void showStripDialog(String id) {
        Creation creation = CreationRepository.load(config.getSavePath(), id);
        if (creation == null || creation.getBlocks() == null) {
            log.warn("Could not load creation {}", id);
            return;
        }
        float vh = app.getCamera().getHeight() / 100f;
        float vw = app.getCamera().getWidth() / 100f;

        closeStripDialog();
        stripCreation = creation;
        BlockRegistry registry = BlocksConfig.getInstance().getBlockRegistry();
        List<CreationBlockFilter.TypeEntry> types =
                CreationBlockFilter.typesPresent(creation.getBlocks(), registry, STRIP_MAX_TYPES);

        Container overlay = createModalOverlay();
        stripOverlay = overlay;

        Container dialog = createDialogBox();

        // Columns of at most STRIP_PREFERRED_ROWS rows, but never more than STRIP_PREFERRED_COLUMNS of them :
        // past that the rows pile up, which the STRIP_MAX_TYPES cap keeps within the screen height.
        int columns = Math.max(1, Math.min(STRIP_PREFERRED_COLUMNS,
                ceilDiv(types.size(), STRIP_PREFERRED_ROWS)));
        int rowsPerColumn = ceilDiv(types.size(), columns);

        Container rows = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        if (types.isEmpty()) {
            Label empty = new Label("This creation holds no block.", IALON_STYLE);
            empty.setFontSize(3 * vh);
            rows.addChild(empty, 0, 0);
        } else {
            for (int i = 0; i < types.size(); i++) {
                rows.addChild(checkRow(types.get(i), vw, vh), i % rowsPerColumn, i / rowsPerColumn);
            }
        }
        dialog.addChild(rows, 1, 0);

        // The checkbox columns set the dialog's width : the title and the two buttons are stretched to it,
        // otherwise their (narrower) fixed size leaves them off-centre in the box.
        float dialogW = Math.max(50 * vw, columns * STRIP_ROW_WIDTH_VW * vw);
        Label title = dialogTitle("Remove blocks from \"" + creation.getName() + "\"");
        title.setPreferredSize(new Vector3f(dialogW, 8 * vh, 0));
        dialog.addChild(title, 0, 0);

        // Half the dialog width each : the row helper takes the gap between them out of these widths, so
        // the row still spans exactly the dialog.
        Button confirm = dialogButton("Remove");
        confirm.setPreferredSize(new Vector3f(dialogW / 2f, 8 * vh, 0));
        confirm.addClickCommands(source -> applyStrip(registry));
        Button cancel = dialogButton("Cancel");
        cancel.setPreferredSize(new Vector3f(dialogW / 2f, 8 * vh, 0));
        cancel.addClickCommands(source -> closeStripDialog());
        dialog.addChild(IalonTheme.dialogButtonRow(app.getCamera().getHeight(), confirm, cancel), 2, 0);

        showOverlay(overlay, dialog);
    }

    /**
     * One checkbox row : a full-width button carrying its own "[X]"/"[ ]" box, so the whole row is an
     * obvious touch target whatever its state (same reasoning as {@link SettingsToggle}). The font is
     * ASCII-only, hence the bracket box rather than a check mark glyph.
     */
    private Button checkRow(CreationBlockFilter.TypeEntry type, float vw, float vh) {
        String label = type.getType() + " (" + type.getCount() + ")";
        // Unchecked to start with : stripChecked holds no entry for the type until the player taps the row.
        Button row = new Button(checkRowText(label, false), IALON_STYLE);
        row.setFontSize(3 * vh);
        row.setPreferredSize(new Vector3f(STRIP_ROW_WIDTH_VW * vw, 5.5f * vh, 0));
        row.setTextHAlignment(HAlignment.Left);
        row.setTextVAlignment(VAlignment.Center);
        row.addClickCommands(source -> {
            boolean checked = !Boolean.TRUE.equals(stripChecked.get(type.getType()));
            stripChecked.put(type.getType(), checked);
            row.setText(checkRowText(label, checked));
        });
        return row;
    }

    private static String checkRowText(String label, boolean checked) {
        return (checked ? "[X] " : "[ ] ") + label;
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    /**
     * Empties every cell of the checked types, flags the creation so its air cells no longer dig into the
     * ground it is placed on, saves it and re-renders its thumbnail (the grid is rebuilt once the new PNG
     * is on disk, so the card shows the stripped creation right away).
     */
    private void applyStrip(BlockRegistry registry) {
        Creation creation = stripCreation;
        if (creation == null) {
            closeStripDialog();
            return;
        }
        Set<String> types = new HashSet<>();
        for (Map.Entry<String, Boolean> checked : stripChecked.entrySet()) {
            if (Boolean.TRUE.equals(checked.getValue())) {
                types.add(checked.getKey());
            }
        }
        int removed = CreationBlockFilter.strip(creation.getBlocks(), types, registry);
        closeStripDialog();
        if (removed == 0) {
            return;
        }

        creation.setSkipAir(true);
        CreationRepository.save(config.getSavePath(), creation);
        log.info("Removed {} cells of {} from creation '{}'", removed, types, creation.getName());
        rerenderPreview(creation);
    }

    /** Re-renders a creation's thumbnail after a strip, then rebuilds the grid to show it. */
    private void rerenderPreview(Creation creation) {
        Path preview = CreationRepository.previewPath(config.getSavePath(), creation.getId());
        Node mesh = CreationMesh.build(creation.getBlocks(),
                creation.getSizeX(), creation.getSizeY(), creation.getSizeZ());
        if (mesh == null) {
            // Everything was removed : no thumbnail to render, the card falls back to an empty preview.
            dropPreview(creation.getId());
            rebuildGrid();
            return;
        }
        float scale = BlocksConfig.getInstance().getBlockScale();
        CreationPreview.render(app, mesh, new Vector3f(creation.getSizeX() * scale,
                creation.getSizeY() * scale, creation.getSizeZ() * scale), preview, () -> {
            // The stale texture must be dropped from the cache before the card reloads it by key.
            WorldPreview.dropFromCacheByKey(app.getAssetManager(),
                    CreationRepository.previewAssetKey(creation.getId()));
            rebuildGrid();
        });
    }

    // --- Dialog plumbing ----------------------------------------------------------------------------

    /** Deletes a creation's thumbnail, on disk and from the asset cache. */
    private void dropPreview(String id) {
        WorldPreview.dropFromCacheByKey(app.getAssetManager(), CreationRepository.previewAssetKey(id));
        try {
            Files.deleteIfExists(CreationRepository.previewPath(config.getSavePath(), id));
        } catch (IOException e) {
            log.warn("Could not delete preview of {}: {}", id, e.getMessage());
        }
    }

    /**
     * A full-screen invisible container, swallowing clicks so the grid below stays inert while showing
     * through around the dialog.
     */
    private Container createModalOverlay() {
        Container overlay = new Container(IALON_STYLE);
        overlay.setLocalTranslation(0, 0, 100);
        IalonTheme.applyClearVeil(overlay, config);
        overlay.addMouseListener(new IgnoreMouseClickListener());
        return overlay;
    }

    private Container createDialogBox() {
        return IalonTheme.dialogBox(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None),
                app.getAssetManager(), app.getCamera().getHeight());
    }

    private Label dialogTitle(String text) {
        return IalonTheme.dialogTitle(text, app.getCamera().getWidth(), app.getCamera().getHeight());
    }

    private void showOverlay(Container overlay, Container dialog) {
        overlay.addChild(dialog);
        popup.attachChild(overlay);
        // Force the full-screen size AFTER attaching + adding the dialog, otherwise the layout shrinks the
        // overlay to its content : the dialog ends up jammed top-left, and the veil no longer spans the
        // screen, so clicks outside it reach the grid it is meant to keep inert.
        Vector3f screen = new Vector3f(app.getCamera().getWidth(), app.getCamera().getHeight(), 0);
        overlay.setPreferredSize(screen);
        overlay.setSize(screen);
    }

    private Button dialogButton(String text) {
        return IalonTheme.dialogButton(text, app.getAssetManager(),
                app.getCamera().getWidth(), app.getCamera().getHeight());
    }

    private void sizePopupToScreen() {
        float width = app.getCamera().getWidth();
        float height = app.getCamera().getHeight();
        Vector3f screen = new Vector3f(width, height, 0);
        Panel panel = (Panel) popup;
        panel.setPreferredSize(screen);
        panel.setSize(screen);
        panel.setLocalTranslation(0, height, 100);
        // Rebuilt rather than reused : a nine-patch bakes its scale into its mesh.
        // Chrome laid OVER the popup (a sibling node), so the scrolling card grid slides under the tube.
        // It carries the title signboard too.
        IalonTheme.applyChromeOver(popup, app.getAssetManager(), width, height, TITLE);
    }

    @Override
    public void onResize(int width, int height) {
        if (popup.getParent() != null) {
            sizePopupToScreen();
            rebuildGrid();
            layoutCornerButtons();
        }
    }

    private static class IgnoreMouseClickListener extends DefaultMouseListener {
        @Override
        public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
            event.setConsumed();
        }
    }

}
