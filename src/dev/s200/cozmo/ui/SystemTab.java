package dev.s200.cozmo.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class SystemTab {
    private final Context c;
    private final UiCallbacks cb;
    private final ScrollView root;
    private final EditText ssid, password, geminiKey;
    private final Button btnConnect, btnExisting, btnDisconnect, btnPowerOff, btnTestKey;
    private final Switch swStartupHead, swCriticalBattery;
    private final TextView wifiStatus, keyState, keyResult;
    private final LinearLayout recoveryCard;
    private final TextView recoveryStatus;
    private final ProgressBar recoveryProgress;
    private final TextView diagStatus, diagCliff, diagPackets, logBox;
    private final Button[] filterButtons;
    private final Button expandEvents;
    private enum Filter { ALL, MOTION, ERRORS }
    private Filter activeFilter = Filter.ALL;
    private boolean expanded, binding;
    private List<String> events = Collections.emptyList();

    public SystemTab(Context context, UiCallbacks callbacks) {
        c = context;
        cb = callbacks;
        root = new ScrollView(c);
        root.setFillViewport(true);
        LinearLayout container = new LinearLayout(c);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(c, 12);
        container.setPadding(pad, pad, pad, pad);
        container.setFocusableInTouchMode(true);
        root.addView(container);

        LinearLayout wifi = section(container, "Connessione Wi-Fi");
        wifiStatus = detail(wifi);
        wifi.addView(UiKit.subtitle(c, "Nome della rete"));
        ssid = UiKit.input(c, "Cozmo_…");
        wifi.addView(ssid);
        wifi.addView(UiKit.spacer(c, 0, 8));
        wifi.addView(UiKit.subtitle(c, "Password mostrata da Cozmo"));
        password = secretInput("Password Wi-Fi");
        wifi.addView(password);
        LinearLayout connectRow = buttonRow(wifi);
        btnConnect = UiKit.actionButton(c, "Collega", UiKit.ACCENT, UiKit.ACCENT_DARK);
        btnDisconnect = neutralButton("Scollega");
        addToRow(connectRow, btnConnect);
        addToRow(connectRow, btnDisconnect);
        btnExisting = neutralButton("Usa Wi-Fi già collegato");
        wifi.addView(UiKit.spacer(c, 0, 6));
        wifi.addView(btnExisting);
        btnConnect.setOnClickListener(v -> connect(false));
        btnExisting.setOnClickListener(v -> connect(true));
        btnDisconnect.setOnClickListener(v -> cb.onDisconnect());

        LinearLayout hardware = section(container, "Alimentazione");
        swStartupHead = setting(hardware, "Solleva testa su basetta");
        swStartupHead.setOnCheckedChangeListener((button, checked) -> {
            if (!binding) cb.onToggleStartupHead(checked);
        });
        swCriticalBattery = setting(hardware, "Spegni sotto 3,25 V");
        swCriticalBattery.setOnCheckedChangeListener((button, checked) -> {
            if (!binding) cb.onToggleCriticalBatteryShutdown(checked);
        });
        btnPowerOff = UiKit.actionButton(c, "Spegni Cozmo", UiKit.DANGER_BG, UiKit.DANGER);
        btnPowerOff.setOnClickListener(v -> new AlertDialog.Builder(c)
            .setTitle("Spegnere Cozmo?")
            .setMessage("Il robot si spegnerà e la connessione verrà chiusa.")
            .setPositiveButton("Spegni", (d, w) -> cb.onPowerOffRobot())
            .setNegativeButton("Annulla", null).show());
        hardware.addView(btnPowerOff);

        LinearLayout key = section(container, "Chiave Gemini Live");
        keyState = detail(key);
        geminiKey = secretInput("Inserisci una chiave API");
        key.addView(geminiKey);
        LinearLayout keyRow = buttonRow(key);
        Button btnSaveKey = UiKit.actionButton(c, "Salva", UiKit.ACCENT, UiKit.ACCENT_DARK);
        btnTestKey = neutralButton("Verifica chiave");
        addToRow(keyRow, btnSaveKey);
        addToRow(keyRow, btnTestKey);
        btnSaveKey.setOnClickListener(v -> {
            String value = geminiKey.getText().toString().trim();
            if (value.isEmpty()) {
                geminiKey.setError("Inserisci la chiave da salvare");
                return;
            }
            cb.onSaveGeminiKey(value);
            geminiKey.setText("");
        });
        btnTestKey.setOnClickListener(v -> cb.onTestGeminiKey());
        keyResult = detail(key);

        recoveryCard = section(container, "Ripristino firmware 2381");
        TextView recoveryInfo = detail(recoveryCard);
        recoveryInfo.setText("La procedura assistita richiede Cozmo sulla basetta e l’immagine firmware sul telefono.");
        recoveryStatus = detail(recoveryCard);
        recoveryProgress = UiKit.horizontalBar(c, 100);
        recoveryCard.addView(recoveryProgress);
        recoveryCard.setVisibility(View.GONE);

        LinearLayout app = section(container, "App & sistema");
        app.addView(UiKit.subtitle(c, "Cozmo è l'app Home di questo tablet: premendo il tasto Home si torna qui."));
        Button btnDock = neutralButton("Apri il dock (Halliday)");
        btnDock.setOnClickListener(v -> cb.onOpenLauncherApp("it.fabriziodp.hallidayai"));
        app.addView(UiKit.spacer(c, 0, 6));
        app.addView(btnDock);
        Button btnLauncher = neutralButton("Tutte le app (drawer)");
        // package vuoto => MainActivity usa l'intent ACTION_MAIN/CATEGORY_HOME, che apre
        // il launcher di sistema (QuickstepLauncher) anche se Cozmo ne è il default HOME.
        btnLauncher.setOnClickListener(v -> cb.onOpenLauncherApp(""));
        app.addView(UiKit.spacer(c, 0, 6));
        app.addView(btnLauncher);
        Button btnSettings = neutralButton("Impostazioni di Android");
        btnSettings.setOnClickListener(v -> cb.onOpenSystemSettings());
        app.addView(UiKit.spacer(c, 0, 6));
        app.addView(btnSettings);

        LinearLayout diagnostics = section(container, "Telemetria");
        diagStatus = detail(diagnostics);
        diagCliff = detail(diagnostics);
        diagPackets = detail(diagnostics);

        LinearLayout journal = section(container, "Registro eventi");
        LinearLayout filters = UiKit.row(c);
        journal.addView(filters);
        filterButtons = new Button[] {neutralButton("Tutti"), neutralButton("Movimenti"), neutralButton("Errori")};
        for (Filter filter : Filter.values()) {
            Button button = filterButtons[filter.ordinal()];
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setPadding(UiKit.dp(c, 4), UiKit.dp(c, 6), UiKit.dp(c, 4), UiKit.dp(c, 6));
            addToRow(filters, button);
            button.setOnClickListener(v -> selectFilter(filter));
        }
        logBox = detail(journal);
        logBox.setTypeface(Typeface.MONOSPACE);
        logBox.setTextIsSelectable(true);
        logBox.setTextSize(13);
        expandEvents = neutralButton("Mostra altri eventi");
        expandEvents.setOnClickListener(v -> {
            expanded = !expanded;
            updateLog();
        });
        journal.addView(expandEvents);
        selectFilter(Filter.ALL);
    }

    private LinearLayout section(LinearLayout parent, String title) {
        LinearLayout card = UiKit.card(c);
        card.addView(UiKit.sectionTitle(c, title));
        parent.addView(card);
        return card;
    }

    private TextView detail(LinearLayout card) {
        TextView value = UiKit.subtitle(c, "");
        value.setTextSize(14);
        value.setPadding(0, UiKit.dp(c, 4), 0, UiKit.dp(c, 8));
        card.addView(value);
        return value;
    }

    private EditText secretInput(String hint) {
        EditText field = UiKit.input(c, hint);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setSingleLine(true);
        field.setSaveEnabled(false);
        return field;
    }

    private Switch setting(LinearLayout card, String text) {
        Switch control = new Switch(c);
        control.setText(text);
        control.setTextSize(15);
        control.setTextColor(UiKit.TEXT_PRIMARY);
        control.setSwitchPadding(UiKit.dp(c, 12));
        control.setMinHeight(UiKit.dp(c, 48));
        card.addView(control);
        return control;
    }

    private Button neutralButton(String label) {
        return UiKit.actionButton(c, label, UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
    }

    private LinearLayout buttonRow(LinearLayout card) {
        card.addView(UiKit.spacer(c, 0, 8));
        LinearLayout row = UiKit.row(c);
        card.addView(row);
        return row;
    }

    private void addToRow(LinearLayout row, Button button) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (row.getChildCount() > 0) lp.setMarginStart(UiKit.dp(c, 6));
        row.addView(button, lp);
    }

    private void connect(boolean existing) {
        String name = ssid.getText().toString().trim();
        if (name.isEmpty()) {
            ssid.setError("Inserisci il nome della rete Cozmo");
            return;
        }
        if (existing) cb.onConnectExistingWifi(name);
        else cb.onConnectWifi(name, password.getText().toString());
    }

    private void selectFilter(Filter filter) {
        activeFilter = filter;
        expanded = false;
        for (Filter choice : Filter.values()) {
            Button button = filterButtons[choice.ordinal()];
            button.setSelected(choice == filter);
            UiKit.styleChip(c, button, choice == filter);
        }
        updateLog();
    }

    private boolean matches(String event) {
        if (activeFilter == Filter.ALL) return true;
        int sep = event.indexOf(':');
        String kind = sep < 0 ? event : event.substring(0, sep);
        if (activeFilter == Filter.MOTION) {
            return kind.equals("motion") || kind.equals("stop") || kind.equals("emergency_stop") || kind.equals("startup_head");
        }
        if (kind.equals("rejected") || kind.endsWith("_error") || kind.endsWith("_rejected")) return true;
        if (kind.equals("status") || kind.equals("firmware_recovery") || kind.equals("ai_key") || kind.equals("ai_test")) {
            String msg = event.toLowerCase(Locale.ROOT);
            return msg.contains("errore") || msg.contains("fallit") || msg.contains("scadut")
                || msg.contains("non disponibile") || msg.contains("perso") || msg.contains("sospeso")
                || msg.contains("non utilizzabile") || msg.contains("annullat");
        }
        return false;
    }

    private void updateLog() {
        StringBuilder lines = new StringBuilder();
        int count = 0;
        for (String event : events) {
            if (!matches(event)) continue;
            if (expanded || count < 6) {
                if (lines.length() > 0) lines.append("\n\n");
                lines.append(event);
            }
            count++;
        }
        logBox.setText(count > 0 ? lines.toString() : (activeFilter == Filter.ALL
            ? "Nessun evento registrato." : "Nessun evento in questa categoria."));
        expandEvents.setVisibility(count > 6 ? View.VISIBLE : View.GONE);
        expandEvents.setText(expanded ? "Mostra meno" : "Mostra altri eventi (" + Math.max(0, count - 6) + ")");
    }

    public View getView() {
        return root;
    }

    /** Bring the Wi-Fi connection section into view and focus the SSID field. */
    public void focusConnection() {
        root.post(() -> {
            root.smoothScrollTo(0, 0);
            ssid.requestFocus();
            ssid.setSelection(ssid.getText().length());
        });
    }

    public void render(UiState s) {
        binding = true;
        swStartupHead.setChecked(s.startupHeadOnCharger);
        swCriticalBattery.setChecked(s.criticalBatteryShutdown);
        binding = false;
        wifiStatus.setText(s.connectionLabel);
        UiKit.setEnabledAlpha(btnConnect, !s.connecting && !s.connected && !s.recoveryRunning);
        UiKit.setEnabledAlpha(btnExisting, !s.connecting && !s.connected && !s.recoveryRunning);
        UiKit.setEnabledAlpha(btnDisconnect, (s.connected || s.connecting) && !s.recoveryRunning);
        UiKit.setEnabledAlpha(btnPowerOff, s.connected && !s.recoveryRunning);
        keyState.setText(s.geminiKeyConfigured ? "Chiave salvata sul telefono." : "Nessuna chiave salvata.");
        UiKit.setEnabledAlpha(btnTestKey, s.geminiKeyConfigured);
        String ai = s.geminiStatus == null ? "" : s.geminiStatus;
        keyResult.setText(ai);
        keyResult.setVisibility(s.geminiKeyConfigured && !ai.isEmpty() ? View.VISIBLE : View.GONE);

        recoveryCard.setVisibility(s.factoryFirmware || s.recoveryRunning ? View.VISIBLE : View.GONE);
        recoveryProgress.setVisibility(s.recoveryRunning ? View.VISIBLE : View.GONE);
        int pct = Math.max(0, Math.min(100, s.recoveryProgressPct));
        recoveryProgress.setProgress(pct);
        recoveryStatus.setText(s.recoveryRunning ? "Trasferimento " + pct + "% · " + s.recoveryStatus : s.recoveryStatus);

        diagStatus.setText(s.connected ? "Stato " + s.statusHex + " · " + (s.onCharger ? "sulla basetta" : "fuori basetta")
            + (s.visualOnly ? " · modalità visiva" : "") + (s.factoryFirmware ? " · firmware di fabbrica" : "") : "In attesa della telemetria");
        diagCliff.setVisibility(s.connected ? View.VISIBLE : View.GONE);
        if (s.connected) {
            StringBuilder readings = new StringBuilder("Sensori di bordo  ");
            for (int i = 0; i < s.cliff.length; i++) {
                if (i > 0) readings.append(" / ");
                readings.append(s.cliff[i]);
            }
            diagCliff.setText(readings.toString());
        }
        diagPackets.setText("Pacchetti  ↑ " + s.txPackets + "   ↓ " + s.rxPackets + "   Invalidi " + s.invalidPackets);
        if (!events.equals(s.recentEvents)) {
            events = s.recentEvents == null ? Collections.emptyList() : s.recentEvents;
            updateLog();
        }
    }

    public void setWifiCredentials(String name, String savedPassword) {
        ssid.setText(name);
        password.setText(savedPassword);
    }
}
