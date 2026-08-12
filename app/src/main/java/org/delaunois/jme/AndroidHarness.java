package org.delaunois.jme;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.jme3.app.Application;
import com.jme3.app.LegacyApplication;
import com.jme3.app.SimpleApplication;
import com.jme3.audio.AudioRenderer;
import com.jme3.input.JoyInput;
import com.jme3.input.TouchInput;
import com.jme3.input.android.AndroidSensorJoyInput;
import com.jme3.input.controls.TouchListener;
import com.jme3.input.controls.TouchTrigger;
import com.jme3.input.event.TouchEvent;
import com.jme3.system.AppSettings;
import com.jme3.system.SystemListener;
import com.jme3.system.android.JmeAndroidSystem;
import com.jme3.system.android.OGLESContext;
import com.jme3.util.AndroidLogHandler;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * <code>AndroidHarness</code> wraps a jme application object and runs it on
 * Android
 *
 * @author Kirill
 * @author larynx
 */
public class AndroidHarness extends Activity implements TouchListener, DialogInterface.OnClickListener, SystemListener {

    protected static final Logger logger = Logger.getLogger(AndroidHarness.class.getName());
    /**
     * The application class to start
     */
    protected String appClass = "jme3test.android.Test";
    /**
     * The jme3 application object
     */
    protected LegacyApplication app = null;

    /**
     * Sets the desired RGB size for the surfaceview.  16 = RGB565, 24 = RGB888.
     * (default = 24)
     */
    protected int eglBitsPerPixel = 24;

    /**
     * Sets the desired number of Alpha bits for the surfaceview.  This affects
     * how the surfaceview is able to display Android views that are located
     * under the surfaceview jME uses to render the scenegraph.
     * 0 = Opaque surfaceview background (fastest)
     * 1-&gt;7 = Transparent surfaceview background
     * 8 or higher = Translucent surfaceview background
     * (default = 0)
     */
    protected int eglAlphaBits = 0;

    /**
     * The number of depth bits specifies the precision of the depth buffer (jME default = 16).
     * <p>
     * Ialon asks for 24 on general principle : the scene spans a 0.2 near plane to a 4000 far plane, and
     * the resolvable depth step grows as {@code z^2 / (near * 2^bits)}, so 16 bits would leave tens of
     * world units unresolved out at the horizon. 24 buys a factor 256 and is universally available on the
     * GLES3 / minSdk 26 targets ({@code GL_OES_depth24}).
     * <p>
     * It is NOT, however, the cure for the distant-coastline wobble it was first reached for.
     * {@link #logDepthBufferPrecision()} confirmed 24 bits are actually granted on device, and the wobble
     * was still there — so depth-buffer resolution was never the mechanism (the arithmetic agrees: ~0.007
     * units of depth step at 130 units, against a 2-unit relief/water separation). That artifact is
     * addressed geometrically instead, in {@code FarTerrainState} and {@code Water.frag}.
     */
protected int eglDepthBits = 24;

    /**
     * Sets the number of samples to use for multisampling.<br>
     * Leave 0 (default) to disable multisampling.<br>
     * Set to 2 or 4 to enable multisampling.
     */
    protected int eglSamples = 0;

    /**
     * Set the number of stencil bits.
     * (default = 0)
     */
    protected int eglStencilBits = 0;

    /**
     * Set the desired frame rate.  If frameRate higher than 0, the application
     * will be capped at the desired frame rate.
     * (default = -1, no frame rate cap)
     */
    protected int frameRate = -1;

    /**
     * Sets the type of Audio Renderer to be used.
     * <p>
     * Android MediaPlayer / SoundPool can be used on all
     * supported Android platform versions (2.2+)<br>
     * OpenAL Soft uses an OpenSL backend and is only supported on Android
     * versions 2.3+.
     * <p>
     * Only use ANDROID_ static strings found in AppSettings
     *
     */
    protected String audioRendererType = AppSettings.ANDROID_OPENAL_SOFT;

