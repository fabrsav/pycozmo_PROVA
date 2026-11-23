package dev.s200.cozmo;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import android.util.Log;
import dev.s200.cozmo.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.SocketFactory;
import org.json.JSONArray;
import org.json.JSONObject;

public final class RobotService extends Service implements Link.Observer {
    public static volatile RobotService live;
    public volatile String status = "Non connesso", firmware = "—", notice = "";
    public volatile Wire.Telemetry telemetry;
    public volatile long stateCount = 0;
    public volatile Link link;
    private volatile Thread linkThread;
    private volatile Network cozmoNetwork;
    public volatile android.graphics.Bitmap cameraImage;
    public volatile long cameraCount = 0;
    public final Personality personality = new Personality();
    public final Choreographer choreographer = new Choreographer();
    public final BehaviorScheduler scheduler = new BehaviorScheduler();
    public final Cubes cubes = new Cubes();
    public volatile VisionAnalyzer.Result visionResult;
    public volatile Wire.CameraCalib cameraCalib;
    public volatile AiSupervisor.Intention lastIntention;
    public volatile String lastAiSummary = "Supervisore AI locale pronto";
    public volatile String activeBehavior = "nessuno";
    public volatile String lastBehaviorReason = "In attesa";
    public MemoryDb memoryDb;

    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wake;
    private PrintWriter log;
    private CameraFrames camera;
    private volatile long generation = 0;

    private boolean life = false;
    private volatile boolean diagNoDisplay = false;
    private boolean cameraActive = false;
    private boolean autonomous = true;
    private boolean autonomousVoice = true;
    private boolean autonomousMotion = false;
    private boolean autonomousWheels = false;
    private GeminiLiveClient.AiTurnPolicy aiPolicy = GeminiLiveClient.AiTurnPolicy.ON_DEMAND;
    private String aiModel = GeminiLiveClient.DEFAULT_MODEL;

