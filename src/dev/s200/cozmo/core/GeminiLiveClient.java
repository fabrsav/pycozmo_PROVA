package dev.s200.cozmo.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Lightweight HTTPS client for the Gemini Interactions API (POST /v1beta/interactions)
 * supporting injected SocketFactory (for strict Android Internet Network isolation away from wlan1),
 * multimodal JPEG frames, structured JSON output, server-side previous_interaction_id chaining,
 * and secret-safe error reporting.
 * Pure Java 11, zero Android dependencies.
 */
public final class GeminiLiveClient {
    public static final String DEFAULT_MODEL = "gemini-3.8-flash";
    public static final String FALLBACK_MODEL = "gemini-3.5-flash-lite";
    public static final String DEFAULT_HOST = "generativelanguage.googleapis.com";
    public static final int DEFAULT_PORT = 443;
    public static final String API_PATH = "/v1beta/interactions";
    public static final String API_REVISION = "2026-05-20";
    public static final int CONNECT_TIMEOUT_MS = 6000;
    public static final int READ_TIMEOUT_MS = 15000;

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
    private static volatile String lastUsedModel = DEFAULT_MODEL;

    public static int turnsCount() { return turnsCount.get(); }
    public static long lastLatencyMs() { return lastLatencyMs.get(); }
    public static String lastErrorCode() { return lastErrorCode; }
    public static String lastUsedModel() { return lastUsedModel; }

    public static String normalizeModel(String raw) {
        if (raw == null || raw.trim().isEmpty()) return DEFAULT_MODEL;
        String m = raw.trim();
        if (m.startsWith("models/")) {
            m = m.substring("models/".length()).trim();
        }
        if (m.isEmpty()) return DEFAULT_MODEL;
        String lower = m.toLowerCase(Locale.ROOT);
        if (lower.contains("gemini-robotics-er-2-streaming-preview")
                || lower.contains("gemini-2.5-")
                || lower.contains("gemini-2.0-")
                || lower.contains("gemini-1.5-")) {
            return DEFAULT_MODEL;
        }
        return m;
    }

    public static final class LiveAction {
        public final String interactionId;
        public final String perception;
        public final String remember;
        public final float gazeX;
        public final float gazeY;
        public final String name;
        public final String clip;
        public final String expression;
        public final String vocalization;
        public final String say;
        public final String backpack;
        public final float headDeltaDeg;
        public final float liftDeltaMm;
        public final int headlightCmd;
        public final float driveLeft;
        public final float driveRight;
        public final float durationS;
        public final String reason;
        public final String rawText;

        public LiveAction(String name, String expression, String vocalization, String say,
                          String backpack, float headDeltaDeg, float driveLeft, float driveRight,
                          float durationS, String reason, String rawText) {
            this("", "", "", 0f, 0f, name, "", expression, vocalization, say, backpack,
                headDeltaDeg, 0f, -1, driveLeft, driveRight, durationS, reason, rawText);
        }

        public LiveAction(String name, String clip, String expression, String vocalization, String say,
                          String backpack, float headDeltaDeg, float driveLeft, float driveRight,
                          float durationS, String reason, String rawText) {
            this("", "", "", 0f, 0f, name, clip, expression, vocalization, say, backpack,
                headDeltaDeg, 0f, -1, driveLeft, driveRight, durationS, reason, rawText);
        }

        public LiveAction(String interactionId, String perception, String remember, float gazeX, float gazeY,
                          String name, String clip, String expression, String vocalization, String say,
                          String backpack, float headDeltaDeg, float driveLeft, float driveRight,
                          float durationS, String reason, String rawText) {
            this(interactionId, perception, remember, gazeX, gazeY, name, clip, expression, vocalization, say,
                backpack, headDeltaDeg, 0f, -1, driveLeft, driveRight, durationS, reason, rawText);
        }

