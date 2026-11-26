package dev.s200.cozmo.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Multi-modal choreographer executing at most one AnimClip at a time with priority preemption,
 * audio backpressure guard (<= 120 frames queued in Link), and autonomous audio anti-spam (1800 ms).
 * Pure Java 11, zero Android dependencies; never invokes Link directly.
 */
public final class Choreographer {
    public static final int AUDIO_BACKPRESSURE_GUARD_FRAMES = 120;
    public static final long AUTONOMOUS_AUDIO_COOLDOWN_MS = 1800L;

    public static final class ChoreographyStep {
        public final String activeClipId;
        public final String startedClipId;
        public final String endedClipId;
        public final AnimClip.FaceKeyframe faceIntent;
        public final List<byte[]> audioFrames;
        public final boolean audioSkippedBackpressure;
        public final boolean audioSkippedCooldown;
        public final String backpackColor;
        public final AnimClip.MotorGesture motorIntent;

        public ChoreographyStep(String activeClipId, String startedClipId, String endedClipId,
                                AnimClip.FaceKeyframe faceIntent, List<byte[]> audioFrames,
                                boolean audioSkippedBackpressure, boolean audioSkippedCooldown,
                                String backpackColor, AnimClip.MotorGesture motorIntent) {
            this.activeClipId = activeClipId;
            this.startedClipId = startedClipId;
            this.endedClipId = endedClipId;
            this.faceIntent = faceIntent;
            this.audioFrames = audioFrames;
            this.audioSkippedBackpressure = audioSkippedBackpressure;
            this.audioSkippedCooldown = audioSkippedCooldown;
            this.backpackColor = backpackColor;
            this.motorIntent = motorIntent;
        }
    }

    private final Map<String, AnimClip> library = new LinkedHashMap<>();
    private AnimClip activeClip = null;
    private long activeStartMs = 0;
    private long lastEvaluatedOffsetMs = -1;
    private String pendingStartedId = null;
    private boolean activeMuteAudio = false;
    private boolean activeMuteFace = false;
    private long lastAutonomousAudioMs = -100_000L;

    public Choreographer() {
        registerBuiltInClips();
    }

