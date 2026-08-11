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

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.math.Vector3f;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.Chunk;
import org.delaunois.ialon.blocks.ChunkManager;
import org.delaunois.ialon.blocks.ChunkThreads;
import org.delaunois.ialon.blocks.WorldManager;
import org.delaunois.ialon.control.PlayerCharacterControl;
import org.delaunois.ialon.ui.ConfirmDialog;
import org.delaunois.ialon.ui.HudMessage;
import org.delaunois.ialon.ui.IalonTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import lombok.extern.slf4j.Slf4j;

/**
 * "Reset chunk" : puts the chunk column the player stands in back to the terrain the world generator
 * produces, discarding every edit made anywhere in it.
 *
 * <p>Reached from the on-screen <b>Reset chunk</b> button, which occupies the "-" slot while the
 * region-delete selection is up (a long press on "-", see {@code PlayerActionControl}) : the gesture that
 * opens a bulk deletion also offers its extreme form. Pressing it drops the selection and opens this
 * state's confirmation, and nothing happens until the player confirms — the reset destroys an arbitrary
 * amount of their own work and the game has no undo.</p>
 *
 * <p>What is reset is the whole vertical <b>column</b> above (chunkX, chunkZ), not just the 16³ chunk the
 * player occupies : see {@link WorldManager#resetColumn} for why the column is the right unit, and for
 * what the reset does beyond the blocks (minimap marker, far relief, far trees).</p>
 *
 * <p>The work is deliberately split across two threads. Regenerating the column is a good fraction of a
 * second of noise sampling on a phone, so it runs on this state's own (low-priority) thread ; only
 * installing the finished block arrays — an array swap per chunk — happens on the update thread, where
 * every other world edit happens. The player reads a HUD message during the short wait instead of
 * watching a frozen frame.</p>
 *
 * @author Cedric de Launois
 */
@Slf4j
public class ChunkResetState extends BaseAppState implements Resizable, Popup {

    // How long the closing confirmation stays on screen after the reset completes.
    private static final float CONFIRM_SECONDS = 3f;

    private final IalonConfig config;

    private SimpleApplication app;
    private HudMessage hud;
    private ExecutorService executorService;
    // The confirmation dialog, while it is up.
    private Container confirmOverlay;
    // Set from the moment a reset is confirmed until its chunks are installed, so a second request cannot
    // interleave with the generation already running.
    private boolean resetting;

