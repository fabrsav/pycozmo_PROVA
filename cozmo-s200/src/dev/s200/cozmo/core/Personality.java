package dev.s200.cozmo.core;

import java.util.Locale;

/**
 * Cozmo personality engine integrating:
 * - EmotionGraph (piecewise decay, repetition penalty, profile baselines)
 * - NeedsModel (Energy, Play, Comfort brackets, cross-multipliers, fullness cooldown, 2h courteous floor)
 * - FaceParams & FaceRenderer (smooth ease-in-out transitions, stochastic blink, discrete saccades, RLE Budget Guard)
 * - PersonalitySnapshot (immutable snapshot for UI, BehaviorScheduler, and AiSupervisor).
 * Pure Java 11, zero Android dependencies.
 */
public final class Personality {
    public enum Expression {
        NEUTRAL("Neutro"),
        HAPPY("Felice"),
        EXCITED("Entusiasta"),
        CURIOUS("Curioso"),
        SURPRISED("Sorpreso"),
        SKEPTICAL("Scettico"),
        SAD("Triste"),
        SLEEPY("Assonnato"),
        FOCUSED("Concentrato");

        public final String label;
        Expression(String label) { this.label = label; }

        public static Expression fromName(String raw) {
            if (raw == null) return NEUTRAL;
            String s = raw.trim().toUpperCase(Locale.ROOT);
            for (Expression e : values()) {
                if (e.name().equals(s) || e.label.equalsIgnoreCase(s)) return e;
            }
            return NEUTRAL;
        }
    }

    public enum Profile {
        CURIOSO("Curioso", 1.35f, 1.00f, 1.05f, "cyan"),
        AFFETTUOSO("Affettuoso", 1.05f, 1.40f, 0.95f, "green"),
        BIRICHINO("Birichino", 1.25f, 1.10f, 1.45f, "purple"),
        TRANQUILLO("Tranquillo", 0.80f, 0.95f, 0.65f, "amber");

        public final String label;
        public final float curiosityGain;
        public final float socialGain;
        public final float playfulnessGain;
        public final String defaultBackpack;

        Profile(String label, float curiosityGain, float socialGain, float playfulnessGain, String defaultBackpack) {
            this.label = label;
            this.curiosityGain = curiosityGain;
            this.socialGain = socialGain;
            this.playfulnessGain = playfulnessGain;
            this.defaultBackpack = defaultBackpack;
        }

        public static Profile fromName(String raw) {
            if (raw == null) return CURIOSO;
            String s = raw.trim().toUpperCase(Locale.ROOT);
            for (Profile p : values()) {
                if (p.name().equals(s) || p.label.equalsIgnoreCase(s)) return p;
            }
            return CURIOSO;
        }
    }

    public static final class PersonalitySnapshot {
        public final Profile profile;
        public final Expression expression;
        public final float energy, play, comfort;
        public final NeedsModel.Bracket energyBracket, playBracket, comfortBracket;
        public final float happy, excited, social, curious, calm;
        public final String dominantEmotion;
        public final String recognizedPerson;
        public final float familiarity;
        public final String recentStimulus;
        public final String moodSummaryIt;

        public PersonalitySnapshot(Profile profile, Expression expression,
                                   float energy, float play, float comfort,
                                   float happy, float excited, float social, float curious, float calm,
                                   String dominantEmotion, String recognizedPerson, float familiarity,
                                   String recentStimulus, String moodSummaryIt) {
            this.profile = profile;
            this.expression = expression;
            this.energy = energy;
            this.play = play;
            this.comfort = comfort;
            this.energyBracket = NeedsModel.bracketOf(energy);
            this.playBracket = NeedsModel.bracketOf(play);
            this.comfortBracket = NeedsModel.bracketOf(comfort);
            this.happy = happy;
            this.excited = excited;
            this.social = social;
            this.curious = curious;
            this.calm = calm;
            this.dominantEmotion = dominantEmotion == null ? "calm" : dominantEmotion;
            this.recognizedPerson = recognizedPerson == null ? "" : recognizedPerson;
            this.familiarity = familiarity;
            this.recentStimulus = recentStimulus == null ? "none" : recentStimulus;
            this.moodSummaryIt = moodSummaryIt == null ? "" : moodSummaryIt;
        }
    }

    private Profile profile = Profile.CURIOSO;
    private final EmotionGraph emotions = new EmotionGraph();
    private final NeedsModel needs = new NeedsModel();
    private final FaceRenderer faceRenderer = new FaceRenderer();

    // Public fields synced for backward compatibility with existing callers/tests
    public float energy = 0.88f;
    public float play = 0.78f;
    public float comfort = 0.92f;
    public float happy = 0.25f;
    public float excited = 0.10f;
    public float social = 0.15f;
    public float curious = 0.30f;
    public float calm = 0.40f;

    private Expression currentExpression = Expression.NEUTRAL;
    private Expression manualExpression = null;
    private long manualUntilMs = 0;
    private long lastUpdateMs = -1;
    private boolean wasPickedUp = false;
    private String recognizedPerson = "";
    private float recognizedFamiliarity = 0f;
    private String recentStimulus = "none";

