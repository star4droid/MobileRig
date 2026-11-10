package com.star4droid.mobirig.lwjgl3;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.star4droid.mobirig.Platform;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/** Desktop file picking with the native dialog (tinyfd). */
public class DesktopPlatform implements Platform {
    private final String startFile;

    public DesktopPlatform(String startFile) {
        this.startFile = startFile;
    }

    @Override
    public void initialModel(FileCallback callback) {
        if (startFile == null) return;
        FileHandle f = Gdx.files.absolute(startFile);
        if (f.exists()) callback.onPicked(f.name(), f.readBytes());
        else callback.onError("File not found: " + startFile);
    }
    @Override
    public void saveFile(final String suggestedName, final byte[] data, final SaveCallback callback) {
        new Thread(() -> {
            String path;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(1);
                filters.put(stack.UTF8("*.glb"));
                filters.flip();
                path = TinyFileDialogs.tinyfd_saveFileDialog("Save rigged GLB", suggestedName, filters, "GLB model (*.glb)");
            } catch (Throwable t) {
                final String msg = "Save dialog failed: " + t.getMessage();
                Gdx.app.postRunnable(() -> callback.onError(msg));
                return;
            }
            if (path == null) {
                Gdx.app.postRunnable(() -> callback.onError("Not saved"));
                return;
            }
            final String p = path.toLowerCase().endsWith(".glb") ? path : path + ".glb";
            try {
                Gdx.files.absolute(p).writeBytes(data, false);
                Gdx.app.postRunnable(() -> callback.onSaved(p));
            } catch (Exception e) {
                Gdx.app.postRunnable(() -> callback.onError("Could not save: " + e.getMessage()));
            }
        }, "save-dialog").start();
    }

    @Override
    public void pickModel(final FileCallback callback) {
        new Thread(() -> {
            String path;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(1);
                filters.put(stack.UTF8("*.glb"));
                filters.flip();
                path = TinyFileDialogs.tinyfd_openFileDialog("Open GLB model", "", filters, "GLB model (*.glb)", false);
            } catch (Throwable t) {
                final String msg = "File dialog failed: " + t.getMessage();
                Gdx.app.postRunnable(() -> callback.onError(msg));
                return;
            }
            if (path == null) {
                Gdx.app.postRunnable(() -> callback.onError("No file chosen"));
                return;
            }
            final FileHandle f = Gdx.files.absolute(path);
            try {
                final byte[] bytes = f.readBytes();
                Gdx.app.postRunnable(() -> callback.onPicked(f.name(), bytes));
            } catch (Exception e) {
                Gdx.app.postRunnable(() -> callback.onError("Could not read " + f.name() + ": " + e.getMessage()));
            }
        }, "file-dialog").start();
    }
}
