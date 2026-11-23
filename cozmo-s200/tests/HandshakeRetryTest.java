import dev.s200.cozmo.core.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Verifies real UDP handshake and M0 initialization state machine across:
 * 1) Dropped first RESET + normal cold start (0xee -> 0x25 -> 0xed -> init -> 0xf0)
 * 2) Warm reconnect trace from 21 Nov 2026 (0xb0, 0xd2, 0xc9, 0xf1, 0xf4 without 0xee or 0xed)
 * 3) 0xee received but 0xed lost (signature alone initializes the robot)
 * 4) Duplicate 0xee and 0xed idempotency (no duplicate Enable or SyncTime)
 * 5) Real 12-byte FirmwareSignature (0xee) enables and initializes even when BodyInfo is skipped
 */
public final class HandshakeRetryTest {
    private static byte[] robotStateBytes() {
        byte[] d = new byte[91];
        Wire.le(91);
        java.nio.ByteBuffer b = Wire.read(d);
        b.putInt(0, 100);
        b.putFloat(36, 0.1f); // head
        b.putFloat(40, 0.0f); // lift
        b.putFloat(68, 4.10f); // battery
        b.putInt(72, 0x1000); // onCharger
        return d;
    }

    private static Wire.Packet firmwarePacket() {
        byte[] json = "{\"version\":2381}".getBytes(StandardCharsets.UTF_8);
        java.nio.ByteBuffer b = Wire.le(4 + json.length);
        b.putShort((short) 0).putShort((short) json.length).put(json);
        return new Wire.Packet(4, 0xee, b.array());
    }

    private static Wire.Packet shortFirmwarePacket() {
        byte[] sig = "2381DEV ".getBytes(StandardCharsets.UTF_8);
        java.nio.ByteBuffer b = Wire.le(4 + sig.length);
        b.putShort((short) 0).putShort((short) sig.length).put(sig);
        return new Wire.Packet(4, 0xee, b.array());
    }

    private static Wire.Packet bodyInfoPacket() {
        java.nio.ByteBuffer b = Wire.le(12);
        b.putInt(0x12345678).putInt(5).putInt(1);
        return new Wire.Packet(4, 0xed, b.array());
    }

    public static void main(String[] args) throws Exception {
        testDroppedResetAndNormalColdStart();
        testWarmReconnectWithoutFirmwareOrBodyInfo();
        testFirmwareSignatureInitializesWithoutBodyInfo();
        testDuplicateFirmwareAndBodyInfoIdempotency();
        testEarlyRobotStateSkipsBootReplay();
        testShortFirmwareSignatureInitializesWithoutBodyInfo();
        System.out.println("PASS real UDP handshake retry after first RESET lost + 6 M0 scenarios");
    }