        public LiveAction(String interactionId, String perception, String remember, float gazeX, float gazeY,
                          String name, String clip, String expression, String vocalization, String say,
                          String backpack, float headDeltaDeg, float liftDeltaMm, int headlightCmd,
                          float driveLeft, float driveRight, float durationS, String reason, String rawText) {
            this.interactionId = interactionId == null ? "" : interactionId;
            this.perception = perception == null ? "" : perception;
            this.remember = remember == null ? "" : remember;
            this.gazeX = Safety.limit(Float.isFinite(gazeX) ? gazeX : 0f, -5.5f, 5.5f);
            this.gazeY = Safety.limit(Float.isFinite(gazeY) ? gazeY : 0f, -3.0f, 3.0f);
            this.name = name == null || name.isEmpty() ? "react" : name;
            this.clip = clip == null ? "" : clip;
            this.expression = expression == null ? "NEUTRAL" : expression;
            this.vocalization = vocalization == null ? "curious" : vocalization;
            this.say = say == null ? "" : say;
            this.backpack = backpack == null ? "cyan" : backpack;
            this.headDeltaDeg = Safety.limit(Float.isFinite(headDeltaDeg) ? headDeltaDeg : 0f, -10.0f, 10.0f);
            this.liftDeltaMm = Safety.limit(Float.isFinite(liftDeltaMm) ? liftDeltaMm : 0f, -AiSupervisor.MAX_LIFT_DELTA_MM, AiSupervisor.MAX_LIFT_DELTA_MM);
            this.headlightCmd = (headlightCmd == 0 || headlightCmd == 1) ? headlightCmd : -1;
            this.driveLeft = Safety.limit(Float.isFinite(driveLeft) ? driveLeft : 0f, -40.0f, 40.0f);
            this.driveRight = Safety.limit(Float.isFinite(driveRight) ? driveRight : 0f, -40.0f, 40.0f);
            this.durationS = Safety.limit(Float.isFinite(durationS) ? Math.max(0f, durationS) : 0f, 0.0f, 1.5f);
            this.reason = reason == null ? "" : reason;
            this.rawText = rawText == null ? "" : rawText;
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
            float liftMm = liftDeltaMm != 0f ? liftDeltaMm : parseFloatOr(extractFromText(base, "lift_delta_mm", "0"), 0f);
            String rsn = reason != null && !reason.isEmpty() ? reason : "Risposta Gemini";
            String json = String.format(Locale.ROOT,
                "{\"clip\":\"%s\",\"expression\":\"%s\",\"vocalization\":\"%s\",\"say\":\"%s\",\"backpack\":\"%s\",\"head_delta_deg\":%.2f,\"lift_delta_mm\":%.1f,\"vl\":%.1f,\"vr\":%.1f,\"duration_s\":%.2f,\"expires_ms\":5000,\"reason\":\"%s\"}",
                escapeJson(clp), escapeJson(expr), escapeJson(vocal), escapeJson(sayTxt), escapeJson(bp),
                deltaDeg, liftMm, driveLeft, driveRight, durationS, escapeJson(rsn));
            return AiSupervisor.validateJson(json, nowMs, "gemini", allowAutonomousDrive);
        }

        private static String extractFromText(String src, String key, String fallback) {
            if (src == null || src.isEmpty()) return fallback;
            int idx = src.indexOf('"' + key + '"');
            if (idx < 0) return fallback;
            int colon = src.indexOf(':', idx);
            if (colon < 0) return fallback;
            int q1 = src.indexOf('"', colon + 1);
            if (q1 < 0) return fallback;
            String val = readJsonString(src, q1);
            return val == null ? fallback : val;
        }

        private static float parseFloatOr(String s, float def) {
            try {
                float v = Float.parseFloat(s.trim());
                return Float.isFinite(v) ? v : def;
            } catch (Exception e) {
                return def;
            }
        }

        public static String escapeJson(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }
    }

    public static String buildSystemInstruction(Personality.Profile profile) {
        Personality.Profile p = profile == null ? Personality.Profile.CURIOSO : profile;
        return "Sei Cozmo, un piccolo robot fisico con carattere " + p.label + ". " +
            "La foto della camera usa già il potenziamento visione notturna/IR, quindi NON lamentarti continuamente che è buio: descrivi invece persone, volti, mani, gesti e oggetti reali. " +
            "Reagisci in modo NATURALE e COERENTE, MAI casuale: " +
            "1) Se rispetto al turno precedente NON è cambiato nulla di rilevante, resta calmo (NEUTRAL se sei solo, oppure HAPPY se guardi una persona) con vocalization=\"none\", say=\"\", clip=\"\", head_delta_deg=0 e lift_delta_mm=0. " +
            "2) Quando accade qualcosa di reale (volto, gesto, oggetto, domanda o pulsante), usa l'expression più naturale per ciò che vedi (non cambiare espressione a caso!), una breve frase italiana naturale in say (1-5 parole: Cozmo la pronuncerà davvero a voce!) oppure una vocalization adatta, e un gesto chiaro con head_delta_deg (es. +-6..10) e lift_delta_mm (es. +-8..15). Testa e carrello sono sicuri anche sulla basetta (charger=true), cingoli solo se allow_drive=true. " +
            "3) Compila perception (onesto e concreto in italiano), gaze_x in [-5,5], gaze_y in [-3,3], backpack coerente con l'umore, opzionale headlight=true se la scena è buia, remember conciso se c'è un fatto nuovo, e reason sintetico.";
    }

