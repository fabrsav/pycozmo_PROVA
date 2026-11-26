package dev.s200.cozmo.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Event-driven and need-aware autonomous behavior scheduler.
 * Evaluates an immutable WorldState every 200 ms and outputs a bounded BehaviorDecision
 * without ever touching hardware or bypassing Safety.
 * Pure Java 11, zero Android dependencies.
 */
public final class BehaviorScheduler {
    public static final class WorldState {
        public final Personality.PersonalitySnapshot personality;
        public final boolean onCharger;
        public final boolean pickedUp;
        public final boolean falling;
        public final boolean cliffDetected;
        public final float headAngleRad;
        public final float batteryV;
        public final boolean armed;
        public final boolean cameraActive;
        public final VisionAnalyzer.Result vision;
        public final boolean cubeRecentActivity;
        public final boolean autonomousEnabled;
        public final boolean voiceEnabled;
        public final boolean motionEnabled;
        public final boolean wheelsAutonomousEnabled;

        public WorldState(Personality.PersonalitySnapshot personality,
                          boolean onCharger, boolean pickedUp, boolean falling, boolean cliffDetected,
                          float headAngleRad, float batteryV, boolean armed, boolean cameraActive,
                          VisionAnalyzer.Result vision, boolean cubeRecentActivity,
                          boolean autonomousEnabled, boolean voiceEnabled,
                          boolean motionEnabled, boolean wheelsAutonomousEnabled) {
            this.personality = personality;
            this.onCharger = onCharger;
            this.pickedUp = pickedUp;
            this.falling = falling;
            this.cliffDetected = cliffDetected;
            this.headAngleRad = headAngleRad;
            this.batteryV = batteryV;
            this.armed = armed;
            this.cameraActive = cameraActive;
            this.vision = vision;
            this.cubeRecentActivity = cubeRecentActivity;
            this.autonomousEnabled = autonomousEnabled;
            this.voiceEnabled = voiceEnabled;
            this.motionEnabled = motionEnabled;
            this.wheelsAutonomousEnabled = wheelsAutonomousEnabled && motionEnabled && !onCharger && !pickedUp && !falling && !cliffDetected;
        }
    }

    public static final class BehaviorDecision {
        public final String behaviorId;
        public final String clipId;
        public final AnimClip.Priority priority;
        public final boolean hasGazeTarget;
        public final float gazeX, gazeY;
        public final boolean hasHeadTarget;
        public final float headTargetRad;
        public final boolean allowVoice;
        public final boolean allowMotion;
        public final boolean allowWheels;
        public final String reason;

        public BehaviorDecision(String behaviorId, String clipId, AnimClip.Priority priority,
                                boolean hasGazeTarget, float gazeX, float gazeY,
                                boolean hasHeadTarget, float headTargetRad,
                                boolean allowVoice, boolean allowMotion, boolean allowWheels,
                                String reason) {
            this.behaviorId = behaviorId;
            this.clipId = clipId;
            this.priority = priority;
            this.hasGazeTarget = hasGazeTarget;
            this.gazeX = gazeX;
            this.gazeY = gazeY;
            this.hasHeadTarget = hasHeadTarget;
            this.headTargetRad = headTargetRad;
            this.allowVoice = allowVoice;
            this.allowMotion = allowMotion;
            this.allowWheels = allowWheels;
            this.reason = reason == null ? "" : reason;
        }
    }

    private final Map<String, Long> lastRunMs = new HashMap<>();
    private long lastFaceSeenAtMs = -100_000L;
    private long lastAnyBehaviorMs = -100_000L;
    private int idleCycle = 0;

    public synchronized void reset() {
        lastRunMs.clear();
        lastFaceSeenAtMs = -100_000L;
        lastAnyBehaviorMs = -100_000L;
        idleCycle = 0;
    }

    private boolean onCooldown(String key, long cooldownMs, long nowMs) {
        Long prev = lastRunMs.get(key);
        return prev != null && (nowMs - prev) < cooldownMs;
    }

    private void markRan(String key, long nowMs) {
        lastRunMs.put(key, nowMs);
        lastAnyBehaviorMs = nowMs;
    }

    public static long idleIntervalForProfile(Personality.Profile p) {
        if (p == Personality.Profile.BIRICHINO) return 18_000L;
        if (p == Personality.Profile.CURIOSO) return 22_000L;
        if (p == Personality.Profile.AFFETTUOSO) return 26_000L;
        return 35_000L; // TRANQUILLO
    }

