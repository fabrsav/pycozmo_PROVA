package dev.s200.cozmo.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MindTab {
    private final Context c;
    private final UiCallbacks cb;
    private final LinearLayout root;

    private final EditText meetInput;
    private final Button btnMeet;
    private final TextView peopleList;
    private final Button segLocal;
    private final Button segOnDemand;
    private final Button segAmbient;
    private final TextView geminiKeyPill;
    private final TextView geminiStatus;
    private final TextView geminiLastIntent;
    private final EditText aiInput;
    private final Button btnAiSend;

    private boolean binding = false;

    public MindTab(Context context, UiCallbacks callbacks) {
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

        // ---- Card Persone Conosciute ----
        LinearLayout cardPeople = UiKit.card(c);
        cardPeople.addView(UiKit.sectionTitle(c, "Persone Conosciute"));
        LinearLayout meetRow = UiKit.row(c);
        meetInput = UiKit.input(c, "Nome persona...");
        LinearLayout.LayoutParams meetLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        meetInput.setLayoutParams(meetLp);
        btnMeet = UiKit.actionButton(c, "Saluta / Registra", UiKit.ACCENT, Color.BLACK);
        meetRow.addView(meetInput);
        meetRow.addView(btnMeet);
        cardPeople.addView(meetRow);
        btnMeet.setOnClickListener(v -> {
            String n = meetInput.getText().toString().trim();
            if (!n.isEmpty() && !meetInput.hasFocus()) {
                cb.onGreetOrRegisterPerson(n);
                meetInput.setText("");
            } else if (!n.isEmpty()) {
                cb.onGreetOrRegisterPerson(n);
            }
        });
        peopleList = new TextView(c);
        peopleList.setText("Nessuna persona registrata");
        peopleList.setTextColor(UiKit.TEXT_MUTED);
        peopleList.setTextSize(13);
        peopleList.setPadding(0, UiKit.dp(c, 8), 0, 0);
        cardPeople.addView(peopleList);
        container.addView(cardPeople);

        // ---- Card Modalità AI ----
        LinearLayout cardAi = UiKit.card(c);
        cardAi.addView(UiKit.sectionTitle(c, "Modalità AI (Gemini Live)"));
        LinearLayout segRow = new LinearLayout(c);
        segRow.setOrientation(LinearLayout.HORIZONTAL);
        segLocal = segBtn("Solo locale");
        segOnDemand = segBtn("Su richiesta");
        segAmbient = segBtn("Ambiente 45 s");
        segRow.addView(segLocal);
        segRow.addView(segOnDemand);
        segRow.addView(segAmbient);
        cardAi.addView(segRow);
        segLocal.setOnClickListener(v -> setSeg(0));
        segOnDemand.setOnClickListener(v -> setSeg(1));
        segAmbient.setOnClickListener(v -> setSeg(2));
        geminiKeyPill = UiKit.pill(c, "Chiave API: non configurata", UiKit.BG_CHIP, UiKit.TEXT_SECONDARY);
        geminiKeyPill.setPadding(UiKit.dp(c, 8), UiKit.dp(c, 3), UiKit.dp(c, 8), UiKit.dp(c, 3));
        cardAi.addView(geminiKeyPill);
        container.addView(cardAi);

        // ---- Card Stato & Dialogo AI ----
        LinearLayout cardState = UiKit.card(c);
        cardState.addView(UiKit.sectionTitle(c, "Stato & Dialogo AI"));
        geminiStatus = new TextView(c);
        geminiStatus.setText("Stato: Inattivo");
        geminiStatus.setTextColor(UiKit.TEXT_PRIMARY);
        geminiStatus.setTextSize(14);
        cardState.addView(geminiStatus);
        geminiLastIntent = new TextView(c);
        geminiLastIntent.setText("Ultimo intento: —");
        geminiLastIntent.setTextColor(UiKit.TEXT_SECONDARY);
        geminiLastIntent.setTextSize(12);
        geminiLastIntent.setPadding(0, UiKit.dp(c, 4), 0, 0);
        cardState.addView(geminiLastIntent);
        LinearLayout aiRow = UiKit.row(c);
        aiInput = UiKit.input(c, "Chiedi qualcosa a Cozmo...");
        LinearLayout.LayoutParams aiLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        aiInput.setLayoutParams(aiLp);
        btnAiSend = UiKit.actionButton(c, "Invia", UiKit.ACCENT, Color.BLACK);
        aiRow.addView(aiInput);
        aiRow.addView(btnAiSend);
        cardState.addView(aiRow);
        btnAiSend.setOnClickListener(v -> {
            String p = aiInput.getText().toString().trim();
            if (!p.isEmpty() && !aiInput.hasFocus()) {
                cb.onSendGeminiPrompt(p);
                aiInput.setText("");
            } else if (!p.isEmpty()) {
                cb.onSendGeminiPrompt(p);
            }
        });
        container.addView(cardState);

        this.root = container;
    }

    private Button segBtn(String label) {
        Button b = UiKit.actionButton(c, label, UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(UiKit.dp(c, 2), 0, UiKit.dp(c, 2), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void setSeg(int idx) {
        binding = true;
        String mode;
        switch (idx) {
            case 0: mode = "OFFLINE_ONLY"; break;
            case 2: mode = "AMBIENT_HYBRID"; break;
            default: mode = "ON_DEMAND"; break;
        }
        cb.onSetGeminiMode(mode);
        binding = false;
    }

    public View getView() {
        return root;
    }

    public void render(UiState s) {
        binding = true;
        if (s.knownPeople != null && !s.knownPeople.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.knownPeople.size(); i++) {
                if (i > 0) sb.append(" · ");
                sb.append(s.knownPeople.get(i));
            }
            peopleList.setText(sb.toString());
        } else {
            peopleList.setText("Nessuna persona registrata");
        }
        if (s.geminiMode != null) {
            int idx;
            switch (s.geminiMode) {
                case "OFFLINE_ONLY": idx = 0; break;
                case "AMBIENT_HYBRID": idx = 2; break;
                default: idx = 1; break;
            }
            Button[] segs = {segLocal, segOnDemand, segAmbient};
            for (int i = 0; i < 3; i++) UiKit.styleChip(c, segs[i], i == idx);
        }
        if (s.geminiKeyConfigured) {
            geminiKeyPill.setText("Chiave API: Configurata");
            UiKit.stylePill(c, geminiKeyPill, UiKit.OK_BG, UiKit.OK);
        } else {
            geminiKeyPill.setText("Chiave API: Mancante (vai in Sistema)");
            UiKit.stylePill(c, geminiKeyPill, UiKit.BG_CHIP, UiKit.TEXT_SECONDARY);
        }
        geminiStatus.setText("Stato: " + s.geminiStatus);
        geminiLastIntent.setText("Ultimo intento: " + (s.geminiLastIntent.isEmpty() ? "—" : s.geminiLastIntent));
        binding = false;
    }
}
