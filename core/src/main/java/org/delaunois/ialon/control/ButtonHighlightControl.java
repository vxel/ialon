package org.delaunois.ialon.control;

import com.jme3.input.controls.ActionListener;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.control.AbstractControl;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.component.QuadBackgroundComponent;

import org.delaunois.ialon.InputActionManager;

import java.util.function.Supplier;

import lombok.Getter;
import lombok.Setter;

public class ButtonHighlightControl extends AbstractControl implements ActionListener {

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
        if (getSpatial() instanceof Container) {
            if (enable) {
                ((QuadBackgroundComponent) ((Container) getSpatial()).getBackground())
                        .getColor().set(0.5f, 0.5f, 0.5f, 0.5f);
            } else {
                ((QuadBackgroundComponent) ((Container) getSpatial()).getBackground())
                        .getColor().set(0, 0, 0, 0.5f);
            }
        }
    }

}
