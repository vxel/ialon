package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.jme3.font.BitmapFont;
import com.jme3.input.controls.ActionListener;
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

import org.delaunois.ialon.Ialon;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import static org.delaunois.ialon.Config.GRID_HEIGHT;
import static org.delaunois.ialon.Config.GRID_RADIUS;
import static org.delaunois.ialon.Config.GRID_RADIUS_MAX;
import static org.delaunois.ialon.Config.GRID_RADIUS_MIN;

@Slf4j
public class GridSettingsState extends BaseAppState implements ActionListener {

    private Ialon app;
    private BitmapFont guiFont;
    private Container buttonSettings;

    private static final int SCREEN_MARGIN = 30;
    private static final int SPACING = 10;

    private int buttonSize;
    private Label gridSettingsLabel;

    @Getter
    private int radius = GRID_RADIUS;

    @Override
    public void initialize(Application app) {
        this.app = (Ialon) app;
        buttonSize = app.getCamera().getHeight() / 12;

        if (guiFont == null) {
            guiFont = app.getAssetManager().loadFont("Interface/Fonts/Default.fnt");
        }

        buttonSettings = createButton(buttonSize, SCREEN_MARGIN + 2 * SPACING + 3 * buttonSize, app.getCamera().getHeight() - SCREEN_MARGIN, new DefaultMouseListener() {
            public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                event.setConsumed();
                if (event.isPressed()) {
                    if (event.getButtonIndex() == 0) {
                        radius = radius + 1;
                    } else {
                        radius = radius - 1;
                    }
                    if (radius > GRID_RADIUS_MAX) {
                        radius = GRID_RADIUS_MIN;
                    }
                    if (radius < GRID_RADIUS_MIN) {
                        radius = GRID_RADIUS_MAX;
                    }
                    setRadius(radius);
                }
            }
        });
    }

    public void setRadius(int radius) {
        this.radius = Math.max(Math.min(radius, GRID_RADIUS_MAX), GRID_RADIUS_MIN);
        int size = radius * 2 + 1;
        BlocksConfig.getInstance().setGrid(new Vec3i(size, GRID_HEIGHT * 2 + 1, size));
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
    }


    @Override
    protected void onEnable() {
        if (buttonSettings.getParent() == null) {
            app.getGuiNode().attachChild(buttonSettings);
        }
        addKeyMappings();
    }

    @Override
    protected void onDisable() {
        if (buttonSettings.getParent() != null) {
            app.getGuiNode().detachChild(buttonSettings);
        }
        deleteKeyMappings();
    }

    public void resize() {
        log.info("Resizing {}", this.getClass().getSimpleName());
        buttonSettings.setLocalTranslation(SCREEN_MARGIN + 2 * SPACING + 3 * buttonSize, app.getCamera().getHeight() - SCREEN_MARGIN, 1);
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
