package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.input.MouseInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.MouseButtonTrigger;
import com.jme3.input.event.MouseButtonEvent;
import com.jme3.input.event.MouseMotionEvent;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.component.DynamicInsetsComponent;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.event.DefaultMouseListener;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.control.ButtonHighlightControl;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import static org.delaunois.ialon.IalonKeyMapping.ACTION_ADD_BLOCK;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_BACKWARD;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_FLY;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_FORWARD;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_JUMP;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_LEFT;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_REMOVE_BLOCK;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_RIGHT;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_SWITCH_MOUSELOCK;

@Slf4j
public class ButtonManagerState extends BaseAppState implements ActionListener {

    private static final String ALPHA_DISCARD_THRESHOLD = "AlphaDiscardThreshold";
    private static final float SCREEN_MARGIN = 30;
    private static final float SPACING = 10;
    private static final String UDK_ACTION = "ACTION";

    @Getter
    private Node directionButtons;

    @Getter
    private Container buttonLeft;

    @Getter
    private Container buttonRight;

    @Getter
    private Container buttonBackward;

    @Getter
    private Container buttonForward;

    @Getter
    private Container buttonJump;

    @Getter
    private Container buttonAddBlock;

    @Getter
    private Container buttonRemoveBlock;

    @Getter
    private Container buttonFly;

    private int buttonSize;
    private SimpleApplication app;
    private final ScreenButtonMouseListener screenButtonMouseListener;
    private final IalonConfig config;

    public ButtonManagerState(IalonConfig config) {
        this.config = config;
        this.screenButtonMouseListener = new ScreenButtonMouseListener(config);
    }

    @Override
    protected void initialize(Application app) {
        this.app = (SimpleApplication) app;
        this.app.getInputManager().addListener(this, ACTION_SWITCH_MOUSELOCK);
        buttonSize = app.getCamera().getHeight() / 6;
        directionButtons = new Node();
        buttonLeft = createButton("Left", buttonSize, SCREEN_MARGIN, SCREEN_MARGIN + buttonSize, ACTION_LEFT);
        buttonBackward = createButton("Backward", buttonSize, SCREEN_MARGIN + buttonSize + SPACING, SCREEN_MARGIN + buttonSize, ACTION_BACKWARD);
        buttonForward = createButton("Forward", buttonSize, SCREEN_MARGIN + buttonSize + SPACING, SCREEN_MARGIN + buttonSize * 2 + SPACING, ACTION_FORWARD);
        buttonRight = createButton("Right", buttonSize, SCREEN_MARGIN + (buttonSize + SPACING) * 2, SCREEN_MARGIN + buttonSize, ACTION_RIGHT);
        buttonJump = createButton("Jump", buttonSize, app.getCamera().getWidth() - SCREEN_MARGIN - buttonSize, SCREEN_MARGIN + buttonSize, ACTION_JUMP);
        buttonAddBlock = createButton("Add", buttonSize, app.getCamera().getWidth() - SCREEN_MARGIN - buttonSize, app.getCamera().getHeight() - SCREEN_MARGIN, ACTION_ADD_BLOCK);
        buttonRemoveBlock = createButton("Remove", buttonSize, SCREEN_MARGIN, app.getCamera().getHeight() - SCREEN_MARGIN, ACTION_REMOVE_BLOCK);
        buttonFly = createButton("Fly", buttonSize, app.getCamera().getWidth() - SCREEN_MARGIN - 2 * buttonSize - SPACING, app.getCamera().getHeight() - SCREEN_MARGIN, ACTION_FLY);

        directionButtons.attachChild(buttonLeft);
        directionButtons.attachChild(buttonBackward);
        directionButtons.attachChild(buttonForward);
        directionButtons.attachChild(buttonRight);
    }

    @Override
    protected void cleanup(Application app) {
        // Nothing to do
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        if (ACTION_SWITCH_MOUSELOCK.equals(name) && isPressed) {
            if (directionButtons.getParent() == null) {
                showControlButtons();
            } else {
                hideControlButtons();
            }
        }
    }

    @Override
    protected void onEnable() {
        if (directionButtons.getParent() == null) {
            app.getGuiNode().attachChild(directionButtons);
            app.getGuiNode().attachChild(buttonJump);
            app.getGuiNode().attachChild(buttonAddBlock);
            app.getGuiNode().attachChild(buttonRemoveBlock);
            app.getGuiNode().attachChild(buttonFly);
        }
    }

