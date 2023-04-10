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

import com.jme3.app.Application;
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

import org.delaunois.ialon.Ialon;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TimeFactorState extends BaseAppState implements ActionListener {

    private Ialon app;
    private BitmapFont guiFont;
    private Container buttonTimeFactor;

    private static final float SCREEN_MARGIN = 30;
    private static final float SPACING = 10;

    private static final float UNIT = FastMath.TWO_PI / 86400;
    private static final float[] TIME_FACTORS = {0, 1 * UNIT, 10 * UNIT, 100 * UNIT, 1000 * UNIT, 10000 * UNIT};
    private static final String[] TIME_FACTORS_LABELS = {"Pause", "1x", "2x", "3x", "4x", "5x"};

    @Getter
    private int timeFactorIndex = 1;

    private int buttonSize;
    private Label timeFactorLabel;

    @Override
    public void initialize(Application app) {
        this.app = (Ialon) app;

        buttonSize = app.getCamera().getHeight() / 12;

        if (guiFont == null) {
            guiFont = app.getAssetManager().loadFont("Interface/Fonts/Default.fnt");
        }

        buttonTimeFactor = createButton(buttonSize, SCREEN_MARGIN + SPACING + 2 * buttonSize, app.getCamera().getHeight() - SCREEN_MARGIN, new DefaultMouseListener() {
            public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                event.setConsumed();
                if (event.isPressed()) {
                    if (event.getButtonIndex() == 0) {
                        setTimeFactorIndex(timeFactorIndex + 1);
                    } else {
                        setTimeFactorIndex(timeFactorIndex - 1);
                    }
                }
            }
        });
    }

    public void setTimeFactorIndex(int index) {
        timeFactorIndex = (index + TIME_FACTORS.length) % TIME_FACTORS.length;
        updateTimeFactor();
    }

    private void updateTimeFactor() {
        if (app != null) {
            app.getStateManager().getState(SunState.class).getSunControl().setTimeFactor(TIME_FACTORS[timeFactorIndex]);
            timeFactorLabel.setText(TIME_FACTORS_LABELS[timeFactorIndex]);
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
        addKeyMappings();
    }

    @Override
    protected void onDisable() {
        if (buttonTimeFactor.getParent() != null) {
            app.getGuiNode().detachChild(buttonTimeFactor);
        }
        deleteKeyMappings();
    }

    public void resize() {
        log.info("Resizing {}", this.getClass().getSimpleName());
        buttonTimeFactor.setLocalTranslation(SCREEN_MARGIN + SPACING + 2 * buttonSize, app.getCamera().getHeight() - SCREEN_MARGIN, 1);
    }

    @Override
    public void update(float tpf) {
    }

    private void addKeyMappings() {
    }

    private void deleteKeyMappings() {
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
    }

}
