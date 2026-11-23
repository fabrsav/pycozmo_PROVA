import dev.s200.cozmo.core.*;
import java.nio.file.*;
import java.util.*;

public final class CoreTest {
    static int checks=0;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static byte[] fixture(String name)throws Exception{return Files.readAllBytes(Path.of("build/fixtures/"+name+".bin"));}
    static void bytes(byte[] actual,String name)throws Exception{check(Arrays.equals(actual,fixture(name)),"upstream fixture "+name);}
    public static void main(String[] args)throws Exception {
        // Original 33 core checks
        bytes(Wire.frame(1,0,0,65535,Collections.emptyList()),"reset");
        bytes(Wire.frame(7,0,0,0,Collections.singletonList(Wire.command(0x25))),"enable");
        bytes(Wire.frame(7,7,7,12,Collections.singletonList(Wire.command(0x32,Wire.floats(30,-30,100,100)))),"drive");
        bytes(Wire.frame(7,8,8,12,Collections.singletonList(Wire.command(0x37,Wire.le(17).put(Wire.floats(.1f,1,4,.6f)).put((byte)0).array()))),"head");
        bytes(Wire.frame(11,65535,65535,2,Collections.singletonList(new Wire.Packet(11,-1,Wire.le(17).putDouble(12.5).putInt(1).putInt(0).put((byte)0).array()))),"ping");
        check(Wire.parse(fixture("connect")).packets.get(0).type==2,"decode connect");
        Wire.Telemetry t=Wire.Telemetry.parse(Wire.parse(fixture("robot")).packets.get(0).data);
        check(Math.abs(t.battery-3.8)<.001&&t.x==12&&t.y==23&&t.cliff[3]==400,"telemetry offsets");
        check(Math.abs(t.lift-(45+66*Math.sin(.3)))<.001,"lift is angle on wire");
        check(t.onCharger()&&!t.unsafe(),"charging status");
        String compactHex="140000000000000001000000000000000000000000802eb9074bbc3f0000000000000000f366dfbe45064bbe00000042797d0bbc90ccc042099300004c03ff042f";
        byte[] compact=new byte[compactHex.length()/2];
        for(int i=0;i<compact.length;i++)compact[i]=(byte)Integer.parseInt(compactHex.substring(2*i,2*i+2),16);
        Wire.Telemetry ct=Wire.Telemetry.parse(compact);
        check(ct.timestamp==20&&Math.abs(ct.head+0.436332f)<.001f,"compact RobotState head");
        check(Math.abs(ct.lift-32f)<.2f&&ct.battery>4f&&ct.battery<4.3f,"compact RobotState lift/battery");
        check(ct.onCharger()&&!ct.unsafe(),"compact RobotState safe status");
        check(Wire.parse(fixture("large-image")).packets.get(0).id==0xf2,"robot camera frames may exceed engine's 1051-byte limit");
        int rejects=0;byte[] valid=fixture("robot");
        for(int n=0;n<valid.length;n++)try{Wire.parse(Arrays.copyOf(valid,n));}catch(IllegalArgumentException e){rejects++;}
        check(rejects==valid.length-1,"reject truncated frames except valid header-only ACK");
        byte[] corrupt=valid.clone();corrupt[0]=0;
        try{Wire.parse(corrupt);throw new AssertionError("bad magic accepted");}catch(IllegalArgumentException expected){checks++;}
        Reliable r=new Reliable();Wire.Packet p=Wire.command(0x25);p.seq=1;
        check(r.receive(p).isEmpty()&&r.lastReceived==65535,"do not ack gap");
        p=Wire.command(0x25);p.seq=0;
        check(r.receive(p).size()==2&&r.lastReceived==1,"ordered delivery after missing packet");
        check(r.receive(p).isEmpty(),"no duplicate delivery");
        r=new Reliable();r.next=65533;r.enqueue(Wire.command(0x25));r.enqueue(Wire.command(0x25));
        check(r.next==1&&r.acknowledge(0)&&r.pendingCount()==0,"ACK wraparound");
        r=new Reliable();r.enqueue(Wire.command(0x32,Wire.floats(30,30,100,100)));r.cancelMotion();
        check(r.pending().get(0).id==0x3b&&r.pending().get(0).seq==0,"expired movement is replaced by stop before retry");
        Safety s=new Safety();check(!s.arm(0),"cannot arm without telemetry");s.update(t,1000);
        check(s.arm(1000)&&s.permit(1000,false)&&!s.permit(1000,true),"allow joints but no wheels on charger");
        check(!s.startupHeadAllowed(1000),"startup gesture waits for head calibration");
        t.status=0x3200;s.update(t,1000);check(s.startupHeadAllowed(1000),"one-shot startup head can run on charger after calibration");
        t.status=0x200;s.update(t,1000);check(!s.startupHeadAllowed(1000),"startup head never runs off charger");
        t.status=0x3200;s.update(t,1000);
        s.lease(1000);check(!s.mustStop(1249)&&s.mustStop(1250),"250ms movement lease");s.stopped();
        s.update(t,1300);s.jointLease(1300);s.update(t,3200);check(!s.mustStop(3499)&&s.mustStop(3500),"joint lease allows target trajectory and still expires");s.stopped();
        check(!s.permit(2350,false),"stale telemetry inhibits motion");
        t.status=8;s.update(t,1400);check(!s.armed(1400),"picked up disarms");
        check(Safety.limit(99,-40,40)==40,"velocity clamp");
        try{Safety.limit(Float.NaN,-40,40);throw new AssertionError("NaN accepted");}catch(IllegalArgumentException expected){checks++;}
        boolean[] screen=new boolean[4096];for(int y=5;y<25;y++)for(int x=20;x<45;x++)screen[y*128+x]=true;
        byte[] encoded=Wire.display(screen);check(encoded.length<=1026,"display bounded");
        Files.write(Path.of("build/fixtures/display-java.bin"),encoded);
        CameraAssembler camera=new CameraAssembler();
        byte[] first=Wire.le(23).putInt(0).putInt(3831).putInt(0).put((byte)8).put((byte)4).put((byte)0).put((byte)0).putShort((short)0).putShort((short)3).put(new byte[]{0,1,2}).array();
        byte[] last=Wire.le(22).putInt(50).putInt(3831).putInt(0).put((byte)0).put((byte)0).put((byte)2).put((byte)1).putShort((short)0).putShort((short)2).put(new byte[]{3,4}).array();
        check(camera.accept(first)==null,"firmware 2381 first camera chunk may have count=0");
        CameraAssembler.Image image=camera.accept(last);
        check(image!=null&&image.encoding==8&&image.resolution==4&&Arrays.equals(image.data,new byte[]{0,1,2,3,4}),"metadata from first chunk, final count from last chunk");
        check(camera.accept(last)==null,"drop orphan final camera chunk");
        camera.accept(first);byte[] wrong=last.clone();wrong[4]++;
        check(camera.accept(wrong)==null&&camera.accept(last)==null,"do not combine chunks from different images");

        // Audio u-law full 65536-value verification against PyCozmo
        byte[] expectedUlaw = fixture("ulaw-all-65536");
        byte[] actualUlaw = new byte[65536];
        for (int sample = -32768; sample < 32768; sample++) {
            actualUlaw[sample + 32768] = Wire.encodeUlaw(sample);
        }
        check(Arrays.equals(actualUlaw, expectedUlaw), "all 65536 u-law encodings match PyCozmo byte-for-byte");

        // New protocol commands against PyCozmo fixtures
        bytes(Wire.frame(7,3,3,1,Collections.singletonList(Wire.setVolume(32767))),"volume");
        byte[] audio744 = new byte[744];
        for (int i = 0; i < 744; i++) audio744[i] = Wire.encodeUlaw((i * 87) - 16000);
        bytes(Wire.frame(7,4,4,1,Collections.singletonList(Wire.outputAudio(audio744))),"audio");
        bytes(Wire.frame(7,5,5,1,Collections.singletonList(Wire.setHeadLight(true))),"headlight");
        bytes(Wire.frame(7,6,6,1,Collections.singletonList(Wire.accessoryDiscovery(true))),"discovery");
        bytes(Wire.frame(7,9,9,2,Collections.singletonList(Wire.objectConnect(0x0a0b0c0d,1))),"obj-connect");
        bytes(Wire.frame(7,10,10,2,Collections.singletonList(Wire.cubeId(1,12))),"cube-id");
        byte[] lsCyan = Wire.lightState(0x03ff, 0, 0, 0, 0, 0, 0);
        bytes(Wire.frame(7,11,11,2,Collections.singletonList(Wire.cubeLights(lsCyan,lsCyan,lsCyan,lsCyan))),"cube-lights");
        bytes(Wire.frame(7,12,12,2,Collections.singletonList(Wire.backpackCenter(lsCyan,lsCyan,lsCyan))),"backpack-center");
        bytes(Wire.frame(7,13,13,2,Collections.singletonList(Wire.nvStorageRead(Wire.NV_TAG_CAMERA_CALIB,1))),"nv-read-calib");
        bytes(Wire.frame(7,14,14,2,Collections.singletonList(Wire.nvStorageRead(Wire.NV_TAG_SAVED_CUBE_IDS,28))),"nv-read-cubes");
        bytes(Wire.frame(7,15,15,2,Collections.singletonList(Wire.shutdownRobot())),"shutdown");
        Safety lowBatt = new Safety();
        Wire.Telemetry lt = new Wire.Telemetry();
        lt.status = 0x0; lt.battery = 3.24f;
        lowBatt.update(lt, 1000);
        check(lowBatt.lowBatteryWarning() && !lowBatt.mustShutdownLowBattery(2499) && lowBatt.mustShutdownLowBattery(2500), "low battery triggers shutdown after 1.5s off charger");
        lt.status = 0x1000; lowBatt.update(lt, 2600);
        check(!lowBatt.lowBatteryWarning() && !lowBatt.mustShutdownLowBattery(5000), "on charger resets low battery shutdown timer");

        // Test 1: compact 65-byte telemetry on charger preserves startupHeadAllowed and onCharger for wheels
        Wire.Telemetry ctCompact = Wire.Telemetry.parse(compact);
        Safety sc = new Safety();
        sc.update(ctCompact, 1000);
        check(ctCompact.onCharger(), "compact onCharger preserved");
        check(!ctCompact.unsafe(), "compact not unsafe");
        check(sc.startupHeadAllowed(1000), "compact startupHeadAllowed allowed on charger via compactTelemetry");
        sc.arm(1000);
        check(!sc.permit(1000, true), "compact wheels blocked on charger");
        check(sc.permit(1000, false), "compact joints allowed on charger");

        // Test 2: Safety wheelBlockReason / jointBlockReason diagnostic strings
        Safety s2 = new Safety();
        Wire.Telemetry t2 = new Wire.Telemetry();
        t2.status = 0x1000; t2.battery = 4.1f;
        s2.update(t2, 1000);
        check("Sessione solo visiva: motori disabilitati".equals(s2.jointBlockReason(1000, true, false)), "joint visual_only reason");
        check("Sessione solo visiva: motori disabilitati".equals(s2.wheelBlockReason(1000, true, false)), "wheel visual_only reason");
        t2.status = 0x1000;
        s2.update(t2, 2000);
        check("Firmware di fabbrica attivo: ripristina firmware 2381".equals(s2.jointBlockReason(2000, false, true)), "joint factory reason");
        check("Firmware di fabbrica attivo: ripristina firmware 2381".equals(s2.wheelBlockReason(2000, false, true)), "wheel factory reason");
        s2.arm(2000);
        check("".equals(s2.jointBlockReason(2000, false, false)), "joint armed on charger empty");
        check("Cingoli bloccati: Cozmo è sulla basetta di ricarica".equals(s2.wheelBlockReason(2000, false, false)), "wheel on charger reason");
        s2.update(t2, 80900);
        check("Motori bloccati: premi Abilita motori (60 s)".equals(s2.jointBlockReason(81000, false, false)), "joint expired after 60s");

        // Test 3: joint lease 2200ms, wheel lease 250ms
        Safety s3 = new Safety();
        Wire.Telemetry t3 = new Wire.Telemetry();
        t3.status = 0x1000; t3.battery = 4.1f;
        s3.update(t3, 1000);
        s3.arm(1000);
        s3.jointLease(1000);
        s3.update(t3, 1700);
        check(!s3.mustStop(1900), "joint lease valid at 900ms (before 2200ms expiry)");
        s3.update(t3, 3000);
        check(s3.mustStop(3201), "joint lease expires after 2200ms");
        s3.update(t3, 3200);
        s3.lease(3200);
        check(!s3.mustStop(3449), "wheel lease valid at 249ms");
        check(s3.mustStop(3450), "wheel lease expires at 250ms (< 350ms)");
        byte[] calibPayload = Wire.le(16).putFloat(290.5f).putFloat(291.2f).putFloat(160.0f).putFloat(120.0f).array();
        Wire.CameraCalib calib = Wire.CameraCalib.parse(calibPayload);
        check(Math.abs(calib.fx - 290.5f) < 0.001f && Math.abs(calib.cy - 120.0f) < 0.001f, "CameraCalib parse");
        byte[] savedCubesPayload = Wire.le(28).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0x11223344).putInt(0).putInt(0x55667788).array();
        List<Integer> savedIds = Wire.parseSavedCubeIds(savedCubesPayload);
        check(savedIds.size() == 2 && savedIds.get(0) == 0x11223344 && savedIds.get(1) == 0x55667788, "parseSavedCubeIds matches PyCozmo 0.9.0");

