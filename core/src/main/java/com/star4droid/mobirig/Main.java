package com.star4droid.mobirig;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Cubemap;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.input.GestureDetector;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.kotcrab.vis.ui.VisUI;
import com.kotcrab.vis.ui.widget.VisLabel;
import com.kotcrab.vis.ui.widget.VisScrollPane;
import com.kotcrab.vis.ui.widget.VisTable;
import com.kotcrab.vis.ui.widget.VisTextButton;
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

/** MobiRig starter: GLB viewer with the "Orient" step of the rigging wizard. */
public class Main extends ApplicationAdapter {
    /** Mixamo needs 65 bones; keep a little headroom. Uniform limits differ per GPU (see plan). */
    public static final int MAX_BONES = 70;

    private final Platform platform;

    private SceneManager sceneManager;
    private PerspectiveCamera camera;
    private OrbitController orbit;
    private ShapeRenderer shapes;
    private Cubemap diffuseCubemap, specularCubemap;
    private Texture brdfLUT;

    private Model mannequinModel;
    private SceneAsset asset;
    private Scene scene;
    private String modelName = "Mannequin";
    private boolean mirrored;

    private Stage stage;
    private VisLabel infoLabel, statusLabel, stepsLabel;
    private VisTable root;

    private final BoundingBox box = new BoundingBox();
    private final Matrix4 tmpM = new Matrix4();
    private final Vector3 tmpV = new Vector3();

    public Main() {
        this(Platform.NONE);
    }

    public Main(Platform platform) {
        this.platform = platform;
    }

    @Override
    public void create() {
        // ---- 3D ----
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

        mannequinModel = Mannequin.build();
        setScene(new Scene(new ModelInstance(mannequinModel)), "Mannequin");

        // ---- UI ----
        boolean android = Gdx.app.getType() == Application.ApplicationType.Android;
        float density = android ? Gdx.graphics.getDensity() : 1f;
        boolean big = density >= 1.5f;
        VisUI.load(big ? VisUI.SkinScale.X2 : VisUI.SkinScale.X1);
        float uiScale = Math.max(0.75f, density / (big ? 2f : 1f));
        ScreenViewport vp = new ScreenViewport();
        vp.setUnitsPerPixel(1f / uiScale);
        stage = new Stage(vp);
        buildUi();
        updateInfo();

        Gdx.input.setInputProcessor(new InputMultiplexer(stage, new GestureDetector(orbit), orbit));
        platform.initialModel(new Platform.FileCallback() {
            @Override
            public void onPicked(String name, byte[] data) {
                loadGlb(name, data);
            }

            @Override
            public void onError(String message) {
                statusLabel.setText(message);
            }
        });
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
        infoLabel.setAlignment(Align.left);
        infoLabel.setWrap(true);
        root.add(infoLabel).growX().left().pad(6).row();

        statusLabel = new VisLabel("Drag: orbit  |  2 fingers / right drag: pan  |  pinch / wheel: zoom  |  double tap: frame");
        statusLabel.setWrap(true);
        statusLabel.setColor(Color.LIGHT_GRAY);
        root.add(statusLabel).growX().left().pad(0, 6, 0, 6).row();

        root.add().expand().row();

        VisTable bar = new VisTable(true);
        bar.add(button("Open GLB", this::openModel));
        bar.add(button("Rot X", () -> rotate(Vector3.X)));
        bar.add(button("Rot Y", () -> rotate(Vector3.Y)));
        bar.add(button("Rot Z", () -> rotate(Vector3.Z)));
        bar.add(button("Flip", this::flipFacing));
        bar.add(button("Mirror", this::mirror));
        bar.add(button("Front", () -> orbit.view(0f, 0f)));
        bar.add(button("Side", () -> orbit.view(90f, 0f)));
        bar.add(button("Frame", this::frameModel));
        bar.add(button("Reset", this::resetOrientation));
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
        updateSteps();
    }

    /** Full step list on wide screens, short form on phones in portrait. */
    private void updateSteps() {
        if (stepsLabel == null) return;
        boolean compact = stage.getViewport().getWorldWidth() < 620;
        stepsLabel.setText(compact ? "Step 2/5  Orient"
            : "1 Import  >  [2 Orient]  >  3 Markers  >  4 Rig  >  5 Export");
    }

    private VisTextButton button(String text, final Runnable action) {
        VisTextButton b = new VisTextButton(text);
        b.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                action.run();
            }
        });
        b.pad(8, 14, 8, 14);
        return b;
    }

    private void applySafeInsets() {
        if (root == null) return;
        float s = ((ScreenViewport) stage.getViewport()).getUnitsPerPixel();
        root.pad(Gdx.graphics.getSafeInsetTop() * s, Gdx.graphics.getSafeInsetLeft() * s,
            Gdx.graphics.getSafeInsetBottom() * s, Gdx.graphics.getSafeInsetRight() * s);
    }

    // ------------------------------------------------------------------ model

    private void openModel() {
        statusLabel.setText("Opening file picker...");
        platform.pickModel(new Platform.FileCallback() {
            @Override
            public void onPicked(String name, byte[] data) {
                loadGlb(name, data);
            }

            @Override
            public void onError(String message) {
                statusLabel.setText(message);
            }
        });
    }

    private void loadGlb(String name, byte[] data) {
        try {
            FileHandle f = Gdx.files.local("import/current.glb");
            f.writeBytes(data, false);
            SceneAsset loaded = new GLBLoader().load(f);
            SceneAsset old = asset;
            asset = loaded;
            setScene(new Scene(loaded.scene), name);
            if (old != null) old.dispose();
            statusLabel.setText("Loaded " + name + " (" + (data.length / 1024) + " KB)");
        } catch (Throwable t) {
            Gdx.app.error("MobiRig", "GLB load failed", t);
            statusLabel.setText("Could not load " + name + ": " + t.getMessage());
        }
    }

    private void setScene(Scene s, String name) {
        if (scene != null) sceneManager.removeScene(scene);
        scene = s;
        modelName = name;
        mirrored = false;
        sceneManager.addScene(scene);
        dropToFloor();
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
        // negative scale flips triangle winding: draw both sides while mirrored
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

    /** Puts the feet on y = 0 and centres the model on x/z. */
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
        stage.getViewport().update(width, height, true);
        applySafeInsets();
        updateSteps();
    }

    @Override
    public void render() {
        float dt = Gdx.graphics.getDeltaTime();
        ScreenUtils.clear(0.11f, 0.12f, 0.14f, 1f, true);
        drawGrid();
        sceneManager.update(dt);
        sceneManager.render();
        stage.act(dt);
        stage.draw();
    }

    private void drawGrid() {
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        shapes.setProjectionMatrix(camera.combined);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        float step = 0.25f;
        int n = 12;
        for (int i = -n; i <= n; i++) {
            float c = i * step;
            shapes.setColor(i == 0 ? 0.35f : 0.22f, i == 0 ? 0.35f : 0.23f, i == 0 ? 0.40f : 0.26f, 1f);
            shapes.line(c, 0f, -n * step, c, 0f, n * step);
            shapes.line(-n * step, 0f, c, n * step, 0f, c);
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
        VisUI.dispose();
    }
}
