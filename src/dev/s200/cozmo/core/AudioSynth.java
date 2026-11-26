package dev.s200.cozmo.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Procedural Cozmo vocal synthesizer at 22050 Hz mono PCM16, converted to
 * 744-sample non-complemented u-law frames for OutputAudio (0x8e).
 * Supports character VoiceProfiles, punctuation prosody, musical motifs, and a hard
 * 90-frame (~2.9 s) cap per vocalization to prevent 0x8e buffer saturation.
 */
public final class AudioSynth {
    public static final int RATE = Wire.AUDIO_SAMPLE_RATE;
    public static final int MAX_CLIP_FRAMES = 90;

    public enum VoiceProfile {
        CURIOSO(1.06f, 1.05f, 0.95f, 1.05f, 1.15f),
        AFFETTUOSO(0.96f, 0.94f, 1.02f, 1.20f, 0.90f),
        BIRICHINO(1.12f, 1.10f, 1.05f, 1.35f, 1.25f),
        TRANQUILLO(0.84f, 0.88f, 0.78f, 0.75f, 0.75f);

        public final float pitchScale;
        public final float formantScale;
        public final float ampScale;
        public final float vibratoScale;
        public final float contourScale;

        VoiceProfile(float pitchScale, float formantScale, float ampScale, float vibratoScale, float contourScale) {
            this.pitchScale = pitchScale;
            this.formantScale = formantScale;
            this.ampScale = ampScale;
            this.vibratoScale = vibratoScale;
            this.contourScale = contourScale;
        }

        public static VoiceProfile fromProfile(Personality.Profile p) {
            if (p == null) return CURIOSO;
            switch (p) {
                case AFFETTUOSO: return AFFETTUOSO;
                case BIRICHINO: return BIRICHINO;
                case TRANQUILLO: return TRANQUILLO;
                case CURIOSO:
                default: return CURIOSO;
            }
        }
    }

    private static final class Segment {
        final float fStart, fEnd, formant, durationSec, amp, vibratoHz, vibratoDepth, breathiness;
        Segment(float fStart, float fEnd, float formant, float durationSec, float amp, float vibratoHz, float vibratoDepth) {
            this(fStart, fEnd, formant, durationSec, amp, vibratoHz, vibratoDepth, 0f);
        }
        Segment(float fStart, float fEnd, float formant, float durationSec, float amp, float vibratoHz, float vibratoDepth, float breathiness) {
            this.fStart = fStart;
            this.fEnd = fEnd;
            this.formant = formant;
            this.durationSec = durationSec;
            this.amp = amp;
            this.vibratoHz = vibratoHz;
            this.vibratoDepth = vibratoDepth;
            this.breathiness = breathiness;
        }
    }

    public static List<byte[]> preset(String kind) {
        return preset(kind, VoiceProfile.CURIOSO);
    }

    public static List<byte[]> preset(String kind, Personality.Profile profile) {
        return preset(kind, VoiceProfile.fromProfile(profile));
    }

