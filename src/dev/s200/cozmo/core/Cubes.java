package dev.s200.cozmo.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tracks Cozmo's three Light Cubes (Paperclip, Lamp, Deli Slicer), BLE connections,
 * LED animations, and the Quick Tap reflex game. Pure Java, zero Android dependencies.
 */
public final class Cubes {
    public static final class Cube {
        public final int type; // 1, 2, 3
        public final int slot; // 0, 1, 2
        public final String name;
        public Integer factoryId;
        public Integer objectId;
        public int rssi = 0;
        public boolean connected = false;
        public boolean moving = false;
        public int upAxis = 7;
        public int batteryLevel = -1;
        public int taps = 0;
        public long lastHeardMs = 0;
        public long lastConnectTryMs = 0;
        public long lastTapMs = 0;
        public long lastMovedMs = 0;
        public String lightPattern = "off";

        Cube(int type, String name) {
            this.type = type;
            this.slot = type - 1;
            this.name = name;
        }
    }

    private final Cube[] cubes = {
        new Cube(1, "Cubo 1 (Graffetta)"),
        new Cube(2, "Cubo 2 (Lampada)"),
        new Cube(3, "Cubo 3 (Affettatrice)")
    };

    private final List<Integer> savedFactoryIds = new ArrayList<>();
    private boolean autoConnect = true;
    private boolean quickTapActive = false;
    private int quickTapScore = 0;
    private int quickTapTargetSlot = 0;
    private long quickTapRoundStartMs = 0;

    public synchronized void setSavedFactoryIds(List<Integer> ids) {
        savedFactoryIds.clear();
        if (ids != null) savedFactoryIds.addAll(ids);
    }

    public synchronized List<Integer> getSavedFactoryIds() {
        return new ArrayList<>(savedFactoryIds);
    }

    public synchronized void reset() {
        for (Cube c : cubes) {
            c.connected = false;
            c.objectId = null;
            c.moving = false;
            c.lastConnectTryMs = 0;
            c.lightPattern = "off";
        }
        quickTapActive = false;
    }

    public synchronized void setAutoConnect(boolean enabled) {
        this.autoConnect = enabled;
    }

    public synchronized boolean isAutoConnect() {
        return autoConnect;
    }

    public synchronized Cube getByType(int objectType) {
        if (objectType >= 1 && objectType <= 3) return cubes[objectType - 1];
        return null;
    }

    public synchronized Cube getByObjectId(int objectId) {
        for (Cube c : cubes) {
            if (c.connected && c.objectId != null && c.objectId == objectId) return c;
        }
        if (objectId >= 0 && objectId < 3) return cubes[objectId];
        return null;
    }

    /**
     * Handles ObjectAvailable (0xf3). Returns an ObjectConnect packet (0x05) if auto-connect should be requested.
     */
    public synchronized Wire.Packet onAvailable(Wire.CubeAvailable pkt, long nowMs) {
        Cube c = getByType(pkt.objectType);
        if (c == null) return null;
        c.factoryId = pkt.factoryId;
        c.rssi = pkt.rssi;
        c.lastHeardMs = nowMs;
        if (autoConnect && !c.connected && (c.lastConnectTryMs == 0 || nowMs - c.lastConnectTryMs >= 5000)) {
            c.lastConnectTryMs = nowMs;
            return Wire.objectConnect(pkt.factoryId, c.slot);
        }
        return null;
    }

    /** Returns ObjectConnect packets for all heard but disconnected cubes. */
    public synchronized List<Wire.Packet> connectAllHeard(long nowMs) {
        List<Wire.Packet> out = new ArrayList<>();
        for (Cube c : cubes) {
            if (c.factoryId != null && !c.connected) {
                c.lastConnectTryMs = nowMs;
                out.add(Wire.objectConnect(c.factoryId, c.slot));
            }
        }
        return out;
    }

    public synchronized Cube onConnectionState(Wire.CubeConnection pkt) {
        Cube c = getByType(pkt.objectType);
        if (c == null) return null;
        c.lastConnectTryMs = 0;
        if (pkt.connected) {
            c.factoryId = pkt.factoryId;
            c.objectId = pkt.objectId;
            c.connected = true;
            c.lightPattern = "connected";
        } else {
            c.connected = false;
            c.objectId = null;
            c.moving = false;
            c.lightPattern = "off";
        }
        return c;
    }

    public synchronized Cube onTapped(Wire.CubeTapped pkt, long nowMs) {
        Cube c = getByObjectId(pkt.objectId);
        if (c == null) return null;
        c.taps += Math.max(1, pkt.numTaps);
        c.lastTapMs = nowMs;
        if (quickTapActive && c.slot == quickTapTargetSlot) {
            quickTapScore++;
            quickTapTargetSlot = (quickTapTargetSlot + 1) % 3;
            quickTapRoundStartMs = nowMs;
        }
        return c;
    }

    public synchronized Cube onMoved(Wire.CubeMoved pkt, long nowMs) {
        Cube c = getByObjectId(pkt.objectId);
        if (c == null) return null;
        c.moving = true;
        c.upAxis = pkt.axis;
        c.lastMovedMs = nowMs;
        return c;
    }

    public synchronized Cube onStopped(int objectId) {
        Cube c = getByObjectId(objectId);
        if (c != null) c.moving = false;
        return c;
    }

    public synchronized Cube onUpAxis(Wire.CubeUpAxis pkt) {
        Cube c = getByObjectId(pkt.objectId);
        if (c != null) c.upAxis = pkt.axis;
        return c;
    }

