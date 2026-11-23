package dev.s200.cozmo.core;

/**
 * Balanced Nurture Needs model (Energy, Play, Comfort) with discrete brackets,
 * cross-need multipliers, fullness cooldowns, and courteous offline pause recovery.
 * Pure Java 11, zero Android dependencies; all time-based methods take explicit nowMs.
 */
public final class NeedsModel {
    public enum Bracket {
        CRITICAL("Critico"),
        LOW("Basso"),
        NORMAL("Buono"),
        HIGH("Alto");

        public final String labelIt;
        Bracket(String labelIt) { this.labelIt = labelIt; }
    }

    public static final float MIN_NEED = 0.03f;
    public static final float MAX_NEED = 1.00f;
    public static final float COURTEOUS_RESTART_FLOOR = 0.25f;
    public static final long OFFLINE_RECOVERY_PAUSE_MS = 2L * 3600L * 1000L; // 2 hours
    public static final long FULLNESS_COOLDOWN_MS = 15_000L;

    public float energy = 0.88f;
    public float play = 0.78f;
    public float comfort = 0.92f;

    private long lastPlayBoostMs = -100_000L;
    private long lastComfortBoostMs = -100_000L;
    private String lastFeedback = "ok";

    public static Bracket bracketOf(float value) {
        if (value < 0.15f) return Bracket.CRITICAL;
        if (value < 0.35f) return Bracket.LOW;
        if (value < 0.75f) return Bracket.NORMAL;
        return Bracket.HIGH;
    }

    public static String bracketLabelIt(float value) {
        return bracketOf(value).labelIt;
    }

    public String lastFeedback() {
        return lastFeedback;
    }

    /**
     * Restores persisted needs while applying a courteous restart floor (0.25) if the offline pause
     * exceeded 2 hours or if previous values were trapped at the 0.03 floor from an old session.
     */
    public void restoreFromPersistence(float savedEnergy, float savedPlay, float savedComfort, long savedAtMs, long nowMs) {
        boolean longPause = savedAtMs <= 0L || (nowMs - savedAtMs) >= OFFLINE_RECOVERY_PAUSE_MS;
        float e = clamp(savedEnergy);
        float p = clamp(savedPlay);
        float c = clamp(savedComfort);
        if (longPause) {
            if (e < COURTEOUS_RESTART_FLOOR) e = COURTEOUS_RESTART_FLOOR;
            if (p < COURTEOUS_RESTART_FLOOR) p = COURTEOUS_RESTART_FLOOR;
            if (c < COURTEOUS_RESTART_FLOOR) c = COURTEOUS_RESTART_FLOOR;
        }
        this.energy = e;
        this.play = p;
        this.comfort = c;
    }

    /**
     * Cross-need multiplier for excitement/playfulness based on current energy bracket.
     */
    public float energyExcitementMultiplier() {
        Bracket b = bracketOf(energy);
        if (b == Bracket.CRITICAL) return 0.40f;
        if (b == Bracket.LOW) return 0.65f;
        if (b == Bracket.HIGH) return 1.10f;
        return 1.0f;
    }

    public void step(float dtSec, boolean onCharger, boolean unsafe, float batteryV) {
        if (dtSec <= 0f) return;
        if (onCharger) {
            energy = clamp(energy + dtSec * 0.0080f);
        } else {
            float drain = bracketOf(comfort) == Bracket.CRITICAL ? 0.0016f : 0.0011f;
            energy = clamp(energy - dtSec * drain);
        }
        float playDecay = bracketOf(energy) == Bracket.CRITICAL ? 0.0008f : 0.0014f;
        play = clamp(play - dtSec * playDecay);
        float comfortDecay = unsafe ? 0.038f : 0.00055f;
        comfort = clamp(comfort - dtSec * comfortDecay);

        if (batteryV > 0f && batteryV < 3.62f && !onCharger) {
            energy = Math.min(energy, 0.22f);
        }
    }

    /**
     * Applies an interaction reward with fullness cooldown protection (15s).
     * Returns "already_satisfied" if the interaction is on cooldown, or "ok".
     */
    public String applyCareOrPlay(String kind, float dEnergy, float dPlay, float dComfort, long nowMs) {
        boolean isComfort = "comfort".equalsIgnoreCase(kind);
        boolean isPlay = "play".equalsIgnoreCase(kind);
        if (isComfort && dComfort >= 0.08f) {
            if (nowMs - lastComfortBoostMs < FULLNESS_COOLDOWN_MS) {
                energy = clamp(energy + dEnergy * 0.2f);
                comfort = clamp(comfort + dComfort * 0.15f);
                lastFeedback = "already_satisfied";
                return lastFeedback;
            }
            lastComfortBoostMs = nowMs;
        } else if (isPlay && dPlay >= 0.08f) {
            if (nowMs - lastPlayBoostMs < FULLNESS_COOLDOWN_MS) {
                play = clamp(play + dPlay * 0.15f);
                lastFeedback = "already_satisfied";
                return lastFeedback;
            }
            lastPlayBoostMs = nowMs;
        }
        energy = clamp(energy + dEnergy);
        play = clamp(play + dPlay);
        comfort = clamp(comfort + dComfort);
        lastFeedback = "ok";
        return lastFeedback;
    }

    public static float clamp(float v) {
        if (!Float.isFinite(v)) return 0.5f;
        return Math.max(MIN_NEED, Math.min(MAX_NEED, v));
    }
}
