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
}