    private void registerBuiltInClips() {
        register(new AnimClip("GREET_FAMILIAR", AnimClip.Priority.SOCIAL_REACTION, 2200L,
            Arrays.asList(
                new AnimClip.FaceKeyframe(0L, Personality.Expression.SURPRISED, 140L, 0f, -1f),
                new AnimClip.FaceKeyframe(350L, Personality.Expression.HAPPY, 220L, 0f, 0f),
                new AnimClip.FaceKeyframe(1200L, Personality.Expression.EXCITED, 240L, 1.5f, 0f)
            ),
            Collections.singletonList(new AnimClip.AudioCue(120L, "happy", "ciao!")),
            Arrays.asList(new AnimClip.LedKeyframe(0L, "cyan", false), new AnimClip.LedKeyframe(400L, "green", true)),
            Arrays.asList(
                new AnimClip.MotorGesture(100L, 0.16f, 14f, 0f, 0f, 0L),
                new AnimClip.MotorGesture(950L, -0.10f, -14f, 0f, 0f, 0L)
            )
        ));

        register(new AnimClip("GREET_STRANGER", AnimClip.Priority.SOCIAL_REACTION, 2000L,
            Arrays.asList(
                new AnimClip.FaceKeyframe(0L, Personality.Expression.CURIOUS, 180L, -2f, 0f),
                new AnimClip.FaceKeyframe(700L, Personality.Expression.HAPPY, 240L, 1.5f, 0f)
            ),
            Collections.singletonList(new AnimClip.AudioCue(150L, "curious", "chi sei?")),
            Collections.singletonList(new AnimClip.LedKeyframe(0L, "cyan", false)),
            Arrays.asList(
                new AnimClip.MotorGesture(150L, 0.14f, 10f, 0f, 0f, 0L),
                new AnimClip.MotorGesture(1050L, -0.08f, -10f, 0f, 0f, 0L)
            )
        ));

        register(new AnimClip("SURPRISED_PICKUP", AnimClip.Priority.SAFETY_REFLEX, 1800L,
            Arrays.asList(
                new AnimClip.FaceKeyframe(0L, Personality.Expression.SURPRISED, 90L, 0f, -2f),
                new AnimClip.FaceKeyframe(800L, Personality.Expression.SKEPTICAL, 220L, 2f, 0f)
            ),
            Collections.singletonList(new AnimClip.AudioCue(0L, "surprised", "")),
            Collections.singletonList(new AnimClip.LedKeyframe(0L, "amber", true)),
            Collections.emptyList()
        ));

        register(new AnimClip("CLIFF_RECOIL", AnimClip.Priority.SAFETY_REFLEX, 1600L,
            Arrays.asList(
                new AnimClip.FaceKeyframe(0L, Personality.Expression.SURPRISED, 80L, 0f, 2f),
                new AnimClip.FaceKeyframe(650L, Personality.Expression.FOCUSED, 200L, 0f, 0f)
            ),
            Collections.singletonList(new AnimClip.AudioCue(0L, "surprised", "oh!")),
            Collections.singletonList(new AnimClip.LedKeyframe(0L, "red", true)),
            Collections.singletonList(new AnimClip.MotorGesture(50L, -0.08f, 0f, 0f, 0f, 0L))
        ));

        register(new AnimClip("HAPPY_COMFORT", AnimClip.Priority.USER_DIRECT, 2000L,
            Arrays.asList(
                new AnimClip.FaceKeyframe(0L, Personality.Expression.HAPPY, 200L, 0f, 0.5f),
                new AnimClip.FaceKeyframe(1000L, Personality.Expression.SLEEPY, 320L, 0f, 1.0f)
            ),
            Collections.singletonList(new AnimClip.AudioCue(80L, "happy", "")),
            Collections.singletonList(new AnimClip.LedKeyframe(0L, "green", false)),
            Arrays.asList(
                new AnimClip.MotorGesture(120L, 0.13f, 8f, 0f, 0f, 0L),
                new AnimClip.MotorGesture(1000L, -0.08f, -8f, 0f, 0f, 0L)
            )
        ));

        register(new AnimClip("PLAY_CHEER", AnimClip.Priority.USER_DIRECT, 2200L,
            Arrays.asList(
                new AnimClip.FaceKeyframe(0L, Personality.Expression.EXCITED, 140L, -2f, -1f),
                new AnimClip.FaceKeyframe(700L, Personality.Expression.HAPPY, 180L, 2f, -1f),
                new AnimClip.FaceKeyframe(1400L, Personality.Expression.EXCITED, 180L, 0f, 0f)
            ),
            Collections.singletonList(new AnimClip.AudioCue(60L, "excited", "evviva!")),
            Collections.singletonList(new AnimClip.LedKeyframe(0L, "purple", true)),
            Arrays.asList(
                new AnimClip.MotorGesture(80L, 0.16f, 15f, 28f, -28f, 180L),
                new AnimClip.MotorGesture(850L, -0.12f, -15f, -28f, 28f, 180L)
            )
        ));

        register(new AnimClip("BORED_SIGH", AnimClip.Priority.IDLE_AUTONOMOUS, 2400L,
            Arrays.asList(
                new AnimClip.FaceKeyframe(0L, Personality.Expression.SKEPTICAL, 260L, -3f, 1.5f),
                new AnimClip.FaceKeyframe(1000L, Personality.Expression.SAD, 320L, 0f, 2.5f)
            ),
            Collections.singletonList(new AnimClip.AudioCue(180L, "sigh", "")),
            Collections.singletonList(new AnimClip.LedKeyframe(0L, "amber", false)),
            Collections.singletonList(new AnimClip.MotorGesture(200L, -0.12f, -6f, 0f, 0f, 0L))
        ));

        register(new AnimClip("SLEEPY_CHARGER", AnimClip.Priority.IDLE_AUTONOMOUS, 2800L,
            Arrays.asList(
                new AnimClip.FaceKeyframe(0L, Personality.Expression.SLEEPY, 400L, 0f, 2.5f),
                new AnimClip.FaceKeyframe(1600L, Personality.Expression.SLEEPY, 400L, -1f, 3.0f)
            ),
            Collections.singletonList(new AnimClip.AudioCue(250L, "lullaby", "")),
            Collections.singletonList(new AnimClip.LedKeyframe(0L, "off", false)),
            Collections.emptyList()
        ));

        register(new AnimClip("CURIOUS_INSPECT", AnimClip.Priority.IDLE_AUTONOMOUS, 2100L,
            Arrays.asList(
                new AnimClip.FaceKeyframe(0L, Personality.Expression.CURIOUS, 160L, -3.5f, -0.5f),
                new AnimClip.FaceKeyframe(650L, Personality.Expression.FOCUSED, 180L, 3.5f, 0.5f),
                new AnimClip.FaceKeyframe(1350L, Personality.Expression.CURIOUS, 200L, 0f, 0f)
            ),
            Collections.singletonList(new AnimClip.AudioCue(120L, "curious", "")),
            Collections.singletonList(new AnimClip.LedKeyframe(0L, "cyan", false)),
            Arrays.asList(
                new AnimClip.MotorGesture(120L, 0.14f, 10f, 20f, 20f, 160L),
                new AnimClip.MotorGesture(950L, -0.12f, -10f, 0f, 0f, 0L)
            )
        ));

        register(new AnimClip("CUBE_VICTORY", AnimClip.Priority.SOCIAL_REACTION, 2300L,
            Arrays.asList(
                new AnimClip.FaceKeyframe(0L, Personality.Expression.EXCITED, 130L, 0f, -1.5f),
                new AnimClip.FaceKeyframe(850L, Personality.Expression.HAPPY, 200L, 0f, 0f)
            ),
            Collections.singletonList(new AnimClip.AudioCue(40L, "fanfare", "")),
            Arrays.asList(new AnimClip.LedKeyframe(0L, "green", true), new AnimClip.LedKeyframe(800L, "purple", true)),
            Arrays.asList(
                new AnimClip.MotorGesture(60L, 0.17f, 15f, 0f, 0f, 0L),
                new AnimClip.MotorGesture(850L, -0.11f, -15f, 0f, 0f, 0L)
            )
        ));
    }