    /**
     * If true Android Sensors are used as simulated Joysticks. Users can use the
     * Android sensor feedback through the RawInputListener or by registering
     * JoyAxisTriggers.
     */
    protected boolean joystickEventsEnabled = false;
    /**
     * If true KeyEvents are generated from TouchEvents
     */
    protected boolean keyEventsEnabled = true;
    /**
     * If true MouseEvents are generated from TouchEvents
     */
    protected boolean mouseEventsEnabled = true;
    /**
     * Flip X axis
     */
    protected boolean mouseEventsInvertX = false;
    /**
     * Flip Y axis
     */
    protected boolean mouseEventsInvertY = false;
    /**
     * if true finish this activity when the jme app is stopped
     */
    protected boolean finishOnAppStop = true;
    /**
     * set to false if you don't want the harness to handle the exit hook
     */
    protected boolean handleExitHook = true;
    /**
     * Title of the exit dialog, default is "Do you want to exit?"
     */
    protected String exitDialogTitle = "Do you want to exit?";
    /**
     * Message of the exit dialog, default is "Use your home key to bring this
     * app into the background or exit to terminate it."
     */
    protected String exitDialogMessage = "Use your home key to bring this app into the background or exit to terminate it.";
    /**
     * Set the screen window mode. If screenFullSize is true, then the
     * notification bar and title bar are removed and the screen covers the
     * entire display. If screenFullSize is false, then the notification bar
     * remains visible if screenShowTitle is true while screenFullScreen is
     * false, then the title bar is also displayed under the notification bar.
     */
    protected boolean screenFullScreen = true;
    /**
     * if screenShowTitle is true while screenFullScreen is false, then the
     * title bar is also displayed under the notification bar
     */
    protected boolean screenShowTitle = true;
    /**
     * Splash Screen picture Resource ID. If a Splash Screen is desired, set
     * splashPicID to the value of the Resource ID (i.e. R.drawable.picname). If
     * splashPicID = 0, then no splash screen will be displayed.
     */
    protected int splashPicID = 0;


    protected OGLESContext ctx;
    protected GLSurfaceView view = null;
    protected boolean isGLThreadPaused = true;
    protected ImageView splashImageView = null;
    protected FrameLayout frameLayout = null;
    // Protected so a subclass can recognise the back-key event in an overridden onTouch (MainActivity
    // answers it with the game's own quit confirmation instead of the harness's native dialog).
    protected static final String ESCAPE_EVENT = "TouchEscape";
    private boolean firstDrawFrame = true;
    private boolean inConfigChange = false;

    private static class DataObject {
        protected LegacyApplication app = null;
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        logger.log(Level.FINE, "onRetainNonConfigurationInstance");
        final DataObject data = new DataObject();
        data.app = this.app;
        inConfigChange = true;
        return data;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onCreate(Bundle savedInstanceState) {
        initializeLogHandler();

        logger.fine("onCreate");
        super.onCreate(savedInstanceState);

        if (screenFullScreen) {
            requestWindowFeature(Window.FEATURE_NO_TITLE);

            int flags = WindowManager.LayoutParams.FLAG_FULLSCREEN
                      | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            getWindow().setFlags(flags, flags);

            View decorView = getWindow().getDecorView();
            int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE;
            decorView.setSystemUiVisibility(uiOptions);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                getWindow().getAttributes().layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }

        } else {
            if (!screenShowTitle) {
                requestWindowFeature(Window.FEATURE_NO_TITLE);
            }
        }

