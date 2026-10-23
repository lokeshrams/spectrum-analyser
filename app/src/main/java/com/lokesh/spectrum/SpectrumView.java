package com.lokesh.spectrum;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;

/** Draws a log-frequency spectrum as bars, a smooth line, or a scrolling waterfall. */
public class SpectrumView extends View {

    public static final int MODE_BARS = 0;
    public static final int MODE_LINE = 1;
    public static final int MODE_WATERFALL = 2;
    public static final int MODE_RAINBOW_LED = 3;
    public static final int MODE_RACK = 4;
    public static final int MODE_PRO_AMBER = 5;
    public static final int MODE_DOT_MATRIX = 6;
    public static final int MODE_CAR_LCD = 7;

    private static final String[] ISO_LABELS = {"20", "25", "31.5", "40", "50", "63", "80", "100", "125", "160",
            "200", "250", "315", "400", "500", "630", "800", "1k", "1.25k", "1.6k", "2k", "2.5k", "3.15k", "4k",
            "5k", "6.3k", "8k", "10k", "12.5k", "16k", "20k"};

    private static final int MAX_BANDS = 128;
    private int bands = 64;       // bars currently drawn
    private int userBands = 64;   // bar count chosen in settings (classic styles); fewer = wider bars
    private float minHz = 40f;
    private float maxHz = 16000f;
    private boolean usePalette = false;   // LED styles: false = their signature colours, true = user's palette
    private int[] colColors = new int[MAX_BANDS];
    private float meter = 0f, meterPeak = 0f;
    private long meterPeakTime = 0;
    private static final float DB_RANGE = 42f;   // magnitude 1..128 -> 0..42 dB
    private static final int WF_ROWS = 240;

    private final float[] target = new float[MAX_BANDS];
    private final float[] disp = new float[MAX_BANDS];
    private final float[] peak = new float[MAX_BANDS];
    private final long[] peakTime = new long[MAX_BANDS];

    private final float[] wfLevels = new float[MAX_BANDS * WF_ROWS];
    private final int[] wfPixels = new int[MAX_BANDS * WF_ROWS];
    private Bitmap wfBitmap = Bitmap.createBitmap(64, WF_ROWS, Bitmap.Config.ARGB_8888);
    private int wfHead = 0;
    private boolean wfDirty = true;

    private int mode = MODE_BARS;
    private int[] stops = {0xFF00E5FF, 0xFF7C4DFF, 0xFFFF00E5};
    private boolean shadersDirty = true;
    private boolean hasData = false;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint();
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();
    private final Rect wfSrc = new Rect(0, 0, 64, WF_ROWS);
    private final Rect wfDst = new Rect();

    private float plotTop, plotBottom, labelH;

