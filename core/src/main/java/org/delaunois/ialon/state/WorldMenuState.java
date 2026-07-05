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
import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_SWITCH_MOUSELOCK;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.event.MouseButtonEvent;
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

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.serialize.WorldParams;
import org.delaunois.ialon.serialize.WorldRepository;
import org.delaunois.ialon.ui.UiHelper;
import org.delaunois.ialon.ui.UiHelper.IconButton;

import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;

/**
 * UI to list, create and switch worlds. A small icon button (top of the screen, next to the settings
 * gear) opens a modal popup listing the existing worlds (each can be played or deleted) plus a "New
 * world" form with high-level generation sliders (seed, water height, relief, tree density, woods
 * size). Playing or creating a world hands off to {@link WorldSelectionState} for the actual runtime
 * switch.
 *
 * @author Cedric de Launois
 */
@Slf4j
public class WorldMenuState extends BaseAppState implements ActionListener, Resizable, CardGrid.ScrollableGrid {

    private static final float SPACING = 10;
    private static final int MAX_WORLDS = 20;

    private final Random random = new Random();
    private SimpleApplication app;
    private IconButton button;
    private int buttonSize;
    private Node popup;
    private Container content;
    // Create-form header buttons, shown at the popup's top corners only while the create form is open.
    private Button createBack;
    private Button createClose;
    // Worlds-grid header buttons, at the popup's top corners (like the other popups). Both return to the
    // game : this is the top-level menu, so "Back" has nowhere to go up to and just closes, like "Close".
    private Button worldsBack;
    private Button worldsClose;

    private final IalonConfig config;
    // SettingsValue sliders shown on the create form, polled each frame so their value labels track.
    private final List<SettingsValue> activeValues = new ArrayList<>();
    // The world card currently selected ; the right-hand Load/Delete buttons act on it.
    private String selectedWorldId;

    // Worlds view (absolutely positioned, swipe-scrollable). The grid spans the full screen height so
    // rows scrolled past the top/bottom fall outside the GUI camera and are clipped by the window ; the
    // button column stays fixed on the right. content (the centred Lemur container) is used only by the
    // create form, attached/detached as views switch.
    private Node worldsView;
    private Container gridContainer;
    private float gridLeftX;
    private float gridTopBase; // resting Y of the grid top (relative to popup top), i.e. a top margin
    private float scrollOffset;
    private float maxScroll;
    private Container deleteOverlay; // modal delete-confirmation overlay, when shown

    public WorldMenuState(IalonConfig config) {
        this.config = config;
    }

    @Override
    public void initialize(Application application) {
        this.app = (SimpleApplication) application;
        buttonSize = app.getCamera().getHeight() / 12;
        button = createMenuButton();
        popup = createPopup();
        layout(app.getCamera().getWidth(), app.getCamera().getHeight());

        if (app.getStateManager().getState(ScreenState.class) != null) {
            app.getStateManager().getState(ScreenState.class).register(this);
        }

        // Allow loading world preview PNGs that live under the (non-classpath) save directory.
        try {
            Files.createDirectories(config.getSavePath());
            app.getAssetManager().registerLocator(
                    config.getSavePath().toAbsolutePath().toString(), FileLocator.class);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not register save dir locator for world previews: {}", e.getMessage());
        }

        app.getInputManager().addListener(this, ACTION_SWITCH_MOUSELOCK);
    }

    private IconButton createMenuButton() {
        // The single top-of-screen button : the gear icon opens this worlds menu (which now also hosts
        // the global settings, reached via the "Settings" button in the menu).
        IconButton iconButton = UiHelper.createTextureButton(config, "gear.png", buttonSize, 0, 0);
        iconButton.background.addMouseListener(new TogglePopupMouseClickListener());
        return iconButton;
    }