    private void syncFromPublicToModels() {
        needs.energy = NeedsModel.clamp(this.energy);
        needs.play = NeedsModel.clamp(this.play);
        needs.comfort = NeedsModel.clamp(this.comfort);
        emotions.happy = EmotionGraph.clamp(this.happy);
        emotions.excited = EmotionGraph.clamp(this.excited);
        emotions.social = EmotionGraph.clamp(this.social);
        emotions.curious = EmotionGraph.clamp(this.curious);
        emotions.calm = EmotionGraph.clamp(this.calm);
    }

    private void syncFromModelsToPublic() {
        this.energy = needs.energy;
        this.play = needs.play;
        this.comfort = needs.comfort;
        this.happy = emotions.happy;
        this.excited = emotions.excited;
        this.social = emotions.social;
        this.curious = emotions.curious;
        this.calm = emotions.calm;
    }

    public synchronized EmotionGraph emotionGraph() { return emotions; }
    public synchronized NeedsModel needsModel() { return needs; }
    public synchronized FaceRenderer faceRenderer() { return faceRenderer; }

    public synchronized Profile profile() {
        return profile;
    }

    public synchronized void setProfile(Profile next) {
        syncFromPublicToModels();
        this.profile = next == null ? Profile.CURIOSO : next;
        emotions.applyBaselineBoost(this.profile);
        syncFromModelsToPublic();
    }

    public synchronized void setRecognizedPerson(String name, float familiarity) {
        this.recognizedPerson = name == null ? "" : name.trim();
        this.recognizedFamiliarity = Math.max(0f, Math.min(1f, familiarity));
    }

    public synchronized Expression expression(long nowMs) {
        if (manualExpression != null && nowMs < manualUntilMs) {
            return manualExpression;
        }
        manualExpression = null;
        return currentExpression;
    }

    public synchronized void setExpression(Expression expr, long durationMs, long nowMs) {
        if (expr == null) expr = Expression.NEUTRAL;
        this.manualExpression = expr;
        this.manualUntilMs = nowMs + Math.max(500L, durationMs);
        this.currentExpression = expr;
        faceRenderer.setTargetExpression(expr, profile, nowMs, 240L);
    }

    public synchronized void setGazeTarget(float gx, float gy) {
        faceRenderer.setGazeTarget(gx, gy, Long.MAX_VALUE / 2);
    }

    public synchronized void setGazeTarget(float gx, float gy, long expiresAtMs) {
        faceRenderer.setGazeTarget(gx, gy, expiresAtMs);
    }

    public synchronized void restoreNeeds(float e, float p, float c) {
        needs.energy = NeedsModel.clamp(e);
        needs.play = NeedsModel.clamp(p);
        needs.comfort = NeedsModel.clamp(c);
        syncFromModelsToPublic();
    }

    public synchronized void restoreNeedsFromPersistence(float e, float p, float c, long savedAtMs, long nowMs) {
        needs.restoreFromPersistence(e, p, c, savedAtMs, nowMs);
        syncFromModelsToPublic();
    }

    public synchronized void rewardInteraction(float dEnergy, float dPlay, float dComfort, float dHappy, float dExcited) {
        rewardInteraction("interaction", Link.now(), dEnergy, dPlay, dComfort, dHappy, dExcited);
    }

    public synchronized String rewardInteraction(String kind, long nowMs, float dEnergy, float dPlay, float dComfort, float dHappy, float dExcited) {
        syncFromPublicToModels();
        String feedback = needs.applyCareOrPlay(kind, dEnergy, dPlay, dComfort, nowMs);
        float mult = "already_satisfied".equals(feedback) ? 0.30f : needs.energyExcitementMultiplier();
        emotions.applyStimulus(kind, nowMs, profile, dHappy, dExcited, 0.20f, 0.04f, 0.05f, mult);
        recentStimulus = kind == null ? "interaction" : kind;
        syncFromModelsToPublic();
        return feedback;
    }