        // New robot-to-engine packets against PyCozmo fixtures
        Wire.AnimState anim = Wire.AnimState.parse(Wire.parse(fixture("anim-state")).packets.get(0).data);
        check(anim.timestamp==900 && anim.animBytesPlayed==4096 && anim.audioFramesPlayed==27 && anim.enabledTracks==3, "decode AnimationState");
        Wire.CubeAvailable avail = Wire.CubeAvailable.parse(Wire.parse(fixture("obj-available")).packets.get(0).data);
        check(avail.factoryId==0x0a0b0c0d && avail.objectType==2 && avail.rssi==-48, "decode ObjectAvailable");
        Wire.CubeConnection conn = Wire.CubeConnection.parse(Wire.parse(fixture("obj-conn-state")).packets.get(0).data);
        check(conn.objectId==1 && conn.factoryId==0x0a0b0c0d && conn.objectType==2 && conn.connected, "decode ObjectConnectionState");
        Wire.CubeTapped tap = Wire.CubeTapped.parse(Wire.parse(fixture("obj-tapped")).packets.get(0).data);
        check(tap.timestamp==777 && tap.objectId==1 && tap.numTaps==2 && tap.tapNeg==-8 && tap.tapPos==11, "decode ObjectTapped");

        // AudioSynth checks
        for (String preset : new String[]{"curious","happy","excited","surprised","sleepy","sad","sing"}) {
            List<byte[]> frames = AudioSynth.preset(preset);
            check(!frames.isEmpty() && frames.get(0).length == 744, "AudioSynth preset " + preset);
        }
        List<byte[]> spoken = AudioSynth.speakText("Ciao Cozmo!");
        check(spoken.size() >= 3 && spoken.get(0).length == 744, "AudioSynth speakText frames");

