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

package org.delaunois.ialon.state;

import com.jme3.scene.Node;

/**
 * Diagnostic root/gui node that times its own per-frame {@code updateLogicalState} (all Controls in the
 * subtree run here) and {@code updateGeometricState} (transform + bounding-volume refresh of dirty
 * spatials), so {@link HitchProfilerState} can split the lumped {@code SpatialUpdate} step into
 * world-vs-HUD and controls-vs-bounds. Used ONLY when the hitch profiler is enabled (see
 * {@link org.delaunois.ialon.Ialon}); in normal runs the app keeps plain {@link Node}s. The overhead is
 * two {@code nanoTime} reads per call.
 *
 * @author Cedric de Launois
 */
public class TimingNode extends Node {

    private volatile long lastLogicalNanos;
    private volatile long lastGeometricNanos;

    public TimingNode(String name) {
        super(name);
    }

    @Override
    public void updateLogicalState(float tpf) {
        long t = System.nanoTime();
        super.updateLogicalState(tpf);
        lastLogicalNanos = System.nanoTime() - t;
    }

    @Override
    public void updateGeometricState() {
        long t = System.nanoTime();
        super.updateGeometricState();
        lastGeometricNanos = System.nanoTime() - t;
    }

    public long getLastLogicalNanos() {
        return lastLogicalNanos;
    }

    public long getLastGeometricNanos() {
        return lastGeometricNanos;
    }
}
