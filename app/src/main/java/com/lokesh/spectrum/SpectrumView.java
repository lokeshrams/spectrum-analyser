package com.lokesh.spectrum;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;

import java.util.Arrays;

/** Draws the spectrum (or the waveform) in one of many styles. */
public class SpectrumView extends View {

    // ---------------------------------------------------------------- styles
    public static final int MODE_BARS = 0;
    public static final int MODE_LINE = 1;
    public static final int MODE_WATERFALL = 2;
    public static final int MODE_RAINBOW_LED = 3;
    public static final int MODE_RACK = 4;
    public static final int MODE_PRO_AMBER = 5;
    public static final int MODE_DOT_MATRIX = 6;
    public static final int MODE_CAR_LCD = 7;
    public static final int MODE_GLOW_TUBES = 8;
    public static final int MODE_WINAMP_AMBER = 9;
    public static final int MODE_WINAMP_SILVER = 10;
    public static final int MODE_WINAMP_MIRROR = 11;
    public static final int MODE_SCOPE_DOTS = 12;
    public static final int MODE_SCOPE_LINES = 13;
    public static final int MODE_SCOPE_SOLID = 14;

    // Winamp-style bar colouring
    public static final int COLOR_NORMAL = 0;   // gradient fixed to the screen height
    public static final int COLOR_FIRE = 1;     // gradient rides on each bar's tip
    public static final int COLOR_LINE = 2;     // each bar one solid colour chosen by its height

    private static final String[] ISO_LABELS = {"20", "25", "31.5", "40", "50", "63", "80", "100", "125", "160",
            "200", "250", "315", "400", "500", "630", "800", "1k", "1.25k", "1.6k", "2k", "2.5k", "3.15k", "4k",
            "5k", "6.3k", "8k", "10k", "12.5k", "16k", "20k"};

    private static final int[] SKIN_AMBER = {0xFF8B1A00, 0xFFFF7A00, 0xFFFFE040};
    private static final int[] SKIN_SILVER = {0xFF4A4A4A, 0xFFB8B8B8, 0xFFFFFFFF};
    private static final int[] SKIN_MIRROR = {0xFF2EE6D6, 0xFF8FD0FF, 0xFFFF6FD8};
    private static final int[] TUBE_ORANGE = {0xFFB34700, 0xFFFF9A1F, 0xFFFFD08A};

    // ---------------------------------------------------------------- analysis state
    private static final int MAX_BANDS = 128;
    private static final float DB_RANGE = 42f;   // magnitude 1..128 -> 0..42 dB
    private static final int WF_ROWS = 240;
    private static final long PEAK_HOLD_MS = 700;
    private static final float AUTO_TARGET = DB_RANGE * 0.92f;

    private int bands = 64;
    private int userBands = 64;
    private float minHz = 40f;
    private float maxHz = 16000f;

    private final float[] target = new float[MAX_BANDS];
    private final float[] disp = new float[MAX_BANDS];
    private final float[] peak = new float[MAX_BANDS];
    private final long[] peakTime = new long[MAX_BANDS];
    private final float[] rawDb = new float[MAX_BANDS];

    // level meters: 0 = overall, 1 = bass half, 2 = treble half
    private final float[] meter = new float[3];
    private final float[] meterPeak = new float[3];
    private final long[] meterPeakTime = new long[3];

    private final float[] wfLevels = new float[MAX_BANDS * WF_ROWS];
    private final int[] wfPixels = new int[MAX_BANDS * WF_ROWS];
    private Bitmap wfBitmap = Bitmap.createBitmap(64, WF_ROWS, Bitmap.Config.ARGB_8888);
    private final Rect wfSrc = new Rect(0, 0, 64, WF_ROWS);
    private final Rect wfDst = new Rect();
    private int wfHead = 0;
    private boolean wfDirty = true;

    private static final int WAVE_N = 512;
    private final float[] wave = new float[WAVE_N];
    private boolean hasWave = false;
    private float scopeRef = 0.3f;

    // ---------------------------------------------------------------- settings
    private int mode = MODE_BARS;
    private int[] stops = {0xFF00E5FF, 0xFF7C4DFF, 0xFFFF00E5};
    private boolean usePalette = false;
    private int coloring = COLOR_NORMAL;
    private boolean peaksEnabled = true;
    private float peakFallSeconds = 2.5f;
    private float fallSeconds = 2f;
    private float gainDb = 0f;
    private boolean autoGain = true;
    private float autoRef = AUTO_TARGET;