    private Node createPopup() {
        Container worldPopup = new Container(IALON_STYLE);
        worldPopup.setLocalTranslation(0, app.getCamera().getHeight(), 100);
        worldPopup.setName("worldPopup");
        worldPopup.setPreferredSize(new Vector3f(app.getCamera().getWidth(), app.getCamera().getHeight(), 0));
        UiHelper.addBackground(worldPopup, new ColorRGBA(0f, 0f, 0f, 0.95f), config);
        // A full-screen scroll listener : it consumes clicks (so they don't reach the game, and the popup
        // does NOT close — closing is explicit via Close) and, since the popup spans the whole screen and
        // sits behind the grid, it catches wheel/drag scrolling anywhere not handled by a card.
        CursorEventControl.addListenersToSpatial(worldPopup, new CardGrid.ScrollListener(this, null));

        // content (centred) is attached lazily, only for the create form. The worlds grid uses worldsView.
        // Horizontally centred ; vertically anchored to a fixed top margin (top inset 0, slack to bottom)
        // so the title sits at the same height as the settings popup regardless of content height.
        content = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        content.setInsetsComponent(new DynamicInsetsComponent(0, 50, 50, 50));
        content.addMouseListener(new IgnoreMouseClickListener());

        worldsView = new Node("worldsView");
        return worldPopup;
    }

    public void togglePopup() {
        if (popup.getParent() == null) {
            showPopup();
        } else {
            hidePopup();
        }
    }

    public void showPopup() {
        if (popup.getParent() != null) {
            return;
        }
        selectedWorldId = config.getWorldId();
        // Capture a screenshot of the current world (clean 3D frame, popup not yet shown) only if it has
        // none yet ; otherwise open the menu straight away. The current world's preview can be refreshed
        // on demand with the "Update preview" button. capture() always invokes the continuation.
        java.nio.file.Path preview = currentPreviewPath();
        if (Files.exists(preview)) {
            reallyShowPopup();
        } else {
            WorldPreview.capture(app, preview, this::reallyShowPopup);
        }
    }

    private java.nio.file.Path currentPreviewPath() {
        return WorldRepository.worldDir(config.getSavePath(), config.getWorldId())
                .resolve(WorldPreview.PREVIEW_FILENAME);
    }

    /** Re-captures the current world's preview : hide the menu, capture the clean frame, reopen. */
    private void recaptureCurrentPreview() {
        java.nio.file.Path preview = currentPreviewPath();
        String worldId = config.getWorldId();
        hidePopup();
        WorldPreview.capture(app, preview, () -> {
            // Drop the cached (now stale) texture so the freshly captured image is reloaded.
            WorldPreview.dropFromCache(app.getAssetManager(), worldId);
            reallyShowPopup();
        });
    }

    private void reallyShowPopup() {
        if (popup.getParent() != null) {
            return;
        }
        // Tapping the gear cancels an in-progress creation capture or placement (restores the normal UI).
        // This is the cancel affordance on mobile, where there is no right-click.
        Optional.ofNullable(app.getStateManager().getState(CreationCaptureState.class))
                .ifPresent(CreationCaptureState::cancel);
        Optional.ofNullable(app.getStateManager().getState(CreationPlacementState.class))
                .ifPresent(CreationPlacementState::cancel);
        rebuildGrid();
        app.getGuiNode().attachChild(popup);
        sizePopupToScreen();
        setPlayerTouchEnabled(false);
    }

    /**
     * Forces the popup (the dark overlay panel) to the full screen size so its width does not shrink to
     * fit its content. Re-applied after each content rebuild.
     */
    private void sizePopupToScreen() {
        Vector3f screen = new Vector3f(app.getCamera().getWidth(), app.getCamera().getHeight(), 0);
        Panel panel = (Panel) popup;
        panel.setPreferredSize(screen);
        panel.setSize(screen);
        panel.setLocalTranslation(0, app.getCamera().getHeight(), 100);
    }

    public void hidePopup() {
        if (popup.getParent() != null) {
            popup.removeFromParent();
            setPlayerTouchEnabled(true);
        }
        activeValues.clear();
        closeDeleteConfirm();
    }

