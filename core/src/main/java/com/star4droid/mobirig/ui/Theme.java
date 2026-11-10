package com.star4droid.mobirig.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;

/**
 * MobiRig look: dark graphite surfaces, violet primary, cyan/amber accents.
 * Everything is measured in dp; fonts are rasterised at the real pixel size of the
 * screen (dp × density) so text stays sharp on every phone, tablet and monitor.
 */
public class Theme implements Disposable {
    // icons (Material Icons code points)
    public static final char I_OPEN = '', I_ROT = '', I_FLIP = '', I_MIRROR = '',
        I_FRAME = '', I_RESET = '', I_NEXT = '', I_BACK = '', I_TPOSE = '',
        I_WALK = '', I_CHECK = '', I_DONE = '', I_SAVE = '', I_EYE = '',
        I_PALETTE = '', I_PREV = '', I_NEXT_SMALL = '', I_MAGIC = '', I_FIT = '',
        I_PERSON = '', I_GYM = '', I_MARTIAL = '', I_ACCESS = '', I_REFRESH = '',
        I_INFO = '', I_ERROR = '', I_CLOSE = '', I_FACE = '', I_TOUCH = '',
        I_LAYERS = '', I_HEIGHT = '', I_FLOOR = '', I_ROT_L = '', I_ROT_R = '',
        I_UP = '', I_BOLT = '', I_SPARK = '', I_TUNE = '', I_RUN = '',
        I_YOGA = '', I_HAND = '', I_PEOPLE = '', I_3D = '', I_360 = '',
        I_SIDE = '', I_SETTINGS = '', I_BONES = '', I_TIMER = '', I_ROTATE = '', I_MORE = '\ue5cf', I_LESS = '\ue5ce', I_PALM = '\ue769';

    public final float density;
    public final Color bg = hex(0x0E1016), surface = hex(0x161922), surface2 = hex(0x1E222D), surface3 = hex(0x272C39),
        outline = hex(0x323849), primary = hex(0x7C5CFF), primaryHi = hex(0x9A80FF), primaryDim = hex(0x2D2552),
        cyan = hex(0x2EE6D6), amber = hex(0xFFC34D), danger = hex(0xFF5D73), success = hex(0x3DDC97),
        text = hex(0xF1F3F8), textDim = hex(0xA1A9BA), textFaint = hex(0x6C7487);

    public BitmapFont body, bodyBold, small, smallBold, title, display, icons, iconsSmall, iconsLarge;
    public Drawable sheet, sheetSide, card, cardHi, chip, chipDown, chipOn, primaryBtn, primaryDown, primaryOff,
        ghost, ghostDown, dotShape, circle, tool, toolDown, toolOn, toast, toastError, track, fill, white, scrim, topBar, badge;

    private final Array<Disposable> owned = new Array<>();

    public Theme(float density) {
        this.density = density;
        String chars = FreeTypeFontGenerator.DEFAULT_CHARS + "–—•…·→←×";
        body = font("fonts/Inter-400Regular.ttf", 15, chars);
        bodyBold = font("fonts/Inter-600SemiBold.ttf", 15, chars);
        small = font("fonts/Inter-400Regular.ttf", 13, chars);
        smallBold = font("fonts/Inter-600SemiBold.ttf", 12.5f, chars);
        title = font("fonts/Inter-700Bold.ttf", 19, chars);
        display = font("fonts/Inter-700Bold.ttf", 24, chars);
        StringBuilder ic = new StringBuilder();
        for (java.lang.reflect.Field f : Theme.class.getFields()) {
            if (f.getType() == char.class) {
                try {
                    ic.append(f.getChar(null));
                } catch (IllegalAccessException ignored) {
                }
            }
        }
        icons = font("fonts/MaterialIcons.ttf", 24, ic.toString());
        iconsSmall = font("fonts/MaterialIcons.ttf", 19, ic.toString());
        iconsLarge = font("fonts/MaterialIcons.ttf", 40, ic.toString());

        sheet = round(18, surface, outline, 1, true, true, false, false);
        sheetSide = round(18, surface, outline, 1, true, false, true, false);
        card = round(14, surface2, null, 0, true, true, true, true);
        cardHi = round(14, surface3, null, 0, true, true, true, true);
        chip = round(12, surface2, outline, 1, true, true, true, true);
        chipDown = round(12, surface3, outline, 1, true, true, true, true);
        chipOn = round(12, primaryDim, primary, 1.5f, true, true, true, true);
        primaryBtn = round(14, primary, null, 0, true, true, true, true);
        primaryDown = round(14, hex(0x6646E6), null, 0, true, true, true, true);
        primaryOff = round(14, hex(0x2E2A45), null, 0, true, true, true, true);
        ghost = round(14, new Color(0, 0, 0, 0), outline, 1.5f, true, true, true, true);
        ghostDown = round(14, surface3, outline, 1.5f, true, true, true, true);
        tool = round(14, surface2, null, 0, true, true, true, true);
        toolDown = round(14, surface3, null, 0, true, true, true, true);
        toolOn = round(14, primaryDim, primary, 1.5f, true, true, true, true);
        toast = round(14, hex(0x232838), outline, 1, true, true, true, true);
        toastError = round(14, hex(0x3A1F27), danger, 1.5f, true, true, true, true);
        track = round(4, surface3, null, 0, true, true, true, true);
        fill = round(4, primary, null, 0, true, true, true, true);
        badge = round(9, surface3, null, 0, true, true, true, true);
        dotShape = round(6, Color.WHITE, null, 0, true, true, true, true);
        circle = circleTex(64);
        topBar = solid(new Color(bg.r, bg.g, bg.b, 0.92f));
        scrim = solid(new Color(0, 0, 0, 0.55f));
        white = solid(Color.WHITE);
    }

