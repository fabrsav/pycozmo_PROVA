package dev.s200.cozmo;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import dev.s200.cozmo.core.*;
import java.io.*;
import java.util.*;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(10, 23, 32), CARD = Color.rgb(20, 41, 54),
        TEXT = Color.rgb(233, 244, 247), MUTED = Color.rgb(155, 180, 190),
        CYAN = Color.rgb(100, 229, 238), ORANGE = Color.rgb(255, 178, 91);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout root, content;
    private TextView status, detail, sensors, notice, cameraLabel, visionLabel, cubesLabel,
        personalityLabel, behaviorLabel, memoryLabel, aiKeyStatusLabel, aiLabel;
    private EditText ssid, password, sayInput, meetInput, aiKeyInput, aiInput;
    private ImageView camera;
    private Button arm;
    private EyeView eyePreview;
    private boolean pendingConnect = false, existingMode = false;
    private boolean robotSection = false, muteToggles = false;
    private Switch lifeToggle, autoToggle, autoVoiceToggle, autoMotionToggle, autoWheelsToggle;
    private final List<Button> readyButtons = new ArrayList<>();
    private final Runnable update = new Runnable() {
        public void run() {
            refresh();
            handler.postDelayed(this, 300);
        }
    };

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets b = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(0, b.top, 0, b.bottom);
            return insets;
        });
        setContentView(root);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(16));
        scroll.addView(content);

        TextView brand = text("COZMO / S200", 12, CYAN);
        brand.setLetterSpacing(0.15f);
        content.addView(brand);
        TextView title = text("Di nuovo insieme.", 29, TEXT);
        title.setTypeface(null, Typeface.BOLD);
        margin(title, 4, 4);
        content.addView(title);
        content.addView(text("Collega il tuo Cozmo e ritrova il suo sguardo.", 14, MUTED));

        eyePreview = new EyeView();
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, dp(112));
        ep.topMargin = dp(16);
        ep.bottomMargin = dp(6);
        content.addView(eyePreview, ep);
        TextView preview = text("ANTEPRIMA ESPRESSIONE", 10, MUTED);
        preview.setGravity(Gravity.CENTER);
        content.addView(preview);

        LinearLayout stateCard = card();
        status = text("Pronto a collegarsi", 20, TEXT);
        status.setTypeface(null, Typeface.BOLD);
        stateCard.addView(status);
        detail = text("Nessuna telemetria ricevuta", 13, MUTED);
        margin(detail, 8, 0);
        stateCard.addView(detail);
        notice = text("", 13, ORANGE);
        stateCard.addView(notice);

        // 01 Connessione
        LinearLayout network = card();
        heading(network, "01  Connessione");
        network.addView(text("Nome della rete Cozmo", 12, MUTED));
        ssid = field("Cozmo_71278E", false);
        ssid.setText(getSharedPreferences("robot", 0).getString("ssid", "Cozmo_71278E"));
        network.addView(ssid);
        network.addView(text("Password visualizzata sul robot", 12, MUTED));
        password = field("Password Wi-Fi", true);
        password.setSaveEnabled(false);
        password.setText(Secrets.read(this));
        network.addView(password);
        network.addView(text("Sulla basetta, alza e abbassa due volte il carrello. Android chiederà di confermare la rete.", 12, MUTED));

        Switch startupHead = new Switch(this);
        startupHead.setText("Solleva la testa al collegamento");
        startupHead.setTextColor(TEXT);
        startupHead.setPadding(0, dp(10), 0, dp(10));
        startupHead.setChecked(getSharedPreferences("robot", 0).getBoolean("auto_head_on_dock", true));
        startupHead.setOnCheckedChangeListener((b, on) -> getSharedPreferences("robot", 0).edit().putBoolean("auto_head_on_dock", on).apply());
        network.addView(startupHead);
        network.addView(text("Sulla basetta la testa si alza per liberare la visuale della camera.", 12, MUTED));

        Switch autoShutdown = new Switch(this);
        autoShutdown.setText("Spegni Cozmo se batteria critica (< 3,25 V)");
        autoShutdown.setTextColor(TEXT);
        autoShutdown.setPadding(0, dp(6), 0, dp(10));
        autoShutdown.setChecked(getSharedPreferences("robot", 0).getBoolean("auto_shutdown_low_battery", true));
        autoShutdown.setOnCheckedChangeListener((b, on) -> {
            getSharedPreferences("robot", 0).edit().putBoolean("auto_shutdown_low_battery", on).apply();
            if (RobotService.live != null && RobotService.live.link != null) RobotService.live.link.setAutoShutdownLowBattery(on);
        });
        network.addView(autoShutdown);
        network.addView(text("Fuori basetta spegne Cozmo automaticamente prima che la batteria vada in protezione.", 12, MUTED));
        row(network, button("Collega", CYAN, () -> connect(false)), button("Scollega", CARD, () -> serviceAction("DISCONNECT")));
        Button current = button("Usa Wi-Fi già collegato", CARD, () -> connect(true));
        network.addView(current);

        // 02 Sguardo, voce, personalità e movimenti
        LinearLayout controls = card();
        heading(controls, "02  Personalità, sguardo, voce e movimenti");
        robotSection = true;
        Switch life = new Switch(this);
        life.setText("Occhi animati sul robot");
        life.setTextColor(TEXT);
        life.setPadding(0, dp(10), 0, dp(8));
        controls.addView(life);
        lifeToggle = life;
        life.setOnCheckedChangeListener((b, on) -> {
            if (muteToggles) return;
            RobotService s = RobotService.live;
            if (s != null && s.link != null && s.link.stateAge() < 350) s.life(on);
            else if (on) { b.setChecked(false); toast("Collega prima il robot"); }
        });
        robotSection = false;

        SharedPreferences prefs = getSharedPreferences("robot", 0);
        autoToggle = new Switch(this);
        autoToggle.setText("Vita autonoma (espressioni e LED)");
        autoToggle.setTextColor(TEXT);
        autoToggle.setPadding(0, dp(6), 0, dp(6));
        autoToggle.setChecked(prefs.getBoolean("autonomous_life", true));
        autoToggle.setOnCheckedChangeListener((b, on) -> pushAutonomousConfig());
        controls.addView(autoToggle);

        autoVoiceToggle = new Switch(this);
        autoVoiceToggle.setText("Voce autonoma");
        autoVoiceToggle.setTextColor(TEXT);
        autoVoiceToggle.setPadding(0, dp(6), 0, dp(6));
        autoVoiceToggle.setChecked(prefs.getBoolean("autonomous_voice", true));
        autoVoiceToggle.setOnCheckedChangeListener((b, on) -> pushAutonomousConfig());
        controls.addView(autoVoiceToggle);

        autoMotionToggle = new Switch(this);
        autoMotionToggle.setText("Gesti testa/carrello");
        autoMotionToggle.setTextColor(TEXT);
        autoMotionToggle.setPadding(0, dp(6), 0, dp(6));
        autoMotionToggle.setChecked(prefs.getBoolean("autonomous_motion", false));
        autoMotionToggle.setOnCheckedChangeListener((b, on) -> {
            if (!on && autoWheelsToggle.isChecked()) {
                muteToggles = true;
                autoWheelsToggle.setChecked(false);
                muteToggles = false;
            }
            pushAutonomousConfig();
        });
        controls.addView(autoMotionToggle);

        autoWheelsToggle = new Switch(this);
        autoWheelsToggle.setText("Cingoli autonomi (solo fuori basetta)");
        autoWheelsToggle.setTextColor(TEXT);
        autoWheelsToggle.setPadding(0, dp(6), 0, dp(10));
        autoWheelsToggle.setChecked(prefs.getBoolean("autonomous_wheels", false));
        autoWheelsToggle.setOnCheckedChangeListener((b, on) -> {
            if (on && !autoMotionToggle.isChecked()) {
                muteToggles = true;
                autoMotionToggle.setChecked(true);
                muteToggles = false;
            }
            pushAutonomousConfig();
        });
        controls.addView(autoWheelsToggle);

        personalityLabel = text("Carattere: Curioso · Espressione: Neutro", 12, MUTED);
        controls.addView(personalityLabel);
        behaviorLabel = text("Comportamento attivo: nessuno", 12, CYAN);
        margin(behaviorLabel, 4, 8);
        controls.addView(behaviorLabel);

        row(controls,
            button("Curioso", CARD, () -> selectProfile("CURIOSO")),
            button("Affettuoso", CARD, () -> selectProfile("AFFETTUOSO")),
            button("Birichino", CARD, () -> selectProfile("BIRICHINO")),
            button("Tranquillo", CARD, () -> selectProfile("TRANQUILLO")));

        controls.addView(text("Cura dei bisogni e coreografie", 12, MUTED));
        row(controls,
            button("Coccole / Comfort +", CYAN, () -> sendCare("comfort")),
            button("Gioca +", CYAN, () -> sendCare("play")));
        row(controls,
            button("Clip Saluto", CARD, () -> sendClip("GREET_FAMILIAR")),
            button("Clip Curioso", CARD, () -> sendClip("CURIOUS_INSPECT")),
            button("Clip Esulta", CARD, () -> sendClip("PLAY_CHEER")));

        robotSection = true;
        row(controls,
            button("Felice", CARD, () -> withService(s -> s.setExpression("HAPPY", 5000))),
            button("Curioso", CARD, () -> withService(s -> s.setExpression("CURIOUS", 5000))),
            button("Entusiasta", CARD, () -> withService(s -> s.setExpression("EXCITED", 5000))));
        row(controls,
            button("Sorpreso", CARD, () -> withService(s -> s.setExpression("SURPRISED", 5000))),
            button("Scettico", CARD, () -> withService(s -> s.setExpression("SKEPTICAL", 5000))),
            button("Assonnato", CARD, () -> withService(s -> s.setExpression("SLEEPY", 5000))));

        controls.addView(text("Vocalizzi Cozmo (22050 Hz u-law)", 12, MUTED));
        row(controls,
            button("Voce Curioso", CARD, () -> withService(s -> s.playVocal("curious"))),
            button("Voce Felice", CARD, () -> withService(s -> s.playVocal("happy"))),
            button("Canta", CARD, () -> withService(s -> s.playVocal("sing"))));
        sayInput = field("Fai parlare Cozmo (es. ciao!)", false);
        controls.addView(sayInput);
        controls.addView(button("Pronuncia testo", CYAN, () -> {
            String t = sayInput.getText().toString().trim();
            if (!t.isEmpty()) withService(s -> s.sayText(t));
        }));
        row(controls,
            button("Faro ON", CARD, () -> withLink(l -> l.setHeadLight(true))),
            button("Faro OFF", CARD, () -> withLink(l -> l.setHeadLight(false))),
            button("Zaino Ciano", CARD, () -> withLink(l -> l.setBackpackColor("cyan"))));
        row(controls,
            button("Zaino Verde", CARD, () -> withLink(l -> l.setBackpackColor("green"))),
            button("Zaino Ambra", CARD, () -> withLink(l -> l.setBackpackColor("amber"))),
            button("Zaino Viola", CARD, () -> withLink(l -> l.setBackpackColor("purple"))));

        arm = button("Abilita movimenti · 60 s", ORANGE, () -> new AlertDialog.Builder(this)
            .setTitle("Verifica lo spazio intorno a Cozmo")
            .setMessage("Usa una superficie bassa e libera. I cingoli eseguono impulsi brevi e restano bloccati sulla basetta. STOP è sempre disponibile.")
            .setPositiveButton("Abilita", (d, w) -> withLink(Link::arm))
            .setNegativeButton("Annulla", null)
            .show());
        margin(arm, 8, 4);
        controls.addView(arm);
        controls.addView(text("Cingoli · impulsi fino a 250 ms", 12, MUTED));
        row(controls,
            button("←", CARD, () -> withLink(l -> l.drive(-30, 30))),
            button("↑", CARD, () -> withLink(l -> l.drive(30, 30))),
            button("→", CARD, () -> withLink(l -> l.drive(30, -30))));
        controls.addView(button("↓  Indietro", CARD, () -> withLink(l -> l.drive(-30, -30))));
        row(controls, button("Testa −", CARD, () -> joint(false, -0.1f)), button("Testa +", CARD, () -> joint(false, 0.1f)));
        row(controls, button("Carrello −", CARD, () -> joint(true, -5)), button("Carrello +", CARD, () -> joint(true, 5)));
        controls.addView(button("Spegni Cozmo", ORANGE, () -> new AlertDialog.Builder(this)
            .setTitle("Spegni Cozmo")
            .setMessage("Invia il comando di spegnimento pulito (ShutdownRobot) al robot?")
            .setPositiveButton("Spegni", (d, w) -> serviceAction("SHUTDOWN"))
            .setNegativeButton("Annulla", null)
            .show()));

        // 03 Camera
        LinearLayout view = card();
        heading(view, "03  Attraverso i suoi occhi");
        cameraLabel = text("Camera del robot · nessun fotogramma", 12, MUTED);
        view.addView(cameraLabel);
        visionLabel = text("Percezione 2D: in attesa di fotogrammi", 12, MUTED);
        view.addView(visionLabel);
        camera = new ImageView(this);
        camera.setScaleType(ImageView.ScaleType.FIT_CENTER);
        camera.setBackgroundColor(BG);
        view.addView(camera, new LinearLayout.LayoutParams(-1, dp(190)));
        row(view,
            button("Avvia camera", CARD, () -> withService(s -> s.cameraToggle(true))),
            button("Ferma", CARD, () -> withService(s -> s.cameraToggle(false))),
            button("Scatta JPEG", CARD, () -> serviceAction("SNAPSHOT")));

        // 04 Cubi
        LinearLayout cubesCard = card();
        heading(cubesCard, "04  Cubi luminosi e Quick Tap");
        cubesLabel = text("Cubi: in attesa di scansione BLE", 12, MUTED);
        cubesCard.addView(cubesLabel);
        row(cubesCard,
            button("Collega cubi", CARD, () -> withService(RobotService::connectCubes)),
            button("Luci Ciano", CARD, () -> withService(s -> s.setCubesLight("cyan"))),
            button("Spin", CARD, () -> withService(s -> s.setCubesLight("spin"))));
        row(cubesCard,
            button("Avvia Quick Tap", CYAN, () -> withService(RobotService::startQuickTap)),
            button("Tap / Punto", CARD, () -> withService(RobotService::simulateQuickTap)),
            button("Stop Gioco", CARD, () -> withService(RobotService::stopQuickTap)));
        robotSection = false;

        // 05 Memoria sociale e AI ibrida
        LinearLayout socialCard = card();
        heading(socialCard, "05  Memoria sociale e AI ibrida");
        memoryLabel = text("Persone ricordate: nessuna", 12, MUTED);
        socialCard.addView(memoryLabel);
        meetInput = field("Nome persona da ricordare", false);
        socialCard.addView(meetInput);
        socialCard.addView(button("Registra / Saluta persona", CYAN, () -> {
            String n = meetInput.getText().toString().trim();
            if (!n.isEmpty()) {
                startService(new Intent(this, RobotService.class).setAction("MEET").putExtra("name", n));
                meetInput.setText("");
            }
        }));

        aiKeyStatusLabel = text("Chiave Gemini: non configurata (solo locale)", 12, MUTED);
        margin(aiKeyStatusLabel, 10, 2);
        socialCard.addView(aiKeyStatusLabel);
        aiKeyInput = field("Chiave API Gemini Live (custodita in Keystore)", true);
        aiKeyInput.setSaveEnabled(false);
        socialCard.addView(aiKeyInput);
        row(socialCard,
            button("Salva chiave Gemini", CARD, () -> {
                String k = aiKeyInput.getText().toString().trim();
                if (!k.isEmpty()) {
                    startService(new Intent(this, RobotService.class).setAction("AI_KEY").putExtra("key", k));
                    aiKeyInput.setText("");
                    toast("Chiave Gemini salvata in Keystore");
                }
            }),
            button("Cancella chiave", CARD, () -> {
                startService(new Intent(this, RobotService.class).setAction("AI_KEY").putExtra("clear", true));
                aiKeyInput.setText("");
                toast("Chiave Gemini rimossa");
            }));

        socialCard.addView(text("Modalità supervisore AI", 12, MUTED));
        row(socialCard,
            button("Solo locale", CARD, () -> sendAiPolicy("OFFLINE_ONLY")),
            button("Gemini su richiesta", CARD, () -> sendAiPolicy("ON_DEMAND")),
            button("Gemini 45 s", CARD, () -> sendAiPolicy("AMBIENT_HYBRID")));
        socialCard.addView(button("Test connessione AI", CARD, () ->
            startService(new Intent(this, RobotService.class).setAction("AI_TEST"))));

        aiLabel = text("Supervisore AI locale pronto", 12, MUTED);
        margin(aiLabel, 10, 4);
        socialCard.addView(aiLabel);
        aiInput = field("Chiedi a Cozmo (es. ciao, coccole, canta, come stai, esplora)", false);
        socialCard.addView(aiInput);
        socialCard.addView(button("Invia al supervisore AI", CYAN, () -> {
            String p = aiInput.getText().toString().trim();
            if (!p.isEmpty()) {
                startService(new Intent(this, RobotService.class).setAction("AI_PROMPT").putExtra("prompt", p));
            }
        }));

        // 06 Diagnostica
        LinearLayout diagnostic = card();
        heading(diagnostic, "06  Stato del robot");
        sensors = text("Sensori in attesa di connessione", 13, MUTED);
        diagnostic.addView(sensors);
        diagnostic.addView(button("Esporta diagnostica", CARD, () -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType("application/x-ndjson")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, "cozmo-diagnostica.ndjson");
            startActivityForResult(i, 4);
        }));
        diagnostic.addView(button("Impostazioni Wi-Fi", CARD, () -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS))));

        TextView footer = text("Cozmo S200 · " + appVersion() + "\nConnessione diretta, personalità e sicurezza sul telefono.", 11, MUTED);
        footer.setGravity(Gravity.CENTER);
        margin(footer, 12, 8);
        content.addView(footer);

        LinearLayout stopBar = new LinearLayout(this);
        stopBar.setPadding(dp(20), dp(8), dp(20), dp(10));
        stopBar.setBackgroundColor(BG);
        Button stop = button("■  STOP", Color.rgb(212, 72, 72), () -> serviceAction("STOP"));
        stopBar.addView(stop, new LinearLayout.LayoutParams(-1, dp(54)));
        root.addView(stopBar);

        startService(new Intent(this, RobotService.class));
        applyIntent(getIntent());
    }

    private void pushAutonomousConfig() {
        if (muteToggles) return;
        boolean en = autoToggle.isChecked();
        boolean voice = autoVoiceToggle.isChecked();
        boolean motion = autoMotionToggle.isChecked();
        boolean wheels = autoWheelsToggle.isChecked();
        if (RobotService.live != null) {
            RobotService.live.setAutonomousConfig(en, voice, motion, wheels);
        } else {
            startService(new Intent(this, RobotService.class)
                .setAction("AUTONOMOUS")
                .putExtra("enabled", en)
                .putExtra("voice", voice)
                .putExtra("motion", motion)
                .putExtra("wheels", wheels));
        }
    }

    private void sendCare(String kind) {
        if (RobotService.live != null) RobotService.live.careInteraction(kind);
        else startService(new Intent(this, RobotService.class).setAction("CARE").putExtra("kind", kind));
    }

    private void sendClip(String clipId) {
        if (RobotService.live != null) RobotService.live.triggerClip(clipId, AnimClip.Priority.USER_DIRECT);
        else startService(new Intent(this, RobotService.class).setAction("CLIP").putExtra("clip", clipId));
    }

    private void sendAiPolicy(String policy) {
        if (RobotService.live != null) RobotService.live.setAiPolicy(policy, null);
        else startService(new Intent(this, RobotService.class).setAction("AI_POLICY").putExtra("policy", policy));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntent(intent);
    }

    private void applyIntent(Intent i) {
        if (i.hasExtra("ssid")) ssid.setText(i.getStringExtra("ssid"));
        if (i.hasExtra("password")) {
            password.setText(i.getStringExtra("password"));
            i.removeExtra("password");
        }
        if (i.getBooleanExtra("connect", false)) {
            i.removeExtra("connect");
            handler.postDelayed(() -> connect(false), 500);
        }
    }

    private void joint(boolean lift, float step) {
        RobotService s = RobotService.live;
        if (s == null || s.telemetry == null) {
            toast("Telemetria non disponibile");
            return;
        }
        if (lift) withLink(l -> l.lift(s.telemetry.lift + step));
        else withLink(l -> l.head(s.telemetry.head + step));
    }

    interface LinkAction { void run(Link l); }
    interface ServiceAction { void run(RobotService s); }

    private void withLink(LinkAction action) {
        RobotService s = RobotService.live;
        if (s == null || s.link == null || s.link.stateAge() > 350) {
            toast("Collegamento al robot non attivo");
            return;
        }
        action.run(s.link);
    }

    private void withService(ServiceAction action) {
        RobotService s = RobotService.live;
        if (s == null) {
            toast("Servizio Cozmo non avviato");
            return;
        }
        action.run(s);
    }

    private void connect(boolean existing) {
        String name = ssid.getText().toString().trim(), pass = password.getText().toString();
        if (!name.startsWith("Cozmo_")) {
            toast("Controlla il nome esatto della rete Cozmo");
            return;
        }
        if (!existing && pass.length() < 8) {
            toast("Inserisci la password mostrata sul robot");
            return;
        }
        List<String> missing = new ArrayList<>();
        String wifi = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.NEARBY_WIFI_DEVICES : Manifest.permission.ACCESS_FINE_LOCATION;
        if (checkSelfPermission(wifi) != PackageManager.PERMISSION_GRANTED) missing.add(wifi);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            existingMode = existing;
            pendingConnect = true;
            requestPermissions(missing.toArray(new String[0]), 2);
            return;
        }
        getSharedPreferences("robot", 0).edit().putString("ssid", name).apply();
        Intent i = new Intent(this, RobotService.class).setAction("CONNECT").putExtra("ssid", name).putExtra("password", pass).putExtra("existing", existing);
        startForegroundService(i);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 2 && pendingConnect) {
            pendingConnect = false;
            String wifi = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.NEARBY_WIFI_DEVICES : Manifest.permission.ACCESS_FINE_LOCATION;
            if (checkSelfPermission(wifi) == PackageManager.PERMISSION_GRANTED) {
                Intent i = new Intent(this, RobotService.class)
                    .setAction("CONNECT")
                    .putExtra("ssid", ssid.getText().toString().trim())
                    .putExtra("password", password.getText().toString())
                    .putExtra("existing", existingMode);
                startForegroundService(i);
            } else {
                toast("Permesso Wi-Fi necessario per collegare Cozmo");
            }
        }
    }

    private void serviceAction(String action) {
        startService(new Intent(this, RobotService.class).setAction(action));
    }

    private void selectProfile(String name) {
        getSharedPreferences("robot", 0).edit().putString("personality_profile", name).apply();
        if (RobotService.live != null) RobotService.live.setPersonalityProfile(name);
        else startService(new Intent(this, RobotService.class).setAction("PROFILE").putExtra("profile", name));
        toast("Carattere: " + Personality.Profile.fromName(name).label);
    }

    private void refresh() {
        RobotService s = RobotService.live;
        long now = Link.now();
        boolean ready = s != null && s.link != null && s.link.stateAge() < 350;
        for (Button b : readyButtons) b.setEnabled(ready);
        lifeToggle.setEnabled(ready);
        boolean eyes = s != null && s.lifeEnabled();
        SharedPreferences prefs = getSharedPreferences("robot", 0);
        boolean autoOn = s != null ? s.autonomousEnabled() : prefs.getBoolean("autonomous_life", true);
        boolean voiceOn = s != null ? s.autonomousVoiceEnabled() : prefs.getBoolean("autonomous_voice", true);
        boolean motionOn = s != null ? s.autonomousMotionEnabled() : prefs.getBoolean("autonomous_motion", false);
        boolean wheelsOn = s != null ? s.autonomousWheelsEnabled() : prefs.getBoolean("autonomous_wheels", false);

        muteToggles = true;
        if (lifeToggle.isChecked() != eyes) lifeToggle.setChecked(eyes);
        if (autoToggle.isChecked() != autoOn) autoToggle.setChecked(autoOn);
        if (autoVoiceToggle.isChecked() != voiceOn) autoVoiceToggle.setChecked(voiceOn);
        if (autoMotionToggle.isChecked() != motionOn) autoMotionToggle.setChecked(motionOn);
        if (autoWheelsToggle.isChecked() != wheelsOn) autoWheelsToggle.setChecked(wheelsOn);
        muteToggles = false;

        if (s == null) {
            status.setText("Pronto a collegarsi");
            detail.setText("Nessuna telemetria ricevuta");
            notice.setText("");
            sensors.setText("Sensori in attesa di connessione");
            camera.setImageDrawable(null);
            cameraLabel.setText("Camera del robot · nessun fotogramma");
            visionLabel.setText("Percezione 2D: in attesa di fotogrammi");
            cubesLabel.setText("Cubi: in attesa di scansione BLE");
            personalityLabel.setText("Espressione: Neutro · Bisogni in attesa");
            behaviorLabel.setText("Comportamento attivo: in attesa del servizio");
            memoryLabel.setText("Persone ricordate: nessuna");
            aiKeyStatusLabel.setText("Chiave Gemini: verifica in corso");
            aiLabel.setText("Supervisore AI locale pronto");
            eyePreview.setFace(Personality.Expression.NEUTRAL, Personality.Profile.CURIOSO);
            return;
        }

        status.setText(s.status);
        notice.setText(s.notice);
        Personality.PersonalitySnapshot snap = s.personality.snapshot(now);
        personalityLabel.setText(snap.moodSummaryIt);
        AnimClip activeClip = s.choreographer.activeClip();
        behaviorLabel.setText(String.format(Locale.ITALY,
            "Vita: %s · Voce: %s · Comportamento: %s%s\n%s",
            autoOn ? "ON" : "OFF",
            voiceOn ? "ON" : "Silenziosa",
            s.activeBehavior,
            activeClip != null ? " (clip " + activeClip.id + ")" : "",
            s.lastBehaviorReason));
        eyePreview.setFace(snap.expression, snap.profile);
        cubesLabel.setText(s.cubes.summary(now));
        if (s.memoryDb != null) memoryLabel.setText(s.memoryDb.summary());
        aiKeyStatusLabel.setText(String.format(Locale.ITALY,
            "Chiave Gemini: %s · Modalità: %s",
            s.hasGeminiKey() ? "Configurata in Keystore" : "Non configurata (offline)",
            s.aiPolicy().labelIt));
        aiLabel.setText(s.lastIntention != null ? s.lastIntention.summary(now) : s.lastAiSummary);

        Wire.Telemetry t = s.telemetry;
        Link l = s.link;
        if (t != null) {
            long age = l == null ? Long.MAX_VALUE : l.stateAge();
            boolean lowWarn = !t.onCharger() && t.battery > 0f && t.battery < Safety.LOW_BATTERY_WARN_V;
            detail.setText(String.format(Locale.ITALY, "%.2f V%s  ·  %d stati ricevuti (init: %s)\n%s",
                t.battery, lowWarn ? " (BATTERIA CRITICA)" : "", s.stateCount,
                l != null ? l.initPath() : "none",
                age < 1000 ? "Ultimo stato: " + age + " ms fa" : "Telemetria non aggiornata"));
            arm.setText(l != null && l.armed() ? "Movimenti abilitati · STOP per bloccare" : "Abilita movimenti · 60 s");
            sensors.setText(String.format(Locale.ITALY,
                "Testa %.1f° · Carrello %.1f mm\nCingoli %.1f / %.1f mm/s\nSensori di caduta: %s\nSollevato: %s · Caduta: %s\nBasetta: %s · Ricarica: %s\nFirmware: %s · Audio TX/HW: %d/%d",
                Math.toDegrees(t.head), t.lift, t.left, t.right, Arrays.toString(t.cliff),
                (t.status & 8) != 0 ? "sì" : "no", (t.status & 32) != 0 ? "sì" : "no",
                t.onCharger() ? "sì" : "no", (t.status & 0x2000) != 0 ? "sì" : "no",
                s.firmware, l != null ? l.audioFramesSent() : 0, l != null ? l.audioFramesPlayed() : 0));
        } else {
            detail.setText("Nessuna telemetria ricevuta");
            sensors.setText("Sensori in attesa di connessione");
        }
        if (s.cameraImage != null) {
            camera.setImageBitmap(s.cameraImage);
            cameraLabel.setText("Camera del robot · " + s.cameraCount + " fotogrammi ricevuti");
            if (s.visionResult != null) visionLabel.setText(s.visionResult.summary(s.cameraCount));
        } else {
            camera.setImageDrawable(null);
            cameraLabel.setText("Camera del robot · nessun fotogramma");
            visionLabel.setText("Percezione 2D: in attesa di fotogrammi");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 4 && result == RESULT_OK && data != null) {
            try (InputStream in = new FileInputStream(new File(getFilesDir(), "events.ndjson"));
                 OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) >= 0) out.write(b, 0, n);
                toast("Diagnostica esportata");
            } catch (Exception e) {
                toast("Nessun log disponibile o esportazione fallita");
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(update);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(update);
        super.onPause();
    }

    private TextView text(String value, int size, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setLineSpacing(dp(2), 1);
        return v;
    }

    private void heading(LinearLayout parent, String value) {
        TextView t = text(value, 16, TEXT);
        t.setTypeface(null, Typeface.BOLD);
        margin(t, 0, 12);
        parent.addView(t);
    }

    private void margin(View v, int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(top);
        p.bottomMargin = dp(bottom);
        v.setLayoutParams(p);
    }

    private LinearLayout card() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(16), dp(16), dp(16), dp(16));
        v.setBackground(shape(CARD));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(16);
        content.addView(v, p);
        return v;
    }

    private GradientDrawable shape(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(14));
        return d;
    }

    private EditText field(String hint, boolean secret) {
        EditText v = new EditText(this);
        v.setSingleLine(true);
        v.setTextColor(TEXT);
        v.setHintTextColor(MUTED);
        v.setTextSize(15);
        v.setHint(hint);
        v.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        v.setPadding(dp(10), dp(10), dp(10), dp(10));
        v.setBackgroundTintList(android.content.res.ColorStateList.valueOf(CYAN));
        margin(v, 4, 12);
        return v;
    }

    private Button button(String title, int color, Runnable action) {
        Button b = new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(color == CARD ? TEXT : BG);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        b.setMinHeight(dp(48));
        b.setOnClickListener(v -> action.run());
        if (robotSection) {
            readyButtons.add(b);
            b.setEnabled(false);
        }
        return b;
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "sviluppo";
        }
    }

    private void row(LinearLayout parent, Button... buttons) {
        LinearLayout row = new LinearLayout(this);
        for (Button b : buttons) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(52), 1);
            p.setMargins(dp(2), dp(3), dp(2), dp(3));
            row.addView(b, p);
        }
        parent.addView(row);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private final class EyeView extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Personality.Expression currentExpr = Personality.Expression.NEUTRAL;
        private Personality.Profile currentProf = Personality.Profile.CURIOSO;

        EyeView() {
            super(MainActivity.this);
            setBackground(shape(Color.rgb(7, 16, 23)));
        }

        void setFace(Personality.Expression expr, Personality.Profile prof) {
            if ((expr != null && expr != currentExpr) || (prof != null && prof != currentProf)) {
                if (expr != null) currentExpr = expr;
                if (prof != null) currentProf = prof;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            paint.setColor(CYAN);
            FaceParams fp = FaceParams.forExpression(currentExpr, currentProf);
            boolean[] px = FaceRenderer.renderStatic(fp);
            float cellW = Math.min(getWidth() / 136f, (getHeight() * 0.82f) / 32f);
            float cellH = cellW * 1.65f;
            float ox = (getWidth() - 128f * cellW) * 0.5f;
            float oy = (getHeight() - 32f * cellH) * 0.5f;
            for (int y = 0; y < 32; y++) {
                for (int x = 0; x < 128; x++) {
                    if (px[y * 128 + x]) {
                        c.drawRect(ox + x * cellW, oy + y * cellH, ox + (x + 0.92f) * cellW, oy + (y + 0.92f) * cellH, paint);
                    }
                }
            }
        }
    }
}
