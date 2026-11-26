package dev.s200.cozmo;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.Manifest;
import android.graphics.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.view.ViewGroup.LayoutParams;
import android.widget.*;
import dev.s200.cozmo.core.*;
import dev.s200.cozmo.ui.*;
import java.util.*;
import java.util.Locale;

public final class MainActivity extends Activity implements UiCallbacks {
    private static final int PERM_REQ = 2;
    private Intent pendingWifiConnection;

    private FrameLayout host;
    private CompanionTab tabCompanion;
    private DriveTab tabDrive;
    private MindTab tabMind;
    private SystemTab tabSystem;
    private int currentTab = 0;

    // Top bar
    private View stateDot;
    private TextView stateText;
    private TextView stateTag;
    private TextView barBattery;
    private TextView barMotors;
    private Button btnStop;

    // Bottom nav
    private Button navCompanion, navDrive, navMind, navSystem;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        public void run() {
            refreshUi();
            handler.postDelayed(this, 300);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF081118);
        getWindow().setNavigationBarColor(0xFF081118);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.BG_APP);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets b = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(b.left, b.top, b.right, b.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(root);

        // ---- Top Bar ----
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(UiKit.BG_BAR);
        topBar.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 8), UiKit.dp(this, 10), UiKit.dp(this, 8));
        View divider = new View(this);
        divider.setBackgroundColor(0xFF1E3545);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 1);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.HORIZONTAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        stateDot = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(UiKit.dp(this, 10), UiKit.dp(this, 10));
        dotLp.setMargins(0, 0, UiKit.dp(this, 8), 0);
        stateDot.setLayoutParams(dotLp);
        stateDot.setBackground(UiKit.box(this, 0xFF5E7B8F, Color.TRANSPARENT, 999, 0));
        stateText = new TextView(this);
        stateText.setText("Disconnesso");
        stateText.setTextColor(UiKit.TEXT_PRIMARY);
        stateText.setTextSize(14);
        stateText.setTypeface(null, Typeface.BOLD);
        stateTag = new TextView(this);
        stateTag.setText("—");
        stateTag.setTextColor(UiKit.TEXT_MUTED);
        stateTag.setTextSize(11);
        left.addView(stateDot);
        left.addView(stateText);
        left.addView(stateTag);
        topBar.addView(left, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        barBattery = new TextView(this);
        barBattery.setText("—");
        barBattery.setTextColor(UiKit.TEXT_PRIMARY);
        barBattery.setTextSize(13);
        barMotors = new TextView(this);
        barMotors.setText("Motori bloccati");
        barMotors.setTextColor(UiKit.TEXT_SECONDARY);
        barMotors.setTextSize(13);
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        right.addView(barBattery);
        right.addView(barMotors);
        topBar.addView(right);
        topBar.addView(UiKit.spacer(this, 12, 0));
        btnStop = UiKit.actionButton(this, "STOP", UiKit.DANGER, Color.WHITE);
        btnStop.setText("■  STOP");
        btnStop.setTextSize(13);
        btnStop.setOnClickListener(v -> onEmergencyStop());
        topBar.addView(btnStop);
        root.addView(topBar);
        root.addView(divider, divLp);

        // ---- Host centrale (4 schede) ----
        host = new FrameLayout(this);
        tabCompanion = new CompanionTab(this, this);
        tabDrive = new DriveTab(this, this);
        tabMind = new MindTab(this, this);
        tabSystem = new SystemTab(this, this);
        tabSystem.setWifiCredentials(savedSsid(), Secrets.read(this));
        host.addView(tabCompanion.getView());
        host.addView(tabDrive.getView());
        host.addView(tabMind.getView());
        host.addView(tabSystem.getView());
        LinearLayout.LayoutParams hostLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(host, hostLp);

        // ---- Bottom Navigation ----
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setBackgroundColor(UiKit.BG_BAR);
        bottom.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 6));
        View topDiv = new View(this);
        topDiv.setBackgroundColor(0xFF1E3545);
        LinearLayout.LayoutParams tdLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 1);
        root.addView(topDiv, tdLp);

        navCompanion = navBtn("Compagno", 0);
        navDrive = navBtn("Guida", 1);
        navMind = navBtn("Mente", 2);
        navSystem = navBtn("Sistema", 3);
        bottom.addView(navCompanion, new LinearLayout.LayoutParams(0, UiKit.dp(this, 58), 1f));
        bottom.addView(navDrive, new LinearLayout.LayoutParams(0, UiKit.dp(this, 58), 1f));
        bottom.addView(navMind, new LinearLayout.LayoutParams(0, UiKit.dp(this, 58), 1f));
        bottom.addView(navSystem, new LinearLayout.LayoutParams(0, UiKit.dp(this, 58), 1f));
        root.addView(bottom);
        showTab(currentTab);

        startService(new Intent(this, RobotService.class));
        applyIntent(getIntent());
    }

    private Button navBtn(String label, int index) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(12);
        b.setTextColor(UiKit.TEXT_MUTED);
        b.setBackground(UiKit.box(this, UiKit.BG_CHIP, UiKit.BORDER_CARD, 999, 1));
        b.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 4), UiKit.dp(this, 8), UiKit.dp(this, 4));
        final int idx = index;
        b.setOnClickListener(v -> showTab(idx));
        return b;
    }

    private void showTab(int index) {
        currentTab = index;
        View[] views = {tabCompanion.getView(), tabDrive.getView(), tabMind.getView(), tabSystem.getView()};
        Button[] navs = {navCompanion, navDrive, navMind, navSystem};
        for (int i = 0; i < 4; i++) {
            views[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            boolean active = i == index;
            navs[i].setTextColor(active ? UiKit.ACCENT : UiKit.TEXT_MUTED);
            if (active) {
                navs[i].setBackground(UiKit.box(this, UiKit.ACCENT, UiKit.ACCENT, 999, 1));
                navs[i].setTextColor(UiKit.ACCENT_DARK);
            } else {
                navs[i].setBackground(UiKit.box(this, UiKit.BG_CHIP, UiKit.BORDER_CARD, 999, 1));
                navs[i].setTextColor(UiKit.TEXT_MUTED);
            }
        }
        if (index != 1) ((DriveTab) tabDrive).stopDpadMotors();
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        ((DriveTab) tabDrive).stopDpadMotors();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(poll);
        ((DriveTab) tabDrive).stopDpadMotors();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntent(intent);
    }

    private void applyIntent(Intent i) {
        if (i == null) return;
        if (i.getBooleanExtra("connect", false)) {
            i.removeExtra("connect");
            handler.postDelayed(() -> connectWifiFromPrefs(), 500);
        }
    }

    /* ================================================================== */
    /* UiCallbacks implementation                                         */
    /* ================================================================== */

    private RobotService svc() { return RobotService.live; }
    private Link link() { RobotService s = svc(); return s != null ? s.link : null; }
    private Wire.Telemetry tel() { RobotService s = svc(); return s != null ? s.telemetry : null; }

    private void startOrCall(Intent i) {
        RobotService s = svc();
        if (s != null && s.link != null && s.link.stateAge() < 350) {
            // handled per-action below via direct calls where possible
        } else {
            startService(i);
        }
    }

    @Override public void onEmergencyStop() { serviceAction("STOP"); }
    @Override public void onOpenConnection() {
        // The connection controls live in the System tab.
        showTab(3);
        tabSystem.focusConnection();
    }
    @Override public void onPet() { care("comfort"); }
    @Override public void onPlay() { care("play"); }
    @Override public void onSelectPersonality(String id) {
        if (svc() != null) svc().setPersonalityProfile(id);
        else startService(new Intent(this, RobotService.class).setAction("PROFILE").putExtra("profile", id));
    }
    @Override public void onTriggerExpression(String id) {
        withService(s -> s.setExpression(id, 5000));
    }
    @Override public void onTriggerChoreography(String id) {
        withService(s -> s.triggerClip(id, AnimClip.Priority.USER_DIRECT));
    }
    @Override public void onSpeak(String text) { withService(s -> s.sayText(text)); }
    @Override public void onPlayVocalization(String id) { withService(s -> s.playVocal(id)); }
    @Override public void onSetBackpackColor(int colorInt) {
        String preset = nearestPreset(colorInt);
        withLink(l -> l.setBackpackColor(preset));
    }
    @Override public void onToggleHeadlight(boolean enabled) {
        withLink(l -> l.setHeadLight(enabled));
    }
    @Override public void onToggleAutonomousMaster(boolean enabled) {
        withService(s -> s.setAutonomousConfig(enabled, s.autonomousVoiceEnabled(), s.autonomousMotionEnabled(), s.autonomousWheelsEnabled()));
    }
    @Override public void onToggleAutonomousVoice(boolean enabled) {
        withService(s -> s.setAutonomousConfig(s.autonomousEnabled(), enabled, s.autonomousMotionEnabled(), s.autonomousWheelsEnabled()));
    }
    @Override public void onToggleAutonomousMotion(boolean enabled) {
        withService(s -> s.setAutonomousConfig(s.autonomousEnabled(), s.autonomousVoiceEnabled(), enabled, s.autonomousWheelsEnabled()));
    }
    @Override public void onToggleAutonomousWheels(boolean enabled) {
        withService(s -> s.setAutonomousConfig(s.autonomousEnabled(), s.autonomousVoiceEnabled(), s.autonomousMotionEnabled(), enabled));
    }
    @Override public void onSetCameraEnabled(boolean enabled) {
        withService(s -> s.cameraToggle(enabled));
    }
    @Override public void onSnapPhoto() { serviceAction("SNAPSHOT"); }
    @Override public void onArmMotors() { serviceAction("ARM"); }
    @Override public void onDrive(float l, float r) { startService(new Intent(this, RobotService.class).setAction("DRIVE").putExtra("left", l).putExtra("right", r)); }
    @Override public void onStopMotors() {
        Link l = link();
        if (l != null) l.stopMotion();
    }
    @Override public void onMoveHead(float deltaOrTargetRad) {
        startService(new Intent(this, RobotService.class).setAction("HEAD").putExtra("delta", deltaOrTargetRad));
    }
    @Override public void onMoveLift(float deltaOrTargetMm) {
        startService(new Intent(this, RobotService.class).setAction("LIFT").putExtra("delta", deltaOrTargetMm));
    }
    @Override public void onCubesScanToggle() { withService(RobotService::connectCubes); }
    @Override public void onCubesQuickTap() { startService(new Intent(this, RobotService.class).setAction("QUICK_TAP")); }
    @Override public void onGreetOrRegisterPerson(String name) {
        startService(new Intent(this, RobotService.class).setAction("MEET").putExtra("name", name));
    }
    @Override public void onSetGeminiMode(String mode) {
        startService(new Intent(this, RobotService.class).setAction("AI_POLICY").putExtra("policy", mode));
    }
    @Override public void onSendGeminiPrompt(String prompt) {
        startService(new Intent(this, RobotService.class).setAction("AI_PROMPT").putExtra("prompt", prompt));
    }
    @Override public void onConnectWifi(String ssid, String password) {
        connectWifi(ssid, password, false);
    }
    @Override public void onConnectExistingWifi(String ssid) {
        connectWifi(ssid, "", true);
    }
    @Override public void onDisconnect() { serviceAction("DISCONNECT"); }
    @Override public void onToggleStartupHead(boolean enabled) {
        startService(new Intent(this, RobotService.class).setAction("AUTO_HEAD").putExtra("enabled", enabled));
    }
    @Override public void onToggleCriticalBatteryShutdown(boolean enabled) {
        startService(new Intent(this, RobotService.class).setAction("AUTO_SHUTDOWN").putExtra("enabled", enabled));
    }
    @Override public void onPowerOffRobot() { serviceAction("SHUTDOWN"); }
    @Override public void onOpenLauncherApp(String packageName) {
        // Cozmo è registrata come app Home: serve un modo esplicito per tornare al dock WebView
        // o al drawer di sistema senza passare dal resolver.
        Intent intent = null;
        if (packageName != null && !packageName.trim().isEmpty()) {
            try { intent = getPackageManager().getLaunchIntentForPackage(packageName); } catch (Exception ignored) { }
        }
        if (intent == null) {
            // Cozmo è il default HOME, quindi ACTION_MAIN/CATEGORY_HOME risolverebbe di nuovo su
            // Cozmo stesso. Puntiamo direttamente al launcher di sistema (QuickstepLauncher),
            // con fallback su ACTION_MAIN/CATEGORY_HOME se il pacchetto non è quello atteso.
            intent = new Intent();
            intent.setClassName("com.android.launcher3", "com.android.launcher3.uioverrides.QuickstepLauncher");
            try {
                intent.resolveActivity(getPackageManager());
                if (intent.resolveActivity(getPackageManager()) == null) intent = null;
            } catch (Exception ignored) { intent = null; }
        }
        if (intent == null) {
            intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            // Ferma i motori prima di lasciare Cozmo: fail-safe.
            ((DriveTab) tabDrive).stopDpadMotors();
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Impossibile aprire: " + packageName, Toast.LENGTH_LONG).show();
        }
    }
    @Override public void onOpenSystemSettings() {
        Intent intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }
    @Override public void onSaveGeminiKey(String apiKey) {
        startService(new Intent(this, RobotService.class).setAction("AI_KEY").putExtra("key", apiKey));
    }
    @Override public void onTestGeminiKey() { serviceAction("AI_TEST"); }

    private void care(String kind) {
        if (svc() != null) svc().careInteraction(kind);
        else startService(new Intent(this, RobotService.class).setAction("CARE").putExtra("kind", kind));
    }

    private void serviceAction(String action) {
        startService(new Intent(this, RobotService.class).setAction(action));
    }

    private void withService(ServiceAction action) {
        RobotService s = svc();
        if (s == null) { toast("Servizio Cozmo non avviato"); return; }
        action.run(s);
    }

    private void withLink(LinkAction action) {
        Link l = link();
        if (l == null || l.stateAge() > 350) { toast("Collegamento al robot non attivo"); return; }
        action.run(l);
    }

    private interface ServiceAction { void run(RobotService s); }
    private interface LinkAction { void run(Link l); }

    private String wifiPermission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.NEARBY_WIFI_DEVICES : Manifest.permission.ACCESS_FINE_LOCATION;
    }

    private void connectWifi(String ssid, String password, boolean existing) {
        if (!ssid.startsWith("Cozmo_")) { toast("Controlla il nome esatto della rete Cozmo"); return; }
        if (!existing && password.length() < 8) { toast("Inserisci la password mostrata sul robot"); return; }
        Intent connection = new Intent(this, RobotService.class).setAction("CONNECT")
            .putExtra("ssid", ssid).putExtra("password", password).putExtra("existing", existing);
        String wifi = wifiPermission();
        if (checkSelfPermission(wifi) != PackageManager.PERMISSION_GRANTED) {
            pendingWifiConnection = connection;
            requestPermissions(new String[]{wifi}, PERM_REQ);
            return;
        }
        startForegroundService(connection);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != PERM_REQ || pendingWifiConnection == null) return;
        Intent connection = pendingWifiConnection;
        pendingWifiConnection = null;
        if (checkSelfPermission(wifiPermission()) == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(connection);
        } else {
            toast("Permesso Wi-Fi necessario per collegare Cozmo");
        }
    }

    private String savedSsid() {
        return getSharedPreferences("robot", 0).getString("ssid", "Cozmo_71278E");
    }

    private void connectWifiFromPrefs() {
        connectWifi(savedSsid(), Secrets.read(this), false);
    }

    private String nearestPreset(int colorInt) {
        int r = (colorInt >> 16) & 0xFF, g = (colorInt >> 8) & 0xFF, b = colorInt & 0xFF;
        if (r > 200 && g > 150 && b < 100) return "amber";
        if (r < 60 && g > 150 && b > 150) return "cyan";
        if (r < 60 && g > 150 && b < 80) return "green";
        if (r > 150 && g < 80 && b > 150) return "purple";
        if (r > 150 && g < 80 && b < 80) return "red";
        return "cyan";
    }

    /* ================================================================== */
    /* UI refresh                                                         */
    /* ================================================================== */

    private void refreshUi() {
        RobotService s = svc();
        Link l = s != null ? s.link : null;
        boolean connected = l != null && l.stateAge() < 350;
        boolean factoryFirmware = l != null && l.isFactoryFirmware();
        boolean visualOnly = l != null && l.isVisualOnly();

        // Top bar
        if (stateDot != null) {
            int dotColor = connected ? UiKit.OK : (factoryFirmware || visualOnly ? UiKit.WARN : 0xFF5E7B8F);
            stateDot.setBackground(UiKit.box(this, dotColor, Color.TRANSPARENT, 999, 0));
            stateText.setText(s != null ? s.status : "Disconnesso");
            stateTag.setText(connected ? (factoryFirmware ? "FACTORY" : visualOnly ? "VISUAL" : "LIVE") : "—");
        }

        Wire.Telemetry t = tel();
        String batt = "—";
        String motors = "Motori bloccati";
        if (t != null) {
            batt = String.format(Locale.ITALY, "%.2f V%s", t.battery, t.onCharger() ? " ⚡" : "");
            motors = l != null && l.armed() ? String.format(Locale.ITALY, "Armati · %d s", (int)((l.armRemainingMs() + 999) / 1000)) : "Bloccati";
        }
        if (barBattery != null) barBattery.setText(batt);
        if (barMotors != null) barMotors.setText(motors);

        // Build UiState
        UiState st = new UiState();
        st.connected = connected;
        st.connecting = !connected && s != null;
        st.visualOnly = visualOnly;
        st.factoryFirmware = factoryFirmware;
        st.connectionLabel = s != null ? s.status : "Disconnesso";
        if (t != null) {
            st.batteryVolts = t.battery;
            st.onCharger = t.onCharger();
            st.headDeg = (float) Math.toDegrees(t.head);
            st.liftMm = t.lift;
            st.cliff = t.cliff.clone();
        }
        st.armed = l != null && l.armed();
        st.canArm = l != null && !l.armed() && l.canArm();
        st.armRemainingSec = l != null ? (int) ((l.armRemainingMs() + 999) / 1000) : 0;
        st.jointBlockReason = l != null ? l.jointBlockReason() : "";
        st.wheelBlockReason = l != null ? l.wheelBlockReason() : "";
        st.statusHex = l != null ? l.statusHex() : "0x0000";
        st.txPackets = l != null ? l.getTxPackets() : 0;
        st.rxPackets = l != null ? l.getRxPackets() : 0;
        st.invalidPackets = l != null ? l.getInvalidPackets() : 0;

        if (s != null) {
            Personality.PersonalitySnapshot snap = s.personality.snapshot(Link.now());
            st.activeBehavior = s.activeBehavior;
            st.activeMood = snap.expression.label;
            st.energyPct = Math.round(snap.energy * 100f);
            st.playPct = Math.round(snap.play * 100f);
            st.comfortPct = Math.round(snap.comfort * 100f);
            st.personalityId = snap.profile.name();
            st.autonomousMaster = s.autonomousEnabled();
            st.autonomousVoice = s.autonomousVoiceEnabled();
            st.autonomousMotion = s.autonomousMotionEnabled();
            st.autonomousWheels = s.autonomousWheelsEnabled();
            st.headlightOn = false; // tracked in Link; not exposed simply here
            st.selectedLedColor = 0xFF00FFFF;
            st.cameraBitmap = s.cameraImage;
            st.cameraRunning = s.cameraImage != null;
            if (s.visionResult != null) {
                st.faceDetected = s.visionResult.faceCount > 0;
                st.motionDetected = s.visionResult.motionScore >= 0.30f;
            }
            st.cubesSummary = s.cubes != null ? s.cubes.summary(Link.now()) : "Nessun cubo collegato";
            st.cubesConnected = s.cubes != null && s.cubes.connectedCount() > 0;
            st.quickTapActive = s.cubes != null && s.cubes.isQuickTapActive();
            st.knownPeople = s.memoryDb != null ? knownPeopleNames(s.memoryDb) : Collections.emptyList();
            st.geminiMode = s.aiPolicy().name();
            st.geminiStatus = s.lastAiSummary;
            st.geminiLastIntent = s.lastIntention != null ? s.lastIntention.summary(Link.now()) : "";
            st.geminiKeyConfigured = s.hasGeminiKey();
            st.startupHeadOnCharger = getSharedPreferences("robot", 0).getBoolean("auto_head_on_dock", true);
            st.criticalBatteryShutdown = l != null ? l.isAutoShutdownLowBattery() : getSharedPreferences("robot", 0).getBoolean("auto_shutdown_low_battery", true);
            st.recoveryRunning = l != null && l.isRecoveringFirmware();
            st.recoveryStatus = s.notice;
        }

        // Render active tab (and all on switch for state consistency)
        switch (currentTab) {
            case 0: tabCompanion.render(st); break;
            case 1: tabDrive.render(st); break;
            case 2: tabMind.render(st); break;
            case 3: tabSystem.render(st); break;
        }
    }

    private List<String> knownPeopleNames(MemoryDb db) {
        List<MemoryDb.Person> persons = db.listPersons();
        List<String> names = new ArrayList<>();
        for (MemoryDb.Person p : persons) names.add(p.name);
        return names;
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }
}
