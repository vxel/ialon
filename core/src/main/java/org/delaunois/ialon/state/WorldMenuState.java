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
import com.simsilica.lemur.Label;
import com.simsilica.lemur.Panel;
import com.simsilica.lemur.component.DynamicInsetsComponent;
import com.simsilica.lemur.component.InsetsComponent;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.component.SpringGridLayout;
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
public class WorldMenuState extends BaseAppState implements ActionListener {

    private static final float SCREEN_MARGIN = 30;
    private static final float SPACING = 10;

    private SimpleApplication app;
    private IconButton button;
    private int buttonSize;
    private Node popup;
    private Container content;

    private final IalonConfig config;
    // SettingsValue sliders shown on the create form, polled each frame so their value labels track.
    private final List<SettingsValue> activeValues = new ArrayList<>();
    // The world card currently selected ; the right-hand Load/Delete buttons act on it.
    private String selectedWorldId;
    // Preview textures loaded into the asset cache while the popup is open, released on close.
    private final Set<String> loadedPreviews = new HashSet<>();

    public WorldMenuState(IalonConfig config) {
        this.config = config;
    }

    @Override
    public void initialize(Application application) {
        this.app = (SimpleApplication) application;
        buttonSize = app.getCamera().getHeight() / 12;
        button = createMenuButton();
        popup = createPopup();

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
        // Placed to the right of the settings gear (which sits at width/2 + buttonSize + SPACING).
        IconButton iconButton = UiHelper.createTextureButton(config,
                "settings.png",
                buttonSize,
                app.getCamera().getWidth() / 2f + 2 * (buttonSize + SPACING),
                app.getCamera().getHeight() - SCREEN_MARGIN
        );
        iconButton.background.addMouseListener(new TogglePopupMouseClickListener());
        return iconButton;
    }

    private Node createPopup() {
        Container worldPopup = new Container(IALON_STYLE);
        worldPopup.setLocalTranslation(0, app.getCamera().getHeight(), 100);
        worldPopup.setName("worldPopup");
        worldPopup.setPreferredSize(new Vector3f(app.getCamera().getWidth(), app.getCamera().getHeight(), 0));
        UiHelper.addBackground(worldPopup, new ColorRGBA(0f, 0f, 0f, 0.8f));
        worldPopup.addMouseListener(new TogglePopupMouseClickListener());

        content = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        content.setInsetsComponent(new DynamicInsetsComponent(10, 50, 50, 50));
        content.addMouseListener(new IgnoreMouseClickListener());
        worldPopup.addChild(content);
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
        hidePopup();
        WorldPreview.capture(app, preview, this::reallyShowPopup);
    }

    private void reallyShowPopup() {
        if (popup.getParent() != null) {
            return;
        }
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
        releasePreviews();
    }

    private void releasePreviews() {
        loadedPreviews.forEach(id -> WorldPreview.dropFromCache(app.getAssetManager(), id));
        loadedPreviews.clear();
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
     * Builds the worlds view : a grid of cards (paving left-to-right then top-to-bottom) plus a column
     * of action buttons (Load / Delete / New world) acting on the selected card.
     */
    private void rebuildGrid() {
        clearContent();
        releasePreviews();
        float vh = app.getCamera().getHeight() / 100f;
        float vw = app.getCamera().getWidth() / 100f;

        List<WorldParams> worlds = WorldRepository.listWorlds(config.getSavePath());
        if (worlds.stream().noneMatch(w -> w.getId().equals(selectedWorldId))) {
            selectedWorldId = config.getWorldId();
        }

        Label title = new Label("Worlds", IALON_STYLE);
        title.setFontSize(6 * vh);
        content.addChild(title, 0, 0);

        // Layout convention (as in SettingsValue) : addChild(child, rowIndex, colIndex) for a
        // SpringGridLayout(Axis.Y, Axis.X). Grid on the left, button column on the right (same row).
        Container body = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);

        // Card grid : Lemur has no wrapping layout, so pave row = i / cols, col = i % cols (left-to-right
        // then top-to-bottom).
        float cardW = 22 * vw;
        int cols = Math.max(1, (int) (68 * vw / cardW)); // keep ~32vw on the right for the button column
        Container grid = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        for (int i = 0; i < worlds.size(); i++) {
            grid.addChild(createCard(worlds.get(i), vw, vh), i / cols, i % cols);
        }
        body.addChild(grid, 0, 0);
        body.addChild(createButtonColumn(worlds, vw, vh), 0, 1);

        content.addChild(body, 1, 0);
        sizePopupToScreen();
    }

