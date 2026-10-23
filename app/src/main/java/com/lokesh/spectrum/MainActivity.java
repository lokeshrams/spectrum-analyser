package com.lokesh.spectrum;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Arrays;

public class MainActivity extends Activity {

    private static final int REQ_AUDIO = 1;
    private static final int MIC_RATE = 44100;
    private static final int MIC_N = 2048;

    static final String[] PRESET_NAMES = {"Neon", "Fire", "Ocean", "Rainbow", "Sunset", "Matrix"};
    static final int[][] PRESETS = {
            {0xFF00E5FF, 0xFF7C4DFF, 0xFFFF00E5},
            {0xFFB71C1C, 0xFFFF6F00, 0xFFFFEB3B},
            {0xFF0D47A1, 0xFF00B8D4, 0xFFE0F7FA},
            {0xFFFF1744, 0xFFFF9100, 0xFFFFEA00, 0xFF00E676, 0xFF2979FF, 0xFFD500F9},
            {0xFF6A1B9A, 0xFFE91E63, 0xFFFFB74D},
            {0xFF003300, 0xFF00C853, 0xFFB9F6CA},
    };

    private interface ColorCallback { void onColor(int color); }

    private SpectrumView spectrum;
    private TextView status;
    private final Button[] modeButtons = new Button[3];
    private SharedPreferences prefs;
    private int mode;
    private int[] palette;
    private boolean askedPermission = false;

