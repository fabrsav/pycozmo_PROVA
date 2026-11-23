package dev.s200.cozmo.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Deterministic 5-dimension emotion model inspired by PyCozmo's DecayGraph and RepetitionPenalty.
 * Pure Java 11, zero Android dependencies; all time-based methods take explicit nowMs.
 */
public final class EmotionGraph {
    public static final float MIN_VAL = -1.0f;
    public static final float MAX_VAL = 1.0f;
    public static final long REPETITION_WINDOW_MS = 20_000L;
    private static final float[] REPETITION_FACTORS = {1.0f, 0.65f, 0.35f, 0.15f};

    // Piecewise-linear decay factors over elapsed seconds: fast initial settle, gentle tail to baseline
    private static final float[] DECAY_TIME_SEC = {0f, 8f, 25f, 60f, 140f};
    private static final float[] DECAY_FACTOR   = {1.0f, 0.85f, 0.50f, 0.20f, 0.0f};

    public float happy = 0.25f;
    public float excited = 0.10f;
    public float social = 0.15f;
    public float curious = 0.30f;
    public float calm = 0.40f;

    private static final class StimulusRecord {
        long lastTimeMs;
        int count;
    }

    private final Map<String, StimulusRecord> stimulusHistory = new HashMap<>();

    public void applyBaselineBoost(Personality.Profile profile) {
        if (profile == Personality.Profile.CURIOSO) {
            curious = Math.max(curious, 0.40f);
        } else if (profile == Personality.Profile.AFFETTUOSO) {
            social = Math.max(social, 0.35f);
            happy = Math.max(happy, 0.28f);
        } else if (profile == Personality.Profile.BIRICHINO) {
            excited = Math.max(excited, 0.30f);
            curious = Math.max(curious, 0.32f);
        } else if (profile == Personality.Profile.TRANQUILLO) {
            calm = Math.max(calm, 0.65f);
        }
    }

    public static float baselineFor(Personality.Profile p, String dimension) {
        Personality.Profile prof = p == null ? Personality.Profile.CURIOSO : p;
        switch (dimension) {
            case "curious":
                return prof == Personality.Profile.CURIOSO ? 0.28f
                    : (prof == Personality.Profile.BIRICHINO ? 0.22f : 0.14f);
            case "social":
                return prof == Personality.Profile.AFFETTUOSO ? 0.26f : 0.10f;
            case "happy":
                return prof == Personality.Profile.AFFETTUOSO ? 0.22f : 0.10f;
            case "excited":
                return prof == Personality.Profile.BIRICHINO ? 0.18f : 0.05f;
            case "calm":
                return prof == Personality.Profile.TRANQUILLO ? 0.60f : 0.30f;
            default:
                return 0.0f;
        }
    }

    /**
     * Piecewise-linear retention factor for an elapsed interval dtSec.
     */
    public static float piecewiseDecayFactor(float dtSec) {
        if (dtSec <= 0f) return 1.0f;
        // For incremental tick updates (small dt), convert piecewise slope into smooth step retention
        if (dtSec < 5.0f) {
            return (float) Math.exp(-dtSec / 38.0);
        }
        if (dtSec >= DECAY_TIME_SEC[DECAY_TIME_SEC.length - 1]) return 0.0f;
        for (int i = 0; i < DECAY_TIME_SEC.length - 1; i++) {
            float t0 = DECAY_TIME_SEC[i], t1 = DECAY_TIME_SEC[i + 1];
            if (dtSec >= t0 && dtSec <= t1) {
                float u = (dtSec - t0) / (t1 - t0);
                return DECAY_FACTOR[i] + u * (DECAY_FACTOR[i + 1] - DECAY_FACTOR[i]);
            }
        }
        return 0.0f;
    }

    public void decayStep(float dtSec, Personality.Profile profile) {
        if (dtSec <= 0f) return;
        // Fast decay near extremes (>0.70), gentler near resting baseline
        float baseFactor = piecewiseDecayFactor(dtSec);
        happy = decayToward(happy, baselineFor(profile, "happy"), baseFactor, dtSec);
        excited = decayToward(excited, baselineFor(profile, "excited"), baseFactor, dtSec);
        social = decayToward(social, baselineFor(profile, "social"), baseFactor, dtSec);
        curious = decayToward(curious, baselineFor(profile, "curious"), baseFactor, dtSec);
        calm = decayToward(calm, baselineFor(profile, "calm"), baseFactor, dtSec);
    }

    private static float decayToward(float current, float baseline, float baseFactor, float dtSec) {
        float dist = Math.abs(current - baseline);
        float factor = baseFactor;
        if (dist > 0.55f && dtSec < 5.0f) {
            // Faster relaxation near extremes
            factor = (float) Math.exp(-dtSec / 22.0);
        }
        return clamp(baseline + (current - baseline) * factor);
    }

    /**
     * Returns an attenuation multiplier (1.0 -> 0.65 -> 0.35 -> 0.15) if the same stimulus
     * is triggered repeatedly within 20 seconds.
     */
    public float repetitionPenalty(String stimulusKey, long nowMs) {
        if (stimulusKey == null || stimulusKey.isEmpty()) return 1.0f;
        StimulusRecord rec = stimulusHistory.get(stimulusKey);
        if (rec == null) {
            rec = new StimulusRecord();
            rec.lastTimeMs = nowMs;
            rec.count = 1;
            stimulusHistory.put(stimulusKey, rec);
            return REPETITION_FACTORS[0];
        }
        if (nowMs - rec.lastTimeMs > REPETITION_WINDOW_MS) {
            rec.count = 1;
        } else {
            rec.count++;
        }
        rec.lastTimeMs = nowMs;
        int idx = Math.min(REPETITION_FACTORS.length - 1, rec.count - 1);
        return REPETITION_FACTORS[idx];
    }

    public float applyStimulus(String stimulusKey, long nowMs, Personality.Profile profile,
                               float dHappy, float dExcited, float dSocial, float dCurious, float dCalm,
                               float energyMultiplier) {
        Personality.Profile prof = profile == null ? Personality.Profile.CURIOSO : profile;
        float penalty = repetitionPenalty(stimulusKey, nowMs);
        float eMult = Math.max(0.35f, Math.min(1.25f, energyMultiplier));
        happy = clamp(happy + dHappy * penalty * prof.socialGain);
        excited = clamp(excited + dExcited * penalty * prof.playfulnessGain * eMult);
        social = clamp(social + dSocial * penalty * prof.socialGain);
        curious = clamp(curious + dCurious * penalty * prof.curiosityGain);
        calm = clamp(calm + dCalm * penalty);
        return penalty;
    }

    public String dominantEmotion() {
        String best = "calm";
        float bestVal = calm * 0.75f;
        if (curious > bestVal) { bestVal = curious; best = "curious"; }
        if (social > bestVal) { bestVal = social; best = "social"; }
        if (happy > bestVal) { bestVal = happy; best = "happy"; }
        if (excited > bestVal) { best = "excited"; }
        return best;
    }

    public static float clamp(float v) {
        if (!Float.isFinite(v)) return 0.0f;
        return Math.max(MIN_VAL, Math.min(MAX_VAL, v));
    }
}