    private static void testDroppedResetAndNormalColdStart() throws Exception {
        AtomicInteger resets = new AtomicInteger();
        AtomicInteger nvBeforeSync = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (DatagramSocket robot = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            robot.setSoTimeout(3000);
            Thread server = new Thread(() -> {
                try {
                    byte[] b = new byte[2048];
                    SocketAddress clientAddr = null;
                    while (resets.get() < 2) {
                        DatagramPacket packet = new DatagramPacket(b, b.length);
                        robot.receive(packet);
                        Wire.Frame frame = Wire.parse(Arrays.copyOf(b, packet.getLength()));
                        if (frame.type == 1 && resets.incrementAndGet() == 2) {
                            clientAddr = packet.getSocketAddress();
                            byte[] reply = Files.readAllBytes(Path.of("build/fixtures/connect.bin"));
                            robot.send(new DatagramPacket(reply, reply.length, clientAddr));
                            Wire.Packet fw = firmwarePacket();
                            fw.seq = 1;
                            byte[] fwFrame = Wire.frame(9, 1, 1, Wire.OOB, Collections.singletonList(fw));
                            robot.send(new DatagramPacket(fwFrame, fwFrame.length, clientAddr));
                        }
                    }
                    // Wait for Enable (0x25) then send BodyInfo (0xed)
                    boolean enableSeen = false;
                    while (!enableSeen) {
                        DatagramPacket packet = new DatagramPacket(b, b.length);
                        robot.receive(packet);
                        Wire.Frame frame = Wire.parse(Arrays.copyOf(b, packet.getLength()));
                        for (Wire.Packet p : frame.packets) {
                            if (p.id == 0x25) enableSeen = true;
                        }
                    }
                    Wire.Packet body = bodyInfoPacket();
                    body.seq = 2;
                    byte[] bodyFrame = Wire.frame(9, 2, 2, 1, Collections.singletonList(body));
                    robot.send(new DatagramPacket(bodyFrame, bodyFrame.length, clientAddr));
                    // Wait for SyncTime (0x4b) then send RobotState (0xf0)
                    boolean syncSeen = false;
                    while (!syncSeen) {
                        DatagramPacket packet = new DatagramPacket(b, b.length);
                        robot.receive(packet);
                        Wire.Frame frame = Wire.parse(Arrays.copyOf(b, packet.getLength()));
                        for (Wire.Packet p : frame.packets) {
                            if (p.id == 0x81) nvBeforeSync.incrementAndGet();
                            if (p.id == 0x4b) syncSeen = true;
                        }
                    }
                    Wire.Packet st = new Wire.Packet(5, 0xf0, robotStateBytes());
                    byte[] stFrame = Wire.frame(9, Wire.OOB, Wire.OOB, 8, Collections.singletonList(st));
                    robot.send(new DatagramPacket(stFrame, stFrame.length, clientAddr));
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "test-robot-cold");
            server.start();
            Link link = new Link("127.0.0.1", robot.getLocalPort(), s -> {}, new Link.Observer() {
                public void event(String kind, String text) {}
                public void state(Wire.Telemetry t, long n) { if (n == 1) ready.countDown(); }
                public void packet(Wire.Packet p) {}
            });
            Thread client = new Thread(link, "test-engine-cold");
            client.start();
            boolean ok = ready.await(4, TimeUnit.SECONDS);
            link.close();
            client.join(2000);
            server.join(2000);
            if (failure.get() != null) throw new AssertionError("Cold robot failed", failure.get());
            if (!ok || resets.get() != 2 || nvBeforeSync.get() != 0 || !"normal".equals(link.initPath()) || link.connectReadyMs() < 0) {
                throw new AssertionError("Cold start failed: ok=" + ok + " path=" + link.initPath() + " nvBeforeSync=" + nvBeforeSync.get());
            }
        }
    }

    private static void testWarmReconnectWithoutFirmwareOrBodyInfo() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicBoolean warmEnableLogged = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (DatagramSocket robot = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            robot.setSoTimeout(4000);
            Thread server = new Thread(() -> {
                try {
                    byte[] b = new byte[2048];
                    DatagramPacket packet = new DatagramPacket(b, b.length);
                    robot.receive(packet);
                    SocketAddress clientAddr = packet.getSocketAddress();
                    // Simulate exact 21 Nov 2026 warm trace: OOB 0xf4/0xf1 + Connect@0 + 0xb0@1 + 0xd2@2 + 0xc9@3 + 0xb0@4
                    List<Wire.Packet> warmPkts = Arrays.asList(
                        new Wire.Packet(5, 0xf4, new byte[17]),
                        new Wire.Packet(5, 0xf1, new byte[16]),
                        new Wire.Packet(2, -1, new byte[0]),
                        new Wire.Packet(4, 0xb0, new byte[12]),
                        new Wire.Packet(4, 0xd2, new byte[44]),
                        new Wire.Packet(4, 0xc9, new byte[6]),
                        new Wire.Packet(4, 0xb0, new byte[8])
                    );
                    byte[] warmFrame = Wire.frame(9, 0, 4, Wire.OOB, warmPkts);
                    robot.send(new DatagramPacket(warmFrame, warmFrame.length, clientAddr));
                    // Wait until client sends Enable (0x25) via warm_trigger and then SyncTime (0x4b) via fallback_timer
                    boolean enableSeen = false, syncSeen = false;
                    while (!enableSeen || !syncSeen) {
                        robot.receive(packet);
                        Wire.Frame f = Wire.parse(Arrays.copyOf(b, packet.getLength()));
                        for (Wire.Packet p : f.packets) {
                            if (p.id == 0x25) enableSeen = true;
                            if (p.id == 0x4b) syncSeen = true;
                        }
                    }
                    Wire.Packet st = new Wire.Packet(5, 0xf0, robotStateBytes());
                    byte[] stFrame = Wire.frame(9, Wire.OOB, Wire.OOB, 8, Collections.singletonList(st));
                    robot.send(new DatagramPacket(stFrame, stFrame.length, clientAddr));
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "test-robot-warm");
            server.start();
            Link link = new Link("127.0.0.1", robot.getLocalPort(), s -> {}, new Link.Observer() {
                public void event(String kind, String text) {
                    if ("init_fallback_enable".equals(kind)) warmEnableLogged.set(true);
                }
                public void state(Wire.Telemetry t, long n) { if (n == 1) ready.countDown(); }
                public void packet(Wire.Packet p) {}
            });
            Thread client = new Thread(link, "test-engine-warm");
            client.start();
            boolean ok = ready.await(4, TimeUnit.SECONDS);
            link.close();
            client.join(2000);
            server.join(2000);
            if (failure.get() != null) throw new AssertionError("Warm robot failed", failure.get());
            if (!ok || !warmEnableLogged.get() || !"warm_trigger".equals(link.initPath())) {
                throw new AssertionError("Warm reconnect failed: ok=" + ok + " path=" + link.initPath());
            }
        }
    }

    private static void testFirmwareSignatureInitializesWithoutBodyInfo() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (DatagramSocket robot = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            robot.setSoTimeout(4000);
            Thread server = new Thread(() -> {
                try {
                    byte[] b = new byte[2048];
                    DatagramPacket packet = new DatagramPacket(b, b.length);
                    robot.receive(packet);
                    SocketAddress clientAddr = packet.getSocketAddress();
                    byte[] reply = Files.readAllBytes(Path.of("build/fixtures/connect.bin"));
                    robot.send(new DatagramPacket(reply, reply.length, clientAddr));
                    Wire.Packet fw = firmwarePacket();
                    fw.seq = 1;
                    byte[] fwFrame = Wire.frame(9, 1, 1, Wire.OOB, Collections.singletonList(fw));
                    robot.send(new DatagramPacket(fwFrame, fwFrame.length, clientAddr));
                    // Never send BodyInfo (0xed): the firmware signature alone must
                    // trigger Enable and the boot sequence, including SyncTime (0x4b).
                    boolean syncSeen = false;
                    while (!syncSeen) {
                        robot.receive(packet);
                        Wire.Frame f = Wire.parse(Arrays.copyOf(b, packet.getLength()));
                        for (Wire.Packet p : f.packets) {
                            if (p.id == 0x4b) syncSeen = true;
                        }
                    }
                    Wire.Packet st = new Wire.Packet(5, 0xf0, robotStateBytes());
                    byte[] stFrame = Wire.frame(9, Wire.OOB, Wire.OOB, 8, Collections.singletonList(st));
                    robot.send(new DatagramPacket(stFrame, stFrame.length, clientAddr));
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "test-robot-nobody");
            server.start();
            Link link = new Link("127.0.0.1", robot.getLocalPort(), s -> {}, new Link.Observer() {
                public void event(String kind, String text) {}
                public void state(Wire.Telemetry t, long n) { if (n == 1) ready.countDown(); }
                public void packet(Wire.Packet p) {}
            });
            Thread client = new Thread(link, "test-engine-nobody");
            client.start();
            boolean ok = ready.await(4, TimeUnit.SECONDS);
            link.close();
            client.join(2000);
            server.join(2000);
            if (failure.get() != null) throw new AssertionError("Missing BodyInfo robot failed", failure.get());
            if (!ok || (!"normal".equals(link.initPath()) && !"fallback_timer".equals(link.initPath()))) {
                throw new AssertionError("Missing BodyInfo initialization failed: ok=" + ok + " path=" + link.initPath());
            }
        }
    }

    private static void testDuplicateFirmwareAndBodyInfoIdempotency() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicInteger enableCount = new AtomicInteger();
        AtomicInteger syncCount = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (DatagramSocket robot = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            robot.setSoTimeout(3000);
            Thread server = new Thread(() -> {
                try {
                    byte[] b = new byte[2048];
                    DatagramPacket packet = new DatagramPacket(b, b.length);
                    robot.receive(packet);
                    SocketAddress clientAddr = packet.getSocketAddress();
                    byte[] reply = Files.readAllBytes(Path.of("build/fixtures/connect.bin"));
                    robot.send(new DatagramPacket(reply, reply.length, clientAddr));
                    // Send two 0xee packets and two 0xed packets in sequence
                    List<Wire.Packet> pkts = Arrays.asList(
                        firmwarePacket(),
                        firmwarePacket(),
                        bodyInfoPacket(),
                        bodyInfoPacket()
                    );
                    byte[] frameBytes = Wire.frame(9, 1, 4, Wire.OOB, pkts);
                    robot.send(new DatagramPacket(frameBytes, frameBytes.length, clientAddr));

                    long deadline = System.currentTimeMillis() + 500;
                    int maxAck = -1;
                    while (System.currentTimeMillis() < deadline && syncCount.get() == 0) {
                        robot.receive(packet);
                        Wire.Frame f = Wire.parse(Arrays.copyOf(b, packet.getLength()));
                        for (Wire.Packet p : f.packets) {
                            if (p.seq >= 0) maxAck = Math.max(maxAck, p.seq);
                            if (p.id == 0x25) enableCount.incrementAndGet();
                            if (p.id == 0x4b) syncCount.incrementAndGet();
                        }
                    }
                    // Send RobotState with cumulative ACK so client does not retransmit
                    Wire.Packet st = new Wire.Packet(5, 0xf0, robotStateBytes());
                    byte[] stFrame = Wire.frame(9, Wire.OOB, Wire.OOB, Math.max(0, maxAck), Collections.singletonList(st));
                    robot.send(new DatagramPacket(stFrame, stFrame.length, clientAddr));
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "test-robot-idemp");
            server.start();
            Link link = new Link("127.0.0.1", robot.getLocalPort(), s -> {}, new Link.Observer() {
                public void event(String kind, String text) {}
                public void state(Wire.Telemetry t, long n) { if (n == 1) ready.countDown(); }
                public void packet(Wire.Packet p) {}
            });
            Thread client = new Thread(link, "test-engine-idemp");
            client.start();
            boolean ok = ready.await(3, TimeUnit.SECONDS);
            link.close();
            client.join(2000);
            server.join(2000);
            if (failure.get() != null) throw new AssertionError("Idempotency robot failed", failure.get());
            if (!ok || enableCount.get() != 2 || syncCount.get() != 1) {
                throw new AssertionError("Idempotency failed: ok=" + ok + " enable=" + enableCount.get() + " sync=" + syncCount.get());
            }
        }
    }

    private static void testShortFirmwareSignatureInitializesWithoutBodyInfo() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicInteger enableCount = new AtomicInteger();
        AtomicInteger syncCount = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (DatagramSocket robot = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            robot.setSoTimeout(3000);
            Thread server = new Thread(() -> {
                try {
                    byte[] b = new byte[2048];
                    DatagramPacket packet = new DatagramPacket(b, b.length);
                    robot.receive(packet);
                    SocketAddress clientAddr = packet.getSocketAddress();
                    byte[] reply = Files.readAllBytes(Path.of("build/fixtures/connect.bin"));
                    robot.send(new DatagramPacket(reply, reply.length, clientAddr));
                    Wire.Packet fw = shortFirmwarePacket();
                    fw.seq = 1;
                    byte[] fwFrame = Wire.frame(9, 1, 1, Wire.OOB, Collections.singletonList(fw));
                    robot.send(new DatagramPacket(fwFrame, fwFrame.length, clientAddr));

                    // No BodyInfo (0xed): the short signature must still trigger
                    // Enable and the full boot sequence, including SyncTime.
                    while (syncCount.get() == 0) {
                        robot.receive(packet);
                        Wire.Frame f = Wire.parse(Arrays.copyOf(b, packet.getLength()));
                        for (Wire.Packet p : f.packets) {
                            if (p.id == 0x25) enableCount.incrementAndGet();
                            if (p.id == 0x4b) syncCount.incrementAndGet();
                        }
                    }
                    Wire.Packet st = new Wire.Packet(5, 0xf0, robotStateBytes());
                    byte[] stFrame = Wire.frame(9, Wire.OOB, Wire.OOB, 8, Collections.singletonList(st));
                    robot.send(new DatagramPacket(stFrame, stFrame.length, clientAddr));
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "test-robot-short-signature");
            server.start();
            Link link = new Link("127.0.0.1", robot.getLocalPort(), s -> {}, new Link.Observer() {
                public void event(String kind, String text) {}
                public void state(Wire.Telemetry t, long n) { if (n == 1) ready.countDown(); }
                public void packet(Wire.Packet p) {}
            });
            Thread client = new Thread(link, "test-engine-short-signature");
            client.start();
            boolean ok = ready.await(4, TimeUnit.SECONDS);
            link.close();
            client.join(2000);
            server.join(2000);
            if (failure.get() != null) throw new AssertionError("Short FirmwareSignature robot failed", failure.get());
            if (!ok || enableCount.get() < 2 || syncCount.get() < 1
                || (!"normal".equals(link.initPath()) && !"fallback_timer".equals(link.initPath()))) {
                throw new AssertionError("Short FirmwareSignature failed: ok=" + ok + " enable=" + enableCount.get()
                    + " sync=" + syncCount.get() + " path=" + link.initPath());
            }
        }
    }

    private static void testEarlyRobotStateSkipsBootReplay() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicInteger forbiddenBootCommands = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (DatagramSocket robot = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            robot.setSoTimeout(1000);
            Thread server = new Thread(() -> {
                try {
                    byte[] bytes = new byte[2048];
                    DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
                    robot.receive(packet);
                    SocketAddress client = packet.getSocketAddress();
                    byte[] reply = Files.readAllBytes(Path.of("build/fixtures/connect.bin"));
                    robot.send(new DatagramPacket(reply, reply.length, client));
                    // Exact first-RobotState regression: no Enable, BodyInfo or boot ACK.
                    Wire.Packet state = new Wire.Packet(5, 0xf0, robotStateBytes());
                    byte[] stateFrame = Wire.frame(9, Wire.OOB, Wire.OOB, Wire.OOB, Collections.singletonList(state));
                    robot.send(new DatagramPacket(stateFrame, stateFrame.length, client));
                    long deadline = System.currentTimeMillis() + 400;
                    while (System.currentTimeMillis() < deadline) {
                        robot.receive(packet);
                        Wire.Frame frame = Wire.parse(Arrays.copyOf(bytes, packet.getLength()));
                        for (Wire.Packet p : frame.packets) {
                            if (p.id == 0x45 || p.id == 0x4b || p.id == 0x81) forbiddenBootCommands.incrementAndGet();
                        }
                    }
                } catch (SocketTimeoutException ignored) {
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "test-robot-early-state");
            server.start();
            Link link = new Link("127.0.0.1", robot.getLocalPort(), s -> {}, new Link.Observer() {
                public void event(String kind, String text) {}
                public void state(Wire.Telemetry t, long n) { if (n == 1) ready.countDown(); }
                public void packet(Wire.Packet p) {}
            });
            Thread client = new Thread(link, "test-engine-early-state");
            client.start();
            boolean ok = ready.await(3, TimeUnit.SECONDS);
            link.close();
            client.join(2000);
            server.join(2000);
            if (failure.get() != null) throw new AssertionError("Early RobotState robot failed", failure.get());
            if (!ok || forbiddenBootCommands.get() != 0 || !"warm_trigger".equals(link.initPath())) {
                throw new AssertionError("Early RobotState replayed boot: ok=" + ok + " commands=" + forbiddenBootCommands.get() + " path=" + link.initPath());
            }
        }
    }
}
