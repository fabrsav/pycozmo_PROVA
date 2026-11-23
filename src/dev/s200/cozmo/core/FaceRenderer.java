package dev.s200.cozmo.core;

import java.util.Random;

/**
 * Renders FaceParams onto Cozmo's 128x32 logical OLED display buffer with:
 * - Smooth expression transitions (ease-in-out)
 * - Stochastic blinking (2.6s..6.2s, double-blink 12%)
 * - Discrete micro-saccades with dwell intervals (0.7s..2.2s) and 90..140ms transitions
 * - Stimulus gaze tracking with expiration
 * - Two-stage RLE Budget Guard guaranteeing Wire.display(pixels).length <= 1024 bytes.
 * Pure Java 11, zero Android dependencies.
 */
public final class FaceRenderer {
    public static final int MAX_RLE_BYTES = 1024;

    private final Random rng;
    private final boolean[] frameBuffer = new boolean[128 * 32];

    // Expression transition state
    private Personality.Expression fromExpr = Personality.Expression.NEUTRAL;
    private Personality.Expression toExpr = Personality.Expression.NEUTRAL;
    private Personality.Profile activeProfile = Personality.Profile.CURIOSO;
    private long transitionStartMs = 0;
    private long transitionDurationMs = 260;

    // Stochastic blink state
    private long nextBlinkAtMs = -1;
    private long blinkEndAtMs = 0;
    private long secondBlinkStartMs = 0;
    private long secondBlinkEndMs = 0;

    // Discrete micro-saccade state
    private float sacFromX = 0f, sacFromY = 0f;
    private float sacToX = 0f, sacToY = 0f;
    private long sacStartMs = 0;
    private long sacDurationMs = 110;
    private long nextSaccadeAtMs = -1;

    // Stimulus gaze target
    private float stimulusGazeX = 0f, stimulusGazeY = 0f;
    private long stimulusExpiresAtMs = 0;
    private float smoothGazeX = 0f, smoothGazeY = 0f;

    private int rleFallbackCount = 0;

    public FaceRenderer() {
        this(new Random(0xC02A0L));
    }

    public FaceRenderer(long seed) {
        this(new Random(seed));
    }

    public FaceRenderer(Random rng) {
        this.rng = rng == null ? new Random(0xC02A0L) : rng;
    }

    public synchronized void setTargetExpression(Personality.Expression expr, Personality.Profile profile, long nowMs, long transitionMs) {
        Personality.Expression next = expr == null ? Personality.Expression.NEUTRAL : expr;
        Personality.Profile prof = profile == null ? Personality.Profile.CURIOSO : profile;
        if (next != toExpr || prof != activeProfile) {
            fromExpr = toExpr;
            toExpr = next;
            activeProfile = prof;
            transitionStartMs = nowMs;
            transitionDurationMs = Math.max(80L, transitionMs);
        }
    }

    public synchronized void setGazeTarget(float nx, float ny, long expiresAtMs) {
        this.stimulusGazeX = Math.max(-7.5f, Math.min(7.5f, nx));
        this.stimulusGazeY = Math.max(-4.0f, Math.min(4.0f, ny));
        this.stimulusExpiresAtMs = expiresAtMs;
    }

    public synchronized int rleFallbackCount() {
        return rleFallbackCount;
    }

    public synchronized FaceParams computeParams(long nowMs, Personality.Expression expr, Personality.Profile profile) {
        setTargetExpression(expr, profile, nowMs, 260L);
        float t = transitionDurationMs <= 0 ? 1f : Math.min(1f, Math.max(0f, (nowMs - transitionStartMs) / (float) transitionDurationMs));
        FaceParams baseA = FaceParams.forExpression(fromExpr, activeProfile);
        FaceParams baseB = FaceParams.forExpression(toExpr, activeProfile);
        FaceParams blended = FaceParams.lerp(baseA, baseB, t);

        float blinkOpen = updateBlink(nowMs, toExpr);
        float[] sac = updateSaccade(nowMs, toExpr, activeProfile);

        float targetGx = (nowMs < stimulusExpiresAtMs) ? stimulusGazeX : sac[0];
        float targetGy = (nowMs < stimulusExpiresAtMs) ? stimulusGazeY : sac[1];
        smoothGazeX += (targetGx - smoothGazeX) * 0.42f;
        smoothGazeY += (targetGy - smoothGazeY) * 0.42f;

        float gx = Math.max(-8.0f, Math.min(8.0f, smoothGazeX));
        float gy = Math.max(-4.0f, Math.min(4.0f, smoothGazeY));
        return blended.withGazeAndBlink(gx, gy, blinkOpen);
    }

