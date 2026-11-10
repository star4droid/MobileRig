package com.star4droid.mobirig.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Array;

/**
 * Responsive overlay. Portrait phones: top bar + bottom sheet. Landscape / tablets / desktop:
 * top bar + side panel. All sizes in dp; grids compute exact cell widths so nothing can run
 * off the screen; long text wraps or ends with an ellipsis.
 */
public class Hud {
    public interface Check {
        boolean on();
    }

    public static class Item {
        public final char icon;
        public final String label;
        public final Runnable action;
        public Color dot;
        public Check checked;
        public boolean enabled = true;

        public Item(char icon, String label, Runnable action) {
            this.icon = icon;
            this.label = label;
            this.action = action;
        }

        public Item dot(Color c) {
            dot = c;
            return this;
        }

        public Item checked(Check c) {
            checked = c;
            return this;
        }

        public Item enabled(boolean e) {
            enabled = e;
            return this;
        }
    }

    public static class Panel {
        public String title = "", hint = "";
        public String chipsTitle, toolsTitle;
        public final Array<Item> chips = new Array<>(), tools = new Array<>();
        /** Icon-only strip shown at the top of the 3D view (no labels). */
        public final Array<Item> viewIcons = new Array<>();
        public Item primary, secondary;
        /** Largest share of the screen height the bottom sheet may take (phones in portrait). */
        public float maxHeight = 0.56f;
        /** Fixed number of chip columns (0 = automatic). */
        public int chipCols;
    }

    private static final String[] STEPS = {"Import", "Markers", "Rig", "Test"};
    private static final float TOP = 56f;

    private final Theme t;
    private final Stage stage;
    private final Table root = new Table();
    private final Table toastBox = new Table();
    private final Table progressLayer = new Table();
    private Label progressTitle, progressPct;
    private Container<Actor> progressFill;
    private float progressValue;
    private Table sheet;
    private Table iconRow;
    private boolean sideLayout;
    private Panel panel = new Panel();
    private int step;
    private String info = "";
    private boolean collapsed, sheetHidden;
    private ScrollPane scroll;
    private float keepScroll = -1;
    private float insetTop, insetBottom, insetLeft, insetRight;

    public Hud(Theme theme, Stage stage) {
        this.t = theme;
        this.stage = stage;
        root.setFillParent(true);
        root.setTouchable(Touchable.childrenOnly);
        stage.addActor(root);
        stage.addActor(toastBox);
        toastBox.setVisible(false);
        buildProgress();
        stage.addActor(progressLayer);
    }

    public void setInsets(float top, float bottom, float left, float right) {
        insetTop = top;
        insetBottom = bottom;
        insetLeft = left;
        insetRight = right;
    }

    public void show(int step, Panel p) {
        if (step != this.step) {
            collapsed = false;
            scroll = null;
        }
        this.step = step;
        this.panel = p;
        rebuild();
    }

    public void setInfo(String s) {
        info = Theme.safe(s);
        rebuild();
    }

    public void setHint(String s) {
        panel.hint = s;
        rebuild();
    }

    /** Re-reads checked/enabled states (call after a selection changes). */
    public void refresh() {
        rebuild();
    }

    // ------------------------------------------------------------------ layout

    public void rebuild() {
        keepScroll = scroll != null ? scroll.getScrollY() : -1;
        root.clear();
        float w = stage.getWidth(), h = stage.getHeight();
        sideLayout = w >= 700 || (w > h * 1.15f && w >= 560);
        root.pad(insetTop, insetLeft, insetBottom, insetRight);

        Table top = topBar(w);
        sheet = buildSheet(w, h);
        Table infoRow = new Table();
        if (!info.isEmpty()) {
            Label l = label(info, t.small, t.textDim);
            l.setEllipsis(true);
            Table chip = new Table();
            chip.setBackground(t.badge);
            chip.add(icon(Theme.I_PERSON, t.iconsSmall, t.cyan)).padRight(6);
            chip.add(l).minWidth(0).maxWidth((sideLayout ? w - sideWidth(w) : w) - 56);
            chip.pad(5, 10, 5, 12);
            infoRow.add(chip).left().minWidth(0);
        }
        iconRow = panel.viewIcons.size > 0 ? iconStrip() : null;
        if (sideLayout) {
            root.add(top).colspan(2).growX().height(TOP).row();
            Table left = new Table();
            if (iconRow != null) left.add(iconRow).top().padTop(10).row();
            left.add(infoRow).left().top().pad(10, 12, 0, 12).minWidth(0).row();
            left.add().expand();
            root.add(left).grow().minWidth(0);
            root.add(sheet).width(sideWidth(w)).growY().padTop(4);
        } else {
            root.add(top).growX().height(TOP).row();
            if (iconRow != null) root.add(iconRow).top().padTop(8).row();
            root.add(infoRow).left().top().pad(8, 12, 0, 12).minWidth(0).row();
            root.add().expand().row();
            root.add(sheet).growX();
        }
        root.validate();
        if (scroll != null && keepScroll > 0) {
            scroll.layout();
            scroll.setScrollY(keepScroll);
            scroll.updateVisualScroll();
        }
        sheet.setVisible(!sheetHidden);
        if (iconRow != null) iconRow.setVisible(!sheetHidden);
        layoutToast();
        layoutProgress();
    }