    public synchronized void register(AnimClip clip) {
        if (clip != null) library.put(clip.id.toUpperCase(Locale.ROOT), clip);
    }

    public synchronized AnimClip getClip(String id) {
        if (id == null) return null;
        return library.get(id.trim().toUpperCase(Locale.ROOT));
    }

    public synchronized Map<String, AnimClip> allClips() {
        return new LinkedHashMap<>(library);
    }

    public synchronized AnimClip activeClip() {
        return activeClip;
    }

    public synchronized void stopAll() {
        activeClip = null;
        pendingStartedId = null;
        lastEvaluatedOffsetMs = -1;
        activeMuteAudio = false;
        activeMuteFace = false;
    }

    public synchronized boolean startClip(String clipId, long nowMs) {
        return startClip(getClip(clipId), null, nowMs, false, false);
    }

    public synchronized boolean startClip(String clipId, AnimClip.Priority overridePriority, long nowMs) {
        return startClip(getClip(clipId), overridePriority, nowMs, false, false);
    }

    public synchronized boolean startClip(String clipId, AnimClip.Priority overridePriority, long nowMs,
                                          boolean muteAudio, boolean muteFace) {
        return startClip(getClip(clipId), overridePriority, nowMs, muteAudio, muteFace);
    }

    public synchronized boolean startClip(AnimClip clip, AnimClip.Priority overridePriority, long nowMs) {
        return startClip(clip, overridePriority, nowMs, false, false);
    }

