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
import org.delaunois.ialon.blocks.ChunkPager;
import org.delaunois.ialon.ui.IalonTheme;

public class SplashscreenState extends BaseAppState implements Resizable {

    /** Progress bar : hot amber, like a pressure gauge, rather than the stock green. */
    private static final ColorRGBA BAR_COLOR = IalonTheme.AMBER;
    private static final String ALPHA_DISCARD_THRESHOLD = "AlphaDiscardThreshold";

    /**
     * "0%" to "100%", built once. The readout changes at most a hundred times over a load but is looked
     * at every frame, and during the chunk phase the splash is the only thing being drawn : concatenating
     * it per frame would be the one allocation in an otherwise idle loop.
     */
    private static final String[] PERCENT_TEXT = new String[101];

    static {
        for (int i = 0; i < PERCENT_TEXT.length; i++) {
            PERCENT_TEXT[i] = i + "%";
        }
    }

    /** Below this, moving the bar is invisible ; not moving it keeps the GUI node's transform clean. */
    private static final float SCALE_EPSILON = 0.002f;

    private SimpleApplication app;
    private Node splashScreen;
    private Container pbContainer;
    private Label percentLabel;
    private Label phaseLabel;

    // Last painted values, so a frame where nothing changed does no work at all.
    private int lastPercent = -1;
    private String lastLabel;
    private float lastScale = -1f;

    private final IalonConfig config;

