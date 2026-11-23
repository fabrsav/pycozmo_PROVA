package dev.s200.cozmo.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Local AI Intention Supervisor & Offline Italian Cognitive Engine.
 * Validates, clamps, and expires structured AI intentions (including whitelisted Choreographer clips
 * and gated drive requests), builds compact (<1 KB) AiContextSnapshots, and sanitizes logs against secret leaks.
 * Pure Java 11, zero Android dependencies.
 */
public final class AiSupervisor {
    public static final float MAX_HEAD_DELTA_RAD = 0.18f; // ~10.3 deg
    public static final long MIN_TTL_MS = 800L;
    public static final long MAX_TTL_MS = 5000L;

    public static final Set<String> WHITELISTED_CLIPS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "GREET_FAMILIAR", "GREET_STRANGER", "SURPRISED_PICKUP", "CLIFF_RECOIL",
        "HAPPY_COMFORT", "PLAY_CHEER", "BORED_SIGH", "SLEEPY_CHARGER",
        "CURIOUS_INSPECT", "CUBE_VICTORY"
    )));

    public static final class AiContextSnapshot {
        public final Personality.Profile profile;
        public final NeedsModel.Bracket energyBracket;
        public final NeedsModel.Bracket playBracket;
        public final NeedsModel.Bracket comfortBracket;
        public final String dominantEmotion;
        public final String personName;
        public final float familiarity;
        public final List<String> recentEvents;
        public final boolean onCharger;
        public final boolean unsafe;
        public final boolean armed;
        public final boolean darkScene;
        public final int facesCount;
        public final boolean allowAutonomousDrive;
        public final String userPrompt;

        public AiContextSnapshot(Personality.Profile profile,
                                 NeedsModel.Bracket energyBracket,
                                 NeedsModel.Bracket playBracket,
                                 NeedsModel.Bracket comfortBracket,
                                 String dominantEmotion,
                                 String personName,
                                 float familiarity,
                                 List<String> recentEvents,
                                 boolean onCharger,
                                 boolean unsafe,
                                 boolean armed,
                                 boolean darkScene,
                                 int facesCount,
                                 boolean allowAutonomousDrive,
                                 String userPrompt) {
            this.profile = profile == null ? Personality.Profile.CURIOSO : profile;
            this.energyBracket = energyBracket == null ? NeedsModel.Bracket.NORMAL : energyBracket;
            this.playBracket = playBracket == null ? NeedsModel.Bracket.NORMAL : playBracket;
            this.comfortBracket = comfortBracket == null ? NeedsModel.Bracket.NORMAL : comfortBracket;
            this.dominantEmotion = dominantEmotion == null ? "calm" : dominantEmotion;
            this.personName = personName == null ? "" : personName;
            this.familiarity = Math.max(0f, Math.min(1f, familiarity));
            this.recentEvents = recentEvents == null ? Collections.emptyList() : recentEvents;
            this.onCharger = onCharger;
            this.unsafe = unsafe;
            this.armed = armed;
            this.darkScene = darkScene;
            this.facesCount = Math.max(0, facesCount);
            this.allowAutonomousDrive = allowAutonomousDrive;
            this.userPrompt = userPrompt == null ? "" : userPrompt;
        }

        public String toCompactJson() {
            StringBuilder ev = new StringBuilder("[");
            int limit = Math.min(3, recentEvents.size());
            for (int i = 0; i < limit; i++) {
                if (i > 0) ev.append(',');
                String item = recentEvents.get(i);
                if (item != null && item.length() > 60) item = item.substring(0, 60);
                ev.append('"').append(escape(item)).append('"');
            }
            ev.append(']');
            String promptTrim = userPrompt.length() > 140 ? userPrompt.substring(0, 140) : userPrompt;
            String json = String.format(Locale.ROOT,
                "{\"profile\":\"%s\",\"needs\":{\"energy\":\"%s\",\"play\":\"%s\",\"comfort\":\"%s\"}," +
                "\"emotion\":\"%s\",\"person\":\"%s\",\"familiarity\":%.2f,\"events\":%s," +
                "\"state\":{\"charger\":%b,\"unsafe\":%b,\"armed\":%b,\"dark\":%b,\"faces\":%d,\"allow_drive\":%b}," +
                "\"prompt\":\"%s\"}",
                profile.name(), energyBracket.name(), playBracket.name(), comfortBracket.name(),
                escape(dominantEmotion), escape(personName), familiarity, ev.toString(),
                onCharger, unsafe, armed, darkScene, facesCount, allowAutonomousDrive,
                escape(promptTrim));
            return json.length() <= 1000 ? json : json.substring(0, 1000);
        }

        private static String escape(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
        }
    }

    public static final class Intention {
        public final String clipId;
        public final Personality.Expression expression;
        public final String vocalization;
        public final String sayText;
        public final String backpackColor;
        public final float headDeltaRad;
        public final float driveLeft;
        public final float driveRight;
        public final long driveDurationMs;
        public final long createdAtMs;
        public final long expiresAtMs;
        public final String reason;
        public final String source;

        public Intention(Personality.Expression expression, String vocalization, String sayText,
                         String backpackColor, float headDeltaRad, long createdAtMs, long ttlMs,
                         String reason, String source) {
            this(null, expression, vocalization, sayText, backpackColor, headDeltaRad,
                0f, 0f, 0L, createdAtMs, ttlMs, reason, source);
        }

        public Intention(String clipId, Personality.Expression expression, String vocalization, String sayText,
                         String backpackColor, float headDeltaRad, float driveLeft, float driveRight,
                         long driveDurationMs, long createdAtMs, long ttlMs,
                         String reason, String source) {
            this.clipId = sanitizeClip(clipId);
            this.expression = expression == null ? Personality.Expression.NEUTRAL : expression;
            this.vocalization = sanitizeVocal(vocalization);
            this.sayText = sanitizeSay(sayText);
            this.backpackColor = sanitizeColor(backpackColor);
            this.headDeltaRad = Safety.limit(Float.isFinite(headDeltaRad) ? headDeltaRad : 0f, -MAX_HEAD_DELTA_RAD, MAX_HEAD_DELTA_RAD);
            this.driveLeft = Safety.limit(Float.isFinite(driveLeft) ? driveLeft : 0f, -40f, 40f);
            this.driveRight = Safety.limit(Float.isFinite(driveRight) ? driveRight : 0f, -40f, 40f);
            this.driveDurationMs = Math.max(0L, Math.min(1500L, driveDurationMs));
            this.createdAtMs = createdAtMs;
            long clampedTtl = Math.max(MIN_TTL_MS, Math.min(MAX_TTL_MS, ttlMs));
            this.expiresAtMs = createdAtMs + clampedTtl;
            this.reason = reason == null || reason.isEmpty() ? "Intenzione validata" : reason;
            this.source = source == null ? "locale" : source;
        }

        public boolean expired(long nowMs) {
            return nowMs > expiresAtMs;
        }

        public String summary(long nowMs) {
            long rem = Math.max(0L, expiresAtMs - nowMs);
            String clipPart = clipId != null && !clipId.isEmpty() ? " · clip=" + clipId : "";
            return String.format(Locale.ITALY,
                "[%s] %s%s · voce=%s%s · zaino=%s · dTesta=%+.1f° · scade=%d ms\nMotivo: %s",
                source, expression.label, clipPart, vocalization,
                sayText.isEmpty() ? "" : " (\"" + sayText + "\")",
                backpackColor, Math.toDegrees(headDeltaRad), rem, reason);
        }
    }

    public static Intention validateJson(String raw, long nowMs, String source) {
        return validateJson(raw, nowMs, source, false);
    }

    public static Intention validateJson(String raw, long nowMs, String source, boolean allowAutonomousDrive) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        String json = raw.substring(start, end + 1);

        String lower = json.toLowerCase(Locale.ROOT);
        if (lower.contains("\"drive_wheels\"") || lower.contains("\"raw_packet\"")) {
            return null;
        }

        boolean hasDriveField = lower.contains("\"drive\"") || lower.contains("\"vl\"") || lower.contains("\"vr\"") || lower.contains("\"speed\"");
        float speed = extractFloat(json, "speed", Float.NaN);
        float vl = extractFloat(json, "vl", Float.isFinite(speed) ? speed : 0f);
        float vr = extractFloat(json, "vr", Float.isFinite(speed) ? speed : 0f);
        long driveDurMs = (long) extractFloat(json, "duration_ms", extractFloat(json, "duration_s", 0f) * 1000f);

        if (hasDriveField && (Math.abs(vl) > 0.01f || Math.abs(vr) > 0.01f) && !allowAutonomousDrive) {
            return null;
        }

        String clipStr = extractString(json, "clip", "");
        if (!clipStr.isEmpty() && sanitizeClip(clipStr) == null) {
            return null;
        }

        String exprStr = extractString(json, "expression", "NEUTRAL");
        String vocalStr = extractString(json, "vocalization", "curious");
        String sayStr = extractString(json, "say", "");
        String colorStr = extractString(json, "backpack", "cyan");
        String reasonStr = extractString(json, "reason", "Risposta modello validata dal supervisore");
        float headDeg = extractFloat(json, "head_delta_deg", 0f);
        long ttlMs = (long) extractFloat(json, "expires_ms", 3000f);

        float headRad = (float) Math.toRadians(Safety.limit(headDeg, -10.0f, 10.0f));
        Personality.Expression expr = Personality.Expression.fromName(exprStr);
        return new Intention(clipStr, expr, vocalStr, sayStr, colorStr, headRad,
            allowAutonomousDrive ? vl : 0f,
            allowAutonomousDrive ? vr : 0f,
            allowAutonomousDrive ? driveDurMs : 0L,
            nowMs, ttlMs, reasonStr, source);
    }

    public static Intention localDecide(String userPrompt, Wire.Telemetry t, Personality p,
                                        VisionAnalyzer.Result vision, Cubes cubes, String knownPerson, long nowMs) {
        Personality.PersonalitySnapshot snap = p != null ? p.snapshot(nowMs)
            : new Personality().snapshot(nowMs);
        AiContextSnapshot ctx = new AiContextSnapshot(
            snap.profile, snap.energyBracket, snap.playBracket, snap.comfortBracket,
            snap.dominantEmotion,
            knownPerson != null && !knownPerson.isEmpty() ? knownPerson : snap.recognizedPerson,
            snap.familiarity, Collections.emptyList(),
            t != null && t.onCharger(), t != null && t.unsafe(), false,
            vision != null && vision.darkScene,
            vision != null ? vision.faceCount : 0,
            false, userPrompt
        );
        boolean cubeAct = cubes != null && cubes.anyRecentActivity(nowMs);
        return localDecide(ctx, userPrompt, cubeAct, nowMs);
    }

    public static Intention localDecide(AiContextSnapshot ctx, String userPrompt, long nowMs) {
        return localDecide(ctx, userPrompt, false, nowMs);
    }

    public static Intention localDecide(AiContextSnapshot ctx, String userPrompt, boolean cubeActive, long nowMs) {
        String q = userPrompt == null ? "" : userPrompt.trim().toLowerCase(Locale.ITALY);
        Personality.Profile prof = ctx != null ? ctx.profile : Personality.Profile.CURIOSO;
        String knownPerson = ctx != null && !ctx.personName.isEmpty() ? ctx.personName : null;

        if (ctx != null && ctx.unsafe) {
            return new Intention("SURPRISED_PICKUP", Personality.Expression.SURPRISED, "surprised", "", "amber",
                0f, 0f, 0f, 0L, nowMs, 2500L, "Robot sollevato o sensore caduta attivo: priorità sicurezza", "locale-sicurezza");
        }
        // 1. Canto / musica
        if (containsAny(q, "cant", "musica", "melodia", "canzon", "fischiett")) {
            return new Intention("PLAY_CHEER", Personality.Expression.EXCITED, "sing", "", "purple",
                0.05f, 0f, 0f, 0L, nowMs, 3500L, "Richiesta musicale: melodia Cozmo e sguardo entusiasta", "locale");
        }
        // 2. Saluti
        if (containsAny(q, "salut", "ciao", "ehi", "buongiorno", "buonasera", "eccomi")) {
            String greet = knownPerson != null ? "ciao " + knownPerson + "!" : "ciao amico!";
            String clip = knownPerson != null ? "GREET_FAMILIAR" : "GREET_STRANGER";
            return new Intention(clip, Personality.Expression.HAPPY, "happy", greet, "cyan",
                0.06f, 0f, 0f, 0L, nowMs, 3200L,
                "Saluto amichevole" + (knownPerson != null ? " a " + knownPerson : ""), "locale");
        }
        // 3. Coccole / affetto
        if (containsAny(q, "coccol", "bravo", "ti voglio bene", "carezz", "dolce", "comfort", "affett")) {
            String say = prof == Personality.Profile.AFFETTUOSO ? "grazie!" : "che bello!";
            return new Intention("HAPPY_COMFORT", Personality.Expression.HAPPY, "happy", say, "green",
                0.04f, 0f, 0f, 0L, nowMs, 3200L, "Interazione affettuosa: risposta felice", "locale");
        }
        // 4. Sonno / riposo
        if (containsAny(q, "dorm", "sonno", "notte", "ripos", "stanco") ||
            (ctx != null && ctx.energyBracket == NeedsModel.Bracket.CRITICAL)) {
            return new Intention("SLEEPY_CHARGER", Personality.Expression.SLEEPY, "sleepy", "notte...", "off",
                -0.05f, 0f, 0f, 0L, nowMs, 3500L, "Stato di riposo o energia bassa", "locale");
        }
        // 5. Gioco / cubi
        if (containsAny(q, "cub", "gioc", "quick tap", "partita", "divert") || cubeActive) {
            return new Intention("CUBE_VICTORY", Personality.Expression.EXCITED, "excited", "giochiamo!", "green",
                -0.04f, 0f, 0f, 0L, nowMs, 3000L, "Attenzione rivolta al gioco e ai cubi luminosi", "locale");
        }
        // 6. Stato / come stai
        if (containsAny(q, "come stai", "tutto bene", "stato", "umore", "bisogn", "batteria")) {
            if (ctx != null && (ctx.playBracket == NeedsModel.Bracket.LOW || ctx.playBracket == NeedsModel.Bracket.CRITICAL)) {
                return new Intention("BORED_SIGH", Personality.Expression.SKEPTICAL, "sad", "giochiamo?", "amber",
                    -0.03f, 0f, 0f, 0L, nowMs, 3000L, "Stato bisogni: Play basso", "locale");
            }
            String moodSay = prof == Personality.Profile.BIRICHINO ? "pronto!" : "tutto bene!";
            return new Intention("HAPPY_COMFORT", Personality.Expression.HAPPY, "happy", moodSay, prof.defaultBackpack,
                0.04f, 0f, 0f, 0L, nowMs, 3000L, "Resoconto umore positivo (" + prof.label + ")", "locale");
        }
        // 7. Curiosità / esplorazione
        if (containsAny(q, "guard", "esplor", "cosa vedi", "intorno", "cerca", "curios")) {
            return new Intention("CURIOUS_INSPECT", Personality.Expression.CURIOUS, "curious", "guardo!", "cyan",
                0.06f, 0f, 0f, 0L, nowMs, 3000L, "Ispezione curiosa dell'ambiente", "locale");
        }

        // Vision-triggered fallback when no specific keyword matched
        if (ctx != null && ctx.facesCount > 0) {
            String greet = knownPerson != null ? "ciao " + knownPerson : "ti vedo!";
            String clip = knownPerson != null ? "GREET_FAMILIAR" : "GREET_STRANGER";
            return new Intention(clip, Personality.Expression.HAPPY, "happy", greet, "cyan",
                0.04f, 0f, 0f, 0L, nowMs, 3000L, "Volto rilevato dalla camera di Cozmo", "locale-visione");
        }
        if (ctx != null && ctx.darkScene) {
            return new Intention("CURIOUS_INSPECT", Personality.Expression.CURIOUS, "curious", "buio?", "cyan",
                0.03f, 0f, 0f, 0L, nowMs, 3000L, "Ambiente al buio rilevato dalla camera: curiosità attiva", "locale-visione");
        }
        if (!q.isEmpty()) {
            String shortSay = q.length() > 18 ? q.substring(0, 18) : q;
            return new Intention("CURIOUS_INSPECT", Personality.Expression.CURIOUS, "curious", shortSay, prof.defaultBackpack,
                0.03f, 0f, 0f, 0L, nowMs, 3000L, "Risposta vocale ed espressiva a: " + q, "locale");
        }
        if (prof == Personality.Profile.BIRICHINO) {
            return new Intention("CURIOUS_INSPECT", Personality.Expression.SKEPTICAL, "curious", "", "purple",
                0.04f, 0f, 0f, 0L, nowMs, 2500L, "Carattere birichino: osservazione vigile", "locale");
        }
        if (prof == Personality.Profile.AFFETTUOSO) {
            return new Intention("HAPPY_COMFORT", Personality.Expression.HAPPY, "happy", "", "green",
                0.02f, 0f, 0f, 0L, nowMs, 2500L, "Carattere affettuoso: presenza serena", "locale");
        }
        if (prof == Personality.Profile.TRANQUILLO) {
            return new Intention("SLEEPY_CHARGER", Personality.Expression.NEUTRAL, "none", "", "amber",
                0f, 0f, 0f, 0L, nowMs, 2500L, "Carattere tranquillo: riposo silenzioso", "locale");
        }
        return new Intention("CURIOUS_INSPECT", Personality.Expression.CURIOUS, "curious", "", "cyan",
            0.03f, 0f, 0f, 0L, nowMs, 2500L, "Carattere curioso: esplorazione dell'ambiente", "locale");
    }

    /**
     * Strips any API key value or URL query parameter (?key=...) before logging or displaying errors.
     */
    public static String sanitizeForLog(String rawMessage, String secretKey) {
        if (rawMessage == null) return "";
        String out = rawMessage.replaceAll("([?&]key=)[^&\\s\"']+", "$1[REDACTED]");
        if (secretKey != null && !secretKey.trim().isEmpty()) {
            out = out.replace(secretKey.trim(), "[REDACTED]");
        }
        return out;
    }

    private static boolean containsAny(String text, String... tokens) {
        if (text == null || text.isEmpty()) return false;
        for (String tok : tokens) {
            if (text.contains(tok)) return true;
        }
        return false;
    }

    public static String sanitizeClip(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String up = raw.trim().toUpperCase(Locale.ROOT);
        return WHITELISTED_CLIPS.contains(up) ? up : null;
    }

    private static String sanitizeVocal(String v) {
        if (v == null) return "none";
        String s = v.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "curious":
            case "happy":
            case "excited":
            case "surprised":
            case "sleepy":
            case "sad":
            case "sing":
            case "fanfare":
            case "lullaby":
            case "sigh":
            case "none":
                return s;
            default:
                return "curious";
        }
    }

    private static String sanitizeColor(String c) {
        if (c == null) return "cyan";
        String s = c.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("#") && s.length() == 7) {
            return s;
        }
        switch (s) {
            case "cyan":
            case "green":
            case "amber":
            case "purple":
            case "red":
            case "off":
                return s;
            default:
                return "cyan";
        }
    }

    private static String sanitizeSay(String s) {
        if (s == null) return "";
        String cleaned = s.replaceAll("[^\\p{L}\\p{N} ?!,.-]", "").trim();
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }

    private static String extractString(String json, String key, String def) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return def;
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon < 0) return def;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return def;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return def;
        return json.substring(q1 + 1, q2).trim();
    }

    private static float extractFloat(String json, String key, float def) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return def;
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon < 0) return def;
        int i = colon + 1;
        while (i < json.length() && (Character.isWhitespace(json.charAt(i)) || json.charAt(i) == '"')) i++;
        int start = i;
        while (i < json.length() && ("0123456789+-.".indexOf(json.charAt(i)) >= 0)) i++;
        if (i <= start) return def;
        try {
            float val = Float.parseFloat(json.substring(start, i));
            return Float.isFinite(val) ? val : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