    public static List<byte[]> preset(String kind, VoiceProfile vp) {
        VoiceProfile v = vp == null ? VoiceProfile.CURIOSO : vp;
        String k = kind == null ? "curious" : kind.toLowerCase(Locale.ROOT).trim();
        List<Segment> segs = new ArrayList<>();
        switch (k) {
            case "happy":
                segs.add(scale(new Segment(480f, 740f, 1420f, 0.095f, 0.68f, 20f, 0.03f), v));
                segs.add(new Segment(0f, 0f, 0f, 0.025f, 0f, 0f, 0f));
                segs.add(scale(new Segment(780f, 590f, 1520f, 0.075f, 0.64f, 18f, 0.03f), v));
                segs.add(scale(new Segment(620f, 940f, 1780f, 0.115f, 0.76f, 24f, 0.04f), v));
                segs.add(new Segment(0f, 0f, 0f, 0.025f, 0f, 0f, 0f));
                segs.add(scale(new Segment(840f, 1140f, 1980f, 0.15f, 0.78f, 26f, 0.04f), v));
                break;
            case "excited":
                segs.add(scale(new Segment(620f, 960f, 1650f, 0.07f, 0.74f, 28f, 0.05f), v));
                segs.add(scale(new Segment(960f, 710f, 1520f, 0.065f, 0.68f, 30f, 0.05f), v));
                segs.add(scale(new Segment(740f, 1160f, 2050f, 0.09f, 0.80f, 32f, 0.06f), v));
                segs.add(new Segment(0f, 0f, 0f, 0.022f, 0f, 0f, 0f));
                segs.add(scale(new Segment(920f, 1290f, 2240f, 0.14f, 0.82f, 30f, 0.05f), v));
                break;
            case "surprised":
                segs.add(scale(new Segment(420f, 1180f, 2020f, 0.125f, 0.78f, 22f, 0.035f, 0.16f), v));
                segs.add(new Segment(0f, 0f, 0f, 0.020f, 0f, 0f, 0f));
                segs.add(scale(new Segment(1060f, 840f, 1680f, 0.095f, 0.58f, 16f, 0.025f, 0.06f), v));
                break;
            case "sleepy":
            case "lullaby":
                segs.add(scale(new Segment(440f, 340f, 920f, 0.24f, 0.44f, 7f, 0.02f), v));
                segs.add(new Segment(0f, 0f, 0f, 0.05f, 0f, 0f, 0f));
                segs.add(scale(new Segment(320f, 240f, 780f, 0.28f, 0.36f, 6f, 0.015f), v));
                break;
            case "sad":
            case "sigh":
                segs.add(scale(new Segment(520f, 360f, 1020f, 0.21f, 0.52f, 9f, 0.025f, 0.22f), v));
                segs.add(scale(new Segment(360f, 270f, 840f, 0.27f, 0.42f, 8f, 0.02f, 0.24f), v));
                break;
            case "fanfare":
                float[] fanNotes = {523.25f, 659.25f, 783.99f, 1046.50f};
                float[] fanDurs = {0.10f, 0.10f, 0.12f, 0.22f};
                for (int i = 0; i < fanNotes.length; i++) {
                    segs.add(scale(new Segment(fanNotes[i] * 0.96f, fanNotes[i] * 1.01f, fanNotes[i] * 2.3f, fanDurs[i], 0.74f, 18f, 0.03f), v));
                    if (i + 1 < fanNotes.length) segs.add(new Segment(0f, 0f, 0f, 0.02f, 0f, 0f, 0f));
                }
                break;
            case "sing":
                float[] notes = {523.25f, 659.25f, 783.99f, 880.0f, 783.99f, 1046.5f};
                float[] durs = {0.12f, 0.12f, 0.13f, 0.15f, 0.12f, 0.22f};
                for (int i = 0; i < notes.length; i++) {
                    segs.add(scale(new Segment(notes[i] * 0.96f, notes[i], notes[i] * 2.4f, durs[i], 0.70f, 15f, 0.03f), v));
                    if (i + 1 < notes.length) segs.add(new Segment(0f, 0f, 0f, 0.02f, 0f, 0f, 0f));
                }
                break;
            case "curious":
            default:
                segs.add(scale(new Segment(360f, 450f, 1140f, 0.085f, 0.56f, 14f, 0.02f), v));
                segs.add(new Segment(0f, 0f, 0f, 0.025f, 0f, 0f, 0f));
                segs.add(scale(new Segment(500f, 890f, 1780f, 0.185f, 0.72f, 19f, 0.038f), v));
                break;
        }
        return capFrames(Wire.pcmToUlawFrames(synthesize(segs)));
    }

    private static Segment scale(Segment s, VoiceProfile v) {
        float f0 = s.fStart * v.pitchScale;
        float delta = (s.fEnd - s.fStart) * v.pitchScale * v.contourScale;
        float f1 = Math.max(120f, Math.min(2400f, f0 + delta));
        float form = Math.max(300f, Math.min(3400f, s.formant * v.formantScale));
        float amp = Math.max(0f, Math.min(0.92f, s.amp * v.ampScale));
        return new Segment(f0, f1, form, s.durationSec, amp, s.vibratoHz * v.vibratoScale, s.vibratoDepth * v.vibratoScale, s.breathiness);
    }

    public static List<byte[]> speakText(String text) {
        return speakText(text, VoiceProfile.CURIOSO, Personality.Expression.NEUTRAL);
    }

