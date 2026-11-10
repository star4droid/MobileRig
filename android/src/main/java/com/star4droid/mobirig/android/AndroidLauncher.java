package com.star4droid.mobirig.android;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.star4droid.mobirig.Main;
import com.star4droid.mobirig.Platform;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Launches the Android application. Files are opened with the Storage Access Framework (no permissions). */
public class AndroidLauncher extends AndroidApplication implements Platform {
    private static final int PICK_MODEL = 4101;
    private FileCallback pending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AndroidApplicationConfiguration configuration = new AndroidApplicationConfiguration();
        configuration.useImmersiveMode = true;
        configuration.useGL30 = true;
        configuration.numSamples = 2;
        initialize(new Main(this), configuration);
    }

    @Override
    public void pickModel(FileCallback callback) {
        pending = callback;
        runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"model/gltf-binary", "application/octet-stream"});
            try {
                startActivityForResult(i, PICK_MODEL);
            } catch (Exception e) {
                deliverError("No file picker available: " + e.getMessage());
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_MODEL) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            deliverError("No file chosen");
            return;
        }
        final Uri uri = data.getData();
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                final byte[] bytes = out.toByteArray();
                final String name = displayName(uri);
                final FileCallback cb = pending;
                Gdx.app.postRunnable(() -> {
                    if (cb == null) return;
                    if (!name.toLowerCase().endsWith(".glb")) cb.onError("Please choose a .glb file (got " + name + ")");
                    else cb.onPicked(name, bytes);
                });
            } catch (Exception e) {
                deliverError("Could not read file: " + e.getMessage());
            }
        }, "glb-reader").start();
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        }
        String p = uri.getLastPathSegment();
        return p == null ? "model.glb" : p;
    }

    private void deliverError(final String msg) {
        final FileCallback cb = pending;
        Gdx.app.postRunnable(() -> {
            if (cb != null) cb.onError(msg);
        });
    }
}
