package dev.s200.cozmo;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import dev.s200.cozmo.core.Personality;
import dev.s200.cozmo.core.Wire;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Synthesizes natural Italian speech using Android TTS (com.google.android.tts),
 * resamples to 22050 Hz, applies subtle Cozmo speaker DSP (high-pass + mild 145 Hz robot timbre),
 * and encodes 744-sample u-law frames for OutputAudio (0x8e).
 */
public final class CozmoTts {
    private static final int MAX_TTS_FRAMES = 140; // ~4.7 s max
    private static final long TIMEOUT_MS = 4500L;

    private static final class PendingJob {
        final File wavFile;
        final Consumer<List<byte[]>> onFrames;
        final Runnable onFallback;
        final AtomicBoolean completed = new AtomicBoolean(false);

        PendingJob(File wavFile, Consumer<List<byte[]>> onFrames, Runnable onFallback) {
            this.wavFile = wavFile;
            this.onFrames = onFrames;
            this.onFallback = onFallback;
        }
    }

    private final Context context;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "cozmo-tts"));
    private final ConcurrentHashMap<String, PendingJob> jobs = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile TextToSpeech tts;
    private volatile boolean ready = false;
    private volatile boolean triedDefaultFallback = false;

    public CozmoTts(Context ctx) {
        this.context = ctx.getApplicationContext();
        cleanupOldCacheFiles();
        initEngine("com.google.android.tts");
    }

    private void initEngine(String enginePkg) {
        if (closed.get()) return;
        try {
            final TextToSpeech[] holder = new TextToSpeech[1];
            TextToSpeech.OnInitListener listener = status -> {
                if (closed.get()) {
                    if (holder[0] != null) {
                        try { holder[0].shutdown(); } catch (Exception ignored) {}
                    }
                    return;
                }
                if (status == TextToSpeech.SUCCESS && holder[0] != null) {
                    TextToSpeech engine = holder[0];
                    int res = engine.setLanguage(Locale.ITALIAN);
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        engine.setLanguage(Locale.getDefault());
                    }
                    engine.setPitch(1.42f);
                    engine.setSpeechRate(1.10f);
                    engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override
                        public void onStart(String utteranceId) {}

                        @Override
                        public void onDone(String utteranceId) {
                            PendingJob job = jobs.remove(utteranceId);
                            if (job == null || !job.completed.compareAndSet(false, true)) return;
                            worker.execute(() -> processWavAndDeliver(job));
                        }

                        @Override
                        @SuppressWarnings("deprecation")
                        public void onError(String utteranceId) {
                            failJob(utteranceId);
                        }

                        @Override
                        public void onError(String utteranceId, int errorCode) {
                            failJob(utteranceId);
                        }
                    });
                    tts = engine;
                    ready = true;
                } else {
                    if (holder[0] != null) {
                        try { holder[0].shutdown(); } catch (Exception ignored) {}
                    }
                    if (!triedDefaultFallback && enginePkg != null) {
                        triedDefaultFallback = true;
                        initEngine(null);
                    }
                }
            };
            holder[0] = (enginePkg != null)
                ? new TextToSpeech(context, listener, enginePkg)
                : new TextToSpeech(context, listener);
        } catch (Exception ignored) {
            ready = false;
        }
    }

    public boolean isReady() {
        return ready && !closed.get() && tts != null;
    }

    public void shutdown() {
        closed.set(true);
        ready = false;
        TextToSpeech engine = tts;
        tts = null;
        if (engine != null) {
            try { engine.stop(); } catch (Exception ignored) {}
            try { engine.shutdown(); } catch (Exception ignored) {}
        }
        for (PendingJob job : jobs.values()) {
            job.completed.set(true);
            safeDelete(job.wavFile);
        }
        jobs.clear();
        worker.shutdownNow();
        cleanupOldCacheFiles();
    }

    public void synthesize(String text, Personality.Expression expr,
                           Consumer<List<byte[]>> onFrames, Runnable onFallback) {
        String clean = sanitizeSpeech(text);
        TextToSpeech engine = tts;
        if (clean.isEmpty() || !ready || closed.get() || engine == null) {
            if (onFallback != null) onFallback.run();
            return;
        }
        long id = seq.incrementAndGet();
        String uttId = "cozmo_utt_" + id;
        File outFile = new File(context.getCacheDir(), "cozmo_tts_" + id + ".wav");
        PendingJob job = new PendingJob(outFile, onFrames, onFallback);
        jobs.put(uttId, job);

        worker.schedule(() -> {
            PendingJob j = jobs.remove(uttId);
            if (j != null && j.completed.compareAndSet(false, true)) {
                safeDelete(j.wavFile);
                if (j.onFallback != null) j.onFallback.run();
            }
        }, TIMEOUT_MS, TimeUnit.MILLISECONDS);

        try {
            synchronized (this) {
                if (closed.get() || tts != engine) {
                    jobs.remove(uttId);
                    safeDelete(outFile);
                    if (onFallback != null) onFallback.run();
                    return;
                }
                engine.setPitch(pitchForExpression(expr));
                engine.setSpeechRate(rateForExpression(expr));
                Bundle params = new Bundle();
                int rc = engine.synthesizeToFile(clean, params, outFile, uttId);
                if (rc != TextToSpeech.SUCCESS) {
                    PendingJob removed = jobs.remove(uttId);
                    if (removed != null && removed.completed.compareAndSet(false, true)) {
                        safeDelete(removed.wavFile);
                        if (removed.onFallback != null) removed.onFallback.run();
                    }
                }
            }
        } catch (Exception e) {
            PendingJob removed = jobs.remove(uttId);
            if (removed != null && removed.completed.compareAndSet(false, true)) {
                safeDelete(removed.wavFile);
                if (removed.onFallback != null) removed.onFallback.run();
            }
        }
    }

    private void failJob(String utteranceId) {
        PendingJob job = utteranceId == null ? null : jobs.remove(utteranceId);
        if (job != null && job.completed.compareAndSet(false, true)) {
            safeDelete(job.wavFile);
            if (job.onFallback != null) {
                worker.execute(job.onFallback);
            }
        }
    }

    private void processWavAndDeliver(PendingJob job) {
        try {
            List<byte[]> frames = decodeWavToCozmoFrames(job.wavFile);
            if (frames != null && !frames.isEmpty()) {
                if (job.onFrames != null) job.onFrames.accept(frames);
            } else if (job.onFallback != null) {
                job.onFallback.run();
            }
        } catch (Exception e) {
            if (job.onFallback != null) job.onFallback.run();
        } finally {
            safeDelete(job.wavFile);
        }
    }

    private static float pitchForExpression(Personality.Expression expr) {
        if (expr == Personality.Expression.SURPRISED) return 1.56f;
        if (expr == Personality.Expression.EXCITED || expr == Personality.Expression.HAPPY) return 1.50f;
        if (expr == Personality.Expression.SAD || expr == Personality.Expression.SLEEPY) return 1.22f;
        return 1.42f;
    }

    private static float rateForExpression(Personality.Expression expr) {
        if (expr == Personality.Expression.EXCITED || expr == Personality.Expression.SURPRISED) return 1.16f;
        if (expr == Personality.Expression.SAD || expr == Personality.Expression.SLEEPY) return 0.96f;
        return 1.10f;
    }

    private static String sanitizeSpeech(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim().replaceAll("\\s+", " ");
        if (trimmed.length() > 100) trimmed = trimmed.substring(0, 100);
        boolean hasAlnum = false;
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isLetterOrDigit(trimmed.charAt(i))) {
                hasAlnum = true;
                break;
            }
        }
        return hasAlnum ? trimmed : "";
    }

    private static List<byte[]> decodeWavToCozmoFrames(File wavFile) throws Exception {
        if (wavFile == null || !wavFile.isFile()) return null;
        long fileLen = wavFile.length();
        if (fileLen < 44 || fileLen > 4_000_000L) return null;

        byte[] raw = readFileBytes(wavFile, (int) fileLen);
        if (raw.length < 44) return null;
        if (raw[0] != 'R' || raw[1] != 'I' || raw[2] != 'F' || raw[3] != 'F'
                || raw[8] != 'W' || raw[9] != 'A' || raw[10] != 'V' || raw[11] != 'E') {
            return null;
        }

        int audioFormat = 1;
        int channels = 1;
        int sampleRate = 22050;
        int bitsPerSample = 16;
        int dataStart = -1;
        int dataLen = 0;
        boolean foundFmt = false;

        int pos = 12;
        while (pos + 8 <= raw.length) {
            String tag = new String(raw, pos, 4, StandardCharsets.US_ASCII);
            long chunkSize = Integer.toUnsignedLong(ByteBuffer.wrap(raw, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
            int payloadStart = pos + 8;
            int avail = (int) Math.min(chunkSize, raw.length - payloadStart);
            if ("fmt ".equals(tag) && avail >= 16) {
                ByteBuffer fmt = ByteBuffer.wrap(raw, payloadStart, avail).order(ByteOrder.LITTLE_ENDIAN);
                audioFormat = fmt.getShort() & 0xFFFF;
                channels = fmt.getShort() & 0xFFFF;
                sampleRate = fmt.getInt();
                fmt.getInt(); // byteRate
                fmt.getShort(); // blockAlign
                bitsPerSample = fmt.getShort() & 0xFFFF;
                foundFmt = true;
            } else if ("data".equals(tag) && avail > 0) {
                dataStart = payloadStart;
                dataLen = avail;
            }
            if (chunkSize > raw.length) break;
            long step = 8L + ((chunkSize + 1L) & ~1L);
            if (step <= 8L || pos + step > raw.length) break;
            pos += (int) step;
        }

        if (!foundFmt || dataStart < 0 || dataLen < 2 || channels < 1 || channels > 8
                || sampleRate < 8000 || sampleRate > 96000) {
            return null;
        }

        int bytesPerSample = bitsPerSample / 8;
        int frameAlign = channels * bytesPerSample;
        if (bytesPerSample <= 0 || frameAlign <= 0) return null;
        int srcFrames = dataLen / frameAlign;
        if (srcFrames < 8) return null;

        float[] mono = new float[srcFrames];
        ByteBuffer db = ByteBuffer.wrap(raw, dataStart, srcFrames * frameAlign).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < srcFrames; i++) {
            float sum = 0f;
            for (int c = 0; c < channels; c++) {
                if (audioFormat == 3 && bitsPerSample == 32) {
                    sum += db.getFloat();
                } else if (bitsPerSample == 8) {
                    sum += ((db.get() & 0xFF) - 128) / 128.0f;
                } else if (bitsPerSample == 16) {
                    sum += db.getShort() / 32768.0f;
                } else {
                    return null;
                }
            }
            mono[i] = sum / channels;
        }

        // Trim leading/trailing silence while retaining ~12 ms pad so edges don't click
        int padSamples = Math.max(0, (sampleRate * 12) / 1000);
        int first = 0;
        while (first < mono.length && Math.abs(mono[first]) < 0.015f) first++;
        if (first >= mono.length) return null;
        int last = mono.length - 1;
        while (last > first && Math.abs(mono[last]) < 0.015f) last--;
        first = Math.max(0, first - padSamples);
        last = Math.min(mono.length - 1, last + padSamples);
        int trimmedLen = last - first + 1;
        if (trimmedLen < 8) return null;

        // Resample linearly to Cozmo native 22050 Hz and cap length
        int maxOutSamples = MAX_TTS_FRAMES * Wire.AUDIO_FRAME_SAMPLES;
        int targetLen = (int) Math.min(maxOutSamples,
            Math.max(Wire.AUDIO_FRAME_SAMPLES, Math.round(trimmedLen * (double) Wire.AUDIO_SAMPLE_RATE / sampleRate)));
        float[] resampled = new float[targetLen];
        double ratio = (trimmedLen > 1 && targetLen > 1) ? (trimmedLen - 1) / (double) (targetLen - 1) : 0.0;
        for (int i = 0; i < targetLen; i++) {
            double srcIdx = i * ratio;
            int idx0 = Math.min(trimmedLen - 1, (int) srcIdx);
            int idx1 = Math.min(trimmedLen - 1, idx0 + 1);
            float frac = (float) (srcIdx - idx0);
            float s0 = mono[first + idx0];
            float s1 = mono[first + idx1];
            resampled[i] = s0 + (s1 - s0) * frac;
        }

        // High-pass (alpha=0.94) + subtle 145 Hz Cozmo speaker timbre + peak normalize
        float[] processed = new float[targetLen];
        float prevX = 0f, prevHp = 0f, peak = 1e-5f;
        for (int i = 0; i < targetLen; i++) {
            float x = resampled[i];
            float hp = 0.94f * (prevHp + x - prevX);
            prevX = x;
            prevHp = hp;
            double mod = Math.sin(2.0 * Math.PI * 145.0 * i / Wire.AUDIO_SAMPLE_RATE);
            float v = (float) (0.86 * hp + 0.14 * hp * mod);
            processed[i] = v;
            float av = Math.abs(v);
            if (av > peak) peak = av;
        }

        float gain = Math.min(6.0f, 26000.0f / (peak * 32767.0f));
        short[] pcm = new short[targetLen];
        for (int i = 0; i < targetLen; i++) {
            int s = Math.round(processed[i] * gain * 32767.0f);
            pcm[i] = (short) Math.max(-32767, Math.min(32767, s));
        }
        List<byte[]> frames = Wire.pcmToUlawFrames(pcm);
        if (frames.size() > MAX_TTS_FRAMES) {
            return new ArrayList<>(frames.subList(0, MAX_TTS_FRAMES));
        }
        return frames;
    }

    private static byte[] readFileBytes(File f, int maxLen) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(maxLen);
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[4096];
            int n;
            while (out.size() < maxLen && (n = in.read(buf, 0, Math.min(buf.length, maxLen - out.size()))) >= 0) {
                out.write(buf, 0, n);
            }
        }
        return out.toByteArray();
    }

    private void cleanupOldCacheFiles() {
        try {
            File[] files = context.getCacheDir().listFiles();
            if (files == null) return;
            for (File f : files) {
                if (f.getName().startsWith("cozmo_tts_") && f.getName().endsWith(".wav")) {
                    safeDelete(f);
                }
            }
        } catch (Exception ignored) {}
    }

    private static void safeDelete(File f) {
        if (f != null) {
            try { f.delete(); } catch (Exception ignored) {}
        }
    }
}
