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

import com.jme3.post.SceneProcessor;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.queue.RenderQueue;

/**
 * Base for one-shot offscreen {@link SceneProcessor}s that render a thumbnail in {@code postFrame} and
 * then remove themselves. Provides no-op implementations of the lifecycle callbacks such processors
 * never use ({@code preFrame}, {@code postQueue}, {@code cleanup}, {@code setProfiler}). Subclasses only
 * implement {@code initialize}, {@code reshape}, {@code isInitialized} and {@code postFrame}.
 *
 * @author Cedric de Launois
 */
abstract class AbstractPreviewProcessor implements SceneProcessor {

    @Override
    public void preFrame(float tpf) {
        // Nothing to do
    }

    @Override
    public void postQueue(RenderQueue rq) {
        // Nothing to do
    }

    @Override
    public void cleanup() {
        // Nothing to do
    }

    @Override
    public void setProfiler(AppProfiler profiler) {
        // Nothing to do
    }
}