    /** Hides the sheet without changing the layout (the 3D view keeps its size). */
    public void setSheetHidden(boolean hidden) {
        sheetHidden = hidden;
        if (sheet != null) sheet.setVisible(!hidden);
        if (iconRow != null) iconRow.setVisible(!hidden);
        if (hidden) toastBox.setVisible(false);
    }

    /** Sheet area in screen pixels (x, y from bottom-left). */
    public Rectangle sheetRect(Rectangle out) {
        if (sheet == null) return out.set(0, 0, 0, 0);
        float s = Gdx.graphics.getWidth() / Math.max(1f, stage.getWidth());
        com.badlogic.gdx.math.Vector2 v = sheet.localToStageCoordinates(new com.badlogic.gdx.math.Vector2(0, 0));
        return out.set(v.x * s, v.y * s, sheet.getWidth() * s, sheet.getHeight() * s);
    }

    private float sideWidth(float w) {
        return Math.min(380f, Math.max(300f, w * 0.34f));
    }

    private Table topBar(float w) {
        Table bar = new Table();
        bar.setBackground(t.topBar);
        bar.pad(0, 14, 0, 12);
        Table logo = new Table();
        Table mark = new Table();
        mark.setBackground(t.primaryBtn);
        mark.pad(0);
        mark.add(icon(Theme.I_3D, t.iconsSmall, Color.WHITE)).size(26);
        logo.add(mark).size(32).padRight(10);
        logo.add(label("MobiRig", t.title, t.text));
        bar.add(logo).left();
        bar.add().expandX();
        boolean wide = w >= 620;
        if (wide) {
            for (int i = 0; i < STEPS.length; i++) {
                boolean done = i < step, cur = i == step;
                Table dot = new Table();
                dot.setBackground(cur ? t.primaryBtn : done ? t.chipOn : t.chip);
                dot.pad(0);
                if (done) dot.add(icon(Theme.I_CHECK, t.iconsSmall, t.cyan)).size(22);
                else dot.add(label(String.valueOf(i + 1), t.smallBold, cur ? Color.WHITE : t.textDim));
                bar.add(dot).size(26).padLeft(i == 0 ? 0 : 6);
                bar.add(label(STEPS[i], cur ? t.smallBold : t.small, cur ? t.text : t.textDim)).padLeft(6);
                if (i < STEPS.length - 1) {
                    Image line = new Image(t.white);
                    line.setColor(done ? t.primary : t.outline);
                    bar.add(line).width(18).height(2).padLeft(8);
                }
            }
        } else {
            Table steps = new Table();
            for (int i = 0; i < STEPS.length; i++) {
                Image seg = new Image(t.white);
                seg.setColor(i <= step ? (i == step ? t.primary : t.cyan) : t.outline);
                steps.add(seg).width(16).height(4).padLeft(i == 0 ? 0 : 4);
            }
            Table col = new Table();
            col.add(label((step + 1) + "/4  " + STEPS[step], t.smallBold, t.text)).right().row();
            col.add(steps).right().padTop(5);
            bar.add(col).right();
        }
        return bar;
    }

