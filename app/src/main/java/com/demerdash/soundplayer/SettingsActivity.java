package com.demerdash.soundplayer;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.demerdash.soundplayer.data.MusicLibrary;
import com.demerdash.soundplayer.data.Prefs;
import com.demerdash.soundplayer.ui.Dialogs;
import com.demerdash.soundplayer.ui.ThemeUtil;

/** Settings: theme & accent, library filter, playback options, about/privacy. */
public final class SettingsActivity extends BaseActivity {

    private LinearLayout container;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        ThemeUtil.padInsets(findViewById(R.id.settings_top), true, false, false);
        ThemeUtil.padInsets(findViewById(R.id.settings_scroll), false, true, false);
        container = findViewById(R.id.settings_container);
        findViewById(R.id.settings_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        build();
    }

    private void build() {
        container.removeAllViews();

        section(R.string.set_appearance);
        final String[] themeKeys = {Prefs.THEME_DARK, Prefs.THEME_LIGHT, Prefs.THEME_SYSTEM};
        final String[] themeNames = {getString(R.string.theme_dark), getString(R.string.theme_light), getString(R.string.theme_system)};
        int ti = 0;
        for (int i = 0; i < themeKeys.length; i++) if (themeKeys[i].equals(Prefs.theme(this))) ti = i;
        final int themeIndex = ti;
        row(R.drawable.ic_palette, getString(R.string.set_theme), themeNames[ti], null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Dialogs.choose(SettingsActivity.this, getString(R.string.set_theme), themeNames, themeIndex, new Dialogs.Choice() {
                    @Override
                    public void onChosen(int index) {
                        Prefs.setTheme(SettingsActivity.this, themeKeys[index]);
                        ThemeUtil.bump();
                        recreate();
                    }
                });
            }
        });
        accentRow();

        section(R.string.set_library);
        final int[] durations = {0, 10, 30, 60};
        final String[] durNames = {getString(R.string.min_duration_off), getString(R.string.min_duration_value, 10),
                getString(R.string.min_duration_value, 30), getString(R.string.min_duration_value, 60)};
        int di = 0;
        for (int i = 0; i < durations.length; i++) if (durations[i] == Prefs.minDurationSec(this)) di = i;
        final int durIndex = di;
        row(R.drawable.ic_music_note, getString(R.string.set_min_duration), durNames[di], null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Dialogs.choose(SettingsActivity.this, getString(R.string.set_min_duration), durNames, durIndex, new Dialogs.Choice() {
                    @Override
                    public void onChosen(int index) {
                        Prefs.setMinDurationSec(SettingsActivity.this, durations[index]);
                        MusicLibrary.get(SettingsActivity.this).load(true);
                        build();
                    }
                });
            }
        });
        row(R.drawable.ic_refresh, getString(R.string.menu_rescan), getString(R.string.set_rescan_summary), null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MusicLibrary.get(SettingsActivity.this).load(true);
                toast(R.string.rescan_started);
            }
        });

        section(R.string.set_playback);
        toggle(R.drawable.ic_queue, R.string.set_gapless, R.string.set_gapless_summary, "gapless", Prefs.gapless(this));
        toggle(R.drawable.ic_equalizer, R.string.set_fade, R.string.set_fade_summary, "fade", Prefs.fade(this));
        toggle(R.drawable.ic_history, R.string.set_resume_rewind, R.string.set_resume_rewind_summary, "rewind_resume", Prefs.rewindOnResume(this));
        toggle(R.drawable.ic_info, R.string.set_keep_screen_on, R.string.set_keep_screen_on_summary, "keep_screen_on", Prefs.keepScreenOn(this));
        row(R.drawable.ic_tune, getString(R.string.menu_equalizer), null, null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, EqualizerActivity.class));
            }
        });

        section(R.string.set_about);
        String version = "1.0";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        row(R.drawable.ic_info, getString(R.string.app_name), getString(R.string.set_version, version), null, null);
        row(R.drawable.ic_lock, getString(R.string.set_privacy), getString(R.string.set_privacy_summary), null, null);
        row(R.drawable.ic_share, getString(R.string.set_source), getString(R.string.set_source_summary), null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/DemerDash123/SoundPlayer")));
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void section(int title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setAllCaps(true);
        t.setTextColor(ThemeUtil.color(this, R.attr.spAccent));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setLetterSpacing(0.06f);
        t.setPadding(dp(20), dp(22), dp(20), dp(6));
        container.addView(t);
    }

    private LinearLayout row(int icon, String title, String summary, View trailing, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(12), dp(16), dp(12));
        row.setMinimumHeight(dp(60));
        if (click != null) {
            TypedValue tv = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            row.setBackgroundResource(tv.resourceId);
            row.setOnClickListener(click);
        }
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setImageTintList(android.content.res.ColorStateList.valueOf(ThemeUtil.color(this, R.attr.spTextDim)));
        row.addView(iv, new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(18), 0, dp(8), 0);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(ThemeUtil.color(this, R.attr.spText));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        texts.addView(t);
        if (summary != null) {
            TextView s = new TextView(this);
            s.setText(summary);
            s.setTextColor(ThemeUtil.color(this, R.attr.spTextDim));
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            s.setPadding(0, dp(2), 0, 0);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (trailing != null) row.addView(trailing);
        container.addView(row);
        return row;
    }

    private void toggle(int icon, int title, int summary, final String key, boolean value) {
        final Switch sw = new Switch(this);
        sw.setChecked(value);
        sw.setClickable(false);
        sw.setFocusable(false);
        row(icon, getString(title), getString(summary), sw, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.setChecked(!sw.isChecked());
            }
        });
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                Prefs.setBool(SettingsActivity.this, key, checked);
            }
        });
    }

    private void accentRow() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setPadding(dp(16), dp(4), dp(16), dp(10));
        int current = Prefs.accent(this);
        for (int i = 0; i < ThemeUtil.ACCENT_COLORS.length; i++) {
            final int index = i;
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(getColor(ThemeUtil.ACCENT_COLORS[i]));
            if (i == current) g.setStroke(dp(3), ThemeUtil.color(this, R.attr.spText));
            dot.setBackground(g);
            dot.setContentDescription(getString(R.string.set_accent) + " " + (i + 1));
            dot.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Prefs.setAccent(SettingsActivity.this, index);
                    ThemeUtil.bump();
                    recreate();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(38));
            lp.setMargins(dp(6), dp(4), dp(6), dp(4));
            dots.addView(dot, lp);
        }
        scroll.addView(dots);
        TextView label = new TextView(this);
        label.setText(R.string.set_accent);
        label.setTextColor(ThemeUtil.color(this, R.attr.spText));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setPadding(dp(62), dp(10), dp(16), dp(2));
        container.addView(label);
        container.addView(scroll);
    }
}
