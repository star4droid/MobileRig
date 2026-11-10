package com.star4droid.mobirig.lwjgl3;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.scenes.scene2d.utils.UIUtils;
import org.lwjgl.glfw.GLFW;
import com.star4droid.mobirig.Main;

/** Launches the desktop (LWJGL3) application. */
public class Lwjgl3Launcher {
    public static void main(String[] args) {
        if (StartupHelper.startNewJvmIfRequired()) return; // This handles macOS support and helps on Windows.

        if (UIUtils.isLinux){
            // Prefer X11 when on Linux and there is a display.
            // This is only useful when lwjgl3Version is greater than 3.3.3 .
            // Doing this may be required to Snap-package this application or to publish via Steam.
            // It may require XWayland, a compatibility layer, on machines using Wayland.
            String display = System.getenv("DISPLAY");
            if (display != null && !display.isEmpty()) GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_X11);
        }

        // Creates a new Lwjgl3Application that runs your main class with the specified configuration.
        createApplication(args.length > 0 ? args[0] : null);
    }

    private static Lwjgl3Application createApplication(String startFile) {
        Main main = new Main(new DesktopPlatform(startFile));
        main.devMarkers = System.getProperty("mobirig.markers");   // markers json or "guess": rig automatically
        main.devPose = System.getProperty("mobirig.pose");         // pose to show after rigging
        main.devView = System.getProperty("mobirig.view");         // yaw,pitch,dist,targetY (factors of height)
        return new Lwjgl3Application(main, getDefaultConfiguration());
    }

    private static Lwjgl3ApplicationConfiguration getDefaultConfiguration() {
        Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
        configuration.setTitle("MobiRig");
        //// Vsync limits the frames per second to what your hardware can display, and helps eliminate
        //// screen tearing. This setting doesn't always work on Linux, so the line after is a safeguard.
        configuration.useVsync(true);
        //// Limits FPS to the refresh rate of the currently active monitor, plus 1 to try to match fractional
        //// refresh rates. The Vsync setting above should limit the actual FPS to match the monitor.
        configuration.setForegroundFPS(Lwjgl3ApplicationConfiguration.getDisplayMode().refreshRate + 1);
        //// If you remove the above line and set Vsync to false, you can get unlimited FPS, which can be
        //// useful for testing performance, but can also be very stressful to some hardware.
        //// You may also need to configure GPU drivers to fully disable Vsync; this can cause screen tearing.

        String size = System.getProperty("mobirig.window", "1280x800"); // e.g. -Dmobirig.window=430x900 to test phone layouts
        String[] wh = size.split("x");
        configuration.setWindowedMode(Integer.parseInt(wh[0]), Integer.parseInt(wh[1]));
        configuration.setBackBufferConfig(8, 8, 8, 8, 16, 0, 4);
        //// You can change these files; they are in lwjgl3/src/main/resources/ .
        //// They can also be loaded from the root of assets/ .
        configuration.setWindowIcon("libgdx128.png", "libgdx64.png", "libgdx32.png", "libgdx16.png");

        //// This could improve compatibility with Windows machines with buggy OpenGL drivers, Macs
        //// with Apple Silicon that have to emulate compatibility with OpenGL anyway, and more.
        //// This uses the dependency `com.badlogicgames.gdx:gdx-lwjgl3-angle` to function.
        //// You would need to add this line to lwjgl3/build.gradle , below the dependency on `gdx-backend-lwjgl3`:
        ////     implementation "com.badlogicgames.gdx:gdx-lwjgl3-angle:$gdxVersion"
        //// You can choose to add the following line and the mentioned dependency if you want; they
        //// are not intended for games that use GL30 (which is compatibility with OpenGL ES 3.0).
        //// Know that it might not work well in some cases.
//        configuration.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.ANGLE_GLES20, 0, 0);

        return configuration;
    }
}