        // Personality & Expressions RLE checks
        Personality pers = new Personality();
        for (Personality.Expression ex : Personality.Expression.values()) {
            boolean[] px = Personality.renderExpression(ex, 1.0f, 0f, 0f);
            byte[] rle = Wire.display(px);
            check(rle.length > 2 && rle.length <= 1026, "Expression RLE valid: " + ex.name());
        }
        t.status = 0x1000; // on charger
        pers.energy = 0.50f;
        pers.update(1000, t, false, false);
        pers.update(6000, t, true, false);
        check(pers.energy > 0.50f && pers.happy > 0.25f, "charger restores energy and face increases happy");
        t.status = 0x8; // picked up
        String tr = pers.update(7000, t, false, false);
        check("surprised".equals(tr) && pers.expression(7000) == Personality.Expression.SURPRISED, "picked up triggers surprised expression");
        t.status = 0x1000;
        pers.setProfile(Personality.Profile.AFFETTUOSO);
        check(pers.profile() == Personality.Profile.AFFETTUOSO, "affettuoso profile active");
        pers.setProfile(Personality.Profile.BIRICHINO);
        pers.play = 0.40f;
        pers.curious = 0.45f;
        pers.happy = 0.05f;
        pers.excited = 0.05f;
        pers.update(10500, t, false, false);
        check(pers.expression(10500) == Personality.Expression.SKEPTICAL, "birichino profile favors skeptical expression when curious and low play");
        check("amber".equals(Personality.Profile.TRANQUILLO.defaultBackpack), "tranquillo profile default backpack");