    public synchronized BehaviorDecision evaluate(WorldState w, long nowMs) {
        if (w == null) return null;

        boolean safeForWheels = w.wheelsAutonomousEnabled && w.armed && !w.onCharger && !w.pickedUp && !w.falling && !w.cliffDetected;
        boolean safeForJoints = w.motionEnabled && w.armed && !w.pickedUp && !w.falling && !w.cliffDetected;

        // 1. Physical safety reflexes (always active when autonomousEnabled or even as safety reflex)
        if (w.falling || w.cliffDetected) {
            if (!onCooldown("CLIFF_RECOIL", 2500L, nowMs)) {
                markRan("CLIFF_RECOIL", nowMs);
                return new BehaviorDecision("CLIFF_RECOIL", "CLIFF_RECOIL", AnimClip.Priority.SAFETY_REFLEX,
                    true, 0f, 2.5f, false, 0f, w.voiceEnabled, false, false,
                    "Sensore caduta/bordo attivo: riflesso di sicurezza");
            }
        }
        if (w.pickedUp) {
            if (!onCooldown("SURPRISED_PICKUP", 3500L, nowMs)) {
                markRan("SURPRISED_PICKUP", nowMs);
                return new BehaviorDecision("SURPRISED_PICKUP", "SURPRISED_PICKUP", AnimClip.Priority.SAFETY_REFLEX,
                    true, 0f, -2.0f, false, 0f, w.voiceEnabled, false, false,
                    "Robot sollevato: riflesso sorpresa");
            }
        }

        if (!w.autonomousEnabled) {
            return null;
        }

        // 2. 3D dock head raise for camera clearance (30s cooldown)
        if (w.onCharger && w.cameraActive && w.headAngleRad < 0.15f && safeForJoints) {
            if (!onCooldown("RAISE_HEAD_FOR_CAMERA", 30_000L, nowMs)) {
                markRan("RAISE_HEAD_FOR_CAMERA", nowMs);
                return new BehaviorDecision("RAISE_HEAD_FOR_CAMERA", null, AnimClip.Priority.SOCIAL_REACTION,
                    false, 0f, 0f, true, 0.18f, false, true, false,
                    "Basetta 3D con camera attiva: sollevo testa a +0.18 rad");
            }
        }

        // 3. Social reactions to faces
        if (w.vision != null && w.vision.faceCount > 0) {
            long faceAbsenceMs = nowMs - lastFaceSeenAtMs;
            lastFaceSeenAtMs = nowMs;
            float gx = w.vision.faceX * 5.5f;
            float gy = w.vision.faceY * 3.0f;
            float targetHead = Safety.limit(w.headAngleRad - w.vision.faceY * 0.18f, -0.35f, 0.58f);
            String person = w.personality != null ? w.personality.recognizedPerson : "";
            if (person != null && !person.isEmpty()) {
                if (faceAbsenceMs >= 45_000L || !onCooldown("GREET_FAMILIAR", 45_000L, nowMs)) {
                    markRan("GREET_FAMILIAR", nowMs);
                    return new BehaviorDecision("GREET_FAMILIAR", "GREET_FAMILIAR", AnimClip.Priority.SOCIAL_REACTION,
                        true, gx, gy, safeForJoints, targetHead, w.voiceEnabled, safeForJoints, false,
                        "Volto familiare riconosciuto: " + person);
                }
            } else if (!onCooldown("GREET_STRANGER", 20_000L, nowMs)) {
                markRan("GREET_STRANGER", nowMs);
                return new BehaviorDecision("GREET_STRANGER", "GREET_STRANGER", AnimClip.Priority.SOCIAL_REACTION,
                    true, gx, gy, safeForJoints, targetHead, w.voiceEnabled, safeForJoints, false,
                    "Volto rilevato nella camera di Cozmo");
            }
        }

        // 4. Environmental & cube reactions
        if (w.cubeRecentActivity && !onCooldown("CUBE_VICTORY", 25_000L, nowMs)) {
            markRan("CUBE_VICTORY", nowMs);
            return new BehaviorDecision("CUBE_VICTORY", "CUBE_VICTORY", AnimClip.Priority.SOCIAL_REACTION,
                false, 0f, 0f, false, 0f, w.voiceEnabled, safeForJoints, false,
                "Attività rilevata sui cubi luminosi");
        }

        if (w.vision != null && w.vision.motionScore >= 0.26f && !onCooldown("MOTION_INSPECT", 10_000L, nowMs)) {
            markRan("MOTION_INSPECT", nowMs);
            String[] motionClips = {"CURIOUS_INSPECT", "PLAY_CHEER", "GREET_STRANGER"};
            String motionClip = motionClips[Math.floorMod(idleCycle++, motionClips.length)];
            float targetHead = Safety.limit(w.headAngleRad - w.vision.motionY * 0.16f, -0.35f, 0.58f);
            return new BehaviorDecision(motionClip, motionClip, AnimClip.Priority.IDLE_AUTONOMOUS,
                true, w.vision.motionX * 6.0f, w.vision.motionY * 3.0f, safeForJoints, targetHead,
                w.voiceEnabled, safeForJoints, safeForWheels,
                "Movimento visivo rilevato nel campo della camera");
        }

        // 5. Need-driven behaviors
        if (w.personality != null) {
            if (w.personality.energyBracket == NeedsModel.Bracket.CRITICAL ||
                (w.onCharger && w.personality.energyBracket == NeedsModel.Bracket.LOW)) {
                if (!onCooldown("SLEEPY_CHARGER", 45_000L, nowMs)) {
                    markRan("SLEEPY_CHARGER", nowMs);
                    return new BehaviorDecision("SLEEPY_CHARGER", "SLEEPY_CHARGER", AnimClip.Priority.IDLE_AUTONOMOUS,
                        true, 0f, 2.5f, false, 0f, false, false, false,
                        "Energia bassa o riposo silenzioso sulla basetta");
                }
            }
            if (w.personality.playBracket == NeedsModel.Bracket.CRITICAL ||
                w.personality.playBracket == NeedsModel.Bracket.LOW) {
                if (!onCooldown("BORED_SIGH", 65_000L, nowMs)) {
                    markRan("BORED_SIGH", nowMs);
                    return new BehaviorDecision("BORED_SIGH", "BORED_SIGH", AnimClip.Priority.IDLE_AUTONOMOUS,
                        true, -2.5f, 1.5f, false, 0f, w.voiceEnabled, safeForJoints, false,
                        "Bisogno Play basso: Cozmo cerca gioco");
                }
            }
        }

        // 6. Alive idle behavior scaled by character profile
        Personality.Profile prof = w.personality != null ? w.personality.profile : Personality.Profile.CURIOSO;
        long idleInterval = idleIntervalForProfile(prof);
        if ((nowMs - lastAnyBehaviorMs) >= idleInterval && !onCooldown("IDLE_LIFE", idleInterval, nowMs)) {
            markRan("IDLE_LIFE", nowMs);
            boolean quietProfile = prof == Personality.Profile.TRANQUILLO;
            int step = Math.floorMod(idleCycle++, 3);
            String clip;
            if (quietProfile) {
                clip = (step % 2 == 0) ? "SLEEPY_CHARGER" : "HAPPY_COMFORT";
            } else if (prof == Personality.Profile.BIRICHINO) {
                String[] biri = {"PLAY_CHEER", "CURIOUS_INSPECT", "CUBE_VICTORY"};
                clip = biri[step];
            } else if (prof == Personality.Profile.AFFETTUOSO) {
                String[] aff = {"HAPPY_COMFORT", "CURIOUS_INSPECT", "PLAY_CHEER"};
                clip = aff[step];
            } else {
                String[] cur = {"CURIOUS_INSPECT", "HAPPY_COMFORT", "PLAY_CHEER"};
                clip = cur[step];
            }
            float[] gazeXs = {-4.5f, 4.5f, 0f};
            float[] gazeYs = {-1.0f, 0.5f, -1.8f};
            return new BehaviorDecision("IDLE_" + prof.name(), clip, AnimClip.Priority.IDLE_AUTONOMOUS,
                !quietProfile, gazeXs[step], gazeYs[step], false, 0f,
                w.voiceEnabled && !quietProfile, safeForJoints, safeForWheels && !quietProfile,
                "Vita autonoma idle (" + prof.label + ")");
        }

        return null;
    }
}