    public synchronized Cube onPower(Wire.CubePower pkt) {
        Cube c = getByObjectId(pkt.objectId);
        if (c != null) c.batteryLevel = pkt.batteryLevel;
        return c;
    }

    /** Generates CubeId (0x10) + CubeLights (0x04) packets for all connected cubes. */
    public synchronized List<Wire.Packet> lightConnectedCubes(String preset) {
        List<Wire.Packet> pkts = new ArrayList<>();
        String p = preset == null ? "cyan" : preset.toLowerCase(Locale.ROOT);
        for (Cube c : cubes) {
            if (!c.connected || c.objectId == null) continue;
            c.lightPattern = p;
            pkts.addAll(buildCubeLightPackets(c.objectId, p));
        }
        return pkts;
    }

    public static List<Wire.Packet> buildCubeLightPackets(int objectId, String preset) {
        List<Wire.Packet> pkts = new ArrayList<>();
        String p = preset == null ? "cyan" : preset.toLowerCase(Locale.ROOT);
        int rotation = 0;
        byte[] l0, l1, l2, l3;
        switch (p) {
            case "green":
                l0 = l1 = l2 = l3 = Wire.solidLight(0, 255, 60);
                break;
            case "red":
                l0 = l1 = l2 = l3 = Wire.solidLight(255, 20, 20);
                break;
            case "blue":
                l0 = l1 = l2 = l3 = Wire.solidLight(20, 80, 255);
                break;
            case "spin":
                rotation = 12;
                l0 = Wire.solidLight(0, 240, 255);
                l1 = Wire.solidLight(255, 160, 0);
                l2 = Wire.solidLight(0, 255, 90);
                l3 = Wire.solidLight(200, 40, 255);
                break;
            case "off":
                l0 = l1 = l2 = l3 = Wire.solidLight(0, 0, 0);
                break;
            case "cyan":
            default:
                l0 = l1 = l2 = l3 = Wire.solidLight(0, 220, 255);
                break;
        }
        pkts.add(Wire.cubeId(objectId, rotation));
        pkts.add(Wire.cubeLights(l0, l1, l2, l3));
        return pkts;
    }

    public synchronized void startQuickTap(long nowMs) {
        quickTapActive = true;
        quickTapScore = 0;
        quickTapTargetSlot = 0;
        quickTapRoundStartMs = nowMs;
    }

    public synchronized void stopQuickTap() {
        quickTapActive = false;
    }

    public synchronized boolean isQuickTapActive() {
        return quickTapActive;
    }

    public synchronized int getQuickTapScore() {
        return quickTapScore;
    }

    public synchronized int getQuickTapTarget() {
        return quickTapTargetSlot;
    }

    /** Packets lighting the Quick Tap target cube green and the other connected cubes off. */
    public synchronized List<Wire.Packet> quickTapLightPackets() {
        List<Wire.Packet> pkts = new ArrayList<>();
        for (Cube c : cubes) {
            if (!c.connected || c.objectId == null) continue;
            pkts.addAll(buildCubeLightPackets(c.objectId, c.slot == quickTapTargetSlot ? "green" : "off"));
        }
        return pkts;
    }

    public synchronized int registerSimulatedOrButtonTap(long nowMs) {
        if (!quickTapActive) startQuickTap(nowMs);
        quickTapScore++;
        quickTapTargetSlot = (quickTapTargetSlot + 1) % 3;
        quickTapRoundStartMs = nowMs;
        return quickTapScore;
    }

    public synchronized int connectedCount() {
        int n = 0;
        for (Cube c : cubes) if (c.connected) n++;
        return n;
    }

    public synchronized int heardCount() {
        int n = 0;
        for (Cube c : cubes) if (c.factoryId != null) n++;
        return n;
    }

    public synchronized boolean anyRecentActivity(long nowMs) {
        for (Cube c : cubes) {
            if ((c.lastTapMs > 0 && nowMs - c.lastTapMs < 3500) || (c.lastMovedMs > 0 && nowMs - c.lastMovedMs < 2500)) {
                return true;
            }
        }
        return false;
    }

    public static String axisName(int axis) {
        switch (axis) {
            case 0: return "-X";
            case 1: return "+X";
            case 2: return "-Y";
            case 3: return "+Y";
            case 4: return "-Z (Capovolto)";
            case 5: return "+Z (Dritto)";
            default: return "—";
        }
    }

    public synchronized String summary(long nowMs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cubes.length; i++) {
            Cube c = cubes[i];
            if (i > 0) sb.append('\n');
            sb.append(c.name).append(": ");
            if (c.connected) {
                sb.append(String.format(Locale.ITALY, "Connesso (ID=%d, S/N=0x%08x, RSSI=%d dBm)",
                    c.objectId, c.factoryId != null ? c.factoryId : 0, c.rssi));
                sb.append(" · Tocchi=").append(c.taps);
                sb.append(" · Asse=").append(axisName(c.upAxis));
                if (c.moving) sb.append(" · IN MOVIMENTO");
            } else if (c.factoryId != null) {
                long ageSec = Math.max(0, (nowMs - c.lastHeardMs) / 1000);
                sb.append(String.format(Locale.ITALY, "Rilevato BLE (S/N=0x%08x, RSSI=%d dBm, %d s fa) · connessione...",
                    c.factoryId, c.rssi, ageSec));
            } else {
                sb.append("Nessun segnale BLE (verifica batteria cubo)");
            }
        }
        if (quickTapActive) {
            sb.append(String.format(Locale.ITALY, "\nGioco Quick Tap: ATTIVO · Punteggio=%d · Target=Cubo %d",
                quickTapScore, quickTapTargetSlot + 1));
        }
        return sb.toString();
    }
}