    public ChunkResetState(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void initialize(Application application) {
        this.app = (SimpleApplication) application;
        hud = new HudMessage(app);
        executorService = Executors.newSingleThreadExecutor(
                ChunkThreads.factory("chunk-reset", ChunkThreads.GENERATION_PRIORITY));
        ScreenState.registerOn(app, this);
    }

    @Override
    protected void cleanup(Application application) {
        closeConfirm();
        hud.hide();
        executorService.shutdown();
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
     * Asks the player to confirm resetting the chunk column they stand in. Called on the update thread by
     * the "Reset chunk" button's handler, once it has dropped the block selection.
     */
    public void askConfirmation() {
        if (confirmOverlay != null || resetting) {
            return;
        }
        Vector3f playerLocation = config.getPlayerLocation();
        if (playerLocation == null) {
            log.warn("No player location : chunk reset ignored");
            return;
        }
        Vec3i column = ChunkManager.getChunkLocation(playerLocation);

        Container overlay = ConfirmDialog.createModalOverlay(config);
        confirmOverlay = overlay;

        Container dialog = ConfirmDialog.createDialogBox(app);
        dialog.addChild(ConfirmDialog.title(app, "Reset chunk " + column.x + ", " + column.z + " ?"), 0, 0);
        dialog.addChild(ConfirmDialog.title(app, "Everything you built here will be lost"), 1, 0);

        Button confirm = ConfirmDialog.button(app, "Reset");
        confirm.addClickCommands(source -> {
            closeConfirm();
            runReset(column.x, column.z);
        });
        Button cancel = ConfirmDialog.button(app, "Cancel");
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

    /**
     * Regenerates the column on the background thread, then installs it on the update thread.
     *
     * <p>The chunks are generated from their locations alone (the generator is a pure function of the world
     * seed), so nothing shared is touched here : the cached chunks are only overwritten in the enqueued
     * step, on the thread that owns every other block write.</p>
     */
    private void runReset(int chunkX, int chunkZ) {
        WorldManager worldManager = Optional
                .ofNullable(getState(PlayerState.class))
                .map(PlayerState::getWorldManager)
                .orElse(null);
        ChunkManager chunkManager = config.getChunkManager();
        if (worldManager == null || chunkManager == null) {
            log.warn("No world loaded : chunk reset skipped");
            return;
        }

        resetting = true;
        hud.show("Resetting chunk...");

        int gridHeight = config.getGridHeight();
        executorService.submit(() -> {
            try {
                List<Chunk> pristine = new ArrayList<>(gridHeight);
                for (int chunkY = 0; chunkY < gridHeight; chunkY++) {
                    pristine.add(chunkManager.generatePristineChunk(new Vec3i(chunkX, chunkY, chunkZ)));
                }
                app.enqueue(() -> installReset(chunkManager, worldManager, chunkX, chunkZ, pristine));
            } catch (Exception e) {
                log.error("Failed to regenerate chunk column ({}, {})", chunkX, chunkZ, e);
                app.enqueue(() -> {
                    resetting = false;
                    hud.show("Chunk reset failed", CONFIRM_SECONDS);
                });
            }
        });
    }

    /** Update thread : installs the pristine chunks, drops their save files, frees a buried player. */
    private void installReset(ChunkManager chunkManager, WorldManager worldManager,
                              int chunkX, int chunkZ, List<Chunk> pristine) {
        resetting = false;
        if (chunkManager != config.getChunkManager()) {
            // The world was switched while the column was being regenerated : this state outlives the world
            // (it is attached once), so the chunks in hand belong to a world that is gone.
            log.info("World switched during the chunk reset : dropping it");
            return;
        }
        boolean reset = !worldManager.resetColumn(chunkX, chunkZ, pristine).isEmpty();

        // The saved chunks go whether or not the column is still loaded : if the player walked far enough
        // for it to be paged out, its edits live in those files only, and they must not come back on reload.
        ChunkSaverState saver = getState(ChunkSaverState.class);
        if (saver != null) {
            for (Chunk chunk : pristine) {
                saver.asyncDelete(chunk.getLocation());
            }
        }

        if (reset) {
            freeBuriedPlayer();
        }
        hud.show(reset ? "Chunk reset" : "Chunk no longer loaded", CONFIRM_SECONDS);
    }

    /**
     * Lifts the player onto the restored surface if the reset buried them : they may well have been
     * standing in a cave they had dug or a room they had built inside the column, and that space is solid
     * ground again. Only ever moves them <b>up</b>, and only while their own cell is blocked, so a reset
     * made in the open air (the normal case) leaves them exactly where they stand.
     */
    private void freeBuriedPlayer() {
        PlayerCharacterControl player = Optional
                .ofNullable(getState(PlayerState.class))
                .map(PlayerState::getPlayerNode)
                .map(node -> node.getControl(PlayerCharacterControl.class))
                .orElse(null);
        Vector3f location = config.getPlayerLocation();
        if (player == null || location == null) {
            return;
        }

        Vec3i cell = ChunkManager.getBlockLocation(location);
        if (!isBlocked(cell.x, cell.y, cell.z) && !isBlocked(cell.x, cell.y + 1, cell.z)) {
            return; // still room enough to stand : nothing to do
        }

        for (int y = cell.y + 1; y < config.getMaxy() - 1; y++) {
            if (!isBlocked(cell.x, y, cell.z) && !isBlocked(cell.x, y + 1, cell.z)) {
                // The player location is the centre of the capsule, whose feet must land on the cell floor.
                Vector3f freed = new Vector3f(location.x, y + config.getPlayerHeight() / 2f, location.z);
                log.info("Player buried by the chunk reset : lifted from {} to {}", location, freed);
                player.setPhysicsLocation(freed);
                return;
            }
        }
        log.warn("No free space above the player after the chunk reset : leaving them at {}", location);
    }

    /** Whether a solid block occupies a cell (a liquid does not count : it is swimmable, not a burial). */
    private boolean isBlocked(int x, int y, int z) {
        Block block = config.getChunkManager()
                .getBlock(new Vector3f(x + 0.5f, y + 0.5f, z + 0.5f))
                .orElse(null);
        return block != null && block.isSolid();
    }

    private void setPlayerTouchEnabled(boolean enabled) {
        Optional.ofNullable(getState(PlayerState.class))
                .ifPresent(ps -> ps.setTouchEnabled(enabled));
    }

    @Override
    public void update(float tpf) {
        hud.update(tpf);
    }

    @Override
    public void onResize(int width, int height) {
        hud.layout(width, height);
    }

    // --- Popup ------------------------------------------------------------------------------------

    @Override
    public boolean isPopupOpen() {
        return confirmOverlay != null;
    }

    /** ESC : abandons the confirmation. A reset already under way cannot be called back. */
    @Override
    public void closePopup() {
        closeConfirm();
    }
}
