/*
 * Copyright (c) 2009-2021 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.material.Material;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial.CullHint;
import com.jme3.scene.shape.Quad;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.blocks.jme.StatsView;
import org.delaunois.ialon.ui.UiHelper;

import java.util.Locale;


/**
 *  Displays stats in SimpleApplication's GUI node or
 *  using the node and font parameters provided.
 *
 *  @author    Paul Speed
 */
public class StatsAppState extends AbstractAppState implements Resizable {

    private Application app;
    protected StatsView statsView;
    private boolean showFps = true;
    private boolean showStats = true;
    private boolean showPosition = false;
    private boolean darkenBehind = false;

    protected Node guiNode;
    protected float secondCounter = 0.0f;
    protected int frameCounter = 0;
    protected BitmapText fpsText;
    protected BitmapText positionText;
    protected BitmapFont guiFont;
    protected Geometry darkenFps;
    protected Geometry darkenStats;

    /** Optional config used to read the live player world position for {@link #positionText}. */
    private IalonConfig config;
    // Last displayed rounded coordinates ; the text geometry is only rebuilt when they change.
    private int lastX = Integer.MIN_VALUE;
    private int lastY = Integer.MIN_VALUE;
    private int lastZ = Integer.MIN_VALUE;

    public StatsAppState() {
    }

    public StatsAppState( Node guiNode, BitmapFont guiFont ) {
        this.guiNode = guiNode;
        this.guiFont = guiFont;
    }

    /**
     *  Called by SimpleApplication to provide an early font
     *  so that the fpsText can be created before init.  This
     *  is because several applications expect to directly access
     *  fpsText... unfortunately.
     * 
     * @param guiFont the desired font (not null, alias created)
     */
    public void setFont( BitmapFont guiFont ) {
        this.guiFont = guiFont;
        this.fpsText = new BitmapText(guiFont);
    }

    public BitmapText getFpsText() {
        return fpsText;
    }

    public StatsView getStatsView() {
        return statsView;
    }

    public float getSecondCounter() {
        return secondCounter;
    }

    public void toggleStats() {
        setDisplayFps( !showFps );
        setDisplayStatView( !showStats );
    }

    public void setDisplayFps(boolean show) {
        showFps = show;
        if (fpsText != null) {
            fpsText.setCullHint(show ? CullHint.Never : CullHint.Always);
            if (darkenFps != null) {
                darkenFps.setCullHint(showFps && darkenBehind ? CullHint.Never : CullHint.Always);
            }

        }
    }

    /** Provides the config from which the live player world position is read. */
    public void setConfig(IalonConfig config) {
        this.config = config;
    }

    /** Shows or hides the on-screen world-position readout (top-left), e.g. {@code "x:10 y:2 z:230"}. */
    public void setDisplayPosition(boolean show) {
        showPosition = show;
        if (positionText != null) {
            positionText.setCullHint(show ? CullHint.Never : CullHint.Always);
        }
    }

    public void setDisplayStatView(boolean show) {
        showStats = show;
        if (statsView != null ) {
            statsView.setEnabled(show);
            statsView.setCullHint(show ? CullHint.Never : CullHint.Always);
            if (darkenStats != null) {
                darkenStats.setCullHint(showStats && darkenBehind ? CullHint.Never : CullHint.Always);
            }
        }
    }

    public void setDarkenBehind(boolean darkenBehind) {
        this.darkenBehind = darkenBehind;
        setEnabled(isEnabled());
    }

    public boolean isDarkenBehind() {
        return darkenBehind;
    }

    @Override
    public void initialize(AppStateManager stateManager, Application app) {
        super.initialize(stateManager, app);
        this.app = app;

        if (app instanceof SimpleApplication) {
            SimpleApplication simpleApp = (SimpleApplication)app;
            if (guiNode == null) {
                guiNode = simpleApp.getGuiNode();
            }
            if (guiFont == null ) {
                guiFont = simpleApp.getAssetManager().loadFont("Interface/Fonts/Default.fnt");
            }
        }

        if (guiNode == null) {
            throw new IllegalStateException( "No guiNode specific and cannot be automatically determined." );
        }

        if (guiFont == null) {
            guiFont = app.getAssetManager().loadFont("Interface/Fonts/Default.fnt");
        }

        loadFpsText();
        loadPositionText();
        loadStatsView();
        loadDarken();

        if (stateManager.getState(ScreenState.class) != null) {
            stateManager.getState(ScreenState.class).register(this);
        }
    }

    @Override
    public void onResize(int width, int height) {
        if (fpsText != null) {
            // FPS text is top-centered ; the stats view and darken quads are bottom-anchored (origin) and
            // therefore do not move on resize.
            fpsText.setLocalTranslation((width - fpsText.getLineWidth()) / 2f, height, 0);
        }
        if (positionText != null) {
            // Position text is top-left, shifted right by twice the screen margin (see loadPositionText).
            positionText.setLocalTranslation(2 * UiHelper.screenMargin(height), height, 0);
        }
    }