    /** Closes the worlds menu and opens the creation library (owned by {@link CreationLibraryState}). */
    private void openLibrary() {
        hidePopup();
        Optional.ofNullable(app.getStateManager().getState(CreationLibraryState.class))
                .ifPresent(CreationLibraryState::showPopup);
    }

    /** Closes the worlds menu and opens the global settings popup (owned by {@link SettingsState}). */
    private void openSettings() {
        hidePopup();
        Optional.ofNullable(app.getStateManager().getState(SettingsState.class))
                .ifPresent(SettingsState::showPopup);
    }

    /** Closes the worlds menu and enters photo mode (owned by {@link PhotoModeState}). */
    private void openPhotoMode() {
        hidePopup();
        Optional.ofNullable(app.getStateManager().getState(PhotoModeState.class))
                .ifPresent(PhotoModeState::enter);
    }

    private void closeDeleteConfirm() {
        if (deleteOverlay != null) {
            deleteOverlay.removeFromParent();
            deleteOverlay = null;
        }
    }

    private void setPlayerTouchEnabled(boolean enabled) {
        Optional.ofNullable(app.getStateManager().getState(PlayerState.class))
                .ifPresent(ps -> ps.setTouchEnabled(enabled));
    }

    private void clearContent() {
        activeValues.clear();
        List<Spatial> children = new ArrayList<>(content.getChildren());
        children.forEach(content::detachChild);
    }

    /**
     * Builds the worlds view : a swipe-scrollable grid of cards on the left (paving left-to-right then
     * top-to-bottom) plus a fixed column of action buttons (Load / Delete-or-Update / New) on the right.
     * The grid spans the full screen height ; rows scrolled past the top or bottom edge are clipped by
     * the GUI camera, so no explicit clipping is needed.
     */
    private void rebuildGrid() {
        showWorldsView();
        float vh = app.getCamera().getHeight() / 100f;
        float vw = app.getCamera().getWidth() / 100f;
        float w = app.getCamera().getWidth();

        worldsView.detachAllChildren();

        List<WorldParams> worlds = WorldRepository.listWorlds(config.getSavePath());
        if (worlds.stream().noneMatch(wp -> wp.getId().equals(selectedWorldId))) {
            selectedWorldId = config.getWorldId();
        }

        // Card grid. Lemur has no wrapping layout, so pave row = i / cols, col = i % cols. Offset the grid
        // to the RIGHT of the top-left Back button (its measured width + a gap) so the cards never share
        // its column ; columns are then sized to the space left up to the right button column.
        float margin = UiHelper.screenMargin(app.getCamera().getHeight());
        gridLeftX = margin + worldsBack.getPreferredSize().x + 3 * vw;
        gridTopBase = -6 * vh; // start the first row a bit below the top of the screen
        float cardFootprintW = 26 * vw + 4.8f * vh; // contentW + 2*(pad+border+halfGap), see createCard
        float rightColumnX = w - 30 * vw;
        int cols = Math.max(1, (int) ((rightColumnX - gridLeftX - 2 * vw) / cardFootprintW));
        gridContainer = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        for (int i = 0; i < worlds.size(); i++) {
            gridContainer.addChild(createCard(worlds.get(i), vw, vh), i / cols, i % cols);
        }
        // Catch drags that start on the gaps between cards (cards catch their own).
        CursorEventControl.addListenersToSpatial(gridContainer, new CardGrid.ScrollListener(this, null));
        worldsView.attachChild(gridContainer);

        // Visible band runs from the top margin down to the screen bottom ; the scroll range is the
        // overflow beyond it.
        float gridH = gridContainer.getPreferredSize().y;
        maxScroll = Math.max(0, gridH - (app.getCamera().getHeight() + gridTopBase));
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        applyScroll();

        // Fixed button column on the right.
        Container right = createButtonColumn(worlds, vw, vh);
        right.setLocalTranslation(w - 30 * vw, -8 * vh, 3);
        worldsView.attachChild(right);

        sizePopupToScreen();
    }