    public SpectrumView(Context ctx) {
        super(ctx);
        setBackgroundColor(Color.BLACK);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(3));
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(0xFF8A8A8A);
        textPaint.setTextSize(sp(11));
        gridPaint.setColor(0x22FFFFFF);
        gridPaint.setStrokeWidth(1);
        labelH = sp(20);
        java.util.Arrays.fill(wfPixels, Color.BLACK);
    }

    public void setMode(int m) {
        mode = m;
        applyBandConfig();
        shadersDirty = true;
        invalidate();
    }

    /** LED styles: true = colour them with the user's palette, false = each style's own colours. */
    public void setUsePalette(boolean on) {
        usePalette = on;
        shadersDirty = true;
        invalidate();
    }

    private void applyBandConfig() {
        int n;
        float lo = 40f, hi = 16000f;
        switch (mode) {
            case MODE_RAINBOW_LED: n = 48; break;
            case MODE_RACK: n = 31; lo = 17.82f; hi = 22449f; break;   // 1/3-octave bands centred on ISO 20 Hz .. 20 kHz
            case MODE_PRO_AMBER: n = 30; lo = 25f; hi = 18000f; break;
            case MODE_DOT_MATRIX: n = 20; break;
            case MODE_CAR_LCD: n = 16; break;
            default: n = userBands; break;
        }
        if (n == bands && lo == minHz && hi == maxHz) return;
        bands = n;
        minHz = lo;
        maxHz = hi;
        java.util.Arrays.fill(target, 0f);
        java.util.Arrays.fill(disp, 0f);
        java.util.Arrays.fill(peak, 0f);
        java.util.Arrays.fill(wfLevels, 0f);
        wfHead = 0;
        wfBitmap = Bitmap.createBitmap(bands, WF_ROWS, Bitmap.Config.ARGB_8888);
        wfSrc.set(0, 0, bands, WF_ROWS);
        wfDirty = true;
        shadersDirty = true;
    }

    public void setPalette(int[] colors) {
        stops = colors.clone();
        shadersDirty = true;
        wfDirty = true;
        invalidate();
    }

    /**
     * Feed one spectrum frame. Must be called on the UI thread.
     * @param mags  linear magnitudes per FFT bin (bin k = k * binHz), scaled so a loud tone is ~128
     * @param binHz width of one bin in Hz
     */
    public void submit(float[] mags, float binHz) {
        int n = mags.length;
        if (n < 4 || binHz <= 0) return;
        double ratio = maxHz / minHz;
        wfHead = (wfHead - 1 + WF_ROWS) % WF_ROWS;
        int rowOff = wfHead * bands;
        for (int b = 0; b < bands; b++) {
            double f0 = minHz * Math.pow(ratio, (double) b / bands);
            double f1 = minHz * Math.pow(ratio, (double) (b + 1) / bands);
            int k0 = Math.max(1, (int) Math.floor(f0 / binHz));
            int k1 = Math.min(n - 1, (int) Math.ceil(f1 / binHz));
            if (k0 > n - 2) k0 = n - 2;
            if (k1 <= k0) k1 = k0 + 1;
            float m = 0f;
            for (int k = k0; k < k1; k++) if (mags[k] > m) m = mags[k];
            // dB with a gentle high-frequency tilt (music energy falls with frequency)
            rawDb[b] = (float) (20.0 * Math.log10(m + 1e-6)) + 6f * b / bands;
        }
        float frameMax = 0f;
        for (int b = 0; b < bands; b++) if (rawDb[b] > frameMax) frameMax = rawDb[b];
        float offset = 0f;
        if (autoGain) {
            if (frameMax > autoRef) autoRef += (frameMax - autoRef) * 0.5f;   // fast attack
            else if (frameMax > 8f) autoRef -= 0.15f;                          // slow release, not during silence
            offset = Math.max(-18f, Math.min(24f, AUTO_TARGET - autoRef));
        }
        for (int b = 0; b < bands; b++) {
            float lv = clamp((rawDb[b] + offset + gainDb) / DB_RANGE);
            target[b] = lv;
            wfLevels[rowOff + b] = lv;
        }
        hasData = true;
        wfDirty = true;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        shadersDirty = true;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        plotTop = getPaddingTop() + dp(8);
        plotBottom = h - getPaddingBottom() - labelH;
        float plotH = plotBottom - plotTop;
        if (plotH <= 0 || w <= 0) return;

        animateLevels();
        if (shadersDirty) buildShaders(w);

        boolean peaksOn = fallSeconds > 0f;
        switch (mode) {
            case MODE_LINE: drawLine(c, w, plotH); break;
            case MODE_WATERFALL: drawWaterfall(c, w); break;
            case MODE_RAINBOW_LED: drawRainbowLed(c, w, plotH, peaksOn); break;
            case MODE_RACK: drawRack(c, w, plotH, peaksOn); break;
            case MODE_PRO_AMBER: drawProAmber(c, w, plotH, peaksOn); break;
            case MODE_DOT_MATRIX: drawDotMatrix(c, w, plotH, peaksOn); break;
            case MODE_CAR_LCD: drawCarLcd(c, w, plotH, peaksOn); break;
            default: drawBars(c, w, plotH); break;
        }
        if (mode <= MODE_WATERFALL) drawAxis(c, w);

        if (!hasData) {
            String msg = "Play a song in Spotify or YouTube";
            float tw = textPaint.measureText(msg);
            c.drawText(msg, (w - tw) / 2f, plotTop + plotH / 2f, textPaint);
        }
        postInvalidateOnAnimation();
    }

    // Fall speeds are timed in seconds, so the feel is the same on 60 Hz and 120 Hz screens.
    private static final long PEAK_HOLD_MS = 700;          // caps hang at the top before falling
    private float fallSeconds = 2f;                        // time for a full-height bar to drop; 0 = no fall (instant)
    private long lastFrameMs = 0;

    /** Seconds for a full-height bar to fall to zero. 0 turns the fall off (bars follow the music instantly). */
    public void setFallSeconds(float s) {
        fallSeconds = Math.max(0f, s);
    }

    /** Number of frequency bars (also the line's points and the waterfall's columns). Resets the history. */
    public void setBandCount(int n) {
        userBands = Math.max(8, Math.min(MAX_BANDS, n));
        applyBandConfig();
        invalidate();
    }

    private float gainDb = 0f;
    private boolean autoGain = true;
    private static final float AUTO_TARGET = DB_RANGE * 0.92f;
    private float autoRef = AUTO_TARGET;
    private final float[] rawDb = new float[MAX_BANDS];

    /** Manual sensitivity in dB (added on top of auto-gain when that is on). */
    public void setGainDb(float g) {
        gainDb = g;
    }

    /** Auto-gain keeps loud parts near the top whatever the phone volume or song loudness. */
    public void setAutoGain(boolean on) {
        autoGain = on;
        autoRef = AUTO_TARGET;
    }

    /** Stroke width of the line view, in dp. */
    public void setLineWidthDp(float w) {
        linePaint.setStrokeWidth(dp(w));
        invalidate();
    }

    private void animateLevels() {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrameMs == 0 ? 0.016f : Math.min(0.1f, (now - lastFrameMs) / 1000f);
        lastFrameMs = now;
        boolean instant = fallSeconds <= 0f;
        float barDrop = instant ? 1f : dt / fallSeconds;
        // peak caps drift down at half the bar speed; with the fall off they still use a 2 s drift
        float peakDrop = dt / (instant ? 2f : fallSeconds * 2f);
        // time-based smoothing: audio frames arrive only ~20x per second, so glide between them
        float kUp = (float) (1.0 - Math.exp(-dt / 0.04));
        float kFollow = (float) (1.0 - Math.exp(-dt / 0.045));
        for (int b = 0; b < bands; b++) {
            float t = target[b];
            if (instant) disp[b] += (t - disp[b]) * kFollow;          // fall off: glide both ways
            else if (t > disp[b]) disp[b] += (t - disp[b]) * kUp;
            else disp[b] = Math.max(t, disp[b] - barDrop);
            if (disp[b] >= peak[b]) {
                peak[b] = disp[b];
                peakTime[b] = now;
            } else if (now - peakTime[b] > PEAK_HOLD_MS) {
                peak[b] = Math.max(disp[b], peak[b] - peakDrop);
            }
        }
        // overall loudness for the level meters (Android gives one mixed channel, so L and R match)
        float sum = 0f;
        for (int b = 0; b < bands; b++) sum += disp[b] * disp[b];
        float loud = clamp((float) Math.sqrt(sum / Math.max(1, bands)) * 1.3f);
        meter += (loud - meter) * kUp;
        if (meter >= meterPeak) { meterPeak = meter; meterPeakTime = now; }
        else if (now - meterPeakTime > PEAK_HOLD_MS) meterPeak = Math.max(meter, meterPeak - peakDrop);
    }

    private void buildShaders(int w) {
        for (int b = 0; b < bands; b++) {
            float f = (b + 0.5f) / bands;
            colColors[b] = usePalette ? colorAt(f)
                    : Color.HSVToColor(new float[]{(120f + 340f * f) % 360f, 0.85f, 1f});   // green..blue..red..yellow
        }
        barPaint.setShader(new LinearGradient(0, plotBottom, 0, plotTop, stops, null, Shader.TileMode.CLAMP));
        linePaint.setShader(new LinearGradient(0, 0, w, 0, stops, null, Shader.TileMode.CLAMP));
        int[] faded = new int[stops.length];
        for (int i = 0; i < stops.length; i++) faded[i] = (stops[i] & 0x00FFFFFF) | 0x55000000;
        fillPaint.setShader(new LinearGradient(0, plotTop, 0, plotBottom,
                new int[]{faded[faded.length - 1], faded[0] & 0x11FFFFFF}, null, Shader.TileMode.CLAMP));
        shadersDirty = false;
    }

    private void drawBars(Canvas c, int w, float plotH) {
        float bw = (float) w / bands;
        float gap = Math.max(1f, bw * 0.22f);
        float capH = Math.max(2f, dp(2));
        for (int b = 0; b < bands; b++) {
            float x0 = b * bw + gap / 2f;
            float x1 = (b + 1) * bw - gap / 2f;
            float top = plotBottom - disp[b] * plotH;
            if (plotBottom - top > 1f) c.drawRect(x0, top, x1, plotBottom, barPaint);
            float py = plotBottom - peak[b] * plotH - capH - dp(2);
            if (fallSeconds <= 0f) continue;   // fall off = no peak caps
            peakPaint.setColor(colorAt(peak[b]));
            if (peak[b] > 0.01f) c.drawRect(x0, py, x1, py + capH, peakPaint);
        }
    }

    private void drawLine(Canvas c, int w, float plotH) {
        float bw = (float) w / bands;
        linePath.reset();
        float px = bw / 2f;
        float py = plotBottom - disp[0] * plotH;
        linePath.moveTo(0, py);
        linePath.lineTo(px, py);
        for (int b = 1; b < bands; b++) {
            float x = (b + 0.5f) * bw;
            float y = plotBottom - disp[b] * plotH;
            linePath.quadTo(px, py, (px + x) / 2f, (py + y) / 2f);
            px = x;
            py = y;
        }
        linePath.lineTo(w, py);
        fillPath.set(linePath);
        fillPath.lineTo(w, plotBottom);
        fillPath.lineTo(0, plotBottom);
        fillPath.close();
        c.drawPath(fillPath, fillPaint);
        c.drawPath(linePath, linePaint);
    }

    private void drawWaterfall(Canvas c, int w) {
        if (wfDirty) {
            for (int r = 0; r < WF_ROWS; r++) {
                int src = ((wfHead + r) % WF_ROWS) * bands;
                int dst = r * bands;
                for (int b = 0; b < bands; b++) wfPixels[dst + b] = heatColor(wfLevels[src + b]);
            }
            wfBitmap.setPixels(wfPixels, 0, bands, 0, 0, bands, WF_ROWS);
            wfDirty = false;
        }
        wfDst.set(0, (int) plotTop, w, (int) plotBottom);
        c.drawBitmap(wfBitmap, wfSrc, wfDst, bmpPaint);
    }

    private void drawAxis(Canvas c, int w) {
        double logRange = Math.log(maxHz / minHz);
        int[] marks = {100, 1000, 5000, 10000};
        String[] names = {"100", "1k", "5k", "10k"};
        for (int i = 0; i < marks.length; i++) {
            float x = (float) (Math.log(marks[i] / minHz) / logRange * w);
            if (mode != MODE_WATERFALL) c.drawLine(x, plotTop, x, plotBottom, gridPaint);
            float tw = textPaint.measureText(names[i]);
            c.drawText(names[i], x - tw / 2f, plotBottom + labelH * 0.75f, textPaint);
        }
    }

    // ================================================================ LED styles

    private final Paint segPaint = new Paint();
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ledText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    {
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    private int segIndex(float level, int segs) {
        int i = (int) (level * segs - 0.001f);
        return i < 0 ? -1 : Math.min(segs - 1, i);
    }

    /** Rainbow LED columns over a glowing baseline, with a fading mirror reflection (sample images 1 and 7). */
    private void drawRainbowLed(Canvas c, int w, float plotH, boolean peaksOn) {
        c.drawColor(Color.BLACK);
        int segs = 24;
        float mainH = plotH * 0.74f;
        float base = plotTop + mainH;
        float reflH = plotBottom + labelH - base;
        float colW = (float) w / bands;
        float gx = Math.max(1f, colW * 0.14f);
        float segH = mainH / segs;
        float gy = Math.max(1f, segH * 0.22f);
        for (int b = 0; b < bands; b++) {
            int col = colColors[b];
            int unlit = dim(col, 0.13f);
            float x0 = b * colW + gx / 2f, x1 = (b + 1) * colW - gx / 2f;
            int lit = Math.round(disp[b] * segs);
            int pk = peaksOn ? segIndex(peak[b], segs) : -1;
            for (int sIdx = 0; sIdx < segs; sIdx++) {
                float y1 = base - sIdx * segH - gy / 2f;
                int color;
                if (sIdx < lit) color = (sIdx == lit - 1) ? toWhite(col, 0.2f) : col;
                else if (sIdx == pk) color = toWhite(col, 0.6f);
                else color = unlit;
                segPaint.setColor(color);
                c.drawRect(x0, y1 - segH + gy, x1, y1, segPaint);
            }
            // reflection
            for (int sIdx = 0; sIdx < lit; sIdx++) {
                float ry0 = base + dp(3) + sIdx * segH + gy / 2f;
                if (ry0 > base + reflH) break;
                float a = 0.30f * (1f - (ry0 - base) / reflH);
                if (a <= 0.01f) break;
                segPaint.setColor(withAlpha(col, a));
                c.drawRect(x0, ry0, x1, ry0 + segH - gy, segPaint);
            }
            // glowing baseline
            segPaint.setColor(withAlpha(col, 0.35f));
            c.drawRect(b * colW, base - dp(2), (b + 1) * colW, base + dp(5), segPaint);
            segPaint.setColor(toWhite(col, 0.55f));
            c.drawRect(b * colW, base + dp(0.5f), (b + 1) * colW, base + dp(2.5f), segPaint);
        }
    }

    /** Hardware rack analyzer: 31 thin green LED columns with a label under every band (sample image 4). */
    private void drawRack(Canvas c, int w, float plotH, boolean peaksOn) {
        c.drawColor(0xFF040704);
        int segs = 40;
        float margin = dp(6);
        float colW = (w - 2 * margin) / bands;
        float gx = Math.max(1f, colW * 0.32f);
        float segH = plotH / segs;
        float gy = Math.max(1f, segH * 0.38f);
        int topStart = (int) (segs * 0.85f);
        for (int b = 0; b < bands; b++) {
            float x0 = margin + b * colW + gx / 2f, x1 = margin + (b + 1) * colW - gx / 2f;
            int lit = Math.round(disp[b] * segs);
            int pk = peaksOn ? segIndex(peak[b], segs) : -1;
            for (int sIdx = 0; sIdx < segs; sIdx++) {
                int on = usePalette ? colorAt((float) sIdx / (segs - 1))
                        : (sIdx >= topStart ? 0xFFC8F24A : 0xFF2BD84C);
                boolean isLit = sIdx < lit || sIdx == pk;
                segPaint.setColor(isLit ? on : dim(on, 0.14f));
                float y1 = plotBottom - sIdx * segH - gy / 2f;
                c.drawRect(x0, y1 - segH + gy, x1, y1, segPaint);
            }
        }
        // band labels: thin them out when they would overlap
        ledText.setColor(0xFF9FB89F);
        ledText.setTextSize(sp(8));
        float maxW = ledText.measureText("3.15k") + dp(3);
        int step = Math.max(1, (int) Math.ceil(maxW / colW));
        for (int b = 0; b < bands && b < ISO_LABELS.length; b += step) {
            String t = ISO_LABELS[b];
            float cx = margin + (b + 0.5f) * colW;
            c.drawText(t, cx - ledText.measureText(t) / 2f, plotBottom + labelH * 0.7f, ledText);
        }
    }

    /** Pro amber analyzer: dB scale, segmented amber bars with floating caps, L/R level meters (sample image 5). */
    private void drawProAmber(Canvas c, int w, float plotH, boolean peaksOn) {
        c.drawColor(0xFF161006);
        int segs = 30;
        float scaleW = dp(30);
        float meterW = Math.max(dp(50), w * 0.11f);
        float gap = dp(10);
        float ax0 = scaleW, ax1 = w - meterW - gap;
        float areaW = ax1 - ax0;

        // dB grid and scale (full height = DB_RANGE dB)
        ledText.setColor(0xFFB38A4E);
        ledText.setTextSize(sp(9));
        segPaint.setColor(0xFF2A1E0E);
        for (int d = 0; d <= (int) DB_RANGE; d += 6) {
            float y = plotTop + plotH * d / DB_RANGE;
            c.drawRect(ax0, y, ax1, y + 1, segPaint);
            String t = d == 0 ? "0" : "-" + d;
            c.drawText(t, scaleW - dp(4) - ledText.measureText(t), y + sp(3), ledText);
        }

        float colW = areaW / bands;
        float gx = Math.max(1f, colW * 0.18f);
        float segH = plotH / segs;
        float gy = Math.max(1f, segH * 0.3f);
        for (int b = 0; b < bands; b++) {
            float x0 = ax0 + b * colW + gx / 2f, x1 = ax0 + (b + 1) * colW - gx / 2f;
            int lit = Math.round(disp[b] * segs);
            for (int sIdx = 0; sIdx < segs; sIdx++) {
                int on = usePalette ? colorAt((float) sIdx / (segs - 1))
                        : (sIdx > segs - 5 ? 0xFFFFC978 : 0xFFFFB04A);
                segPaint.setColor(sIdx < lit ? on : dim(on, 0.16f));
                float y1 = plotBottom - sIdx * segH - gy / 2f;
                c.drawRect(x0, y1 - segH + gy, x1, y1, segPaint);
            }
            if (peaksOn && peak[b] > 0.02f) {
                float py = plotBottom - peak[b] * plotH;
                segPaint.setColor(0xFFF4EAD3);
                c.drawRect(x0, py - segH * 0.55f, x1, py, segPaint);
            }
        }

        // octave labels
        String[] names = {"31.5", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"};
        float[] freqs = {31.5f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f};
        double logR = Math.log(maxHz / minHz);
        for (int i = 0; i < names.length; i++) {
            float x = ax0 + (float) (Math.log(freqs[i] / minHz) / logR) * areaW;
            c.drawText(names[i], x - ledText.measureText(names[i]) / 2f, plotBottom + labelH * 0.7f, ledText);
        }

        // L / R level meters
        float mx0 = w - meterW;
        float mw = (meterW - dp(6)) / 2f;
        for (int ch = 0; ch < 2; ch++) {
            float x0 = mx0 + ch * (mw + dp(6)), x1 = x0 + mw;
            int lit = Math.round(meter * segs);
            for (int sIdx = 0; sIdx < segs; sIdx++) {
                int on = usePalette ? colorAt((float) sIdx / (segs - 1)) : 0xFFFFB04A;
                segPaint.setColor(sIdx < lit ? on : dim(on, 0.16f));
                float y1 = plotBottom - sIdx * segH - gy / 2f;
                c.drawRect(x0, y1 - segH + gy, x1, y1, segPaint);
            }
            if (peaksOn && meterPeak > 0.02f) {
                float py = plotBottom - meterPeak * plotH;
                segPaint.setColor(0xFFF4EAD3);
                c.drawRect(x0, py - segH * 0.55f, x1, py, segPaint);
            }
            String t = ch == 0 ? "L" : "R";
            c.drawText(t, (x0 + x1) / 2f - ledText.measureText(t) / 2f, plotBottom + labelH * 0.7f, ledText);
        }
    }

    /** Round glowing LED dots, green -> yellow -> orange -> red (sample image 6). */
    private void drawDotMatrix(Canvas c, int w, float plotH, boolean peaksOn) {
        c.drawColor(0xFF020202);
        int rows = 16;
        float colW = (float) w / bands;
        float rowH = plotH / rows;
        float r = Math.min(colW, rowH) * 0.34f;
        for (int b = 0; b < bands; b++) {
            float cx = (b + 0.5f) * colW;
            int lit = Math.round(disp[b] * rows);
            int pk = peaksOn ? segIndex(peak[b], rows) : -1;
            for (int sIdx = 0; sIdx < rows; sIdx++) {
                int on;
                if (usePalette) on = colorAt((float) sIdx / (rows - 1));
                else if (sIdx < 9) on = 0xFF8BFF3C;
                else if (sIdx < 12) on = 0xFFFFD43B;
                else if (sIdx < 14) on = 0xFFFF8C1A;
                else on = 0xFFFF3B1F;
                float cy = plotBottom - (sIdx + 0.5f) * rowH;
                if (sIdx < lit || sIdx == pk) {
                    dotPaint.setColor(withAlpha(on, 0.22f));
                    c.drawCircle(cx, cy, r * 1.75f, dotPaint);
                    dotPaint.setColor(toWhite(on, 0.15f));
                    c.drawCircle(cx, cy, r, dotPaint);
                } else {
                    dotPaint.setColor(dim(on, 0.12f));
                    c.drawCircle(cx, cy, r * 0.9f, dotPaint);
                }
            }
        }
    }

    /** Car-stereo LCD: chunky green/orange/red blocks, grey peaks, dotted blue frame (sample image 9). */
    private void drawCarLcd(Canvas c, int w, float plotH, boolean peaksOn) {
        c.drawColor(0xFF07080C);
        float inset = dp(14);
        float x0a = inset, x1a = w - dp(8);
        float areaW = x1a - x0a;
        int segs = 12;
        float colW = areaW / bands;
        float gx = Math.max(2f, colW * 0.2f);
        float segH = plotH / segs;
        float gy = Math.max(1f, segH * 0.14f);
        for (int b = 0; b < bands; b++) {
            float x0 = x0a + b * colW + gx / 2f, x1 = x0a + (b + 1) * colW - gx / 2f;
            int lit = Math.round(disp[b] * segs);
            for (int sIdx = 0; sIdx < lit; sIdx++) {
                int on;
                if (usePalette) on = colorAt((float) sIdx / (segs - 1));
                else if (sIdx < 6) on = 0xFF3CCB3C;
                else if (sIdx < 9) on = 0xFFFF9A1F;
                else on = 0xFFE8401C;
                segPaint.setColor(on);
                float y1 = plotBottom - sIdx * segH - gy / 2f;
                c.drawRect(x0, y1 - segH + gy, x1, y1, segPaint);
            }
            int pk = peaksOn ? segIndex(peak[b], segs) : -1;
            if (pk >= lit && pk >= 0) {
                segPaint.setColor(0xFFB8B8B8);
                float y1 = plotBottom - pk * segH - gy / 2f;
                c.drawRect(x0, y1 - segH * 0.45f, x1, y1, segPaint);
            }
        }
        // dotted frame: left edge and baseline
        framePaint.setColor(0xFF3AA0FF);
        framePaint.setStrokeWidth(dp(2.5f));
        framePaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{0.1f, dp(6)}, 0));
        c.drawLine(dp(6), plotTop, dp(6), plotBottom + dp(6), framePaint);
        c.drawLine(dp(6), plotBottom + dp(6), w - dp(8), plotBottom + dp(6), framePaint);
    }

    private static int dim(int col, float f) {
        int r = (int) (((col >> 16) & 0xFF) * f), g = (int) (((col >> 8) & 0xFF) * f), b = (int) ((col & 0xFF) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int toWhite(int col, float f) {
        return lerpColor(col, 0xFFFFFFFF, f);
    }

    private static int withAlpha(int col, float a) {
        int al = Math.max(0, Math.min(255, (int) (a * 255)));
        return (al << 24) | (col & 0x00FFFFFF);
    }

    private int heatColor(float t) {
        int col = colorAt(t);
        float k = clamp(t * 1.4f);
        return lerpColor(Color.BLACK, col, k);
    }

    int colorAt(float t) {
        t = clamp(t);
        float pos = t * (stops.length - 1);
        int i = (int) Math.floor(pos);
        if (i >= stops.length - 1) return stops[stops.length - 1];
        return lerpColor(stops[i], stops[i + 1], pos - i);
    }

    private static int lerpColor(int a, int b, float f) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * f);
        int g = (int) (ag + (bg - ag) * f);
        int bl = (int) (ab + (bb - ab) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }
}
