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
import com.jme3.input.event.MouseButtonEvent;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.HAlignment;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.VAlignment;
import com.simsilica.lemur.component.IconComponent;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.event.DefaultMouseListener;

import org.delaunois.ialon.IalonConfig;

public class SplashscreenState extends BaseAppState {

    private static final ColorRGBA BAR_COLOR = new ColorRGBA(.137f, .693f, .145f, 1f);
    private static final String ALPHA_DISCARD_THRESHOLD = "AlphaDiscardThreshold";

    private SimpleApplication app;
    private Node splashScreen;
    private Container pbContainer;
    private Label percentLabel;

    private final IalonConfig config;

    public SplashscreenState(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void initialize(Application app) {
        this.app = (SimpleApplication) app;
        splashScreen = new Node("SplashScreen");

        float vh = app.getCamera().getHeight() / 100.0f;
        float vw = app.getCamera().getWidth() / 100.0f;

        Container splashContainer = new Container();
        splashContainer.setPreferredSize(new Vector3f(100 * vw, 100 * vh, 0));
        QuadBackgroundComponent qbc = new QuadBackgroundComponent(ColorRGBA.Black);
        qbc.getMaterial().getMaterial().clearParam(ALPHA_DISCARD_THRESHOLD);
        splashContainer.setBackground(qbc);
        splashContainer.setLocalTranslation(0, 100 * vh, 10);
        splashContainer.setName("SplashScreen");
        // Full-screen : consume clicks so nothing behind (in-game buttons) is interactable while loading.
        splashContainer.addMouseListener(new DefaultMouseListener() {
            @Override
            public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                event.setConsumed();
            }
        });
        splashContainer.addChild(buildTitle());

        qbc = new QuadBackgroundComponent(BAR_COLOR);
        qbc.getMaterial().getMaterial().clearParam(ALPHA_DISCARD_THRESHOLD);
        pbContainer = new Container();
        pbContainer.setBackground(qbc);
        pbContainer.setPreferredSize(new Vector3f(50 * vw, 3, 0));
        pbContainer.setName("ProgressBar");
        pbContainer.setLocalTranslation(25 * vw, 25 * vh, 20);

        percentLabel = new Label("");
        // The splash is built before setupGui() remaps the default Lemur style font to the game font, so
        // set it explicitly here to keep the percent readout consistent with the rest of the UI.
        percentLabel.setFont(app.getAssetManager().loadFont(IalonConfig.FONT_PATH));
        percentLabel.setFontSize(4 * vh);
        percentLabel.setTextHAlignment(HAlignment.Center);
        percentLabel.setLocalTranslation(50 * vw - 50f, 30 * vh, 20);

        splashScreen.attachChild(splashContainer);
        splashScreen.attachChild(pbContainer);
        splashScreen.attachChild(percentLabel);
    }

    @Override
    public void update(float tpf) {
        float percent = 0;
        // Re-fetch every frame : on a world switch the ChunkPagerState is detached and replaced, so a
        // cached reference would point at the old (cleared) pager and the bar would stay at 0.
        ChunkPagerState chunkPagerState = getStateManager().getState(ChunkPagerState.class);
        // Only count progress while a build is actually running : right after the menu shows the splash
        // (one frame before the teardown), the OLD world is still fully loaded and would flash ~100%.
        WorldBuilderState worldBuilderState = getStateManager().getState(WorldBuilderState.class);
        boolean building = worldBuilderState != null && worldBuilderState.isEnabled();
        if (building && chunkPagerState != null && chunkPagerState.getChunkPager() != null) {
            int gridSize = config.getGridRadius() * 2 + 1;
            float total = gridSize * gridSize * (float) config.getGridHeight();
            int numPagesAttached = chunkPagerState.getChunkPager().getAttachedPages().size();
            percent = numPagesAttached / total;
        }

        int percentInt = (int) (percent * 100);
        // Before any chunk is attached (world not building yet, or just starting) show "Loading..." rather
        // than a stuck "0%" ; switch to the percentage as soon as progress is measurable.
        percentLabel.setText(percentInt <= 0 ? "Loading..." : percentInt + "%");
        pbContainer.setLocalScale(percent, 1, 1);
    }

    private Container buildTitle() {
        Container titleContainer = new Container();
        IconComponent icon = new IconComponent("ialonsplash.png");
        float scale = app.getCamera().getWidth() / (2f * icon.getImageTexture().getImage().getWidth());
        icon.setIconScale(scale);
        icon.setHAlignment(HAlignment.Center);
        icon.setVAlignment(VAlignment.Center);
        icon.getMaterial().getMaterial().setColor("Color", ColorRGBA.White);
        icon.getMaterial().getMaterial().clearParam(ALPHA_DISCARD_THRESHOLD);

        Label label = new Label("");
        label.setIcon(icon);
        titleContainer.addChild(label);

        return titleContainer;
    }

    @Override
    protected void cleanup(Application app) {
        // Nothing to do
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