    public SplashscreenState(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void initialize(Application app) {
        this.app = (SimpleApplication) app;
        buildSplashScreen();
    }

    /**
     * (Re)builds the whole splash for the current camera size. Called on initialize and on every
     * resolution change : the widgets are sized in vh/vw, so a resize has to rebuild rather than move
     * them — and a load is now long enough that a resize during it is a real possibility.
     */
    private void buildSplashScreen() {
        boolean visible = splashScreen != null && splashScreen.getParent() != null;
        if (splashScreen != null) {
            splashScreen.removeFromParent();
        }
        splashScreen = new Node("SplashScreen");
        lastPercent = -1;
        lastLabel = null;
        lastScale = -1f;

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

        percentLabel = buildLabel(4 * vh, 50 * vw);
        percentLabel.setLocalTranslation(25 * vw, 30 * vh, 20);

        // What the game is busy with, under the percentage : the load is a sequence of very different
        // jobs (textures, blocks, terrain) and their durations differ enough per device that a bare
        // percentage says nothing about which one is taking the time.
        phaseLabel = buildLabel(2.5f * vh, 50 * vw);
        phaseLabel.setLocalTranslation(25 * vw, 20 * vh, 20);
        phaseLabel.setText(LoadProgress.INITIAL_LABEL);

        splashScreen.attachChild(splashContainer);
        splashScreen.attachChild(pbContainer);
        splashScreen.attachChild(percentLabel);
        splashScreen.attachChild(phaseLabel);

        if (visible) {
            app.getGuiNode().attachChild(splashScreen);
        }
    }

    private Label buildLabel(float fontSize, float width) {
        Label label = new Label("");
        // The splash is built before setupGui() remaps the default Lemur style font to the game font, so
        // set it explicitly here to keep the readouts consistent with the rest of the UI.
        label.setFont(app.getAssetManager().loadFont(IalonConfig.FONT_PATH));
        label.setFontSize(fontSize);
        label.setTextHAlignment(HAlignment.Center);
        // Give the label the progress bar's width and centre the text inside it, so the readout stays
        // centred whatever the font's glyph widths and however long the text is ("42%" / "Loading...").
        // The height is read AFTER the font size, which is what determines it.
        label.setPreferredSize(new Vector3f(width, label.getPreferredSize().y, 0));
        return label;
    }

    @Override
    public void onResize(int width, int height) {
        buildSplashScreen();
    }

    @Override
    public void update(float tpf) {
        refresh(tpf);
    }

    /**
     * Re-reads the progress model and repaints the bar and the labels.
     *
     * <p>Public because the init pump calls it again right after running a step, from
     * {@code simpleUpdate} — which runs after this state's {@code update} but before the frame is drawn.
     * Without it the bar and the label would always be one frame — that is, one whole init step — behind
     * the work they describe, which during a load is the difference between announcing the step that is
     * about to run and the one that has just finished.
     */
    public void refresh(float tpf) {
        LoadProgress progress = config.getLoadProgress();

        // The chunk phases are PULLED from the pager rather than pushed by it : the blocks package must
        // not import game code, and its listeners fire from the worker threads whereas this model is
        // render-thread only. Re-fetched every frame : on a world switch the ChunkPagerState is detached
        // and replaced, so a cached reference would point at the old (cleared) pager.
        ChunkPagerState chunkPagerState = getStateManager().getState(ChunkPagerState.class);
        // Only count pages while a build is actually running. On a world switch the splash goes up a
        // frame before the teardown, and the OLD world is still fully paged in : feeding its counters
        // would peg both chunk steps at 100% for good (they are monotonic) before the new world has
        // generated a single chunk. The old builder has long since disabled itself, so this reads false.
        WorldBuilderState worldBuilderState = getStateManager().getState(WorldBuilderState.class);
        boolean building = worldBuilderState != null && worldBuilderState.isEnabled();
        if (building && !progress.isComplete()
                && chunkPagerState != null && chunkPagerState.getChunkPager() != null) {
            feedChunkProgress(progress, chunkPagerState.getChunkPager());
        }

        float percent = progress.update(tpf);

        int percentInt = progress.getDisplayedPercent();
        if (percentInt != lastPercent) {
            lastPercent = percentInt;
            percentLabel.setText(PERCENT_TEXT[percentInt]);
        }
        // Every label is a compile-time constant (a Step's, or INITIAL_LABEL), so the common case where
        // nothing changed is settled by equals()' own identity short-circuit — as cheap as comparing the
        // references, without depending on interning to stay correct if a label ever stops being a literal.
        // getLabel() never returns null ; lastLabel does, on the first frame, which equals() handles.
        String label = progress.getLabel();
        if (!label.equals(lastLabel)) {
            lastLabel = label;
            phaseLabel.setText(label);
        }
        if (Math.abs(percent - lastScale) > SCALE_EPSILON) {
            lastScale = percent;
            pbContainer.setLocalScale(percent, 1, 1);
        }
    }

    /**
     * Reports how far the world build has got to the progress model, in two phases.
     *
     * <p>{@code ChunkManager.requestChunks} waits for the <em>whole</em> requested slab to be generated
     * before it submits a single mesh — a chunk's mesh needs its neighbours' blocks — so not one page is
     * attached until generation is completely done. Counting attached pages alone therefore reads exactly
     * 0 for the entire first phase, then jumps: on a desktop that phase is ~150 ms and nobody notices, but
     * on a phone it is the bulk of the load. Generation progress is visible in the pager's fetched pages,
     * which fill one chunk at a time.
     *
     * <p>The fetch box is the meshed box grown by one ring (the neighbours), hence {@code gridSize + 2} ;
     * its Y range is clamped by the pager's grid bounds to the same {@code gridHeight} as the meshed box
     * (see {@code IalonConfig#getGridLowerBound}). Both counts can go DOWN — the pager unfetches pages
     * when the player moves — which is why they go through the model's monotonic clamp.
     */
    private void feedChunkProgress(LoadProgress progress, ChunkPager chunkPager) {
        int gridSize = config.getGridRadius() * 2 + 1;
        float meshTotal = gridSize * gridSize * (float) config.getGridHeight();
        float fetchTotal = (gridSize + 2f) * (gridSize + 2f) * config.getGridHeight();
        int attached = chunkPager.getAttachedPages().size();
        progress.setStepFraction(LoadProgress.Step.CHUNK_FETCH,
                chunkPager.getFetchedPages().size() / fetchTotal);
        if (attached > 0) {
            // The first attached page is proof that generation is over — the chunk manager meshes nothing
            // until the whole slab has been generated. Said explicitly because the totals above are only
            // estimates, so the fetch fraction does not reliably reach exactly 1 by itself, and without
            // this the bar would stall short of the phase and keep announcing the wrong one.
            progress.completeStep(LoadProgress.Step.CHUNK_FETCH);
        }
        progress.setStepFraction(LoadProgress.Step.CHUNK_MESH, attached / meshTotal);
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
