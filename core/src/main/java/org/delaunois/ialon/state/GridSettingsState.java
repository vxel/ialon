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

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.font.BitmapFont;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.input.event.MouseButtonEvent;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Spatial;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.component.DynamicInsetsComponent;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.event.DefaultMouseListener;
import com.simsilica.lemur.event.MouseListener;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonConfig;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GridSettingsState extends BaseAppState implements ActionListener {

    private static final String ACTION_SWITCH_MOUSELOCK = "switch-mouselock";
    private static final float SCREEN_MARGIN = 30;
    private static final float SPACING = 10;

    private SimpleApplication app;
    private BitmapFont guiFont;
    private Container buttonSettings;
    private int buttonSize;
    private Label gridSettingsLabel;
    private boolean isMouseLocked = false;
    private int radius;

    private final IalonConfig config;

    public GridSettingsState(IalonConfig config) {
        this.config = config;
        this.radius = config.getGridRadius();
    }

    @Override
    public void initialize(Application app) {
        this.app = (SimpleApplication) app;
        buttonSize = app.getCamera().getHeight() / 12;
        radius = config.getGridRadius();

        if (guiFont == null) {
            guiFont = app.getAssetManager().loadFont("Interface/Fonts/Default.fnt");
        }

        buttonSettings = createButton(buttonSize,
                app.getCamera().getWidth() / 2f + buttonSize + SPACING,
                app.getCamera().getHeight() - SCREEN_MARGIN,
                new DefaultMouseListener() {
                    @Override
                    public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                        event.setConsumed();
                        if (event.isPressed()) {
                            if (event.getButtonIndex() == 0) {
                                radius = radius + 1;
                            } else {
                                radius = radius - 1;
                            }
                            if (radius > config.getGridRadiusMax()) {
                                radius = config.getGridRadiusMin();
                            }
                            if (radius < config.getGridRadiusMin()) {
                                radius = config.getGridRadiusMax();
                            }
                            setRadius(radius);
                        }
                    }
                });

        if (!app.getInputManager().hasMapping(ACTION_SWITCH_MOUSELOCK)) {
            app.getInputManager().addMapping(ACTION_SWITCH_MOUSELOCK, new KeyTrigger(KeyInput.KEY_BACK));
        }
        app.getInputManager().addListener(this, ACTION_SWITCH_MOUSELOCK);
    }

    public void setRadius(int radius) {
        this.radius = Math.max(Math.min(radius, config.getGridRadiusMax()), config.getGridRadiusMin());
        config.setGridRadius(radius);
        int size = radius * 2 + 1;
        BlocksConfig.getInstance().setGrid(new Vec3i(size, config.getGridHeight() * 2 + 1, size));
        if (app != null) {
            gridSettingsLabel.setText(size + "x" + size);
            app.getStateManager().getState(ChunkPagerState.class).getChunkPager().updateGridSize();
        }
    }


    private Container createButton(float size, float posx, float posy, MouseListener listener) {
        Container buttonContainer = new Container();
        buttonContainer.setPreferredSize(new Vector3f(size, size, 0));
        QuadBackgroundComponent background = new QuadBackgroundComponent(new ColorRGBA(0, 0, 0, 0.5f));
        // Clear AlphaDiscardThreshold because it is useless here and generates a new specific Shader
        background.getMaterial().getMaterial().clearParam("AlphaDiscardThreshold");
        buttonContainer.setBackground(background);
        int gridSize = radius * 2 + 1;
        gridSettingsLabel = new Label(gridSize + "x" + gridSize);
        Label label = buttonContainer.addChild(gridSettingsLabel);
        label.getFont().getPage(0).clearParam("AlphaDiscardThreshold");
        label.getFont().getPage(0).clearParam("VertexColor");

        // Center the text in the box.
        label.setInsetsComponent(new DynamicInsetsComponent(0.5f, 0.5f, 0.5f, 0.5f));
        label.setColor(ColorRGBA.White);
        buttonContainer.setLocalTranslation(posx, posy, 1);

        buttonContainer.addMouseListener(listener);
        return buttonContainer;
    }

    @Override
    protected void cleanup(Application app) {
        // Nothing to do
    }

    @Override
    protected void onEnable() {
        if (buttonSettings.getParent() == null) {
            app.getGuiNode().attachChild(buttonSettings);
        }
    }

    @Override
    protected void onDisable() {
        if (buttonSettings.getParent() != null) {
            app.getGuiNode().detachChild(buttonSettings);
        }
    }

    public void resize() {
        log.info("Resizing {}", this.getClass().getSimpleName());
        buttonSettings.setLocalTranslation(app.getCamera().getWidth() / 2f + buttonSize + SPACING, app.getCamera().getHeight() - SCREEN_MARGIN, 1);
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        if (ACTION_SWITCH_MOUSELOCK.equals(name) && isPressed) {
            setEnabled(isMouseLocked);
            isMouseLocked = !isMouseLocked;
        }
    }

}
