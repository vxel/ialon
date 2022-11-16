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
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.HAlignment;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.VAlignment;
import com.simsilica.lemur.component.IconComponent;
import com.simsilica.lemur.component.QuadBackgroundComponent;

import org.delaunois.ialon.Ialon;

import static org.delaunois.ialon.Config.GRID_HEIGHT;

public class SplashscreenState extends BaseAppState {

    private static final ColorRGBA BAR_COLOR = new ColorRGBA(.137f, .693f, .145f, 1f);

    private Node splashScreen;
    private Container pbContainer;
    private Label percentLabel;

    private Ialon app;

    @Override
    protected void initialize(Application app) {
        this.app = (Ialon) app;
        splashScreen = new Node("SplashScreen");

        Container splashContainer = new Container();
        splashContainer.setPreferredSize(new Vector3f(app.getCamera().getWidth(), app.getCamera().getHeight(), 0));
        splashContainer.setBackground(new QuadBackgroundComponent(ColorRGBA.Black));
        splashContainer.setLocalTranslation(0, getApplication().getCamera().getHeight(), 10);
        splashContainer.setName("SplashScreen");
        splashContainer.addChild(buildTitle());

        pbContainer = new Container();
        pbContainer.setBackground(new QuadBackgroundComponent(BAR_COLOR));
        pbContainer.setPreferredSize(new Vector3f(getApplication().getCamera().getWidth() * 0.5f, 3, 0));
        pbContainer.setName("ProgressBar");
        pbContainer.setLocalTranslation(getApplication().getCamera().getWidth() * 0.25f, getApplication().getCamera().getHeight() / 4f, 20);

        percentLabel = new Label("");
        percentLabel.setFontSize(getApplication().getCamera().getHeight() / 50f);
        percentLabel.setTextHAlignment(HAlignment.Center);
        percentLabel.setPreferredSize(new Vector3f(100, 10, 0));
        percentLabel.setLocalTranslation(getApplication().getCamera().getWidth() / 2f - 50f, getApplication().getCamera().getHeight() / 3.5f, 20);

        splashScreen.attachChild(splashContainer);
        splashScreen.attachChild(pbContainer);
        splashScreen.attachChild(percentLabel);
    }

    @Override
    public void update(float tpf) {
        float percent = 0;
        ChunkPagerState chunkPagerState = getStateManager().getState(ChunkPagerState.class);
        GridSettingsState gridSettingsState = getStateManager().getState(GridSettingsState.class);
        if (chunkPagerState != null && chunkPagerState.getChunkPager() != null && gridSettingsState != null) {
            int gridSize = gridSettingsState.getRadius() * 2 + 1;
            float total = gridSize * gridSize * GRID_HEIGHT;
            int numPagesAttached = chunkPagerState.getChunkPager().getAttachedPages().size();
            percent = numPagesAttached / total;
        }

        percentLabel.setText(((int)(percent * 100)) + "%");
        pbContainer.setLocalScale(percent, 1, 1);
    }

    private Container buildTitle() {
        Container titleContainer = new Container();
        IconComponent icon = new IconComponent("ialonsplash.png");
        float scale = app.getCamera().getWidth() / (2f * icon.getImageTexture().getImage().getWidth());
        icon.setIconScale(scale);
        icon.setHAlignment(HAlignment.Center);
        icon.setVAlignment(VAlignment.Center);

        Label label = new Label("");
        label.setIcon(icon);
        titleContainer.addChild(label);

        return titleContainer;
    }

    @Override
    protected void cleanup(Application app) {

    }

    @Override
    protected void onEnable() {
        if (splashScreen.getParent() == null) {
            app.getGuiNode().attachChild(splashScreen);
        }
    }

    @Override
    protected void onDisable() {
        if (splashScreen.getParent() != null) {
            app.getGuiNode().detachChild(splashScreen);
        }
    }
}