    private Visualizer visualizer;
    private AudioRecord audioRecord;
    private Thread micThread;
    private volatile boolean micRunning;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("spectrum", MODE_PRIVATE);
        mode = prefs.getInt("mode", SpectrumView.MODE_BARS);
        palette = loadPalette();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        spectrum = new SpectrumView(this);
        spectrum.setPadding(0, dp(32), 0, dp(60));
        spectrum.setMode(mode);
        spectrum.setPalette(palette);
        root.addView(spectrum, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setTextColor(0xFF9E9E9E);
        status.setTextSize(12);
        status.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xE0111111);
        String[] labels = {"Bars", "Line", "Waterfall"};
        for (int i = 0; i < 3; i++) {
            final int m = i;
            Button b = makeBarButton(labels[i]);
            b.setOnClickListener(v -> setMode(m));
            modeButtons[i] = b;
            bar.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        Button colours = makeBarButton("Colours");
        colours.setTextColor(Color.WHITE);
        colours.setOnClickListener(v -> showPaletteDialog());
        bar.addView(colours, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        root.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56), Gravity.BOTTOM));

        setContentView(root);
        refreshButtons();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAudio();
        } else if (!askedPermission) {
            askedPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        } else {
            showPermissionMessage();
        }
    }

    @Override
    protected void onPause() {
        stopAudio();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_AUDIO) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startAudio();
        else showPermissionMessage();
    }

    private void showPermissionMessage() {
        status.setText("Audio permission needed. Allow it in Settings > Apps > Spectrum Analyser.");
    }

    // ---------------------------------------------------------------- audio

    private void startAudio() {
        stopAudio();
        try {
            Visualizer v = new Visualizer(0);   // session 0 = everything the phone is playing
            int[] range = Visualizer.getCaptureSizeRange();
            v.setCaptureSize(range[1]);
            final int size = v.getCaptureSize();
            final float fs = v.getSamplingRate() / 1000f;   // milliHz -> Hz
            int rc = v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer vis, byte[] wave, int rate) { }

                @Override
                public void onFftDataCapture(Visualizer vis, byte[] fft, int rate) {
                    handleVisualizerFft(fft, fs, size);
                }
            }, Visualizer.getMaxCaptureRate(), false, true);
            if (rc != Visualizer.SUCCESS) throw new IllegalStateException("listener " + rc);
            if (v.setEnabled(true) != Visualizer.SUCCESS) throw new IllegalStateException("enable");
            visualizer = v;
            status.setText("Source: phone audio");
        } catch (Throwable t) {
            if (visualizer != null) { try { visualizer.release(); } catch (Throwable ignored) { } }
            visualizer = null;
            startMic();
        }
    }

    private void handleVisualizerFft(byte[] fft, float fs, int size) {
        int n = fft.length;
        if (n < 8) return;
        int bins = n / 2 + 1;
        float[] mags = new float[bins];
        mags[0] = Math.abs(fft[0]);
        mags[bins - 1] = Math.abs(fft[1]);
        for (int k = 1; k < n / 2; k++) {
            float re = fft[2 * k];
            float im = fft[2 * k + 1];
            mags[k] = (float) Math.sqrt(re * re + im * im);
        }
        spectrum.submit(mags, fs / n);
    }

    private void startMic() {
        int min = AudioRecord.getMinBufferSize(MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, MIC_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, MIC_N * 4));
        } catch (Throwable t) {
            audioRecord = null;
        }
        if (audioRecord == null || audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            if (audioRecord != null) audioRecord.release();
            audioRecord = null;
            status.setText("Could not open phone audio or microphone.");
            return;
        }
        status.setText("Source: microphone (phone audio not available on this device)");
        audioRecord.startRecording();
        micRunning = true;
        final AudioRecord rec = audioRecord;
        micThread = new Thread(() -> micLoop(rec), "mic-fft");
        micThread.start();
    }

    private void micLoop(AudioRecord rec) {
        short[] buf = new short[MIC_N];
        float[] window = new float[MIC_N];
        for (int i = 0; i < MIC_N; i++) window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (MIC_N - 1)));
        float[] re = new float[MIC_N];
        float[] im = new float[MIC_N];
        while (micRunning) {
            int got = 0;
            while (got < MIC_N && micRunning) {
                int r = rec.read(buf, got, MIC_N - got);
                if (r <= 0) break;
                got += r;
            }
            if (!micRunning || got < MIC_N) continue;
            for (int i = 0; i < MIC_N; i++) {
                re[i] = buf[i] / 32768f * window[i];
                im[i] = 0f;
            }
            fft(re, im);
            final float[] mags = new float[MIC_N / 2 + 1];
            float scale = 128f / (MIC_N / 4f);   // full-scale sine -> ~128, like the Visualizer
            for (int k = 0; k < mags.length; k++) {
                mags[k] = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]) * scale;
            }
            spectrum.post(() -> spectrum.submit(mags, (float) MIC_RATE / MIC_N));
        }
    }

    private void stopAudio() {
        if (visualizer != null) {
            try { visualizer.setEnabled(false); } catch (Throwable ignored) { }
            try { visualizer.release(); } catch (Throwable ignored) { }
            visualizer = null;
        }
        micRunning = false;
        if (micThread != null) {
            try { micThread.join(500); } catch (InterruptedException ignored) { }
            micThread = null;
        }
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Throwable ignored) { }
            audioRecord.release();
            audioRecord = null;
        }
    }

    /** In-place iterative radix-2 FFT. Length must be a power of two. */
    static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1f, ci = 0f;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    float xr = re[b] * cr - im[b] * ci;
                    float xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    float ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }

    // ---------------------------------------------------------------- mode & palette

    private void setMode(int m) {
        mode = m;
        spectrum.setMode(m);
        prefs.edit().putInt("mode", m).apply();
        refreshButtons();
    }

    private void refreshButtons() {
        int accent = palette[palette.length - 1];
        for (int i = 0; i < modeButtons.length; i++) {
            boolean sel = i == mode;
            modeButtons[i].setTextColor(sel ? accent : 0xFF8A8A8A);
            modeButtons[i].setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private void applyPalette(int[] p) {
        palette = p.clone();
        spectrum.setPalette(palette);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < palette.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(palette[i]);
        }
        prefs.edit().putString("palette", sb.toString()).apply();
        refreshButtons();
    }

    private int[] loadPalette() {
        String s = prefs.getString("palette", null);
        if (s != null) {
            try {
                String[] parts = s.split(",");
                if (parts.length >= 2) {
                    int[] p = new int[parts.length];
                    for (int i = 0; i < parts.length; i++) p[i] = Integer.parseInt(parts[i].trim());
                    return p;
                }
            } catch (NumberFormatException ignored) { }
        }
        return PRESETS[0].clone();
    }

    private void showPaletteDialog() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(20), dp(8), dp(20), dp(8));

        final AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Colours")
                .setNegativeButton("Close", null)
                .create();

        for (int i = 0; i < PRESETS.length; i++) {
            final int[] p = PRESETS[i];
            String name = PRESET_NAMES[i] + (Arrays.equals(p, palette) ? "  ✓" : "");
            View row = paletteRow(name, p);
            row.setOnClickListener(v -> { applyPalette(p); dialog.dismiss(); });
            list.addView(row);
        }
        View custom = paletteRow("Custom gradient…", new int[]{palette[0], palette[palette.length - 1]});
        custom.setOnClickListener(v -> { dialog.dismiss(); showCustomDialog(); });
        list.addView(custom);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        dialog.setView(scroll);
        dialog.show();
    }

    private View paletteRow(String name, int[] colors) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        row.setClickable(true);

        View sw = new View(this);
        sw.setBackground(gradient(colors));
        row.addView(sw, new LinearLayout.LayoutParams(dp(96), dp(28)));

        TextView tv = new TextView(this);
        tv.setText(name);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(16);
        tv.setPadding(dp(16), 0, 0, 0);
        row.addView(tv);
        return row;
    }

    private void showCustomDialog() {
        final int[] cur = {palette[0], palette[palette.length - 1]};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));

        final View preview = new View(this);
        preview.setBackground(gradient(cur));
        box.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        final View[] swatches = new View[2];
        String[] names = {"Start colour (quiet / low)", "End colour (loud / high)"};
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(14), 0, 0);
            View sw = new View(this);
            sw.setBackground(solid(cur[i]));
            swatches[i] = sw;
            row.addView(sw, new LinearLayout.LayoutParams(dp(44), dp(44)));
            TextView tv = new TextView(this);
            tv.setText(names[i] + "\nTap to change");
            tv.setTextColor(Color.WHITE);
            tv.setPadding(dp(14), 0, 0, 0);
            row.addView(tv);
            row.setOnClickListener(v -> showHsvPicker(cur[idx], color -> {
                cur[idx] = color;
                swatches[idx].setBackground(solid(color));
                preview.setBackground(gradient(cur));
            }));
            box.addView(row);
        }

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Custom gradient")
                .setView(box)
                .setPositiveButton("Apply", (d, w) -> applyPalette(cur))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showHsvPicker(int initial, ColorCallback cb) {
        final float[] hsv = new float[3];
        Color.colorToHSV(initial, hsv);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));

        final View preview = new View(this);
        preview.setBackground(solid(initial));
        box.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        String[] labels = {"Hue", "Saturation", "Brightness"};
        int[] max = {360, 100, 100};
        int[] start = {Math.round(hsv[0]), Math.round(hsv[1] * 100), Math.round(hsv[2] * 100)};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView tv = new TextView(this);
            tv.setText(labels[i]);
            tv.setTextColor(0xFFBDBDBD);
            tv.setPadding(0, dp(14), 0, 0);
            box.addView(tv);
            SeekBar sb = new SeekBar(this);
            sb.setMax(max[i]);
            sb.setProgress(start[i]);
            if (i == 0) {
                GradientDrawable hue = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{
                        0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000});
                hue.setCornerRadius(dp(4));
                sb.setBackground(hue);
            }
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                    if (idx == 0) hsv[0] = Math.min(359.9f, progress);
                    else hsv[idx] = progress / 100f;
                    preview.setBackground(solid(Color.HSVToColor(hsv)));
                }
                @Override public void onStartTrackingTouch(SeekBar s) { }
                @Override public void onStopTrackingTouch(SeekBar s) { }
            });
            box.addView(sb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
        }

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Pick a colour")
                .setView(box)
                .setPositiveButton("OK", (d, w) -> cb.onColor(Color.HSVToColor(hsv)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------------------------------------------------------------- helpers

    private Button makeBarButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setBackgroundColor(Color.TRANSPARENT);
        return b;
    }

    private GradientDrawable gradient(int[] colors) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors.clone());
        g.setCornerRadius(dp(6));
        return g;
    }

    private GradientDrawable solid(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(6));
        g.setStroke(dp(1), 0x55FFFFFF);
        return g;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