    private boolean shadersDirty = true;
    private boolean hasData = false;
    private long lastFrameMs = 0;
    private float plotTop, plotBottom, labelH;
    private float builtTop = -1, builtBottom = -1;

    // ---------------------------------------------------------------- paints
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint solidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scopePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scopeFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint();
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint segPaint = new Paint();
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ledText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();
    private final Matrix shaderMatrix = new Matrix();
    private final RectF rectF = new RectF();
    private final int[] colColors = new int[MAX_BANDS];

    private LinearGradient barShader, amberShader, silverShader, mirrorUpShader, mirrorDownShader;

    public SpectrumView(Context ctx) {
        super(ctx);
        setBackgroundColor(Color.BLACK);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(4));
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        scopePaint.setStyle(Paint.Style.STROKE);
        scopePaint.setStrokeJoin(Paint.Join.ROUND);
        scopePaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
        scopeFill.setStyle(Paint.Style.FILL);
        textPaint.setColor(0xFF8A8A8A);
        textPaint.setTextSize(sp(11));
        gridPaint.setColor(0x22FFFFFF);
        gridPaint.setStrokeWidth(1);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeCap(Paint.Cap.ROUND);
        framePaint.setColor(0xFF3AA0FF);
        framePaint.setStrokeWidth(dp(2.5f));
        framePaint.setPathEffect(new DashPathEffect(new float[]{0.1f, dp(6)}, 0));
        glassPaint.setStyle(Paint.Style.STROKE);
        glassPaint.setStrokeWidth(dp(1.5f));
        labelH = sp(20);
        Arrays.fill(wfPixels, Color.BLACK);
    }

    // ================================================================ public setters

    public void setMode(int m) {
        mode = m;
        applyBandConfig();
        shadersDirty = true;
        invalidate();
    }

    public void setPalette(int[] colors) {
        stops = colors.clone();
        shadersDirty = true;
        wfDirty = true;
        invalidate();
    }

    /** Styles with their own look: true = colour them with the user's palette instead. */
    public void setUsePalette(boolean on) {
        usePalette = on;
        shadersDirty = true;
        invalidate();
    }

    public void setColoring(int c) {
        coloring = c;
    }

    public void setPeaksEnabled(boolean on) {
        peaksEnabled = on;
    }

    /** Seconds for a peak cap to fall the full height after its hold. */
    public void setPeakFallSeconds(float s) {
        peakFallSeconds = Math.max(0.2f, s);
    }

    /** Seconds for a full-height bar to fall to zero. 0 turns the fall off (and hides peaks). */
    public void setFallSeconds(float s) {
        fallSeconds = Math.max(0f, s);
    }

    /** Bar count for the classic styles. Fewer bars = wider bars. */
    public void setBandCount(int n) {
        userBands = Math.max(8, Math.min(MAX_BANDS, n));
        applyBandConfig();
        invalidate();
    }

    public void setGainDb(float g) {
        gainDb = g;
    }

    public void setAutoGain(boolean on) {
        autoGain = on;
        autoRef = AUTO_TARGET;
    }

    public void setLineWidthDp(float w) {
        linePaint.setStrokeWidth(dp(w));
        scopePaint.setStrokeWidth(dp(Math.max(1.5f, w * 0.6f)));
        invalidate();
    }

    private void applyBandConfig() {
        int n;
        float lo = 40f, hi = 16000f;
        switch (mode) {
            case MODE_RAINBOW_LED: n = 48; break;
            case MODE_RACK: n = 31; lo = 17.82f; hi = 22449f; break;   // 1/3-octave bands on ISO centres
            case MODE_PRO_AMBER: n = 30; lo = 25f; hi = 18000f; break;
            case MODE_DOT_MATRIX: n = 20; break;
            case MODE_CAR_LCD: n = 16; break;
            case MODE_GLOW_TUBES: n = 32; break;
            case MODE_WINAMP_AMBER:
            case MODE_WINAMP_SILVER: n = 76; break;
            case MODE_WINAMP_MIRROR: n = 60; break;
            default: n = userBands; break;
        }
        if (n == bands && lo == minHz && hi == maxHz) return;
        bands = n;
        minHz = lo;
        maxHz = hi;
        Arrays.fill(target, 0f);
        Arrays.fill(disp, 0f);
        Arrays.fill(peak, 0f);
        Arrays.fill(wfLevels, 0f);
        wfHead = 0;
        wfBitmap = Bitmap.createBitmap(bands, WF_ROWS, Bitmap.Config.ARGB_8888);
        wfSrc.set(0, 0, bands, WF_ROWS);
        wfDirty = true;
        shadersDirty = true;
    }

    private boolean isScope() {
        return mode >= MODE_SCOPE_DOTS;
    }

    // ================================================================ data input (UI thread)

    /**
     * One spectrum frame.
     * @param mags  linear magnitudes per FFT bin (bin k = k * binHz), scaled so a loud tone is ~128
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
            rawDb[b] = (float) (20.0 * Math.log10(m + 1e-6)) + 6f * b / bands;   // gentle treble tilt
        }
        float frameMax = 0f;
        for (int b = 0; b < bands; b++) if (rawDb[b] > frameMax) frameMax = rawDb[b];
        float offset = 0f;
        if (autoGain) {
            if (frameMax > autoRef) autoRef += (frameMax - autoRef) * 0.5f;
            else if (frameMax > 8f) autoRef -= 0.15f;
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

    /** One waveform frame, samples in -1..1. */
    public void submitWave(float[] samples, int count) {
        if (count < 2) return;
        float max = 0f;
        for (int i = 0; i < WAVE_N; i++) {
            float v = samples[(int) ((long) i * (count - 1) / (WAVE_N - 1))];
            wave[i] = v;
            float a = Math.abs(v);
            if (a > max) max = a;
        }
        if (max > scopeRef) scopeRef += (max - scopeRef) * 0.5f;
        else if (max > 0.01f) scopeRef = Math.max(0.05f, scopeRef - 0.004f);
        hasWave = true;
        hasData = true;
    }

    // ================================================================ frame

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
        if (shadersDirty || plotTop != builtTop || plotBottom != builtBottom) buildShaders(w);

        boolean peaksOn = peaksEnabled && fallSeconds > 0f;
        switch (mode) {
            case MODE_LINE: drawLine(c, w, plotH); break;
            case MODE_WATERFALL: drawWaterfall(c, w); break;
            case MODE_RAINBOW_LED: drawRainbowLed(c, w, plotH, peaksOn); break;
            case MODE_RACK: drawRack(c, w, plotH, peaksOn); break;
            case MODE_PRO_AMBER: drawProAmber(c, w, plotH, peaksOn); break;
            case MODE_DOT_MATRIX: drawDotMatrix(c, w, plotH); break;
            case MODE_CAR_LCD: drawCarLcd(c, w, plotH, peaksOn); break;
            case MODE_GLOW_TUBES: drawGlowTubes(c, w, plotH, peaksOn); break;
            case MODE_WINAMP_AMBER:
                c.drawColor(Color.BLACK);
                drawBarSet(c, 0, w, plotH, usePalette ? stops : SKIN_AMBER, amberShader, 0.2f, peaksOn,
                        usePalette ? 0 : 0xFFFFF2B0, dp(2));
                break;
            case MODE_WINAMP_SILVER:
                c.drawColor(Color.BLACK);
                drawBarSet(c, 0, w, plotH, usePalette ? stops : SKIN_SILVER, silverShader, 0.2f, peaksOn,
                        usePalette ? 0 : 0xFFFFFFFF, dp(2));
                break;
            case MODE_WINAMP_MIRROR: drawMirror(c, w, plotH, peaksOn); break;
            case MODE_SCOPE_DOTS:
            case MODE_SCOPE_LINES:
            case MODE_SCOPE_SOLID: drawScope(c, w, plotH); break;
            default:
                drawBarSet(c, 0, w, plotH, stops, barShader, 0.22f, peaksOn, 0, Math.max(2f, dp(2)));
                break;
        }
        if (mode <= MODE_WATERFALL) drawAxis(c, w);

        if (!hasData || (isScope() && !hasWave)) {
            String msg = "Play a song in Spotify or YouTube";
            float tw = textPaint.measureText(msg);
            c.drawText(msg, (w - tw) / 2f, plotTop + plotH / 2f, textPaint);
        }
        postInvalidateOnAnimation();
    }

    private void animateLevels() {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrameMs == 0 ? 0.016f : Math.min(0.1f, (now - lastFrameMs) / 1000f);
        lastFrameMs = now;
        boolean instant = fallSeconds <= 0f;
        float barDrop = instant ? 1f : dt / fallSeconds;
        float peakDrop = dt / peakFallSeconds;
        // audio frames arrive only ~20x per second, so glide between them
        float kUp = (float) (1.0 - Math.exp(-dt / 0.04));
        float kFollow = (float) (1.0 - Math.exp(-dt / 0.045));
        for (int b = 0; b < bands; b++) {
            float t = target[b];
            if (instant) disp[b] += (t - disp[b]) * kFollow;
            else if (t > disp[b]) disp[b] += (t - disp[b]) * kUp;
            else disp[b] = Math.max(t, disp[b] - barDrop);
            if (disp[b] >= peak[b]) {
                peak[b] = disp[b];
                peakTime[b] = now;
            } else if (now - peakTime[b] > PEAK_HOLD_MS) {
                peak[b] = Math.max(disp[b], peak[b] - peakDrop);
            }
        }
        // loudness meters: overall, bass half, treble half
        int half = Math.max(1, bands / 2);
        float sAll = 0f, sLo = 0f, sHi = 0f;
        for (int b = 0; b < bands; b++) {
            float v = disp[b] * disp[b];
            sAll += v;
            if (b < half) sLo += v; else sHi += v;
        }
        float[] loud = {
                (float) Math.sqrt(sAll / Math.max(1, bands)) * 1.3f,
                (float) Math.sqrt(sLo / half) * 1.25f,
                (float) Math.sqrt(sHi / Math.max(1, bands - half)) * 1.45f};
        for (int i = 0; i < 3; i++) {
            float l = clamp(loud[i]);
            if (instant || l > meter[i]) meter[i] += (l - meter[i]) * kUp;
            else meter[i] = Math.max(l, meter[i] - barDrop);
            if (meter[i] >= meterPeak[i]) { meterPeak[i] = meter[i]; meterPeakTime[i] = now; }
            else if (now - meterPeakTime[i] > PEAK_HOLD_MS) meterPeak[i] = Math.max(meter[i], meterPeak[i] - peakDrop);
        }
    }

    private void buildShaders(int w) {
        for (int b = 0; b < bands; b++) {
            float f = (b + 0.5f) / bands;
            colColors[b] = usePalette ? colorAt(f, stops)
                    : Color.HSVToColor(new float[]{(120f + 340f * f) % 360f, 0.85f, 1f});
        }
        barShader = new LinearGradient(0, plotBottom, 0, plotTop, stops, null, Shader.TileMode.CLAMP);
        amberShader = new LinearGradient(0, plotBottom, 0, plotTop, usePalette ? stops : SKIN_AMBER, null, Shader.TileMode.CLAMP);
        silverShader = new LinearGradient(0, plotBottom, 0, plotTop, usePalette ? stops : SKIN_SILVER, null, Shader.TileMode.CLAMP);
        float mid = (plotTop + plotBottom) / 2f;
        int[] ms = usePalette ? stops : SKIN_MIRROR;
        mirrorUpShader = new LinearGradient(0, mid, 0, plotTop, ms, null, Shader.TileMode.CLAMP);
        mirrorDownShader = new LinearGradient(0, mid, 0, plotBottom, ms, null, Shader.TileMode.CLAMP);
        linePaint.setShader(new LinearGradient(0, 0, w, 0, stops, null, Shader.TileMode.CLAMP));
        int[] faded = new int[stops.length];
        for (int i = 0; i < stops.length; i++) faded[i] = (stops[i] & 0x00FFFFFF) | 0x55000000;
        fillPaint.setShader(new LinearGradient(0, plotTop, 0, plotBottom,
                new int[]{faded[faded.length - 1], faded[0] & 0x11FFFFFF}, null, Shader.TileMode.CLAMP));
        // scope: centre = first colour, extremes = last colour
        int n = stops.length;
        int[] sym = new int[n * 2 - 1];
        for (int i = 0; i < n; i++) {
            sym[i] = stops[n - 1 - i];
            sym[n - 1 + i] = stops[i];
        }
        LinearGradient scope = new LinearGradient(0, plotTop, 0, plotBottom, sym, null, Shader.TileMode.CLAMP);
        scopePaint.setShader(scope);
        scopeFill.setShader(scope);
        builtTop = plotTop;
        builtBottom = plotBottom;
        shadersDirty = false;
    }

    // ================================================================ classic styles

    /** Bars with Winamp colouring (Normal / Fire / Line) and optional peak caps. */
    private void drawBarSet(Canvas c, float x0a, float areaW, float plotH, int[] st, LinearGradient shader,
                            float gapFrac, boolean peaksOn, int peakColor, float capH) {
        float bw = areaW / bands;
        float gap = Math.max(1f, bw * gapFrac);
        barPaint.setShader(shader);
        for (int b = 0; b < bands; b++) {
            float x0 = x0a + b * bw + gap / 2f;
            float x1 = x0a + (b + 1) * bw - gap / 2f;
            float top = plotBottom - disp[b] * plotH;
            if (plotBottom - top > 1f) {
                if (coloring == COLOR_LINE) {
                    solidPaint.setColor(colorAt(disp[b], st));
                    c.drawRect(x0, top, x1, plotBottom, solidPaint);
                } else {
                    if (coloring == COLOR_FIRE) shaderMatrix.setTranslate(0, top - plotTop);
                    else shaderMatrix.reset();
                    shader.setLocalMatrix(shaderMatrix);
                    c.drawRect(x0, top, x1, plotBottom, barPaint);
                }
            }
            if (peaksOn && peak[b] > 0.01f) {
                float py = plotBottom - peak[b] * plotH - capH - dp(2);
                solidPaint.setColor(peakColor != 0 ? peakColor : colorAt(peak[b], st));
                c.drawRect(x0, py, x1, py + capH, solidPaint);
            }
        }
        shaderMatrix.reset();
        shader.setLocalMatrix(shaderMatrix);
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

    /** Colour position of a segment under the chosen Winamp colouring. */
    private float segFrac(int sIdx, int lit, int segs) {
        float d = Math.max(1, segs - 1);
        if (sIdx >= lit) return sIdx / d;                       // unlit / peak: its own position
        switch (coloring) {
            case COLOR_FIRE: return clamp((sIdx + segs - lit) / d);
            case COLOR_LINE: return clamp((lit - 1) / d);
            default: return sIdx / d;
        }
    }

    /** Signature colour of each LED style at height fraction f (or the palette). */
    private int ledColor(float f) {
        if (usePalette) return colorAt(f, stops);
        switch (mode) {
            case MODE_RACK: return f >= 0.85f ? 0xFFC8F24A : 0xFF2BD84C;
            case MODE_PRO_AMBER: return f > 0.86f ? 0xFFFFC978 : 0xFFFFB04A;
            case MODE_DOT_MATRIX:
                if (f < 0.56f) return 0xFF8BFF3C;
                if (f < 0.75f) return 0xFFFFD43B;
                if (f < 0.87f) return 0xFFFF8C1A;
                return 0xFFFF3B1F;
            case MODE_CAR_LCD:
                if (f < 0.5f) return 0xFF3CCB3C;
                if (f < 0.75f) return 0xFFFF9A1F;
                return 0xFFE8401C;
            case MODE_GLOW_TUBES: return colorAt(f, TUBE_ORANGE);
            default: return colorAt(f, stops);
        }
    }

    private int segIndex(float level, int segs) {
        int i = (int) (level * segs - 0.001f);
        return i < 0 ? -1 : Math.min(segs - 1, i);
    }

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
            for (int sIdx = 0; sIdx < lit; sIdx++) {
                float ry0 = base + dp(3) + sIdx * segH + gy / 2f;
                if (ry0 > base + reflH) break;
                float a = 0.30f * (1f - (ry0 - base) / reflH);
                if (a <= 0.01f) break;
                segPaint.setColor(withAlpha(col, a));
                c.drawRect(x0, ry0, x1, ry0 + segH - gy, segPaint);
            }
            segPaint.setColor(withAlpha(col, 0.35f));
            c.drawRect(b * colW, base - dp(2), (b + 1) * colW, base + dp(5), segPaint);
            segPaint.setColor(toWhite(col, 0.55f));
            c.drawRect(b * colW, base + dp(0.5f), (b + 1) * colW, base + dp(2.5f), segPaint);
        }
    }

    /** Draws one segmented column; used by the rack, amber, car and tube styles. */
    private void drawSegColumn(Canvas c, float x0, float x1, float bottom, float h, int segs, float gyFrac,
                               float level, int pk, float unlitDim) {
        float segH = h / segs;
        float gy = Math.max(1f, segH * gyFrac);
        int lit = Math.round(level * segs);
        for (int sIdx = 0; sIdx < segs; sIdx++) {
            boolean on = sIdx < lit || sIdx == pk;
            if (!on && unlitDim <= 0f) continue;
            int col = ledColor(segFrac(sIdx, lit, segs));
            segPaint.setColor(on ? col : dim(col, unlitDim));
            float y1 = bottom - sIdx * segH - gy / 2f;
            c.drawRect(x0, y1 - segH + gy, x1, y1, segPaint);
        }
    }

    private void drawRack(Canvas c, int w, float plotH, boolean peaksOn) {
        c.drawColor(0xFF040704);
        int segs = 40;
        float margin = dp(6);
        float colW = (w - 2 * margin) / bands;
        float gx = Math.max(1f, colW * 0.32f);
        for (int b = 0; b < bands; b++) {
            float x0 = margin + b * colW + gx / 2f, x1 = margin + (b + 1) * colW - gx / 2f;
            drawSegColumn(c, x0, x1, plotBottom, plotH, segs, 0.38f, disp[b],
                    peaksOn ? segIndex(peak[b], segs) : -1, 0.14f);
        }
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

    private void drawProAmber(Canvas c, int w, float plotH, boolean peaksOn) {
        c.drawColor(0xFF161006);
        int segs = 30;
        float scaleW = dp(30);
        float meterW = Math.max(dp(50), w * 0.11f);
        float gap = dp(10);
        float ax0 = scaleW, ax1 = w - meterW - gap;
        float areaW = ax1 - ax0;

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
        for (int b = 0; b < bands; b++) {
            float x0 = ax0 + b * colW + gx / 2f, x1 = ax0 + (b + 1) * colW - gx / 2f;
            drawSegColumn(c, x0, x1, plotBottom, plotH, segs, 0.3f, disp[b], -1, 0.16f);
            if (peaksOn && peak[b] > 0.02f) {
                float py = plotBottom - peak[b] * plotH;
                segPaint.setColor(0xFFF4EAD3);
                c.drawRect(x0, py - segH * 0.55f, x1, py, segPaint);
            }
        }

        String[] names = {"31.5", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"};
        float[] freqs = {31.5f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f};
        double logR = Math.log(maxHz / minHz);
        for (int i = 0; i < names.length; i++) {
            float x = ax0 + (float) (Math.log(freqs[i] / minHz) / logR) * areaW;
            c.drawText(names[i], x - ledText.measureText(names[i]) / 2f, plotBottom + labelH * 0.7f, ledText);
        }

        float mx0 = w - meterW;
        float mw = (meterW - dp(6)) / 2f;
        for (int ch = 0; ch < 2; ch++) {
            float x0 = mx0 + ch * (mw + dp(6)), x1 = x0 + mw;
            drawSegColumn(c, x0, x1, plotBottom, plotH, segs, 0.3f, meter[0], -1, 0.16f);
            if (peaksOn && meterPeak[0] > 0.02f) {
                float py = plotBottom - meterPeak[0] * plotH;
                segPaint.setColor(0xFFF4EAD3);
                c.drawRect(x0, py - segH * 0.55f, x1, py, segPaint);
            }
            String t = ch == 0 ? "L" : "R";
            c.drawText(t, (x0 + x1) / 2f - ledText.measureText(t) / 2f, plotBottom + labelH * 0.7f, ledText);
        }
    }

    /** Glowing dots; no floating peak dot (it lagged awkwardly behind the dots). */
    private void drawDotMatrix(Canvas c, int w, float plotH) {
        c.drawColor(0xFF020202);
        int rows = 16;
        float colW = (float) w / bands;
        float rowH = plotH / rows;
        float r = Math.min(colW, rowH) * 0.34f;
        for (int b = 0; b < bands; b++) {
            float cx = (b + 0.5f) * colW;
            int lit = Math.round(disp[b] * rows);
            for (int sIdx = 0; sIdx < rows; sIdx++) {
                float cy = plotBottom - (sIdx + 0.5f) * rowH;
                int on = ledColor(segFrac(sIdx, lit, rows));
                if (sIdx < lit) {
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

    private void drawCarLcd(Canvas c, int w, float plotH, boolean peaksOn) {
        c.drawColor(0xFF07080C);
        float x0a = dp(14), x1a = w - dp(8);
        float areaW = x1a - x0a;
        int segs = 12;
        float colW = areaW / bands;
        float gx = Math.max(2f, colW * 0.2f);
        float segH = plotH / segs;
        float gy = Math.max(1f, segH * 0.14f);
        for (int b = 0; b < bands; b++) {
            float x0 = x0a + b * colW + gx / 2f, x1 = x0a + (b + 1) * colW - gx / 2f;
            drawSegColumn(c, x0, x1, plotBottom, plotH, segs, 0.14f, disp[b], -1, 0f);
            int lit = Math.round(disp[b] * segs);
            int pk = peaksOn ? segIndex(peak[b], segs) : -1;
            if (pk >= lit && pk >= 0) {
                segPaint.setColor(0xFFB8B8B8);
                float y1 = plotBottom - pk * segH - gy / 2f;
                c.drawRect(x0, y1 - segH * 0.45f, x1, y1, segPaint);
            }
        }
        c.drawLine(dp(6), plotTop, dp(6), plotBottom + dp(6), framePaint);
        c.drawLine(dp(6), plotBottom + dp(6), w - dp(8), plotBottom + dp(6), framePaint);
    }

    /** Two glowing glass tubes: bass (left) and treble (right) loudness (sample image 3). */
    private void drawGlowTubes(Canvas c, int w, float plotH, boolean peaksOn) {
        c.drawColor(0xFF0B0A09);
        float tubeW = Math.min(w * 0.16f, dp(64));
        float gap = tubeW * 1.4f;
        float left = (w - (2 * tubeW + gap)) / 2f;
        float baseH = dp(14);
        float top = plotTop + tubeW * 0.5f;
        float bottom = plotBottom + labelH * 0.2f - baseH;
        float innerH = bottom - top - tubeW * 0.3f;
        int segs = 40;
        ledText.setColor(0xFF8A7A66);
        ledText.setTextSize(sp(11));
        for (int t = 0; t < 2; t++) {
            int idx = t == 0 ? 1 : 2;
            float x0 = left + t * (tubeW + gap), x1 = x0 + tubeW;
            float cx = (x0 + x1) / 2f;
            float lvl = meter[idx];
            int glow = ledColor(0.6f);
            // glow inside the tube up to the level
            float gTop = bottom - lvl * innerH;
            if (lvl > 0.01f) {
                solidPaint.setShader(new LinearGradient(x0, 0, x1, 0,
                        new int[]{withAlpha(glow, 0f), withAlpha(glow, 0.55f), withAlpha(glow, 0f)},
                        null, Shader.TileMode.CLAMP));
                c.drawRect(x0 + dp(2), gTop, x1 - dp(2), bottom, solidPaint);
                solidPaint.setShader(null);
            }
            // filament dashes
            float dashW = tubeW * 0.16f;
            drawSegColumn(c, cx - dashW / 2f, cx + dashW / 2f, bottom, innerH, segs, 0.45f, lvl,
                    peaksOn ? segIndex(meterPeak[idx], segs) : -1, 0.22f);
            // glass with a rounded top
            rectF.set(x0, plotTop, x1, bottom);
            glassPaint.setColor(0x66D8D8D8);
            glassPaint.setStyle(Paint.Style.STROKE);
            c.drawRoundRect(rectF, tubeW / 2f, tubeW / 2f, glassPaint);
            glassPaint.setStyle(Paint.Style.FILL);
            glassPaint.setColor(0x12FFFFFF);
            c.drawRoundRect(rectF, tubeW / 2f, tubeW / 2f, glassPaint);
            // highlight streak
            glassPaint.setColor(0x22FFFFFF);
            c.drawRect(x0 + tubeW * 0.18f, plotTop + tubeW * 0.6f, x0 + tubeW * 0.26f, bottom - dp(8), glassPaint);
            // metal base
            segPaint.setColor(0xFF3A332C);
            c.drawRect(x0 - dp(4), bottom, x1 + dp(4), bottom + baseH, segPaint);
            String label = t == 0 ? "Bass" : "Treble";
            c.drawText(label, cx - ledText.measureText(label) / 2f, bottom + baseH + sp(13), ledText);
        }
    }

    /** Winamp-style mirrored bars from a centre line (sample image 2, bottom). */
    private void drawMirror(Canvas c, int w, float plotH, boolean peaksOn) {
        c.drawColor(0xFF221A3D);
        int[] st = usePalette ? stops : SKIN_MIRROR;
        float mid = (plotTop + plotBottom) / 2f;
        float halfH = plotH / 2f;
        float bw = (float) w / bands;
        float gap = Math.max(1f, bw * 0.2f);
        for (int b = 0; b < bands; b++) {
            float x0 = b * bw + gap / 2f, x1 = (b + 1) * bw - gap / 2f;
            float h = disp[b] * halfH;
            if (h > 0.5f) {
                if (coloring == COLOR_LINE) {
                    solidPaint.setColor(colorAt(disp[b], st));
                    c.drawRect(x0, mid - h, x1, mid + h, solidPaint);
                } else {
                    float sc = coloring == COLOR_FIRE ? Math.max(0.02f, disp[b]) : 1f;
                    shaderMatrix.setScale(1f, sc, 0, mid);
                    mirrorUpShader.setLocalMatrix(shaderMatrix);
                    mirrorDownShader.setLocalMatrix(shaderMatrix);
                    barPaint.setShader(mirrorUpShader);
                    c.drawRect(x0, mid - h, x1, mid, barPaint);
                    barPaint.setShader(mirrorDownShader);
                    c.drawRect(x0, mid, x1, mid + h, barPaint);
                }
            }
            if (peaksOn && peak[b] > 0.01f) {
                float ph = peak[b] * halfH;
                solidPaint.setColor(toWhite(colorAt(peak[b], st), 0.5f));
                c.drawRect(x0, mid - ph - dp(3), x1, mid - ph - dp(1.5f), solidPaint);
                c.drawRect(x0, mid + ph + dp(1.5f), x1, mid + ph + dp(3), solidPaint);
            }
        }
        shaderMatrix.reset();
        mirrorUpShader.setLocalMatrix(shaderMatrix);
        mirrorDownShader.setLocalMatrix(shaderMatrix);
        segPaint.setColor(0x55FFFFFF);
        c.drawRect(0, mid - 0.5f, w, mid + 0.5f, segPaint);
    }

    /** Winamp oscilloscope: Dots, Lines or Solid. */
    private void drawScope(Canvas c, int w, float plotH) {
        c.drawColor(Color.BLACK);
        if (!hasWave) return;
        float mid = (plotTop + plotBottom) / 2f;
        float halfH = plotH / 2f;
        float scale = (autoGain ? 0.9f / scopeRef : 1f) * (float) Math.pow(10, gainDb / 20.0);
        float dx = (float) w / (WAVE_N - 1);
        segPaint.setColor(0x22FFFFFF);
        c.drawRect(0, mid - 0.5f, w, mid + 0.5f, segPaint);
        if (mode == MODE_SCOPE_DOTS) {
            float r = Math.max(dp(1.2f), scopePaint.getStrokeWidth() * 0.6f);
            int step = Math.max(1, (int) (WAVE_N / (w / (r * 2.2f))));
            for (int i = 0; i < WAVE_N; i += step) {
                float v = Math.max(-1f, Math.min(1f, wave[i] * scale));
                dotPaint.setColor(colorAt(Math.abs(v), stops));
                c.drawCircle(i * dx, mid - v * halfH, r, dotPaint);
            }
            return;
        }
        linePath.reset();
        for (int i = 0; i < WAVE_N; i++) {
            float v = Math.max(-1f, Math.min(1f, wave[i] * scale));
            float x = i * dx, y = mid - v * halfH;
            if (i == 0) linePath.moveTo(x, y); else linePath.lineTo(x, y);
        }
        if (mode == MODE_SCOPE_SOLID) {
            fillPath.set(linePath);
            fillPath.lineTo(w, mid);
            fillPath.lineTo(0, mid);
            fillPath.close();
            c.drawPath(fillPath, scopeFill);
        } else {
            c.drawPath(linePath, scopePaint);
        }
    }

    // ================================================================ colour helpers

    private int heatColor(float t) {
        int col = colorAt(t, stops);
        return lerpColor(Color.BLACK, col, clamp(t * 1.4f));
    }

    int colorAt(float t) {
        return colorAt(t, stops);
    }

    private static int colorAt(float t, int[] st) {
        t = clamp(t);
        float pos = t * (st.length - 1);
        int i = (int) Math.floor(pos);
        if (i >= st.length - 1) return st[st.length - 1];
        return lerpColor(st[i], st[i + 1], pos - i);
    }

    private static int lerpColor(int a, int b, float f) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * f);
        int g = (int) (ag + (bg - ag) * f);
        int bl = (int) (ab + (bb - ab) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
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
