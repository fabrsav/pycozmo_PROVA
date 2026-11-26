package dev.s200.cozmo.core;

import java.util.Locale;

/**
 * Pure-Java 2D frame analyzer for Cozmo's 320x240 camera stream.
 * Computes luminance, contrast, inter-frame motion centroid, and 2D fiducial marker candidates.
 * Explicitly separates 2D visual cues from 3D physical certainty.
 */
public final class VisionAnalyzer {
    public static final int GRID_W = 16, GRID_H = 12;
    private static final float MARKER_MM = 25.0f;
    private static final float FOCAL_PX = 290.0f;

    public static final class Result {
        public final int width, height;
        public final float meanLuma, contrastStd;
        public final boolean darkScene;
        public final float motionScore, motionX, motionY;
        public final int markerCandidates;
        public final float markerX, markerY, approxMarkerDistMm;
        public final int faceCount;
        public final float faceX, faceY, faceConfidence, faceEyeDistPx;

        public Result(int width, int height, float meanLuma, float contrastStd,
                      float motionScore, float motionX, float motionY,
                      int markerCandidates, float markerX, float markerY, float approxMarkerDistMm,
                      int faceCount, float faceX, float faceY, float faceConfidence, float faceEyeDistPx) {
            this.width = width;
            this.height = height;
            this.meanLuma = meanLuma;
            this.contrastStd = contrastStd;
            this.darkScene = meanLuma < 26.0f;
            this.motionScore = motionScore;
            this.motionX = motionX;
            this.motionY = motionY;
            this.markerCandidates = markerCandidates;
            this.markerX = markerX;
            this.markerY = markerY;
            this.approxMarkerDistMm = approxMarkerDistMm;
            this.faceCount = faceCount;
            this.faceX = faceX;
            this.faceY = faceY;
            this.faceConfidence = faceConfidence;
            this.faceEyeDistPx = faceEyeDistPx;
        }

        public String summary(long framesCount) {
            String lightDesc = darkScene ? "ambiente scuro/buio" : (meanLuma < 85f ? "luce soffusa" : "ben illuminato");
            String faceDesc = faceCount > 0
                ? String.format(Locale.ITALY, "%d volto/i (conf %.0f%%, pos %+.2f/%+.2f, dist. occhi %.1f px)",
                    faceCount, faceConfidence * 100f, faceX, faceY, faceEyeDistPx)
                : "nessun volto nel fotogramma";
            String markerDesc = markerCandidates > 0
                ? String.format(Locale.ITALY, "%d candidato/i 2D (stima ~%.0f mm, non calibrata 3D)", markerCandidates, approxMarkerDistMm)
                : "nessun marker 2D";
            return String.format(Locale.ITALY,
                "Fotogrammi analizzati: %d (%dx%d)\n" +
                "Luminosità: %.1f/255 (%s) · Contrasto: %.1f\n" +
                "Movimento visivo: %.0f%% (centro %+.2f, %+.2f)\n" +
                "Volti (Camera Cozmo): %s\n" +
                "Marker cubi (2D): %s",
                framesCount, width, height,
                meanLuma, lightDesc, contrastStd,
                motionScore * 100f, motionX, motionY,
                faceDesc, markerDesc);
        }
    }

    private float[] prevGrid = null;

    public synchronized void reset() {
        prevGrid = null;
    }