    private Container createCard(WorldParams world, float vw, float vh) {
        boolean selected = world.getId().equals(selectedWorldId);
        float border = 0.4f * vh;   // white frame thickness
        float pad = 1.2f * vh;      // black mat between the frame and the content
        float halfGap = 0.8f * vh;  // half the gap between adjacent cards
        float contentW = 20 * vw;
        float previewH = 12 * vh;
        float captionH = 5 * vh;

        // In Lemur an InsetsComponent reveals the PARENT's background in its margin (insets are applied
        // outside the background). So the inset goes on the CHILD to expose its parent's colour :
        //   frame (white bg) > mat (black bg, inset reveals the white frame) > content (transparent,
        //   inset reveals the black mat) > preview + caption.
        // A halfGap inset on the frame reveals the popup background, spacing the cards apart.
        // Selected world : white frame ; others : black frame (blends with the black mat, i.e. no frame).
        Container frame = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        UiHelper.addBackground(frame, selected ? ColorRGBA.White : ColorRGBA.Black);
        frame.setInsetsComponent(new InsetsComponent(halfGap, halfGap, halfGap, halfGap));

        Container mat = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        UiHelper.addBackground(mat, ColorRGBA.Black);
        mat.setInsetsComponent(new InsetsComponent(border, border, border, border));

        Container contentBox = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        contentBox.setInsetsComponent(new InsetsComponent(pad, pad, pad, pad));

        // Preview image (or the black mat as placeholder when no screenshot exists yet).
        Panel preview = new Panel();
        preview.setPreferredSize(new Vector3f(contentW, previewH, 0));
        Texture tex = WorldPreview.load(app.getAssetManager(), world.getId());
        if (tex != null) {
            preview.setBackground(new QuadBackgroundComponent(tex));
            loadedPreviews.add(world.getId());
        }
        contentBox.addChild(preview, 0, 0);

        Label caption = new Label(world.getName() + "\n" + formatDate(world.getId()), IALON_STYLE);
        caption.setFontSize(2.2f * vh);
        caption.setColor(ColorRGBA.White);
        caption.setPreferredSize(new Vector3f(contentW, captionH, 0));
        contentBox.addChild(caption, 1, 0);

        mat.addChild(contentBox);
        frame.addChild(mat);
        frame.addMouseListener(new SelectCardMouseListener(world.getId()));
        return frame;
    }

    private Container createButtonColumn(List<WorldParams> worlds, float vw, float vh) {
        boolean selIsCurrent = selectedWorldId.equals(config.getWorldId());
        Vector3f btnSize = new Vector3f(26 * vw, 8 * vh, 0);

        Container buttons = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        // Left inset reveals the popup background, adding space between the card grid and the buttons.
        buttons.setInsetsComponent(new InsetsComponent(0, 4 * vw, 0, 0));
        int row = 0;

        Button load = new Button("Load", IALON_STYLE);
        load.setFontSize(5 * vh);
        load.setPreferredSize(btnSize);
        load.setEnabled(!selIsCurrent);
        load.addClickCommands(source -> {
            String target = selectedWorldId;
            hidePopup();
            Optional.ofNullable(app.getStateManager().getState(WorldSelectionState.class))
                    .ifPresent(wss -> wss.switchTo(target));
        });
        buttons.addChild(load, row++, 0);

        if (selIsCurrent) {
            // For the current world : refresh its screenshot (in place of Delete, which is not allowed).
            Button replace = new Button("Update preview", IALON_STYLE);
            replace.setFontSize(5 * vh);
            replace.setPreferredSize(btnSize);
            replace.addClickCommands(source -> recaptureCurrentPreview());
            buttons.addChild(replace, row++, 0);
        } else if (worlds.size() > 1) {
            // Delete : not the current world, and not the last remaining one.
            Button delete = new Button("Delete", IALON_STYLE);
            delete.setFontSize(5 * vh);
            delete.setPreferredSize(btnSize);
            delete.addClickCommands(source -> {
                WorldRepository.deleteWorld(config.getSavePath(), selectedWorldId);
                selectedWorldId = config.getWorldId();
                app.enqueue(this::rebuildGrid);
            });
            buttons.addChild(delete, row++, 0);
        }

        Button create = new Button("New world", IALON_STYLE);
        create.setFontSize(5 * vh);
        create.setPreferredSize(btnSize);
        create.addClickCommands(source -> showCreateForm());
        buttons.addChild(create, row, 0);

        return buttons;
    }