        // Cubes state machine checks
        Cubes cubes = new Cubes();
        Wire.Packet autoConn = cubes.onAvailable(avail, 1000);
        check(autoConn != null && autoConn.id == 0x05, "auto-connects discovered cube");
        check(cubes.onAvailable(avail, 2000) == null, "respects 5s cube connect cooldown");
        cubes.onConnectionState(conn);
        check(cubes.connectedCount() == 1, "cube marked connected");
        cubes.startQuickTap(2500);
        cubes.onTapped(tap, 3000);
        check(cubes.getQuickTapScore() == 0 && cubes.getByType(2).taps == 2, "tap on a non-target cube counts as tap but not as score");
        cubes.registerSimulatedOrButtonTap(3100); // target moves to slot 1 (cube type 2)
        cubes.onTapped(tap, 3200);
        check(cubes.getQuickTapScore() == 2 && cubes.getQuickTapTarget() == 2, "tap on target cube scores and moves target");
        check(cubes.quickTapLightPackets().size() == 2, "quick tap light packets for connected cube");
        check(cubes.lightConnectedCubes("spin").size() == 2, "cube light packet pair generated");

        // VisionAnalyzer checks
        VisionAnalyzer va = new VisionAnalyzer();
        int[] darkFrame = new int[320 * 240];
        VisionAnalyzer.Result vr1 = va.analyze(darkFrame, 320, 240, 0, 0, 0, 0, 0);
        check(vr1.darkScene && vr1.meanLuma < 1.0f && vr1.motionScore == 0f, "dark scene detected");
        int[] brightFrame = new int[320 * 240];
        for (int i = 0; i < brightFrame.length; i++) brightFrame[i] = (i % 320 > 160) ? 0xFFE0E0E0 : 0xFF101010;
        VisionAnalyzer.Result vr2 = va.analyze(brightFrame, 320, 240, 1, 0.25f, -0.1f, 0.82f, 42f);
        check(!vr2.darkScene && vr2.motionScore > 0.3f && vr2.faceCount == 1, "motion and face metadata reported");