    /**
     * Analyzes ARGB_8888 pixels (width x height) and optional face detector measurements.
     */
    public synchronized Result analyze(int[] argb, int width, int height,
                                       int faceCount, float faceX, float faceY, float faceConfidence, float faceEyeDistPx) {
        if (argb == null || width <= 0 || height <= 0 || argb.length < width * height) {
            throw new IllegalArgumentException("Invalid image buffer");
        }
        float[] grid = new float[GRID_W * GRID_H];
        int[] gridCounts = new int[GRID_W * GRID_H];
        double sum = 0.0, sumSq = 0.0;
        int samples = 0;

        for (int y = 0; y < height; y += 2) {
            int gy = Math.min(GRID_H - 1, (y * GRID_H) / height);
            int rowOffset = y * width;
            for (int x = 0; x < width; x += 2) {
                int gx = Math.min(GRID_W - 1, (x * GRID_W) / width);
                int c = argb[rowOffset + x];
                int r = (c >> 16) & 255;
                int g = (c >> 8) & 255;
                int b = c & 255;
                float luma = 0.299f * r + 0.587f * g + 0.114f * b;
                sum += luma;
                sumSq += luma * luma;
                samples++;
                int gi = gy * GRID_W + gx;
                grid[gi] += luma;
                gridCounts[gi]++;
            }
        }

        for (int i = 0; i < grid.length; i++) {
            if (gridCounts[i] > 0) grid[i] /= gridCounts[i];
        }

        float mean = samples > 0 ? (float) (sum / samples) : 0f;
        float variance = samples > 0 ? Math.max(0f, (float) (sumSq / samples - mean * mean)) : 0f;
        float std = (float) Math.sqrt(variance);

        // Motion against previous grid
        float motionScore = 0f, mx = 0f, my = 0f;
        if (prevGrid != null && prevGrid.length == grid.length) {
            float weightSum = 0f;
            int activeCells = 0;
            for (int gy = 0; gy < GRID_H; gy++) {
                for (int gx = 0; gx < GRID_W; gx++) {
                    int gi = gy * GRID_W + gx;
                    float diff = Math.abs(grid[gi] - prevGrid[gi]);
                    if (diff > 7.5f) {
                        activeCells++;
                        weightSum += diff;
                        float nx = ((gx + 0.5f) / GRID_W) * 2f - 1f;
                        float ny = ((gy + 0.5f) / GRID_H) * 2f - 1f;
                        mx += nx * diff;
                        my += ny * diff;
                    }
                }
            }
            if (weightSum > 0f) {
                mx /= weightSum;
                my /= weightSum;
                motionScore = Math.min(1.0f, (activeCells / (float) (GRID_W * GRID_H)) * 2.2f);
            }
        }
        prevGrid = grid;

        // Detect 2D high-contrast concentric square marker candidates on the 16x12 grid
        int markers = 0;
        float bestMarkerX = 0f, bestMarkerY = 0f, bestContrast = 0f;
        if (std >= 12.0f) {
            for (int gy = 1; gy < GRID_H - 1; gy++) {
                for (int gx = 1; gx < GRID_W - 1; gx++) {
                    float center = grid[gy * GRID_W + gx];
                    float ring = (grid[(gy - 1) * GRID_W + gx] + grid[(gy + 1) * GRID_W + gx]
                                + grid[gy * GRID_W + (gx - 1)] + grid[gy * GRID_W + (gx + 1)]) * 0.25f;
                    float corners = (grid[(gy - 1) * GRID_W + (gx - 1)] + grid[(gy - 1) * GRID_W + (gx + 1)]
                                   + grid[(gy + 1) * GRID_W + (gx - 1)] + grid[(gy + 1) * GRID_W + (gx + 1)]) * 0.25f;
                    // Light cube marker has a high contrast between the dark square frame and lighter margin/plastic
                    float ringDiff = center - ring;
                    if (ringDiff > 28f && Math.abs(ring - corners) < 22f) {
                        markers++;
                        if (ringDiff > bestContrast) {
                            bestContrast = ringDiff;
                            bestMarkerX = ((gx + 0.5f) / GRID_W) * 2f - 1f;
                            bestMarkerY = ((gy + 0.5f) / GRID_H) * 2f - 1f;
                        }
                    }
                }
            }
        }
        float approxDistMm = markers > 0 ? (MARKER_MM * FOCAL_PX) / Math.max(18f, (width / (float) GRID_W) * 2.0f) : 0f;

        return new Result(width, height, mean, std, motionScore, mx, my,
            markers, bestMarkerX, bestMarkerY, approxDistMm,
            faceCount, faceX, faceY, faceConfidence, faceEyeDistPx);
    }

    /**
     * Enhances low-light ARGB frames via percentile contrast stretch + adaptive gamma LUT
     * so dimmed scenes and facial features become clearly visible for FaceDetector and Gemini.
     * Returns null if enhancement is unnecessary (rawMeanLuma >= 95.0f) or buffer invalid.
     */
    public static int[] enhanceLowLightPixels(int[] argb, int width, int height, float rawMeanLuma) {
        if (argb == null || width <= 0 || height <= 0 || argb.length < width * height || rawMeanLuma >= 95.0f) {
            return null;
        }
        int[] hist = new int[256];
        int totalSamples = 0;
        for (int y = 0; y < height; y += 2) {
            int row = y * width;
            for (int x = 0; x < width; x += 2) {
                int c = argb[row + x];
                int r = (c >> 16) & 255;
                int g = (c >> 8) & 255;
                int b = c & 255;
                int luma = Math.max(0, Math.min(255, (77 * r + 150 * g + 29 * b) >> 8));
                hist[luma]++;
                totalSamples++;
            }
        }
        if (totalSamples <= 0) return null;

        int loTarget = Math.max(1, (totalSamples * 2) / 100);
        int hiTarget = Math.max(loTarget + 1, (totalSamples * 98) / 100);
        int cum = 0, lo = 0, hi = 255;
        boolean foundLo = false;
        for (int i = 0; i < 256; i++) {
            cum += hist[i];
            if (!foundLo && cum >= loTarget) {
                lo = i;
                foundLo = true;
            }
            if (cum >= hiTarget) {
                hi = i;
                break;
            }
        }
        if (hi - lo < 28) {
            hi = Math.min(255, lo + 28);
            if (hi - lo < 28) lo = Math.max(0, hi - 28);
        }
        float gamma = rawMeanLuma < 35f ? 0.45f : (rawMeanLuma < 60f ? 0.56f : 0.70f);
        float span = Math.max(1f, hi - lo);
        int[] lut = new int[256];
        for (int v = 0; v < 256; v++) {
            if (v <= lo) {
                lut[v] = 6;
            } else if (v >= hi) {
                lut[v] = 248;
            } else {
                double norm = (v - lo) / span;
                int mapped = (int) Math.round(6.0 + 242.0 * Math.pow(norm, gamma));
                lut[v] = Math.max(0, Math.min(255, mapped));
            }
        }
        int len = width * height;
        int[] out = new int[len];
        for (int i = 0; i < len; i++) {
            int c = argb[i];
            int r = lut[(c >> 16) & 255];
            int g = lut[(c >> 8) & 255];
            int b = lut[c & 255];
            out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return out;
    }
}
