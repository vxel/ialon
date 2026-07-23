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
import com.simsilica.lemur.Panel;
import com.simsilica.lemur.event.DefaultMouseListener;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.control.ButtonHighlightControl;
import org.delaunois.ialon.ui.ButtonPlates;
import org.delaunois.ialon.ui.UiHelper;
import org.delaunois.ialon.ui.UiHelper.IconButton;

import java.util.Arrays;

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

    /** Slop added around the buttons' reserved touch zones (see {@link #isInReservedTouchZone}). */
    private static final float TOUCH_SLOP = SPACING;

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

    // The add ("+") and remove ("-") block buttons are kept OUT of the batched node (each in its own
    // node, like the action button) so they can be hidden on their own : the "build mode" (creation
    // capture) detaches them and shows the action button in the "+" slot instead.
    private final Node addBlockNode = new Node("AddBlockButton");
    private final Node removeBlockNode = new Node("RemoveBlockButton");
    private boolean buildMode = false;

    // All the button plates in a single geometry (they are flat coloured quads that differ only by
    // position and colour). The panels themselves stay in the graph, hidden, as the pick targets.
    private ButtonPlates plates;

    // All the button ICONS in a single geometry, the counterpart of the plates above : they all sample
    // the same texture atlas, so only the scene graph ever kept them apart. Re-batched by refreshIcons()
    // when the toggling buttons come and go.
    private RebatchableNode batchNode;

    // Outer edges of the two touch zones reserved to the walking controls : the movement pad owns the
    // bottom-LEFT corner of the screen (from 0,0 up to padMax*), the jump button the bottom-RIGHT one
    // (from jumpMinX to the right edge, up to jumpMaxY). Each zone is therefore fully described by the
    // bounds below. Kept in sync with the button layout by layout(...).
    private float movementPadMaxX;
    private float movementPadMaxY;
    private float jumpMinX;
    private float jumpMaxY;

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
        // Add/remove/action buttons live in their own nodes so build mode and the door cursor can hide them.
        // Only the BACKGROUND goes in : the icon is batched with all the others (see the BatchNode below),
        // and refreshIcons() takes it in and out of that batch as the node comes and goes.
        actionButtonNode.attachChild(buttonAction.background);
        addBlockNode.attachChild(buttonAddBlock.background);
        removeBlockNode.attachChild(buttonRemoveBlock.background);

        // EVERY HUD icon is one draw : they all come from the same texture atlas (UiHelper.createTextureButton
        // binds the shared TextureAtlasManager diffuse map), so nothing but the scene graph was keeping them
        // apart. The toggling ones (add / remove / action) join the batch too and are re-batched when their
        // visibility changes -- see refreshIcons(). Their BACKGROUNDS stay in the toggle nodes, which is what
        // ButtonPlates reads to decide which plates to draw, and what Lemur still picks.
        batchNode = new RebatchableNode("ButtonBatch");
        batchNode.attachChild(buttonLeft.icon);
        batchNode.attachChild(buttonBackward.icon);
        batchNode.attachChild(buttonForward.icon);
        batchNode.attachChild(buttonRight.icon);
        batchNode.attachChild(buttonJump.icon);
        batchNode.attachChild(buttonFly.icon);
        batchNode.attachChild(buttonAddBlock.icon);
        batchNode.attachChild(buttonRemoveBlock.icon);
        batchNode.batch();
        batchNode.getMaterial().setColor("Color", ColorRGBA.White);
        buttonParentNode.attachChild(batchNode);

        buttonParentNode.attachChild(buttonLeft.background);
        buttonParentNode.attachChild(buttonBackward.background);
        buttonParentNode.attachChild(buttonForward.background);
        buttonParentNode.attachChild(buttonRight.background);
        buttonParentNode.attachChild(buttonJump.background);
        buttonParentNode.attachChild(buttonFly.background);
        buttonParentNode.attachChild(addBlockNode);
        buttonParentNode.attachChild(removeBlockNode);

        // The plates are all flat coloured quads : draw them as one geometry instead of one per button.
        // The panels stay in the graph (hidden) — they remain the pick targets and own the colour.
        plates = new ButtonPlates(buttonParentNode, buttonLeft.background);
        plates.setPanels(Arrays.asList(
                buttonLeft.background, buttonBackward.background, buttonForward.background,
                buttonRight.background, buttonJump.background, buttonFly.background,
                buttonAddBlock.background, buttonRemoveBlock.background, buttonAction.background));

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
        // Touch zone reserved to the pad : the bounding box of the four arrows placed above (they are
        // laid out on 3 columns and 2 rows from the bottom-left corner), plus a bit of slop.
        movementPadMaxX = margin + buttonSize * 3 + SPACING * 2 + TOUCH_SLOP;
        movementPadMaxY = margin + buttonSize * 2 + SPACING + TOUCH_SLOP;
        place(buttonJump, width - margin - buttonSize, margin + buttonSize);
        // Same reservation for the jump button, in the opposite corner.
        jumpMinX = width - margin - buttonSize - TOUCH_SLOP;
        jumpMaxY = margin + buttonSize + TOUCH_SLOP;
        place(buttonFly, width - margin - 2 * buttonSize - SPACING, height - margin);
        place(buttonRemoveBlock, margin, height - margin);
        place(buttonAddBlock, width - margin - buttonSize, height - margin);
        // In build mode the action button takes the "+" (add block) slot ; otherwise it sits left of fly
        // (its contextual door-open position).
        if (buildMode) {
            place(buttonAction, width - margin - buttonSize, height - margin);
        } else {
            place(buttonAction, width - margin - 3 * buttonSize - 2 * SPACING, height - margin);
        }
        refreshPlates(); // the merged plates carry their own copy of the positions
    }

    /**
     * Tells whether a screen point falls in one of the touch zones reserved to the walking controls : the
     * bottom-left corner up to the outer edge of the movement pad, and the bottom-right corner around the
     * jump button. Inside them a drag must only press the button — it must never turn the camera,
     * otherwise the small finger travel while walking or jumping rotates the view (see
     * {@code PlayerHeadDirectionControl#onTouch}). Each zone runs all the way to the screen corner, which
     * also covers the margins around the buttons, where a finger sliding off one ends up.
     *
     * <p>Only reserved while the on-screen controls are actually displayed : with the buttons hidden
     * (desktop mouse-lock, photo mode) the whole screen belongs to the camera again.
     *
     * @param x the x coordinate of the touch, in GUI pixels (origin bottom-left)
     * @param y the y coordinate of the touch, in GUI pixels (origin bottom-left)
     * @return true if the point belongs to the movement pad or to the jump button zone
     */
    public boolean isInReservedTouchZone(float x, float y) {
        if (buttonParentNode.getParent() == null) {
            return false;
        }
        return (x <= movementPadMaxX && y <= movementPadMaxY)
                || (x >= jumpMinX && y <= jumpMaxY);
    }

    /**
     * Toggles "build mode" (creation capture) : hides the add/remove block buttons and moves the action
     * button into the "+" slot. The action button's visibility itself is driven by
     * {@code PlaceholderControl} (kept visible throughout capture).
     */
    public void setBuildMode(boolean buildMode) {
        if (buildMode == this.buildMode) {
            return;
        }
        this.buildMode = buildMode;
        if (buildMode) {
            addBlockNode.removeFromParent();
            removeBlockNode.removeFromParent();
        } else {
            buttonParentNode.attachChild(addBlockNode);
            buttonParentNode.attachChild(removeBlockNode);
        }
        refreshIcons(); // the + / - icons join or leave the merged icon sheet
        layout(app.getCamera().getWidth(), app.getCamera().getHeight());
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
        refreshIcons();  // the action icon joins / leaves the merged icon sheet
        refreshPlates(); // the action plate joins / leaves the merged geometry
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
        ButtonHighlightControl highlight = new ButtonHighlightControl(config.getInputActionManager(), actionName);
        // The panel no longer draws itself : the press colour has to reach the merged plate geometry.
        highlight.setOnHighlightChanged(this::refreshPlates);
        iconButton.background.addControl(highlight);
        return iconButton;
    }

    /** Re-reads the plate positions / colours into the merged geometry. Null-safe : buttons are created
     * before the plates exist, and their controls can fire a highlight during construction. */
    private void refreshPlates() {
        if (plates != null) {
            plates.refresh();
        }
    }

    /**
     * Re-batches the icon sheet so it holds exactly the icons that should be visible. The six movement
     * icons are always in ; add / remove / action come and go with build mode and the door cursor, and
     * a BatchNode merges whatever is attached when it batches — so membership has to be restated.
     *
     * <p>Only ever runs on a user-driven toggle, never in the render loop, and merges nine quads.
     */
    private void refreshIcons() {
        if (batchNode == null) {
            return; // toggles can fire while the buttons are still being built
        }
        setIconBatched(buttonAddBlock.icon, addBlockNode.getParent() != null);
        setIconBatched(buttonRemoveBlock.icon, removeBlockNode.getParent() != null);
        setIconBatched(buttonAction.icon, actionButtonNode.getParent() != null);
        batchNode.rebatch();
    }

    private void setIconBatched(Panel icon, boolean batched) {
        if (batched) {
            if (icon.getParent() == null) {
                batchNode.attachChild(icon);
            }
        } else {
            icon.removeFromParent();
        }
    }

    /**
     * A {@link BatchNode} that can be re-batched from scratch once its children have changed.
     *
     * <p>jME's own {@code batch()} only GROWS the batch after the first call : its incremental path folds
     * the existing batch geometry back into the merge, so detaching a child never drops its triangles.
     * The HUD needs the opposite — icons must be able to leave. Forcing a full rebatch is safe because
     * {@code gatherGeometries} skips the batch geometries themselves.
     */
    private static class RebatchableNode extends BatchNode {

        RebatchableNode(String name) {
            super(name);
        }

        void rebatch() {
            setNeedsFullRebatch(true);
            batch();
        }
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
