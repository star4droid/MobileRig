package com.star4droid.mobirig.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Disposable;

/**
 * "Where do the markers go?" window: a reference character with every marker, a leader line
 * and a clear label. The picture is fitted inside the window on every screen size.
 */
public class MarkerGuide implements Disposable {
    // normalised positions on the picture (x right, y down) and label anchors
    private static final String[] NAMES = {"Chin", "Neck", "Shoulder", "Wrist", "Pelvis", "Ankle"};
    private static final boolean[] CENTER = {true, true, false, false, true, false};
    private static final float[][] POINT = {
        {0.4809f, 0.3011f}, {0.4831f, 0.3379f}, {0.6135f, 0.3678f},
        {0.8449f, 0.3793f}, {0.4843f, 0.6494f}, {0.5742f, 0.8621f}};
    private static final float[] MIRROR_X = {0, 0, 0.3528f, 0.1213f, 0, 0.3921f};
    private static final float[][] LABEL = {
        {0.17f, 0.16f}, {0.83f, 0.16f}, {0.80f, 0.53f},
        {0.86f, 0.25f}, {0.17f, 0.60f}, {0.82f, 0.75f}};
    private static final float ASPECT = 626f / 640f;

    private final Theme t;
    private final Texture tex;
    private final Table layer = new Table();

    public MarkerGuide(Theme theme) {
        this.t = theme;
        tex = new Texture(Gdx.files.internal("guide/markers_guide.jpg"), true);
        tex.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        layer.setFillParent(true);
        layer.setBackground(t.scrim);
        layer.setTouchable(Touchable.enabled);
        layer.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                if (event.getTarget() == layer) hide();
            }
        });
        layer.setVisible(false);
    }

    public Table actor() {
        return layer;
    }

    public boolean isShown() {
        return layer.isVisible();
    }

    public void hide() {
        layer.setVisible(false);
    }

    /** Builds the window for the current stage size and shows it. */
    public void show() {
        float sw = layer.getStage().getWidth(), sh = layer.getStage().getHeight();
        layer.clear();
        float cardW = Math.min(sw - 24, 560);
        float inner = cardW - 32;
        Table card = new Table();
        card.setBackground(t.card);
        card.pad(16);
        card.setTouchable(Touchable.enabled);

        Table head = new Table();
        Label title = new Label("Where do the markers go?", new Label.LabelStyle(t.title, t.text));
        title.setWrap(true);
        head.add(title).growX().minWidth(0).left();
        Label close = new Label(String.valueOf(Theme.I_CLOSE), new Label.LabelStyle(t.icons, t.textDim));
        Table closeBtn = new Table();
        closeBtn.add(close);
        closeBtn.setTouchable(Touchable.enabled);
        closeBtn.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                hide();
            }
        });
        head.add(closeBtn).size(44).right().top();
        card.add(head).growX().row();

        Label sub = new Label("Front view. Yellow markers sit on the centre line; cyan ones on the character's left side "
            + "(screen right) and are mirrored for you.", new Label.LabelStyle(t.small, t.textDim));
        sub.setWrap(true);
        card.add(sub).growX().padTop(2).row();

        // figure: as big as fits under the text
        sub.setWidth(inner);
        sub.layout();
        float textH = 44 + sub.getPrefHeight() + 16;
        float maxFigH = sh - 24 - 32 - textH - 16;
        float figW = Math.min(inner, maxFigH / ASPECT);
        figW = Math.max(160, figW);
        card.add(new Figure()).size(figW, figW * ASPECT).padTop(12).row();
        layer.add(card).width(cardW);
        layer.setVisible(true);
        layer.toFront();
    }

    private class Figure extends Widget {
        private final TextureRegion region = new TextureRegion(tex);
        private final GlyphLayout gl = new GlyphLayout();

        @Override
        public void draw(Batch batch, float parentAlpha) {
            float x0 = getX(), y0 = getY(), w = getWidth(), h = getHeight();
            Color old = new Color(batch.getColor());
            batch.setColor(1, 1, 1, parentAlpha);
            batch.draw(region, x0, y0, w, h);
            TextureRegion white = ((TextureRegionDrawable) t.white).getRegion();
            BitmapFont font = w < 300 ? t.smallBold : t.bodyBold;
            float dot = w < 300 ? 5.5f : 7f;
            for (int i = 0; i < NAMES.length; i++) {
                Color c = CENTER[i] ? t.amber : t.cyan;
                float px = x0 + POINT[i][0] * w, py = y0 + (1 - POINT[i][1]) * h;
                // label pill, kept inside the picture
                gl.setText(font, NAMES[i]);
                float pw = gl.width + 26, ph = gl.height + 14;
                float lx = MathUtils.clamp(x0 + LABEL[i][0] * w - pw / 2, x0 + 4, x0 + w - pw - 4);
                float ly = MathUtils.clamp(y0 + (1 - LABEL[i][1]) * h - ph / 2, y0 + 4, y0 + h - ph - 4);
                float ax = MathUtils.clamp(px, lx, lx + pw), ay = MathUtils.clamp(py, ly, ly + ph);
                // leader line (dark halo + colour)
                line(batch, white, px, py, ax, ay, 4f, 0, 0, 0, 0.55f * parentAlpha);
                line(batch, white, px, py, ax, ay, 2f, c.r, c.g, c.b, parentAlpha);
                // mirrored marker
                if (!CENTER[i]) {
                    float mx = x0 + MIRROR_X[i] * w;
                    disc(batch, mx, py, dot + 2, 0, 0, 0, 0.7f * parentAlpha);
                    disc(batch, mx, py, dot, c.r, c.g, c.b, 0.55f * parentAlpha);
                }
                disc(batch, px, py, dot + 2.5f, 0, 0, 0, 0.85f * parentAlpha);
                disc(batch, px, py, dot, c.r, c.g, c.b, parentAlpha);
                batch.setColor(1, 1, 1, parentAlpha * 0.95f);
                t.badge.draw(batch, lx, ly, pw, ph);
                batch.setColor(c.r, c.g, c.b, parentAlpha);
                t.circle.draw(batch, lx + 9, ly + ph / 2 - 4, 8, 8);
                font.setColor(t.text);
                font.draw(batch, NAMES[i], lx + 21, ly + ph / 2 + gl.height / 2, pw - 24, Align.left, false);
            }
            batch.setColor(old);
        }

        private void disc(Batch batch, float x, float y, float r, float cr, float cg, float cb, float a) {
            batch.setColor(cr, cg, cb, a);
            t.circle.draw(batch, x - r, y - r, r * 2, r * 2);
        }

        private void line(Batch batch, TextureRegion px, float x1, float y1, float x2, float y2, float th,
                          float r, float g, float b, float a) {
            float dx = x2 - x1, dy = y2 - y1, len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1) return;
            batch.setColor(r, g, b, a);
            batch.draw(px, x1, y1 - th / 2, 0, th / 2, len, th, 1, 1, MathUtils.atan2(dy, dx) * MathUtils.radiansToDegrees);
        }
    }

    @Override
    public void dispose() {
        tex.dispose();
    }
}
