package org.delaunois.ialon.control;

import com.jme3.input.controls.ActionListener;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.control.AbstractControl;
import com.simsilica.lemur.Panel;
import com.simsilica.lemur.component.QuadBackgroundComponent;

import org.delaunois.ialon.InputActionManager;

import java.util.function.Supplier;

import lombok.Getter;
import lombok.Setter;

public class ButtonHighlightControl extends AbstractControl implements ActionListener {

    private static final ColorRGBA HALF_WHITE = new ColorRGBA(0.5f, 0.5f, 0.5f, 0.5f);
    private static final ColorRGBA HALF_BLACK = new ColorRGBA(0f, 0f, 0f, 0.5f);
    private final InputActionManager inputActionManager;
    private final String actionName;

    @Getter
    @Setter
    private Supplier<Boolean> statusSupplier;

    public ButtonHighlightControl(InputActionManager inputActionManager, String actionName) {
        this.inputActionManager = inputActionManager;
        this.actionName = actionName;
        setEnabled(true);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (isEnabled()) {
            inputActionManager.addListener(this, actionName);
        } else {
            inputActionManager.removeListener(this);
        }
    }

    @Override
    protected void controlUpdate(float tpf) {
        // Nothing to do
    }

    @Override
    protected void controlRender(RenderManager rm, ViewPort vp) {
        // Nothing to do
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        if (statusSupplier == null) {
            highlight(isPressed);
        } else {
            highlight(Boolean.TRUE.equals(statusSupplier.get()));
        }
    }

    public void highlight(boolean enable) {
        if (getSpatial() instanceof Panel) {
            Panel panel = (Panel) getSpatial();
            if (panel.getBackground() instanceof QuadBackgroundComponent) {
                highlightQuad(enable, (QuadBackgroundComponent) panel.getBackground());
            }
        }
    }

    private void highlightQuad(boolean enable, QuadBackgroundComponent quad) {
        if (enable) {
            if (quad.getTexture() == null) {
                quad.setColor(HALF_WHITE);
            } else {
                quad.setColor(ColorRGBA.White);
            }
        } else {
            if (quad.getTexture() == null) {
                quad.setColor(HALF_BLACK);
            } else {
                quad.setColor(HALF_WHITE);
            }
        }
    }

}
