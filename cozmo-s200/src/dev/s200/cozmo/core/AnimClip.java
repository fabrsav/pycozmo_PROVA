package dev.s200.cozmo.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Declarative multi-modal choreography clip combining face expressions/gaze,
 * audio cues, backpack LED colors, and bounded motor gestures.
 * Pure Java 11, zero Android dependencies.
 */
public final class AnimClip {
    public enum Priority {
        IDLE_AUTONOMOUS(1),
        AI_ACTION(2),
        SOCIAL_REACTION(3),
        USER_DIRECT(4),
        SAFETY_REFLEX(5);

        public final int level;
        Priority(int level) { this.level = level; }
    }

    public static final class FaceKeyframe {
        public final long offsetMs;
        public final Personality.Expression expression;
        public final long transitionMs;
        public final float gazeX, gazeY;

        public FaceKeyframe(long offsetMs, Personality.Expression expression, long transitionMs, float gazeX, float gazeY) {
            this.offsetMs = Math.max(0L, offsetMs);
            this.expression = expression == null ? Personality.Expression.NEUTRAL : expression;
            this.transitionMs = Math.max(60L, transitionMs);
            this.gazeX = Math.max(-7.5f, Math.min(7.5f, gazeX));
            this.gazeY = Math.max(-4.0f, Math.min(4.0f, gazeY));
        }
    }

    public static final class AudioCue {
        public final long offsetMs;
        public final String preset;
        public final String sayText;

        public AudioCue(long offsetMs, String preset, String sayText) {
            this.offsetMs = Math.max(0L, offsetMs);
            this.preset = preset == null ? "curious" : preset;
            this.sayText = sayText == null ? "" : sayText;
        }
    }

    public static final class LedKeyframe {
        public final long offsetMs;
        public final String color;
        public final boolean pulse;

        public LedKeyframe(long offsetMs, String color, boolean pulse) {
            this.offsetMs = Math.max(0L, offsetMs);
            this.color = color == null ? "cyan" : color;
            this.pulse = pulse;
        }
    }

    public static final class MotorGesture {
        public final long offsetMs;
        public final float headDeltaRad;
        public final float liftDeltaMm;
        public final float wheelLeft;
        public final float wheelRight;
        public final long wheelDurationMs;

        public MotorGesture(long offsetMs, float headDeltaRad, float liftDeltaMm,
                            float wheelLeft, float wheelRight, long wheelDurationMs) {
            this.offsetMs = Math.max(0L, offsetMs);
            this.headDeltaRad = Safety.limit(headDeltaRad, -0.18f, 0.18f);
            this.liftDeltaMm = Safety.limit(liftDeltaMm, -15f, 15f);
            this.wheelLeft = Safety.limit(wheelLeft, -40f, 40f);
            this.wheelRight = Safety.limit(wheelRight, -40f, 40f);
            this.wheelDurationMs = Math.max(0L, Math.min(250L, wheelDurationMs));
        }
    }

    public final String id;
    public final Priority priority;
    public final long durationMs;
    public final List<FaceKeyframe> faceTrack;
    public final List<AudioCue> audioTrack;
    public final List<LedKeyframe> ledTrack;
    public final List<MotorGesture> motorTrack;

    public AnimClip(String id, Priority priority, long durationMs,
                    List<FaceKeyframe> faceTrack, List<AudioCue> audioTrack,
                    List<LedKeyframe> ledTrack, List<MotorGesture> motorTrack) {
        this.id = id == null ? "CLIP" : id;
        this.priority = priority == null ? Priority.IDLE_AUTONOMOUS : priority;
        this.durationMs = Math.max(300L, durationMs);
        this.faceTrack = Collections.unmodifiableList(new ArrayList<>(faceTrack == null ? Collections.emptyList() : faceTrack));
        this.audioTrack = Collections.unmodifiableList(new ArrayList<>(audioTrack == null ? Collections.emptyList() : audioTrack));
        this.ledTrack = Collections.unmodifiableList(new ArrayList<>(ledTrack == null ? Collections.emptyList() : ledTrack));
        this.motorTrack = Collections.unmodifiableList(new ArrayList<>(motorTrack == null ? Collections.emptyList() : motorTrack));
    }
}