        // AiSupervisor checks
        AiSupervisor.Intention validInt = AiSupervisor.validateJson(
            "{\"expression\":\"HAPPY\",\"vocalization\":\"sing\",\"say\":\"ciao!\",\"backpack\":\"green\",\"head_delta_deg\":45.0,\"expires_ms\":99999,\"reason\":\"test\"}",
            5000, "test-llm");
        check(validInt != null
            && validInt.expression == Personality.Expression.HAPPY
            && Math.abs(validInt.headDeltaRad - (float) Math.toRadians(10.0)) < 0.001f
            && validInt.expiresAtMs == 10000
            && !validInt.expired(9999) && validInt.expired(10001),
            "AiSupervisor clamps head angle and TTL");
        check(AiSupervisor.validateJson("{\"drive_wheels\":[100,100]}", 5000, "bad") == null,
            "AiSupervisor rejects raw wheel command injection");
        AiSupervisor.Intention localInt = AiSupervisor.localDecide("Canta una melodia", null, pers, vr2, cubes, "Marco", 6000);
        check(localInt != null && "sing".equals(localInt.vocalization), "AiSupervisor local offline engine works");

        // M1: EmotionGraph, NeedsModel, PersonalitySnapshot checks
        EmotionGraph eg = new EmotionGraph();
        check(eg.repetitionPenalty("tap", 1000L) == 1.0f
            && Math.abs(eg.repetitionPenalty("tap", 2000L) - 0.65f) < 0.001f
            && Math.abs(eg.repetitionPenalty("tap", 3000L) - 0.35f) < 0.001f
            && Math.abs(eg.repetitionPenalty("tap", 4000L) - 0.15f) < 0.001f
            && eg.repetitionPenalty("tap", 25000L) == 1.0f,
            "EmotionGraph repetitionPenalty attenuates and resets after 20s window");
        eg.decayStep(30f, Personality.Profile.TRANQUILLO);
        check(eg.calm >= 0.40f, "EmotionGraph decays toward TRANQUILLO calm baseline");