        final DataObject data = (DataObject) getLastNonConfigurationInstance();
        if (data != null) {
            logger.log(Level.FINE, "Using Retained App");
            this.app = data.app;
        } else {
            // Discover the screen resolution
            //TODO try to find a better way to get a hand on the resolution
            WindowManager wind = this.getWindowManager();
            Display disp = wind.getDefaultDisplay();
            Point p = new Point();
            disp.getSize(p);
            Log.d("AndroidHarness", "Resolution from Window, width:" + p.x + ", height: " + p.y);


            // Create Settings
            logger.log(Level.FINE, "Creating settings");
            AppSettings settings = new AppSettings(true);
            settings.setEmulateMouse(mouseEventsEnabled);
            settings.setEmulateMouseFlipAxis(mouseEventsInvertX, mouseEventsInvertY);
            settings.setUseJoysticks(joystickEventsEnabled);
            settings.setEmulateKeyboard(keyEventsEnabled);

            settings.setBitsPerPixel(eglBitsPerPixel);
            settings.setAlphaBits(eglAlphaBits);
            settings.setDepthBits(eglDepthBits);
            settings.setSamples(eglSamples);
            settings.setStencilBits(eglStencilBits);

            settings.setResolution(p.x, p.y);
            settings.setAudioRenderer(audioRendererType);

            settings.setFrameRate(frameRate);

            // Create application instance
            try {
                if (app == null) {
                    @SuppressWarnings("unchecked")
                    Class<? extends LegacyApplication> clazz = (Class<? extends LegacyApplication>) Class.forName(appClass);
                    app = clazz.newInstance();
                }

                app.setSettings(settings);
                app.start();
            } catch (Exception ex) {
                handleError("Class " + appClass + " init failed", ex);
                setContentView(new TextView(this));
            }
        }

        ctx = (OGLESContext) app.getContext();
        view = ctx.createView(this);
        // store the glSurfaceView in JmeAndroidSystem for future use
        JmeAndroidSystem.setView(view);
        // AndroidHarness wraps the app as a SystemListener.
        ctx.setSystemListener(this);
        layoutDisplay();
    }

    @Override
    protected void onRestart() {
        logger.fine("onRestart");
        super.onRestart();
        if (app != null) {
            app.restart();
        }
    }

    @Override
    protected void onStart() {
        logger.fine("onStart");
        super.onStart();
    }

    @Override
    protected void onResume() {
        logger.fine("onResume");
        super.onResume();

        gainFocus();
    }

    @Override
    protected void onPause() {
        logger.fine("onPause");
        loseFocus();

        super.onPause();
    }

    @Override
    protected void onStop() {
        logger.fine("onStop");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        logger.fine("onDestroy");
        final DataObject data = (DataObject) getLastNonConfigurationInstance();
        if (data != null || inConfigChange) {
            logger.fine("In Config Change, not stopping app.");
        } else {
            if (app != null) {
                app.stop(!isGLThreadPaused);
            }
        }
        setContentView(new TextView(this));
        ctx = null;
        app = null;
        view = null;
        JmeAndroidSystem.setView(null);

        super.onDestroy();
    }

    public Application getJmeApplication() {
        return app;
    }

