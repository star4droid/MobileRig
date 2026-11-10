package com.star4droid.mobirig;

import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.input.GestureDetector;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.collision.BoundingBox;

/** Front (or side) orthographic view for placing markers: drag pans, pinch / wheel zooms. */
public class OrthoController extends InputAdapter implements GestureDetector.GestureListener {
    public final OrthographicCamera camera = new OrthographicCamera();
    private float frameH = 2f, cx, cy, frontZ = 5f;
    private boolean pinching;
    private final Vector2 lastMid = new Vector2();
    private float lastDist;

    public void frame(BoundingBox box, int w, int h) {
        cx = box.getCenterX();
        cy = box.getCenterY();
        frontZ = box.max.z + box.getDepth() + 5f;
        frameH = box.getHeight() * 1.12f;
        resize(w, h);
        camera.zoom = 1f;
        camera.position.set(cx, cy, frontZ);
        update();
    }

    public void resize(int w, int h) {
        float aspect = w / (float) Math.max(1, h);
        // fit the height on landscape screens and the width on narrow portrait screens
        float vh = frameH;
        float vw = vh * aspect;
        if (vw < frameH * 0.62f) {
            vw = frameH * 0.62f;
            vh = vw / aspect;
        }
        camera.viewportWidth = vw;
        camera.viewportHeight = vh;
        update();
    }

    public void reset() {
        camera.zoom = 1f;
        camera.position.set(cx, cy, frontZ);
        update();
    }

    public void update() {
        camera.direction.set(0, 0, -1);
        camera.up.set(0, 1, 0);
        camera.near = 0.01f;
        camera.far = frontZ * 2f + 100f;
        camera.update();
    }

    /** World units per screen pixel. */
    public float unitsPerPixel(int screenH) {
        return camera.viewportHeight * camera.zoom / Math.max(1, screenH);
    }

    private void pan(float dx, float dy, int screenH) {
        float s = unitsPerPixel(screenH);
        camera.position.add(-dx * s, dy * s, 0);
        update();
    }

    @Override
    public boolean scrolled(float ax, float ay) {
        camera.zoom = MathUtils.clamp(camera.zoom * (float) Math.pow(1.12, ay), 0.05f, 4f);
        update();
        return true;
    }

    @Override
    public boolean touchDown(float x, float y, int pointer, int button) {
        pinching = false;
        return false;
    }

    @Override
    public boolean tap(float x, float y, int count, int button) {
        if (count >= 2) {
            reset();
            return true;
        }
        return false;
    }

    @Override
    public boolean longPress(float x, float y) {
        return false;
    }

    @Override
    public boolean fling(float vx, float vy, int button) {
        return false;
    }

    @Override
    public boolean pan(float x, float y, float dx, float dy) {
        if (pinching) return true;
        pan(dx, dy, com.badlogic.gdx.Gdx.graphics.getHeight());
        return true;
    }

    @Override
    public boolean panStop(float x, float y, int pointer, int button) {
        return false;
    }

    @Override
    public boolean zoom(float initialDistance, float distance) {
        return false;
    }

    @Override
    public boolean pinch(Vector2 i1, Vector2 i2, Vector2 p1, Vector2 p2) {
        float midX = (p1.x + p2.x) * 0.5f, midY = (p1.y + p2.y) * 0.5f, d = p1.dst(p2);
        if (pinching && lastDist > 1f && d > 1f) {
            camera.zoom = MathUtils.clamp(camera.zoom * lastDist / d, 0.05f, 4f);
            pan(midX - lastMid.x, midY - lastMid.y, com.badlogic.gdx.Gdx.graphics.getHeight());
        }
        pinching = true;
        lastMid.set(midX, midY);
        lastDist = d;
        update();
        return true;
    }

    @Override
    public void pinchStop() {
        pinching = false;
    }
}
