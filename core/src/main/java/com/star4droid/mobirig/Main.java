package com.star4droid.mobirig;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Cubemap;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.graphics.glutils.HdpiUtils;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.input.GestureDetector;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.star4droid.mobirig.rig.GlbExporter;
import com.star4droid.mobirig.rig.Markers;
import com.star4droid.mobirig.rig.Poses;
import com.star4droid.mobirig.rig.RigException;
import com.star4droid.mobirig.rig.RigPipeline;
import com.star4droid.mobirig.rig.Skeleton;
import com.star4droid.mobirig.ui.Hud;
import com.star4droid.mobirig.ui.Theme;
import net.mgsx.gltf.loaders.glb.GLBLoader;
import net.mgsx.gltf.scene3d.attributes.PBRCubemapAttribute;
import net.mgsx.gltf.scene3d.attributes.PBRTextureAttribute;
import net.mgsx.gltf.scene3d.lights.DirectionalLightEx;
import net.mgsx.gltf.scene3d.scene.Scene;
import net.mgsx.gltf.scene3d.scene.SceneAsset;
import net.mgsx.gltf.scene3d.scene.SceneManager;
import net.mgsx.gltf.scene3d.shaders.PBRShaderConfig;
import net.mgsx.gltf.scene3d.shaders.PBRShaderProvider;
import net.mgsx.gltf.scene3d.utils.IBLBuilder;

/** MobiRig: Import/Orient → Markers → Rig → Test & Export. */
public class Main extends ApplicationAdapter {
    public static final int MAX_BONES = 70;

    enum Step { ORIENT, MARKERS, RIGGING, TEST }

    private final Platform platform;
    /** Desktop testing: markers JSON path or "guess" to rig automatically after the start file loads. */
    public String devMarkers;
    /** Desktop testing: pose to show after rigging ("Markers" stops at the markers step). */
    public String devPose;
    /** Desktop testing: "yaw,pitch,distanceFactor,targetHeightFactor" camera after rigging. */
    public String devView;
    /** Desktop testing: simulate a phone screen density (1 = 160 dpi). */
    public float devDensity = 1f;

    private SceneManager sceneManager;
    private PerspectiveCamera camera;
    private OrbitController orbit;
    private final OrthoController ortho = new OrthoController();
    private ShapeRenderer shapes;
    private SpriteBatch batch;
    private Cubemap diffuseCubemap, specularCubemap;
    private Texture brdfLUT;

    private Model mannequinModel;
    private SceneAsset asset;
    private Scene scene;
    private String modelName = "Mannequin";
    private byte[] modelBytes;
    private boolean mirrored;

    private Step step = Step.ORIENT;
    private RigPipeline pipeline;
    private Markers.Kind selected = Markers.Kind.CHIN;
    private Markers.Kind dragging;
    private boolean dragMirror;
    private float dragOffset;
    private volatile String progressText;
    private volatile float progressF;

    private Node[] jointNodes;
    private Pose currentPoseHolder = new Pose();
    private boolean walking, showBones = true;
    private float walkTime;
    private int weightBone = -2; // -2 off, -1 all bones, >=0 one bone
    private Array<Poses.Pose> poses;
    private String rigSummary = "";

    private Theme theme;
    private Hud hud;
    private com.star4droid.mobirig.ui.MarkerGuide guide;
    private boolean fingersEnabled = true;
    private final com.badlogic.gdx.graphics.OrthographicCamera lensCam = new com.badlogic.gdx.graphics.OrthographicCamera();
    private final Rectangle lensRect = new Rectangle();
    private Stage stage;
    private float density = 1f;
    private final Rectangle view = new Rectangle();
    private boolean framedForView;

    private final BoundingBox box = new BoundingBox();
    private final Matrix4 tmpM = new Matrix4();
    private final Vector3 tmpV = new Vector3(), tmpV2 = new Vector3();

    private static final class Pose {
        Poses.Pose pose;
    }

    public Main() {
        this(Platform.NONE);
    }

    public Main(Platform platform) {
        this.platform = platform;
    }

