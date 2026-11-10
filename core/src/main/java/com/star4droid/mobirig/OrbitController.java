package com.star4droid.mobirig;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.input.GestureDetector;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;

/**
 * Orbit camera for touch and mouse.
 * Touch: 1 finger orbit, 2 fingers pinch zoom + drag pan, double tap frame.
 * Mouse: left orbit, right/middle pan, wheel zoom, double click frame.
 */
public class OrbitController extends InputAdapter implements GestureDetector.GestureListener {
    public final PerspectiveCamera camera;
    public final Vector3 target = new Vector3(0, 0.9f, 0);
    public float yaw = 25f, pitch = 12f, distance = 3.2f;
    private float frameDistance = 3.2f;
    private final Vector3 frameTarget = new Vector3(0, 0.9f, 0);

    private int button = Input.Buttons.LEFT;
    private final Vector2 lastMid = new Vector2();
    private float lastDist;
    private boolean pinching;

    private final Vector3 tmp = new Vector3(), right = new Vector3(), up = new Vector3();

    public OrbitController(PerspectiveCamera camera) {
        this.camera = camera;
        update();
    }

    public void update() {
        pitch = MathUtils.clamp(pitch, -89f, 89f);
        distance = MathUtils.clamp(distance, 0.05f, 500f);
        float cp = MathUtils.cosDeg(pitch);
        camera.position.set(target).add(
            cp * MathUtils.sinDeg(yaw) * distance,
            MathUtils.sinDeg(pitch) * distance,
            cp * MathUtils.cosDeg(yaw) * distance);
        camera.up.set(Vector3.Y);
        camera.lookAt(target);
        camera.near = Math.max(0.01f, distance / 200f);
        camera.far = distance * 50f + 10f;
        camera.update();
    }

    /** Points the camera at the whole box. */
    public void frame(BoundingBox box) {
        box.getCenter(frameTarget);
        float r = Math.max(0.1f, box.getDimensions(tmp).len() * 0.5f);
        frameDistance = r / MathUtils.sinDeg(camera.fieldOfView * 0.5f) * 1.05f;
        reframe();
    }

    public void reframe() {
        target.set(frameTarget);
        distance = frameDistance;
        update();
    }

    /** Sets a standard view (front = 0, side = 90, back = 180). */
    public void view(float yawDeg, float pitchDeg) {
        yaw = yawDeg;
        pitch = pitchDeg;
        update();
    }

    private void orbit(float dx, float dy) {
        yaw -= dx * 0.35f;
        pitch += dy * 0.35f;
        update();
    }

    private void pan(float dx, float dy) {
        float s = distance * 0.0016f;
        right.set(camera.direction).crs(camera.up).nor();
        up.set(camera.up).nor();
        target.mulAdd(right, -dx * s).mulAdd(up, dy * s);
        update();
    }

    // ---- InputProcessor (mouse) ----
    @Override
    public boolean touchDown(int x, int y, int pointer, int btn) {
        if (pointer == 0) button = btn;
        return false;
    }

    @Override
    public boolean scrolled(float amountX, float amountY) {
        distance *= (float) Math.pow(1.12, amountY);
        update();
        return true;
    }

    // ---- GestureListener (touch + mouse drags) ----
    @Override
    public boolean touchDown(float x, float y, int pointer, int btn) {
        pinching = false;
        return false;
    }

    @Override
    public boolean tap(float x, float y, int count, int btn) {
        if (count >= 2) {
            reframe();
            return true;
        }
        return false;
    }

    @Override
    public boolean longPress(float x, float y) {
        return false;
    }

    @Override
    public boolean fling(float vx, float vy, int btn) {
        return false;
    }

    @Override
    public boolean pan(float x, float y, float dx, float dy) {
        if (pinching) return true;
        if (button == Input.Buttons.LEFT) orbit(dx, dy);
        else pan(dx, dy);
        return true;
    }

    @Override
    public boolean panStop(float x, float y, int pointer, int btn) {
        return false;
    }

    @Override
    public boolean zoom(float initialDistance, float d) {
        return false;
    }

    @Override
    public boolean pinch(Vector2 i1, Vector2 i2, Vector2 p1, Vector2 p2) {
        float midX = (p1.x + p2.x) * 0.5f, midY = (p1.y + p2.y) * 0.5f;
        float dist = p1.dst(p2);
        if (!pinching) {
            pinching = true;
        } else {
            if (dist > 1f && lastDist > 1f) {
                distance *= lastDist / dist;
            }
            pan(midX - lastMid.x, midY - lastMid.y);
        }
        lastMid.set(midX, midY);
        lastDist = dist;
        update();
        return true;
    }

    @Override
    public void pinchStop() {
        pinching = false;
    }
}
