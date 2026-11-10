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
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.input.GestureDetector;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.kotcrab.vis.ui.VisUI;
import com.kotcrab.vis.ui.widget.VisLabel;
import com.kotcrab.vis.ui.widget.VisScrollPane;
import com.kotcrab.vis.ui.widget.VisTable;
import com.kotcrab.vis.ui.widget.VisTextButton;
import com.star4droid.mobirig.rig.Markers;
import com.star4droid.mobirig.rig.Poses;
import com.star4droid.mobirig.rig.RigException;
import com.star4droid.mobirig.rig.RigPipeline;
import com.star4droid.mobirig.rig.Skeleton;
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
    /** Desktop testing: pose to show after rigging. */
    public String devPose;
    /** Desktop testing: "yaw,pitch,distanceFactor,targetHeightFactor" camera after rigging. */
    public String devView;

    private SceneManager sceneManager;
    private PerspectiveCamera camera;
    private OrbitController orbit;
    private final OrthoController ortho = new OrthoController();
    private ShapeRenderer shapes;
    private SpriteBatch batch;
    private BitmapFont font;
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

    // test step
    private Node[] jointNodes;
    private Quaternion[] restRot;
    private Poses.Pose pose;
    private boolean walking, showBones = true;
    private int weightBone = -2; // -2 off, -1 all bones, >=0 one bone
    private float walkTime;
    private com.badlogic.gdx.utils.Array<Poses.Pose> poses;

    private Stage stage;
    private VisLabel infoLabel, statusLabel, stepsLabel;
    private VisTable root, bar;
    private float density = 1f;

    private final BoundingBox box = new BoundingBox();
    private final Matrix4 tmpM = new Matrix4();
    private final Vector3 tmpV = new Vector3(), tmpV2 = new Vector3();

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
        density = android ? Gdx.graphics.getDensity() : 1f;
        boolean big = density >= 1.5f;
        VisUI.load(big ? VisUI.SkinScale.X2 : VisUI.SkinScale.X1);
        font = VisUI.getSkin().getFont("default-font");
        float uiScale = Math.max(0.75f, density / (big ? 2f : 1f));
        ScreenViewport vp = new ScreenViewport();
        vp.setUnitsPerPixel(1f / uiScale);
        stage = new Stage(vp);
        buildUi();
        updateInfo();
        setInput();

        platform.initialModel(new Platform.FileCallback() {
            @Override
            public void onPicked(String name, byte[] data) {
                loadGlb(name, data);
                if (devMarkers != null) devAutoRig();
            }

            @Override
            public void onError(String message) {
                status(message);
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
        } else {
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
            float best = 40f * Math.max(1f, density);
            Markers.Kind found = null;
            boolean mir = false;
            for (Markers.Kind k : Markers.Kind.values()) {
                for (int m = 0; m < (k.center ? 1 : 2); m++) {
                    Vector3 p = pipeline.markers.get(k);
                    tmpV.set(m == 0 ? p.x : -p.x, p.y, p.z);
                    ortho.camera.project(tmpV);
                    float d = Vector3.dst(tmpV.x, Gdx.graphics.getHeight() - tmpV.y, 0, x, y, 0);
                    if (d < best) {
                        best = d;
                        found = k;
                        mir = m == 1;
                    }
                }
            }
            if (found == null) return false;
            dragging = found;
            selected = found;
            dragMirror = mir;
            boolean touch = Gdx.app.getType() == Application.ApplicationType.Android;
            dragOffset = touch ? 64f * density : 0f;
            describeMarker();
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
            return true;
        }
    }

    private void moveMarker(int sx, int sy) {
        tmpV.set(sx, sy - dragOffset, 0);
        ortho.camera.unproject(tmpV);
        Vector3 m = pipeline.markers.get(dragging);
        m.set(dragMirror ? -tmpV.x : tmpV.x, tmpV.y, 0);
        pipeline.markers.constrain(pipeline.mesh, dragging);
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        root = new VisTable();
        root.setFillParent(true);
        stage.addActor(root);

        VisTable top = new VisTable();
        top.setBackground(VisUI.getSkin().getDrawable("window-bg"));
        VisLabel title = new VisLabel("MobiRig");
        title.setColor(new Color(0.55f, 0.75f, 1f, 1f));
        top.add(title).padRight(12);
        stepsLabel = new VisLabel("");
        top.add(stepsLabel).expandX().left();
        root.add(top).growX().top().row();

        infoLabel = new VisLabel("");
        infoLabel.setWrap(true);
        root.add(infoLabel).growX().left().pad(6).row();
        statusLabel = new VisLabel("");
        statusLabel.setWrap(true);
        statusLabel.setColor(Color.LIGHT_GRAY);
        root.add(statusLabel).growX().left().pad(0, 6, 0, 6).row();
        root.add().expand().row();

        bar = new VisTable(true);
        bar.pad(6);
        VisScrollPane scroll = new VisScrollPane(bar);
        scroll.setScrollingDisabled(false, true);
        scroll.setFadeScrollBars(true);
        scroll.setOverscroll(true, false);
        VisTable bottom = new VisTable();
        bottom.setBackground(VisUI.getSkin().getDrawable("window-bg"));
        bottom.add(scroll).growX();
        root.add(bottom).growX().bottom();
        applySafeInsets();
        showStep(Step.ORIENT);
    }

    private void showStep(Step s) {
        step = s;
        bar.clearChildren();
        switch (s) {
            case ORIENT:
                bar.add(button("Open GLB", this::openModel, false));
                bar.add(button("Rot X", () -> rotate(Vector3.X), false));
                bar.add(button("Rot Y", () -> rotate(Vector3.Y), false));
                bar.add(button("Rot Z", () -> rotate(Vector3.Z), false));
                bar.add(button("Flip", this::flipFacing, false));
                bar.add(button("Mirror", this::mirror, false));
                bar.add(button("Front", () -> orbit.view(0f, 0f), false));
                bar.add(button("Side", () -> orbit.view(90f, 0f), false));
                bar.add(button("Frame", this::frameModel, false));
                bar.add(button("Reset", this::resetOrientation, false));
                bar.add(button("Next: Markers", this::enterMarkers, true));
                status("Make the character stand upright and face you, then tap Next. Drag: orbit, 2 fingers: pan/zoom, double tap: frame.");
                break;
            case MARKERS:
                bar.add(button("Back", this::backToOrient, false));
                for (final Markers.Kind k : Markers.Kind.values()) {
                    bar.add(button(k.label, () -> {
                        selected = k;
                        describeMarker();
                    }, false));
                }
                bar.add(button("Guess again", () -> {
                    pipeline.guessMarkers();
                    status("Markers guessed again from the shape. Check each one.");
                }, false));
                bar.add(button("Fit view", () -> ortho.reset(), false));
                bar.add(button("Rig it", this::startRig, true));
                describeMarker();
                break;
            case RIGGING:
                bar.add(new VisLabel("Rigging..."));
                break;
            case TEST:
                bar.add(button("Markers", this::backToMarkers, false));
                bar.add(button("Walk", () -> {
                    walking = true;
                    pose = null;
                }, false));
                for (final Poses.Pose p : poses) {
                    bar.add(button(p.name, () -> {
                        walking = false;
                        pose = p;
                        applyPose(p);
                    }, false));
                }
                bar.add(button("Bones", () -> showBones = !showBones, false));
                bar.add(button("Weights", () -> setWeightView(weightBone == -2 ? -1 : -2), false));
                bar.add(button("< Bone", () -> setWeightView(prevDeform(weightBone)), false));
                bar.add(button("Bone >", () -> setWeightView(nextDeform(weightBone)), false));
                bar.add(button("Export GLB", this::exportGlb, true));
                break;
        }
        updateSteps();
        setInput();
    }

    private VisTextButton button(String text, final Runnable action, boolean primary) {
        VisTextButton b = primary ? new VisTextButton(text, "blue") : new VisTextButton(text);
        b.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                try {
                    action.run();
                } catch (RigException e) {
                    status(e.getMessage());
                }
            }
        });
        b.pad(8, 14, 8, 14);
        return b;
    }

    private void status(String s) {
        if (statusLabel != null) statusLabel.setText(s);
    }

    private void updateSteps() {
        if (stepsLabel == null) return;
        int n = step == Step.ORIENT ? 1 : step == Step.MARKERS ? 2 : step == Step.RIGGING ? 3 : 4;
        String[] names = {"Orient", "Markers", "Rig", "Test & Export"};
        boolean compact = stage.getViewport().getWorldWidth() < 620;
        if (compact) stepsLabel.setText("Step " + n + "/4  " + names[n - 1]);
        else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                if (i > 0) sb.append("  >  ");
                sb.append(i + 1 == n ? "[" + (i + 1) + " " + names[i] + "]" : (i + 1) + " " + names[i]);
            }
            stepsLabel.setText(sb);
        }
    }

    private void applySafeInsets() {
        if (root == null) return;
        float s = ((ScreenViewport) stage.getViewport()).getUnitsPerPixel();
        root.pad(Gdx.graphics.getSafeInsetTop() * s, Gdx.graphics.getSafeInsetLeft() * s,
            Gdx.graphics.getSafeInsetBottom() * s, Gdx.graphics.getSafeInsetRight() * s);
    }

    private void describeMarker() {
        int done = selected.ordinal() + 1;
        status("Drag the markers onto the joints (front view). Selected " + done + "/6: " + selected.label
            + " = " + selected.hint + ". Yellow: centre line, blue: left side (mirrored).");
    }

    // ------------------------------------------------------------------ steps

    private void enterMarkers() {
        if (modelBytes == null) {
            status("Open a GLB model first.");
            return;
        }
        if (pipeline == null || !pipeline.orient.equals(scene.modelInstance.transform)) {
            status("Analysing mesh...");
            pipeline = new RigPipeline(modelBytes, scene.modelInstance.transform);
            pipeline.guessMarkers();
        }
        ModelInstance mi = scene.modelInstance;
        mi.calculateBoundingBox(box);
        box.mul(mi.transform);
        ortho.frame(box, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        sceneManager.setCamera(ortho.camera);
        selected = Markers.Kind.CHIN;
        showStep(Step.MARKERS);
    }

    private void backToOrient() {
        sceneManager.setCamera(camera);
        showStep(Step.ORIENT);
    }

    private void backToMarkers() {
        // show the original model again
        loadScene(modelName, modelBytes, false);
        scene.modelInstance.transform.set(pipeline.orient);
        enterMarkers();
    }

    private void startRig() {
        if (pipeline == null) return;
        showStep(Step.RIGGING);
        final boolean android = Gdx.app.getType() == Application.ApplicationType.Android;
        final int res = android ? 96 : 128;
        progressText = "Starting...";
        new Thread(() -> {
            try {
                pipeline.rig(res, (stage1, f) -> progressText = String.format("%s... %d%%", stage1, Math.round(f * 100)));
                Gdx.app.postRunnable(this::rigDone);
            } catch (final Throwable t) {
                Gdx.app.error("MobiRig", "rig failed", t);
                Gdx.app.postRunnable(() -> {
                    progressText = null;
                    showStep(Step.MARKERS);
                    status("Rigging failed: " + t.getMessage());
                });
            }
        }, "rigger").start();
    }

    private void rigDone() {
        progressText = null;
        loadScene(modelName, pipeline.output, true);
        Skeleton sk = pipeline.skeleton;
        weightBone = -2;
        bindJointNodes();
        poses = Poses.all(sk);
        sceneManager.setCamera(camera);
        frameModel();
        orbit.view(25f, 10f);
        pose = null;
        walking = false;
        showStep(Step.TEST);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Rigged in %.1f s. ", (pipeline.fitMs + pipeline.bindMs + pipeline.exportMs + pipeline.qualityMs) / 1000f));
        sb.append(pipeline.quality.summary());
        for (String n : pipeline.notes) sb.append(" ").append(n);
        status(sb.toString());
        if (devView != null && !devView.isEmpty()) {
            String[] v = devView.split(",");
            float hgt = box.getHeight();
            orbit.yaw = Float.parseFloat(v[0]);
            orbit.pitch = Float.parseFloat(v[1]);
            orbit.distance = hgt * Float.parseFloat(v[2]);
            orbit.target.set(0, hgt * Float.parseFloat(v[3]), 0);
            orbit.update();
            showBones = false;
        }
        if (devPose != null && devPose.startsWith("Weights")) {
            String[] parts = devPose.split(":");
            String[] sub = parts.length > 1 ? parts[1].split("@") : new String[0];
            if (sub.length > 1) for (Poses.Pose p : poses) if (p.name.equals(sub[1])) pose = p;
            setWeightView(sub.length > 0 && !sub[0].isEmpty() ? sk.index(sub[0]) : -1);
            return;
        }
        if (devPose != null) {
            if (devPose.equals("Walk")) walking = true;
            else for (Poses.Pose p : poses) if (p.name.equals(devPose)) {
                pose = p;
                applyPose(p);
            }
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

    /** Shows the model coloured by weights (one bone as a heat map, or all bones), keeping the pose. */
    private void setWeightView(int bone) {
        weightBone = bone;
        byte[] bytes = bone == -2 ? pipeline.output : new com.star4droid.mobirig.rig.GlbExporter().export(
            pipeline.source, pipeline.mesh, pipeline.skeleton, pipeline.joints, pipeline.weights,
            com.star4droid.mobirig.rig.GlbExporter.weightColors(pipeline.mesh, pipeline.skeleton, pipeline.joints, pipeline.weights, bone));
        Vector3 tgt = new Vector3(orbit.target);
        float yaw = orbit.yaw, pitch = orbit.pitch, dist = orbit.distance;
        loadScene(modelName, bytes, true);
        orbit.target.set(tgt);
        orbit.yaw = yaw;
        orbit.pitch = pitch;
        orbit.distance = dist;
        orbit.update();
        bindJointNodes();
        if (pose != null) applyPose(pose);
        status(bone == -2 ? "Normal view." : bone == -1 ? "Weights: every colour is one bone; blends show shared areas."
            : "Weights of " + pipeline.skeleton.joints.get(bone).name + ": red = 100%, blue = 0%.");
    }

    private void bindJointNodes() {
        Skeleton sk = pipeline.skeleton;
        jointNodes = new Node[sk.joints.size];
        restRot = new Quaternion[sk.joints.size];
        for (int j = 0; j < sk.joints.size; j++) {
            jointNodes[j] = scene.modelInstance.getNode("mixamorig:" + sk.joints.get(j).name, true);
            restRot[j] = jointNodes[j] == null ? new Quaternion() : new Quaternion(jointNodes[j].rotation);
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
                status("Saved " + where + " (" + pipeline.output.length / 1024 + " KB, Mixamo skeleton).");
            }

            @Override
            public void onError(String message) {
                status(message);
            }
        });
    }

    private void devAutoRig() {
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
        startRig();
    }

    // ------------------------------------------------------------------ model

    private void openModel() {
        status("Opening file picker...");
        platform.pickModel(new Platform.FileCallback() {
            @Override
            public void onPicked(String name, byte[] data) {
                loadGlb(name, data);
            }

            @Override
            public void onError(String message) {
                status(message);
            }
        });
    }

    private void loadGlb(String name, byte[] data) {
        if (loadScene(name, data, false)) {
            modelBytes = data;
            pipeline = null;
            status("Loaded " + name + " (" + (data.length / 1024) + " KB). Make it face you, then tap Next.");
        }
    }

    private boolean loadScene(String name, byte[] data, boolean rigged) {
        try {
            FileHandle f = Gdx.files.local(rigged ? "import/rigged.glb" : "import/current.glb");
            f.writeBytes(data, false);
            SceneAsset loaded = new GLBLoader().load(f);
            SceneAsset old = asset;
            asset = loaded;
            setScene(new Scene(loaded.scene), name, !rigged);
            if (old != null) old.dispose();
            return true;
        } catch (Throwable t) {
            Gdx.app.error("MobiRig", "GLB load failed", t);
            status("Could not load " + name + ": " + t.getMessage());
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
        if (infoLabel == null || scene == null) return;
        int verts = 0, tris = 0;
        for (Mesh m : scene.modelInstance.model.meshes) {
            verts += m.getNumVertices();
            int idx = m.getNumIndices();
            tris += (idx > 0 ? idx : m.getNumVertices()) / 3;
        }
        int bones = countBones(scene.modelInstance.nodes, 0);
        ModelInstance mi = scene.modelInstance;
        mi.calculateBoundingBox(box);
        box.mul(mi.transform);
        infoLabel.setText(String.format("%s   |   %,d verts   %,d tris   |   rig: %s   |   height %.2f%s",
            modelName, verts, tris, bones > 0 ? bones + " bones" : "none",
            box.getHeight(), mirrored ? "   |   mirrored" : ""));
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

    @Override
    public void resize(int width, int height) {
        sceneManager.updateViewport(width, height);
        ortho.resize(width, height);
        stage.getViewport().update(width, height, true);
        applySafeInsets();
        updateSteps();
    }

    @Override
    public void render() {
        float dt = Gdx.graphics.getDeltaTime();
        if (step == Step.TEST && walking && jointNodes != null) {
            walkTime += dt;
            applyPose(Poses.walk(pipeline.skeleton, walkTime));
        }
        if (step == Step.RIGGING && progressText != null) status(progressText);
        ScreenUtils.clear(0.11f, 0.12f, 0.14f, 1f, true);
        Camera cam = step == Step.MARKERS ? ortho.camera : camera;
        drawGrid(cam);
        sceneManager.update(dt);
        sceneManager.render();
        if (step == Step.MARKERS && pipeline != null) drawMarkers();
        if (step == Step.TEST && showBones && jointNodes != null) drawBones();
        stage.act(dt);
        stage.draw();
    }

    private void drawGrid(Camera cam) {
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        shapes.setProjectionMatrix(cam.combined);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        float step2 = 0.25f;
        int n = 12;
        for (int i = -n; i <= n; i++) {
            float c = i * step2;
            shapes.setColor(i == 0 ? 0.35f : 0.22f, i == 0 ? 0.35f : 0.23f, i == 0 ? 0.40f : 0.26f, 1f);
            shapes.line(c, 0f, -n * step2, c, 0f, n * step2);
            shapes.line(-n * step2, 0f, c, n * step2, 0f, c);
        }
        shapes.end();
    }

    private void drawMarkers() {
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        tmpM.setToOrtho2D(0, 0, w, h);
        shapes.setProjectionMatrix(tmpM);
        float r = 9f * Math.max(1f, density);
        Color center = new Color(1f, 0.82f, 0.2f, 1f), left = new Color(0.35f, 0.7f, 1f, 1f);
        // guide lines between markers
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(1, 1, 1, 0.35f);
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
                Color c = k.center ? center : left;
                float rr = k == selected ? r * 1.35f : r;
                shapes.setColor(0, 0, 0, 0.6f);
                shapes.circle(tmpV.x, tmpV.y, rr + 2.5f, 20);
                shapes.setColor(c.r, c.g, c.b, m == 1 ? 0.45f : 1f);
                shapes.circle(tmpV.x, tmpV.y, rr, 20);
            }
        }
        shapes.end();
        batch.getProjectionMatrix().set(tmpM);
        batch.begin();
        font.getData().setScale(Math.max(1f, density * 0.6f));
        for (Markers.Kind k : Markers.Kind.values()) {
            project(k, false, tmpV);
            font.setColor(k == selected ? Color.WHITE : Color.LIGHT_GRAY);
            font.draw(batch, k.label, tmpV.x + r * 1.8f, tmpV.y + r * 0.6f);
        }
        batch.end();
        font.getData().setScale(1f);
        font.setColor(Color.WHITE);
    }

    private void project(Markers.Kind k, boolean mirror, Vector3 out) {
        Vector3 p = pipeline.markers.get(k);
        out.set(mirror ? -p.x : p.x, p.y, p.z);
        ortho.camera.project(out);
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
            shapes.setColor(side > 0 ? 0.35f : side < 0 ? 1f : 1f, side > 0 ? 0.7f : side < 0 ? 0.45f : 0.85f, side > 0 ? 1f : side < 0 ? 0.4f : 0.3f, 1f);
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
        batch.dispose();
        stage.dispose();
        VisUI.dispose();
    }
}