    /**
     * Called when an error has occurred. By default, will show an error message
     * to the user and print the exception/error to the log.
     */
    @Override
    public void handleError(final String errorMsg, final Throwable t) {
        String stackTrace = "";
        String title = "Error";

        if (t != null) {
            // Convert exception to string
            StringWriter sw = new StringWriter(100);
            t.printStackTrace(new PrintWriter(sw));
            stackTrace = sw.toString();
            title = t.toString();
        }

        final String finalTitle = title;
        final String finalMsg = (errorMsg != null ? errorMsg : "Uncaught Exception")
                + "\n" + stackTrace;

        logger.log(Level.SEVERE, finalMsg);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                AlertDialog dialog = new AlertDialog.Builder(AndroidHarness.this) // .setIcon(R.drawable.alert_dialog_icon)
                        .setTitle(finalTitle).setPositiveButton("Kill", AndroidHarness.this).setMessage(finalMsg).create();
                dialog.show();
            }
        });
    }

    /**
     * Called by the android alert dialog, terminate the activity and OpenGL
     * rendering
     *
     * @param dialog ignored
     * @param whichButton the button index
     */
    @Override
    public void onClick(DialogInterface dialog, int whichButton) {
        if (whichButton != -2) {
            if (app != null) {
                app.stop(true);
            }
            app = null;
            this.finish();
        }
    }

    /**
     * Gets called by the InputManager on all touch/drag/scale events
     */
    @Override
    public void onTouch(String name, TouchEvent evt, float tpf) {
        if (name.equals(ESCAPE_EVENT)) {
            switch (evt.getType()) {
                case KEY_UP:
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            AlertDialog dialog = new AlertDialog.Builder(AndroidHarness.this) // .setIcon(R.drawable.alert_dialog_icon)
                                    .setTitle(exitDialogTitle).setPositiveButton("Yes", AndroidHarness.this).setNegativeButton("No", AndroidHarness.this).setMessage(exitDialogMessage).create();
                            dialog.show();
                        }
                    });
                    break;
                default:
                    break;
            }
        }
    }

    public void layoutDisplay() {
        logger.log(Level.FINE, "Splash Screen Picture Resource ID: {0}", splashPicID);
        if (view == null) {
            logger.log(Level.FINE, "view is null!");
        }
        if (splashPicID != 0) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT,
                    Gravity.CENTER);

            frameLayout = new FrameLayout(this);
            splashImageView = new ImageView(this);

            Drawable drawable = this.getResources().getDrawable(splashPicID);
            if (drawable instanceof NinePatchDrawable) {
                splashImageView.setBackgroundDrawable(drawable);
            } else {
                splashImageView.setImageResource(splashPicID);
            }

            if (view != null) {
                if (view.getParent() != null) {
                    ((ViewGroup) view.getParent()).removeView(view);
                }
                frameLayout.addView(view);
            }

            if (splashImageView.getParent() != null) {
                ((ViewGroup) splashImageView.getParent()).removeView(splashImageView);
            }
            frameLayout.addView(splashImageView, lp);

            setContentView(frameLayout);
            logger.log(Level.FINE, "Splash Screen Created");
        } else {
            logger.log(Level.FINE, "Splash Screen Skipped.");
            setContentView(view);
        }
    }

    public void removeSplashScreen() {
        logger.log(Level.FINE, "Splash Screen Picture Resource ID: {0}", splashPicID);
        if (splashPicID != 0) {
            if (frameLayout != null) {
                if (splashImageView != null) {
                    this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            splashImageView.setVisibility(View.INVISIBLE);
                            frameLayout.removeView(splashImageView);
                        }
                    });
                } else {
                    logger.log(Level.FINE, "splashImageView is null");
                }
            } else {
                logger.log(Level.FINE, "frameLayout is null");
            }
        }
    }

    /**
     * Removes the standard Android log handler due to an issue with not logging
     * entries lower than INFO level and adds a handler that produces
     * JME formatted log messages.
     */
    protected void initializeLogHandler() {
        Logger log = LogManager.getLogManager().getLogger("");
        for (Handler handler : log.getHandlers()) {
            if (log.getLevel() != null && log.getLevel().intValue() <= Level.FINE.intValue()) {
                Log.v("AndroidHarness", "Removing Handler class: " + handler.getClass().getName());
            }
            log.removeHandler(handler);
        }
        Handler handler = new AndroidLogHandler();
        log.addHandler(handler);
        handler.setLevel(Level.ALL);
    }

    @Override
    public void initialize() {
        app.initialize();
        if (handleExitHook) {
            // remove existing mapping from SimpleApplication that stops the app
            // when the esc key is pressed (esc key = android back key) so that
            // AndroidHarness can produce the exit app dialog box.
            if (app.getInputManager().hasMapping(SimpleApplication.INPUT_MAPPING_EXIT)) {
                app.getInputManager().deleteMapping(SimpleApplication.INPUT_MAPPING_EXIT);
            }

            app.getInputManager().addMapping(ESCAPE_EVENT, new TouchTrigger(TouchInput.KEYCODE_BACK));
            app.getInputManager().addListener(this, new String[]{ESCAPE_EVENT});
        }
        logDepthBufferPrecision();
    }

    /**
     * Logs the depth buffer the driver ACTUALLY granted, next to the one that was requested.
     * <p>
     * {@code settings.setDepthBits()} is only a <b>request</b> : jME turns it into an EGL config
     * constraint and the config chooser silently falls back to a shallower buffer when it cannot match
     * (the desktop LWJGL backend ignores the value outright). So the request must never be assumed —
     * it has to be read back.
     * <p>
     * This was written to test whether the Android-only wobble of the distant coastline came from a
     * shallow depth buffer, the relief and the flat far water plane being only a couple of units apart
     * there. The answer was <b>no</b> : the device reports {@code requested=24 granted=24} and the wobble
     * was unchanged. Worth keeping anyway — it is the only way to know what the depth test is actually
     * working with, and "the request was honoured" is not something to assume on either platform.
     */
    private void logDepthBufferPrecision() {
        int[] depth = new int[1];
        int[] stencil = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_DEPTH_BITS, depth, 0);
        GLES20.glGetIntegerv(GLES20.GL_STENCIL_BITS, stencil, 0);
        if (depth[0] == 0) {
            // GL_DEPTH_BITS is deprecated in GLES3 and some drivers answer 0 : ask the default
            // framebuffer's depth attachment directly instead.
            GLES30.glGetFramebufferAttachmentParameteriv(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH,
                    GLES30.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE, depth, 0);
        }
        Log.i("AndroidHarness", "EGL surface : depth requested=" + eglDepthBits + " granted=" + depth[0]
                + " bits, stencil=" + stencil[0] + " bits, GL_VERSION=" + GLES20.glGetString(GLES20.GL_VERSION));
    }

    @Override
    public void reshape(int width, int height) {
        app.reshape(width, height);
    }

    public void update() {
        app.update();
        // call to remove the splash screen, if present.
        // call after app.update() to make sure no gap between
        // splash screen going away and app display being shown.
        if (firstDrawFrame) {
            removeSplashScreen();
            firstDrawFrame = false;
        }
    }

    @Override
    public void requestClose(boolean esc) {
        app.requestClose(esc);
    }

    @Override
    public void destroy() {
        if (app != null) {
            app.destroy();
        }
        if (finishOnAppStop) {
            finish();
        }
    }

    @Override
    public void gainFocus() {
        logger.fine("gainFocus");
        if (view != null) {
            view.onResume();
        }

        if (app != null) {
            //resume the audio
            AudioRenderer audioRenderer = app.getAudioRenderer();
            if (audioRenderer != null) {
                audioRenderer.resumeAll();
            }
            //resume the sensors (aka joysticks)
            if (isContextLive()) {
                JoyInput joyInput = app.getContext().getJoyInput();
                if (joyInput != null) {
                    if (joyInput instanceof AndroidSensorJoyInput) {
                        AndroidSensorJoyInput androidJoyInput = (AndroidSensorJoyInput) joyInput;
                        androidJoyInput.resumeSensors();
                    }
                }
            }
        }

        isGLThreadPaused = false;

        if (app != null) {
            app.gainFocus();
        }
    }

    /**
     * Whether the context is not merely present but still <b>created</b> — the condition for asking it
     * anything.
     * <p>
     * A destroyed context stays reachable through {@code app.getContext()} while its input handler is
     * gone, and {@code OGLESContext.getJoyInput()} dereferences that handler with no check of its own : a
     * plain null test on the context is therefore not enough, and a pause arriving after a shutdown (the
     * activity finishing on the app's own request) crashed with an NPE in {@link #loseFocus()}.
     * {@code deinitInThread} clears the created flag before dropping the handler, so this guard is never
     * the loser of that race.
     */
    private boolean isContextLive() {
        return app != null && app.getContext() != null && app.getContext().isCreated();
    }

    @Override
    public void loseFocus() {
        logger.fine("loseFocus");
        if (app != null) {
            app.loseFocus();
        }

        if (view != null) {
            view.onPause();
        }

        if (app != null) {
            //pause the audio
            AudioRenderer audioRenderer = app.getAudioRenderer();
            if (audioRenderer != null) {
                audioRenderer.pauseAll();
            }
            //pause the sensors (aka joysticks)
            if (isContextLive()) {
                JoyInput joyInput = app.getContext().getJoyInput();
                if (joyInput != null) {
                    if (joyInput instanceof AndroidSensorJoyInput) {
                        AndroidSensorJoyInput androidJoyInput = (AndroidSensorJoyInput) joyInput;
                        androidJoyInput.pauseSensors();
                    }
                }
            }
        }
        isGLThreadPaused = true;
    }
}