    /** Slides the grid vertically by the current scroll offset (clamped). */
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
        if (!cardId.equals(selectedWorldId)) {
            selectedWorldId = cardId;
            app.enqueue(this::rebuildGrid);
        }
    }

    /** Shows the worlds view (worldsView attached, create-form content + its corner buttons detached). */
    private void showWorldsView() {
        if (content.getParent() != null) {
            ((Container) popup).removeChild(content);
        }
        detachCreateFormCornerButtons();
        if (worldsView.getParent() == null) {
            popup.attachChild(worldsView);
        }
        showWorldsViewCornerButtons();
    }

    /**
     * Shows the worlds-grid header buttons at the popup's top corners (like the settings / library / create
     * popups) : Back (left) and Close (right) both return to the game (the worlds menu is the top level).
     * Created lazily ; their font is set here so their width is known when {@link #rebuildGrid} offsets the
     * grid to the right of the Back button.
     */
    private void showWorldsViewCornerButtons() {
        float vh = app.getCamera().getHeight() / 100f;
        if (worldsBack == null) {
            worldsBack = new Button("Back", IALON_STYLE);
            worldsBack.setTextVAlignment(VAlignment.Center);
            worldsBack.addClickCommands(source -> hidePopup());
            worldsClose = new Button("Close", IALON_STYLE);
            worldsClose.setTextVAlignment(VAlignment.Center);
            worldsClose.addClickCommands(source -> hidePopup());
        }
        sizeCornerButton(worldsBack, vh);
        sizeCornerButton(worldsClose, vh);
        popup.attachChild(worldsBack);
        popup.attachChild(worldsClose);
        UiHelper.placeCornerButtons(worldsBack, worldsClose,
                app.getCamera().getWidth(), app.getCamera().getHeight());
    }

    private void detachWorldsViewCornerButtons() {
        if (worldsBack != null) {
            worldsBack.removeFromParent();
            worldsClose.removeFromParent();
        }
    }

    private Container createCard(WorldParams world, float vw, float vh) {
        boolean selected = world.getId().equals(selectedWorldId);
        boolean current = world.getId().equals(config.getWorldId());
        float border = 0.4f * vh;   // white frame thickness
        float pad = 1.2f * vh;      // black mat between the frame and the content
        float halfGap = 0.8f * vh;  // half the gap between adjacent cards
        float contentW = 26 * vw;
        float previewH = 17 * vh;
        float captionH = 5.5f * vh;

        // Currently loaded world : light-blue frame ; selected (but not loaded) : white frame ; others :
        // black frame (blends with the black mat, i.e. no frame).
        ColorRGBA frameColor = current ? new ColorRGBA(0.4f, 0.7f, 1f, 1f)
                : (selected ? ColorRGBA.White : ColorRGBA.Black);
        Texture tex = WorldPreview.load(app.getAssetManager(), world.getId());
        String captionText = world.getName() + "\n" + formatDate(world.getId());
        Container frame = CardGrid.buildCard(config, frameColor, border, pad, halfGap, contentW, previewH,
                captionH, tex, captionText, 2.2f * vh);
        CursorEventControl.addListenersToSpatial(frame, new CardGrid.ScrollListener(this, world.getId()));
        return frame;
    }

    private Container createButtonColumn(List<WorldParams> worlds, float vw, float vh) {
        boolean selIsCurrent = selectedWorldId.equals(config.getWorldId());

        Container buttons = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        int row = 0;

        // The world already loaded has nothing to load : the Load button is still created (so its row is
        // reserved and the buttons below it keep their position), but hidden -- no command, not rendered.
        Button load = menuButton("Load", vw, vh);
        if (selIsCurrent) {
            load.setCullHint(Spatial.CullHint.Always);
        } else {
            load.addClickCommands(source ->
                    // Keep the popup up : WorldSelectionState closes it once the loading screen is shown, so the
                    // in-game buttons stay blocked until then.
                    Optional.ofNullable(app.getStateManager().getState(WorldSelectionState.class))
                            .ifPresent(wss -> wss.switchTo(selectedWorldId)));
        }
        buttons.addChild(load, row++, 0);

        if (selIsCurrent) {
            // For the current world : refresh its screenshot (in place of Delete, which is not allowed).
            Button replace = menuButton("Update preview", vw, vh);
            replace.addClickCommands(source -> recaptureCurrentPreview());
            buttons.addChild(replace, row++, 0);
        } else if (worlds.size() > 1) {
            // Delete : not the current world, and not the last remaining one. Asks for confirmation.
            Button delete = menuButton("Delete", vw, vh);
            delete.addClickCommands(source -> showDeleteConfirm(selectedWorldId));
            buttons.addChild(delete, row++, 0);
        }

        // New world : hidden once the world limit is reached.
        if (worlds.size() < MAX_WORLDS) {
            Button create = menuButton("New world", vw, vh);
            create.addClickCommands(source -> showCreateForm());
            buttons.addChild(create, row++, 0);
        }

        // Creations library : closes this menu and opens the creation library (place / capture a creation).
        Button library = menuButton("Creations", vw, vh);
        library.addClickCommands(source -> openLibrary());
        buttons.addChild(library, row++, 0);

        // Global settings : closes this menu and opens the settings popup.
        Button settings = menuButton("Global Settings", vw, vh);
        settings.addClickCommands(source -> openSettings());
        buttons.addChild(settings, row++, 0);

        // Photo mode : closes this menu and hides all UI for a clean screenshot of the world.
        Button photo = menuButton("Photo Mode", vw, vh);
        photo.addClickCommands(source -> openPhotoMode());
        buttons.addChild(photo, row, 0);

        // Back / Close now live at the popup's top corners (see showWorldsViewCornerButtons).
        return buttons;
    }

    /** A right-column button : fixed size, with a top inset that spaces the buttons vertically apart. */
    private Button menuButton(String text, float vw, float vh) {
        Button button = new Button(text, IALON_STYLE);
        button.setFontSize(4 * vh);
        button.setPreferredSize(new Vector3f(26 * vw, 8 * vh, 0));
        button.setInsetsComponent(new InsetsComponent(3 * vh, 0, 0, 0));
        return button;
    }

    private String formatDate(String worldId) {
        long millis = WorldRepository.lastModifiedMillis(config.getSavePath(), worldId);
        if (millis <= 0L) {
            return "";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).format(new Date(millis));
    }

    /** Switches from the worlds view to the centred content panel (create form / confirm dialog). */
    private void useCenteredContent() {
        if (worldsView.getParent() != null) {
            worldsView.removeFromParent();
        }
        detachWorldsViewCornerButtons();
        if (content.getParent() == null) {
            ((Container) popup).addChild(content);
        }
        clearContent();
    }

    /**
     * Confirmation dialog before deleting a world. Shown as a modal overlay on top of the worlds popup
     * (the grid stays visible behind, dimmed) : its own full-screen black background (alpha 0.9) consumes
     * input so the grid behind is not interactable.
     */
    private void showDeleteConfirm(String worldId) {
        float vh = app.getCamera().getHeight() / 100f;
        float vw = app.getCamera().getWidth() / 100f;
        Vector3f screen = new Vector3f(app.getCamera().getWidth(), app.getCamera().getHeight(), 0);

        closeDeleteConfirm();
        Container overlay = new Container(IALON_STYLE);
        deleteOverlay = overlay;
        overlay.setLocalTranslation(0, 0, 100); // relative to the popup : on top of the worlds view
        UiHelper.addBackground(overlay, new ColorRGBA(0f, 0f, 0f, 0.95f), config);
        overlay.addMouseListener(new IgnoreMouseClickListener());

        WorldParams params = WorldRepository.loadWorldParams(config.getSavePath(), worldId);
        String name = params != null ? params.getName() : worldId;

        Container dialog = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        dialog.setInsetsComponent(new DynamicInsetsComponent(0.5f, 0.5f, 0.5f, 0.5f)); // centre in the overlay
        // Opaque black box behind the dialog text/buttons (the margin pads the content inside the box).
        QuadBackgroundComponent dialogBg = new QuadBackgroundComponent(ColorRGBA.Black);
        dialogBg.setMargin(3 * vw, 2 * vh);
        dialogBg.getMaterial().getMaterial().clearParam(UiHelper.ALPHA_DISCARD_THRESHOLD);
        dialog.setBackground(dialogBg);

        Label title = new Label("Delete \"" + name + "\" ?", IALON_STYLE);
        title.setFontSize(4 * vh);
        title.setTextHAlignment(HAlignment.Center);
        title.setPreferredSize(new Vector3f(50 * vw, 8 * vh, 0)); // span the two buttons so the text centres
        dialog.addChild(title, 0, 0);

        // Buttons side by side : row 0, columns 0 and 1.
        Container buttons = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        Button confirm = new Button("Delete", IALON_STYLE);
        confirm.setFontSize(4 * vh);
        confirm.setPreferredSize(new Vector3f(25 * vw, 8 * vh, 0));
        confirm.setTextHAlignment(HAlignment.Center);
        confirm.setTextVAlignment(VAlignment.Center);
        confirm.addClickCommands(source -> {
            WorldRepository.deleteWorld(config.getSavePath(), worldId);
            if (worldId.equals(selectedWorldId)) {
                selectedWorldId = config.getWorldId();
            }
            closeDeleteConfirm();
            rebuildGrid();
        });
        buttons.addChild(confirm, 0, 0);

        Button cancel = new Button("Cancel", IALON_STYLE);
        cancel.setFontSize(4 * vh);
        cancel.setPreferredSize(new Vector3f(25 * vw, 8 * vh, 0));
        cancel.setTextHAlignment(HAlignment.Center);
        cancel.setTextVAlignment(VAlignment.Center);
        cancel.addClickCommands(source -> closeDeleteConfirm());
        buttons.addChild(cancel, 0, 1);
        dialog.addChild(buttons, 1, 0);

        overlay.addChild(dialog);
        popup.attachChild(overlay);
        // Force the full-screen size AFTER attaching + adding the dialog, otherwise the layout shrinks the
        // overlay to its content (leaving the dialog jammed top-left and the grid undimmed).
        overlay.setPreferredSize(screen);
        overlay.setSize(screen);
    }

    private void showCreateForm() {
        useCenteredContent();
        float vh = app.getCamera().getHeight() / 100f;
        float vw = app.getCamera().getWidth() / 100f;

        // Fixed top margin (same as the screen-edge margin used by the top buttons) so the title sits at a
        // constant height, matching the settings popup.
        Panel topSpacer = new Panel();
        topSpacer.setBackground(null);
        topSpacer.setPreferredSize(new Vector3f(1, UiHelper.screenMargin(app.getCamera().getHeight()), 0));
        content.addChild(topSpacer, 0, 0);

        // Title, centered horizontally within its cell (the widest column 0 child) by the
        // DynamicInsetsComponent, with a small spacer row below it.
        Label title = new Label("New world", IALON_STYLE);
        title.setFontSize(4 * vh);
        title.setColor(ColorRGBA.White);
        title.setTextHAlignment(HAlignment.Center);
        title.setInsetsComponent(new DynamicInsetsComponent(0, 0.5f, 0, 0.5f));
        content.addChild(title, 1, 0);

        Panel titleSpacer = new Panel();
        titleSpacer.setBackground(null);
        titleSpacer.setPreferredSize(new Vector3f(1, 3 * vh, 0));
        content.addChild(titleSpacer, 2, 0);

        // The name is assigned automatically ("World N", first free number) — no text input (awkward on
        // Android, and the screenshot preview already distinguishes worlds).
        // High-level generation sliders. Each maps to one or more existing generation parameters
        // (see WorldParams / NoiseTerrainGenerator). Defaults reproduce the standard world.
        int randomSeed = random.nextInt(10000);
        Container sliders = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        SettingsValue seed = addValue(sliders, 0, "Seed", 0, 9999, randomSeed, v -> String.valueOf(v.intValue()));
        SettingsValue water = addValue(sliders, 1, "Water height", 0, 60, 30, v -> String.valueOf(v.intValue()));
        SettingsValue relief = addValue(sliders, 2, "Relief", 0.3, 2.5, 1.0, v -> String.format(Locale.ENGLISH, "%.2f", v));
        SettingsValue density = addValue(sliders, 3, "Mountain density", 0.5, 2.0, 1.0, v -> String.format(Locale.ENGLISH, "%.2f", v));
        SettingsValue trees = addValue(sliders, 4, "Tree density", 0.0, 1.0, 0.70, v -> String.format(Locale.ENGLISH, "%.2f", v));
        SettingsValue woods = addValue(sliders, 5, "Woods size", 0.5, 3.0, 1.0, v -> String.format(Locale.ENGLISH, "%.2f", v));
        content.addChild(sliders, 3, 0);

        // Empty spacer row : vertical margin between the sliders and the buttons.
        Panel spacer = new Panel();
        spacer.setBackground(null);
        spacer.setPreferredSize(new Vector3f(1, 6 * vh, 0));
        content.addChild(spacer, 4, 0);

        // Only the primary action stays at the bottom (centered) : Create. Back/Close live at the top
        // corners (see below). The DynamicInsetsComponent centers the button within its cell.
        Container buttons = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        buttons.setInsetsComponent(new DynamicInsetsComponent(0.5f, 0.5f, 0.5f, 0.5f));
        Button createBtn = new Button("Create", IALON_STYLE);
        createBtn.setFontSize(4 * vh);
        createBtn.setPreferredSize(new Vector3f(25 * vw, 8 * vh, 0));
        createBtn.setTextHAlignment(HAlignment.Center);
        createBtn.setTextVAlignment(VAlignment.Center);
        createBtn.addClickCommands(source -> createAndPlay(seed, water, relief, density, trees, woods));
        buttons.addChild(createBtn, 0, 0);
        content.addChild(buttons, 5, 0);

        showCreateFormCornerButtons();
        sizePopupToScreen();
    }

    /**
     * Shows the create-form header buttons at the popup's top corners (level with the title) : Back (left)
     * returns to the worlds list, Close (right) returns to the game. Created lazily, then attached and
     * positioned. Removed again by {@link #showWorldsView()} when leaving the create form.
     */
    private void showCreateFormCornerButtons() {
        float vh = app.getCamera().getHeight() / 100f;
        if (createBack == null) {
            createBack = new Button("Back", IALON_STYLE);
            createBack.setTextVAlignment(VAlignment.Center);
            createBack.addClickCommands(source -> rebuildGrid());
            createClose = new Button("Close", IALON_STYLE);
            createClose.setTextVAlignment(VAlignment.Center);
            createClose.addClickCommands(source -> hidePopup());
        }
        sizeCornerButton(createBack, vh);
        sizeCornerButton(createClose, vh);
        popup.attachChild(createBack);
        popup.attachChild(createClose);
        UiHelper.placeCornerButtons(createBack, createClose,
                app.getCamera().getWidth(), app.getCamera().getHeight());
    }

    private void detachCreateFormCornerButtons() {
        if (createBack != null) {
            createBack.removeFromParent();
            createClose.removeFromParent();
        }
    }

    /**
     * Sizes a corner header button : font only, no fixed width, so the button hugs its text. That lets
     * {@link UiHelper#placeCornerButtons} sit it flush in the corner (its real width is then known), so
     * Back hugs the left edge and Close hugs the right edge at the same margin.
     */
    private void sizeCornerButton(Button button, float vh) {
        button.setFontSize(4 * vh);
    }

    private SettingsValue addValue(Container container, int row, String title, double min, double max,
                                   double value, java.util.function.Function<Double, String> fmt) {
        SettingsValue sv = new SettingsValue(title, app.getCamera(), min, max, value, fmt);
        sv.addToContainer(container, row);
        activeValues.add(sv);
        return sv;
    }

    private void createAndPlay(SettingsValue seed, SettingsValue water, SettingsValue relief,
                               SettingsValue density, SettingsValue trees, SettingsValue woods) {
        WorldParams params = new WorldParams();
        String name = nextWorldName();
        params.setName(name);
        params.setId(generateUniqueId(name));
        params.setSeed((long) seed.getValue());
        params.setWaterHeight((float) water.getValue());
        params.setReliefAmplitude((float) relief.getValue());
        params.setReliefFrequency((float) density.getValue());
        params.setTreeDensity((float) trees.getValue());
        params.setForestPatchSize((float) woods.getValue());

        WorldRepository.createWorld(config.getSavePath(), params);
        // Keep the popup up until the loading screen is shown (closed by WorldSelectionState).
        Optional.ofNullable(app.getStateManager().getState(WorldSelectionState.class))
                .ifPresent(wss -> wss.switchTo(params.getId()));
    }

    /** Returns "World N" where N is the smallest positive integer not already used by an existing world. */
    private String nextWorldName() {
        Set<Integer> used = new HashSet<>();
        Pattern pattern = Pattern.compile("World (\\d+)");
        for (WorldParams w : WorldRepository.listWorlds(config.getSavePath())) {
            Matcher m = pattern.matcher(w.getName());
            if (m.matches()) {
                used.add(Integer.parseInt(m.group(1)));
            }
        }
        int n = 1;
        while (used.contains(n)) {
            n++;
        }
        return "World " + n;
    }

    private String generateUniqueId(String name) {
        String base = name.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+)|(-+$)", "");
        if (base.isEmpty()) {
            base = "world";
        }
        if (!WorldRepository.worldExists(config.getSavePath(), base)) {
            return base;
        }
        int suffix = 2;
        while (WorldRepository.worldExists(config.getSavePath(), base + "-" + suffix)) {
            suffix++;
        }
        return base + "-" + suffix;
    }

    @Override
    public void update(float tpf) {
        activeValues.forEach(SettingsValue::update);
    }

    @Override
    protected void cleanup(Application application) {
        if (application.getStateManager().getState(ScreenState.class) != null) {
            application.getStateManager().getState(ScreenState.class).unregister(this);
        }
    }

    @Override
    protected void onEnable() {
        if (button.background.getParent() == null) {
            app.getGuiNode().attachChild(button.background);
            app.getGuiNode().attachChild(button.icon);
        }
    }

    @Override
    protected void onDisable() {
        hidePopup();
        if (button.background.getParent() != null) {
            app.getGuiNode().detachChild(button.background);
            app.getGuiNode().detachChild(button.icon);
        }
    }

    @Override
    public void onResize(int width, int height) {
        layout(width, height);
        // If the popup happens to be open, re-fit it (and re-pave the worlds grid) to the new size.
        if (popup.getParent() != null) {
            sizePopupToScreen();
            if (gridContainer != null && gridContainer.getParent() != null) {
                rebuildGrid();
            }
            // Reposition the create-form corner buttons if the create form is the current view.
            if (createBack != null && createBack.getParent() != null) {
                float vh = height / 100f;
                sizeCornerButton(createBack, vh);
                sizeCornerButton(createClose, vh);
                UiHelper.placeCornerButtons(createBack, createClose, width, height);
            }
        }
    }

    /** Recomputes the menu-button size for the new height and repositions it (the single top button). */
    private void layout(int width, int height) {
        buttonSize = height / 12;
        float margin = UiHelper.screenMargin(height);
        float x = width / 2f + buttonSize + SPACING;
        float y = height - margin;
        UiHelper.resizeTextureButton(button, buttonSize, x, y);
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        if (ACTION_SWITCH_MOUSELOCK.equals(name) && isPressed) {
            setEnabled(!this.isEnabled());
        }
    }

    private class TogglePopupMouseClickListener extends DefaultMouseListener {
        @Override
        public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
            event.setConsumed();
            if (event.isPressed()) {
                togglePopup();
            }
        }
    }

    private static class IgnoreMouseClickListener extends DefaultMouseListener {
        @Override
        public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
            event.setConsumed();
        }
    }

}