    private String formatDate(String worldId) {
        long millis = WorldRepository.lastModifiedMillis(config.getSavePath(), worldId);
        if (millis <= 0L) {
            return "";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).format(new Date(millis));
    }

    private void showCreateForm() {
        clearContent();
        float vh = app.getCamera().getHeight() / 100f;
        float vw = app.getCamera().getWidth() / 100f;

        Label title = new Label("New world", IALON_STYLE);
        title.setFontSize(6 * vh);
        content.addChild(title, 0, 0);

        // The name is assigned automatically ("World N", first free number) — no text input (awkward on
        // Android, and the screenshot preview already distinguishes worlds).
        // High-level generation sliders. Each maps to one or more existing generation parameters
        // (see WorldParams / NoiseTerrainGenerator). Defaults reproduce the standard world.
        int randomSeed = new Random().nextInt(10000);
        Container sliders = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        SettingsValue seed = addValue(sliders, 0, "Seed", 0, 9999, randomSeed, v -> String.valueOf(v.intValue()));
        SettingsValue water = addValue(sliders, 1, "Water height", 10, 60, 30, v -> String.valueOf(v.intValue()));
        SettingsValue relief = addValue(sliders, 2, "Relief", 0.3, 2.5, 1.0, v -> String.format(Locale.ENGLISH, "%.2f", v));
        SettingsValue density = addValue(sliders, 3, "Mountain density", 0.5, 2.0, 1.0, v -> String.format(Locale.ENGLISH, "%.2f", v));
        SettingsValue trees = addValue(sliders, 4, "Tree density", 0.0, 1.0, 0.70, v -> String.format(Locale.ENGLISH, "%.2f", v));
        SettingsValue woods = addValue(sliders, 5, "Woods size", 0.5, 3.0, 1.0, v -> String.format(Locale.ENGLISH, "%.2f", v));
        content.addChild(sliders, 1, 0);

        Container buttons = new Container(new SpringGridLayout(Axis.X, Axis.Y, FillMode.None, FillMode.None), IALON_STYLE);
        Button createBtn = new Button("Create", IALON_STYLE);
        createBtn.setFontSize(5 * vh);
        createBtn.setPreferredSize(new Vector3f(25 * vw, 8 * vh, 0));
        createBtn.addClickCommands(source -> createAndPlay(seed, water, relief, density, trees, woods));
        buttons.addChild(createBtn, 0, 0);

        Button cancel = new Button("Cancel", IALON_STYLE);
        cancel.setFontSize(5 * vh);
        cancel.setPreferredSize(new Vector3f(25 * vw, 8 * vh, 0));
        cancel.addClickCommands(source -> rebuildGrid());
        buttons.addChild(cancel, 0, 1);
        content.addChild(buttons, 2, 0);
        sizePopupToScreen();
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
        hidePopup();
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
        String base = name.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
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
        // Nothing to do
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

    public void resize() {
        float x = app.getCamera().getWidth() / 2f + 2 * (buttonSize + SPACING);
        float y = app.getCamera().getHeight() - SCREEN_MARGIN;
        button.background.setLocalTranslation(x, y, 1);
        button.icon.setLocalTranslation(x, y, 1);
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

    private class IgnoreMouseClickListener extends DefaultMouseListener {
        @Override
        public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
            event.setConsumed();
        }
    }

    /** Selects the clicked world card and rebuilds the grid (updates the highlight and the buttons). */
    private class SelectCardMouseListener extends DefaultMouseListener {
        private final String worldId;

        SelectCardMouseListener(String worldId) {
            this.worldId = worldId;
        }

        @Override
        public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
            event.setConsumed();
            if (event.isPressed() && !worldId.equals(selectedWorldId)) {
                selectedWorldId = worldId;
                app.enqueue(WorldMenuState.this::rebuildGrid);
            }
        }
    }
}
