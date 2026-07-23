/**
 * Copyright (C) 2022 Cédric de Launois
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.delaunois.ialon.ui;

import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferUtils;
import com.simsilica.lemur.Panel;
import com.simsilica.lemur.component.QuadBackgroundComponent;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

import lombok.Getter;

/**
 * Draws the flat coloured plates behind the on-screen buttons as ONE geometry.
 *
 * <p>Every Lemur {@link Panel} is its own geometry with its own material, so the eight-or-nine button
 * plates of the HUD cost eight-or-nine draw calls for eight-or-nine untextured quads — a real share of
 * the frame on a device where the cost is per draw call, not per pixel. They differ only by position
 * and colour, so they merge into a single vertex-coloured quad sheet.
 *
 * <p>The Panels themselves are NOT removed : they stay in the scene graph, hidden with
 * {@link Spatial.CullHint#Always}, because they are what Lemur's picking collides with and what carries
 * the mouse listeners, the highlight control and the layout size. {@code Node.collideWith} ignores cull
 * hints, so a hidden panel is still a live button.
 *
 * <p>The plate colour is never decided here : {@link #refresh()} re-reads it from each panel's
 * {@link QuadBackgroundComponent}, which stays the single source of truth (the press highlight keeps
 * writing to it). Call {@code refresh()} whenever the buttons move, appear, disappear or highlight.
 *
 * @author Cedric de Launois
 */
public class ButtonPlates {

    private static final int VERTICES_PER_QUAD = 4;
    private static final int INDICES_PER_QUAD = 6;

    private final List<Panel> panels = new ArrayList<>();
    private final Mesh mesh = new Mesh();

    @Getter
    private final Geometry geometry;

    private final Node parent;
    private int quadCapacity = 0;

    /**
     * @param parent   the node the merged geometry is attached to (the same one holding the panels)
     * @param template one of the panels : its background material is cloned, so the plates render with
     *                 exactly the render state Lemur set up (alpha blend, no alpha discard)
     */
    public ButtonPlates(Node parent, Panel template) {
        this.parent = parent;
        this.geometry = new Geometry("ButtonPlates", mesh);
        this.geometry.setMaterial(plateMaterial(template));
    }

    /**
     * Clones the Lemur background material and switches it to vertex colours : one material for every
     * plate, each plate's own colour carried by its vertices.
     */
    private static Material plateMaterial(Panel template) {
        QuadBackgroundComponent background = (QuadBackgroundComponent) template.getBackground();
        Material material = background.getMaterial().getMaterial().clone();
        material.setBoolean("VertexColor", true);
        material.setColor("Color", ColorRGBA.White);
        return material;
    }

    /** Registers the panels whose plates this geometry draws, in back-to-front order. */
    public void setPanels(List<Panel> plates) {
        panels.clear();
        panels.addAll(plates);
        for (Panel panel : panels) {
            // Hidden, not detached : still picked, still highlighted, simply no longer drawn.
            panel.setCullHint(Spatial.CullHint.Always);
        }
        refresh();
    }

    /**
     * Rebuilds the quad sheet from the panels that are currently in the scene graph (the add / remove /
     * action buttons come and go with build mode and the door cursor), reading each one's position, size
     * and colour. Cheap enough to call on every layout change and every button press : a handful of
     * quads.
     */
    public void refresh() {
        List<Panel> visible = new ArrayList<>(panels.size());
        for (Panel panel : panels) {
            if (isAttached(panel)) {
                visible.add(panel);
            }
        }

        if (visible.isEmpty()) {
            geometry.removeFromParent();
            return;
        }

        // The Gui bucket is sorted by the GEOMETRY's world z, not by its vertices : with the plate depth
        // baked into the vertices the merged sheet would sit at z=0 like the icons, and end up painting
        // its 60%-black over them. Carry the plates' depth on the geometry instead, and emit the
        // vertices relative to it.
        float depth = plateDepth(visible);
        geometry.setLocalTranslation(0, 0, depth);

        ensureCapacity(visible.size());
        FloatBuffer positions = (FloatBuffer) mesh.getBuffer(VertexBuffer.Type.Position).getData();
        FloatBuffer colors = (FloatBuffer) mesh.getBuffer(VertexBuffer.Type.Color).getData();
        ShortBuffer indices = (ShortBuffer) mesh.getBuffer(VertexBuffer.Type.Index).getData();
        positions.clear();
        colors.clear();
        indices.clear();

        for (int i = 0; i < visible.size(); i++) {
            appendQuad(visible.get(i), i, depth, positions, colors, indices);
        }

        positions.limit(visible.size() * VERTICES_PER_QUAD * 3).position(0);
        colors.limit(visible.size() * VERTICES_PER_QUAD * 4).position(0);
        indices.limit(visible.size() * INDICES_PER_QUAD).position(0);
        mesh.getBuffer(VertexBuffer.Type.Position).updateData(positions);
        mesh.getBuffer(VertexBuffer.Type.Color).updateData(colors);
        mesh.getBuffer(VertexBuffer.Type.Index).updateData(indices);
        mesh.updateCounts();
        mesh.updateBound();

        if (geometry.getParent() == null) {
            parent.attachChild(geometry);
        }
    }