    /**
     * Attaches FPS statistics to guiNode and displays it on the screen.
     *
     */
    public void loadFpsText() {
        if (fpsText == null) {
            fpsText = new BitmapText(guiFont);
        }

        fpsText.setText("FPS: 000");
        fpsText.setLocalTranslation((app.getCamera().getWidth()  - fpsText.getLineWidth()) / 2f, app.getCamera().getHeight(), 0);
        fpsText.setCullHint(showFps ? CullHint.Never : CullHint.Always);
        guiNode.attachChild(fpsText);

    }

    /**
     * Attaches the world-position readout to guiNode, top-left, using the same font as the FPS counter.
     */
    public void loadPositionText() {
        positionText = new BitmapText(guiFont);
        positionText.setText("x:0 y:0 z:0");
        // Shifted right by twice the screen margin so it clears the block-removal button placed below it
        // (ButtonManagerState positions that button at x = margin).
        float height = app.getCamera().getHeight();
        positionText.setLocalTranslation(2 * UiHelper.screenMargin(height), height, 0);
        positionText.setCullHint(showPosition ? CullHint.Never : CullHint.Always);
        guiNode.attachChild(positionText);
    }

    /**
     * Attaches Statistics View to guiNode and displays it on the screen
     * above FPS statistics line.
     *
     */
    public void loadStatsView() {
        statsView = new StatsView("Statistics View",
                                  app.getAssetManager(),
                                  app.getRenderer().getStatistics(), guiFont);
        // move it up so it appears above fps text
        statsView.setLocalTranslation(0, fpsText.getLineHeight(), 0);
        statsView.setEnabled(showStats);
        statsView.setCullHint(showStats ? CullHint.Never : CullHint.Always);
        guiNode.attachChild(statsView);
    }

    public void loadDarken() {
        Material mat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", new ColorRGBA(0,0,0,0.5f));
        mat.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);

        darkenFps = new Geometry("StatsDarken", new Quad(200, fpsText.getLineHeight()));
        darkenFps.setMaterial(mat);
        darkenFps.setLocalTranslation(0, 0, -1);
        darkenFps.setCullHint(showFps && darkenBehind ? CullHint.Never : CullHint.Always);
        guiNode.attachChild(darkenFps);

        darkenStats = new Geometry("StatsDarken", new Quad(200, statsView.getHeight()));
        darkenStats.setMaterial(mat);
        darkenStats.setLocalTranslation(0, fpsText.getHeight(), -1);
        darkenStats.setCullHint(showStats && darkenBehind ? CullHint.Never : CullHint.Always);
        guiNode.attachChild(darkenStats);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);

        if (enabled) {
            fpsText.setCullHint(showFps ? CullHint.Never : CullHint.Always);
            darkenFps.setCullHint(showFps && darkenBehind ? CullHint.Never : CullHint.Always);
            statsView.setEnabled(showStats);
            statsView.setCullHint(showStats ? CullHint.Never : CullHint.Always);
            darkenStats.setCullHint(showStats && darkenBehind ? CullHint.Never : CullHint.Always);
            positionText.setCullHint(showPosition ? CullHint.Never : CullHint.Always);
        } else {
            fpsText.setCullHint(CullHint.Always);
            darkenFps.setCullHint(CullHint.Always);
            statsView.setEnabled(false);
            statsView.setCullHint(CullHint.Always);
            darkenStats.setCullHint(CullHint.Always);
            positionText.setCullHint(CullHint.Always);
        }
    }

    @Override
    public void update(float tpf) {
        if (showFps) {
            secondCounter += app.getTimer().getTimePerFrame();
            frameCounter ++;
            if (secondCounter >= 1.0f) {
                int fps = (int) (frameCounter / secondCounter);
                fpsText.setText("FPS: " + fps);
                secondCounter = 0.0f;
                frameCounter = 0;
            }
        }
        updatePositionText();
    }

    /** Refreshes the position readout, rebuilding the text only when the rounded coordinates change. */
    private void updatePositionText() {
        if (!showPosition || positionText == null || config == null) {
            return;
        }
        Vector3f loc = config.getPlayerLocation();
        if (loc == null) {
            return;
        }
        int x = Math.round(loc.x);
        int y = Math.round(loc.y);
        int z = Math.round(loc.z);
        if (x != lastX || y != lastY || z != lastZ) {
            lastX = x;
            lastY = y;
            lastZ = z;
            positionText.setText(String.format(Locale.ENGLISH, "x:%d y:%d z:%d", x, y, z));
        }
    }

    @Override
    public void cleanup() {
        super.cleanup();

        if (app != null && app.getStateManager().getState(ScreenState.class) != null) {
            app.getStateManager().getState(ScreenState.class).unregister(this);
        }
        guiNode.detachChild(statsView);
        guiNode.detachChild(fpsText);
        guiNode.detachChild(positionText);
        guiNode.detachChild(darkenFps);
        guiNode.detachChild(darkenStats);
    }


}
