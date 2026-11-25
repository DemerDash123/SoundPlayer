package com.demerdash.soundplayer.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import com.demerdash.soundplayer.R;
import com.demerdash.soundplayer.data.Prefs;

/** Theme (dark / light / system + accent) and edge-to-edge helpers. */
public final class ThemeUtil {

    public static final int[] ACCENT_STYLES = {
            R.style.Accent_Violet, R.style.Accent_Blue, R.style.Accent_Teal, R.style.Accent_Green,
            R.style.Accent_Orange, R.style.Accent_Pink, R.style.Accent_Red, R.style.Accent_Gold
    };
    public static final int[] ACCENT_COLORS = {
            R.color.accent_violet, R.color.accent_blue, R.color.accent_teal, R.color.accent_green,
            R.color.accent_orange, R.color.accent_pink, R.color.accent_red, R.color.accent_gold
    };

    private static int version;

    private ThemeUtil() {
    }

    public static int version() {
        return version;
    }

    public static void bump() {
        version++;
    }

    public static boolean isDark(Context c) {
        String mode = Prefs.theme(c);
        if (Prefs.THEME_LIGHT.equals(mode)) return false;
        if (Prefs.THEME_SYSTEM.equals(mode)) {
            int night = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return night == Configuration.UI_MODE_NIGHT_YES;
        }
        return true;
    }

    /** Call before super.onCreate(). */
    public static void apply(Activity a) {
        a.setTheme(isDark(a) ? R.style.Theme_SP_Dark : R.style.Theme_SP_Light);
        int accent = Prefs.accent(a);
        if (accent < 0 || accent >= ACCENT_STYLES.length) accent = 0;
        a.getTheme().applyStyle(ACCENT_STYLES[accent], true);
    }

    public static int accentColor(Context c) {
        int accent = Prefs.accent(c);
        if (accent < 0 || accent >= ACCENT_COLORS.length) accent = 0;
        return c.getColor(ACCENT_COLORS[accent]);
    }

    /** Draw behind status and navigation bars. {@code lightIcons} forces white system icons (Now Playing). */
    public static void edgeToEdge(Activity a, boolean lightIcons) {
        Window w = a.getWindow();
        boolean darkIcons = !lightIcons && !isDark(a);
        View decor = w.getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(darkIcons ? mask : 0, mask);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (darkIcons) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            decor.setSystemUiVisibility(flags);
        }
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
            w.setStatusBarContrastEnforced(false);
        }
    }

    /** {left, top, right, bottom} system bar (+ cutout, + keyboard if requested) insets. */
    public static int[] insets(WindowInsets in, boolean includeIme) {
        if (Build.VERSION.SDK_INT >= 30) {
            int types = WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout();
            if (includeIme) types |= WindowInsets.Type.ime();
            Insets i = in.getInsets(types);
            return new int[]{i.left, i.top, i.right, i.bottom};
        }
        return new int[]{in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(),
                in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom()};
    }

    /** Adds system bar insets to the view's original padding. */
    public static void padInsets(final View v, final boolean top, final boolean bottom, final boolean ime) {
        final int l = v.getPaddingLeft();
        final int t = v.getPaddingTop();
        final int r = v.getPaddingRight();
        final int b = v.getPaddingBottom();
        v.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets in) {
                int[] s = insets(in, ime);
                view.setPadding(l + s[0], t + (top ? s[1] : 0), r + s[2], b + (bottom ? s[3] : 0));
                return in;
            }
        });
        v.requestApplyInsets();
    }

    /** Adds the bottom inset to the view's bottom margin (floating mini player). */
    public static void marginBottomInset(final View v) {
        final android.view.ViewGroup.MarginLayoutParams lp = (android.view.ViewGroup.MarginLayoutParams) v.getLayoutParams();
        final int base = lp.bottomMargin;
        final int baseL = lp.leftMargin;
        final int baseR = lp.rightMargin;
        v.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets in) {
                int[] s = insets(in, false);
                android.view.ViewGroup.MarginLayoutParams p = (android.view.ViewGroup.MarginLayoutParams) view.getLayoutParams();
                if (p.bottomMargin != base + s[3] || p.leftMargin != baseL + s[0] || p.rightMargin != baseR + s[2]) {
                    p.bottomMargin = base + s[3];
                    p.leftMargin = baseL + s[0];
                    p.rightMargin = baseR + s[2];
                    view.setLayoutParams(p);
                }
                return in;
            }
        });
        v.requestApplyInsets();
    }

    public static int color(Context c, int attr) {
        TypedArray ta = c.obtainStyledAttributes(new int[]{attr});
        int col = ta.getColor(0, Color.MAGENTA);
        ta.recycle();
        return col;
    }

    public static int dp(Context c, float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, c.getResources().getDisplayMetrics()));
    }

    /** Blend two colors. */
    public static int blend(int a, int b, float t) {
        float u = 1f - t;
        return Color.argb(
                Math.round(Color.alpha(a) * u + Color.alpha(b) * t),
                Math.round(Color.red(a) * u + Color.red(b) * t),
                Math.round(Color.green(a) * u + Color.green(b) * t),
                Math.round(Color.blue(a) * u + Color.blue(b) * t));
    }

    public static int withAlpha(int color, float alpha) {
        return (Math.round(Color.alpha(color) * alpha) << 24) | (color & 0x00FFFFFF);
    }
}