    public static String buildInteractionRequest(String model, String systemInstruction, String previousInteractionId,
                                                 String telemetrySummary, String visionSummary, String userPrompt,
                                                 byte[] jpegBytes) {
        String m = normalizeModel(model);
        String sys = (systemInstruction == null || systemInstruction.trim().isEmpty())
            ? buildSystemInstruction(Personality.Profile.CURIOSO)
            : systemInstruction.trim();

        StringBuilder promptBuf = new StringBuilder();
        if (telemetrySummary != null && !telemetrySummary.isEmpty()) {
            promptBuf.append("Contesto: ").append(telemetrySummary).append('\n');
        }
        if (visionSummary != null && !visionSummary.isEmpty()) {
            promptBuf.append("Visione locale: ").append(visionSummary).append('\n');
        }
        if (userPrompt != null && !userPrompt.isEmpty()) {
            promptBuf.append("Richiesta/Evento: ").append(userPrompt);
        }
        String txt = promptBuf.toString().trim();
        if (txt.isEmpty()) txt = "Osserva la scena e aggiorna la reazione di Cozmo.";

        StringBuilder inputArr = new StringBuilder();
        inputArr.append("[{\"type\":\"text\",\"text\":\"").append(LiveAction.escapeJson(txt)).append("\"}");
        if (jpegBytes != null && jpegBytes.length > 0 && jpegBytes.length <= 250_000) {
            String b64 = Base64.getEncoder().encodeToString(jpegBytes);
            inputArr.append(",{\"type\":\"image\",\"data\":\"").append(b64).append("\",\"mime_type\":\"image/jpeg\"}");
        }
        inputArr.append(']');

        String schema = "{\"type\":\"object\",\"properties\":{" +
            "\"perception\":{\"type\":\"string\"}," +
            "\"expression\":{\"type\":\"string\",\"enum\":[\"NEUTRAL\",\"HAPPY\",\"EXCITED\",\"CURIOUS\",\"SURPRISED\",\"SKEPTICAL\",\"SAD\",\"SLEEPY\",\"FOCUSED\"]}," +
            "\"clip\":{\"type\":\"string\",\"enum\":[\"\",\"GREET_FAMILIAR\",\"GREET_STRANGER\",\"HAPPY_COMFORT\",\"PLAY_CHEER\",\"BORED_SIGH\",\"SLEEPY_CHARGER\",\"CURIOUS_INSPECT\",\"CUBE_VICTORY\"]}," +
            "\"vocalization\":{\"type\":\"string\",\"enum\":[\"none\",\"curious\",\"happy\",\"excited\",\"surprised\",\"sleepy\",\"sad\",\"sing\",\"fanfare\",\"lullaby\",\"sigh\"]}," +
            "\"say\":{\"type\":\"string\"}," +
            "\"backpack\":{\"type\":\"string\",\"enum\":[\"cyan\",\"green\",\"amber\",\"purple\",\"red\",\"off\"]}," +
            "\"head_delta_deg\":{\"type\":\"number\"}," +
            "\"lift_delta_mm\":{\"type\":\"number\"}," +
            "\"headlight\":{\"type\":\"boolean\"}," +
            "\"gaze_x\":{\"type\":\"number\"}," +
            "\"gaze_y\":{\"type\":\"number\"}," +
            "\"remember\":{\"type\":\"string\"}," +
            "\"reason\":{\"type\":\"string\"}" +
            "},\"required\":[\"perception\",\"expression\",\"vocalization\",\"say\",\"backpack\",\"reason\"]}";

        StringBuilder body = new StringBuilder();
        body.append("{\"model\":\"").append(LiveAction.escapeJson(m)).append("\",");
        body.append("\"system_instruction\":\"").append(LiveAction.escapeJson(sys)).append("\",");
        if (previousInteractionId != null && !previousInteractionId.trim().isEmpty()) {
            body.append("\"previous_interaction_id\":\"").append(LiveAction.escapeJson(previousInteractionId.trim())).append("\",");
        }
        if (m.contains("3.8-flash")) {
            body.append("\"generation_config\":{\"thinking_level\":\"low\"},");
        }
        body.append("\"input\":").append(inputArr).append(',');
        body.append("\"response_format\":{\"type\":\"text\",\"mime_type\":\"application/json\",\"schema\":").append(schema).append('}');
        body.append('}');
        return body.toString();
    }

