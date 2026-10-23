package com.lokesh.spectrum;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.util.TypedValue;
import android.view.SurfaceHolder;
import android.view.View;

/** Live wallpaper that draws the app's chosen style and settings while the home screen is visible. */
public class SpectrumWallpaper extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new SpectrumEngine();
    }

    private static final long FRAME_MS = 33;   // ~30 fps to save battery

    private class SpectrumEngine extends Engine {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable frame = this::drawFrame;
        private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private SpectrumView view;
        private Visualizer visualizer;
        private boolean visible;
        private int width, height;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(false);
            view = new SpectrumView(SpectrumWallpaper.this);
            hintPaint.setColor(0xFF9E9E9E);
            hintPaint.setTextSize(sp(14));
        }

        @Override
        public void onVisibilityChanged(boolean v) {
            visible = v;
            if (v) {
                applyPrefs();
                layoutView();
                startAudio();
                handler.removeCallbacks(frame);
                handler.post(frame);
            } else {
                handler.removeCallbacks(frame);
                stopAudio();
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            super.onSurfaceChanged(holder, format, w, h);
            width = w;
            height = h;
            layoutView();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            visible = false;
            handler.removeCallbacks(frame);
            stopAudio();
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onDestroy() {
            handler.removeCallbacks(frame);
            stopAudio();
            super.onDestroy();
        }

        private void applyPrefs() {
            SharedPreferences p = getSharedPreferences("spectrum", MODE_PRIVATE);
            int widthIdx = idx(p.getInt("widthIdx", 1), MainActivity.WIDTH_VALUES.length);
            view.setMode(idx(p.getInt("mode", 0), MainActivity.STYLE_NAMES.length));
            view.setPalette(loadPalette(p));
            view.setFallSeconds(MainActivity.FALL_VALUES[idx(p.getInt("fallIdx", 3), MainActivity.FALL_VALUES.length)]);
            view.setLineWidthDp(MainActivity.WIDTH_VALUES[widthIdx]);
            view.setBandCount(MainActivity.BAND_VALUES[widthIdx]);
            view.setAutoGain(p.getBoolean("autoGain", true));
            view.setGainDb(Math.max(-12, Math.min(24, p.getInt("gainDb", 0))));
            view.setUsePalette(p.getBoolean("ledPalette", false));
            view.setPeaksEnabled(p.getBoolean("peaksOn", true));
            view.setPeakFallSeconds(MainActivity.PEAK_FALL_VALUES[
                    idx(p.getInt("peakFallIdx", 2), MainActivity.PEAK_FALL_VALUES.length)]);
            view.setColoring(idx(p.getInt("coloringIdx", 0), MainActivity.COLORING_NAMES.length));
        }

        private void layoutView() {
            if (width <= 0 || height <= 0) return;
            SharedPreferences p = getSharedPreferences("spectrum", MODE_PRIVATE);
            boolean full = p.getInt("wallArea", 0) == 1;
            int top = full ? (int) (height * 0.12f) : (int) (height * 0.45f);   // leave room for the clock
            view.setPadding(0, top, 0, (int) dp(64));                          // clear of the dock / nav bar
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, width, height);
        }

        private void drawFrame() {
            if (!visible) return;
            SurfaceHolder holder = getSurfaceHolder();
            Canvas c = null;
            try {
                c = holder.lockCanvas();
                if (c != null) {
                    view.draw(c);
                    if (!hasAudioPermission()) {
                        String msg = "Open Spectrum Analyser once to allow audio";
                        c.drawText(msg, (width - hintPaint.measureText(msg)) / 2f, height * 0.3f, hintPaint);
                    }
                }
            } catch (Throwable ignored) {
                // surface went away mid-frame
            } finally {
                if (c != null) {
                    try { holder.unlockCanvasAndPost(c); } catch (Throwable ignored) { }
                }
            }
            handler.removeCallbacks(frame);
            if (visible) handler.postDelayed(frame, FRAME_MS);
        }

        private boolean hasAudioPermission() {
            return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }

        private void startAudio() {
            stopAudio();
            if (!hasAudioPermission()) return;
            try {
                Visualizer v = new Visualizer(0);
                v.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
                final float fs = v.getSamplingRate() / 1000f;
                int rc = v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                    @Override
                    public void onWaveFormDataCapture(Visualizer vis, byte[] wave, int rate) {
                        float[] w = new float[wave.length];
                        for (int i = 0; i < wave.length; i++) w[i] = ((wave[i] & 0xFF) - 128) / 128f;
                        view.submitWave(w, w.length);
                    }

                    @Override
                    public void onFftDataCapture(Visualizer vis, byte[] fft, int rate) {
                        int n = fft.length;
                        if (n < 8) return;
                        int bins = n / 2 + 1;
                        float[] mags = new float[bins];
                        mags[0] = Math.abs(fft[0]);
                        mags[bins - 1] = Math.abs(fft[1]);
                        for (int k = 1; k < n / 2; k++) {
                            float re = fft[2 * k], im = fft[2 * k + 1];
                            mags[k] = (float) Math.sqrt(re * re + im * im);
                        }
                        view.submit(mags, fs / n);
                    }
                }, Visualizer.getMaxCaptureRate(), true, true);
                if (rc != Visualizer.SUCCESS || v.setEnabled(true) != Visualizer.SUCCESS) {
                    v.release();
                    return;
                }
                visualizer = v;
            } catch (Throwable t) {
                visualizer = null;
            }
        }

        private void stopAudio() {
            if (visualizer != null) {
                try { visualizer.setEnabled(false); } catch (Throwable ignored) { }
                try { visualizer.release(); } catch (Throwable ignored) { }
                visualizer = null;
            }
        }
    }

    private static int idx(int i, int len) {
        return (i < 0 || i >= len) ? 0 : i;
    }

    private static int[] loadPalette(SharedPreferences p) {
        String s = p.getString("palette", null);
        if (s != null) {
            try {
                String[] parts = s.split(",");
                if (parts.length >= 2) {
                    int[] out = new int[parts.length];
                    for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].trim());
                    return out;
                }
            } catch (NumberFormatException ignored) { }
        }
        return MainActivity.PRESETS[0].clone();
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }
}