    @Override
    public void create() {
        PBRShaderConfig config = PBRShaderProvider.createDefaultConfig();
        config.numBones = MAX_BONES;
        config.numDirectionalLights = 1;
        config.numPointLights = 0;
        config.numSpotLights = 0;
        sceneManager = new SceneManager(PBRShaderProvider.createDefault(config),
            PBRShaderProvider.createDefaultDepth(MAX_BONES));
        camera = new PerspectiveCamera(45f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        sceneManager.setCamera(camera);
        orbit = new OrbitController(camera);

        DirectionalLightEx light = new DirectionalLightEx();
        light.direction.set(-0.6f, -1f, -0.8f).nor();
        light.color.set(Color.WHITE);
        light.intensity = 1.1f;
        sceneManager.environment.add(light);
        IBLBuilder ibl = IBLBuilder.createOutdoor(light);
        diffuseCubemap = ibl.buildIrradianceMap(64);
        specularCubemap = ibl.buildRadianceMap(6);
        ibl.dispose();
        brdfLUT = new Texture(Gdx.files.classpath("net/mgsx/gltf/shaders/brdfLUT.png"));
        sceneManager.setAmbientLight(0.7f);
        sceneManager.environment.set(new PBRTextureAttribute(PBRTextureAttribute.BRDFLUTTexture, brdfLUT));
        sceneManager.environment.set(PBRCubemapAttribute.createSpecularEnv(specularCubemap));
        sceneManager.environment.set(PBRCubemapAttribute.createDiffuseEnv(diffuseCubemap));

        shapes = new ShapeRenderer();
        batch = new SpriteBatch();
        mannequinModel = Mannequin.build();
        setScene(new Scene(new ModelInstance(mannequinModel)), "Mannequin", true);

        boolean android = Gdx.app.getType() == Application.ApplicationType.Android;
        density = android ? Math.max(1f, Gdx.graphics.getDensity()) : Math.max(0.75f, devDensity);
        theme = new Theme(density);
        ScreenViewport vp = new ScreenViewport();
        vp.setUnitsPerPixel(1f / density);
        stage = new Stage(vp, batch);
        hud = new Hud(theme, stage);
        guide = new com.star4droid.mobirig.ui.MarkerGuide(theme);
        stage.addActor(guide.actor());
        applyInsets();
        showOrient();
        setInput();

        platform.initialModel(new Platform.FileCallback() {
            @Override
            public void onPicked(String name, byte[] data) {
                loadGlb(name, data);
                if (devMarkers != null && modelBytes != null) devAutoRig();
            }

            @Override
            public void onError(String message) {
                hud.toast(message, true);
            }
        });
    }

    // ------------------------------------------------------------------ input

    private void setInput() {
        InputMultiplexer mx = new InputMultiplexer();
        mx.addProcessor(stage);
        if (step == Step.MARKERS) {
            mx.addProcessor(new MarkerInput());
            mx.addProcessor(new GestureDetector(ortho));
            mx.addProcessor(ortho);
        } else if (step != Step.RIGGING) {
            mx.addProcessor(new GestureDetector(orbit));
            mx.addProcessor(orbit);
        }
        Gdx.input.setInputProcessor(mx);
    }

    /** Drags markers. On touch screens the marker sits above the finger so it stays visible. */
    private class MarkerInput extends InputAdapter {
        @Override
        public boolean touchDown(int x, int y, int pointer, int button) {
            if (pointer > 0 || pipeline == null) return false;
            float best = 36f * density;
            Markers.Kind found = null;
            boolean mir = false;
            for (Markers.Kind k : Markers.Kind.values()) {
                for (int m = 0; m < (k.center ? 1 : 2); m++) {
                    project(k, m == 1, tmpV);
                    float d = Vector3.dst(tmpV.x, Gdx.graphics.getHeight() - tmpV.y, 0, x, y, 0);
                    if (d < best) {
                        best = d;
                        found = k;
                        mir = m == 1;
                    }
                }
            }
            if (found == null) return false;
            hud.setSheetHidden(true);
            dragging = found;
            dragMirror = mir;
            dragOffset = Gdx.app.getType() == Application.ApplicationType.Android ? 56f * density : 0f;
            if (selected != found) {
                selected = found;
                showMarkers();
            }
            moveMarker(x, y);
            return true;
        }

        @Override
        public boolean touchDragged(int x, int y, int pointer) {
            if (dragging == null) return false;
            moveMarker(x, y);
            return true;
        }

        @Override
        public boolean touchUp(int x, int y, int pointer, int button) {
            if (dragging == null) return false;
            dragging = null;
            hud.setSheetHidden(false);
            return true;
        }
    }

    private void moveMarker(int sx, int sy) {
        tmpV.set(sx, sy - dragOffset, 0);
        ortho.camera.unproject(tmpV, view.x, view.y, view.width, view.height);
        Vector3 m = pipeline.markers.get(dragging);
        m.set(dragMirror ? -tmpV.x : tmpV.x, tmpV.y, 0);
        pipeline.markers.constrain(pipeline.mesh, dragging);
    }

    // ------------------------------------------------------------------ panels

    private void showOrient() {
        step = Step.ORIENT;
        Hud.Panel p = new Hud.Panel();
        if (modelBytes == null) {
            p.title = "Import a character";
            p.hint = "Open a .glb model. Rigging runs fully offline on this device.";
            p.primary = new Hud.Item(Theme.I_OPEN, "Open GLB", this::openModel);
        } else {
            p.title = "Orient the model";
            p.hint = "Stand the character upright, facing you. Use Front to check.";
            p.toolsTitle = "Turn & fix";
            p.tools.add(new Hud.Item(Theme.I_ROT, "Rotate X", () -> rotate(Vector3.X)));
            p.tools.add(new Hud.Item(Theme.I_ROT_L, "Rotate Y", () -> rotate(Vector3.Y)));
            p.tools.add(new Hud.Item(Theme.I_ROT_R, "Rotate Z", () -> rotate(Vector3.Z)));
            p.tools.add(new Hud.Item(Theme.I_FLIP, "Face me", this::flipFacing));
            p.tools.add(new Hud.Item(Theme.I_MIRROR, "Mirror", this::mirror).checked(() -> mirrored));
            p.tools.add(new Hud.Item(Theme.I_PERSON, "Front", () -> orbit.view(0f, 0f)));
            p.tools.add(new Hud.Item(Theme.I_SIDE, "Side", () -> orbit.view(90f, 0f)));
            p.tools.add(new Hud.Item(Theme.I_FIT, "Frame", this::frameModel));
            p.tools.add(new Hud.Item(Theme.I_RESET, "Reset", this::resetOrientation));
            p.tools.add(new Hud.Item(Theme.I_OPEN, "Open", this::openModel));
            p.primary = new Hud.Item(Theme.I_NEXT, "Next: markers", this::enterMarkers);
        }
        hud.show(0, p);
        updateInfo();
    }

    private void showMarkers() {
        Hud.Panel p = new Hud.Panel();
        p.title = "Place the markers";
        p.maxHeight = 0.44f;
        p.hint = selected.label + ": " + selected.hint + ". Drag the dots onto the model; the right side mirrors automatically.";
        p.chipsTitle = "Markers";
        p.chipCols = 3;
        for (final Markers.Kind k : Markers.Kind.values()) {
            p.chips.add(new Hud.Item(Theme.I_PERSON, k.label, () -> {
                selected = k;
                showMarkers();
            }).dot(k.center ? theme.amber : theme.cyan).checked(() -> selected == k));
        }
        p.toolsTitle = "Tools";
        p.tools.add(new Hud.Item(Theme.I_ERROR, "Guide", guide::show));
        p.tools.add(new Hud.Item(Theme.I_MAGIC, "Guess", () -> {
            pipeline.guessMarkers();
            hud.toast("Markers guessed from the shape. Check each one.", false);
        }));
        p.tools.add(new Hud.Item(Theme.I_PALM, "Fingers", () -> {
            fingersEnabled = !fingersEnabled;
            hud.toast(fingersEnabled ? "Fingers will be detected and rigged (Mixamo finger bones)."
                : "Fingers off: each hand is one bone.", false);
            showMarkers();
        }).checked(() -> fingersEnabled));
        p.tools.add(new Hud.Item(Theme.I_FIT, "Fit view", ortho::reset));
        p.tools.add(new Hud.Item(Theme.I_ROT, "Rotate X", () -> turnInMarkers(Vector3.X)));
        p.tools.add(new Hud.Item(Theme.I_ROT_L, "Rotate Y", () -> turnInMarkers(Vector3.Y)));
        p.tools.add(new Hud.Item(Theme.I_ROT_R, "Rotate Z", () -> turnInMarkers(Vector3.Z)));
        p.tools.add(new Hud.Item(Theme.I_FLIP, "Face me", () -> turnInMarkers(null)));
        p.tools.add(new Hud.Item(Theme.I_MIRROR, "Mirror", () -> {
            mirror();
            restartMarkers();
        }).checked(() -> mirrored));
        p.secondary = new Hud.Item(Theme.I_BACK, "Back", this::backToOrient);
        p.primary = new Hud.Item(Theme.I_SPARK, "Rig it", this::startRig);
        hud.show(1, p);
    }

    private void showTest() {
        Hud.Panel p = new Hud.Panel();
        p.title = "Test & export";
        p.maxHeight = 0.5f;
        p.hint = weightBone == -2 ? rigSummary : weightBone == -1
            ? "Weights view: each colour is one bone; blends show where bones share the skin."
            : "Weights of " + pipeline.skeleton.joints.get(weightBone).name + ": red 100%, blue 0%.";
        p.chipsTitle = "Poses";
        p.chips.add(new Hud.Item(Theme.I_WALK, "Walk", () -> {
            walking = true;
            currentPoseHolder.pose = null;
            showTest();
        }).checked(() -> walking));
        for (final Poses.Pose ps : poses) {
            char ic = ps.name.startsWith("Arms") ? Theme.I_TPOSE : ps.name.startsWith("Squat") || ps.name.startsWith("Kick")
                ? Theme.I_RUN : ps.name.startsWith("Rest") ? Theme.I_PERSON : Theme.I_GYM;
            p.chips.add(new Hud.Item(ic, ps.name, () -> {
                walking = false;
                currentPoseHolder.pose = ps;
                applyPose(ps);
                showTest();
            }).checked(() -> !walking && currentPoseHolder.pose == ps));
        }
        p.toolsTitle = "View";
        p.tools.add(new Hud.Item(Theme.I_BONES, "Bones", () -> {
            showBones = !showBones;
            showTest();
        }).checked(() -> showBones));
        p.tools.add(new Hud.Item(Theme.I_PALETTE, "Weights", () -> setWeightView(weightBone == -2 ? -1 : -2))
            .checked(() -> weightBone != -2));
        p.tools.add(new Hud.Item(Theme.I_PREV, "Prev bone", () -> setWeightView(prevDeform(weightBone))));
        p.tools.add(new Hud.Item(Theme.I_NEXT_SMALL, "Next bone", () -> setWeightView(nextDeform(weightBone))));
        p.secondary = new Hud.Item(Theme.I_BACK, "Markers", this::backToMarkers);
        p.primary = new Hud.Item(Theme.I_SAVE, "Export GLB", this::exportGlb);
        hud.show(3, p);
    }

    // ------------------------------------------------------------------ steps

    private void enterMarkers() {
        if (modelBytes == null) {
            hud.toast("Open a GLB model first.", true);
            return;
        }
        try {
            if (pipeline == null || !java.util.Arrays.equals(pipeline.orient.val, scene.modelInstance.transform.val)) {
                pipeline = new RigPipeline(modelBytes, scene.modelInstance.transform);
                pipeline.guessMarkers();
            }
        } catch (RuntimeException e) {
            Gdx.app.error("MobiRig", "analyse failed", e);
            hud.toast("This model can't be rigged: " + e.getMessage(), true);
            return;
        }
        step = Step.MARKERS;
        selected = Markers.Kind.CHIN;
        framedForView = false;
        sceneManager.setCamera(ortho.camera);
        showMarkers();
        setInput();
    }

    /** Turns the model while placing markers; the markers are guessed again for the new orientation. */
    private void turnInMarkers(Vector3 axis) {
        if (axis == null) flipFacing();
        else rotate(axis);
        restartMarkers();
    }

    private void restartMarkers() {
        try {
            pipeline = new RigPipeline(modelBytes, scene.modelInstance.transform);
            pipeline.guessMarkers();
        } catch (RuntimeException e) {
            hud.toast("This model can't be rigged: " + e.getMessage(), true);
            return;
        }
        framedForView = false;
        showMarkers();
        hud.toast("Model turned. Markers were guessed again: check them.", false);
    }

    private void frameOrtho() {
        ModelInstance mi = scene.modelInstance;
        mi.calculateBoundingBox(box);
        box.mul(mi.transform);
        ortho.frame(box, (int) view.width, (int) view.height);
    }

    private void backToOrient() {
        sceneManager.setCamera(camera);
        showOrient();
        setInput();
    }

    private void backToMarkers() {
        loadScene(modelName, modelBytes, false);
        scene.modelInstance.transform.set(pipeline.orient);
        weightBone = -2;
        enterMarkers();
    }

    private void startRig() {
        if (pipeline == null) return;
        step = Step.RIGGING;
        setInput();
        final boolean android = Gdx.app.getType() == Application.ApplicationType.Android;
        final int res = android ? 96 : 128;
        pipeline.fingers = fingersEnabled;
        progressText = "Starting";
        progressF = 0;
        hud.progress("Rigging your character", "Starting...", 0);
        new Thread(() -> {
            try {
                pipeline.rig(res, (st, f) -> {
                    progressText = st;
                    progressF = f;
                });
                Gdx.app.postRunnable(this::rigDone);
            } catch (final Throwable e) {
                Gdx.app.error("MobiRig", "rig failed", e);
                Gdx.app.postRunnable(() -> {
                    progressText = null;
                    hud.hideProgress();
                    step = Step.MARKERS;
                    setInput();
                    hud.toast("Rigging failed: " + e.getMessage(), true);
                });
            }
        }, "rigger").start();
    }

    private void rigDone() {
        progressText = null;
        hud.hideProgress();
        loadScene(modelName, pipeline.output, true);
        Skeleton sk = pipeline.skeleton;
        weightBone = -2;
        bindJointNodes();
        poses = Poses.all(sk);
        sceneManager.setCamera(camera);
        frameModel();
        orbit.view(25f, 10f);
        currentPoseHolder.pose = null;
        walking = false;
        float secs = (pipeline.fitMs + pipeline.bindMs + pipeline.exportMs + pipeline.qualityMs) / 1000f;
        float worst = 0;
        String worstName = "-";
        for (com.star4droid.mobirig.rig.Quality.PoseScore s : pipeline.quality.poses) {
            if (s.badEdgePct + s.collapsedTriPct > worst) {
                worst = s.badEdgePct + s.collapsedTriPct;
                worstName = s.pose;
            }
        }
        rigSummary = String.format(java.util.Locale.US, "Rigged in %.1f s with %d bones. Weight leaks %.2f%%, unweighted %d. Hardest pose: %s.",
            secs, sk.joints.size, pipeline.quality.bleedPct, pipeline.quality.unweightedVertices, worstName);
        for (String n : pipeline.notes) rigSummary += " " + n;
        step = Step.TEST;
        showTest();
        setInput();
        updateInfo();
        if (devView != null && !devView.isEmpty()) {
            String[] v = devView.split(",");
            float hgt = box.getHeight();
            orbit.yaw = Float.parseFloat(v[0]);
            orbit.pitch = Float.parseFloat(v[1]);
            orbit.distance = hgt * Float.parseFloat(v[2]);
            orbit.target.set(v.length > 4 ? hgt * Float.parseFloat(v[4]) : 0, hgt * Float.parseFloat(v[3]), v.length > 5 ? hgt * Float.parseFloat(v[5]) : 0);
            orbit.update();
            showBones = false;
        }
        if (devPose != null && devPose.startsWith("Weights")) {
            String[] parts = devPose.split(":");
            String[] sub = parts.length > 1 ? parts[1].split("@") : new String[0];
            if (sub.length > 1) for (Poses.Pose p : poses) if (p.name.equals(sub[1])) currentPoseHolder.pose = p;
            setWeightView(sub.length > 0 && !sub[0].isEmpty() ? sk.index(sub[0]) : -1);
            return;
        }
        if (devPose != null) {
            if (devPose.equals("Walk")) walking = true;
            else for (Poses.Pose p : poses) if (p.name.equals(devPose)) {
                currentPoseHolder.pose = p;
                applyPose(p);
            }
            showTest();
        }
    }

    private int nextDeform(int j) {
        Skeleton sk = pipeline.skeleton;
        for (int k = 1; k <= sk.joints.size; k++) {
            int c = ((j < 0 ? -1 : j) + k) % sk.joints.size;
            if (sk.joints.get(c).deform) return c;
        }
        return -1;
    }

    private int prevDeform(int j) {
        Skeleton sk = pipeline.skeleton;
        int n = sk.joints.size;
        for (int k = 1; k <= n; k++) {
            int c = (((j < 0 ? 0 : j) - k) % n + n) % n;
            if (sk.joints.get(c).deform) return c;
        }
        return -1;
    }

    /** Shows the model coloured by weights (one bone as a heat map, or all bones), keeping pose and camera. */
    private void setWeightView(int bone) {
        weightBone = bone;
        byte[] bytes = bone == -2 ? pipeline.output : new GlbExporter().export(
            pipeline.source, pipeline.mesh, pipeline.skeleton, pipeline.joints, pipeline.weights,
            GlbExporter.weightColors(pipeline.mesh, pipeline.skeleton, pipeline.joints, pipeline.weights, bone));
        Vector3 tgt = new Vector3(orbit.target);
        float yaw = orbit.yaw, pitch = orbit.pitch, dist = orbit.distance;
        loadScene(modelName, bytes, true);
        orbit.target.set(tgt);
        orbit.yaw = yaw;
        orbit.pitch = pitch;
        orbit.distance = dist;
        orbit.update();
        bindJointNodes();
        if (currentPoseHolder.pose != null) applyPose(currentPoseHolder.pose);
        showTest();
    }

    private void bindJointNodes() {
        Skeleton sk = pipeline.skeleton;
        jointNodes = new Node[sk.joints.size];
        for (int j = 0; j < sk.joints.size; j++) {
            jointNodes[j] = scene.modelInstance.getNode("mixamorig:" + sk.joints.get(j).name, true);
        }
    }

    private void applyPose(Poses.Pose p) {
        Quaternion[] rot = Poses.localRotations(pipeline.skeleton, p);
        for (int j = 0; j < jointNodes.length; j++) if (jointNodes[j] != null) jointNodes[j].rotation.set(rot[j]);
        scene.modelInstance.calculateTransforms();
    }

    private void exportGlb() {
        String base = modelName.toLowerCase().endsWith(".glb") ? modelName.substring(0, modelName.length() - 4) : modelName;
        platform.saveFile(base + "_rigged.glb", pipeline.output, new Platform.SaveCallback() {
            @Override
            public void onSaved(String where) {
                hud.toast("Saved " + where + " (" + pipeline.output.length / 1024 + " KB, Mixamo skeleton).", false);
            }

            @Override
            public void onError(String message) {
                hud.toast(message, !message.equals("Not saved"));
            }
        });
    }

    private void devAutoRig() {
        // the viewport must exist before markers are framed
        view.set(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        enterMarkers();
        if (!devMarkers.equals("guess")) {
            JsonValue mj = new JsonReader().parse(Gdx.files.absolute(devMarkers).readString("UTF-8"));
            for (Markers.Kind k : Markers.Kind.values()) {
                float[] v = mj.get(k.name().toLowerCase()).asFloatArray();
                pipeline.markers.get(k).set(v[0], v[1], v[2]);
            }
            pipeline.markers.constrainAll(pipeline.mesh);
        }
        if (devPose != null && devPose.equals("Markers")) return;
        if (devPose != null && devPose.equals("Guide")) {
            Gdx.app.postRunnable(guide::show);
            return;
        }
        if (devPose != null && devPose.startsWith("Lens:")) {
            dragging = Markers.Kind.valueOf(devPose.substring(5));
            selected = dragging;
            showMarkers();
            hud.setSheetHidden(true);
            return;
        }
        startRig();
    }

    // ------------------------------------------------------------------ model

    private void openModel() {
        platform.pickModel(new Platform.FileCallback() {
            @Override
            public void onPicked(String name, byte[] data) {
                loadGlb(name, data);
            }

            @Override
            public void onError(String message) {
                hud.toast(message, !message.equals("No file chosen"));
            }
        });
    }

    private void loadGlb(String name, byte[] data) {
        if (step != Step.ORIENT) {
            sceneManager.setCamera(camera);
            step = Step.ORIENT;
        }
        if (loadScene(name, data, false)) {
            modelBytes = data;
            pipeline = null;
            showOrient();
            setInput();
            hud.toast("Loaded " + name + " (" + (data.length / 1024) + " KB).", false);
        }
    }

    /** An existing rig with more bones than the shader supports is hidden in the preview (it is replaced anyway). */
    private static byte[] displayBytes(byte[] data) {
        try {
            com.star4droid.mobirig.rig.GlbFile g = com.star4droid.mobirig.rig.GlbFile.parse(data);
            JsonValue skins = g.json.get("skins");
            boolean tooMany = false;
            if (skins != null) for (JsonValue sk = skins.child; sk != null; sk = sk.next) {
                if (sk.get("joints") != null && sk.get("joints").size > MAX_BONES) tooMany = true;
            }
            if (!tooMany) return data;
            for (JsonValue n = g.json.get("nodes").child; n != null; n = n.next) n.remove("skin");
            for (JsonValue m = g.json.get("meshes").child; m != null; m = m.next) {
                for (JsonValue pr = m.get("primitives").child; pr != null; pr = pr.next) {
                    JsonValue a = pr.get("attributes");
                    for (String k : new String[]{"JOINTS_0", "WEIGHTS_0", "JOINTS_1", "WEIGHTS_1"}) a.remove(k);
                }
            }
            g.json.remove("skins");
            g.json.remove("animations");
            return g.write();
        } catch (RuntimeException e) {
            return data;
        }
    }

    private boolean loadScene(String name, byte[] data, boolean rigged) {
        try {
            FileHandle f = Gdx.files.local(rigged ? "import/rigged.glb" : "import/current.glb");
            f.writeBytes(rigged ? data : displayBytes(data), false);
            SceneAsset loaded = new GLBLoader().load(f);
            SceneAsset old = asset;
            asset = loaded;
            setScene(new Scene(loaded.scene), name, !rigged);
            if (old != null) old.dispose();
            return true;
        } catch (Throwable t) {
            Gdx.app.error("MobiRig", "GLB load failed", t);
            hud.toast("Could not open " + name + ": " + t.getMessage(), true);
            return false;
        }
    }

    private void setScene(Scene s, String name, boolean floor) {
        if (scene != null) sceneManager.removeScene(scene);
        scene = s;
        modelName = name;
        mirrored = false;
        sceneManager.addScene(scene);
        if (floor) dropToFloor();
        frameModel();
        updateInfo();
    }

    private void rotate(Vector3 axis) {
        scene.modelInstance.transform.mulLeft(tmpM.setToRotation(axis, 90f));
        dropToFloor();
        frameModel();
    }

    private void flipFacing() {
        scene.modelInstance.transform.mulLeft(tmpM.setToRotation(Vector3.Y, 180f));
        dropToFloor();
    }

    private void mirror() {
        scene.modelInstance.transform.mulLeft(tmpM.setToScaling(-1f, 1f, 1f));
        mirrored = !mirrored;
        for (Material m : scene.modelInstance.materials) {
            if (mirrored) m.set(IntAttribute.createCullFace(GL20.GL_NONE));
            else m.remove(IntAttribute.CullFace);
        }
        dropToFloor();
        if (step == Step.ORIENT) showOrient();
    }

    private void resetOrientation() {
        scene.modelInstance.transform.idt();
        if (mirrored) mirror();
        dropToFloor();
        frameModel();
    }

    private void dropToFloor() {
        ModelInstance mi = scene.modelInstance;
        mi.calculateBoundingBox(box);
        box.mul(mi.transform);
        if (!box.isValid()) return;
        box.getCenter(tmpV);
        mi.transform.mulLeft(tmpM.setToTranslation(-tmpV.x, -box.min.y, -tmpV.z));
        updateInfo();
    }

    private void frameModel() {
        ModelInstance mi = scene.modelInstance;
        mi.calculateBoundingBox(box);
        box.mul(mi.transform);
        if (box.isValid()) orbit.frame(box);
    }

    private void updateInfo() {
        if (hud == null || scene == null) return;
        int verts = 0;
        for (Mesh m : scene.modelInstance.model.meshes) verts += m.getNumVertices();
        int bones = countBones(scene.modelInstance.nodes, 0);
        ModelInstance mi = scene.modelInstance;
        mi.calculateBoundingBox(box);
        box.mul(mi.transform);
        hud.setInfo(String.format(java.util.Locale.US, "%s  •  %,d verts  •  %s  •  %.2f m", modelName, verts,
            bones > 0 ? bones + " bones" : "no rig", box.getHeight()));
    }

    private int countBones(Iterable<Node> nodes, int max) {
        for (Node n : nodes) {
            for (NodePart p : n.parts) {
                if (p.invBoneBindTransforms != null) max = Math.max(max, p.invBoneBindTransforms.size);
            }
            max = countBones(n.getChildren(), max);
        }
        return max;
    }

    // ------------------------------------------------------------------ loop

    private void applyInsets() {
        hud.setInsets(Gdx.graphics.getSafeInsetTop() / density, Gdx.graphics.getSafeInsetBottom() / density,
            Gdx.graphics.getSafeInsetLeft() / density, Gdx.graphics.getSafeInsetRight() / density);
    }

    @Override
    public void resize(int width, int height) {
        stage.getViewport().update(width, height, true);
        applyInsets();
        hud.rebuild();
        if (guide.isShown()) guide.show();
        framedForView = false;
    }

    @Override
    public void render() {
        float dt = Math.min(Gdx.graphics.getDeltaTime(), 0.1f);
        if (step == Step.TEST && walking && jointNodes != null) {
            walkTime += dt;
            applyPose(Poses.walk(pipeline.skeleton, walkTime));
        }
        if (step == Step.RIGGING && progressText != null) {
            hud.progress("Rigging your character", progressText + "  " + Math.round(progressF * 100) + "%", progressF);
        }
        stage.act(dt);

        hud.viewRect(view);
        ScreenUtils.clear(theme.bg.r, theme.bg.g, theme.bg.b, 1f, true);
        HdpiUtils.glViewport((int) view.x, (int) view.y, (int) view.width, (int) view.height);
        Camera cam;
        if (step == Step.MARKERS) {
            if (!framedForView) {
                frameOrtho();
                framedForView = true;
            } else ortho.resize((int) view.width, (int) view.height);
            cam = ortho.camera;
        } else {
            camera.viewportWidth = view.width;
            camera.viewportHeight = view.height;
            camera.update();
            cam = camera;
        }
        drawGrid(cam);
        sceneManager.update(dt);
        sceneManager.render();
        if (step == Step.TEST && showBones && jointNodes != null && weightBone == -2) drawBones();
        HdpiUtils.glViewport(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        if (step == Step.MARKERS && pipeline != null) drawMarkers();
        stage.draw();
        if (step == Step.MARKERS && dragging != null) drawLens();
    }

    private void drawGrid(Camera cam) {
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(cam.combined);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        float st = 0.25f;
        int n = 12;
        for (int i = -n; i <= n; i++) {
            float c = i * st;
            float a = i == 0 ? 0.5f : 0.22f;
            shapes.setColor(0.45f, 0.48f, 0.6f, a);
            shapes.line(c, 0f, -n * st, c, 0f, n * st);
            shapes.line(-n * st, 0f, c, n * st, 0f, c);
        }
        shapes.end();
    }

    private void drawMarkers() {
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        tmpM.setToOrtho2D(0, 0, w, h);
        shapes.setProjectionMatrix(tmpM);
        float r = 9f * density;
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(1, 1, 1, 0.3f);
        line2d(Markers.Kind.CHIN, false, Markers.Kind.NECK, false);
        line2d(Markers.Kind.NECK, false, Markers.Kind.PELVIS, false);
        line2d(Markers.Kind.NECK, false, Markers.Kind.SHOULDER, false);
        line2d(Markers.Kind.NECK, false, Markers.Kind.SHOULDER, true);
        line2d(Markers.Kind.SHOULDER, false, Markers.Kind.WRIST, false);
        line2d(Markers.Kind.SHOULDER, true, Markers.Kind.WRIST, true);
        line2d(Markers.Kind.PELVIS, false, Markers.Kind.ANKLE, false);
        line2d(Markers.Kind.PELVIS, false, Markers.Kind.ANKLE, true);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (Markers.Kind k : Markers.Kind.values()) {
            for (int m = 0; m < (k.center ? 1 : 2); m++) {
                project(k, m == 1, tmpV);
                Color c = k.center ? theme.amber : theme.cyan;
                boolean sel = k == selected;
                float rr = sel ? r * 1.3f : r;
                if (sel && m == 0) {
                    shapes.setColor(c.r, c.g, c.b, 0.25f);
                    shapes.circle(tmpV.x, tmpV.y, rr * 2.1f, 32);
                }
                shapes.setColor(0.05f, 0.06f, 0.08f, 0.85f);
                shapes.circle(tmpV.x, tmpV.y, rr + 2.5f * density, 24);
                shapes.setColor(c.r, c.g, c.b, m == 1 ? 0.5f : 1f);
                shapes.circle(tmpV.x, tmpV.y, rr, 24);
            }
        }
        shapes.end();
        // labels in dp space (crisp font)
        batch.getProjectionMatrix().setToOrtho2D(0, 0, w / density, h / density);
        batch.begin();
        for (Markers.Kind k : Markers.Kind.values()) {
            project(k, false, tmpV);
            boolean sel = k == selected;
            com.badlogic.gdx.graphics.g2d.BitmapFont f = sel ? theme.smallBold : theme.small;
            f.setColor(sel ? theme.text : theme.textDim);
            // centre markers alternate sides so close markers (chin/neck) never overlap
            boolean leftSide = k == Markers.Kind.CHIN || k == Markers.Kind.PELVIS;
            float x = tmpV.x / density, y = tmpV.y / density + 5;
            if (leftSide) f.draw(batch, k.label, x - 16, y, 0, com.badlogic.gdx.utils.Align.right, false);
            else f.draw(batch, k.label, x + 16, y);
        }
        batch.end();
    }

    /** Magnifier in the (hidden) sheet area: the exact spot under the dragged marker, 3x. */
    private void drawLens() {
        hud.sheetRect(lensRect);
        if (lensRect.width < 10 || lensRect.height < 10) return;
        float size = Math.min(Math.min(190f * density, lensRect.height - 24f * density), lensRect.width - 24f * density);
        if (size < 60f * density) return;
        int lx = (int) (lensRect.x + (lensRect.width - size) / 2), ly = (int) (lensRect.y + (lensRect.height - size) / 2);
        int ls = (int) size;
        Vector3 m = pipeline.markers.get(dragging);
        float mx = dragMirror ? -m.x : m.x, my = m.y;
        float worldPerPx = ortho.camera.viewportHeight * ortho.camera.zoom / Math.max(1f, view.height);
        float worldSize = ls * worldPerPx / 3f;
        lensCam.viewportWidth = worldSize;
        lensCam.viewportHeight = worldSize;
        lensCam.zoom = 1f;
        lensCam.position.set(mx, my, ortho.camera.position.z);
        lensCam.direction.set(0, 0, -1);
        lensCam.up.set(0, 1, 0);
        lensCam.near = ortho.camera.near;
        lensCam.far = ortho.camera.far;
        lensCam.update();

        // frame + background
        Gdx.gl.glEnable(GL20.GL_BLEND);
        tmpM.setToOrtho2D(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        shapes.setProjectionMatrix(tmpM);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        float b = 3f * density;
        shapes.setColor(theme.primary);
        shapes.rect(lx - b, ly - b, ls + 2 * b, ls + 2 * b);
        shapes.end();
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
        HdpiUtils.glScissor(lx, ly, ls, ls);
        Gdx.gl.glClearColor(theme.bg.r, theme.bg.g, theme.bg.b, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        HdpiUtils.glViewport(lx, ly, ls, ls);
        sceneManager.setCamera(lensCam);
        sceneManager.render();
        sceneManager.setCamera(ortho.camera);
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        HdpiUtils.glViewport(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());

        // crosshair with a gap + marker ring at the exact point
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        float cx = lx + ls / 2f, cy = ly + ls / 2f, gap = 9f * density, arm = ls / 2f - 6f * density;
        Color c = dragging.center ? theme.amber : theme.cyan;
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        float th = 1.5f * density;
        shapes.setColor(0, 0, 0, 0.6f);
        shapes.rect(cx - arm, cy - th, arm - gap, th * 2);
        shapes.rect(cx + gap, cy - th, arm - gap, th * 2);
        shapes.rect(cx - th, cy - arm, th * 2, arm - gap);
        shapes.rect(cx - th, cy + gap, th * 2, arm - gap);
        shapes.setColor(c);
        th = 0.75f * density;
        shapes.rect(cx - arm, cy - th, arm - gap, th * 2);
        shapes.rect(cx + gap, cy - th, arm - gap, th * 2);
        shapes.rect(cx - th, cy - arm, th * 2, arm - gap);
        shapes.rect(cx - th, cy + gap, th * 2, arm - gap);
        shapes.circle(cx, cy, 2.5f * density, 16);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(c);
        shapes.circle(cx, cy, gap - 2f * density, 32);
        shapes.end();
        // caption above the lens
        batch.getProjectionMatrix().setToOrtho2D(0, 0, Gdx.graphics.getWidth() / density, Gdx.graphics.getHeight() / density);
        batch.begin();
        theme.smallBold.setColor(theme.text);
        theme.smallBold.draw(batch, dragging.label + "  ×3", lx / density, (ly + ls) / density + 18, ls / density, com.badlogic.gdx.utils.Align.center, false);
        batch.end();
    }

    private void project(Markers.Kind k, boolean mirror, Vector3 out) {
        Vector3 p = pipeline.markers.get(k);
        out.set(mirror ? -p.x : p.x, p.y, p.z);
        ortho.camera.project(out, view.x, view.y, view.width, view.height);
    }

    private void line2d(Markers.Kind a, boolean ma, Markers.Kind b, boolean mb) {
        project(a, ma, tmpV);
        project(b, mb, tmpV2);
        shapes.line(tmpV.x, tmpV.y, tmpV2.x, tmpV2.y);
    }

    private void drawBones() {
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        shapes.setProjectionMatrix(camera.combined);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Skeleton sk = pipeline.skeleton;
        for (int j = 0; j < jointNodes.length; j++) {
            int p = sk.joints.get(j).parent;
            if (p < 0 || jointNodes[j] == null || jointNodes[p] == null) continue;
            jointNodes[j].globalTransform.getTranslation(tmpV).mul(scene.modelInstance.transform);
            jointNodes[p].globalTransform.getTranslation(tmpV2).mul(scene.modelInstance.transform);
            int side = sk.joints.get(j).side;
            shapes.setColor(side > 0 ? theme.cyan : side < 0 ? theme.danger : theme.amber);
            shapes.line(tmpV2, tmpV);
        }
        shapes.end();
    }

    @Override
    public void dispose() {
        sceneManager.dispose();
        if (asset != null) asset.dispose();
        mannequinModel.dispose();
        diffuseCubemap.dispose();
        specularCubemap.dispose();
        brdfLUT.dispose();
        shapes.dispose();
        stage.dispose();
        batch.dispose();
        theme.dispose();
        guide.dispose();
    }
}