    public static LiveAction parseInteractionResponse(String responseJson) {
        if (responseJson == null || responseJson.isEmpty()) return null;
        String interactionId = extractStringFieldFrom(responseJson, 0, "id");
        String payloadText = extractModelOutputText(responseJson);
        String sourceJson = (payloadText != null && payloadText.indexOf('{') >= 0) ? payloadText : responseJson;
        int objStart = sourceJson.indexOf('{');
        int objEnd = sourceJson.lastIndexOf('}');
        if (objStart >= 0 && objEnd > objStart) {
            sourceJson = sourceJson.substring(objStart, objEnd + 1);
        }

        String perception = extractStringField(sourceJson, "perception");
        String remember = extractStringField(sourceJson, "remember");
        String clip = extractStringField(sourceJson, "clip");
        String expr = extractStringField(sourceJson, "expression");
        String vocal = extractStringField(sourceJson, "vocalization");
        String say = extractStringField(sourceJson, "say");
        String bp = extractStringField(sourceJson, "backpack");
        String reason = extractStringField(sourceJson, "reason");
        float headDeg = extractNumberField(sourceJson, "head_delta_deg", 0f);
        float liftMm = extractNumberField(sourceJson, "lift_delta_mm", 0f);
        int headlight = extractOptionalBooleanField(sourceJson, "headlight");
        float gazeX = extractNumberField(sourceJson, "gaze_x", 0f);
        float gazeY = extractNumberField(sourceJson, "gaze_y", 0f);
        float vl = extractNumberField(sourceJson, "vl", 0f);
        float vr = extractNumberField(sourceJson, "vr", 0f);
        float dur = extractNumberField(sourceJson, "duration_s", 0f);
        if (dur == 0f) dur = extractNumberField(sourceJson, "duration", 0f);

        if (perception.isEmpty() && expr.isEmpty() && vocal.isEmpty() && say.isEmpty()
                && clip.isEmpty() && reason.isEmpty() && vl == 0f && vr == 0f) {
            return null;
        }
        String resolvedName = (vl != 0f || vr != 0f) ? "drive" : "react";
        return new LiveAction(interactionId, perception, remember, gazeX, gazeY,
            resolvedName, clip, expr, vocal, say, bp, headDeg, liftMm, headlight, vl, vr, dur, reason, payloadText);
    }