    public synchronized String update(long nowMs, Wire.Telemetry t, boolean faceSeen, boolean cubeActive) {
        syncFromPublicToModels();
        if (lastUpdateMs < 0) lastUpdateMs = nowMs;
        float dtSec = Math.max(0f, Math.min(10f, (nowMs - lastUpdateMs) / 1000.0f));
        lastUpdateMs = nowMs;

        boolean onCharger = t != null && t.onCharger();
        boolean unsafe = t != null && t.unsafe();
        float battV = t != null ? t.battery : 0f;

        needs.step(dtSec, onCharger, unsafe, battV);
        emotions.decayStep(dtSec, profile);

        String transition = null;
        if (t != null) {
            if (unsafe) {
                emotions.calm = EmotionGraph.clamp(emotions.calm - 0.35f);
                recentStimulus = "picked_up";
                if (!wasPickedUp) {
                    wasPickedUp = true;
                    setExpression(Expression.SURPRISED, 3000L, nowMs);
                    transition = "surprised";
                }
            } else if (wasPickedUp) {
                wasPickedUp = false;
                needs.comfort = NeedsModel.clamp(needs.comfort + 0.05f);
            }
        }

        if (faceSeen) {
            float famBoost = 1.0f + recognizedFamiliarity * 0.35f;
            emotions.social = EmotionGraph.clamp(emotions.social + dtSec * 0.18f * profile.socialGain * famBoost);
            emotions.happy = EmotionGraph.clamp(emotions.happy + dtSec * 0.12f * profile.socialGain * famBoost);
            needs.play = NeedsModel.clamp(needs.play + dtSec * 0.008f);
            recentStimulus = "face_seen";
        }
        if (cubeActive) {
            float eMult = needs.energyExcitementMultiplier();
            needs.play = NeedsModel.clamp(needs.play + dtSec * 0.025f * profile.playfulnessGain);
            emotions.excited = EmotionGraph.clamp(emotions.excited + dtSec * 0.20f * profile.playfulnessGain * eMult);
            recentStimulus = "cube_active";
        }
        if (profile == Profile.CURIOSO) {
            emotions.curious = EmotionGraph.clamp(emotions.curious + dtSec * 0.015f);
        }

        if (manualExpression == null || nowMs >= manualUntilMs) {
            Expression prev = currentExpression;
            if (unsafe) currentExpression = Expression.SURPRISED;
            else if (needs.energy < 0.25f) currentExpression = Expression.SLEEPY;
            else if (needs.comfort < 0.25f) currentExpression = Expression.SAD;
            else if (emotions.excited > 0.45f && needs.bracketOf(needs.energy) != NeedsModel.Bracket.CRITICAL) currentExpression = Expression.EXCITED;
            else if (emotions.happy > 0.35f || faceSeen) currentExpression = Expression.HAPPY;
            else if (profile == Profile.BIRICHINO && needs.play < 0.55f && emotions.curious > 0.32f) currentExpression = Expression.SKEPTICAL;
            else if (needs.play < 0.35f || emotions.curious > 0.38f) currentExpression = Expression.CURIOUS;
            else currentExpression = Expression.NEUTRAL;
            if (transition == null && prev != currentExpression) {
                transition = currentExpression.name().toLowerCase(Locale.ROOT);
            }
        }
        faceRenderer.setTargetExpression(expression(nowMs), profile, nowMs, 260L);
        syncFromModelsToPublic();
        return transition;
    }

    public synchronized String recommendedBackpack() {
        if (currentExpression == Expression.SURPRISED) return "amber";
        if (currentExpression == Expression.SLEEPY) return "off";
        if (currentExpression == Expression.EXCITED) return "purple";
        if (currentExpression == Expression.HAPPY) return "green";
        return profile.defaultBackpack;
    }

    public static String bracket(float value) {
        return NeedsModel.bracketLabelIt(value);
    }

    public synchronized PersonalitySnapshot snapshot(long nowMs) {
        syncFromPublicToModels();
        Expression e = expression(nowMs);
        String dom = emotions.dominantEmotion();
        String personInfo = recognizedPerson.isEmpty() ? "" :
            String.format(Locale.ITALY, " · Persona: %s (fam. %.0f%%)", recognizedPerson, recognizedFamiliarity * 100f);
        String summaryIt = String.format(Locale.ITALY,
            "Carattere: %s · Espressione: %s%s\nUmore (%s): felice %+.0f%% · curioso %+.0f%% · sociale %+.0f%%\n" +
            "Bisogni: Energy %.0f%% (%s) · Play %.0f%% (%s) · Comfort %.0f%% (%s)",
            profile.label, e.label, personInfo,
            dom, emotions.happy * 100f, emotions.curious * 100f, emotions.social * 100f,
            needs.energy * 100f, NeedsModel.bracketLabelIt(needs.energy),
            needs.play * 100f, NeedsModel.bracketLabelIt(needs.play),
            needs.comfort * 100f, NeedsModel.bracketLabelIt(needs.comfort));
        return new PersonalitySnapshot(
            profile, e,
            needs.energy, needs.play, needs.comfort,
            emotions.happy, emotions.excited, emotions.social, emotions.curious, emotions.calm,
            dom, recognizedPerson, recognizedFamiliarity, recentStimulus, summaryIt
        );
    }

    public synchronized String summary(long nowMs) {
        return snapshot(nowMs).moodSummaryIt;
    }

    public synchronized boolean[] renderFrame(long nowMs, double elapsedSec) {
        FaceParams fp = faceRenderer.computeParams(nowMs, expression(nowMs), profile);
        return faceRenderer.renderPixels(fp);
    }

    public synchronized byte[] renderEncodedFrame(long nowMs) {
        FaceParams fp = faceRenderer.computeParams(nowMs, expression(nowMs), profile);
        return faceRenderer.renderEncoded(fp);
    }

    public static boolean[] renderExpression(Expression expr, float blinkOpen, float dx, float dy) {
        FaceParams fp = FaceParams.forExpression(expr, Profile.CURIOSO).withGazeAndBlink(dx, dy, blinkOpen);
        return FaceRenderer.renderStatic(fp);
    }
}