        NeedsModel nm = new NeedsModel();
        nm.restoreFromPersistence(0.03f, 0.03f, 0.03f, 1000L, 1000L + 3L * 3600L * 1000L);
        check(Math.abs(nm.energy - 0.25f) < 0.001f && Math.abs(nm.play - 0.25f) < 0.001f && Math.abs(nm.comfort - 0.25f) < 0.001f,
            "NeedsModel applies courteous 0.25 floor after >2h offline pause");
        check(NeedsModel.bracketOf(0.10f) == NeedsModel.Bracket.CRITICAL
            && NeedsModel.bracketOf(0.25f) == NeedsModel.Bracket.LOW
            && NeedsModel.bracketOf(0.50f) == NeedsModel.Bracket.NORMAL
            && NeedsModel.bracketOf(0.85f) == NeedsModel.Bracket.HIGH,
            "NeedsModel discrete brackets");
        String fb1 = nm.applyCareOrPlay("comfort", 0.02f, 0.04f, 0.18f, 10_000L);
        String fb2 = nm.applyCareOrPlay("comfort", 0.02f, 0.04f, 0.18f, 15_000L);
        String fb3 = nm.applyCareOrPlay("comfort", 0.02f, 0.04f, 0.18f, 26_000L);
        check("ok".equals(fb1) && "already_satisfied".equals(fb2) && "ok".equals(fb3),
            "NeedsModel fullness cooldown prevents spam within 15s");
        Personality.PersonalitySnapshot snap = pers.snapshot(12_000L);
        check(snap != null && snap.moodSummaryIt.contains("Bisogni:"), "PersonalitySnapshot serializes summary");

        // M2: Exhaustive 9 expressions x 4 profiles x 5 interpolation points + blink & gaze RLE budget guard (<= 1024 bytes)
        FaceRenderer fr = new FaceRenderer(42L);
        float[] interpPoints = {0.0f, 0.25f, 0.5f, 0.75f, 1.0f};
        for (Personality.Profile prof : Personality.Profile.values()) {
            for (Personality.Expression exA : Personality.Expression.values()) {
                FaceParams fpA = FaceParams.forExpression(exA, prof);
                FaceParams fpNext = FaceParams.forExpression(Personality.Expression.EXCITED, prof);
                for (float u : interpPoints) {
                    FaceParams blended = FaceParams.lerp(fpA, fpNext, u).withGazeAndBlink(u * 6f - 3f, u * 3f - 1.5f, u < 0.2f ? 0.12f : 1.0f);
                    byte[] rleBytes = fr.renderEncoded(blended);
                    check(rleBytes != null && rleBytes.length > 2 && rleBytes.length <= FaceRenderer.MAX_RLE_BYTES,
                        "RLE budget <= 1024 for " + prof + " " + exA + " u=" + u);
                }
            }
        }

