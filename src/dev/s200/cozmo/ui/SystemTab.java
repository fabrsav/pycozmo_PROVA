package dev.s200.cozmo.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

public final class SystemTab {
    private final Context c;
    private final UiCallbacks cb;
    private final LinearLayout root;

    private final EditText ssid;
    private final EditText password;
    private final Button btnConnect;
    private final Button btnExisting;
    private final Button btnDisconnect;
    private final Switch swStartupHead;
    private final Switch swCriticalBattery;
    private final Button btnPowerOff;
    private final EditText geminiKey;
    private final Button btnSaveKey;
    private final Button btnTestKey;
    private final LinearLayout recoveryCard;
    private final TextView recoveryStatus;
    private final ProgressBar recoveryProgress;
    private final Button btnRecovery;
    private final TextView diagStatus;
    private final TextView diagCliff;
    private final TextView diagPackets;
    private final Button chipLogAll;
    private final Button chipLogMotion;
    private final Button chipLogError;
    private final TextView logBox;

    private boolean binding = false;

    public SystemTab(Context context, UiCallbacks callbacks) {
        this.c = context;
        this.cb = callbacks;

        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        LinearLayout container = new LinearLayout(c);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(c, 12);
        container.setPadding(pad, pad, pad, pad);
        container.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scroll.addView(container);

        // ---- Card Connessione Wi-Fi ----
        LinearLayout cardWifi = UiKit.card(c);
        cardWifi.addView(UiKit.sectionTitle(c, "Connessione Wi-Fi"));
        ssid = UiKit.input(c, "SSID rete Cozmo");
        password = UiKit.input(c, "Password visualizzata sul robot");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        cardWifi.addView(ssid);
        cardWifi.addView(password);
        LinearLayout wifiBtnRow = UiKit.row(c);
        btnConnect = UiKit.actionButton(c, "Collega", UiKit.ACCENT, Color.BLACK);
        btnExisting = UiKit.actionButton(c, "Usa Wi-Fi già collegato", UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
        btnDisconnect = UiKit.actionButton(c, "Scollega", UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
        LinearLayout.LayoutParams wb = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wb.setMargins(UiKit.dp(c, 2), 0, UiKit.dp(c, 2), 0);
        btnConnect.setLayoutParams(wb);
        btnExisting.setLayoutParams(wb);
        btnDisconnect.setLayoutParams(wb);
        btnConnect.setOnClickListener(v -> {
            String s = ssid.getText().toString().trim();
            String p = password.getText().toString();
            if (!s.isEmpty()) cb.onConnectWifi(s, p);
        });
        btnExisting.setOnClickListener(v -> cb.onConnectExistingWifi());
        btnDisconnect.setOnClickListener(v -> cb.onDisconnect());
        wifiBtnRow.addView(btnConnect);
        wifiBtnRow.addView(btnExisting);
        wifiBtnRow.addView(btnDisconnect);
        cardWifi.addView(wifiBtnRow);
        container.addView(cardWifi);

        // ---- Card Opzioni Hardware & Batteria ----
        LinearLayout cardHw = UiKit.card(c);
        cardHw.addView(UiKit.sectionTitle(c, "Opzioni Hardware & Batteria"));
        swStartupHead = new Switch(c);
        swStartupHead.setText("Solleva testa su basetta");
        swStartupHead.setTextColor(UiKit.TEXT_PRIMARY);
        swStartupHead.setOnCheckedChangeListener((b, on) -> {
            if (!binding) cb.onToggleStartupHead(on);
        });
        cardHw.addView(swStartupHead);
        swCriticalBattery = new Switch(c);
        swCriticalBattery.setText("Spegnimento batteria critica (< 3,25 V)");
        swCriticalBattery.setTextColor(UiKit.TEXT_PRIMARY);
        swCriticalBattery.setOnCheckedChangeListener((b, on) -> {
            if (!binding) cb.onToggleCriticalBatteryShutdown(on);
        });
        cardHw.addView(swCriticalBattery);
        btnPowerOff = UiKit.actionButton(c, "Spegni Cozmo", UiKit.DANGER, Color.WHITE);
        btnPowerOff.setOnClickListener(v -> new AlertDialog.Builder(c)
            .setTitle("Spegni Cozmo")
            .setMessage("Invia il comando di spegnimento pulito al robot?")
            .setPositiveButton("Spegni", (d, w) -> cb.onPowerOffRobot())
            .setNegativeButton("Annulla", null)
            .show());
        cardHw.addView(btnPowerOff);
        container.addView(cardHw);

        // ---- Card Chiave API Gemini ----
        LinearLayout cardKey = UiKit.card(c);
        cardKey.addView(UiKit.sectionTitle(c, "Chiave API Gemini"));
        geminiKey = UiKit.input(c, "Chiave API Gemini Live (custodita in Keystore)");
        geminiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        geminiKey.setSaveEnabled(false);
        cardKey.addView(geminiKey);
        LinearLayout keyBtnRow = UiKit.row(c);
        btnSaveKey = UiKit.actionButton(c, "Salva", UiKit.ACCENT, Color.BLACK);
        btnTestKey = UiKit.actionButton(c, "Testa", UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
        LinearLayout.LayoutParams kb = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        kb.setMargins(UiKit.dp(c, 2), 0, UiKit.dp(c, 2), 0);
        btnSaveKey.setLayoutParams(kb);
        btnTestKey.setLayoutParams(kb);
        btnSaveKey.setOnClickListener(v -> {
            String k = geminiKey.getText().toString().trim();
            if (!k.isEmpty() && !geminiKey.hasFocus()) {
                cb.onSaveGeminiKey(k);
                geminiKey.setText("");
            } else if (!k.isEmpty()) {
                cb.onSaveGeminiKey(k);
            }
        });
        btnTestKey.setOnClickListener(v -> cb.onTestGeminiKey());
        keyBtnRow.addView(btnSaveKey);
        keyBtnRow.addView(btnTestKey);
        cardKey.addView(keyBtnRow);
        container.addView(cardKey);

        // ---- Card Condizionale Ripristino Firmware 2381 ----
        recoveryCard = UiKit.card(c);
        recoveryCard.addView(UiKit.sectionTitle(c, "Ripristino Firmware 2381"));
        TextView recWarn = new TextView(c);
        recWarn.setText("Attenzione: questa operazione sostituisce il firmware di fabbrica con la versione 2381. Richiede Cozmo connesso e sulla basetta.");
        recWarn.setTextColor(UiKit.WARN);
        recWarn.setTextSize(12);
        recWarn.setBackground(UiKit.box(c, UiKit.WARN_BG, UiKit.WARN, 8, 1));
        recWarn.setPadding(UiKit.dp(c, 8), UiKit.dp(c, 6), UiKit.dp(c, 8), UiKit.dp(c, 6));
        recoveryCard.addView(recWarn);
        recoveryStatus = new TextView(c);
        recoveryStatus.setText("Stato: inattivo");
        recoveryStatus.setTextColor(UiKit.TEXT_SECONDARY);
        recoveryStatus.setTextSize(12);
        recoveryCard.addView(recoveryStatus);
        recoveryProgress = UiKit.horizontalBar(c, 100);
        recoveryCard.addView(recoveryProgress);
        btnRecovery = UiKit.actionButton(c, "Avvia ripristino 2381", UiKit.WARN, Color.BLACK);
        btnRecovery.setOnClickListener(v -> cb.onStartFirmwareRecovery());
        recoveryCard.addView(btnRecovery);
        recoveryCard.setVisibility(View.GONE);
        container.addView(recoveryCard);

        // ---- Card Diagnostica Grezza & Log Eventi ----
        LinearLayout cardDiag = UiKit.card(c);
        cardDiag.addView(UiKit.sectionTitle(c, "Diagnostica Grezza & Log Eventi"));
        diagStatus = new TextView(c);
        diagStatus.setText("status: 0x0000 · onCharger: no");
        diagStatus.setTextColor(UiKit.TEXT_SECONDARY);
        diagStatus.setTextSize(12);
        cardDiag.addView(diagStatus);
        diagCliff = new TextView(c);
        diagCliff.setText("cliff: [0, 0, 0, 0]");
        diagCliff.setTextColor(UiKit.TEXT_SECONDARY);
        diagCliff.setTextSize(12);
        cardDiag.addView(diagCliff);
        diagPackets = new TextView(c);
        diagPackets.setText("tx: 0 · rx: 0 · invalid: 0");
        diagPackets.setTextColor(UiKit.TEXT_SECONDARY);
        diagPackets.setTextSize(12);
        cardDiag.addView(diagPackets);
        LinearLayout logFilter = UiKit.row(c);
        chipLogAll = logChip("Tutti", true);
        chipLogMotion = logChip("Movimenti", false);
        chipLogError = logChip("Errori", false);
        logFilter.addView(chipLogAll);
        logFilter.addView(chipLogMotion);
        logFilter.addView(chipLogError);
        cardDiag.addView(logFilter);
        logBox = new TextView(c);
        logBox.setText("Nessun evento");
        logBox.setTextColor(UiKit.TEXT_PRIMARY);
        logBox.setTextSize(11);
        logBox.setTypeface(android.graphics.Typeface.MONOSPACE);
        logBox.setMovementMethod(new ScrollingMovementMethod());
        logBox.setBackground(UiKit.box(c, 0xFF050A0F, 0xFF1E3545, 8, 1));
        logBox.setPadding(UiKit.dp(c, 8), UiKit.dp(c, 8), UiKit.dp(c, 8), UiKit.dp(c, 8));
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(c, 120));
        logBox.setLayoutParams(logLp);
        cardDiag.addView(logBox);
        container.addView(cardDiag);

        this.root = container;
    }

    private Button logChip(String label, boolean active) {
        Button b = UiKit.actionButton(c, label, active ? UiKit.ACCENT : UiKit.BG_CHIP, active ? Color.BLACK : UiKit.TEXT_PRIMARY);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(UiKit.dp(c, 2), 0, UiKit.dp(c, 2), 0);
        b.setLayoutParams(lp);
        return b;
    }

    public View getView() {
        return root;
    }

    public void render(UiState s) {
        binding = true;
        ssid.setText(s.wifiSsid);
        swStartupHead.setChecked(s.startupHeadOnCharger);
        swCriticalBattery.setChecked(s.criticalBatteryShutdown);
        recoveryCard.setVisibility((s.factoryFirmware || s.recoveryRunning) ? View.VISIBLE : View.GONE);
        recoveryStatus.setText(s.recoveryStatus);
        recoveryProgress.setProgress(Math.max(0, Math.min(100, s.recoveryProgressPct)));

        diagStatus.setText(String.format("status: %s · onCharger: %s · visualOnly: %s · factory: %s",
            s.statusHex, s.onCharger, s.visualOnly, s.factoryFirmware));
        StringBuilder cliff = new StringBuilder("cliff: [");
        for (int i = 0; i < s.cliff.length; i++) {
            if (i > 0) cliff.append(", ");
            cliff.append(s.cliff[i]);
        }
        cliff.append("]");
        diagCliff.setText(cliff.toString());
        diagPackets.setText(String.format("tx: %d · rx: %d · invalid: %d", s.txPackets, s.rxPackets, s.invalidPackets));

        // Log events
        if (s.recentEvents != null && !s.recentEvents.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String ev : s.recentEvents) sb.append(ev).append("\n");
            logBox.setText(sb.toString());
        }
        binding = false;
    }

    public void setSsidPasswordFocus(boolean ssidFocused, boolean passwordFocused) {
        // Not needed: render never overwrites focused EditText
    }
}