    private long lifeStart = 0;
    private String lastAutoBackpack = "";
    private byte[] lastSentDisplayRle = null;
    private long lastDisplaySentMs = 0;
    private long lastFaceSeenMs = 0, lastPersonalityTickMs = 0, lastStateJsonMs = 0, lastNeedsPersistMs = 0;
    private long lastLiveTickMs = 0, liveBackoffMs = 45_000L, lastOnDemandTriggerMs = 0;
    private final AtomicBoolean aiTurnInFlight = new AtomicBoolean(false);

    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "cozmo-live-ai"));
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "cozmo-memory-db"));

    private final Runnable lifeTick = new Runnable() {
        public void run() {
            Link l = link;
            long now = Link.now();
            boolean linkAlive = l != null && l.stateAge() < 350;
            Wire.Telemetry t = telemetry;

            if (t != null && (t.onCharger() || t.unsafe()) && autonomousWheels) {
                autonomousWheels = false;
            }

            if (linkAlive) {
                if (now - lastPersonalityTickMs >= 200) {
                    lastPersonalityTickMs = now;
                    boolean faceRecent = lastFaceSeenMs > 0 && (now - lastFaceSeenMs) < 2500;
                    boolean cubeAct = cubes.anyRecentActivity(now);
                    String tr = personality.update(now, t, faceRecent, cubeAct);
                    if (tr != null) {
                        event("emotion", "Transizione emotiva automatica: " + tr);
                    }

                    Personality.PersonalitySnapshot snap = personality.snapshot(now);
                    BehaviorScheduler.WorldState ws = new BehaviorScheduler.WorldState(
                        snap,
                        t != null && t.onCharger(),
                        t != null && t.pickedUp(),
                        t != null && t.falling(),
                        t != null && t.cliffDetected(),
                        t != null ? t.head : 0f,
                        t != null ? t.battery : 0f,
                        l.armed(),
                        cameraActive,
                        visionResult,
                        cubeAct,
                        autonomous,
                        autonomousVoice,
                        autonomousMotion,
                        autonomousWheels
                    );
                    BehaviorScheduler.BehaviorDecision dec = scheduler.evaluate(ws, now);
                    if (dec != null) {
                        activeBehavior = dec.behaviorId;
                        lastBehaviorReason = dec.reason;
                        if (dec.hasGazeTarget) {
                            personality.setGazeTarget(dec.gazeX, dec.gazeY, now + 2000L);
                        }
                        if (dec.hasHeadTarget && dec.allowMotion && l.armed() && t != null && !t.unsafe()) {
                            l.head(dec.headTargetRad);
                        }
                        if (dec.clipId != null) {
                            choreographer.startClip(dec.clipId, dec.priority, now);
                        }
                        event("behavior", dec.behaviorId + " · " + dec.reason);
                    }
                }

                // Tick multi-modal choreographer
                boolean allowClipAudio = autonomousVoice ||
                    (choreographer.activeClip() != null && choreographer.activeClip().priority.level >= AnimClip.Priority.USER_DIRECT.level);
                Choreographer.ChoreographyStep step = choreographer.tick(
                    now, personality.profile(), l.getQueuedAudioFrames(), allowClipAudio);

                if (step.startedClipId != null) {
                    event("clip_start", step.startedClipId);
                }
                if (step.faceIntent != null) {
                    personality.setExpression(step.faceIntent.expression, Math.max(800L, step.faceIntent.transitionMs * 4), now);
                    personality.setGazeTarget(step.faceIntent.gazeX, step.faceIntent.gazeY, now + 1500L);
                }
                if (step.backpackColor != null && !step.backpackColor.equals(lastAutoBackpack)) {
                    lastAutoBackpack = step.backpackColor;
                    l.setBackpackColor(step.backpackColor);
                } else if (autonomous && step.activeClipId == null) {
                    String bp = personality.recommendedBackpack();
                    if (!bp.equals(lastAutoBackpack)) {
                        lastAutoBackpack = bp;
                        l.setBackpackColor(bp);
                    }
                }
                if (step.audioSkippedBackpressure) {
                    event("audio_backpressure_skip", "Coda audio > 120 frame: salto audio clip " + step.activeClipId);
                }
                if (step.audioFrames != null && !step.audioFrames.isEmpty()) {
                    l.playAudio(step.audioFrames);
                    event("audio_queued", "Clip=" + step.activeClipId + " frames=" + step.audioFrames.size());
                }
                if (step.motorIntent != null) {
                    applySafeMotorGesture(l, t, step.motorIntent,
                        step.activeClipId != null && choreographer.getClip(step.activeClipId) != null
                            && choreographer.getClip(step.activeClipId).priority == AnimClip.Priority.USER_DIRECT);
                }
                if (step.endedClipId != null) {
                    event("clip_end", step.endedClipId);
                }

                // Persist needs asynchronously every 30s without blocking lifeTick
                if (memoryDb != null && now - lastNeedsPersistMs >= 30_000L) {
                    lastNeedsPersistMs = now;
                    final float e = personality.energy, p = personality.play, c = personality.comfort;
                    dbExecutor.execute(() -> {
                        MemoryDb db = memoryDb;
                        if (db != null) db.saveNeeds(e, p, c);
                    });
                }

                // Render 128x32 procedural face at ~16.6 Hz (60ms) with RLE Budget Guard & delta deduplication
                if (life && !diagNoDisplay) {
                    byte[] rle = personality.renderEncodedFrame(now);
                    if (lastSentDisplayRle == null || !Arrays.equals(rle, lastSentDisplayRle) || (now - lastDisplaySentMs) >= 420L) {
                        lastSentDisplayRle = rle;
                        lastDisplaySentMs = now;
                        l.displayEncoded(rle);
                    }
                }
            }

            // Ambient hybrid AI check-in (only when AMBIENT_HYBRID is selected, >=45s backoff, and not SLEEPY idle on charger)
            if (autonomous && aiPolicy == GeminiLiveClient.AiTurnPolicy.AMBIENT_HYBRID && (now - lastLiveTickMs) >= liveBackoffMs) {
                boolean restingOnCharger = t != null && t.onCharger() && personality.expression(now) == Personality.Expression.SLEEPY;
                if (!restingOnCharger && hasGeminiKey()) {
                    lastLiveTickMs = now;
                    runGeminiLiveTurn(null, false);
                }
            }

            handler.postDelayed(this, 60);
        }
    };

    private void applySafeMotorGesture(Link l, Wire.Telemetry t, AnimClip.MotorGesture mg, boolean userDirect) {
        if (l == null || mg == null || !l.armed() || t == null || t.unsafe()) return;
        boolean allowJoints = userDirect || autonomousMotion;
        boolean allowWheels = (userDirect || autonomousWheels) && !t.onCharger();
        if (allowJoints) {
            if (Math.abs(mg.headDeltaRad) > 0.005f) {
                l.head(t.head + mg.headDeltaRad);
            }
            if (Math.abs(mg.liftDeltaMm) > 0.5f) {
                l.lift(t.lift + mg.liftDeltaMm);
            }
        }
        if (allowWheels && mg.wheelDurationMs > 0L && (Math.abs(mg.wheelLeft) > 0.5f || Math.abs(mg.wheelRight) > 0.5f)) {
            l.drive(mg.wheelLeft, mg.wheelRight);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        live = this;
        cm = getSystemService(ConnectivityManager.class);
        try {
            log = new PrintWriter(new FileOutputStream(new File(getFilesDir(), "events.ndjson"), true), true);
        } catch (IOException e) {
            Log.e("CozmoS200", "Cannot open diagnostic log", e);
        }
        memoryDb = new MemoryDb(this);
        MemoryDb.SavedNeeds savedNeeds = memoryDb.loadNeedsWithTimestamp();
        if (savedNeeds != null) {
            personality.restoreNeedsFromPersistence(
                savedNeeds.energy, savedNeeds.play, savedNeeds.comfort,
                savedNeeds.updatedMs, System.currentTimeMillis());
        }
        MemoryDb.Person recentPerson = memoryDb.mostRecentPerson();
        if (recentPerson != null) {
            personality.setRecognizedPerson(recentPerson.name, recentPerson.familiarity);
        }

        SharedPreferences prefs = getSharedPreferences("robot", 0);
        personality.setProfile(Personality.Profile.fromName(prefs.getString("personality_profile", "CURIOSO")));
        autonomous = prefs.getBoolean("autonomous_life", true);
        autonomousVoice = prefs.getBoolean("autonomous_voice", true);
        autonomousMotion = prefs.getBoolean("autonomous_motion", false);
        autonomousWheels = prefs.getBoolean("autonomous_wheels", false) && autonomousMotion;
        aiPolicy = GeminiLiveClient.AiTurnPolicy.fromName(prefs.getString("ai_turn_policy", "ON_DEMAND"));
        aiModel = prefs.getString("ai_model", GeminiLiveClient.DEFAULT_MODEL);

        camera = new CameraFrames(this);
        PowerManager pm = getSystemService(PowerManager.class);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CozmoS200:link");
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel("robot", "Connessione Cozmo", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        handler.post(lifeTick);
        writeStateJson();
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, RobotService.class).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent close = PendingIntent.getService(this, 2, new Intent(this, RobotService.class).setAction("DISCONNECT"), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "robot")
            .setSmallIcon(dev.s200.cozmo.R.drawable.icon)
            .setContentTitle("Cozmo S200")
            .setContentText(status)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "STOP", stop).build())
            .addAction(new Notification.Action.Builder(null, "Scollega", close).build())
            .build();
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        if (i == null) {
            if (link == null) stopSelf();
            return START_NOT_STICKY;
        }
        String action = i.getAction();
        if (action == null) return START_NOT_STICKY;
        if (link != null && link.isRecoveringFirmware()) return START_NOT_STICKY;
        switch (action) {
            case "CONNECT": {
                String ssid = i.getStringExtra("ssid");
                String pass = i.getStringExtra("password");
                boolean existing = i.getBooleanExtra("existing", false);
                if (i.hasExtra("auto_head")) getSharedPreferences("robot", 0).edit().putBoolean("auto_head_on_dock", i.getBooleanExtra("auto_head", true)).apply();
                if (i.hasExtra("eyes")) getSharedPreferences("robot", 0).edit().putBoolean("eyes_on_connect", i.getBooleanExtra("eyes", true)).apply();
                if (ssid == null || ssid.trim().isEmpty()) ssid = getSharedPreferences("robot", 0).getString("ssid", "Cozmo_71278E");
                if (!existing && (pass == null || pass.isEmpty())) pass = Secrets.read(this);
                connect(ssid.trim(), pass, existing, i.getBooleanExtra("visual_only", false));
                break;
            }
            case "AUTO_HEAD":
                getSharedPreferences("robot", 0).edit().putBoolean("auto_head_on_dock", i.getBooleanExtra("enabled", true)).apply();
                break;
            case "DISCONNECT":
                disconnect();
                stopSelf();
                break;
            case "STOP":
                emergencyStop();
                break;
            case "SHUTDOWN":
                life(false);
                choreographer.stopAll();
                cubes.stopQuickTap();
                if (link != null) link.shutdownRobot("Spegnimento manuale richiesto");
                writeStateJson();
                break;
            case "AUTO_SHUTDOWN": {
                boolean en = i.getBooleanExtra("enabled", true);
                getSharedPreferences("robot", 0).edit().putBoolean("auto_shutdown_low_battery", en).apply();
                if (link != null) link.setAutoShutdownLowBattery(en);
                event("battery", en ? "Protezione batteria critica attiva (< 3,25 V)" : "Protezione batteria critica disattivata");
                break;
            }
            case "ARM":
                if (link != null) link.arm();
                else event("rejected", "Collegamento non attivo");
                break;
            case "DRIVE":
                if (link != null) link.drive(i.getFloatExtra("left", 0f), i.getFloatExtra("right", 0f));
                break;
            case "HEAD": {
                if (link != null) {
                    if (i.hasExtra("angle")) link.head(i.getFloatExtra("angle", 0.18f));
                    else if (i.hasExtra("delta") && telemetry != null) link.head(telemetry.head + i.getFloatExtra("delta", 0f));
                }
                break;
            }
            case "LIFT": {
                if (link != null) {
                    if (i.hasExtra("height")) link.lift(i.getFloatExtra("height", 32f));
                    else if (i.hasExtra("delta") && telemetry != null) link.lift(telemetry.lift + i.getFloatExtra("delta", 0f));
                }
                break;
            }
            case "EYES":
                life(i.getBooleanExtra("enabled", true));
                break;
            case "PROFILE": {
                String raw = i.getStringExtra("profile");
                if (raw == null) raw = i.getStringExtra("name");
                setPersonalityProfile(raw);
                break;
            }
            case "AUTONOMOUS": {
                boolean en = i.getBooleanExtra("enabled", autonomous);
                boolean voice = i.hasExtra("voice") ? i.getBooleanExtra("voice", true)
                    : (i.hasExtra("quiet") ? !i.getBooleanExtra("quiet", false) : autonomousVoice);
                boolean motion = i.getBooleanExtra("motion", autonomousMotion);
                boolean wheels = i.getBooleanExtra("wheels", autonomousWheels);
                setAutonomousConfig(en, voice, motion, wheels);
                break;
            }
            case "CLIP": {
                String clipId = i.getStringExtra("clip");
                if (clipId == null) clipId = i.getStringExtra("id");
                triggerClip(clipId, AnimClip.Priority.USER_DIRECT);
                break;
            }
            case "EXPRESSION": {
                String name = i.getStringExtra("name");
                if (name == null) name = i.getStringExtra("expression");
                long dur = i.getLongExtra("duration_ms", 4000L);
                setExpression(name, dur);
                break;
            }
            case "VOCAL": {
                String kind = i.getStringExtra("kind");
                if (kind == null) kind = i.getStringExtra("preset");
                playVocal(kind);
                break;
            }
            case "SAY":
                sayText(i.getStringExtra("text"));
                break;
            case "VOLUME": {
                if (link != null) {
                    int lvl = i.hasExtra("percent") ? (Math.max(0, Math.min(100, i.getIntExtra("percent", 50))) * 65535) / 100 : i.getIntExtra("level", 32767);
                    link.setVolume(lvl);
                }
                break;
            }
            case "HEADLIGHT":
                if (link != null) link.setHeadLight(i.getBooleanExtra("enabled", true));
                break;
            case "DIAG_NO_DISPLAY":
                diagNoDisplay = i.getBooleanExtra("enabled", true);
                event("diag", "Display continuo " + (diagNoDisplay ? "disattivato" : "attivato"));
                break;
            case "DIAG_SINGLE_FRAME":
                if (link != null) link.displayEncoded(Wire.display(new boolean[128*32]));
                event("diag", "Singolo frame display 128x32 inviato");
                break;
            case "RECOVER_FIRMWARE_2381": {
                Link active = link;
                if (active != null && active.isVisualOnly() && active.isFactoryFirmware()) {
                    try {
                        String token = i.getStringExtra("token");
                        File capability = new File(getFilesDir(), "recover-2381.token");
                        if (token == null || token.length() != 64 || capability.length() != 64
                                || !java.security.MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII),
                                    java.nio.file.Files.readAllBytes(capability.toPath()))) {
                            event("firmware_recovery", "Avvio sospeso · autorizzazione locale non presente");
                            break;
                        }
                        File image = new File(getFilesDir(), "firmware-2381.safe");
                        if (image.length() != 382416) throw new IllegalArgumentException("Dimensione immagine inattesa");
                        java.nio.file.Files.delete(capability.toPath());
                        active.recoverFirmware2381(java.nio.file.Files.readAllBytes(image.toPath()));
                    } catch (Exception e) {
                        event("firmware_recovery", "Immagine non utilizzabile · " + e.getMessage());
                    }
                } else {
                    event("firmware_recovery", "Avvio sospeso · modalità di fabbrica non rilevata");
                }
                break;
            }
            case "DIAG_VISUAL_PLAYER":
                if (link != null) link.probeVisualPlayer(i.getBooleanExtra("tagged", false));
                break;
            case "DIAG_BACKPACK98":
                if (link != null) link.diagBackpack98();
                break;
            case "BACKPACK":
                if (link != null) link.setBackpackColor(i.getStringExtra("color"));
                break;
            case "CAMERA": {
                boolean en = i.getBooleanExtra("enabled", true);
                cameraActive = en;
                if (link != null) link.camera(en);
                break;
            }
            case "SNAPSHOT":
                camera.requestSnapshot();
                break;
            case "CUBES_CONNECT":
                connectCubes();
                break;
            case "CUBES_LIGHT": {
                String preset = i.getStringExtra("preset");
                if (preset == null) preset = i.getStringExtra("color");
                setCubesLight(preset);
                break;
            }
            case "QUICK_TAP": {
                String mode = i.getStringExtra("mode");
                if (mode == null) mode = i.getBooleanExtra("enabled", true) ? "start" : "stop";
                if ("stop".equalsIgnoreCase(mode)) stopQuickTap();
                else if ("tap".equalsIgnoreCase(mode)) simulateQuickTap();
                else startQuickTap();
                break;
            }
            case "AI_PROMPT":
                processAiPrompt(i.getStringExtra("prompt"));
                break;
            case "AI_POLICY":
                setAiPolicy(i.getStringExtra("policy"), i.getStringExtra("model"));
                break;
            case "AI_KEY":
                if (i.getBooleanExtra("clear", false)) clearGeminiKey();
                else saveGeminiKey(i.getStringExtra("key"));
                break;
            case "AI_TEST":
                testGeminiConnection();
                break;
            case "MEET":
                meetPerson(i.getStringExtra("name"));
                break;
            case "CARE":
                careInteraction(i.getStringExtra("kind"));
                break;
        }
        return START_NOT_STICKY;
    }

    public void emergencyStop() {
        life(false);
        choreographer.stopAll();
        cubes.stopQuickTap();
        autonomousMotion = false;
        autonomousWheels = false;
        getSharedPreferences("robot", 0).edit()
            .putBoolean("autonomous_motion", false)
            .putBoolean("autonomous_wheels", false)
            .apply();
        if (link != null) link.stopMotion();
        event("emergency_stop", "STOP premuto: motori disarmati, coreografie interrotte, movimenti autonomi disattivati");
        writeStateJson();
    }

    private void connect(String ssid, String password, boolean existing, boolean visualOnly) {
        disconnect();
        if (ssid == null || !ssid.startsWith("Cozmo_")) {
            event("status", "SSID Cozmo non valido");
            return;
        }
        if (!existing && (password == null || password.length() < 8)) {
            event("status", "Inserisci la password mostrata sul robot");
            return;
        }
        event("status", existing ? "Cerco la rete Wi-Fi locale di Cozmo" : "Richiesta Wi-Fi · conferma il dialogo Android");
        final long attempt = Link.now();
        final long session = generation;
        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                handler.post(() -> {
                    if (callback != this || link != null) return;
                    LinkProperties props = cm.getLinkProperties(n);
                    boolean local = props != null && props.getLinkAddresses().stream().anyMatch(a -> a.getAddress().getHostAddress().startsWith("172.31.1."));
                    if (existing && !local) return;
                    cozmoNetwork = n;
                    event("network", "Wi-Fi disponibile · " + (props == null ? "rete locale" : props.getInterfaceName()));
                    if (!existing) {
                        try {
                            Secrets.save(RobotService.this, password);
                            getSharedPreferences("robot", 0).edit().putString("ssid", ssid).apply();
                        } catch (Exception e) {
                            event("credentials", "Salvataggio protetto non disponibile: reinserisci la password al prossimo avvio");
                        }
                    }
                    try {
                        wake.acquire(15 * 60 * 1000L);
                        Link.Observer scoped = new Link.Observer() {
                            public void event(String kind, String text) { if (generation == session) RobotService.this.event(kind, text); }
                            public void state(Wire.Telemetry value, long count) { if (generation == session) RobotService.this.state(value, count); }
                            public void packet(Wire.Packet p) { if (generation == session) RobotService.this.packet(p); }
                        };
                        Link created = new Link("172.31.1.1", 5551, s -> n.bindSocket(s), scoped, visualOnly);
                        created.setStartupHeadEnabled(getSharedPreferences("robot", 0).getBoolean("auto_head_on_dock", true));
                        created.setAutoShutdownLowBattery(getSharedPreferences("robot", 0).getBoolean("auto_shutdown_low_battery", true));
                        link = created;
                        Thread t = new Thread(created, "cozmo-udp");
                        linkThread = t;
                        t.start();
                    } catch (Exception e) {
                        event("status", "Avvio rete fallito: " + e.getMessage());
                        releaseNetwork();
                    }
                });
            }

            @Override
            public void onUnavailable() {
                handler.post(() -> {
                    if (callback == this) {
                        event("status", "Rete non disponibile o richiesta annullata");
                        releaseNetwork();
                    }
                });
            }

            @Override
            public void onLost(Network n) {
                handler.post(() -> {
                    if (callback == this) {
                        event("status", "Wi-Fi del robot perso");
                        disconnect();
                    }
                });
            }
        };
        try {
            NetworkRequest.Builder b = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            if (existing) cm.registerNetworkCallback(b.build(), callback);
            else {
                WifiNetworkSpecifier spec = new WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(password).build();
                cm.requestNetwork(b.setNetworkSpecifier(spec).build(), callback, 60000);
            }
            handler.postDelayed(() -> {
                if (generation == session && stateCount == 0 && Link.now() - attempt >= 70000 && callback != null) {
                    event("status", "Tentativo scaduto: ripristino la rete precedente");
                    disconnect();
                }
            }, 75000);
        } catch (Exception e) {
            event("status", "Permessi o richiesta Wi-Fi: " + e.getMessage());
            releaseNetwork();
        }
    }

    private void releaseNetwork() {
        cozmoNetwork = null;
        if (callback != null) {
            try { cm.unregisterNetworkCallback(callback); } catch (Exception ignored) {}
            callback = null;
        }
        if (wake != null && wake.isHeld()) wake.release();
    }

    private void disconnect() {
        generation++;
        life = false;
        cameraActive = false;
        choreographer.stopAll();
        scheduler.reset();
        Link old = link;
        Thread oldThread = linkThread;
        link = null;
        linkThread = null;
        if (old != null) old.close();
        if (oldThread != null) {
            try { oldThread.join(180); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        releaseNetwork();
        telemetry = null;
        stateCount = 0;
        cameraImage = null;
        cameraCount = 0;
        visionResult = null;
        lastSentDisplayRle = null;
        camera.reset();
        cubes.reset();
        writeStateJson();
    }

    public void life(boolean enable) {
        life = enable;
        lifeStart = Link.now();
        lastSentDisplayRle = null;
        getSharedPreferences("robot", 0).edit().putBoolean("eyes_on_connect", enable).apply();
        event("life", enable ? "Occhi animati attivi, motori autonomi controllati da Safety" : "Occhi animati disattivati");
    }

    public boolean lifeEnabled() { return life; }
    public boolean autonomousEnabled() { return autonomous; }
    public boolean autonomousVoiceEnabled() { return autonomousVoice; }
    public boolean autonomousMotionEnabled() { return autonomousMotion; }
    public boolean autonomousWheelsEnabled() { return autonomousWheels; }
    public GeminiLiveClient.AiTurnPolicy aiPolicy() { return aiPolicy; }
    public boolean hasGeminiKey() {
        String k = Secrets.readNamed(this, "gemini_live_key_v1");
        return k != null && !k.trim().isEmpty();
    }

    public void setPersonalityProfile(String raw) {
        Personality.Profile p = Personality.Profile.fromName(raw);
        personality.setProfile(p);
        getSharedPreferences("robot", 0).edit().putString("personality_profile", p.name()).apply();
        lastAutoBackpack = "";
        lastSentDisplayRle = null;
        if (link != null && link.stateAge() < 350) link.setBackpackColor(personality.recommendedBackpack());
        event("personality", "Carattere impostato: " + p.label);
    }

    public void setAutonomous(boolean enabled, boolean quiet) {
        setAutonomousConfig(enabled, !quiet, autonomousMotion, autonomousWheels);
    }

    public void setAutonomousConfig(boolean enabled, boolean voice, boolean motion, boolean wheels) {
        autonomous = enabled;
        autonomousVoice = voice;
        autonomousMotion = motion;
        boolean safeWheels = wheels && motion && (telemetry == null || (!telemetry.onCharger() && !telemetry.unsafe()));
        autonomousWheels = safeWheels;
        lastAutoBackpack = "";
        getSharedPreferences("robot", 0).edit()
            .putBoolean("autonomous_life", autonomous)
            .putBoolean("autonomous_voice", autonomousVoice)
            .putBoolean("autonomous_quiet", !autonomousVoice)
            .putBoolean("autonomous_motion", autonomousMotion)
            .putBoolean("autonomous_wheels", autonomousWheels)
            .apply();
        if (enabled && link != null && link.stateAge() < 350 && !life) life(true);
        event("personality", String.format(Locale.ITALY,
            "Vita autonoma=%s · voce=%s · gesti=%s · cingoli=%s",
            autonomous ? "ON" : "OFF",
            autonomousVoice ? "ON" : "OFF",
            autonomousMotion ? "ON" : "OFF",
            autonomousWheels ? "ON" : "OFF"));
        writeStateJson();
    }

    public void triggerClip(String rawClipId, AnimClip.Priority priority) {
        if (rawClipId == null || rawClipId.trim().isEmpty()) return;
        long now = Link.now();
        boolean started = choreographer.startClip(rawClipId.trim(), priority, now);
        if (started) {
            if (!life && link != null && link.stateAge() < 350) life(true);
            activeBehavior = rawClipId.trim().toUpperCase(Locale.ROOT);
            lastBehaviorReason = "Coreografia richiesta: " + activeBehavior;
            event("clip_trigger", "Avviata coreografia " + activeBehavior);
        } else {
            event("clip_rejected", "Coreografia ignorata o priorità inferiore: " + rawClipId);
        }
    }

    public void setExpression(String rawName, long durationMs) {
        Personality.Expression expr = Personality.Expression.fromName(rawName);
        personality.setExpression(expr, durationMs, Link.now());
        lastSentDisplayRle = null;
        Link active = link;
        if (active != null && active.stateAge() < 350) {
            if (active.isVisualOnly()) active.setBackpackColor(personality.recommendedBackpack());
            if (!life) life(true);
        }
        event("expression", "Espressione impostata: " + expr.name() + " (" + expr.label + ")");
    }

    public void playVocal(String kind) {
        String k = (kind == null || kind.trim().isEmpty()) ? "curious" : kind.trim().toLowerCase(Locale.ROOT);
        if (link != null) {
            link.playAudio(AudioSynth.preset(k, personality.profile()));
        } else {
            event("rejected", "Collegamento al robot non attivo");
        }
        if ("happy".equals(k) || "excited".equals(k) || "sing".equals(k)) {
            personality.rewardInteraction("vocal_" + k, Link.now(), 0f, 0.03f, 0.02f, 0.12f, 0.10f);
        }
    }

    public void sayText(String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (link != null) {
            AudioSynth.VoiceProfile vp = AudioSynth.VoiceProfile.fromProfile(personality.profile());
            link.playAudio(AudioSynth.speakText(text, vp, personality.expression(Link.now())));
            event("audio", "Sintesi sillabica Cozmo (" + personality.profile().label + "): " + text.trim());
        } else {
            event("rejected", "Collegamento al robot non attivo");
        }
    }

    public void cameraToggle(boolean enable) {
        cameraActive = enable;
        if (link != null) link.camera(enable);
    }

    public void connectCubes() {
        long now = Link.now();
        List<Wire.Packet> pkts = cubes.connectAllHeard(now);
        if (link != null) {
            if (!pkts.isEmpty()) link.sendPackets(pkts, "cubes", "Tentativo connessione cubi (" + pkts.size() + ")");
            else link.sendPackets(Collections.singletonList(Wire.accessoryDiscovery(true)), "cubes", "Scansione BLE cubi avviata");
        } else {
            event("rejected", "Collegamento al robot non attivo");
        }
    }

    public void setCubesLight(String preset) {
        List<Wire.Packet> pkts = cubes.lightConnectedCubes(preset);
        if (link != null && !pkts.isEmpty()) link.sendPackets(pkts, "cubes", "Luci cubi impostate: " + (preset == null ? "cyan" : preset));
        else event("cubes", "Nessun cubo connesso per aggiornare le luci");
    }

    public void startQuickTap() {
        long now = Link.now();
        cubes.startQuickTap(now);
        personality.setExpression(Personality.Expression.EXCITED, 4000, now);
        choreographer.startClip("PLAY_CHEER", AnimClip.Priority.USER_DIRECT, now);
        if (link != null) {
            List<Wire.Packet> pkts = cubes.quickTapLightPackets();
            if (!pkts.isEmpty()) link.sendPackets(pkts, "quick_tap", "Quick Tap avviato · target Cubo " + (cubes.getQuickTapTarget() + 1));
            else event("quick_tap", "Quick Tap avviato (collega i cubi per i LED)");
        } else {
            event("quick_tap", "Quick Tap avviato in locale");
        }
    }

    public void stopQuickTap() {
        cubes.stopQuickTap();
        if (link != null) {
            List<Wire.Packet> pkts = cubes.lightConnectedCubes("cyan");
            if (!pkts.isEmpty()) link.sendPackets(pkts, "quick_tap", "Quick Tap terminato");
        }
        event("quick_tap", "Quick Tap fermato · punteggio=" + cubes.getQuickTapScore());
    }

    public void simulateQuickTap() {
        long now = Link.now();
        int score = cubes.registerSimulatedOrButtonTap(now);
        choreographer.startClip("CUBE_VICTORY", AnimClip.Priority.USER_DIRECT, now);
        if (link != null) {
            List<Wire.Packet> pkts = cubes.quickTapLightPackets();
            if (!pkts.isEmpty()) link.sendPackets(pkts, "quick_tap", "Punto Quick Tap! Punteggio=" + score);
        }
        event("quick_tap", "Quick Tap tap registrato · punteggio=" + score + " · nuovo target Cubo " + (cubes.getQuickTapTarget() + 1));
    }

    public void meetPerson(String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) return;
        final String cleanName = rawName.trim();
        VisionAnalyzer.Result vr = visionResult;
        final float eyeDist = vr != null ? vr.faceEyeDistPx : 0f;
        final float luma = vr != null ? vr.meanLuma : -1f;
        long now = Link.now();
        personality.rewardInteraction("meet", now, 0f, 0.06f, 0.08f, 0.28f, 0.16f);
        personality.setExpression(Personality.Expression.HAPPY, 4500, now);
        choreographer.startClip("GREET_FAMILIAR", AnimClip.Priority.USER_DIRECT, now);
        if (link != null) {
            if (!life && link.stateAge() < 350) life(true);
            link.setBackpackColor("cyan");
            AudioSynth.VoiceProfile vp = AudioSynth.VoiceProfile.fromProfile(personality.profile());
            link.playAudio(AudioSynth.speakText("ciao " + cleanName + "!", vp, Personality.Expression.HAPPY));
        }
        final float e = personality.energy, pl = personality.play, c = personality.comfort;
        dbExecutor.execute(() -> {
            MemoryDb db = memoryDb;
            if (db != null) {
                MemoryDb.Person p = db.registerOrMeet(cleanName, eyeDist, luma, "HAPPY");
                if (p != null) {
                    personality.setRecognizedPerson(p.name, p.familiarity);
                    event("memory", "Incontro registrato: " + p.name + " (#" + p.encounters + ", fam " + Math.round(p.familiarity * 100f) + "%)");
                }
                db.saveNeeds(e, pl, c);
            }
        });
    }

    public void careInteraction(String kind) {
        long now = Link.now();
        String k = (kind == null || kind.trim().isEmpty()) ? "play" : kind.trim().toLowerCase(Locale.ROOT);
        String feedback;
        if ("comfort".equals(k)) {
            feedback = personality.rewardInteraction("comfort", now, 0.02f, 0.04f, 0.18f, 0.20f, 0.05f);
            personality.setExpression(Personality.Expression.HAPPY, 3500, now);
            choreographer.startClip("HAPPY_COMFORT", AnimClip.Priority.USER_DIRECT, now);
        } else {
            feedback = personality.rewardInteraction("play", now, -0.02f, 0.20f, 0.06f, 0.22f, 0.24f);
            personality.setExpression(Personality.Expression.EXCITED, 3500, now);
            choreographer.startClip("PLAY_CHEER", AnimClip.Priority.USER_DIRECT, now);
        }
        final float e = personality.energy, pl = personality.play, c = personality.comfort;
        final String person = personality.snapshot(now).recognizedPerson;
        final String exprName = personality.expression(now).name();
        dbExecutor.execute(() -> {
            MemoryDb db = memoryDb;
            if (db != null) {
                db.saveNeeds(e, pl, c);
                if (person != null && !person.isEmpty()) {
                    db.recordPersonInteraction(person, "cura_" + k, exprName);
                }
            }
        });
        event("personality", "Interazione cura: " + k + " (" + feedback + ")");
        writeStateJson();
    }

    public void setAiPolicy(String rawPolicy, String rawModel) {
        if (rawPolicy != null && !rawPolicy.trim().isEmpty()) {
            aiPolicy = GeminiLiveClient.AiTurnPolicy.fromName(rawPolicy);
        }
        if (rawModel != null && !rawModel.trim().isEmpty()) {
            aiModel = rawModel.trim();
        }
        getSharedPreferences("robot", 0).edit()
            .putString("ai_turn_policy", aiPolicy.name())
            .putString("ai_model", aiModel)
            .apply();
        lastAiSummary = "Modalità AI: " + aiPolicy.labelIt + " (" + (hasGeminiKey() ? "chiave presente" : "solo offline") + ")";
        event("ai_policy", lastAiSummary);
        writeStateJson();
    }

    public void saveGeminiKey(String rawKey) {
        if (rawKey == null || rawKey.trim().isEmpty()) {
            event("ai_key", "Chiave API vuota ignorata");
            return;
        }
        try {
            Secrets.saveNamed(this, "gemini_live_key_v1", rawKey.trim());
            lastAiSummary = "Chiave Gemini salvata in Android Keystore · modalità " + aiPolicy.labelIt;
            event("ai_key", "Chiave Gemini configurata in Keystore");
        } catch (Exception e) {
            event("ai_key", "Errore salvataggio Keystore: " + e.getClass().getSimpleName());
        }
        writeStateJson();
    }

    public void clearGeminiKey() {
        Secrets.clearNamed(this, "gemini_live_key_v1");
        lastAiSummary = "Chiave Gemini rimossa · attivo supervisore AI locale offline";
        event("ai_key", "Chiave Gemini rimossa");
        writeStateJson();
    }

    /**
     * Finds a validated Internet Network distinct from Cozmo's local Wi-Fi (wlan1).
     * Returns null if no separate Internet network is available, ensuring Gemini never touches wlan1.
     */
    private SocketFactory findValidatedInternetSocketFactory() {
        if (cm == null) return null;
        try {
            Network fallbackNet = null;
            for (Network net : cm.getAllNetworks()) {
                if (net == null || net.equals(cozmoNetwork)) continue;
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                LinkProperties lp = cm.getLinkProperties(net);
                boolean isCozmoSubnet = lp != null && lp.getLinkAddresses().stream()
                    .anyMatch(a -> a.getAddress().getHostAddress().startsWith("172.31.1."));
                if (isCozmoSubnet) continue;
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    return net.getSocketFactory();
                }
                if (fallbackNet == null) fallbackNet = net;
            }
            return fallbackNet != null ? fallbackNet.getSocketFactory() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public void testGeminiConnection() {
        final String key = Secrets.readNamed(this, "gemini_live_key_v1");
        if (key == null || key.trim().isEmpty()) {
            lastAiSummary = "Test AI: chiave Gemini non configurata (modalità locale offline pronta)";
            event("ai_test", lastAiSummary);
            writeStateJson();
            return;
        }
        final SocketFactory netFactory = findValidatedInternetSocketFactory();
        if (netFactory == null) {
            lastAiSummary = "Test AI: nessuna rete Internet separata da wlan1 disponibile (fallback locale attivo)";
            event("ai_test", lastAiSummary);
            writeStateJson();
            return;
        }
        lastAiSummary = "Test connessione Gemini Live in corso...";
        writeStateJson();
        aiExecutor.execute(() -> {
            String res = GeminiLiveClient.testConnection(key.trim(), netFactory, 6000);
            handler.post(() -> {
                lastAiSummary = res;
                event("ai_test", res);
                writeStateJson();
            });
        });
    }

    private AiSupervisor.AiContextSnapshot buildAiContext(String userPrompt, long nowMs) {
        Personality.PersonalitySnapshot snap = personality.snapshot(nowMs);
        Wire.Telemetry t = telemetry;
        VisionAnalyzer.Result vr = visionResult;
        Link l = link;
        List<String> recentEvents = memoryDb != null ? memoryDb.recentEpisodicEvents(3) : Collections.emptyList();
        boolean allowDrive = autonomousWheels && l != null && l.armed() && t != null && !t.onCharger() && !t.unsafe();
        return new AiSupervisor.AiContextSnapshot(
            snap.profile, snap.energyBracket, snap.playBracket, snap.comfortBracket,
            snap.dominantEmotion, snap.recognizedPerson, snap.familiarity,
            recentEvents,
            t != null && t.onCharger(),
            t != null && t.unsafe(),
            l != null && l.armed(),
            vr != null && vr.darkScene,
            vr != null ? vr.faceCount : 0,
            allowDrive,
            userPrompt
        );
    }

    public void processAiPrompt(String prompt) {
        long now = Link.now();
        boolean useRemote = aiPolicy != GeminiLiveClient.AiTurnPolicy.OFFLINE_ONLY
            && hasGeminiKey()
            && findValidatedInternetSocketFactory() != null;
        if (useRemote) {
            runGeminiLiveTurn(prompt, true);
        } else {
            applyLocalDecision(prompt, now, "locale");
        }
    }

    private void applyLocalDecision(String prompt, long now, String sourceTag) {
        AiSupervisor.AiContextSnapshot ctx = buildAiContext(prompt, now);
        AiSupervisor.Intention intent = AiSupervisor.localDecide(ctx, prompt, cubes.anyRecentActivity(now), now);
        applyIntention(intent, prompt, now);
    }

    private void applyIntention(AiSupervisor.Intention intent, String prompt, long now) {
        if (intent == null) {
            event("ai_rejected", "Intenzione AI rifiutata dal supervisore");
            return;
        }
        lastIntention = intent;
        lastAiSummary = intent.summary(now);
        personality.setExpression(intent.expression, Math.max(1500L, intent.expiresAtMs - now), now);
        if (intent.clipId != null && !intent.clipId.isEmpty()) {
            choreographer.startClip(intent.clipId, AnimClip.Priority.AI_ACTION, now);
        }
        if (memoryDb != null && prompt != null && !prompt.trim().isEmpty()) {
            final String pTrim = prompt.trim();
            final String lbl = intent.expression.label;
            dbExecutor.execute(() -> {
                MemoryDb db = memoryDb;
                if (db != null) db.recordEvent("ai", "Prompt: " + pTrim + " -> " + lbl);
            });
        }
        Link l = link;
        Wire.Telemetry t = telemetry;
        if (l != null && l.stateAge() < 350) {
            if (!life) life(true);
            l.setBackpackColor(intent.backpackColor);
            AudioSynth.VoiceProfile vp = AudioSynth.VoiceProfile.fromProfile(personality.profile());
            if (!intent.sayText.isEmpty()) {
                l.playAudio(AudioSynth.speakText(intent.sayText, vp, intent.expression));
            } else if (!"none".equals(intent.vocalization)) {
                l.playAudio(AudioSynth.preset(intent.vocalization, vp));
            }
            if (Math.abs(intent.headDeltaRad) > 0.005f && l.armed() && t != null && !t.unsafe()) {
                l.head(t.head + intent.headDeltaRad);
            }
            if (intent.driveDurationMs > 0L && (Math.abs(intent.driveLeft) > 0.5f || Math.abs(intent.driveRight) > 0.5f)
                && autonomousWheels && l.armed() && t != null && !t.onCharger() && !t.unsafe()) {
                final long session = generation;
                l.drive(intent.driveLeft, intent.driveRight);
                handler.postDelayed(() -> {
                    if (generation == session && link != null) link.drive(0f, 0f);
                }, Math.min(1500L, intent.driveDurationMs));
            }
        }
        event("ai", "Intenzione applicata [" + intent.source + "]: " + intent.expression.name()
            + (intent.clipId != null ? " clip=" + intent.clipId : "")
            + " voce=" + intent.vocalization + " zaino=" + intent.backpackColor);
        writeStateJson();
    }

    private void runGeminiLiveTurn(String prompt, boolean fallbackOnFailure) {
        final String apiKey = Secrets.readNamed(this, "gemini_live_key_v1");
        final SocketFactory netFactory = findValidatedInternetSocketFactory();
        if (apiKey == null || apiKey.trim().isEmpty() || netFactory == null) {
            if (fallbackOnFailure) applyLocalDecision(prompt, Link.now(), "locale-fallback");
            return;
        }
        if (!aiTurnInFlight.compareAndSet(false, true)) {
            if (fallbackOnFailure) applyLocalDecision(prompt, Link.now(), "locale-busy");
            return;
        }
        final long session = generation;
        final long nowMs = Link.now();
        final AiSupervisor.AiContextSnapshot ctx = buildAiContext(prompt, nowMs);
        final String ctxJson = ctx.toCompactJson();
        final VisionAnalyzer.Result vr = visionResult;
        final String visSummary = vr == null ? "nessuna" : vr.summary(cameraCount);
        final byte[] jpeg = currentCameraJpeg();
        final String sysInstr = GeminiLiveClient.buildSystemInstruction(ctx.profile);
        final String modelToUse = aiModel;

        aiExecutor.execute(() -> {
            try {
                GeminiLiveClient.LiveAction act = GeminiLiveClient.queryOnce(
                    apiKey.trim(), modelToUse, sysInstr, ctxJson, visSummary, prompt, jpeg,
                    GeminiLiveClient.READ_TIMEOUT_MS, netFactory);
                liveBackoffMs = 45_000L;
                handler.post(() -> {
                    aiTurnInFlight.set(false);
                    if (generation != session) return;
                    long cur = Link.now();
                    if (act == null) {
                        if (fallbackOnFailure) applyLocalDecision(prompt, cur, "locale-fallback");
                        return;
                    }
                    AiSupervisor.Intention intent = act.toIntention(cur, ctx.allowAutonomousDrive);
                    if (intent != null) {
                        applyIntention(intent, prompt, cur);
                        event("live_ai", "Azione=" + act.name + " latenza=" + GeminiLiveClient.lastLatencyMs() + "ms"
                            + (act.reason != null && !act.reason.isEmpty() ? " · " + act.reason : ""));
                    } else {
                        event("ai_rejected", "Risposta Gemini Live rifiutata da AiSupervisor");
                        if (fallbackOnFailure) applyLocalDecision(prompt, cur, "locale-fallback");
                    }
                });
            } catch (Exception e) {
                liveBackoffMs = Math.min(180_000L, liveBackoffMs * 2L);
                final String cleanErr = AiSupervisor.sanitizeForLog(e.getMessage(), apiKey);
                handler.post(() -> {
                    aiTurnInFlight.set(false);
                    if (generation != session) return;
                    event("live_ai_error", cleanErr);
                    if (fallbackOnFailure) {
                        applyLocalDecision(prompt, Link.now(), "locale-fallback");
                    }
                });
            }
        });
    }

    private byte[] currentCameraJpeg() {
        android.graphics.Bitmap bmp = cameraImage;
        if (bmp == null) return null;
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out);
            return out.toByteArray();
        } catch (Exception ignored) {
            return null;
        }
    }

    void onVisionResult(VisionAnalyzer.Result vr) {
        visionResult = vr;
        long now = Link.now();
        if (vr != null) {
            if (vr.faceCount > 0) {
                boolean wasAbsent = (now - lastFaceSeenMs) > 25_000L;
                lastFaceSeenMs = now;
                personality.setGazeTarget(vr.faceX * 5.5f, vr.faceY * 3.0f, now + 1800L);
                if (wasAbsent && autonomous && aiPolicy == GeminiLiveClient.AiTurnPolicy.ON_DEMAND
                    && (now - lastOnDemandTriggerMs) >= 25_000L && hasGeminiKey()) {
                    lastOnDemandTriggerMs = now;
                    runGeminiLiveTurn("Un volto è appena apparso davanti a Cozmo", false);
                }
            } else if (vr.motionScore >= 0.30f) {
                personality.setGazeTarget(vr.motionX * 5.0f, vr.motionY * 2.5f, now + 1200L);
            }
        }
    }

    @Override
    public void event(String kind, String text) {
        String safeText = AiSupervisor.sanitizeForLog(text, Secrets.readNamed(this, "gemini_live_key_v1"));
        if (kind.equals("status")) status = safeText;
        else if (kind.equals("firmware")) {
            try {
                JSONObject fwObj = new JSONObject(safeText);
                int ver = fwObj.optInt("version", -1);
                String bld = fwObj.optString("build", "");
                firmware = ver > 0 ? (ver + (bld.isEmpty() ? "" : " (" + bld + ")")) : safeText;
            } catch (Exception e) {
                firmware = "Versione non riconosciuta";
            }
        } else if (kind.equals("rejected") || kind.equals("motion") || kind.equals("shutdown") || kind.equals("emergency_stop")) {
            notice = safeText;
        }
        Log.i("CozmoS200", kind + ": " + safeText);
        synchronized (this) {
            if (log != null) {
                try {
                    log.println(new JSONObject()
                        .put("time", System.currentTimeMillis())
                        .put("monotonic_ms", Link.now())
                        .put("kind", kind)
                        .put("message", safeText));
                } catch (Exception ignored) {}
            }
        }
        if (kind.equals("status")) handler.post(() -> getSystemService(NotificationManager.class).notify(1, notification()));
        if (kind.equals("closed")) handler.post(() -> { life = false; releaseNetwork(); });
        writeStateJson();
    }

    @Override
    public void state(Wire.Telemetry value, long count) {
        telemetry = value;
        stateCount = count;
        if (count == 1) {
            final long session = generation;
            handler.post(() -> {
                if (generation == session && link != null && link.stateAge() < 350 && getSharedPreferences("robot", 0).getBoolean("eyes_on_connect", true)) {
                    life(true);
                }
            });
        }
        if (count % 150 == 1) {
            event("telemetry", String.format(Locale.US, "count=%d battery=%.3f head=%.4f lift=%.2f flags=0x%x",
                count, value.battery, value.head, value.lift, value.status));
        }
        long now = Link.now();
        if (now - lastStateJsonMs >= 250) {
            lastStateJsonMs = now;
            writeStateJson();
        }
    }

    @Override
    public void packet(Wire.Packet p) {
        try {
            switch (p.id) {
                case 0xf2:
                    camera.accept(p.data);
                    break;
                case 0xf3: {
                    Wire.CubeAvailable avail = Wire.CubeAvailable.parse(p.data);
                    Wire.Packet conn = cubes.onAvailable(avail, Link.now());
                    if (conn != null && link != null) {
                        link.sendPackets(Collections.singletonList(conn), "cubes", "Connessione automatica cubo tipo " + avail.objectType + " (RSSI " + avail.rssi + " dBm)");
                    }
                    break;
                }
                case 0xd0: {
                    Wire.CubeConnection cc = Wire.CubeConnection.parse(p.data);
                    Cubes.Cube c = cubes.onConnectionState(cc);
                    if (c != null) event("cubes", c.name + (cc.connected ? " connesso (ID=" + cc.objectId + ")" : " scollegato"));
                    break;
                }
                case 0xb6: {
                    long now = Link.now();
                    Wire.CubeTapped tap = Wire.CubeTapped.parse(p.data);
                    Cubes.Cube c = cubes.onTapped(tap, now);
                    if (c != null) {
                        event("cubes", "Tocco su " + c.name + " (tot=" + c.taps + ")");
                        personality.rewardInteraction("cube_tap", now, 0f, 0.04f, 0.01f, 0.12f, 0.15f);
                        if (cubes.isQuickTapActive() && link != null) {
                            link.sendPackets(cubes.quickTapLightPackets(), "quick_tap", "Quick Tap target: Cubo " + (cubes.getQuickTapTarget() + 1));
                            link.playAudio(AudioSynth.preset("fanfare", personality.profile()));
                        }
                    }
                    break;
                }
                case 0xb4: {
                    Wire.CubeMoved mv = Wire.CubeMoved.parse(p.data);
                    Cubes.Cube c = cubes.onMoved(mv, Link.now());
                    if (c != null) event("cubes", "Movimento " + c.name + " asse=" + Cubes.axisName(mv.axis));
                    break;
                }
                case 0xb5: {
                    int objId = p.data.length >= 8 ? Wire.read(p.data).getInt(4) : (p.data.length >= 4 ? Wire.read(p.data).getInt(0) : -1);
                    if (objId >= 0) cubes.onStopped(objId);
                    break;
                }
                case 0xd7: {
                    Wire.CubeUpAxis ua = Wire.CubeUpAxis.parse(p.data);
                    cubes.onUpAxis(ua);
                    break;
                }
                case 0xce: {
                    Wire.CubePower pw = Wire.CubePower.parse(p.data);
                    cubes.onPower(pw);
                    break;
                }
                case 0xcd: {
                    Wire.NvStorageResult nv = Wire.NvStorageResult.parse(p.data);
                    if (nv.op == Wire.NV_OP_READ && nv.result == Wire.NV_RESULT_OKAY) {
                        if (nv.tag == Wire.NV_TAG_CAMERA_CALIB && nv.data.length >= 16) {
                            cameraCalib = Wire.CameraCalib.parse(nv.data);
                            event("nv", "Calibrazione camera NV: fx=" + cameraCalib.fx + " fy=" + cameraCalib.fy + " cx=" + cameraCalib.cx + " cy=" + cameraCalib.cy);
                        } else if (nv.tag == Wire.NV_TAG_SAVED_CUBE_IDS && nv.data.length >= 28) {
                            List<Integer> ids = Wire.parseSavedCubeIds(nv.data);
                            cubes.setSavedFactoryIds(ids);
                            event("nv", "ID cubi salvati in NV: " + ids.size());
                        }
                    }
                    break;
                }
            }
        } catch (IllegalArgumentException ignored) {}
    }

    private synchronized void writeStateJson() {
        try {
            long now = Link.now();
            Link l = link;
            Wire.Telemetry t = telemetry;
            VisionAnalyzer.Result vr = visionResult;
            Personality.PersonalitySnapshot snap = personality.snapshot(now);
            JSONObject root = new JSONObject();
            root.put("time", System.currentTimeMillis());
            root.put("monotonic_ms", now);
            root.put("connected", l != null && l.stateAge() < 350);
            root.put("status", status);
            root.put("firmware", firmware);
            root.put("notice", notice);
            root.put("state_count", stateCount);
            root.put("telemetryCount", stateCount);
            root.put("initPath", l != null ? l.initPath() : "none");
            root.put("connectReadyMs", l != null ? l.connectReadyMs() : -1);
            root.put("camera_count", cameraCount);
            root.put("eyes_enabled", life);
            root.put("armed", l != null && l.armed());
            root.put("visual_only", l != null && l.isVisualOnly());
            root.put("factory_firmware", l != null && l.isFactoryFirmware());
            root.put("firmware_recovering", l != null && l.isRecoveringFirmware());
            root.put("anim_bytes_played", l != null ? l.animBytesPlayed() : 0);
            root.put("anim_drops", l != null ? l.animDrops() : 0);
            root.put("anim_tracks", l != null ? l.animTracks() : 0);
            root.put("audio_tx", l != null ? l.audioFramesSent() : 0);
            root.put("audio_hw", l != null ? l.audioFramesPlayed() : 0);
            root.put("audio_queued", l != null ? l.getQueuedAudioFrames() : 0);
            if (t != null) {
                JSONObject tj = new JSONObject();
                tj.put("battery", t.battery);
                tj.put("head_rad", t.head);
                tj.put("head_deg", Math.toDegrees(t.head));
                tj.put("lift_mm", t.lift);
                tj.put("left_mm_s", t.left);
                tj.put("right_mm_s", t.right);
                JSONArray cliff = new JSONArray();
                for (int c : t.cliff) cliff.put(c);
                tj.put("cliff", cliff);
                tj.put("picked_up", t.pickedUp());
                tj.put("falling", t.falling());
                tj.put("cliff_detected", t.cliffDetected());
                tj.put("on_charger", t.onCharger());
                tj.put("charging", t.charging());
                tj.put("unsafe", t.unsafe());
                tj.put("low_battery_warn", !t.onCharger() && t.battery > 0f && t.battery < Safety.LOW_BATTERY_WARN_V);
                tj.put("status_flags", t.status);
                root.put("telemetry", tj);
            }
            root.put("auto_shutdown_low_battery", l != null ? l.isAutoShutdownLowBattery() : getSharedPreferences("robot", 0).getBoolean("auto_shutdown_low_battery", true));
            root.put("autonomous", autonomous);
            root.put("autonomousVoice", autonomousVoice);
            root.put("autonomousMotion", autonomousMotion);
            root.put("autonomousWheels", autonomousWheels);
            root.put("activeBehavior", activeBehavior);
            root.put("lastBehaviorReason", lastBehaviorReason);
            AnimClip ac = choreographer.activeClip();
            root.put("activeClip", ac != null ? ac.id : "");
            root.put("aiPolicy", aiPolicy.name());
            root.put("aiKeyConfigured", hasGeminiKey());
            root.put("aiTurnsCount", GeminiLiveClient.turnsCount());
            root.put("aiLastLatencyMs", GeminiLiveClient.lastLatencyMs());
            root.put("aiLastError", GeminiLiveClient.lastErrorCode());

            JSONObject pj = new JSONObject();
            pj.put("profile", snap.profile.name());
            pj.put("expression", snap.expression.name());
            pj.put("dominantEmotion", snap.dominantEmotion);
            pj.put("energy", snap.energy);
            pj.put("energyBracket", snap.energyBracket.name());
            pj.put("play", snap.play);
            pj.put("playBracket", snap.playBracket.name());
            pj.put("comfort", snap.comfort);
            pj.put("comfortBracket", snap.comfortBracket.name());
            pj.put("needsFeedback", personality.needsModel().lastFeedback());
            pj.put("happy", snap.happy);
            pj.put("excited", snap.excited);
            pj.put("social", snap.social);
            pj.put("curious", snap.curious);
            pj.put("calm", snap.calm);
            pj.put("recognizedPerson", snap.recognizedPerson);
            pj.put("familiarity", snap.familiarity);
            pj.put("rleFallbacks", personality.faceRenderer().rleFallbackCount());
            root.put("personality", pj);

            JSONObject cj = new JSONObject();
            cj.put("heard", cubes.heardCount());
            cj.put("connected", cubes.connectedCount());
            JSONArray savedCubes = new JSONArray();
            for (int fid : cubes.getSavedFactoryIds()) savedCubes.put(String.format(Locale.US, "0x%08x", fid));
            cj.put("saved_ids", savedCubes);
            cj.put("quick_tap_active", cubes.isQuickTapActive());
            cj.put("quick_tap_score", cubes.getQuickTapScore());
            cj.put("quick_tap_target", cubes.getQuickTapTarget());
            root.put("cubes", cj);

            if (vr != null) {
                JSONObject vj = new JSONObject();
                vj.put("dark_scene", vr.darkScene);
                vj.put("mean_luma", vr.meanLuma);
                vj.put("motion_score", vr.motionScore);
                vj.put("face_count", vr.faceCount);
                vj.put("marker_candidates", vr.markerCandidates);
                root.put("vision", vj);
            }

            File dir = getFilesDir();
            File tmp = new File(dir, "state.json.tmp");
            File dst = new File(dir, "state.json");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            if (!tmp.renameTo(dst)) {
                try (FileInputStream in = new FileInputStream(tmp); FileOutputStream out = new FileOutputStream(dst)) {
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = in.read(b)) >= 0) out.write(b, 0, n);
                }
                tmp.delete();
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        if (memoryDb != null) {
            memoryDb.saveNeeds(personality.energy, personality.play, personality.comfort);
            memoryDb.close();
            memoryDb = null;
        }
        handler.removeCallbacksAndMessages(null);
        disconnect();
        camera.close();
        aiExecutor.shutdownNow();
        dbExecutor.shutdownNow();
        live = null;
        synchronized (this) {
            if (log != null) {
                log.close();
                log = null;
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}