    public static LiveAction parseServerMessage(String json) {
        if (json == null || json.isEmpty()) return null;
        if (json.contains("\"steps\"") || json.contains("\"outputs\"")) {
            LiveAction inter = parseInteractionResponse(json);
            if (inter != null) return inter;
        }
        String fnName = extractFunctionCallName(json);
        String clip = extractStringField(json, "clip");
        String expr = extractStringField(json, "expression");
        String vocal = extractStringField(json, "vocalization");
        String say = extractStringField(json, "say");
        String bp = extractStringField(json, "backpack");
        String reason = extractStringField(json, "reason");
        String perception = extractStringField(json, "perception");
        String remember = extractStringField(json, "remember");
        float headDeg = extractNumberField(json, "head_delta_deg", 0f);
        float liftMm = extractNumberField(json, "lift_delta_mm", 0f);
        int headlight = extractOptionalBooleanField(json, "headlight");
        float gazeX = extractNumberField(json, "gaze_x", 0f);
        float gazeY = extractNumberField(json, "gaze_y", 0f);
        float vl = extractNumberField(json, "vl", 0f);
        float vr = extractNumberField(json, "vr", 0f);
        float dur = extractNumberField(json, "duration_s", 0f);
        if (dur == 0f) dur = extractNumberField(json, "duration", 0f);

        String textPart = extractTextPart(json);
        if (fnName == null && (textPart == null || textPart.isEmpty()) && clip.isEmpty() && expr.isEmpty()
                && vocal.isEmpty() && say.isEmpty() && perception.isEmpty() && vl == 0f && vr == 0f) {
            return null;
        }
        String resolvedName = fnName != null ? fnName : (vl != 0f || vr != 0f ? "drive" : "react");
        return new LiveAction("", perception, remember, gazeX, gazeY, resolvedName, clip, expr,
            vocal, say, bp, headDeg, liftMm, headlight, vl, vr, dur, reason, textPart);
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
            LiveAction act = queryOnce(apiKey, DEFAULT_MODEL, null, null,
                "batt=4.1V on_charger=true", "camera=spenta", "Rispondi brevemente per confermare la connessione.",
                null, timeoutMs, networkSocketFactory);
            long dt = System.currentTimeMillis() - t0;
            lastLatencyMs.set(dt);
            lastErrorCode = "OK";
            String detail = "";
            if (act != null) {
                detail = !act.perception.isEmpty() ? " · " + act.perception : " · azione=" + act.name;
            }
            return "Connessione Gemini (" + lastUsedModel + ") riuscita (" + dt + " ms)" + detail;
        } catch (Exception e) {
            String clean = AiSupervisor.sanitizeForLog(e.getMessage(), apiKey);
            return "Errore connessione AI (" + lastErrorCode + "): " + clean;
        }
    }

    public static LiveAction queryOnce(String apiKey, String model, String systemInstruction,
                                       String telemetrySummary, String visionSummary,
                                       String userPrompt, byte[] jpegBytes, int timeoutMs) throws Exception {
        return queryOnce(apiKey, model, systemInstruction, null, telemetrySummary, visionSummary,
            userPrompt, jpegBytes, timeoutMs, null);
    }

    public static LiveAction queryOnce(String apiKey, String model, String systemInstruction,
                                       String telemetrySummary, String visionSummary,
                                       String userPrompt, byte[] jpegBytes, int timeoutMs,
                                       SocketFactory networkSocketFactory) throws Exception {
        return queryOnce(apiKey, model, systemInstruction, null, telemetrySummary, visionSummary,
            userPrompt, jpegBytes, timeoutMs, networkSocketFactory);
    }

    public static LiveAction queryOnce(String apiKey, String model, String systemInstruction,
                                       String previousInteractionId,
                                       String telemetrySummary, String visionSummary,
                                       String userPrompt, byte[] jpegBytes, int timeoutMs,
                                       SocketFactory networkSocketFactory) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            lastErrorCode = "MISSING_KEY";
            throw new IllegalArgumentException("Chiave API Gemini mancante");
        }
        long t0 = System.currentTimeMillis();
        int readTo = Math.max(2000, Math.min(READ_TIMEOUT_MS, timeoutMs));
        String activeModel = normalizeModel(model);
        String effectivePrevId = previousInteractionId;
        for (int attempt = 0; attempt < 3; attempt++) {
            String payloadJson = buildInteractionRequest(
                activeModel, systemInstruction, effectivePrevId,
                telemetrySummary, visionSummary, userPrompt, jpegBytes);
            HttpResult res = postJson(apiKey.trim(), payloadJson, readTo, networkSocketFactory);
            if (res.statusCode >= 200 && res.statusCode < 300) {
                lastUsedModel = activeModel;
                LiveAction act = parseInteractionResponse(res.body);
                turnsCount.incrementAndGet();
                lastLatencyMs.set(System.currentTimeMillis() - t0);
                if (act == null) {
                    lastErrorCode = "EMPTY_TURN";
                    return null;
                }
                lastErrorCode = "OK";
                return act;
            }
            if ((res.statusCode == 400 || res.statusCode == 404) && effectivePrevId != null && !effectivePrevId.isEmpty()) {
                effectivePrevId = null;
                continue;
            }
            if ((res.statusCode == 503 || res.statusCode == 429) && !FALLBACK_MODEL.equals(activeModel)) {
                activeModel = FALLBACK_MODEL;
                effectivePrevId = null;
                continue;
            }
            if (res.statusCode == 401 || res.statusCode == 403) {
                lastErrorCode = "HTTP_AUTH";
            } else if (res.statusCode == 429) {
                lastErrorCode = "HTTP_429_QUOTA";
            } else {
                lastErrorCode = "HTTP_" + res.statusCode;
            }
            String snippet = res.body == null ? "" : res.body.replace('\n', ' ').replace('\r', ' ').trim();
            if (snippet.length() > 200) snippet = snippet.substring(0, 200);
            throw new IllegalStateException("HTTP " + res.statusCode + (snippet.isEmpty() ? "" : ": " + AiSupervisor.sanitizeForLog(snippet, apiKey)));
        }
        lastErrorCode = "HTTP_RETRY_EXHAUSTED";
        throw new IllegalStateException("Richiesta Gemini fallita dopo retry");
    }

    private static final class HttpResult {
        final int statusCode;
        final String body;
        HttpResult(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }

    private static HttpResult postJson(String apiKey, String jsonBody, int readTo, SocketFactory networkSocketFactory) throws Exception {
        byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        Socket socket = null;
        try {
            socket = openTlsSocket(networkSocketFactory, CONNECT_TIMEOUT_MS, readTo);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            String headers = "POST " + API_PATH + " HTTP/1.1\r\n" +
                "Host: " + DEFAULT_HOST + "\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Accept: application/json\r\n" +
                "Accept-Encoding: identity\r\n" +
                "Connection: close\r\n" +
                "Api-Revision: " + API_REVISION + "\r\n" +
                "x-goog-api-key: " + apiKey + "\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n\r\n";
            out.write(headers.getBytes(StandardCharsets.ISO_8859_1));
            out.write(bodyBytes);
            out.flush();

            String rawHeaders = readHttpHeaders(in);
            int statusCode = parseStatusCode(rawHeaders);
            String lowerHeaders = rawHeaders.toLowerCase(Locale.ROOT);
            byte[] rawBody;
            if (lowerHeaders.contains("transfer-encoding:") && lowerHeaders.contains("chunked")) {
                rawBody = readChunkedBody(in, 2_000_000);
            } else {
                int contentLen = parseContentLength(lowerHeaders);
                if (contentLen >= 0) {
                    rawBody = readUpTo(in, Math.min(contentLen, 2_000_000));
                } else {
                    rawBody = readAllBytes(in, 2_000_000);
                }
            }
            return new HttpResult(statusCode, new String(rawBody, StandardCharsets.UTF_8));
        } catch (Exception e) {
            if ("OK".equals(lastErrorCode)) {
                lastErrorCode = e.getClass().getSimpleName();
            }
            throw new IllegalStateException(AiSupervisor.sanitizeForLog(e.getMessage(), apiKey), e);
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    public static byte[] decodeChunkedBody(byte[] raw) {
        if (raw == null || raw.length == 0) return new byte[0];
        try {
            return readChunkedBody(new ByteArrayInputStream(raw), raw.length);
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static byte[] readChunkedBody(InputStream in, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (out.size() < maxBytes) {
            String sizeLine = readCrLfLine(in);
            if (sizeLine == null) break;
            sizeLine = sizeLine.trim();
            int semi = sizeLine.indexOf(';');
            if (semi >= 0) sizeLine = sizeLine.substring(0, semi).trim();
            if (sizeLine.isEmpty()) continue;
            int chunkLen;
            try {
                chunkLen = Integer.parseInt(sizeLine, 16);
            } catch (NumberFormatException e) {
                break;
            }
            if (chunkLen <= 0) break;
            int toRead = Math.min(chunkLen, maxBytes - out.size());
            byte[] buf = new byte[toRead];
            readExactBytes(in, buf, toRead);
            out.write(buf);
            if (chunkLen > toRead) break;
            readCrLfLine(in);
        }
        return out.toByteArray();
    }

    private static String readCrLfLine(InputStream in) throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int prev = -1;
        while (line.size() < 8192) {
            int b = in.read();
            if (b < 0) return line.size() == 0 ? null : new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
            if (b == '\n') {
                byte[] arr = line.toByteArray();
                int len = (prev == '\r' && arr.length > 0) ? arr.length - 1 : arr.length;
                return new String(arr, 0, len, StandardCharsets.ISO_8859_1);
            }
            line.write(b);
            prev = b;
        }
        return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static void readExactBytes(InputStream in, byte[] dst, int len) throws Exception {
        int off = 0;
        while (off < len) {
            int n = in.read(dst, off, len - off);
            if (n < 0) throw new java.io.EOFException("Flusso chunked interrotto");
            off += n;
        }
    }

    private static int parseStatusCode(String headers) {
        if (headers == null || headers.isEmpty()) return 0;
        int sp1 = headers.indexOf(' ');
        if (sp1 < 0) return 0;
        int i = sp1 + 1;
        while (i < headers.length() && headers.charAt(i) == ' ') i++;
        int start = i;
        while (i < headers.length() && Character.isDigit(headers.charAt(i))) i++;
        if (i <= start) return 0;
        try {
            return Integer.parseInt(headers.substring(start, i));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int parseContentLength(String lowerHeaders) {
        int idx = lowerHeaders.indexOf("content-length:");
        if (idx < 0) return -1;
        int i = idx + "content-length:".length();
        while (i < lowerHeaders.length() && (lowerHeaders.charAt(i) == ' ' || lowerHeaders.charAt(i) == '\t')) i++;
        int start = i;
        while (i < lowerHeaders.length() && Character.isDigit(lowerHeaders.charAt(i))) i++;
        if (i <= start) return -1;
        try {
            return Integer.parseInt(lowerHeaders.substring(start, i));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static byte[] readUpTo(InputStream in, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int remaining = maxBytes;
        while (remaining > 0) {
            int n = in.read(buf, 0, Math.min(buf.length, remaining));
            if (n < 0) break;
            out.write(buf, 0, n);
            remaining -= n;
        }
        return out.toByteArray();
    }

    private static byte[] readAllBytes(InputStream in, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (out.size() < maxBytes) {
            int n = in.read(buf, 0, Math.min(buf.length, maxBytes - out.size()));
            if (n < 0) break;
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String extractModelOutputText(String json) {
        int stepsIdx = json.indexOf("\"steps\"");
        if (stepsIdx >= 0) {
            int pos = stepsIdx;
            String last = "";
            while (true) {
                int mo = json.indexOf("\"model_output\"", pos);
                if (mo < 0) break;
                int contentIdx = json.indexOf("\"content\"", mo);
                if (contentIdx >= 0) {
                    String t = extractTextPartFrom(json, contentIdx);
                    if (t != null && !t.isEmpty()) last = t;
                    pos = contentIdx + 9;
                } else {
                    pos = mo + 14;
                }
            }
            if (!last.isEmpty()) return last;
        }
        int outputsIdx = json.indexOf("\"outputs\"");
        if (outputsIdx >= 0) {
            String t = extractTextPartFrom(json, outputsIdx);
            if (t != null && !t.isEmpty()) return t;
        }
        return extractTextPart(json);
    }

    private static String extractFunctionCallName(String json) {
        int fcIdx = json.indexOf("\"functionCall\"");
        if (fcIdx < 0) return null;
        return extractStringFieldFrom(json, fcIdx, "name");
    }

    private static String extractTextPart(String json) {
        return extractTextPartFrom(json, 0);
    }

    private static String extractTextPartFrom(String json, int fromIndex) {
        int idx = json.indexOf("\"text\"", fromIndex);
        while (idx >= 0) {
            int colon = json.indexOf(':', idx + 6);
            if (colon > 0) {
                int q1 = nextNonWs(json, colon + 1);
                if (q1 >= 0 && json.charAt(q1) == '"') {
                    String val = readJsonString(json, q1);
                    if (val != null && !val.isEmpty() && !"text".equals(val)) return val;
                }
            }
            idx = json.indexOf("\"text\"", idx + 6);
        }
        return "";
    }

    private static int nextNonWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i < s.length() ? i : -1;
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
        int q1 = nextNonWs(json, colon + 1);
        if (q1 < 0 || json.charAt(q1) != '"') return "";
        String v = readJsonString(json, q1);
        return v == null ? "" : v;
    }

    private static int extractOptionalBooleanField(String json, String key) {
        String target = "\"" + key + "\"";
        int idx = json.indexOf(target);
        if (idx < 0) return -1;
        int colon = json.indexOf(':', idx + target.length());
        if (colon < 0) return -1;
        int i = nextNonWs(json, colon + 1);
        if (i < 0) return -1;
        if (json.regionMatches(true, i, "true", 0, 4)) return 1;
        if (json.regionMatches(true, i, "false", 0, 5)) return 0;
        return -1;
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
                else if (c == 'u' && i + 4 < s.length()) {
                    try {
                        out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                    } catch (NumberFormatException ignored) {
                        out.append('u');
                    }
                } else out.append(c);
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
}
