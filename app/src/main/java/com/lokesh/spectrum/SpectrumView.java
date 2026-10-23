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

    private static final int BANDS = 72;
    private static final float MIN_HZ = 40f;
    private static final float MAX_HZ = 16000f;
    private static final float DB_RANGE = 42f;   // magnitude 1..128 -> 0..42 dB
    private static final int WF_ROWS = 240;

    private final float[] target = new float[BANDS];
    private final float[] disp = new float[BANDS];
    private final float[] peak = new float[BANDS];
    private final long[] peakTime = new long[BANDS];

    private final float[] wfLevels = new float[BANDS * WF_ROWS];
    private final int[] wfPixels = new int[BANDS * WF_ROWS];
    private final Bitmap wfBitmap = Bitmap.createBitmap(BANDS, WF_ROWS, Bitmap.Config.ARGB_8888);
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
    private final Rect wfSrc = new Rect(0, 0, BANDS, WF_ROWS);
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
        invalidate();
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
        double ratio = MAX_HZ / MIN_HZ;
        wfHead = (wfHead - 1 + WF_ROWS) % WF_ROWS;
        int rowOff = wfHead * BANDS;
        for (int b = 0; b < BANDS; b++) {
            double f0 = MIN_HZ * Math.pow(ratio, (double) b / BANDS);
            double f1 = MIN_HZ * Math.pow(ratio, (double) (b + 1) / BANDS);
            int k0 = Math.max(1, (int) Math.floor(f0 / binHz));
            int k1 = Math.min(n - 1, (int) Math.ceil(f1 / binHz));
            if (k0 > n - 2) k0 = n - 2;
            if (k1 <= k0) k1 = k0 + 1;
            float m = 0f;
            for (int k = k0; k < k1; k++) if (mags[k] > m) m = mags[k];
            // dB with a gentle high-frequency tilt (music energy falls with frequency)
            float db = (float) (20.0 * Math.log10(m + 1e-6)) + 6f * b / BANDS;
            float lv = clamp(db / DB_RANGE);
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

        switch (mode) {
            case MODE_LINE: drawLine(c, w, plotH); break;
            case MODE_WATERFALL: drawWaterfall(c, w); break;
            default: drawBars(c, w, plotH); break;
        }
        drawAxis(c, w);

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
        for (int b = 0; b < BANDS; b++) {
            float t = target[b];
            if (instant) disp[b] = t;
            else if (t > disp[b]) disp[b] += (t - disp[b]) * 0.55f;
            else disp[b] = Math.max(t, disp[b] - barDrop);
            if (disp[b] >= peak[b]) {
                peak[b] = disp[b];
                peakTime[b] = now;
            } else if (now - peakTime[b] > PEAK_HOLD_MS) {
                peak[b] = Math.max(disp[b], peak[b] - peakDrop);
            }
        }
    }

    private void buildShaders(int w) {
        barPaint.setShader(new LinearGradient(0, plotBottom, 0, plotTop, stops, null, Shader.TileMode.CLAMP));
        linePaint.setShader(new LinearGradient(0, 0, w, 0, stops, null, Shader.TileMode.CLAMP));
        int[] faded = new int[stops.length];
        for (int i = 0; i < stops.length; i++) faded[i] = (stops[i] & 0x00FFFFFF) | 0x55000000;
        fillPaint.setShader(new LinearGradient(0, plotTop, 0, plotBottom,
                new int[]{faded[faded.length - 1], faded[0] & 0x11FFFFFF}, null, Shader.TileMode.CLAMP));
        shadersDirty = false;
    }

    private void drawBars(Canvas c, int w, float plotH) {
        float bw = (float) w / BANDS;
        float gap = Math.max(1f, bw * 0.22f);
        float capH = Math.max(2f, dp(2));
        for (int b = 0; b < BANDS; b++) {
            float x0 = b * bw + gap / 2f;
            float x1 = (b + 1) * bw - gap / 2f;
            float top = plotBottom - disp[b] * plotH;
            if (plotBottom - top > 1f) c.drawRect(x0, top, x1, plotBottom, barPaint);
            float py = plotBottom - peak[b] * plotH - capH - dp(2);
            peakPaint.setColor(colorAt(peak[b]));
            if (peak[b] > 0.01f) c.drawRect(x0, py, x1, py + capH, peakPaint);
        }
    }

    private void drawLine(Canvas c, int w, float plotH) {
        float bw = (float) w / BANDS;
        linePath.reset();
        float px = bw / 2f;
        float py = plotBottom - disp[0] * plotH;
        linePath.moveTo(0, py);
        linePath.lineTo(px, py);
        for (int b = 1; b < BANDS; b++) {
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
                int src = ((wfHead + r) % WF_ROWS) * BANDS;
                int dst = r * BANDS;
                for (int b = 0; b < BANDS; b++) wfPixels[dst + b] = heatColor(wfLevels[src + b]);
            }
            wfBitmap.setPixels(wfPixels, 0, BANDS, 0, 0, BANDS, WF_ROWS);
            wfDirty = false;
        }
        wfDst.set(0, (int) plotTop, w, (int) plotBottom);
        c.drawBitmap(wfBitmap, wfSrc, wfDst, bmpPaint);
    }

    private void drawAxis(Canvas c, int w) {
        double logRange = Math.log(MAX_HZ / MIN_HZ);
        int[] marks = {100, 1000, 5000, 10000};
        String[] names = {"100", "1k", "5k", "10k"};
        for (int i = 0; i < marks.length; i++) {
            float x = (float) (Math.log(marks[i] / MIN_HZ) / logRange * w);
            if (mode != MODE_WATERFALL) c.drawLine(x, plotTop, x, plotBottom, gridPaint);
            float tw = textPaint.measureText(names[i]);
            c.drawText(names[i], x - tw / 2f, plotBottom + labelH * 0.75f, textPaint);
        }
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