    private float updateBlink(long nowMs, Personality.Expression expr) {
        if (nextBlinkAtMs < 0) {
            scheduleNextBlink(nowMs, expr);
        }
        if (nowMs >= nextBlinkAtMs && nowMs <= blinkEndAtMs) {
            return 0.10f;
        }
        if (secondBlinkStartMs > 0 && nowMs >= secondBlinkStartMs && nowMs <= secondBlinkEndMs) {
            return 0.12f;
        }
        if (nowMs > Math.max(blinkEndAtMs, secondBlinkEndMs)) {
            scheduleNextBlink(nowMs, expr);
        }
        return 1.0f;
    }

    private void scheduleNextBlink(long nowMs, Personality.Expression expr) {
        long minWait = 2600L, maxWait = 6200L;
        long dur = 135L;
        if (expr == Personality.Expression.EXCITED || expr == Personality.Expression.SURPRISED) {
            minWait = 2100L; maxWait = 4400L; dur = 115L;
        } else if (expr == Personality.Expression.SLEEPY) {
            minWait = 3400L; maxWait = 6800L; dur = 260L;
        }
        long wait = minWait + (long) (rng.nextFloat() * (maxWait - minWait));
        nextBlinkAtMs = nowMs + wait;
        blinkEndAtMs = nextBlinkAtMs + dur;
        if (rng.nextFloat() < 0.12f) {
            secondBlinkStartMs = blinkEndAtMs + 95L;
            secondBlinkEndMs = secondBlinkStartMs + 115L;
        } else {
            secondBlinkStartMs = 0L;
            secondBlinkEndMs = 0L;
        }
    }

    private float[] updateSaccade(long nowMs, Personality.Expression expr, Personality.Profile profile) {
        if (nextSaccadeAtMs < 0 || nowMs >= nextSaccadeAtMs) {
            sacFromX = sacToX;
            sacFromY = sacToY;
            float rangeX = profile == Personality.Profile.TRANQUILLO ? 2.4f : 4.5f;
            float rangeY = profile == Personality.Profile.TRANQUILLO ? 1.0f : 1.8f;
            if (expr == Personality.Expression.FOCUSED) {
                rangeX *= 0.45f; rangeY *= 0.45f;
            }
            sacToX = (rng.nextFloat() * 2f - 1f) * rangeX;
            sacToY = (rng.nextFloat() * 2f - 1f) * rangeY;
            sacStartMs = nowMs;
            sacDurationMs = 90L + rng.nextInt(51); // 90..140 ms
            long dwellMin = profile == Personality.Profile.CURIOSO || profile == Personality.Profile.BIRICHINO ? 700L : 1050L;
            long dwellMax = profile == Personality.Profile.TRANQUILLO ? 2200L : 1850L;
            nextSaccadeAtMs = nowMs + sacDurationMs + dwellMin + (long) (rng.nextFloat() * (dwellMax - dwellMin));
        }
        float u = sacDurationMs <= 0 ? 1f : Math.min(1f, Math.max(0f, (nowMs - sacStartMs) / (float) sacDurationMs));
        float smooth = u * u * (3f - 2f * u);
        return new float[]{
            sacFromX + (sacToX - sacFromX) * smooth,
            sacFromY + (sacToY - sacFromY) * smooth
        };
    }

    public synchronized boolean[] renderPixels(FaceParams params) {
        renderInto(params, frameBuffer);
        return frameBuffer.clone();
    }

