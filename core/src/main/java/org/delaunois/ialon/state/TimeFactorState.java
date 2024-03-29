/*
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

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.font.BitmapFont;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.event.MouseButtonEvent;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.scene.Spatial;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.component.DynamicInsetsComponent;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.event.DefaultMouseListener;
import com.simsilica.lemur.event.MouseListener;

import org.delaunois.ialon.IalonConfig;

import lombok.extern.slf4j.Slf4j;

import static org.delaunois.ialon.IalonKeyMapping.ACTION_SWITCH_MOUSELOCK;

@Slf4j
public class TimeFactorState extends BaseAppState implements ActionListener {

    private static final float SCREEN_MARGIN = 30;
    private static final float SPACING = 10;

    private static final float UNIT = FastMath.TWO_PI / 86400;
    private static final float[] TIME_FACTORS = {0, 1 * UNIT, 10 * UNIT, 100 * UNIT, 1000 * UNIT, 10000 * UNIT};
    private static final String[] TIME_FACTORS_LABELS = {"Pause", "1 x", "2 x", "3 x", "4 x", "5 x"};

    private SimpleApplication app;
    private BitmapFont guiFont;
    private Container buttonTimeFactor;
    private int buttonSize;
    private Label timeFactorLabel;

    private final IalonConfig config;

    public TimeFactorState(IalonConfig config) {
        this.config = config;
    }

    @Override
    public void initialize(Application app) {
        this.app = (SimpleApplication) app;

        buttonSize = app.getCamera().getHeight() / 12;

        if (guiFont == null) {
            guiFont = app.getAssetManager().loadFont("Interface/Fonts/Default.fnt");
        }

        buttonTimeFactor = createButton(buttonSize, app.getCamera().getWidth() / 2f - 2 * buttonSize - SPACING, app.getCamera().getHeight() - SCREEN_MARGIN,
                new DefaultMouseListener() {
                    @Override
                    public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                        event.setConsumed();
                        if (event.isPressed()) {
                            if (event.getButtonIndex() == 0) {
                                setTimeFactorIndex(config.getTimeFactorIndex() + 1);
                            } else {
                                setTimeFactorIndex(config.getTimeFactorIndex() - 1);
                            }
                        }
                    }
                });

        app.getInputManager().addListener(this, ACTION_SWITCH_MOUSELOCK);
    }

    public void setTimeFactorIndex(int index) {
        config.setTimeFactorIndex((index + TIME_FACTORS.length) % TIME_FACTORS.length);
        updateTimeFactor();
    }

    private void updateTimeFactor() {
        if (app != null) {
            float timeFactor = TIME_FACTORS[config.getTimeFactorIndex()];
            config.setTimeFactor(timeFactor);
            timeFactorLabel.setText(TIME_FACTORS_LABELS[config.getTimeFactorIndex()]);
        }
    }

    private Container createButton(float size, float posx, float posy, MouseListener listener) {
        Container buttonContainer = new Container();
        buttonContainer.setPreferredSize(new Vector3f(size, size, 0));
        QuadBackgroundComponent background = new QuadBackgroundComponent(new ColorRGBA(0, 0, 0, 0.5f));
        // Clear AlphaDiscardThreshold because it is useless here and generates a new specific Shader
        background.getMaterial().getMaterial().clearParam("AlphaDiscardThreshold");
        buttonContainer.setBackground(background);
        timeFactorLabel = new Label("");
        timeFactorLabel.setFontSize(buttonSize / 4f);
        Label label = buttonContainer.addChild(timeFactorLabel);
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
        app.getInputManager().removeListener(this);
    }

    @Override
    protected void onEnable() {
        SunState sunState = app.getStateManager().getState(SunState.class);
        if (sunState == null) {
            log.error("TimeFactorState requires SunState");
            return;
        }
        updateTimeFactor();
        if (buttonTimeFactor.getParent() == null) {
            app.getGuiNode().attachChild(buttonTimeFactor);
        }
    }

    @Override
    protected void onDisable() {
        if (buttonTimeFactor.getParent() != null) {
            app.getGuiNode().detachChild(buttonTimeFactor);
        }
    }

    public void resize() {
        log.info("Resizing {}", this.getClass().getSimpleName());
        buttonTimeFactor.setLocalTranslation(app.getCamera().getWidth() / 2f - 2 * buttonSize - SPACING, app.getCamera().getHeight() - SCREEN_MARGIN, 1);
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        if (ACTION_SWITCH_MOUSELOCK.equals(name) && isPressed) {
            setEnabled(!this.isEnabled());
        }
    }

}