    public float dp(float v) {
        return v * density;
    }

    private BitmapFont font(String path, float sizeDp, String chars) {
        FreeTypeFontGenerator gen = new FreeTypeFontGenerator(Gdx.files.internal(path));
        FreeTypeFontGenerator.FreeTypeFontParameter p = new FreeTypeFontGenerator.FreeTypeFontParameter();
        p.size = Math.max(8, Math.round(sizeDp * density));
        p.characters = chars;
        p.minFilter = Texture.TextureFilter.Linear;
        p.magFilter = Texture.TextureFilter.Linear;
        p.hinting = FreeTypeFontGenerator.Hinting.Slight;
        p.kerning = true;
        FreeTypeFontGenerator.FreeTypeBitmapFontData data = new FreeTypeFontGenerator.FreeTypeBitmapFontData();
        if (path.contains("Icons")) {
            // icon fonts have no letters: measure x-height / cap height on an icon glyph
            data.xChars = new char[]{I_PERSON};
            data.capChars = new char[]{I_PERSON};
        }
        BitmapFont f = gen.generateFont(p, data);
        gen.dispose();
        // render in dp units: one font pixel = one screen pixel
        f.getData().setScale(1f / density);
        f.setUseIntegerPositions(false);
        owned.add(f);
        return f;
    }

    private Drawable circleTex(int size) {
        Pixmap pm = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        pm.setBlending(Pixmap.Blending.None);
        float r = size / 2f;
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                float d = (float) Math.sqrt((x + 0.5f - r) * (x + 0.5f - r) + (y + 0.5f - r) * (y + 0.5f - r)) - (r - 1);
                pm.drawPixel(x, y, Color.rgba8888(1, 1, 1, clamp(0.5f - d)));
            }
        Texture t = new Texture(pm, true);
        t.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        pm.dispose();
        owned.add(t);
        return new TextureRegionDrawable(new TextureRegion(t));
    }

    private Drawable solid(Color c) {
        Pixmap pm = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        pm.setColor(c);
        pm.fill();
        Texture t = new Texture(pm);
        pm.dispose();
        owned.add(t);
        return new TextureRegionDrawable(new TextureRegion(t));
    }

    /** Anti-aliased rounded rectangle as a nine-patch; corners: tl, tr, bl, br. */
    private Drawable round(float radiusDp, Color fillC, Color strokeC, float strokeDp,
                           boolean tl, boolean tr, boolean bl, boolean br) {
        int r = Math.max(2, Math.round(radiusDp * density));
        float sw = strokeC == null ? 0 : Math.max(1f, strokeDp * density);
        int size = r * 2 + 3;
        Pixmap pm = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        pm.setBlending(Pixmap.Blending.None);
        float half = size / 2f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float px = x + 0.5f - half, py = y + 0.5f - half;
                boolean top = py < 0, left = px < 0;
                boolean rounded = top ? (left ? tl : tr) : (left ? bl : br);
                float rr = rounded ? r : 0;
                float qx = Math.abs(px) - (half - rr), qy = Math.abs(py) - (half - rr);
                float d = (float) Math.sqrt(Math.max(qx, 0) * Math.max(qx, 0) + Math.max(qy, 0) * Math.max(qy, 0))
                    + Math.min(Math.max(qx, qy), 0) - rr;
                float a = clamp(0.5f - d);
                if (a <= 0) {
                    pm.drawPixel(x, y, 0);
                    continue;
                }
                float cr = fillC.r, cg = fillC.g, cb = fillC.b, ca = fillC.a;
                if (strokeC != null) {
                    float s = clamp(0.5f - (Math.abs(d + sw * 0.5f) - sw * 0.5f));
                    cr = cr * (1 - s) + strokeC.r * s;
                    cg = cg * (1 - s) + strokeC.g * s;
                    cb = cb * (1 - s) + strokeC.b * s;
                    ca = ca * (1 - s) + strokeC.a * s;
                }
                pm.drawPixel(x, y, Color.rgba8888(cr, cg, cb, ca * a));
            }
        }
        Texture t = new Texture(pm);
        t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        pm.dispose();
        owned.add(t);
        NinePatch np = new NinePatch(t, r + 1, r + 1, r + 1, r + 1);
        np.scale(1f / density, 1f / density); // patch sizes in dp
        return new NinePatchDrawable(np);
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    public static Color hex(int rgb) {
        return new Color(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, 1f);
    }

    /** Keeps only characters the bundled font can draw (file names may contain other scripts). */
    public static String safe(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 32 && c < 127) || (c >= 0xA0 && c <= 0xFF) || "–—•…·→←×".indexOf(c) >= 0;
            b.append(ok ? c : '?');
        }
        return b.toString();
    }

    @Override
    public void dispose() {
        for (Disposable d : owned) d.dispose();
    }
}
