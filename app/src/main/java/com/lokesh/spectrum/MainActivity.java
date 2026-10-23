package com.lokesh.spectrum;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaMetadata;
import android.media.MediaRecorder;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.provider.Settings;
import android.text.TextUtils;
import android.media.audiofx.Visualizer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class MainActivity extends Activity {

    private static final int REQ_AUDIO = 1;
    private static final int MIC_RATE = 44100;
    private static final int MIC_N = 2048;

    static final String[] PRESET_NAMES = {"Neon", "Fire", "Ocean", "Rainbow", "Sunset", "Matrix", "Winamp Classic"};
    static final int[][] PRESETS = {
            {0xFF00E5FF, 0xFF7C4DFF, 0xFFFF00E5},
            {0xFFB71C1C, 0xFFFF6F00, 0xFFFFEB3B},
            {0xFF0D47A1, 0xFF00B8D4, 0xFFE0F7FA},
            {0xFFFF1744, 0xFFFF9100, 0xFFFFEA00, 0xFF00E676, 0xFF2979FF, 0xFFD500F9},
            {0xFF6A1B9A, 0xFFE91E63, 0xFFFFB74D},
            {0xFF003300, 0xFF00C853, 0xFFB9F6CA},
            {0xFF18A53A, 0xFF29CE10, 0xFFD6B521, 0xFFD65A00, 0xFFEF3110},
    };

    static final float[] FALL_VALUES = {0f, 0.5f, 1f, 2f, 3f, 5f};
    static final String[] FALL_NAMES = {"Off (no fall)", "0.5 s", "1 s", "2 s", "3 s", "5 s"};
    static final float[] WIDTH_VALUES = {2f, 4f, 7f, 10f};
    static final int[] BAND_VALUES = {96, 64, 40, 24};     // fewer bars = wider bars
    static final String[] WIDTH_NAMES = {"Thin (96 bars)", "Medium (64 bars)", "Thick (40 bars)", "Extra thick (24 bars)"};
    static final int[] ORIENT_VALUES = {
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE};
    static final String[] ORIENT_NAMES = {"Auto-rotate", "Portrait", "Landscape"};

    static final String[] STYLE_NAMES = {"Bars", "Line", "Waterfall",
            "Rainbow LED", "Rack Analyzer", "Pro Amber", "Dot Matrix", "Car Stereo",
            "Glow Tubes", "Winamp Amber", "Winamp Silver", "Winamp Mirror",
            "Scope Dots", "Scope Lines", "Scope Solid"};
    static final float[] PEAK_FALL_VALUES = {6f, 4f, 2.5f, 1.2f, 0.6f};
    static final String[] PEAK_FALL_NAMES = {"Slower", "Slow", "Moderate", "Fast", "Faster"};
    static final String[] COLORING_NAMES = {"Normal (gradient by height)", "Fire (hot colour on every tip)",
            "Line (one colour per bar)"};
    static final int[] AUTO_VALUES = {0, 10, 20, 30, 60};
    static final String[] AUTO_NAMES = {"Off", "Every 10 s", "Every 20 s", "Every 30 s", "Every 60 s"};
    static final String[] ON_OFF = {"On", "Off"};

    private interface ColorCallback { void onColor(int color); }

    private SpectrumView spectrum;
    private TextView status;
    private Button styleLabel;
    private LinearLayout bar;
    private boolean controlsVisible = true;
    private boolean hideHintShown = false;
    private int autoIdx;
    private boolean keepOn;
    private boolean ledPalette;
    private boolean peaksOn;
    private int peakFallIdx;
    private int coloringIdx;
    private boolean nowPlayingOn;
    private TextView nowPlaying;
    private final Runnable nowPlayingPoll = new Runnable() {
        @Override public void run() {
            updateNowPlaying();
            handler.postDelayed(this, 1500);
        }
    };
    private boolean autoGain;
    private int gainDb;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rng = new Random();
    private final Runnable autoCycle = new Runnable() {
        @Override public void run() {
            randomStyle();   // randomStyle() reschedules the timer
        }
    };
    private SharedPreferences prefs;
    private int mode;
    private int[] palette;
    private boolean askedPermission = false;
    private int fallIdx;
    private int widthIdx;
    private int orientIdx;

    private Visualizer visualizer;
    private AudioRecord audioRecord;
    private Thread micThread;
    private volatile boolean micRunning;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("spectrum", MODE_PRIVATE);
        mode = clampIdx(prefs.getInt("mode", SpectrumView.MODE_BARS), STYLE_NAMES.length);
        autoIdx = clampIdx(prefs.getInt("autoIdx", 0), AUTO_VALUES.length);
        keepOn = prefs.getBoolean("keepOn", true);
        ledPalette = prefs.getBoolean("ledPalette", false);
        peaksOn = prefs.getBoolean("peaksOn", true);
        peakFallIdx = clampIdx(prefs.getInt("peakFallIdx", 2), PEAK_FALL_VALUES.length);
        coloringIdx = clampIdx(prefs.getInt("coloringIdx", 0), COLORING_NAMES.length);
        nowPlayingOn = prefs.getBoolean("nowPlaying", true);
        autoGain = prefs.getBoolean("autoGain", true);
        gainDb = Math.max(-12, Math.min(24, prefs.getInt("gainDb", 0)));
        applyKeepOn();
        palette = loadPalette();
        fallIdx = clampIdx(prefs.getInt("fallIdx", 3), FALL_VALUES.length);
        widthIdx = clampIdx(prefs.getInt("widthIdx", 1), WIDTH_VALUES.length);
        orientIdx = clampIdx(prefs.getInt("orientIdx", 0), ORIENT_VALUES.length);
        setRequestedOrientation(ORIENT_VALUES[orientIdx]);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        spectrum = new SpectrumView(this);
        spectrum.setPadding(0, dp(32), 0, dp(60));
        spectrum.setMode(mode);
        spectrum.setPalette(palette);
        spectrum.setFallSeconds(FALL_VALUES[fallIdx]);
        spectrum.setLineWidthDp(WIDTH_VALUES[widthIdx]);
        spectrum.setBandCount(BAND_VALUES[widthIdx]);
        spectrum.setAutoGain(autoGain);
        spectrum.setGainDb(gainDb);
        spectrum.setUsePalette(ledPalette);
        spectrum.setPeaksEnabled(peaksOn);
        spectrum.setPeakFallSeconds(PEAK_FALL_VALUES[peakFallIdx]);
        spectrum.setColoring(coloringIdx);
        root.addView(spectrum, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setTextColor(0xFF9E9E9E);
        status.setTextSize(12);
        status.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));

        nowPlaying = new TextView(this);
        nowPlaying.setTextColor(0xFFE0E0E0);
        nowPlaying.setTextSize(13);
        nowPlaying.setSingleLine(true);
        nowPlaying.setEllipsize(TextUtils.TruncateAt.END);
        nowPlaying.setGravity(Gravity.END);
        nowPlaying.setPadding(dp(12), dp(8), dp(12), dp(8));
        nowPlaying.setVisibility(View.GONE);
        root.addView(nowPlaying, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                nowPlaying.setMaxWidth((int) ((r - l) * 0.68f)));

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xE0111111);
        Button prev = makeBarButton("◀");
        prev.setOnClickListener(v -> stepStyle(-1));
        bar.addView(prev, new LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.MATCH_PARENT));
        styleLabel = makeBarButton("");
        styleLabel.setTypeface(null, Typeface.BOLD);
        styleLabel.setSingleLine(true);
        styleLabel.setEllipsize(TextUtils.TruncateAt.END);
        styleLabel.setOnClickListener(v -> pickOne("Style", STYLE_NAMES, mode, this::setMode));
        bar.addView(styleLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        Button next = makeBarButton("▶");
        next.setOnClickListener(v -> stepStyle(1));
        bar.addView(next, new LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.MATCH_PARENT));
        Button random = makeBarButton("🎲");
        random.setTextSize(20);
        random.setOnClickListener(v -> randomStyle());
        bar.addView(random, new LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.MATCH_PARENT));
        Button colours = makeBarButton("🎨");
        colours.setTextSize(20);
        colours.setOnClickListener(v -> showPaletteDialog());
        bar.addView(colours, new LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.MATCH_PARENT));
        Button settings = makeBarButton("⚙");
        settings.setTextColor(Color.WHITE);
        settings.setTextSize(20);
        settings.setOnClickListener(v -> showSettingsDialog());
        bar.addView(settings, new LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56), Gravity.BOTTOM));

        final GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                toggleControls();
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (Math.abs(vx) > Math.abs(vy) && Math.abs(vx) > dp(400)) {
                    stepStyle(vx < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
        });
        spectrum.setOnTouchListener((v, ev) -> gestures.onTouchEvent(ev));

        setContentView(root);
        refreshButtons();
    }

    @Override
    protected void onResume() {
        super.onResume();
        scheduleAutoCycle();
        handler.removeCallbacks(nowPlayingPoll);
        handler.post(nowPlayingPoll);
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
        handler.removeCallbacks(autoCycle);
        handler.removeCallbacks(nowPlayingPoll);
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
                public void onWaveFormDataCapture(Visualizer vis, byte[] wave, int rate) {
                    float[] w = new float[wave.length];
                    for (int i = 0; i < wave.length; i++) w[i] = ((wave[i] & 0xFF) - 128) / 128f;
                    spectrum.submitWave(w, w.length);
                }

                @Override
                public void onFftDataCapture(Visualizer vis, byte[] fft, int rate) {
                    handleVisualizerFft(fft, fs, size);
                }
            }, Visualizer.getMaxCaptureRate(), true, true);
            if (rc != Visualizer.SUCCESS) throw new IllegalStateException("listener " + rc);
            if (v.setEnabled(true) != Visualizer.SUCCESS) throw new IllegalStateException("enable");
            visualizer = v;
            status.setText("Phone audio");
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
        status.setText("Microphone (phone audio blocked)");
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
            final float[] samples = new float[MIC_N];
            for (int i = 0; i < MIC_N; i++) {
                samples[i] = buf[i] / 32768f;
                re[i] = samples[i] * window[i];
                im[i] = 0f;
            }
            fft(re, im);
            final float[] mags = new float[MIC_N / 2 + 1];
            float scale = 128f / (MIC_N / 4f);   // full-scale sine -> ~128, like the Visualizer
            for (int k = 0; k < mags.length; k++) {
                mags[k] = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]) * scale;
            }
            spectrum.post(() -> {
                spectrum.submit(mags, (float) MIC_RATE / MIC_N);
                spectrum.submitWave(samples, MIC_N / 2);   // ~23 ms window, like the phone-audio scope
            });
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
        mode = clampIdx(m, STYLE_NAMES.length);
        spectrum.setMode(mode);
        prefs.edit().putInt("mode", mode).apply();
        refreshButtons();
        scheduleAutoCycle();   // a manual change restarts the auto-cycle timer
    }

    private void stepStyle(int dir) {
        int n = STYLE_NAMES.length;
        setMode(((mode + dir) % n + n) % n);
        if (!controlsVisible) flashStyleName();
    }

    private void randomStyle() {
        int n = STYLE_NAMES.length;
        int m = mode;
        if (n > 1) while (m == mode) m = rng.nextInt(n);
        setMode(m);
        if (!controlsVisible) flashStyleName();
    }

    private void flashStyleName() {
        Toast.makeText(this, STYLE_NAMES[mode], Toast.LENGTH_SHORT).show();
    }

    private void scheduleAutoCycle() {
        handler.removeCallbacks(autoCycle);
        if (AUTO_VALUES[autoIdx] > 0) handler.postDelayed(autoCycle, AUTO_VALUES[autoIdx] * 1000L);
    }

    private void toggleControls() {
        controlsVisible = !controlsVisible;
        int vis = controlsVisible ? View.VISIBLE : View.GONE;
        bar.setVisibility(vis);
        status.setVisibility(vis);
        spectrum.setPadding(0, controlsVisible ? dp(32) : dp(8), 0, controlsVisible ? dp(60) : dp(4));
        if (!controlsVisible && !hideHintShown) {
            hideHintShown = true;
            Toast.makeText(this, "Tap to show controls · swipe to change style", Toast.LENGTH_SHORT).show();
        }
    }

    private void applyKeepOn() {
        if (keepOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void refreshButtons() {
        styleLabel.setText(STYLE_NAMES[mode]);
        styleLabel.setTextColor(palette[palette.length - 1]);
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

    // ---------------------------------------------------------------- live wallpaper

    private void openWallpaperPicker() {
        try {
            Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    new ComponentName(this, SpectrumWallpaper.class));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
            } catch (Throwable t2) {
                Toast.makeText(this, "Open your phone's wallpaper settings and pick Spectrum Analyser",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---------------------------------------------------------------- now playing

    private boolean hasNotificationAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(getPackageName());
    }

    private void askNotificationAccess() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Show song title")
                .setMessage("To show the song playing in Spotify or YouTube, Android needs you to turn on "
                        + "notification access for Spectrum Analyser on the next screen. The app only reads "
                        + "the song title and artist.")
                .setPositiveButton("Open settings", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                    } catch (Throwable t) {
                        Toast.makeText(this, "Open Settings > Notifications > Notification access", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    private void updateNowPlaying() {
        if (nowPlaying == null) return;
        String text = null;
        if (nowPlayingOn && hasNotificationAccess()) {
            try {
                MediaSessionManager msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
                List<MediaController> sessions = msm.getActiveSessions(new ComponentName(this, NowPlayingListener.class));
                MediaController pick = null;
                for (MediaController mc : sessions) {
                    PlaybackState ps = mc.getPlaybackState();
                    if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) { pick = mc; break; }
                }
                if (pick == null && !sessions.isEmpty()) pick = sessions.get(0);
                if (pick != null && pick.getMetadata() != null) {
                    MediaMetadata md = pick.getMetadata();
                    String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                    String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                    if (TextUtils.isEmpty(artist)) artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                    if (!TextUtils.isEmpty(title)) {
                        text = "♪ " + title + (TextUtils.isEmpty(artist) ? "" : " · " + artist);
                    }
                }
            } catch (Throwable ignored) {
                // access revoked or no sessions: hide the title
            }
        }
        if (text == null) {
            nowPlaying.setVisibility(View.GONE);
        } else {
            if (!text.contentEquals(nowPlaying.getText())) nowPlaying.setText(text);
            nowPlaying.setVisibility(View.VISIBLE);
        }
    }

    // ---------------------------------------------------------------- settings

    private void showSettingsDialog() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(20), dp(4), dp(20), dp(4));

        final AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Settings")
                .setNegativeButton("Close", null)
                .create();

        list.addView(settingRow("Bar fall time", FALL_NAMES[fallIdx], v -> {
            dialog.dismiss();
            pickOne("Bar fall time", FALL_NAMES, fallIdx, i -> {
                fallIdx = i;
                spectrum.setFallSeconds(FALL_VALUES[i]);
                prefs.edit().putInt("fallIdx", i).apply();
            });
        }));
        list.addView(settingRow("Bar / line width (Bars, Line, Waterfall)", WIDTH_NAMES[widthIdx], v -> {
            dialog.dismiss();
            pickOne("Bar / line width", WIDTH_NAMES, widthIdx, i -> {
                widthIdx = i;
                spectrum.setLineWidthDp(WIDTH_VALUES[i]);
                spectrum.setBandCount(BAND_VALUES[i]);
                prefs.edit().putInt("widthIdx", i).apply();
            });
        }));
        list.addView(settingRow("Orientation", ORIENT_NAMES[orientIdx], v -> {
            dialog.dismiss();
            pickOne("Orientation", ORIENT_NAMES, orientIdx, i -> {
                orientIdx = i;
                setRequestedOrientation(ORIENT_VALUES[i]);
                prefs.edit().putInt("orientIdx", i).apply();
            });
        }));

        list.addView(settingRow("Peaks", peaksOn ? "On" : "Off", v -> {
            dialog.dismiss();
            pickOne("Peaks", ON_OFF, peaksOn ? 0 : 1, i -> {
                peaksOn = i == 0;
                spectrum.setPeaksEnabled(peaksOn);
                prefs.edit().putBoolean("peaksOn", peaksOn).apply();
            });
        }));
        list.addView(settingRow("Peak fall speed", PEAK_FALL_NAMES[peakFallIdx], v -> {
            dialog.dismiss();
            pickOne("Peak fall speed", PEAK_FALL_NAMES, peakFallIdx, i -> {
                peakFallIdx = i;
                spectrum.setPeakFallSeconds(PEAK_FALL_VALUES[i]);
                prefs.edit().putInt("peakFallIdx", i).apply();
            });
        }));
        list.addView(settingRow("Bar colouring (Winamp)", COLORING_NAMES[coloringIdx], v -> {
            dialog.dismiss();
            pickOne("Bar colouring", COLORING_NAMES, coloringIdx, i -> {
                coloringIdx = i;
                spectrum.setColoring(i);
                prefs.edit().putInt("coloringIdx", i).apply();
            });
        }));
        list.addView(settingRow("Now playing title", nowPlayingOn ? "On" : "Off", v -> {
            dialog.dismiss();
            pickOne("Now playing title", ON_OFF, nowPlayingOn ? 0 : 1, i -> {
                nowPlayingOn = i == 0;
                prefs.edit().putBoolean("nowPlaying", nowPlayingOn).apply();
                if (nowPlayingOn && !hasNotificationAccess()) askNotificationAccess();
                updateNowPlaying();
            });
        }));
        list.addView(settingRow("Set as live wallpaper", "Uses the style and settings chosen here", v -> {
            dialog.dismiss();
            openWallpaperPicker();
        }));
        final String[] areaNames = {"Bottom half of screen", "Full screen"};
        final int areaIdx = prefs.getInt("wallArea", 0) == 1 ? 1 : 0;
        list.addView(settingRow("Wallpaper area", areaNames[areaIdx], v -> {
            dialog.dismiss();
            pickOne("Wallpaper area", areaNames, areaIdx, i -> prefs.edit().putInt("wallArea", i).apply());
        }));
        final String[] ledNames = {"Style's own colours", "My palette (🎨)"};
        list.addView(settingRow("LED / Winamp / tube colours", ledNames[ledPalette ? 1 : 0], v -> {
            dialog.dismiss();
            pickOne("LED style colours", ledNames, ledPalette ? 1 : 0, i -> {
                ledPalette = i == 1;
                spectrum.setUsePalette(ledPalette);
                prefs.edit().putBoolean("ledPalette", ledPalette).apply();
            });
        }));
        list.addView(settingRow("Auto-cycle styles (random)", AUTO_NAMES[autoIdx], v -> {
            dialog.dismiss();
            pickOne("Auto-cycle styles", AUTO_NAMES, autoIdx, i -> {
                autoIdx = i;
                prefs.edit().putInt("autoIdx", i).apply();
                scheduleAutoCycle();
            });
        }));
        list.addView(settingRow("Auto-gain", autoGain ? "On" : "Off", v -> {
            dialog.dismiss();
            pickOne("Auto-gain", ON_OFF, autoGain ? 0 : 1, i -> {
                autoGain = i == 0;
                spectrum.setAutoGain(autoGain);
                prefs.edit().putBoolean("autoGain", autoGain).apply();
            });
        }));
        list.addView(settingRow("Sensitivity", formatDb(gainDb), v -> {
            dialog.dismiss();
            showSensitivityDialog();
        }));
        list.addView(settingRow("Keep screen on", keepOn ? "On" : "Off", v -> {
            dialog.dismiss();
            pickOne("Keep screen on", ON_OFF, keepOn ? 0 : 1, i -> {
                keepOn = i == 0;
                applyKeepOn();
                prefs.edit().putBoolean("keepOn", keepOn).apply();
            });
        }));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        dialog.setView(scroll);
        dialog.show();
    }

    private static String formatDb(int db) {
        return (db > 0 ? "+" : "") + db + " dB";
    }

    private void showSensitivityDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));
        final TextView value = new TextView(this);
        value.setTextColor(Color.WHITE);
        value.setTextSize(18);
        value.setText(formatDb(gainDb));
        box.addView(value);
        TextView hint = new TextView(this);
        hint.setTextColor(0xFF9E9E9E);
        hint.setText(autoGain ? "Auto-gain is on: this fine-tunes on top of it." : "Raise it if the bars look low.");
        box.addView(hint);
        SeekBar sb = new SeekBar(this);
        sb.setMax(36);                 // -12 .. +24 dB
        sb.setProgress(gainDb + 12);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                gainDb = p - 12;
                value.setText(formatDb(gainDb));
                spectrum.setGainDb(gainDb);       // live preview
                prefs.edit().putInt("gainDb", gainDb).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        box.addView(sb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Sensitivity")
                .setView(box)
                .setPositiveButton("Done", null)
                .show();
    }

    private interface IndexCallback { void onPick(int index); }

    private void pickOne(String title, String[] names, int current, IndexCallback cb) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setSingleChoiceItems(names, current, (d, which) -> {
                    cb.onPick(which);
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private View settingRow(String title, String value, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.setClickable(true);
        row.setOnClickListener(click);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        row.addView(t);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(0xFF9E9E9E);
        v.setTextSize(13);
        row.addView(v);
        return row;
    }

    private static int clampIdx(int i, int len) {
        return (i < 0 || i >= len) ? 0 : i;
    }

    // ---------------------------------------------------------------- helpers

    private Button makeBarButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(2), 0, dp(2), 0);
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