    public static List<byte[]> speakText(String text, VoiceProfile vp, Personality.Expression expr) {
        VoiceProfile v = vp == null ? VoiceProfile.CURIOSO : vp;
        if (text == null) return preset("curious", v);
        String cleaned = text.trim().toLowerCase(Locale.ITALY);
        if (cleaned.isEmpty()) return preset("curious", v);
        if (cleaned.length() > 36) cleaned = cleaned.substring(0, 36);
        List<Segment> segs = new ArrayList<>();
        boolean question = cleaned.endsWith("?");
        boolean exclaim = cleaned.endsWith("!");
        boolean period = cleaned.endsWith(".");
        float exprPitch = (expr == Personality.Expression.EXCITED || expr == Personality.Expression.HAPPY) ? 1.08f
            : (expr == Personality.Expression.SAD || expr == Personality.Expression.SLEEPY ? 0.87f
            : (expr == Personality.Expression.SURPRISED ? 1.14f : 1.0f));
        int phraseHash = cleaned.hashCode();
        float[] contourBias = {0.92f, 1.08f, 0.98f, 1.15f, 0.88f, 1.12f};
        int idx = 0;
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (c == ' ') {
                segs.add(new Segment(0f, 0f, 0f, 0.048f, 0f, 0f, 0f));
                continue;
            }
            if (!Character.isLetterOrDigit(c)) continue;
            int hashStep = Math.abs((phraseHash >> ((idx % 5) * 4)) ^ (c * 31));
            float basePitch = (460f + (c % 7) * 38f) * contourBias[(idx + hashStep) % contourBias.length] * v.pitchScale * exprPitch;
            if (v == VoiceProfile.BIRICHINO && (idx & 1) == 1) basePitch *= 1.12f;
            if (question && i >= cleaned.length() - 3) basePitch += 145f * v.contourScale;
            if (exclaim && i == 0) basePitch += 95f * v.contourScale;
            if (period && i >= cleaned.length() - 3) basePitch *= 0.89f;
            if ("aeiouàèéìòù".indexOf(c) >= 0) {
                float formant = (c == 'a' || c == 'à' ? 1320f
                    : (c == 'e' || c == 'è' || c == 'é' ? 1820f
                    : (c == 'i' || c == 'ì' ? 2220f
                    : (c == 'o' || c == 'ò' ? 1020f : 840f)))) * v.formantScale;
                float slope = (hashStep % 2 == 0) ? 1.09f : 0.94f;
                float endPitch = question && i >= cleaned.length() - 2 ? basePitch * 1.28f
                    : (period && i >= cleaned.length() - 2 ? basePitch * 0.86f : basePitch * slope);
                float baseDur = v == VoiceProfile.TRANQUILLO ? 0.096f : (v == VoiceProfile.BIRICHINO ? 0.072f : 0.084f);
                float dur = baseDur + ((c % 3) - 1) * 0.012f;
                segs.add(new Segment(basePitch, endPitch, formant, dur, 0.68f * v.ampScale, 16f * v.vibratoScale, 0.028f));
            } else {
                float f = (350f + ((c - 'a') & 15) * 26f) * v.pitchScale * exprPitch;
                segs.add(new Segment(f, f * 1.15f, 1480f * v.formantScale, 0.028f, 0.40f * v.ampScale, 20f, 0.02f, 0.08f));
            }
            idx++;
        }
        if (segs.isEmpty()) return preset("curious", v);
        return capFrames(Wire.pcmToUlawFrames(synthesize(segs)));
    }

    private static List<byte[]> capFrames(List<byte[]> frames) {
        if (frames == null || frames.size() <= MAX_CLIP_FRAMES) return frames;
        return new ArrayList<>(frames.subList(0, MAX_CLIP_FRAMES));
    }

    public static short[] synthesize(List<Segment> segments) {
        int totalSamples = 0;
        int maxSamples = MAX_CLIP_FRAMES * Wire.AUDIO_FRAME_SAMPLES;
        for (Segment s : segments) {
            totalSamples += Math.max(1, Math.round(s.durationSec * RATE));
        }
        totalSamples = Math.min(totalSamples, maxSamples);
        short[] out = new short[totalSamples];
        int pos = 0;
        double phase1 = 0.0, phaseFormant = 0.0;
        int noiseSeed = 0x51A7;
        for (Segment s : segments) {
            if (pos >= totalSamples) break;
            int n = Math.max(1, Math.round(s.durationSec * RATE));
            int attack = Math.max(1, Math.min(n / 4, (int) (0.014f * RATE)));
            int release = Math.max(1, Math.min(n / 3, (int) (0.022f * RATE)));
            for (int i = 0; i < n && pos < totalSamples; i++, pos++) {
                if (s.amp <= 0.001f || s.fStart <= 10f) {
                    out[pos] = 0;
                    continue;
                }
                float frac = (float) i / Math.max(1, n - 1);
                float env = 1.0f;
                if (i < attack) env = (float) i / attack;
                else if (i > n - release) env = (float) (n - i) / release;
                double t = (double) i / RATE;
                double vib = s.vibratoDepth > 0 ? Math.sin(2.0 * Math.PI * s.vibratoHz * t) * s.vibratoDepth : 0.0;
                double freq = (s.fStart + (s.fEnd - s.fStart) * frac) * (1.0 + vib);
                phase1 += 2.0 * Math.PI * freq / RATE;
                double fSwept = Math.max(freq * 1.5, s.formant * (0.94 + 0.12 * frac));
                phaseFormant += 2.0 * Math.PI * fSwept / RATE;
                double tri = (2.0 / Math.PI) * Math.asin(Math.sin(phase1));
                double wave = 0.52 * Math.sin(phase1)
                    + 0.20 * tri
                    + 0.22 * Math.sin(phaseFormant) * (0.45 + 0.55 * Math.cos(phase1))
                    + 0.06 * Math.sin(2.0 * phase1);
                if (s.breathiness > 0.005f) {
                    noiseSeed = noiseSeed * 1664525 + 1013904223;
                    double breath = (((noiseSeed >>> 16) & 0xFFFF) / 32768.0) - 1.0;
                    wave = wave * (1.0 - s.breathiness * 0.55) + breath * s.breathiness * 0.45;
                }
                int pcm = (int) Math.round(wave * env * s.amp * 24000.0);
                out[pos] = (short) Math.max(-32767, Math.min(32767, pcm));
            }
        }
        return out;
    }
}