    @Override
    protected void onDisable() {
        if (directionButtons.getParent() != null) {
            directionButtons.removeFromParent();
            buttonJump.removeFromParent();
            buttonAddBlock.removeFromParent();
            buttonRemoveBlock.removeFromParent();
            buttonFly.removeFromParent();
        }
    }

    public void showControlButtons() {
        this.app.getInputManager().deleteTrigger(ACTION_ADD_BLOCK, new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
        this.app.getInputManager().deleteTrigger(ACTION_REMOVE_BLOCK, new MouseButtonTrigger(MouseInput.BUTTON_RIGHT));
        if (directionButtons.getParent() == null) {
            app.getGuiNode().attachChild(directionButtons);
            app.getGuiNode().attachChild(buttonJump);
            app.getGuiNode().attachChild(buttonAddBlock);
            app.getGuiNode().attachChild(buttonRemoveBlock);
        }
    }

    public void hideControlButtons() {
        this.app.getInputManager().addMapping(ACTION_ADD_BLOCK, new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
        this.app.getInputManager().addMapping(ACTION_REMOVE_BLOCK, new MouseButtonTrigger(MouseInput.BUTTON_RIGHT));
        if (directionButtons.getParent() != null) {
            directionButtons.removeFromParent();
            buttonJump.removeFromParent();
            buttonAddBlock.removeFromParent();
            buttonRemoveBlock.removeFromParent();
        }
    }

    public void resize() {
        buttonJump.setLocalTranslation(app.getCamera().getWidth() - SCREEN_MARGIN - buttonSize, SCREEN_MARGIN + buttonSize, 1);
        buttonAddBlock.setLocalTranslation(app.getCamera().getWidth() - SCREEN_MARGIN - buttonSize, app.getCamera().getHeight() - SCREEN_MARGIN, 1);
        buttonRemoveBlock.setLocalTranslation(SCREEN_MARGIN, app.getCamera().getHeight() - SCREEN_MARGIN, 1);
        buttonFly.setLocalTranslation(app.getCamera().getWidth() - SCREEN_MARGIN - 2 * buttonSize - SPACING, app.getCamera().getHeight() - SCREEN_MARGIN, 1);
    }

    private Container createButton(String text, float size, float posx, float posy, String actionName) {
        Container buttonContainer = new Container();
        buttonContainer.setName(text);
        buttonContainer.setUserData(UDK_ACTION, actionName);
        buttonContainer.setPreferredSize(new Vector3f(size, size, 0));
        QuadBackgroundComponent background = new QuadBackgroundComponent(new ColorRGBA(0, 0, 0, 0.5f));
        // Clear AlphaDiscardThreshold because it is useless here and generates a new specific Shader
        background.getMaterial().getMaterial().clearParam(ALPHA_DISCARD_THRESHOLD);
        buttonContainer.setBackground(background);

        Label label = buttonContainer.addChild(new Label(text));
        label.getFont().getPage(0).clearParam(ALPHA_DISCARD_THRESHOLD);
        label.getFont().getPage(0).clearParam("VertexColor");

        // Center the text in the box.
        label.setInsetsComponent(new DynamicInsetsComponent(0.5f, 0.5f, 0.5f, 0.5f));
        label.setColor(ColorRGBA.White);
        buttonContainer.setLocalTranslation(posx, posy, 1);

        buttonContainer.addMouseListener(screenButtonMouseListener);
        buttonContainer.addControl(new ButtonHighlightControl(config.getInputActionManager(), actionName));

        return buttonContainer;
    }

    private static class ScreenButtonMouseListener extends DefaultMouseListener {

        private final IalonConfig config;

        public ScreenButtonMouseListener(IalonConfig config) {
            this.config = config;
        }

        @Override
        public void mouseButtonEvent(MouseButtonEvent event, Spatial button, Spatial capture) {
            if (button != null) {
                event.setConsumed();
                config.getInputActionManager().triggerAction(button.getUserData(UDK_ACTION), event.isPressed());
            }
        }

        @Override
        public void mouseExited( MouseMotionEvent event, Spatial button, Spatial capture ) {
            if (button != null) {
                event.setConsumed();
                config.getInputActionManager().triggerAction(button.getUserData(UDK_ACTION), false);
            }
        }

    }
}
