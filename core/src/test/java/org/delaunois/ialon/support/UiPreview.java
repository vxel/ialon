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

package org.delaunois.ialon.support;

import static org.delaunois.ialon.Ialon.IALON_STYLE;

import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AppState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.system.AppSettings;

import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Command;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.DefaultRangedValueModel;
import com.simsilica.lemur.FillMode;
import com.simsilica.lemur.GuiGlobals;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.Panel;
import com.simsilica.lemur.component.SpringGridLayout;
import com.simsilica.lemur.event.CursorEventControl;
import com.simsilica.lemur.event.DefaultCursorListener;
import com.simsilica.lemur.event.DefaultMouseListener;
import com.simsilica.lemur.event.MouseEventControl;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.IalonInitializer;
import org.delaunois.ialon.ui.IalonTheme;
import org.delaunois.ialon.ui.Slider;
import org.delaunois.ialon.ui.UiHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Steampunk UI chrome preview : boots the font + theme part of the game's bootstrap, builds one
 * screen holding every piece of chrome {@link IalonTheme} provides (framed popup, title, corner
 * buttons, menu buttons, a dialog box, a slider, a plated card), captures the GUI viewport to a PNG
 * and exits.
 *
 * <p>Its reason to exist is the one thing that is hard to eyeball in a static PNG of the artwork :
 * whether the chrome keeps the same thickness <em>relative to the screen</em> at any resolution.
 * Run it at two resolutions and compare the two PNGs scaled to the same display size — the frames
 * must look identical, the corners must be sharp (no smearing along the edges) and no nine-patch
 * may render inverted (which is what happens when a widget is smaller than its patch).
 *
 * <p>{@link ScreenshotHarness} cannot do this : it captures the <b>3D</b> viewport and never calls
 * {@code setupGui}, so no UI ever reaches its frames. This one skips the world entirely and
 * screenshots {@code guiViewPort}, like {@link FontPreview}.
 *
 * <pre>
 * ./gradlew :core:uiPreview                                   # both reference resolutions
 * ./gradlew :core:uiPreview --args="out.png 2400 1080"        # one specific size
 * </pre>
 *
 * @author Cedric de Launois
 */
public class UiPreview extends SimpleApplication {

    private static final Logger log = LoggerFactory.getLogger(UiPreview.class);

    /**
     * Frames rendered before the capture, so the atlas upload and the text meshes have landed.
     *
     * <p>Overridable with {@code -Dialon.uiPreview.frames=...} : the popup backdrop is animated (the steam
     * of {@link org.delaunois.ialon.ui.SteamBackground}), and a still taken at a fixed instant says nothing
     * about how it evolves. Rendering further before the capture samples the plume at a later time.
     */
    private static final int WARMUP_FRAMES = Integer.getInteger("ialon.uiPreview.frames", 10);

    private final Path output;
    private final Object lock = new Object();
    private volatile boolean finished;
    private int frames;
    private boolean capturing;

