package dev.s200.cozmo.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Lightweight WebSocket client for Gemini Live API (BidiGenerateContent)
 * supporting injected SocketFactory (for strict Android Internet Network isolation away from wlan1),
 * explicit connect (5s) and read (8s) timeouts, session metrics, and secret-safe error reporting.
 * Pure Java 11, zero Android dependencies.
 */
public final class GeminiLiveClient {
    public static final String DEFAULT_MODEL = "models/gemini-robotics-er-2-streaming-preview";
    public static final String DEFAULT_HOST = "generativelanguage.googleapis.com";
    public static final int DEFAULT_PORT = 443;
    public static final int CONNECT_TIMEOUT_MS = 5000;
    public static final int READ_TIMEOUT_MS = 8000;
    public static final String WS_PATH_PREFIX = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";

    public enum AiTurnPolicy {
        OFFLINE_ONLY("Solo locale offline"),
        ON_DEMAND("Gemini su richiesta"),
        AMBIENT_HYBRID("Gemini ambientale (45 s)");

        public final String labelIt;
        AiTurnPolicy(String labelIt) { this.labelIt = labelIt; }

        public static AiTurnPolicy fromName(String raw) {
            if (raw == null) return ON_DEMAND;
            for (AiTurnPolicy p : values()) {
                if (p.name().equalsIgnoreCase(raw.trim()) || p.labelIt.equalsIgnoreCase(raw.trim())) return p;
            }
            return ON_DEMAND;
        }
    }

    private static final AtomicInteger turnsCount = new AtomicInteger(0);
    private static final AtomicLong lastLatencyMs = new AtomicLong(-1L);
    private static volatile String lastErrorCode = "OK";

    public static int turnsCount() { return turnsCount.get(); }
    public static long lastLatencyMs() { return lastLatencyMs.get(); }
    public static String lastErrorCode() { return lastErrorCode; }

    public static final class LiveAction {
        public final String name;
        public final String clip;
        public final String expression;
        public final String vocalization;
        public final String say;
        public final String backpack;
        public final float headDeltaDeg;
        public final float driveLeft;
        public final float driveRight;
        public final float durationS;
        public final String reason;
        public final String rawText;

        public LiveAction(String name, String expression, String vocalization, String say,
                          String backpack, float headDeltaDeg, float driveLeft, float driveRight,
                          float durationS, String reason, String rawText) {
            this(name, "", expression, vocalization, say, backpack, headDeltaDeg, driveLeft, driveRight, durationS, reason, rawText);
        }

        public LiveAction(String name, String clip, String expression, String vocalization, String say,
                          String backpack, float headDeltaDeg, float driveLeft, float driveRight,
                          float durationS, String reason, String rawText) {
            this.name = name;
            this.clip = clip == null ? "" : clip;
            this.expression = expression;
            this.vocalization = vocalization;
            this.say = say;
            this.backpack = backpack;
            this.headDeltaDeg = headDeltaDeg;
            this.driveLeft = driveLeft;
            this.driveRight = driveRight;
            this.durationS = durationS;
            this.reason = reason;
            this.rawText = rawText;
        }

        public AiSupervisor.Intention toIntention(long nowMs) {
            return toIntention(nowMs, false);
        }

        public AiSupervisor.Intention toIntention(long nowMs, boolean allowAutonomousDrive) {
            String base = rawText != null && !rawText.isEmpty() ? rawText : "";
            String clp = clip != null && !clip.isEmpty() ? clip : extractFromText(base, "clip", "");
            String expr = expression != null && !expression.isEmpty() ? expression : extractFromText(base, "expression", "CURIOUS");
            String vocal = vocalization != null && !vocalization.isEmpty() ? vocalization : extractFromText(base, "vocalization", "curious");
            String sayTxt = say != null && !say.isEmpty() ? say : extractFromText(base, "say", "");
            String bp = backpack != null && !backpack.isEmpty() ? backpack : extractFromText(base, "backpack", "cyan");
            float deltaDeg = headDeltaDeg != 0f ? headDeltaDeg : parseFloatOr(extractFromText(base, "head_delta_deg", "0"), 0f);
            String rsn = reason != null && !reason.isEmpty() ? reason : "Risposta Gemini Live";
            String json = String.format(Locale.ROOT,
                "{\"clip\":\"%s\",\"expression\":\"%s\",\"vocalization\":\"%s\",\"say\":\"%s\",\"backpack\":\"%s\",\"head_delta_deg\":%.2f,\"vl\":%.1f,\"vr\":%.1f,\"duration_s\":%.2f,\"expires_ms\":3200,\"reason\":\"%s\"}",
                escapeJson(clp), escapeJson(expr), escapeJson(vocal), escapeJson(sayTxt), escapeJson(bp),
                deltaDeg, driveLeft, driveRight, durationS, escapeJson(rsn));
            return AiSupervisor.validateJson(json, nowMs, "gemini-live", allowAutonomousDrive);
        }

