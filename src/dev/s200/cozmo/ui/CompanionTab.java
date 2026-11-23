package dev.s200.cozmo.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

public final class CompanionTab {
    private final Context c;
    private final UiCallbacks cb;
    private final LinearLayout root;

    private final ImageView faceView;
    private final TextView behaviorPill;
    private final TextView moodPill;
    private final ProgressBar energyBar;
    private TextView energyLabel;
    private final ProgressBar playBar;
    private TextView playLabel;
    private final ProgressBar comfortBar;
    private TextView comfortLabel;
    private final Button btnPet;
    private final Button btnPlay;
    private final Button[] profileChips = new Button[4];
    private final LinearLayout exprStrip;
    private final LinearLayout choreoStrip;
    private final EditText sayInput;
    private final Button btnSay;
    private final Button[] vocalChips = new Button[3];
    private final View[] ledDots = new View[6];
    private final Button btnHeadlight;
    private final Switch masterSwitch;
    private final Button chipVoice;
    private final Button chipGestures;
    private final Button chipWheels;
    private final TextView noteWheels;

    private boolean binding = false;

    public CompanionTab(Context context, UiCallbacks callbacks) {
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

        // ---- Card 1: OLED & Stato ----
        LinearLayout card1 = UiKit.card(c);
        card1.addView(UiKit.sectionTitle(c, "OLED & Stato"));
        faceView = new ImageView(c);
        faceView.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams faceLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(c, 110));
        faceLp.topMargin = UiKit.dp(c, 4);
        faceLp.bottomMargin = UiKit.dp(c, 6);
        faceView.setLayoutParams(faceLp);
        faceView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        card1.addView(faceView);
        LinearLayout pills = UiKit.row(c);
        behaviorPill = UiKit.pill(c, "In attesa", UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
        moodPill = UiKit.pill(c, "Neutro", UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
        pills.addView(behaviorPill);
        pills.addView(UiKit.spacer(c, 8, 0));
        pills.addView(moodPill);
        card1.addView(pills);
        container.addView(card1);

        // ---- Card 2: Bisogni & Interazione ----
        LinearLayout card2 = UiKit.card(c);
        card2.addView(UiKit.sectionTitle(c, "Bisogni & Interazione"));
        energyBar = needBar(card2, "Energia", 0);
        energyLabel = null;
        playBar = needBar(card2, "Gioco", 0);
        comfortBar = needBar(card2, "Comfort", 0);
        // energyLabel/playLabel/comfortLabel are the third child (TextView) of each needBar row
        energyLabel = (TextView) ((LinearLayout) card2.getChildAt(1)).getChildAt(2);
        playLabel = (TextView) ((LinearLayout) card2.getChildAt(2)).getChildAt(2);
        comfortLabel = (TextView) ((LinearLayout) card2.getChildAt(3)).getChildAt(2);

        LinearLayout btnRow2 = UiKit.row(c);
        btnPet = UiKit.actionButton(c, "Coccole", UiKit.ACCENT, UiKit.ACCENT_DARK);
        btnPlay = UiKit.actionButton(c, "Gioca", UiKit.ACCENT, UiKit.ACCENT_DARK);
        LinearLayout.LayoutParams w1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnPet.setLayoutParams(w1);
        btnPlay.setLayoutParams(w1);
        btnPet.setOnClickListener(v -> cb.onPet());
        btnPlay.setOnClickListener(v -> cb.onPlay());
        btnRow2.addView(btnPet);
        btnRow2.addView(btnPlay);
        card2.addView(btnRow2);
        container.addView(card2);

        // ---- Card 3: Carattere ----
        LinearLayout card3 = UiKit.card(c);
        card3.addView(UiKit.sectionTitle(c, "Carattere"));
        LinearLayout profRow = UiKit.row(c);
        String[] profIds = {"CURIOSO", "AFFETTUOSO", "BIRICHINO", "TRANQUILLO"};
        String[] profLabels = {"Curioso", "Affettuoso", "Birichino", "Tranquillo"};
        for (int i = 0; i < 4; i++) {
            final String id = profIds[i];
            Button b = UiKit.actionButton(c, profLabels[i], UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(UiKit.dp(c, 2), 0, UiKit.dp(c, 2), 0);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> cb.onSelectPersonality(id));
            profileChips[i] = b;
            profRow.addView(b);
        }
        card3.addView(profRow);
        container.addView(card3);

        // ---- Card 4: Espressioni & Coreografie ----
        LinearLayout card4 = UiKit.card(c);
        card4.addView(UiKit.sectionTitle(c, "Espressioni & Coreografie"));
        card4.addView(UiKit.subtitle(c, "Espressioni"));
        exprStrip = new LinearLayout(c);
        exprStrip.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView exprScroll = new HorizontalScrollView(c);
        exprScroll.setFillViewport(true);
        exprScroll.addView(exprStrip);
        card4.addView(exprScroll);
        String[] exprs = {"NEUTRAL", "HAPPY", "EXCITED", "CURIOUS", "SURPRISED", "SKEPTICAL", "SAD", "SLEEPY", "FOCUSED"};
        String[] exprLabels = {"Neutro", "Felice", "Entusiasta", "Curioso", "Sorpreso", "Scettico", "Triste", "Assonnato", "Concentrato"};
        for (int i = 0; i < exprs.length; i++) {
            final String id = exprs[i];
            Button b = UiKit.actionButton(c, exprLabels[i], UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(UiKit.dp(c, 2), 0, UiKit.dp(c, 2), 0);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> cb.onTriggerExpression(id));
            exprStrip.addView(b);
        }
        card4.addView(UiKit.subtitle(c, "Coreografie"));
        choreoStrip = new LinearLayout(c);
        choreoStrip.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView choreoScroll = new HorizontalScrollView(c);
        choreoScroll.setFillViewport(true);
        choreoScroll.addView(choreoStrip);
        card4.addView(choreoScroll);
        String[] clips = {"GREET_FAMILIAR", "CURIOUS_INSPECT", "PLAY_CHEER", "HAPPY_COMFORT", "CUBE_VICTORY", "SURPRISED_PICKUP", "BORED_SIGH"};
        String[] clipLabels = {"Saluto", "Curioso", "Esulta", "Comfort", "Vittoria", "Sorpreso", "Sospiro"};
        for (int i = 0; i < clips.length; i++) {
            final String id = clips[i];
            Button b = UiKit.actionButton(c, clipLabels[i], UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(UiKit.dp(c, 2), 0, UiKit.dp(c, 2), 0);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> cb.onTriggerChoreography(id));
            choreoStrip.addView(b);
        }
        container.addView(card4);

        // ---- Card 5: Voce & Luci ----
        LinearLayout card5 = UiKit.card(c);
        card5.addView(UiKit.sectionTitle(c, "Voce & Luci"));
        LinearLayout sayRow = UiKit.row(c);
        sayInput = UiKit.input(c, "Fai parlare Cozmo...");
        LinearLayout.LayoutParams sayLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sayInput.setLayoutParams(sayLp);
        btnSay = UiKit.actionButton(c, "Parla", 0xFF4DE4F0, Color.BLACK);
        btnSay.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        btnSay.setOnClickListener(v -> {
            String t = sayInput.getText().toString().trim();
            if (!t.isEmpty()) {
                cb.onSpeak(t);
                if (!sayInput.hasFocus()) sayInput.setText("");
            }
        });
        sayRow.addView(sayInput);
        sayRow.addView(btnSay);
        card5.addView(sayRow);
        card5.addView(UiKit.subtitle(c, "Vocalizzi"));
        LinearLayout vocalRow = UiKit.row(c);
        String[] vocs = {"curious", "happy", "sing"};
        String[] vocLabels = {"Voce Curioso", "Voce Felice", "Canta"};
        for (int i = 0; i < 3; i++) {
            final String id = vocs[i];
            Button b = UiKit.actionButton(c, vocLabels[i], UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(UiKit.dp(c, 2), 0, UiKit.dp(c, 2), 0);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> cb.onPlayVocalization(id));
            vocalChips[i] = b;
            vocalRow.addView(b);
        }
        card5.addView(vocalRow);
        card5.addView(UiKit.subtitle(c, "LED zaino"));
        LinearLayout ledRow = UiKit.row(c);
        int[] ledColors = {0xFF00FFFF, 0xFF00FF00, 0xFFFFBF00, 0xFFBF00FF, 0xFFFF0000, 0xFF000000};
        String[] ledLabels = {"Ciano", "Verde", "Ambra", "Viola", "Rosso", "Spento"};
        for (int i = 0; i < 6; i++) {
            final int color = ledColors[i];
            final String label = ledLabels[i];
            Button b = UiKit.actionButton(c, label, color, color == 0xFF000000 ? Color.WHITE : Color.BLACK);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, UiKit.dp(c, 34), 1f);
            lp.setMargins(UiKit.dp(c, 2), 0, UiKit.dp(c, 2), 0);
            b.setLayoutParams(lp);
            b.setTextSize(10);
            final int col = color;
            b.setOnClickListener(v -> cb.onSetBackpackColor(col));
            ledDots[i] = b;
            ledRow.addView(b);
        }
        card5.addView(ledRow);
        btnHeadlight = UiKit.actionButton(c, "Faro OFF", UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
        btnHeadlight.setOnClickListener(v -> cb.onToggleHeadlight(false));
        card5.addView(btnHeadlight);
        container.addView(card5);

        // ---- Card 6: Vita Autonoma ----
        LinearLayout card6 = UiKit.card(c);
        card6.addView(UiKit.sectionTitle(c, "Vita Autonoma"));
        LinearLayout masterRow = UiKit.row(c);
        masterSwitch = new Switch(c);
        masterSwitch.setText("Master autonomo");
        masterSwitch.setTextColor(UiKit.TEXT_PRIMARY);
        masterSwitch.setTextSize(14);
        masterRow.addView(masterSwitch);
        masterSwitch.setOnCheckedChangeListener((b, on) -> {
            if (!binding) cb.onToggleAutonomousMaster(on);
        });
        card6.addView(masterRow);
        LinearLayout chipsRow = UiKit.row(c);
        chipVoice = UiKit.actionButton(c, "Voce", UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
        chipGestures = UiKit.actionButton(c, "Gesti", UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
        chipWheels = UiKit.actionButton(c, "Cingoli", UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
        LinearLayout.LayoutParams cw = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        chipVoice.setLayoutParams(cw);
        chipGestures.setLayoutParams(cw);
        chipWheels.setLayoutParams(cw);
        chipVoice.setOnClickListener(v -> cb.onToggleAutonomousVoice(!chipVoiceIsActive()));
        chipGestures.setOnClickListener(v -> cb.onToggleAutonomousMotion(!chipGesturesIsActive()));
        chipWheels.setOnClickListener(v -> cb.onToggleAutonomousWheels(!chipWheelsIsActive()));
        chipsRow.addView(chipVoice);
        chipsRow.addView(chipGestures);
        chipsRow.addView(chipWheels);
        card6.addView(chipsRow);
        noteWheels = UiKit.subtitle(c, "");
        card6.addView(noteWheels);
        container.addView(card6);

        this.root = container;
    }

    public View getView() {
        return root;
    }

    public void render(UiState s) {
        binding = true;
        if (s.faceBitmap != null) faceView.setImageBitmap(s.faceBitmap);
        behaviorPill.setText(s.activeBehavior);
        moodPill.setText(s.activeMood);
        if (energyBar != null) energyBar.setProgress(Math.max(0, Math.min(100, s.energyPct)));
        if (energyLabel != null) energyLabel.setText(String.format("%d%%", s.energyPct));
        if (playBar != null) playBar.setProgress(Math.max(0, Math.min(100, s.playPct)));
        if (playLabel != null) playLabel.setText(String.format("%d%%", s.playPct));
        if (comfortBar != null) comfortBar.setProgress(Math.max(0, Math.min(100, s.comfortPct)));
        if (comfortLabel != null) comfortLabel.setText(String.format("%d%%", s.comfortPct));

        String[] profIds = {"CURIOSO", "AFFETTUOSO", "BIRICHINO", "TRANQUILLO"};
        for (int i = 0; i < 4; i++) {
            UiKit.styleChip(c, profileChips[i], profIds[i].equals(s.personalityId));
        }

        boolean wheelsEnabled = s.connected && !s.onCharger && !s.visualOnly && !s.factoryFirmware;
        UiKit.styleChip(c, chipVoice, s.autonomousVoice);
        UiKit.styleChip(c, chipGestures, s.autonomousMotion);
        UiKit.styleChip(c, chipWheels, s.autonomousWheels);
        UiKit.setEnabledAlpha(chipWheels, wheelsEnabled);
        noteWheels.setText(wheelsEnabled ? "" : "Cingoli bloccati: " + (s.onCharger ? "sulla basetta" : "motori non abilitati"));
        noteWheels.setVisibility(wheelsEnabled || s.onCharger ? View.VISIBLE : View.GONE);
        masterSwitch.setChecked(s.autonomousMaster);
        btnHeadlight.setText(s.headlightOn ? "Faro ON" : "Faro OFF");
        UiKit.styleChip(c, btnHeadlight, s.headlightOn);
        binding = false;
    }

    private boolean chipVoiceIsActive() {
        return chipVoice.getCurrentTextColor() == UiKit.ACCENT_DARK;
    }
    private boolean chipGesturesIsActive() {
        return chipGestures.getCurrentTextColor() == UiKit.ACCENT_DARK;
    }
    private boolean chipWheelsIsActive() {
        return chipWheels.getCurrentTextColor() == UiKit.ACCENT_DARK;
    }

    private ProgressBar needBar(LinearLayout parent, String label, int pct) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView lbl = new TextView(c);
        lbl.setText(label);
        lbl.setTextColor(UiKit.TEXT_SECONDARY);
        lbl.setTextSize(13);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(UiKit.dp(c, 56), ViewGroup.LayoutParams.WRAP_CONTENT);
        lbl.setLayoutParams(ll);
        ProgressBar pb = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgress(pct);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, UiKit.dp(c, 6), 1f);
        pl.setMargins(UiKit.dp(c, 8), 0, UiKit.dp(c, 8), 0);
        pb.setLayoutParams(pl);
        TextView val = new TextView(c);
        val.setText(String.format("%d%%", pct));
        val.setTextColor(UiKit.TEXT_PRIMARY);
        val.setTextSize(12);
        val.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(lbl);
        row.addView(pb);
        row.addView(val);
        parent.addView(row);
        return pb;
    }
}
