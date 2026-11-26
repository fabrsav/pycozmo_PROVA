import dev.s200.cozmo.core.*;
import java.nio.charset.StandardCharsets;

public final class GeminiLiveTest {
    static int checks = 0;
    static void check(boolean cond, String msg) {
        checks++;
        if (!cond) throw new AssertionError(msg);
    }

    public static void main(String[] args) throws Exception {
        check("gemini-3.8-flash".equals(GeminiLiveClient.normalizeModel(null)), "null model defaults to gemini-3.8-flash");
        check("gemini-3.8-flash".equals(GeminiLiveClient.normalizeModel("models/gemini-robotics-er-2-streaming-preview")), "migrates legacy streaming preview model");
        check("gemini-3.8-flash".equals(GeminiLiveClient.normalizeModel("gemini-2.5-flash")), "migrates deprecated 2.5 model");
        check("gemini-3.8-flash".equals(GeminiLiveClient.normalizeModel("gemini-2.0-flash-live-001")), "migrates deprecated 2.0 model");

        String req = GeminiLiveClient.buildInteractionRequest(
            "models/gemini-robotics-er-2-streaming-preview",
            null,
            "int_prev_001",
            "batt=4.50V head=0.18",
            "dark=false faces=1",
            "Osserva chi hai davanti",
            new byte[]{1, 2, 3});
        check(req.contains("\"model\":\"gemini-3.8-flash\""), "request uses normalized gemini-3.8-flash");
        check(req.contains("\"system_instruction\""), "request includes system_instruction");
        check(req.contains("\"previous_interaction_id\":\"int_prev_001\""), "request includes previous_interaction_id");
        check(req.contains("\"type\":\"image\"") && req.contains("image/jpeg"), "request includes inline jpeg input");
        check(req.contains("\"response_format\"") && req.contains("\"perception\""), "request includes JSON schema response_format");

        String q = "\"";
        String innerJson = "{"
            + q + "perception" + q + ":" + q + "Vedo una mano davanti a me" + q + ","
            + q + "expression" + q + ":" + q + "CURIOUS" + q + ","
            + q + "clip" + q + ":" + q + "CURIOUS_INSPECT" + q + ","
            + q + "vocalization" + q + ":" + q + "curious" + q + ","
            + q + "say" + q + ":" + q + "ciao mano!" + q + ","
            + q + "backpack" + q + ":" + q + "cyan" + q + ","
            + q + "head_delta_deg" + q + ":6.0,"
            + q + "lift_delta_mm" + q + ":11.5,"
            + q + "headlight" + q + ":true,"
            + q + "gaze_x" + q + ":2.5,"
            + q + "gaze_y" + q + ":-1.2,"
            + q + "remember" + q + ":" + q + "Una persona ha salutato con la mano" + q + ","
            + q + "reason" + q + ":" + q + "Gesto rilevato dalla camera" + q
            + "}";
        String escapedInner = GeminiLiveClient.LiveAction.escapeJson(innerJson);
        String stepsResp = "{"
            + q + "id" + q + ":" + q + "int_abc123" + q + ","
            + q + "status" + q + ":" + q + "completed" + q + ","
            + q + "steps" + q + ":[{" + q + "type" + q + ":" + q + "model_output" + q + ","
            + q + "content" + q + ":[{" + q + "type" + q + ":" + q + "text" + q + "," + q + "text" + q + ":" + q + escapedInner + q + "}]}]}";

        GeminiLiveClient.LiveAction act = GeminiLiveClient.parseInteractionResponse(stepsResp);
        check(act != null && "int_abc123".equals(act.interactionId), "extracts interaction id");
        check("Vedo una mano davanti a me".equals(act.perception), "extracts perception");
        check("Una persona ha salutato con la mano".equals(act.remember), "extracts remember field");
        check(Math.abs(act.gazeX - 2.5f) < 0.01f && Math.abs(act.gazeY + 1.2f) < 0.01f, "extracts gaze target");
        check(Math.abs(act.liftDeltaMm - 11.5f) < 0.01f && act.headlightCmd == 1, "extracts lift_delta_mm and headlight");
        AiSupervisor.Intention intent = act.toIntention(1000L);
        check(intent != null && intent.expression == Personality.Expression.CURIOUS
            && "CURIOUS_INSPECT".equals(intent.clipId)
            && Math.abs(intent.liftDeltaMm - 11.5f) < 0.01f
            && "ciao mano!".equals(intent.sayText), "converts interaction response to validated Intention");

        String chunkedRaw = "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n";
        String decoded = new String(GeminiLiveClient.decodeChunkedBody(chunkedRaw.getBytes(StandardCharsets.ISO_8859_1)), StandardCharsets.UTF_8);
        check("hello world".equals(decoded), "decodes multi-chunk HTTP body");

        String driveResp = "{" + q + "serverContent" + q + ":{" + q + "modelTurn" + q + ":{" + q + "parts" + q + ":[{" + q + "functionCall" + q + ":{" + q + "name" + q + ":" + q + "drive" + q + "," + q + "args" + q + ":{" + q + "vl" + q + ":120.0," + q + "vr" + q + ":-120.0," + q + "duration_s" + q + ":5.0," + q + "gaze_x" + q + ":99.0," + q + "head_delta_deg" + q + ":45.0," + q + "reason" + q + ":" + q + "Giro breve" + q + "}}}]}}}";
        GeminiLiveClient.LiveAction drv = GeminiLiveClient.parseServerMessage(driveResp);
        check(drv != null && drv.driveLeft == 40f && drv.driveRight == -40f && drv.durationS == 1.5f
            && drv.gazeX == 5.5f && drv.headDeltaDeg == 10.0f, "clamps drive, gaze, and head delta to safe bounds");

        System.out.println("PASS " + checks + " GeminiLiveClient checks");
    }
}
