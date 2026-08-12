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

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.ui.ConfirmDialog;
import org.delaunois.ialon.ui.IalonTheme;

import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

/**
 * "Leave Ialon ?" : the confirmation the player answers before the game actually stops.
 *
 * <p>Reached from the single exit gesture the game has — ESC on desktop, the back key on Android (see
 * {@code Ialon.requestExit}, which routes both). It is the last popup in the ESC chain : with a menu or an
 * in-world mode up, ESC closes that instead and never reaches here, so leaving still takes a deliberate
 * press with nothing else open.</p>
 *
 * <p>Worth a confirmation even though the world is durable — blocks are persisted as they are placed
 * (see {@link ChunkSaverState}) — because a mis-hit key ends a session outright : the player is thrown out
 * of the world, and coming back means the whole startup, terrain paging and all, which is a good few
 * seconds on a phone. The dialog says the building is safe so the answer is about the session only.</p>
 *
 * <p>Wears the <b>clear</b> veil and leaves the world drawn and audible behind it ({@link #hidesWorld()}
 * stays false) : the question is asked over the world the player is standing in, and a full-screen curtain
 * for a two-button dialog would read as having already left it.</p>
 *
 * @author Cedric de Launois
 */
@Slf4j
public class QuitConfirmState extends BaseAppState implements Resizable, Popup {

    private final IalonConfig config;
    // What "leave" does, handed in rather than decided here : it is platform business (see
    // Ialon.setExitHandler), and this state's job is only to ask the question.
    private final Runnable onLeave;

    private SimpleApplication app;
    // The confirmation dialog, while it is up.
    private Container confirmOverlay;

    public QuitConfirmState(IalonConfig config, Runnable onLeave) {
        this.config = config;
        this.onLeave = onLeave;
    }

    @Override
    protected void initialize(Application application) {
        this.app = (SimpleApplication) application;
        ScreenState.registerOn(app, this);
    }

    @Override
    protected void cleanup(Application application) {
        closeConfirm();
        ScreenState.unregisterFrom(application, this);
    }

    @Override
    protected void onEnable() {
        // Nothing : the state is driven by askConfirmation(), not by enable/disable.
    }

    @Override
    protected void onDisable() {
        // Nothing.
    }

    /**
     * Asks the player to confirm leaving the game, and stops the application if they do. Called on the
     * update thread ; a no-op while the dialog is already up, so a second press cannot stack two veils.
     */
    public void askConfirmation() {
        if (confirmOverlay != null) {
            return;
        }

        Container overlay = ConfirmDialog.createModalOverlay(config);
        confirmOverlay = overlay;

        Container dialog = ConfirmDialog.createDialogBox(app);
        dialog.addChild(ConfirmDialog.title(app, "Leave Ialon ?"), 0, 0);
        dialog.addChild(ConfirmDialog.title(app, ""), 1, 0);

        Button confirm = ConfirmDialog.button(app, "Leave");
        confirm.addClickCommands(source -> {
            closeConfirm();
            log.info("Leaving Ialon on the player's confirmation");
            onLeave.run();
        });
        Button cancel = ConfirmDialog.button(app, "Stay");
        cancel.addClickCommands(source -> closeConfirm());
        dialog.addChild(IalonTheme.dialogButtonRow(app.getCamera().getHeight(), confirm, cancel), 2, 0);

        ConfirmDialog.showOverScreen(app, overlay, dialog);
        // The veil swallows clicks, but not the camera drag : without this a finger sliding over the dialog
        // would still turn the view behind it.
        setPlayerTouchEnabled(false);
    }

    private void closeConfirm() {
        if (confirmOverlay != null) {
            confirmOverlay.removeFromParent();
            confirmOverlay = null;
            setPlayerTouchEnabled(true);
        }
    }

    private void setPlayerTouchEnabled(boolean enabled) {
        Optional.ofNullable(getState(PlayerState.class))
                .ifPresent(ps -> ps.setTouchEnabled(enabled));
    }

    /**
     * The dialog's plate and text are sized in screen units at build time, so a resize while it is up is
     * answered by building it again rather than by nudging it — the same handling as the other popups.
     */
    @Override
    public void onResize(int width, int height) {
        if (confirmOverlay != null) {
            closeConfirm();
            askConfirmation();
        }
    }

    // --- Popup ------------------------------------------------------------------------------------

    @Override
    public boolean isPopupOpen() {
        return confirmOverlay != null;
    }

    /** ESC (or the back key) while the question is up : the answer is "stay". */
    @Override
    public void closePopup() {
        closeConfirm();
    }
}