        private static String extractFromText(String src, String key, String fallback) {
            if (src == null || src.isEmpty()) return fallback;
            int idx = src.indexOf('"' + key + '"');
            if (idx < 0) return fallback;
            int colon = src.indexOf(':', idx);
            if (colon < 0) return fallback;
            int q1 = src.indexOf('"', colon + 1);
            if (q1 < 0) return fallback;
            int q2 = src.indexOf('"', q1 + 1);
            if (q2 < 0) return fallback;
            return src.substring(q1 + 1, q2);
        }

        private static float parseFloatOr(String s, float def) {
            try {
                float v = Float.parseFloat(s.trim());
                return Float.isFinite(v) ? v : def;
            } catch (Exception e) {
                return def;
            }
        }

        static String escapeJson(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    public static String buildSystemInstruction(Personality.Profile profile) {
        Personality.Profile p = profile == null ? Personality.Profile.CURIOSO : profile;
        return "Sei Cozmo, un piccolo robot espressivo con carattere " + p.label + ". " +
            "Rispondi in italiano breve e usa il tool react (con expression, vocalization, say breve, backpack, head_delta_deg entro [-10,10] e clip opzionale tra GREET_FAMILIAR, GREET_STRANGER, HAPPY_COMFORT, PLAY_CHEER, BORED_SIGH, SLEEPY_CHARGER, CURIOUS_INSPECT, CUBE_VICTORY). " +
            "Non tentare mai movimenti pericolosi sulla basetta.";
    }

    public static String buildSetupMessage(String model, String systemInstruction) {
        String m = (model == null || model.trim().isEmpty()) ? DEFAULT_MODEL : model.trim();
        String sys = (systemInstruction == null || systemInstruction.trim().isEmpty())
            ? buildSystemInstruction(Personality.Profile.CURIOSO)
            : systemInstruction.trim();
        return "{" +
            "\"setup\":{" +
            "\"model\":\"" + LiveAction.escapeJson(m) + "\"," +
            "\"generationConfig\":{" +
                "\"responseModalities\":[\"TEXT\"]" +
            "}," +
            "\"systemInstruction\":{" +
                "\"parts\":[{\"text\":\"" + LiveAction.escapeJson(sys) + "\"}]" +
            "}," +
            "\"tools\":[" +
                "{\"functionDeclarations\":[" +
                    "{\"name\":\"react\",\"description\":\"Imposta espressione, clip, vocalizzo, eventuale parola breve e colore zaino.\"," +
                    "\"parameters\":{\"type\":\"OBJECT\",\"properties\":{\"clip\":{\"type\":\"STRING\"},\"expression\":{\"type\":\"STRING\"},\"vocalization\":{\"type\":\"STRING\"},\"say\":{\"type\":\"STRING\"},\"backpack\":{\"type\":\"STRING\"},\"head_delta_deg\":{\"type\":\"NUMBER\"},\"reason\":{\"type\":\"STRING\"}}}}" +
                    ",{\"name\":\"drive\",\"description\":\"Impulso breve e prudente sui cingoli (velocità in mm/s limitate, durata breve).\"," +
                    "\"parameters\":{\"type\":\"OBJECT\",\"properties\":{\"vl\":{\"type\":\"NUMBER\"},\"vr\":{\"type\":\"NUMBER\"},\"duration_s\":{\"type\":\"NUMBER\"},\"reason\":{\"type\":\"STRING\"}}}}" +
                "]}" +
            "]" +
            "}" +
        "}";
    }

    public static String buildContextTurn(String telemetrySummary, String visionSummary, String userPrompt, byte[] jpegBytes) {
        StringBuilder parts = new StringBuilder();
        StringBuilder summary = new StringBuilder();
        if (telemetrySummary != null && !telemetrySummary.isEmpty()) {
            summary.append("Telemetria: ").append(telemetrySummary).append("\n");
        }
        if (visionSummary != null && !visionSummary.isEmpty()) {
            summary.append("Visione: ").append(visionSummary).append("\n");
        }
        if (userPrompt != null && !userPrompt.isEmpty()) {
            summary.append("Richiesta: ").append(userPrompt);
        }
        String txt = summary.toString().trim();
        if (txt.isEmpty()) txt = "Aggiorna la reazione di Cozmo in base alla situazione.";
        parts.append("{\"text\":\"").append(LiveAction.escapeJson(txt)).append("\"}");
        if (jpegBytes != null && jpegBytes.length > 0 && jpegBytes.length <= 250_000) {
            String b64 = Base64.getEncoder().encodeToString(jpegBytes);
            parts.append(",{\"inlineData\":{\"mimeType\":\"image/jpeg\",\"data\":\"").append(b64).append("\"}}");
        }
        return "{\"clientContent\":{" +
            "\"turns\":[{\"role\":\"user\",\"parts\":[" + parts.toString() + "]}]," +
            "\"turnComplete\":true" +
        "}}";
    }

    public static LiveAction parseServerMessage(String json) {
        if (json == null || json.isEmpty()) return null;
        String fnName = extractFunctionCallName(json);
        String clip = extractStringField(json, "clip");
        String expr = extractStringField(json, "expression");
        String vocal = extractStringField(json, "vocalization");
        String say = extractStringField(json, "say");
        String bp = extractStringField(json, "backpack");
        String reason = extractStringField(json, "reason");
        float headDeg = extractNumberField(json, "head_delta_deg", 0f);
        float vl = extractNumberField(json, "vl", 0f);
        float vr = extractNumberField(json, "vr", 0f);
        float dur = extractNumberField(json, "duration_s", 0f);
        if (dur == 0f) dur = extractNumberField(json, "duration", 0f);

        float clampedVl = Safety.limit(vl, -40f, 40f);
        float clampedVr = Safety.limit(vr, -40f, 40f);
        float clampedDur = Safety.limit(Math.max(0f, dur), 0f, 1.5f);

        String textPart = extractTextPart(json);
        if (fnName == null && (textPart == null || textPart.isEmpty()) && clip.isEmpty() && expr.isEmpty() && vocal.isEmpty() && say.isEmpty() && vl == 0f && vr == 0f) {
            return null;
        }
        String resolvedName = fnName != null ? fnName : (vl != 0f || vr != 0f ? "drive" : "react");
        return new LiveAction(resolvedName, clip, expr, vocal, say, bp, headDeg, clampedVl, clampedVr, clampedDur, reason, textPart);
    }

    private static Socket openTlsSocket(SocketFactory networkSocketFactory, int connectTimeoutMs, int readTimeoutMs) throws Exception {
        SSLSocketFactory sslFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        Socket raw = (networkSocketFactory != null) ? networkSocketFactory.createSocket() : new Socket();
        raw.connect(new InetSocketAddress(DEFAULT_HOST, DEFAULT_PORT), Math.max(1000, connectTimeoutMs));
        raw.setSoTimeout(Math.max(1000, readTimeoutMs));
        SSLSocket ssl = (SSLSocket) sslFactory.createSocket(raw, DEFAULT_HOST, DEFAULT_PORT, true);
        ssl.setSoTimeout(Math.max(1000, readTimeoutMs));
        ssl.startHandshake();
        return ssl;
    }

    public static String testConnection(String apiKey, SocketFactory networkSocketFactory, int timeoutMs) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            lastErrorCode = "MISSING_KEY";
            return "Chiave API mancante";
        }
        long t0 = System.currentTimeMillis();
        try {
            LiveAction act = queryOnce(apiKey, DEFAULT_MODEL, null, "batt=4.1V", "test", "Saluta brevemente", null, timeoutMs, networkSocketFactory);
            long dt = System.currentTimeMillis() - t0;
            lastLatencyMs.set(dt);
            lastErrorCode = "OK";
            return "Connessione Gemini Live riuscita (" + dt + " ms)" + (act != null ? " · azione=" + act.name : "");
        } catch (Exception e) {
            String clean = AiSupervisor.sanitizeForLog(e.getMessage(), apiKey);
            return "Errore connessione AI (" + lastErrorCode + "): " + clean;
        }
    }

