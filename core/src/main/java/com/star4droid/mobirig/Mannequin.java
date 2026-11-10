package com.star4droid.mobirig;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.g3d.utils.shapebuilders.BoxShapeBuilder;
import com.badlogic.gdx.graphics.g3d.utils.shapebuilders.SphereShapeBuilder;
import com.badlogic.gdx.math.Matrix4;
import net.mgsx.gltf.scene3d.attributes.PBRColorAttribute;
import net.mgsx.gltf.scene3d.attributes.PBRFloatAttribute;

/** Simple 1.8 m T-pose placeholder shown before a GLB is opened (no asset file, keeps the APK small). */
public final class Mannequin {
    private Mannequin() {
    }

    public static Model build() {
        Material mat = new Material(
            PBRColorAttribute.createBaseColorFactor(new Color(0.55f, 0.58f, 0.63f, 1f)),
            PBRFloatAttribute.createMetallic(0f),
            PBRFloatAttribute.createRoughness(0.7f));
        ModelBuilder mb = new ModelBuilder();
        mb.begin();
        MeshPartBuilder b = mb.part("body", GL20.GL_TRIANGLES,
            VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal, mat);
        // torso, pelvis
        BoxShapeBuilder.build(b, 0f, 1.25f, 0f, 0.36f, 0.50f, 0.20f);
        BoxShapeBuilder.build(b, 0f, 0.95f, 0f, 0.32f, 0.16f, 0.19f);
        // neck + head
        BoxShapeBuilder.build(b, 0f, 1.54f, 0f, 0.09f, 0.08f, 0.09f);
        b.setVertexTransform(new Matrix4().setToTranslation(0f, 1.67f, 0.01f));
        SphereShapeBuilder.build(b, 0.21f, 0.25f, 0.23f, 16, 12);
        b.setVertexTransform(null);
        // arms (T-pose), both sides
        for (int s = -1; s <= 1; s += 2) {
            BoxShapeBuilder.build(b, s * 0.33f, 1.44f, 0f, 0.30f, 0.09f, 0.09f);   // upper arm
            BoxShapeBuilder.build(b, s * 0.60f, 1.44f, 0f, 0.26f, 0.075f, 0.075f); // forearm
            BoxShapeBuilder.build(b, s * 0.80f, 1.44f, 0f, 0.14f, 0.03f, 0.08f);   // hand
            BoxShapeBuilder.build(b, s * 0.10f, 0.66f, 0f, 0.13f, 0.44f, 0.13f);   // thigh
            BoxShapeBuilder.build(b, s * 0.10f, 0.25f, 0f, 0.10f, 0.40f, 0.10f);   // calf
            BoxShapeBuilder.build(b, s * 0.10f, 0.03f, 0.05f, 0.10f, 0.06f, 0.24f); // foot
        }
        return mb.end();
    }
}
