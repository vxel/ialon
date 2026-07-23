/**
 * Ialon, a block construction game
 * Copyright (C) 2022 Cédric de Launois
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.delaunois.ialon.state;

/**
 * A dismissable UI overlay (modal menu, settings panel, minimap, block picker...). Implemented by the
 * AppStates that own such an overlay so a single ESC handler in {@code Ialon} can close whichever popup
 * is currently open instead of quitting the game.
 *
 * @author Cedric de Launois
 */
public interface Popup {

    /** @return {@code true} if this popup is currently displayed. */
    boolean isPopupOpen();

    /** Closes this popup if it is open ; a no-op otherwise. */
    void closePopup();

    /**
     * Whether this popup, while open, hides the world completely — a full-screen panel wearing the opaque
     * veil ({@link org.delaunois.ialon.ui.IalonTheme#applyVeil}). The 3D scene is then not rendered at all
     * while it is up (see {@code Ialon.updateWorldRendering}), which is the whole point of stating this :
     * nothing in jME links "covered by the GUI" to "culled", so a full world — chunks, far terrain, sky —
     * otherwise keeps being drawn behind the curtain for nobody, at full cost in draw calls, battery and
     * heat.
     *
     * <p>Defaults to {@code false}, which is the safe answer : a HUD overlay (the block picker), a modal
     * wearing the clear veil, or any popup the world shows through must keep the world drawn.
     *
     * @return {@code true} if the world can stop being rendered while this popup is open
     */
    default boolean hidesWorld() {
        return false;
    }
}