    public static LiveAction queryOnce(String apiKey, String model, String systemInstruction,
                                       String telemetrySummary, String visionSummary,
                                       String userPrompt, byte[] jpegBytes, int timeoutMs) throws Exception {
        return queryOnce(apiKey, model, systemInstruction, telemetrySummary, visionSummary, userPrompt, jpegBytes, timeoutMs, null);
    }

    public static LiveAction queryOnce(String apiKey, String model, String systemInstruction,
                                       String telemetrySummary, String visionSummary,
                                       String userPrompt, byte[] jpegBytes, int timeoutMs,
                                       SocketFactory networkSocketFactory) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            lastErrorCode = "MISSING_KEY";
            throw new IllegalArgumentException("Chiave API Gemini mancante");
        }
        long t0 = System.currentTimeMillis();
        int readTo = Math.max(1500, Math.min(READ_TIMEOUT_MS, timeoutMs));
        String encodedPath = WS_PATH_PREFIX + "?key=" + URLEncoder.encode(apiKey.trim(), "UTF-8");
        Socket socket = null;
        try {
            socket = openTlsSocket(networkSocketFactory, CONNECT_TIMEOUT_MS, readTo);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            byte[] nonce = new byte[16];
            new Random().nextBytes(nonce);
            String secKey = Base64.getEncoder().encodeToString(nonce);
            String req = "GET " + encodedPath + " HTTP/1.1\r\n" +
                "Host: " + DEFAULT_HOST + "\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: " + secKey + "\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            String headers = readHttpHeaders(in);
            String firstLine = headers.lines().findFirst().orElse("");
            if (!headers.contains(" 101 ")) {
                if (firstLine.contains("401") || firstLine.contains("403")) lastErrorCode = "HTTP_AUTH";
                else if (firstLine.contains("429")) lastErrorCode = "HTTP_429_QUOTA";
                else lastErrorCode = "HTTP_HANDSHAKE";
                throw new IllegalStateException("Handshake WebSocket fallito (" + lastErrorCode + "): " + AiSupervisor.sanitizeForLog(firstLine, apiKey));
            }

            sendWsTextFrame(out, buildSetupMessage(model, systemInstruction));
            sendWsTextFrame(out, buildContextTurn(telemetrySummary, visionSummary, userPrompt, jpegBytes));

            long deadline = System.currentTimeMillis() + readTo;
            while (System.currentTimeMillis() < deadline) {
                String framePayload = readWsFrame(in);
                if (framePayload == null) break;
                LiveAction act = parseServerMessage(framePayload);
                if (act != null) {
                    turnsCount.incrementAndGet();
                    lastLatencyMs.set(System.currentTimeMillis() - t0);
                    lastErrorCode = "OK";
                    return act;
                }
            }
            turnsCount.incrementAndGet();
            lastLatencyMs.set(System.currentTimeMillis() - t0);
            lastErrorCode = "EMPTY_TURN";
            return null;
        } catch (Exception e) {
            if ("OK".equals(lastErrorCode)) {
                lastErrorCode = e.getClass().getSimpleName();
            }
            throw new IllegalStateException(AiSupervisor.sanitizeForLog(e.getMessage(), apiKey));
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static String extractFunctionCallName(String json) {
        int fcIdx = json.indexOf("\"functionCall\"");
        if (fcIdx < 0) return null;
        return extractStringFieldFrom(json, fcIdx, "name");
    }

    private static String extractTextPart(String json) {
        int idx = json.indexOf("\"text\"");
        while (idx >= 0) {
            int colon = json.indexOf(':', idx + 6);
            if (colon > 0) {
                int q1 = json.indexOf('"', colon + 1);
                if (q1 > 0) {
                    String val = readJsonString(json, q1);
                    if (val != null && !val.isEmpty()) return val;
                }
            }
            idx = json.indexOf("\"text\"", idx + 6);
        }
        return "";
    }

    private static String extractStringField(String json, String key) {
        return extractStringFieldFrom(json, 0, key);
    }

    private static String extractStringFieldFrom(String json, int fromIndex, String key) {
        String target = "\"" + key + "\"";
        int idx = json.indexOf(target, fromIndex);
        if (idx < 0) return "";
        int colon = json.indexOf(':', idx + target.length());
        if (colon < 0) return "";
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return "";
        String v = readJsonString(json, q1);
        return v == null ? "" : v;
    }

    private static float extractNumberField(String json, String key, float def) {
        String target = "\"" + key + "\"";
        int idx = json.indexOf(target);
        if (idx < 0) return def;
        int colon = json.indexOf(':', idx + target.length());
        if (colon < 0) return def;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        int start = i;
        while (i < json.length() && ("0123456789+-.eE".indexOf(json.charAt(i)) >= 0)) i++;
        if (i <= start) return def;
        try {
            float val = Float.parseFloat(json.substring(start, i));
            return Float.isFinite(val) ? val : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String readJsonString(String s, int openQuoteIdx) {
        StringBuilder out = new StringBuilder();
        boolean esc = false;
        for (int i = openQuoteIdx + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) {
                if (c == 'n') out.append('\n');
                else if (c == 'r') out.append('\r');
                else if (c == 't') out.append('\t');
                else out.append(c);
                esc = false;
            } else if (c == '\\') {
                esc = true;
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
            }
        }
        return null;
    }

    private static String readHttpHeaders(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        while (buf.size() < 16384) {
            int b = in.read();
            if (b < 0) break;
            buf.write(b);
            byte[] arr = buf.toByteArray();
            int len = arr.length;
            if (len >= 4 && arr[len - 4] == '\r' && arr[len - 3] == '\n' && arr[len - 2] == '\r' && arr[len - 1] == '\n') {
                return new String(arr, StandardCharsets.ISO_8859_1);
            }
        }
        return buf.toString(StandardCharsets.ISO_8859_1.name());
    }

    public static byte[] encodeWsTextFrame(String text) throws java.io.IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        byte[] mask = new byte[4];
        new Random().nextBytes(mask);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x81);
        int len = payload.length;
        if (len <= 125) {
            out.write(0x80 | len);
        } else if (len <= 65535) {
            out.write(0x80 | 126);
            out.write((len >>> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(0x80 | 127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) ((len >>> shift) & 0xFF));
            }
        }
        out.write(mask);
        for (int i = 0; i < payload.length; i++) {
            out.write(payload[i] ^ mask[i & 3]);
        }
        return out.toByteArray();
    }

    private static void sendWsTextFrame(OutputStream out, String text) throws Exception {
        out.write(encodeWsTextFrame(text));
        out.flush();
    }

    private static String readWsFrame(InputStream in) throws Exception {
        int b0 = in.read();
        if (b0 < 0) return null;
        int opcode = b0 & 0x0F;
        int b1 = in.read();
        if (b1 < 0) return null;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            int hi = in.read(), lo = in.read();
            if (hi < 0 || lo < 0) return null;
            len = ((hi & 0xFF) << 8) | (lo & 0xFF);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) {
                int ch = in.read();
                if (ch < 0) return null;
                len = (len << 8) | (ch & 0xFF);
            }
        }
        byte[] mask = new byte[4];
        if (masked) {
            readExact(in, mask, 0, 4);
        }
        int safeLen = (int) Math.min(len, 1_048_576L);
        byte[] data = new byte[safeLen];
        readExact(in, data, 0, safeLen);
        if (masked) {
            for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
        }
        if (opcode == 0x8) return null;
        return new String(data, StandardCharsets.UTF_8);
    }

    private static void readExact(InputStream in, byte[] dst, int off, int len) throws Exception {
        int readTotal = 0;
        while (readTotal < len) {
            int n = in.read(dst, off + readTotal, len - readTotal);
            if (n < 0) throw new java.io.EOFException("Flusso WebSocket interrotto");
            readTotal += n;
        }
    }
}
