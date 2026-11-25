package com.demerdash.soundplayer;

import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.demerdash.soundplayer.data.Prefs;
import com.demerdash.soundplayer.playback.EffectsManager;
import com.demerdash.soundplayer.playback.EqPresets;
import com.demerdash.soundplayer.ui.EqCurveView;
import com.demerdash.soundplayer.ui.ThemeUtil;

import java.util.ArrayList;
import java.util.List;

/** 10-band equalizer with 22 presets, bass boost, 3D surround, volume boost and balance. */
public final class EqualizerActivity extends BaseActivity {

    private static final String CUSTOM = "custom";

    private Switch enable;
    private EqCurveView curve;
    private TextView engine;
    private LinearLayout presets;
    private final List<TextView> chips = new ArrayList<>();
    private String presetKey;
    private float[] gains;
    private SeekBar bass;
    private SeekBar virt;
    private SeekBar loud;
    private SeekBar balance;
    private TextView bassValue;
    private TextView virtValue;
    private TextView loudValue;
    private TextView balanceValue;
    private View bassRow;
    private View virtRow;
    private View loudRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_equalizer);
        ThemeUtil.padInsets(findViewById(R.id.eq_top), true, false, false);
        ThemeUtil.padInsets(findViewById(R.id.eq_scroll), false, true, false);
        enable = findViewById(R.id.eq_switch);
        curve = findViewById(R.id.eq_curve);
        engine = findViewById(R.id.eq_engine);
        presets = findViewById(R.id.eq_presets);
        findViewById(R.id.eq_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        presetKey = Prefs.eqPreset(this);
        int pi = EqPresets.indexOf(presetKey);
        gains = pi >= 0 ? EqPresets.GAINS[pi].clone() : EqPresets.parse(Prefs.eqGains(this), EqPresets.FREQS.length);
        if (pi < 0) presetKey = CUSTOM;
        curve.setGains(gains);

        buildChips();
        buildEffects();

        boolean on = Prefs.eqEnabled(this);
        enable.setChecked(on);
        curve.setActive(on);
        enable.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                Prefs.setEqEnabled(EqualizerActivity.this, checked);
                curve.setActive(checked);
                EffectsManager fx = fx();
                if (fx != null) fx.setEnabled(checked);
            }
        });

        curve.setListener(new EqCurveView.Listener() {
            @Override
            public void onBandChanged(int band, float db) {
                if (!enable.isChecked()) enable.setChecked(true);
                gains = curve.getGains();
                presetKey = CUSTOM;
                updateChips();
                EffectsManager fx = fx();
                if (fx != null) fx.setGains(gains);
            }

            @Override
            public void onTrackingStopped() {
                Prefs.setEq(EqualizerActivity.this, CUSTOM, EqPresets.serialize(gains));
            }
        });

        findViewById(R.id.eq_reset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyPreset("flat");
                bass.setProgress(0);
                virt.setProgress(0);
                loud.setProgress(0);
                balance.setProgress(100);
                saveEffects();
            }
        });
        updateEngineText();
    }

    private EffectsManager fx() {
        return service != null ? service.effects() : null;
    }

    @Override
    protected void onServiceReady() {
        updateEngineText();
        EffectsManager fx = fx();
        if (fx != null) {
            bassRow.setVisibility(fx.hasBass() ? View.VISIBLE : View.GONE);
            virtRow.setVisibility(fx.hasVirtualizer() ? View.VISIBLE : View.GONE);
            loudRow.setVisibility(fx.hasLoudness() ? View.VISIBLE : View.GONE);
        }
    }

    private void updateEngineText() {
        EffectsManager fx = fx();
        if (fx == null) {
            engine.setText(R.string.eq_engine_10);
        } else if (!fx.isAvailable()) {
            engine.setText(R.string.eq_engine_none);
        } else if (fx.isTenBand()) {
            engine.setText(R.string.eq_engine_10);
        } else {
            engine.setText(getString(R.string.eq_engine_system, fx.deviceBandCount()));
        }
    }

    private static int nameFor(String key) {
        switch (key) {
            case "flat":
                return R.string.preset_flat;
            case "bass":
                return R.string.preset_bass;
            case "bass_reducer":
                return R.string.preset_bass_reducer;
            case "treble":
                return R.string.preset_treble;
            case "treble_reducer":
                return R.string.preset_treble_reducer;
            case "vocal":
                return R.string.preset_vocal;
            case "rock":
                return R.string.preset_rock;
            case "pop":
                return R.string.preset_pop;
            case "jazz":
                return R.string.preset_jazz;
            case "classical":
                return R.string.preset_classical;
            case "hiphop":
                return R.string.preset_hiphop;
            case "electronic":
                return R.string.preset_electronic;
            case "dance":
                return R.string.preset_dance;
            case "acoustic":
                return R.string.preset_acoustic;
            case "rnb":
                return R.string.preset_rnb;
            case "latin":
                return R.string.preset_latin;
            case "piano":
                return R.string.preset_piano;
            case "lounge":
                return R.string.preset_lounge;
            case "deep":
                return R.string.preset_deep;
            case "loudness":
                return R.string.preset_loudness;
            case "small_speakers":
                return R.string.preset_small_speakers;
            case "arabic":
                return R.string.preset_arabic;
            default:
                return R.string.eq_custom;
        }
    }

    private void buildChips() {
        chips.clear();
        presets.removeAllViews();
        List<String> keys = new ArrayList<>();
        keys.add(CUSTOM);
        for (String k : EqPresets.KEYS) keys.add(k);
        for (final String k : keys) {
            TextView t = new TextView(this);
            t.setText(nameFor(k));
            t.setTag(k);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            t.setTextColor(ThemeUtil.color(this, R.attr.spText));
            t.setBackgroundResource(R.drawable.bg_chip);
            t.setPadding(dp(16), dp(8), dp(16), dp(8));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (CUSTOM.equals(k)) {
                        float[] g = EqPresets.parse(Prefs.eqGains(EqualizerActivity.this), EqPresets.FREQS.length);
                        presetKey = CUSTOM;
                        gains = g;
                        curve.setGains(g);
                        Prefs.setEq(EqualizerActivity.this, CUSTOM, EqPresets.serialize(g));
                        updateChips();
                        EffectsManager fx = fx();
                        if (fx != null) fx.setGains(g);
                    } else {
                        applyPreset(k);
                    }
                    if (!enable.isChecked()) enable.setChecked(true);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            presets.addView(t, lp);
            chips.add(t);
        }
        updateChips();
    }

    private void updateChips() {
        int accent = ThemeUtil.color(this, R.attr.spAccent);
        int text = ThemeUtil.color(this, R.attr.spText);
        for (TextView t : chips) {
            boolean sel = presetKey.equals(t.getTag());
            t.setSelected(sel);
            t.setTextColor(sel ? accent : text);
        }
    }

    private void applyPreset(String key) {
        int i = EqPresets.indexOf(key);
        if (i < 0) return;
        presetKey = key;
        gains = EqPresets.GAINS[i].clone();
        curve.setGains(gains);
        Prefs.setEq(this, key, EqPresets.serialize(gains));
        updateChips();
        EffectsManager fx = fx();
        if (fx != null) fx.setGains(gains);
    }

    private void buildEffects() {
        LinearLayout box = findViewById(R.id.eq_effects);
        bassValue = new TextView(this);
        bass = new SeekBar(this);
        bassRow = effectRow(box, R.string.eq_bass, bass, bassValue, 1000, Prefs.bass(this));
        virtValue = new TextView(this);
        virt = new SeekBar(this);
        virtRow = effectRow(box, R.string.eq_virtualizer, virt, virtValue, 1000, Prefs.virtualizer(this));
        loudValue = new TextView(this);
        loud = new SeekBar(this);
        loudRow = effectRow(box, R.string.eq_loudness, loud, loudValue, 1000, Prefs.loudness(this));
        TextView warn = new TextView(this);
        warn.setText(R.string.eq_loudness_warning);
        warn.setTextColor(ThemeUtil.color(this, R.attr.spTextDim));
        warn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        warn.setPadding(dp(16), 0, dp(16), dp(6));
        ((LinearLayout) loudRow).addView(warn);
        balanceValue = new TextView(this);
        balance = new SeekBar(this);
        effectRow(box, R.string.eq_balance, balance, balanceValue, 200, Prefs.balance(this) + 100);
        refreshValues();

        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                refreshValues();
                if (!fromUser) return;
                EffectsManager fx = fx();
                if (s == balance) {
                    if (service != null) service.setBalance((p - 100) / 100f);
                    return;
                }
                if (!enable.isChecked()) enable.setChecked(true);
                if (fx == null) return;
                if (s == bass) fx.setBass(p);
                else if (s == virt) fx.setVirtualizer(p);
                else if (s == loud) fx.setLoudness(p);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                saveEffects();
            }
        };
        bass.setOnSeekBarChangeListener(l);
        virt.setOnSeekBarChangeListener(l);
        loud.setOnSeekBarChangeListener(l);
        balance.setOnSeekBarChangeListener(l);
    }

    private void saveEffects() {
        Prefs.setInt(this, "eq_bass", bass.getProgress());
        Prefs.setInt(this, "eq_virt", virt.getProgress());
        Prefs.setInt(this, "eq_loud", loud.getProgress());
        Prefs.setInt(this, "eq_balance", balance.getProgress() - 100);
        EffectsManager fx = fx();
        if (fx != null) {
            fx.setBass(bass.getProgress());
            fx.setVirtualizer(virt.getProgress());
            fx.setLoudness(loud.getProgress());
        }
        if (service != null) service.setBalance((balance.getProgress() - 100) / 100f);
    }

    private void refreshValues() {
        bassValue.setText(getString(R.string.percent, bass.getProgress() / 10));
        virtValue.setText(getString(R.string.percent, virt.getProgress() / 10));
        loudValue.setText(getString(R.string.percent, loud.getProgress() / 10));
        int b = balance.getProgress() - 100;
        balanceValue.setText(b == 0 ? "L = R" : (b < 0 ? getString(R.string.eq_balance_left) + " " + (-b) + "%"
                : getString(R.string.eq_balance_right) + " " + b + "%"));
    }

    private View effectRow(LinearLayout box, int title, SeekBar bar, TextView value, int max, int progress) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setPadding(dp(16), dp(12), dp(16), 0);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(ThemeUtil.color(this, R.attr.spText));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        value.setTextColor(ThemeUtil.color(this, R.attr.spAccent));
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        head.addView(value);
        row.addView(head);
        bar.setMax(max);
        bar.setProgress(progress);
        bar.setPadding(dp(16), dp(4), dp(16), dp(4));
        row.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        box.addView(row);
        return row;
    }
}
