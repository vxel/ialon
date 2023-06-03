package org.delaunois.ialon;

import com.jme3.input.InputManager;
import com.jme3.input.controls.ActionListener;

import java.util.ArrayList;
import java.util.HashMap;

import lombok.Getter;
import lombok.Setter;

public class InputActionManager {

    private final HashMap<String, Mapping> mappings = new HashMap<>();

    @Getter
    @Setter
    private InputManager inputManager;

    private static class Mapping {
        private final String name;
        private final ArrayList<ActionListener> listeners = new ArrayList<>();

        public Mapping(String name) {
            this.name = name;
        }
    }

    public void triggerAction(String actionName, boolean isPressed) {
        Mapping mapping = mappings.get(actionName);
        for (ActionListener listener : mapping.listeners) {
            listener.onAction(mapping.name, isPressed, 0);
        }
    }

    public void addListener(ActionListener listener, String... mappingNames) {
        if (inputManager != null) {
            inputManager.addListener(listener, mappingNames);
        }

        for (String mappingName : mappingNames) {
            Mapping mapping = mappings.computeIfAbsent(mappingName, k -> new Mapping(mappingName));
            if (!mapping.listeners.contains(listener)) {
                mapping.listeners.add(listener);
            }
        }
    }

    public void removeListener(ActionListener listener) {
        if (inputManager != null) {
            inputManager.removeListener(listener);
        }

        for (Mapping mapping : mappings.values()) {
            mapping.listeners.remove(listener);
        }
    }

}
