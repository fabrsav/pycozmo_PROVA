import dev.s200.cozmo.core.*;

public final class GeminiLiveTest {
    static int checks = 0;
    static void check(boolean cond, String msg) {
        checks++;
        if (!cond) throw new AssertionError(msg);
    }

    public static void main(String[] args) throws Exception {
        String setup = GeminiLiveClient.buildSetupMessage(null, null);
        check(setup.contains(GeminiLiveClient.DEFAULT_MODEL), "setup includes default streaming preview model");
        check(setup.contains("\"responseModalities\":[\"TEXT\"]"), "setup requests TEXT modality");
        check(setup.contains("\"name\":\"react\"") && setup.contains("\"name\":\"drive\""), "setup declares react and drive tools");

        String turn = GeminiLiveClient.buildContextTurn("batt=4.80V head=0.18", "dark=true", "Esplora con cautela", new byte[]{1, 2, 3});
        check(turn.contains("batt=4.80V") && turn.contains("image/jpeg"), "turn includes telemetry, vision, and base64 jpeg");

        String q = "\"";
        String sampleResp = "{" + q + "serverContent" + q + ":{" + q + "modelTurn" + q + ":{" + q + "parts" + q + ":[{" + q + "functionCall" + q + ":{" + q + "name" + q + ":" + q + "react" + q + "," + q + "args" + q + ":{" + q + "expression" + q + ":" + q + "CURIOUS" + q + "," + q + "vocalization" + q + ":" + q + "curious" + q + "," + q + "say" + q + ":" + q + "buio?" + q + "," + q + "backpack" + q + ":" + q + "cyan" + q + "," + q + "head_delta_deg" + q + ":6.0," + q + "reason" + q + ":" + q + "Stanza scura" + q + "}}}]}}}";
        GeminiLiveClient.LiveAction act = GeminiLiveClient.parseServerMessage(sampleResp);
        check(act != null && "react".equals(act.name), "parses functionCall react");
        AiSupervisor.Intention intent = act.toIntention(1000L);
        check(intent != null && intent.expression == Personality.Expression.CURIOUS && "buio?".equals(intent.sayText), "converts LiveAction to validated Intention");

        String driveResp = "{" + q + "serverContent" + q + ":{" + q + "modelTurn" + q + ":{" + q + "parts" + q + ":[{" + q + "functionCall" + q + ":{" + q + "name" + q + ":" + q + "drive" + q + "," + q + "args" + q + ":{" + q + "vl" + q + ":120.0," + q + "vr" + q + ":-120.0," + q + "duration_s" + q + ":5.0," + q + "reason" + q + ":" + q + "Giro breve" + q + "}}}]}}}";
        GeminiLiveClient.LiveAction drv = GeminiLiveClient.parseServerMessage(driveResp);
        check(drv != null && drv.driveLeft == 40f && drv.driveRight == -40f && drv.durationS == 1.5f, "clamps drive speed and duration to safe bounds");

        byte[] wsFrame = GeminiLiveClient.encodeWsTextFrame("{\"ping\":true}");
        check(wsFrame.length > 6 && (wsFrame[0] & 0x8F) == 0x81 && (wsFrame[1] & 0x80) != 0, "encodes masked client WebSocket text frame");

        System.out.println("PASS " + checks + " GeminiLiveClient checks");
    }
}