    /**
     * Renders FaceParams and encodes with Wire.display(), applying the 2-level RLE Budget Guard
     * to guarantee the resulting payload is always <= 1024 bytes.
     */
    public synchronized byte[] renderEncoded(FaceParams params) {
        renderInto(params, frameBuffer);
        byte[] encoded = tryEncode(frameBuffer);
        if (encoded != null && encoded.length <= MAX_RLE_BYTES) {
            return encoded;
        }
        // Level 1 degradation: flatten eyelid curvature and rotation
        rleFallbackCount++;
        renderInto(params.simplifiedLevel1(), frameBuffer);
        encoded = tryEncode(frameBuffer);
        if (encoded != null && encoded.length <= MAX_RLE_BYTES) {
            return encoded;
        }
        // Level 2 degradation: clean rounded rectangle
        rleFallbackCount++;
        renderInto(params.simplifiedLevel2(), frameBuffer);
        encoded = tryEncode(frameBuffer);
        if (encoded != null && encoded.length <= MAX_RLE_BYTES) {
            return encoded;
        }
        return Wire.display(new boolean[128 * 32]);
    }

    private static byte[] tryEncode(boolean[] pixels) {
        try {
            return Wire.display(pixels);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean[] renderStatic(FaceParams params) {
        boolean[] out = new boolean[128 * 32];
        renderInto(params, out);
        return out;
    }

    public static void renderInto(FaceParams params, boolean[] pixels) {
        if (pixels == null || pixels.length != 128 * 32) {
            throw new IllegalArgumentException("Expected 128x32 buffer");
        }
        for (int i = 0; i < pixels.length; i++) pixels[i] = false;
        if (params == null) return;
        drawEye(params.left, params.gazeX, params.gazeY, params.blinkOpenness, pixels);
        drawEye(params.right, params.gazeX, params.gazeY, params.blinkOpenness, pixels);
    }

    private static void drawEye(FaceParams.EyeParams eye, float gx, float gy, float blinkOpen, boolean[] pixels) {
        float cx = eye.cx + gx;
        float cy = eye.cy + gy;
        float halfW = Math.max(5.5f, eye.width * 0.5f);
        float halfH = Math.max(1.2f, eye.height * 0.5f * blinkOpen);
        float cornerR = Math.min(eye.cornerRadius, Math.min(halfW * 0.48f, halfH * 0.48f));
        float cosA = (float) Math.cos(-eye.angleRad);
        float sinA = (float) Math.sin(-eye.angleRad);

        int minX = Math.max(0, (int) Math.floor(cx - halfW - 2));
        int maxX = Math.min(127, (int) Math.ceil(cx + halfW + 2));
        int minY = Math.max(0, (int) Math.floor(cy - halfH - 2));
        int maxY = Math.min(31, (int) Math.ceil(cy + halfH + 2));

        for (int y = minY; y <= maxY; y++) {
            int row = y * 128;
            for (int x = minX; x <= maxX; x++) {
                float dx = x - cx;
                float dy = y - cy;
                float rx = Math.abs(eye.angleRad) > 0.005f ? (dx * cosA - dy * sinA) : dx;
                float ry = Math.abs(eye.angleRad) > 0.005f ? (dx * sinA + dy * cosA) : dy;
                float ax = Math.abs(rx);
                float ay = Math.abs(ry);
                if (ax >= halfW || ay >= halfH) continue;

                if (cornerR > 0.5f && ax > halfW - cornerR && ay > halfH - cornerR) {
                    if (Math.hypot(ax - (halfW - cornerR), ay - (halfH - cornerR)) > cornerR) continue;
                }

                float normX = rx / halfW; // [-1, 1]
                float archFactor = Math.max(0f, 1.0f - normX * normX);

                if (blinkOpen > 0.22f && (eye.upperLidY > 0.01f || Math.abs(eye.upperLidAngle) > 0.01f || Math.abs(eye.upperLidBend) > 0.01f)) {
                    float lidCutY = -halfH + (2f * halfH * eye.upperLidY) + eye.upperLidAngle * rx - archFactor * (halfH * eye.upperLidBend * 0.55f);
                    if (ry < lidCutY) continue;
                }

                if (blinkOpen > 0.22f && (eye.lowerLidY > 0.01f || Math.abs(eye.lowerLidAngle) > 0.01f || Math.abs(eye.lowerLidBend) > 0.01f)) {
                    float bendLift = archFactor * (halfH * eye.lowerLidBend * 1.15f);
                    float lidCutY = halfH - (2f * halfH * eye.lowerLidY) + eye.lowerLidAngle * rx - bendLift;
                    if (ry > lidCutY) continue;
                }

                pixels[row + x] = true;
            }
        }
    }
}