        // M3: AudioSynth VoiceProfiles (<= 90 frames) & Choreographer priority/backpressure/bounds
        for (Personality.Profile prof : Personality.Profile.values()) {
            AudioSynth.VoiceProfile vp = AudioSynth.VoiceProfile.fromProfile(prof);
            for (String preset : new String[]{"curious","happy","excited","surprised","sleepy","sad","sing","fanfare","lullaby"}) {
                List<byte[]> frames = AudioSynth.preset(preset, vp);
                check(!frames.isEmpty() && frames.size() <= AudioSynth.MAX_CLIP_FRAMES && frames.get(0).length == 744,
                    "VoiceProfile preset bounded <= 90 frames: " + prof + " " + preset);
            }
            List<byte[]> longSpeech = AudioSynth.speakText("Ciao Cozmo, come stai oggi piccolo amico curioso?", vp, Personality.Expression.HAPPY);
            check(!longSpeech.isEmpty() && longSpeech.size() <= AudioSynth.MAX_CLIP_FRAMES, "VoiceProfile speech bounded <= 90 frames");
        }
        Choreographer choreo = new Choreographer();
        check(choreo.allClips().size() == 10, "Choreographer has 10 built-in multi-modal clips");
        for (AnimClip clip : choreo.allClips().values()) {
            for (AnimClip.MotorGesture mg : clip.motorTrack) {
                check(Math.abs(mg.headDeltaRad) <= 0.1801f && Math.abs(mg.wheelLeft) <= 40.01f
                    && Math.abs(mg.wheelRight) <= 40.01f && mg.wheelDurationMs <= 250L,
                    "Clip motor bounds valid: " + clip.id);
            }
        }
        check(choreo.startClip("CURIOUS_INSPECT", 1000L), "Starts IDLE_AUTONOMOUS clip");
        check(!choreo.startClip("BORED_SIGH", 1100L), "Same priority does not preempt active clip");
        check(choreo.startClip("SURPRISED_PICKUP", 1200L) && "SURPRISED_PICKUP".equals(choreo.activeClip().id),
            "SAFETY_REFLEX preempts IDLE_AUTONOMOUS clip immediately");
        Choreographer.ChoreographyStep bpStep = choreo.tick(1250L, Personality.Profile.CURIOSO, 130, true);
        check(bpStep.audioSkippedBackpressure && bpStep.audioFrames == null,
            "Choreographer skips audio when Link queue > 120 frames");

        // M4: BehaviorScheduler 10-minute accelerated simulation
        BehaviorScheduler sched = new BehaviorScheduler();
        check(BehaviorScheduler.idleIntervalForProfile(Personality.Profile.BIRICHINO) < BehaviorScheduler.idleIntervalForProfile(Personality.Profile.TRANQUILLO),
            "TRANQUILLO idle cadence slower than BIRICHINO");
        pers.setProfile(Personality.Profile.BIRICHINO);
        Personality.PersonalitySnapshot snapBiri = pers.snapshot(0L);
        int biriCount = 0, tranqCount = 0;
        for (long simMs = 0; simMs <= 600_000L; simMs += 1000L) {
            BehaviorScheduler.WorldState ws = new BehaviorScheduler.WorldState(
                snapBiri, true, false, false, false, 0.18f, 4.1f, false, false, null, false,
                true, false, true, true);
            BehaviorScheduler.BehaviorDecision d = sched.evaluate(ws, simMs);
            if (d != null) {
                biriCount++;
                check(!d.allowVoice && !d.allowWheels, "No autonomous voice when voiceEnabled=false and no wheels onCharger");
            }
        }
        sched.reset();
        pers.setProfile(Personality.Profile.TRANQUILLO);
        Personality.PersonalitySnapshot snapTranq = pers.snapshot(0L);
        for (long simMs = 0; simMs <= 600_000L; simMs += 1000L) {
            BehaviorScheduler.WorldState ws = new BehaviorScheduler.WorldState(
                snapTranq, true, false, false, false, 0.18f, 4.1f, false, false, null, false,
                true, false, true, true);
            if (sched.evaluate(ws, simMs) != null) tranqCount++;
        }
        check(biriCount > tranqCount && tranqCount > 0, "10-min simulation: BIRICHINO triggers more idle behaviors than TRANQUILLO");
        BehaviorScheduler.WorldState pickupWs = new BehaviorScheduler.WorldState(
            snapBiri, false, true, false, false, 0.18f, 4.0f, true, false, null, false,
            true, true, true, true);
        BehaviorScheduler.BehaviorDecision reflexDec = sched.evaluate(pickupWs, 700_000L);
        check(reflexDec != null && "SURPRISED_PICKUP".equals(reflexDec.clipId) && reflexDec.priority == AnimClip.Priority.SAFETY_REFLEX,
            "SAFETY_REFLEX triggers immediately when picked up");