    public synchronized boolean startClip(AnimClip clip, AnimClip.Priority overridePriority, long nowMs,
                                          boolean muteAudio, boolean muteFace) {
        if (clip == null) return false;
        AnimClip.Priority incomingPrio = overridePriority != null ? overridePriority : clip.priority;
        if (activeClip != null && nowMs - activeStartMs < activeClip.durationMs) {
            if (incomingPrio.level < activeClip.priority.level ||
                (incomingPrio.level == activeClip.priority.level && incomingPrio.level < AnimClip.Priority.USER_DIRECT.level)) {
                return false;
            }
        }
        activeClip = overridePriority == null ? clip : new AnimClip(
            clip.id, overridePriority, clip.durationMs, clip.faceTrack, clip.audioTrack, clip.ledTrack, clip.motorTrack);
        activeStartMs = nowMs;
        lastEvaluatedOffsetMs = -1;
        pendingStartedId = activeClip.id;
        activeMuteAudio = muteAudio;
        activeMuteFace = muteFace;
        return true;
    }

    public synchronized ChoreographyStep tick(long nowMs, Personality.Profile profile,
                                              int queuedAudioFrames, boolean allowAudio) {
        if (activeClip == null) {
            return new ChoreographyStep(null, null, null, null, null, false, false, null, null);
        }
        long elapsed = Math.max(0L, nowMs - activeStartMs);
        String started = pendingStartedId;
        pendingStartedId = null;

        AnimClip.FaceKeyframe face = null;
        if (!activeMuteFace) {
            for (AnimClip.FaceKeyframe fk : activeClip.faceTrack) {
                if (fk.offsetMs > lastEvaluatedOffsetMs && fk.offsetMs <= elapsed) {
                    face = fk;
                }
            }
        }

        String led = null;
        for (AnimClip.LedKeyframe lk : activeClip.ledTrack) {
            if (lk.offsetMs > lastEvaluatedOffsetMs && lk.offsetMs <= elapsed) {
                led = lk.color;
            }
        }

        AnimClip.MotorGesture motor = null;
        for (AnimClip.MotorGesture mg : activeClip.motorTrack) {
            if (mg.offsetMs > lastEvaluatedOffsetMs && mg.offsetMs <= elapsed) {
                motor = mg;
            }
        }

        List<byte[]> audioFrames = null;
        boolean skippedBackpressure = false;
        boolean skippedCooldown = false;
        if (!activeMuteAudio) {
            for (AnimClip.AudioCue ac : activeClip.audioTrack) {
                if (ac.offsetMs > lastEvaluatedOffsetMs && ac.offsetMs <= elapsed) {
                    if (!allowAudio) {
                        break;
                    }
                    if (queuedAudioFrames > AUDIO_BACKPRESSURE_GUARD_FRAMES) {
                        skippedBackpressure = true;
                        break;
                    }
                    boolean isAutonomous = activeClip.priority.level <= AnimClip.Priority.SOCIAL_REACTION.level;
                    if (isAutonomous && (nowMs - lastAutonomousAudioMs) < AUTONOMOUS_AUDIO_COOLDOWN_MS) {
                        skippedCooldown = true;
                        break;
                    }
                    AudioSynth.VoiceProfile vp = AudioSynth.VoiceProfile.fromProfile(profile);
                    Personality.Expression expr = face != null ? face.expression : Personality.Expression.NEUTRAL;
                    audioFrames = (ac.sayText != null && !ac.sayText.isEmpty())
                        ? AudioSynth.speakText(ac.sayText, vp, expr)
                        : AudioSynth.preset(ac.preset, vp);
                    if (isAutonomous) {
                        lastAutonomousAudioMs = nowMs;
                    }
                }
            }
        }

        lastEvaluatedOffsetMs = elapsed;
        String activeId = activeClip.id;
        String endedId = null;
        if (elapsed >= activeClip.durationMs) {
            endedId = activeClip.id;
            activeClip = null;
            lastEvaluatedOffsetMs = -1;
            activeMuteAudio = false;
            activeMuteFace = false;
        }
        return new ChoreographyStep(activeId, started, endedId, face, audioFrames,
            skippedBackpressure, skippedCooldown, led, motor);
    }
}
