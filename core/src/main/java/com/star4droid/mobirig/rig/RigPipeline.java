package com.star4droid.mobirig.rig;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Array;

/** Import → markers → fit → weights → export → quality, usable from the UI thread pool or headless. */
public class RigPipeline {
    public final byte[] source;
    public final Matrix4 orient;
    public final GlbFile glb;
    public final RigMesh mesh;
    public Markers markers;
    public Skeleton skeleton;
    public int[] joints;
    public float[] weights;
    public byte[] output;
    public Quality quality;
    public final Array<String> notes = new Array<>();
    public long fitMs, bindMs, exportMs, qualityMs;

    public RigPipeline(byte[] source, Matrix4 orient) {
        this.source = source;
        this.orient = new Matrix4(orient);
        this.glb = GlbFile.parse(source);
        this.mesh = RigMesh.build(glb, this.orient);
        if (mesh.height() <= 0) throw new RigException("The model has no height");
    }

    public Markers guessMarkers() {
        markers = Markers.guess(mesh);
        return markers;
    }

    /** Runs fitting, binding, export and the quality check. resolution: voxels on the longest side. */
    public void rig(int resolution, VoxelBinder.Progress progress) {
        if (markers == null) throw new RigException("Place the markers first");
        notes.clear();
        long t0 = System.currentTimeMillis();
        if (progress != null) progress.update("Fitting skeleton", 0.02f);
        Fitter fitter = new Fitter(mesh);
        skeleton = fitter.fit(markers);
        notes.addAll(fitter.notes);
        long t1 = System.currentTimeMillis();
        VoxelBinder binder = new VoxelBinder();
        binder.resolution = resolution;
        binder.bind(mesh, skeleton, progress == null ? null : (s, f) -> progress.update(s, 0.05f + f * 0.8f));
        joints = binder.joints;
        weights = binder.weights;
        long t2 = System.currentTimeMillis();
        if (progress != null) progress.update("Writing GLB", 0.88f);
        output = new GlbExporter().export(source, mesh, skeleton, joints, weights);
        long t3 = System.currentTimeMillis();
        if (progress != null) progress.update("Checking deformation", 0.93f);
        quality = Quality.measure(mesh, skeleton, joints, weights);
        long t4 = System.currentTimeMillis();
        fitMs = t1 - t0;
        bindMs = t2 - t1;
        exportMs = t3 - t2;
        qualityMs = t4 - t3;
        if (progress != null) progress.update("Done", 1f);
    }
}