    /**
     * One plate quad. Lemur's convention puts the panel's local translation at its TOP-LEFT corner, the
     * size extending right and down, so the quad spans [x, x+w] x [y-h, y] at the panel's own z — which
     * is what keeps the plates behind their icons.
     */
    private void appendQuad(Panel panel, int quad, float depth,
                            FloatBuffer positions, FloatBuffer colors, ShortBuffer indices) {
        Vector3f at = panel.getLocalTranslation();
        Vector3f size = panel.getSize();
        float w = size.x > 0 ? size.x : panel.getPreferredSize().x;
        float h = size.y > 0 ? size.y : panel.getPreferredSize().y;
        float x = at.x;
        float y = at.y;
        float z = at.z - depth; // the geometry already carries `depth` (see refresh)

        positions.put(x).put(y - h).put(z);
        positions.put(x + w).put(y - h).put(z);
        positions.put(x + w).put(y).put(z);
        positions.put(x).put(y).put(z);

        ColorRGBA color = plateColor(panel);
        for (int i = 0; i < VERTICES_PER_QUAD; i++) {
            colors.put(color.r).put(color.g).put(color.b).put(color.a);
        }

        short base = (short) (quad * VERTICES_PER_QUAD);
        indices.put(base).put((short) (base + 1)).put((short) (base + 2));
        indices.put(base).put((short) (base + 2)).put((short) (base + 3));
    }

    /** The depth the merged geometry sits at : the frontmost plate, so none is ever drawn over an icon. */
    private static float plateDepth(List<Panel> visible) {
        float depth = visible.get(0).getLocalTranslation().z;
        for (Panel panel : visible) {
            depth = Math.min(depth, panel.getLocalTranslation().z);
        }
        return depth;
    }

    private static ColorRGBA plateColor(Panel panel) {
        if (panel.getBackground() instanceof QuadBackgroundComponent) {
            ColorRGBA color = ((QuadBackgroundComponent) panel.getBackground()).getColor();
            if (color != null) {
                return color;
            }
        }
        return ColorRGBA.White;
    }

    /** Whether the panel is currently in the graph under the node this geometry lives in. */
    private boolean isAttached(Panel panel) {
        for (Spatial spatial = panel; spatial != null; spatial = spatial.getParent()) {
            if (spatial == parent) {
                return true;
            }
        }
        return false;
    }

    private void ensureCapacity(int quads) {
        if (quads <= quadCapacity) {
            return;
        }
        quadCapacity = quads;
        mesh.clearBuffer(VertexBuffer.Type.Position);
        mesh.clearBuffer(VertexBuffer.Type.Color);
        mesh.clearBuffer(VertexBuffer.Type.Index);
        mesh.setBuffer(VertexBuffer.Type.Position, 3,
                BufferUtils.createFloatBuffer(quads * VERTICES_PER_QUAD * 3));
        mesh.setBuffer(VertexBuffer.Type.Color, 4,
                BufferUtils.createFloatBuffer(quads * VERTICES_PER_QUAD * 4));
        mesh.setBuffer(VertexBuffer.Type.Index, 3,
                BufferUtils.createShortBuffer(quads * INDICES_PER_QUAD));
    }
}
