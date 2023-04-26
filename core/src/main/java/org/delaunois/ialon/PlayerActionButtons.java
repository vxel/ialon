package org.delaunois.ialon;

import com.jme3.app.SimpleApplication;
import com.jme3.input.event.MouseButtonEvent;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.component.DynamicInsetsComponent;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.event.DefaultMouseListener;
import com.simsilica.lemur.event.MouseListener;

import org.delaunois.ialon.state.PlayerState;

import lombok.Getter;

public class PlayerActionButtons {

    private static final String ALPHA_DISCARD_THRESHOLD = "AlphaDiscardThreshold";
    private static final float SCREEN_MARGIN = 30;
    private static final float SPACING = 10;

    @Getter
    private final Node directionButtons;

    private final Container buttonLeft;
    private final Container buttonRight;
    private final Container buttonForward;
    private final Container buttonBackward;

    @Getter
    private final Container buttonJump;

    @Getter
    private final Container buttonAddBlock;

    @Getter
    private final Container buttonRemoveBlock;

    @Getter
    private final Container buttonFly;

    private final int buttonSize;
    private final SimpleApplication app;
    private final PlayerState playerState;

    public PlayerActionButtons(PlayerState playerState) {
        this.playerState = playerState;
        this.app = (SimpleApplication) playerState.getApplication();
        buttonSize = app.getCamera().getHeight() / 6;
        directionButtons = new Node();
        buttonLeft = createButton("Left", buttonSize, SCREEN_MARGIN, SCREEN_MARGIN + buttonSize,
                new DefaultMouseListener() {
                    @Override
                    public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                        event.setConsumed();
                        actionLeft(event.isPressed());
                    }
                });

        buttonBackward = createButton("Backward", buttonSize, SCREEN_MARGIN + buttonSize + SPACING, SCREEN_MARGIN + buttonSize,
                new DefaultMouseListener() {
                    @Override
                    public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                        event.setConsumed();
                        actionBackward(event.isPressed());
                    }
                });

        buttonForward = createButton("Forward", buttonSize, SCREEN_MARGIN + buttonSize + SPACING, SCREEN_MARGIN + buttonSize * 2 + SPACING,
                new DefaultMouseListener() {
                    @Override
                    public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                        event.setConsumed();
                        actionForward(event.isPressed());
                    }
                });

        buttonRight = createButton("Right", buttonSize, SCREEN_MARGIN + (buttonSize + SPACING) * 2, SCREEN_MARGIN + buttonSize,
                new DefaultMouseListener() {
                    @Override
                    public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                        event.setConsumed();
                        actionRight(event.isPressed());
                    }
                });

        buttonJump = createButton("Jump", buttonSize, playerState.getCamera().getWidth() - SCREEN_MARGIN - buttonSize, SCREEN_MARGIN + buttonSize,
                new DefaultMouseListener() {
                    @Override
                    public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                        event.setConsumed();
                        actionJump(event.isPressed());
                    }
                });

        buttonAddBlock = createButton("Add", buttonSize, playerState.getCamera().getWidth() - SCREEN_MARGIN - buttonSize, playerState.getCamera().getHeight() - SCREEN_MARGIN,
                new DefaultMouseListener() {
                    @Override
                    public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                        event.setConsumed();
                        actionAddBlock(event.isPressed());
                    }
                });

        buttonRemoveBlock = createButton("Remove", buttonSize, SCREEN_MARGIN, playerState.getCamera().getHeight() - SCREEN_MARGIN,
                new DefaultMouseListener() {
                    @Override
                    public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                        event.setConsumed();
                        actionRemoveBlock(event.isPressed());
                    }
                });

        buttonFly = createButton("Fly", buttonSize, playerState.getCamera().getWidth() - SCREEN_MARGIN - 2 * buttonSize - SPACING, playerState.getCamera().getHeight() - SCREEN_MARGIN,
                new DefaultMouseListener() {
                    @Override
                    public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                        event.setConsumed();
                        actionToggleFly(event.isPressed());
                    }
                });

        directionButtons.attachChild(buttonLeft);
        directionButtons.attachChild(buttonBackward);
        directionButtons.attachChild(buttonForward);
        directionButtons.attachChild(buttonRight);
    }

    public void actionAddBlock(boolean isPressed) {
        highlight(isPressed, buttonAddBlock);
        if (isPressed) {
            playerState.addBlock();
        }
    }

    public void actionRemoveBlock(boolean isPressed) {
        highlight(isPressed, buttonRemoveBlock);
        if (isPressed) {
            playerState.removeBlock();
        }
    }

    public void actionToggleFly(boolean isPressed) {
        playerState.toogleFly(isPressed);
        highlight(playerState.isFly(), buttonFly);
    }

    public void actionLeft(boolean isPressed) {
        highlight(isPressed, buttonLeft);
        playerState.actionLeft(isPressed);
    }

    public void actionRight(boolean isPressed) {
        highlight(isPressed, buttonRight);
        playerState.actionRight(isPressed);
    }

    public void actionForward(boolean isPressed) {
        highlight(isPressed, buttonForward);
        playerState.actionForward(isPressed);
    }

    public void actionBackward(boolean isPressed) {
        highlight(isPressed, buttonBackward);
        playerState.actionBackward(isPressed);
    }

    public void actionJump(boolean isPressed) {
        highlight(isPressed, buttonJump);
        playerState.actionJump(isPressed);
    }

    public void showControlButtons() {
        if (directionButtons.getParent() == null) {
            app.getGuiNode().attachChild(directionButtons);
            app.getGuiNode().attachChild(buttonJump);
            app.getGuiNode().attachChild(buttonAddBlock);
            app.getGuiNode().attachChild(buttonRemoveBlock);
            app.getGuiNode().attachChild(buttonFly);
        }
    }

    public void hideControlButtons() {
        if (directionButtons.getParent() != null) {
            directionButtons.removeFromParent();
            buttonJump.removeFromParent();
            buttonAddBlock.removeFromParent();
            buttonRemoveBlock.removeFromParent();
            buttonFly.removeFromParent();
        }
    }

    public void resize() {
        buttonJump.setLocalTranslation(app.getCamera().getWidth() - SCREEN_MARGIN - buttonSize, SCREEN_MARGIN + buttonSize, 1);
        buttonAddBlock.setLocalTranslation(app.getCamera().getWidth() - SCREEN_MARGIN - buttonSize, app.getCamera().getHeight() - SCREEN_MARGIN, 1);
        buttonRemoveBlock.setLocalTranslation(SCREEN_MARGIN, app.getCamera().getHeight() - SCREEN_MARGIN, 1);
        buttonFly.setLocalTranslation(app.getCamera().getWidth() - SCREEN_MARGIN - 2 * buttonSize - SPACING, app.getCamera().getHeight() - SCREEN_MARGIN, 1);
    }

    private Container createButton(String text, float size, float posx, float posy, MouseListener listener) {
        Container buttonContainer = new Container();
        buttonContainer.setName(text);
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

        buttonContainer.addMouseListener(listener);
        return buttonContainer;
    }

    private void highlight(boolean isPressed, Container button) {
        if (isPressed) {
            ((QuadBackgroundComponent)button.getBackground()).getColor().set(0.5f, 0.5f, 0.5f, 0.5f);
        } else {
            ((QuadBackgroundComponent)button.getBackground()).getColor().set(0, 0, 0, 0.5f);
        }
    }

}
