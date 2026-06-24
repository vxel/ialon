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

import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_ADD_BLOCK;
import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_BACKWARD;
import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_FLY;
import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_FORWARD;
import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_JUMP;
import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_LEFT;
import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_REMOVE_BLOCK;
import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_RIGHT;
import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_SWITCH_MOUSELOCK;
import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_ACTION_OBJECT;

@Slf4j
public class ButtonManagerState extends BaseAppState implements ActionListener, Resizable {

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

    // Contextual "Action" button (open/close a door). Unlike the others its icon is NOT batched, so it
    // can be shown/hidden on its own : icon + background live in actionButtonNode, attached to
    // buttonParentNode only while a door is targeted (driven by PlaceholderControl).
    @Getter
    private IconButton buttonAction;
    private final Node actionButtonNode = new Node("ActionButton");
    private boolean actionButtonVisible = false;

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

        // Initial positions are placeholders ; layout(...) below sets the authoritative positions and is
        // the single source of truth reused on every resolution change.
        buttonLeft = createTextureButton("arrowleft.png", buttonSize, 0, 0, ACTION_LEFT);
        buttonBackward = createTextureButton("arrowdown.png", buttonSize, 0, 0, ACTION_BACKWARD);
        buttonForward = createTextureButton("arrowup.png", buttonSize, 0, 0, ACTION_FORWARD);
        buttonRight = createTextureButton("arrowright.png", buttonSize, 0, 0, ACTION_RIGHT);
        buttonJump = createTextureButton("arrowjump.png", buttonSize, 0, 0, ACTION_JUMP);
        buttonFly = createTextureButton("flight.png", buttonSize, 0, 0, ACTION_FLY);
        buttonRemoveBlock = createTextureButton("minus.png", buttonSize, 0, 0, ACTION_REMOVE_BLOCK);
        buttonAddBlock = createTextureButton("plus.png", buttonSize, 0, 0, ACTION_ADD_BLOCK);
        buttonAction = createTextureButton("action.png", buttonSize, 0, 0, ACTION_ACTION_OBJECT);
        actionButtonNode.attachChild(buttonAction.icon);
        actionButtonNode.attachChild(buttonAction.background);

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

        layout(app.getCamera().getWidth(), app.getCamera().getHeight());

        if (app.getStateManager().getState(ScreenState.class) != null) {
            app.getStateManager().getState(ScreenState.class).register(this);
        }
    }

    @Override
    protected void cleanup(Application app) {
        if (app.getStateManager().getState(ScreenState.class) != null) {
            app.getStateManager().getState(ScreenState.class).unregister(this);
        }
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

    @Override
    public void onResize(int width, int height) {
        layout(width, height);
    }

    /**
     * Positions the eight on-screen control buttons : the movement pad in the bottom-left corner, the
     * jump button in the bottom-right, and the fly / add / remove buttons along the top. The margin to
     * the screen edges is proportional to the height so it scales with the resolution. The button size
     * itself is fixed (computed once at init) : the icons are batched, so re-scaling them would need a
     * re-batch ; positions stay consistent because they use that same fixed size.
     */
    private void layout(int width, int height) {
        float margin = UiHelper.screenMargin(height);
        place(buttonLeft, margin, margin + buttonSize);
        place(buttonBackward, margin + buttonSize + SPACING, margin + buttonSize);
        place(buttonForward, margin + buttonSize + SPACING, margin + buttonSize * 2 + SPACING);
        place(buttonRight, margin + (buttonSize + SPACING) * 2, margin + buttonSize);
        place(buttonJump, width - margin - buttonSize, margin + buttonSize);
        place(buttonFly, width - margin - 2 * buttonSize - SPACING, height - margin);
        place(buttonAction, width - margin - 3 * buttonSize - 2 * SPACING, height - margin);
        place(buttonRemoveBlock, margin, height - margin);
        place(buttonAddBlock, width - margin - buttonSize, height - margin);
    }

    /**
     * Shows or hides the contextual "Action" (door open/close) button. Driven by
     * {@code PlaceholderControl} : visible only while the player's cursor targets a door block.
     * Cheap and idempotent — only attaches/detaches the (un-batched) action button node.
     */
    public void setActionButtonVisible(boolean visible) {
        if (visible == actionButtonVisible) {
            return;
        }
        actionButtonVisible = visible;
        if (visible) {
            buttonParentNode.attachChild(actionButtonNode);
        } else {
            actionButtonNode.removeFromParent();
        }
    }

    private void place(IconButton button, float posx, float posy) {
        button.background.setLocalTranslation(posx, posy, -1);
        button.icon.setLocalTranslation(posx, posy, 0);
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