        // M5: AiSupervisor extended schema, 12+ Italian phrases across 4 profiles, and secret sanitization
        check(AiSupervisor.validateJson("{\"clip\":\"PLAY_CHEER\",\"expression\":\"HAPPY\",\"vl\":25,\"vr\":25,\"duration_s\":0.5}", 1000L, "test", false) == null,
            "AiSupervisor rejects drive when allowAutonomousDrive is false");
        AiSupervisor.Intention allowedDrive = AiSupervisor.validateJson(
            "{\"clip\":\"PLAY_CHEER\",\"expression\":\"HAPPY\",\"vl\":25,\"vr\":25,\"duration_s\":0.5}", 1000L, "test", true);
        check(allowedDrive != null && "PLAY_CHEER".equals(allowedDrive.clipId) && allowedDrive.driveLeft == 25f,
            "AiSupervisor accepts whitelisted clip and bounded drive when allowAutonomousDrive is true");
        check(AiSupervisor.validateJson("{\"clip\":\"EVIL_HACK_CLIP\"}", 1000L, "test", true) == null,
            "AiSupervisor rejects non-whitelisted clip");
        String[] samplePrompts = {
            "Ciao Cozmo!", "Buongiorno piccolo", "Fai le coccole", "Bravo robot dolce",
            "Giochiamo con i cubi!", "Facciamo una partita a Quick Tap", "Hai sonno? Vai a dormire",
            "Buonanotte Cozmo", "Canta una canzone", "Fai una melodia musicale",
            "Come stai oggi?", "Guardati intorno ed esplora"
        };
        for (Personality.Profile prof : Personality.Profile.values()) {
            AiSupervisor.AiContextSnapshot ctx = new AiSupervisor.AiContextSnapshot(
                prof, NeedsModel.Bracket.NORMAL, NeedsModel.Bracket.NORMAL, NeedsModel.Bracket.NORMAL,
                "happy", "Marco", 0.8f, Arrays.asList("Incontro Marco", "Quick Tap"),
                true, false, false, false, 1, false, "Ciao");
            check(ctx.toCompactJson().length() < 1024, "AiContextSnapshot compact JSON < 1 KB");
            for (String phrase : samplePrompts) {
                AiSupervisor.Intention dec = AiSupervisor.localDecide(ctx, phrase, 5000L);
                check(dec != null && dec.clipId != null && AiSupervisor.WHITELISTED_CLIPS.contains(dec.clipId),
                    "Italian classifier returns valid clip for '" + phrase + "' (" + prof + ")");
            }
        }
        String dummySecret = "AIzaSyDUMMY_SECRET_KEY_12345";
        String dirtyLog = "Handshake failed for wss://host/path?key=AIzaSyDUMMY_SECRET_KEY_12345&foo=1 with key AIzaSyDUMMY_SECRET_KEY_12345";
        String cleanLog = AiSupervisor.sanitizeForLog(dirtyLog, dummySecret);
        check(!cleanLog.contains(dummySecret) && cleanLog.contains("[REDACTED]"),
            "AiSupervisor.sanitizeForLog strips API keys from URLs and messages");

        System.out.println("PASS "+checks+" core checks against PyCozmo, audio, personality, cubes, vision, and AI safety");
    }
}