    public UiPreview(Path output) {
        super((AppState[]) null);
        this.output = output;
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length >= 3) {
            capture(Paths.get(args[0]), Integer.parseInt(args[1]), Integer.parseInt(args[2]));
            return;
        }
        Path dir = Paths.get(args.length > 0 ? args[0] : "core/build/screenshots");
        // The two sizes that matter : the resolution the artwork is authored for, and a phone-like
        // 1080p. Same chrome, same relative thickness — that is what the pair proves.
        capture(dir.resolve("ui-preview-1280x720.png"), 1280, 720);
        capture(dir.resolve("ui-preview-2400x1080.png"), 2400, 1080);
    }

    private static void capture(Path output, int width, int height) throws InterruptedException {
        UiPreview app = new UiPreview(output);
        AppSettings settings = new AppSettings(true);
        settings.setResolution(width, height);
        settings.setGammaCorrection(true); // sRGB pipeline (matches the game)
        settings.setRenderer(AppSettings.LWJGL_OPENGL32);
        settings.setAudioRenderer(null);
        settings.setVSync(false);
        settings.setTitle("UiPreview " + width + "x" + height);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false);

        app.start();
        long deadline = System.currentTimeMillis() + 60_000L;
        synchronized (app.lock) {
            while (!app.finished && System.currentTimeMillis() < deadline) {
                app.lock.wait(1000);
            }
        }
        if (!app.finished) {
            log.warn("Timed out before capturing {}", output);
        }
        app.stop(true);
    }

    @Override
    public void simpleInitApp() {
        IalonConfig config = new IalonConfig();
        config.getInputActionManager().setInputManager(inputManager);

        IalonInitializer.setupLogging();
        GuiGlobals.initialize(this);
        IalonInitializer.setupAtlasManager(this, config);
        IalonInitializer.setupAtlasFont(this, config);
        IalonInitializer.setupGui(this, config);

        // A saturated backdrop, not black : it shows through wherever the veil or a nine-patch's alpha
        // is wrong, which a dark background would hide.
        viewPort.setBackgroundColor(new ColorRGBA().setAsSrgb(0.16f, 0.34f, 0.46f, 1f));

        float width = cam.getWidth();
        float height = cam.getHeight();
        float vw = width / 100f;
        float vh = height / 100f;

        // ---- the popup itself : veil in the background layer, chrome in the border layer ----
        Container popup = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None),
                IALON_STYLE);
        IalonTheme.applyVeil(popup, assetManager, config);
        popup.setPreferredSize(new Vector3f(width, height, 0));
        popup.setLocalTranslation(0, height, 0);
        // The real popups carry a scroll catcher and a click consumer. Reproduced here because
        // assertChromeIsTransparentToPicking is only meaningful against a popup that HAS listeners : that
        // is what a chrome placed inside the popup would inherit and use to swallow card taps.
        CursorEventControl.addListenersToSpatial(popup, new DefaultCursorListener() { });
        popup.addMouseListener(new DefaultMouseListener() { });
        guiNode.attachChild(popup);

        float margin = UiHelper.screenMargin(height);

        // The title is not placed here : it is part of the chrome now, passed to applyChromeOver below, so
        // this preview exercises the same path the popups do (including the signboard nine-patch).

        // ---- corner buttons : sized by their text, so they exercise the smallest plate ----
        Button back = IalonTheme.cornerButton("Back", assetManager, height);
        Button close = IalonTheme.cornerButton("Close", assetManager, height);
        popup.attachChild(back);
        popup.attachChild(close);
        UiHelper.placeCornerButtons(back, close, width, height);
        assertClickSurvivesRestyle(back, height);
        assertClickSurvivesRestyle(close, height);

        // ---- the right-hand menu button column ----
        Container menu = column();
        menu.addChild(IalonTheme.menuButton("Play", assetManager, width, height), 0, 0);
        menu.addChild(IalonTheme.menuButton("New world", assetManager, width, height), 1, 0);
        menu.addChild(IalonTheme.menuButton("Creations", assetManager, width, height), 2, 0);
        place(popup, menu, width - 30 * vw, -10 * vh);

        // ---- the three plated cards : one per grid state, side by side ----
        // A card's state is which plate it wears (see CardGrid.buildCard), so the three belong in the same
        // shot : it is the only way to check that the metals really read apart and that none of the patches
        // renders inverted. Built by hand rather than through CardGrid.buildCard, which is package-private
        // to the state package.
        IalonTheme.Frame[] plates = {
                IalonTheme.Frame.PLATE_GOLD, IalonTheme.Frame.PLATE_BRONZE, IalonTheme.Frame.PLATE_SILVER,
        };
        String[] states = {"Selected", "Current", "Other"};
        for (int i = 0; i < plates.length; i++) {
            Container card = column();
            IalonTheme.applyPlate(card, plates[i], assetManager, height);
            Panel preview = new Panel();
            preview.setPreferredSize(new Vector3f(18 * vw, 14 * vh, 0));
            UiHelper.addBackground(preview, new ColorRGBA(0.10f, 0.09f, 0.08f, 1f), config);
            card.addChild(preview, 0, 0);
            Label caption = new Label(states[i], IALON_STYLE);
            caption.setFontSize(3 * vh);
            card.addChild(caption, 1, 0);
            place(popup, card, margin + i * 21 * vw, -14 * vh);
        }

        // ---- a slider : rail, filled part and cog thumb come from the theme's selectors ----
        Container sliderRow = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None),
                IALON_STYLE);
        Label sliderLabel = new Label("Relief", IALON_STYLE);
        sliderLabel.setFontSize(4 * vh);
        sliderLabel.setPreferredSize(new Vector3f(20 * vw, 8 * vh, 0));
        sliderRow.addChild(sliderLabel, 0, 0);
        Slider slider = new Slider(IALON_STYLE);
        slider.setPreferredSize(new Vector3f(34 * vw, 8 * vh, 0));
        slider.setModel(new DefaultRangedValueModel(0.3, 2.5, 1.6));
        sliderRow.addChild(slider, 0, 1);
        place(popup, sliderRow, margin, -50 * vh);

        // ---- a modal dialog box on top ----
        // On its own full-screen veil, as the confirmation dialogs are : the veil is invisible (it only
        // exists to swallow clicks), so the popup must come out unchanged around the dialog.
        Container veil = new Container(IALON_STYLE);
        IalonTheme.applyClearVeil(veil, config);
        veil.addMouseListener(new DefaultMouseListener() { });
        veil.setPreferredSize(new Vector3f(width, height, 0));
        popup.attachChild(veil);
        veil.setSize(new Vector3f(width, height, 0));
        veil.setLocalTranslation(0, 0, 15);

        Container dialog = IalonTheme.dialogBox(
                new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), assetManager, height);
        dialog.addChild(IalonTheme.dialogTitle("Delete \"World 1\" ?", width, height), 0, 0);
        dialog.addChild(IalonTheme.dialogButtonRow(height,
                IalonTheme.dialogButton("Delete", assetManager, width, height),
                IalonTheme.dialogButton("Cancel", assetManager, width, height)), 1, 0);
        place(veil, dialog, width / 2f - 28 * vw, -62 * vh);
        dialog.setLocalTranslation(dialog.getLocalTranslation().setZ(20));

        // A bright strip deliberately straddling the left tube : the chrome is laid over the popup, so
        // this must come out cut off by the tube rather than painted across it.
        Panel underTubeProbe = new Panel(14 * vw, 4 * vh, IALON_STYLE);
        UiHelper.addBackground(underTubeProbe, new ColorRGBA().setAsSrgb(0.85f, 0.25f, 0.2f, 1f), config);
        underTubeProbe.setLocalTranslation(-4 * vw, -40 * vh, 10);
        popup.attachChild(underTubeProbe);
        IalonTheme.sizeToPreferred(underTubeProbe);

        // The chrome goes on last : attached to the popup's parent, above everything the popup holds. The
        // resolution is put in the title on purpose — it labels each PNG of the pair being compared.
        IalonTheme.applyChromeOver(popup, assetManager, width, height,
                "Settings   " + (int) width + "x" + (int) height);
        assertChromeIsTransparentToPicking(popup);

        logSizeBudget(height);
    }

    private static Container column() {
        return new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
    }

    /**
     * Attaches a container to the popup at a popup-local position, and gives it a size.
     *
     * <p>The explicit {@code setSize} is the point : attaching with {@code attachChild} keeps the child
     * out of the parent's layout, so nothing ever sizes it and it stays at zero. That is not cosmetic —
     * a zero-sized {@link Slider} computes a negative visible range and throws
     * {@code "Size cannot be negative"} from its very first {@code updateLogicalState}. The real popups
     * hit the same trap and work around it the same way (see {@code WorldMenuState.sizePopupToScreen}).
     */
    private static void place(Container parent, Container child, float x, float y) {
        parent.attachChild(child);
        Vector3f size = child.getPreferredSize().clone();
        child.setSize(size);
        child.setLocalTranslation(x, y, 10);
    }

    /**
     * Checks the chrome laid over a popup cannot steal a tap.
     *
     * <p>Reproduces Lemur's {@code PickEventSession.findHitTarget} : it walks UP from the hit geometry and
     * returns the first ancestor carrying a cursor / mouse listener ; a collision that resolves to nothing
     * is skipped and the pick falls through to whatever is behind. So the chrome, which covers the whole
     * popup, is only harmless as long as NO ancestor of its geometry has a listener — which is exactly why
     * it is attached as a sibling of the popup rather than inside it. Inside, every tap would resolve to
     * the popup's own scroll catcher, which consumes it, and no card could ever be selected again.
     */
    private void assertChromeIsTransparentToPicking(Node popup) {
        Spatial chrome = null;
        for (Spatial sibling : popup.getParent().getChildren()) {
            if (sibling != popup && sibling instanceof Node && !((Node) sibling).getChildren().isEmpty()) {
                chrome = sibling;
            }
        }
        // Also look INSIDE the popup, so a regression that puts the chrome back there is caught here
        // rather than only on a device.
        for (Spatial child : popup.getChildren()) {
            if (chrome == null && child instanceof Node && !((Node) child).getChildren().isEmpty()
                    && !(child instanceof Panel)) {
                chrome = child;
            }
        }
        if (chrome == null) {
            log.error("BROKEN chrome : no chrome node found beside the popup");
            return;
        }
        Spatial listener = firstListenerAncestor(deepestGeometry(chrome));
        if (listener != null) {
            log.error("BROKEN chrome : a tap on it resolves to {} ('{}'), which will swallow card taps",
                    listener.getClass().getSimpleName(), listener.getName());
        } else {
            log.info("chrome '{}' is transparent to picking (no listener on its ancestors)", chrome.getName());
        }
    }

    private static Spatial deepestGeometry(Spatial root) {
        if (root instanceof Node) {
            for (Spatial child : ((Node) root).getChildren()) {
                Spatial found = deepestGeometry(child);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        return root;
    }

    /** Mirrors {@code PickEventSession.findHitTarget}. */
    private static Spatial firstListenerAncestor(Spatial hit) {
        for (Spatial s = hit; s != null; s = s.getParent()) {
            if (s.getControl(CursorEventControl.class) != null || s.getControl(MouseEventControl.class) != null) {
                return s;
            }
        }
        return null;
    }

    /**
     * Replays the exact sequence every popup performs on a header button — build it, add the click
     * command, then restyle it from {@code layout(width, height)} — and checks the button still responds.
     *
     * <p>This is here because that sequence once silently broke every Back / Close button in the game :
     * the restyle used {@code Button.setButtonCommands}, which clears the whole command map, taking the
     * click handler with it. The buttons still looked perfect, so nothing but clicking them showed it.
     * Logged rather than thrown, so a regression shows up in the run output next to the screenshot.
     */
    private void assertClickSurvivesRestyle(Button button, float height) {
        final boolean[] fired = {false};
        button.addClickCommands(source -> fired[0] = true);
        IalonTheme.restyleCornerButton(button, assetManager, height);
        IalonTheme.restyleCornerButton(button, assetManager, height); // a second resize must not stack handlers

        List<Command<? super Button>> click = button.getCommands(Button.ButtonAction.Click);
        int clickCount = click == null ? 0 : click.size();
        for (int i = 0; i < clickCount; i++) {
            click.get(i).execute(button);
        }
        int downCount = size(button.getCommands(Button.ButtonAction.Down));

        // Pressing must not cost the button its background geometry. That geometry is what Lemur picks
        // against, and its release handler only fires the click when the pointer is still over the same
        // spatial (target == capture). Losing it on press made the plate vanish AND swallowed the click,
        // but only when the press landed on the plate rather than on the glyphs.
        int plateBefore = backgroundGeometries(button);
        runCommands(button, Button.ButtonAction.Down);
        int plateDown = backgroundGeometries(button);
        runCommands(button, Button.ButtonAction.Up);
        int plateUp = backgroundGeometries(button);

        if (!fired[0] || clickCount != 1 || downCount != 1
                || plateBefore != 1 || plateDown != 1 || plateUp != 1) {
            log.error("BROKEN '{}' : click commands={} (fired={}), press-swap commands={},"
                            + " background geometries idle/down/up={}/{}/{} — expected 1/true/1 and 1/1/1",
                    button.getText(), clickCount, fired[0], downCount, plateBefore, plateDown, plateUp);
        } else {
            log.info("'{}' survives two restyles and a press : 1 click command, 1 press swap,"
                    + " plate geometry kept ({})", button.getText(), plateBefore);
        }
    }

    private static void runCommands(Button button, Button.ButtonAction action) {
        List<Command<? super Button>> commands = button.getCommands(action);
        if (commands != null) {
            for (int i = 0; i < commands.size(); i++) {
                commands.get(i).execute(button);
            }
        }
    }

    /** Counts the geometries a nine-patch background contributes to a button (it names them "background"). */
    private static int backgroundGeometries(Button button) {
        int count = 0;
        for (Spatial child : button.getChildren()) {
            if ("background".equals(child.getName())) {
                count++;
            }
        }
        return count;
    }


    private static int size(List<Command<? super Button>> commands) {
        return commands == null ? 0 : commands.size();
    }

    /**
     * A nine-patch renders inverted on a widget smaller than its patch, and that failure is easy to
     * miss in a screenshot. Log each frame's floor so the numbers can be checked against the widget
     * sizes above.
     */
    private void logSizeBudget(float height) {
        for (IalonTheme.Frame frame : IalonTheme.Frame.values()) {
            Vector3f min = frame.minimumSize(height);
            log.info("{} : imageScale={}, needs a widget of at least {}x{} px",
                    frame, IalonTheme.imageScale(height), (int) min.x, (int) min.y);
        }
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (capturing) {
            return;
        }
        if (++frames < WARMUP_FRAMES) {
            return;
        }
        capturing = true;
        // The GUI viewport renders last, so its postFrame sees the composited image.
        guiViewPort.addProcessor(new ScreenshotProcessor(this, output, () -> {
            synchronized (lock) {
                finished = true;
                lock.notifyAll();
            }
        }));
    }
}