    private Table buildSheet(float w, float h) {
        float sheetW = sideLayout ? sideWidth(w) : w;
        float inner = sheetW - 32;
        boolean canCollapse = !sideLayout;

        // header: title + collapse chevron (tap to show more of the 3D view)
        Table header = new Table();
        Label title = label(panel.title, t.title, t.text);
        title.setEllipsis(true);
        header.add(title).growX().minWidth(0).left();
        if (canCollapse) header.add(icon(collapsed ? Theme.I_LESS : Theme.I_MORE, t.icons, t.textDim)).padLeft(8);

        Table content = new Table();
        content.top().left();
        content.pad(0, 16, 8, 16);
        if (panel.hint != null && !panel.hint.isEmpty()) {
            Label hint = label(Theme.safe(panel.hint), t.body, t.textDim);
            hint.setWrap(true);
            content.add(hint).width(inner).left().padTop(2).row();
        }
        if (panel.chips.size > 0) {
            if (panel.chipsTitle != null) content.add(caption(panel.chipsTitle)).left().padTop(16).row();
            int cols = panel.chipCols > 0 && (inner - 8 * (panel.chipCols - 1)) / panel.chipCols >= 118 ? panel.chipCols
                : inner < 400 ? 2 : Math.max(2, Math.min(4, (int) (inner / 150)));
            content.add(grid(panel.chips, cols, inner, true)).left().padTop(8).row();
        }
        if (panel.tools.size > 0) {
            if (panel.toolsTitle != null) content.add(caption(panel.toolsTitle)).left().padTop(panel.chips.size > 0 ? 8 : 16).row();
            int cols = Math.max(3, Math.min(6, (int) (inner / 84)));
            content.add(grid(panel.tools, cols, inner, false)).left().padTop(8).row();
        }

        ScrollPane scroll = new ScrollPane(content);
        this.scroll = scroll;
        scroll.setScrollingDisabled(true, false);
        scroll.setOverscroll(false, true);
        scroll.setFadeScrollBars(true);
        scroll.setSmoothScrolling(true);

        Table actions = new Table();
        if (panel.secondary != null) actions.add(actionButton(panel.secondary, false)).height(52).growX().uniformX().padRight(panel.primary != null ? 10 : 0);
        if (panel.primary != null) actions.add(actionButton(panel.primary, true)).height(52).growX().uniformX();

        Table s = new Table();
        s.setBackground(sideLayout ? t.sheetSide : t.sheet);
        s.pad(0);
        s.setTouchable(Touchable.enabled);
        Table top = new Table();
        if (canCollapse) {
            Image handle = new Image(t.dotShape);
            handle.setColor(t.outline);
            top.add(handle).width(40).height(5).padTop(8).row();
        }
        top.add(header).growX().pad(canCollapse ? 6 : 18, 16, 8, 12);
        if (canCollapse) {
            top.setTouchable(Touchable.enabled);
            top.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    collapsed = !collapsed;
                    Gdx.app.postRunnable(Hud.this::rebuild);
                }
            });
        }
        s.add(top).growX().row();
        content.validate();
        float contentH = content.getPrefHeight();
        float actionsH = actions.hasChildren() ? 52 + 24 : 8;
        float maxH = Math.max(110f, h * panel.maxHeight - actionsH - 50);
        if (sideLayout) s.add(scroll).grow().row();
        else if (!collapsed) s.add(scroll).growX().height(Math.min(contentH, maxH)).row();
        if (actions.hasChildren()) s.add(actions).growX().pad(8, 16, 16, 16);
        else s.add().height(8);
        return s;
    }

    /** One row of bare icons on a soft pill: big touch targets, no text, no button tiles. */
    private Table iconStrip() {
        Table row = new Table();
        row.setBackground(t.badge);
        row.pad(2, 6, 2, 6);
        row.setTouchable(Touchable.enabled);
        for (final Item it : panel.viewIcons) {
            final boolean on = it.checked != null && it.checked.on();
            final Label l = icon(it.icon, t.icons, on ? t.primary : t.text);
            final Color base = new Color(on ? t.primary : t.text);
            Container<Label> c = new Container<>(l);
            c.setTouchable(Touchable.enabled);
            c.addListener(new com.badlogic.gdx.scenes.scene2d.utils.ClickListener() {
                @Override
                public boolean touchDown(InputEvent e, float x, float y, int pointer, int button) {
                    l.setColor(t.cyan);
                    return super.touchDown(e, x, y, pointer, button);
                }

                @Override
                public void touchUp(InputEvent e, float x, float y, int pointer, int button) {
                    l.setColor(base);
                    super.touchUp(e, x, y, pointer, button);
                }

                @Override
                public void clicked(InputEvent e, float x, float y) {
                    if (it.enabled && it.action != null) Gdx.app.postRunnable(it.action);
                }
            });
            row.add(c).size(48, 44);
        }
        return row;
    }

    private Label caption(String s) {
        return label(s.toUpperCase(), t.smallBold, t.textFaint);
    }

    private Table grid(Array<Item> items, int cols, float inner, boolean chips) {
        Table g = new Table();
        float gap = 8;
        float cellW = (inner - gap * (cols - 1)) / cols;
        for (int i = 0; i < items.size; i++) {
            Button b = chips ? chip(items.get(i)) : tool(items.get(i));
            g.add(b).width(cellW).height(chips ? 48 : 70).padRight(i % cols == cols - 1 ? 0 : gap).padBottom(gap);
            if (i % cols == cols - 1) g.row();
        }
        return g;
    }

    private Button tool(final Item it) {
        Button.ButtonStyle st = new Button.ButtonStyle(t.tool, t.toolDown, t.toolOn);
        Button b = new Button(st);
        b.pad(6, 4, 6, 4);
        boolean on = it.checked != null && it.checked.on();
        b.setChecked(on);
        Label ic = icon(it.icon, t.icons, on ? t.primaryHi : t.text);
        Label lb = label(it.label, t.small, on ? t.text : t.textDim);
        lb.setEllipsis(true);
        lb.setAlignment(Align.center);
        b.add(ic).row();
        b.add(lb).minWidth(0).growX().padTop(4);
        wire(b, it);
        return b;
    }

    private Button chip(final Item it) {
        Button.ButtonStyle st = new Button.ButtonStyle(t.chip, t.chipDown, t.chipOn);
        Button b = new Button(st);
        b.pad(0);
        boolean on = it.checked != null && it.checked.on();
        b.setChecked(on);
        if (it.dot != null) {
            Image d = new Image(t.dotShape);
            d.setColor(it.dot);
            b.add(d).size(12).padLeft(12).padRight(8);
        } else b.add(icon(it.icon, t.iconsSmall, on ? t.primaryHi : t.textDim)).padLeft(10).padRight(6);
        Label lb = label(it.label, on ? t.bodyBold : t.body, on ? t.text : t.textDim);
        lb.setEllipsis(true);
        b.add(lb).minWidth(0).growX().left().padRight(10);
        wire(b, it);
        return b;
    }

    private Button actionButton(final Item it, boolean primary) {
        Button.ButtonStyle st = primary ? new Button.ButtonStyle(t.primaryBtn, t.primaryDown, null)
            : new Button.ButtonStyle(t.ghost, t.ghostDown, null);
        if (primary) st.disabled = t.primaryOff;
        Button b = new Button(st);
        b.pad(0);
        Color c = primary ? (it.enabled ? Color.WHITE : t.textFaint) : t.text;
        Table row = new Table();
        if (!primary) row.add(icon(it.icon, t.iconsSmall, c)).padRight(6);
        Label lb = label(it.label, t.bodyBold, c);
        lb.setEllipsis(true);
        row.add(lb).minWidth(0);
        if (primary) row.add(icon(it.icon, t.iconsSmall, c)).padLeft(8);
        b.add(row).minWidth(0).pad(0, 12, 0, 12);
        b.setDisabled(!it.enabled);
        wire(b, it);
        return b;
    }

    private void wire(final Button b, final Item it) {
        b.setProgrammaticChangeEvents(false);
        b.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (it.checked != null) b.setChecked(it.checked.on()); // state is owned by the app
                if (!it.enabled || it.action == null) return;
                // run after the event so the action can rebuild the UI safely
                Gdx.app.postRunnable(it.action);
            }
        });
    }

    private Label label(String s, com.badlogic.gdx.graphics.g2d.BitmapFont f, Color c) {
        Label.LabelStyle ls = new Label.LabelStyle(f, c);
        return new Label(s, ls);
    }

    private Label icon(char c, com.badlogic.gdx.graphics.g2d.BitmapFont f, Color col) {
        Label l = label(String.valueOf(c), f, col);
        l.setAlignment(Align.center);
        return l;
    }

    // ------------------------------------------------------------------ viewport

    /** Free area for the 3D view in screen pixels (x, y from bottom-left). */
    public Rectangle viewRect(Rectangle out) {
        float s = Gdx.graphics.getWidth() / Math.max(1f, stage.getWidth());
        float topPx = (TOP + insetTop) * s;
        if (sheet == null) return out.set(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        if (sideLayout) {
            float sw = sheet.getWidth() * s + insetRight * s;
            return out.set(0, 0, Math.max(1, Gdx.graphics.getWidth() - sw), Math.max(1, Gdx.graphics.getHeight() - topPx));
        }
        float sh = (sheet.getHeight() + insetBottom) * s;
        return out.set(0, sh, Gdx.graphics.getWidth(), Math.max(1, Gdx.graphics.getHeight() - topPx - sh));
    }

    // ------------------------------------------------------------------ toast

    public void toast(String msg, boolean error) {
        toastBox.clear();
        toastBox.clearActions();
        toastBox.setBackground(error ? t.toastError : t.toast);
        toastBox.pad(12, 14, 12, 14);
        toastBox.add(icon(error ? Theme.I_ERROR : Theme.I_INFO, t.iconsSmall, error ? t.danger : t.cyan)).top().padRight(10);
        Label l = label(Theme.safe(msg), t.body, t.text);
        l.setWrap(true);
        toastBox.add(l).growX().minWidth(0);
        toastBox.setTouchable(Touchable.enabled);
        toastBox.clearListeners();
        toastBox.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                toastBox.clearActions();
                toastBox.addAction(Actions.sequence(Actions.fadeOut(0.15f), Actions.visible(false)));
            }
        });
        toastBox.setVisible(true);
        layoutToast();
        toastBox.getColor().a = 0;
        toastBox.addAction(Actions.sequence(Actions.fadeIn(0.18f, Interpolation.fade),
            Actions.delay(error ? 7f : 2.2f), Actions.fadeOut(0.3f), Actions.visible(false)));
    }

    private void layoutToast() {
        float w = Math.min(stage.getWidth() - 24 - insetLeft - insetRight, 520);
        if (sideLayout && sheet != null) w = Math.min(w, stage.getWidth() - sheet.getWidth() - 24);
        toastBox.setWidth(w);
        toastBox.invalidate();
        toastBox.setHeight(toastBox.getPrefHeight());
        toastBox.validate();
        toastBox.setHeight(toastBox.getPrefHeight());
        float areaW = sideLayout && sheet != null ? stage.getWidth() - sheet.getWidth() : stage.getWidth();
        float below = iconRow != null ? iconRow.getPrefHeight() + 8 : 0; // keep the icon strip reachable
        toastBox.setPosition((areaW - w) / 2f, stage.getHeight() - insetTop - TOP - 12 - below - toastBox.getHeight());
        toastBox.toFront();
    }

    // ------------------------------------------------------------------ progress

    private void buildProgress() {
        progressLayer.setFillParent(true);
        progressLayer.setBackground(t.scrim);
        progressLayer.setTouchable(Touchable.enabled);
        Table card = new Table();
        card.setBackground(t.card);
        card.pad(22, 22, 22, 22);
        card.add(icon(Theme.I_SPARK, t.iconsLarge, t.primaryHi)).row();
        progressTitle = label("", t.title, t.text);
        progressTitle.setAlignment(Align.center);
        progressTitle.setWrap(true);
        card.add(progressTitle).growX().padTop(8).row();
        progressPct = label("", t.body, t.textDim);
        progressPct.setAlignment(Align.center);
        progressPct.setWrap(true);
        card.add(progressPct).growX().padTop(4).row();
        Table track = new Table();
        track.setBackground(t.track);
        track.pad(0);
        progressFill = new Container<>(new Image(t.fill));
        progressFill.fill();
        track.add(progressFill).left().height(8).width(0);
        track.add().growX();
        card.add(track).growX().height(8).padTop(16);
        progressLayer.add(card).width(300);
        progressLayer.setVisible(false);
    }

    public void progress(String title, String detail, float f) {
        progressLayer.setVisible(true);
        progressLayer.toFront();
        progressTitle.setText(title);
        progressPct.setText(detail);
        progressValue = Math.max(0, Math.min(1, f));
        layoutProgress();
    }

    private void layoutProgress() {
        if (!progressLayer.isVisible()) return;
        Table card = (Table) progressLayer.getChildren().first();
        progressLayer.getCell(card).width(Math.min(320, stage.getWidth() - 48));
        progressLayer.validate();
        Table track = (Table) progressFill.getParent();
        track.getCell(progressFill).width(Math.max(0.001f, (Math.min(320, stage.getWidth() - 48) - 44) * progressValue));
        track.invalidate();
    }

    public void hideProgress() {
        progressLayer.setVisible(false);
    }

    public boolean sideLayout() {
        return sideLayout;
    }
}
