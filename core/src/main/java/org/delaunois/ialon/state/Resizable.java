/*
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

/**
 * Implemented by AppStates that own GUI elements which must be re-laid-out when the screen
 * resolution changes (window resize or fullscreen toggle, see {@link ScreenState}).
 * <p>
 * A Resizable state registers itself with {@link ScreenState#register(Resizable)} during its
 * {@code initialize()} ; {@code ScreenState} then calls {@link #onResize(int, int)} on every
 * registered state whenever the framebuffer is reshaped. The same {@code onResize} body should
 * be invoked from the state's own {@code initialize()} so that the initial layout and the
 * resize layout share a single source of truth and cannot drift apart.
 *
 * @author Cedric de Launois
 */
public interface Resizable {

    /**
     * Lays out (positions and, where relevant, resizes) this state's GUI elements for the given
     * screen size, expressed in logical GUI pixels.
     *
     * @param width  the new GUI width
     * @param height the new GUI height
     */
    void onResize(int width, int height);

}
