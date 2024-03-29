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
import com.jme3.scene.BatchNode;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.simsilica.lemur.event.DefaultMouseListener;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.control.ButtonHighlightControl;
import org.delaunois.ialon.ui.UiHelper;
import org.delaunois.ialon.ui.UiHelper.IconButton;

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

    private static final float SCREEN_MARGIN = 30;
    private static final float SPACING = 10;
    private static final String UDK_ACTION = "ACTION";

    @Getter
    private Node buttonParentNode;

    @Getter
    private IconButton buttonLeft;

    @Getter
    private IconButton buttonRight;

    @Getter
    private IconButton buttonBackward;

    @Getter
    private IconButton buttonForward;

    @Getter
    private IconButton buttonJump;

    @Getter
    private IconButton buttonAddBlock;

    @Getter
    private IconButton buttonRemoveBlock;

    @Getter
    private IconButton buttonFly;

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
        buttonParentNode = new Node();

        buttonLeft = createTextureButton("arrowleft.png", buttonSize, SCREEN_MARGIN, SCREEN_MARGIN + buttonSize, ACTION_LEFT);
        buttonBackward = createTextureButton("arrowdown.png", buttonSize, SCREEN_MARGIN + buttonSize + SPACING, SCREEN_MARGIN + buttonSize, ACTION_BACKWARD);
        buttonForward = createTextureButton("arrowup.png", buttonSize, SCREEN_MARGIN + buttonSize + SPACING, SCREEN_MARGIN + buttonSize * 2 + SPACING, ACTION_FORWARD);
        buttonRight = createTextureButton("arrowright.png", buttonSize, SCREEN_MARGIN + (buttonSize + SPACING) * 2, SCREEN_MARGIN + buttonSize, ACTION_RIGHT);
        buttonJump = createTextureButton("arrowjump.png", buttonSize, app.getCamera().getWidth() - SCREEN_MARGIN - buttonSize, SCREEN_MARGIN + buttonSize, ACTION_JUMP);
        buttonFly = createTextureButton("flight.png", buttonSize, app.getCamera().getWidth() - SCREEN_MARGIN - 2 * buttonSize - SPACING, app.getCamera().getHeight() - SCREEN_MARGIN, ACTION_FLY);
        buttonRemoveBlock = createTextureButton("minus.png", buttonSize, SCREEN_MARGIN, app.getCamera().getHeight() - SCREEN_MARGIN, ACTION_REMOVE_BLOCK);
        buttonAddBlock = createTextureButton("plus.png", buttonSize, app.getCamera().getWidth() - SCREEN_MARGIN - buttonSize, app.getCamera().getHeight() - SCREEN_MARGIN, ACTION_ADD_BLOCK);

        BatchNode batchNode = new BatchNode("ButtonBatch");
        batchNode.attachChild(buttonLeft.icon);
        batchNode.attachChild(buttonBackward.icon);
        batchNode.attachChild(buttonForward.icon);
        batchNode.attachChild(buttonRight.icon);
        batchNode.attachChild(buttonJump.icon);
        batchNode.attachChild(buttonFly.icon);
        batchNode.attachChild(buttonRemoveBlock.icon);
        batchNode.attachChild(buttonAddBlock.icon);
        batchNode.batch();
        batchNode.getMaterial().setColor("Color", ColorRGBA.White);
        buttonParentNode.attachChild(batchNode);

        buttonParentNode.attachChild(buttonLeft.background);
        buttonParentNode.attachChild(buttonBackward.background);
        buttonParentNode.attachChild(buttonForward.background);
        buttonParentNode.attachChild(buttonRight.background);
        buttonParentNode.attachChild(buttonJump.background);
        buttonParentNode.attachChild(buttonAddBlock.background);
        buttonParentNode.attachChild(buttonRemoveBlock.background);
        buttonParentNode.attachChild(buttonFly.background);
    }

    @Override
    protected void cleanup(Application app) {
        // Nothing to do
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        if (isEnabled() && ACTION_SWITCH_MOUSELOCK.equals(name) && isPressed) {
            if (buttonParentNode.getParent() == null) {
                showControlButtons();
            } else {
                hideControlButtons();
            }
        }
    }

    @Override
    protected void onEnable() {
        if (buttonParentNode.getParent() == null) {
            app.getGuiNode().attachChild(buttonParentNode);
        }
    }

    @Override
    protected void onDisable() {
        if (buttonParentNode.getParent() != null) {
            buttonParentNode.removeFromParent();
        }
    }

    public void showControlButtons() {
        this.app.getInputManager().deleteTrigger(ACTION_ADD_BLOCK, new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
        this.app.getInputManager().deleteTrigger(ACTION_REMOVE_BLOCK, new MouseButtonTrigger(MouseInput.BUTTON_RIGHT));
        if (buttonParentNode.getParent() == null) {
            app.getGuiNode().attachChild(buttonParentNode);
        }
    }

    public void hideControlButtons() {
        this.app.getInputManager().addMapping(ACTION_ADD_BLOCK, new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
        this.app.getInputManager().addMapping(ACTION_REMOVE_BLOCK, new MouseButtonTrigger(MouseInput.BUTTON_RIGHT));
        if (buttonParentNode.getParent() != null) {
            buttonParentNode.removeFromParent();
        }
    }

    public void resize() {
        buttonJump.background.setLocalTranslation(app.getCamera().getWidth() - SCREEN_MARGIN - buttonSize, SCREEN_MARGIN + buttonSize, -1);
        buttonJump.icon.setLocalTranslation(app.getCamera().getWidth() - SCREEN_MARGIN - buttonSize, SCREEN_MARGIN + buttonSize, 0);
        buttonAddBlock.background.setLocalTranslation(app.getCamera().getWidth() - SCREEN_MARGIN - buttonSize, app.getCamera().getHeight() - SCREEN_MARGIN, -1);
        buttonAddBlock.icon.setLocalTranslation(app.getCamera().getWidth() - SCREEN_MARGIN - buttonSize, app.getCamera().getHeight() - SCREEN_MARGIN, 0);
        buttonRemoveBlock.background.setLocalTranslation(SCREEN_MARGIN, app.getCamera().getHeight() - SCREEN_MARGIN, -1);
        buttonRemoveBlock.icon.setLocalTranslation(SCREEN_MARGIN, app.getCamera().getHeight() - SCREEN_MARGIN, 0);
        buttonFly.background.setLocalTranslation(app.getCamera().getWidth() - SCREEN_MARGIN - 2 * buttonSize - SPACING, app.getCamera().getHeight() - SCREEN_MARGIN, -1);
        buttonFly.icon.setLocalTranslation(app.getCamera().getWidth() - SCREEN_MARGIN - 2 * buttonSize - SPACING, app.getCamera().getHeight() - SCREEN_MARGIN, 0);
    }

    private IconButton createTextureButton(String textureName, float size, float posx, float posy, String actionName) {
        IconButton iconButton = UiHelper.createTextureButton(config, textureName, size, posx, posy);
        iconButton.background.setName(actionName);
        iconButton.background.setUserData(UDK_ACTION, actionName);
        iconButton.background.addMouseListener(screenButtonMouseListener);
        iconButton.background.addControl(new ButtonHighlightControl(config.getInputActionManager(), actionName));
        return iconButton;
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
