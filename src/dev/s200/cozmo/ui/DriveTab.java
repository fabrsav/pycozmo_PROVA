package dev.s200.cozmo.ui;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

public final class DriveTab {
    private final Context c;
    private final UiCallbacks cb;
    private final LinearLayout root;

    private final ImageView cameraView;
    private final TextView pillFace;
    private final TextView pillMotion;
    private final Button btnCamStart;
    private final Button btnCamStop;
    private final Button btnSnap;
    private final TextView banner;
    private final Button btnArm;
    private final ProgressBar armBar;
    private final TextView headText;
    private final Button btnHeadUp;
    private final Button btnHeadDown;
    private final TextView liftText;
    private final Button btnLiftUp;
    private final Button btnLiftDown;
    private final Button btnDpadUp;
    private final Button btnDpadDown;
    private final Button btnDpadLeft;
    private final Button btnDpadRight;
    private final Button btnDpadStop;
    private LinearLayout cubesBody;
    private final TextView cubesSummary;
    private final Button btnCubesScan;
    private final Button btnCubesQuickTap;

    private final Handler dpadHandler = new Handler();
    private Runnable dpadTask;
    private float dpadL, dpadR;

    public DriveTab(Context context, UiCallbacks callbacks) {
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

        // ---- Viewport Camera Live ----
        FrameLayout camViewport = new FrameLayout(c);
        camViewport.setBackground(UiKit.box(c, 0xFF050A0F, 0xFF1E3545, 16, 1));
        LinearLayout.LayoutParams camLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(c, 180));
        camViewport.setLayoutParams(camLp);
        cameraView = new ImageView(c);
        cameraView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cameraView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        camViewport.addView(cameraView);
        pillFace = UiKit.pill(c, "Volto", UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
        pillFace.setPadding(UiKit.dp(c, 6), UiKit.dp(c, 2), UiKit.dp(c, 6), UiKit.dp(c, 2));
        FrameLayout.LayoutParams pf = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pf.setMargins(UiKit.dp(c, 8), UiKit.dp(c, 8), 0, 0);
        pf.gravity = Gravity.TOP | Gravity.LEFT;
        camViewport.addView(pillFace, pf);
        pillMotion = UiKit.pill(c, "Movimento", UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
        pillMotion.setPadding(UiKit.dp(c, 6), UiKit.dp(c, 2), UiKit.dp(c, 6), UiKit.dp(c, 2));
        FrameLayout.LayoutParams pm = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pm.setMargins(UiKit.dp(c, 8), UiKit.dp(c, 8), 0, 0);
        pm.gravity = Gravity.TOP | Gravity.LEFT;
        camViewport.addView(pillMotion, pm);
        LinearLayout camButtons = new LinearLayout(c);
        camButtons.setOrientation(LinearLayout.HORIZONTAL);
        camButtons.setGravity(Gravity.RIGHT | Gravity.BOTTOM);
        FrameLayout.LayoutParams cbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cbLp.setMargins(0, 0, UiKit.dp(c, 8), UiKit.dp(c, 8));
        cbLp.gravity = Gravity.RIGHT | Gravity.BOTTOM;
        btnCamStart = UiKit.actionButton(c, "Avvia", UiKit.ACCENT, Color.BLACK);
        btnCamStop = UiKit.actionButton(c, "Ferma", UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
        btnSnap = UiKit.actionButton(c, "Scatta", 0xFF4DE4F0, Color.BLACK);
        btnCamStart.setOnClickListener(v -> cb.onToggleCamera());
        btnCamStop.setOnClickListener(v -> cb.onToggleCamera());
        btnSnap.setOnClickListener(v -> cb.onSnapPhoto());
        camButtons.addView(btnCamStart);
        camButtons.addView(btnCamStop);
        camButtons.addView(btnSnap);
        camViewport.addView(camButtons, cbLp);
        container.addView(camViewport);

        // ---- Card Stato & Abilitazione Motori ----
        LinearLayout cardState = UiKit.card(c);
        cardState.addView(UiKit.sectionTitle(c, "Stato & Abilitazione Motori"));
        banner = UiKit.banner(c);
        cardState.addView(banner);
        LinearLayout armRow = UiKit.row(c);
        btnArm = UiKit.actionButton(c, "Abilita motori · 60 s", UiKit.WARN, Color.BLACK);
        LinearLayout.LayoutParams armBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnArm.setLayoutParams(armBtnLp);
        btnArm.setOnClickListener(v -> cb.onArmMotors());
        armBar = UiKit.horizontalBar(c, 60);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        barLp.setMargins(UiKit.dp(c, 8), 0, 0, 0);
        armBar.setLayoutParams(barLp);
        armRow.addView(btnArm);
        armRow.addView(armBar);
        cardState.addView(armRow);
        container.addView(cardState);

        // ---- Card Comandi Manuali ----
        LinearLayout cardCmd = UiKit.card(c);
        cardCmd.addView(UiKit.sectionTitle(c, "Comandi Manuali"));
        LinearLayout twoCols = new LinearLayout(c);
        twoCols.setOrientation(LinearLayout.HORIZONTAL);

        // Colonna sinistra: Pad Direzionale
        LinearLayout leftCol = new LinearLayout(c);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftCol.addView(UiKit.subtitle(c, "Cingoli"));
        LinearLayout dpad = new LinearLayout(c);
        dpad.setOrientation(LinearLayout.VERTICAL);
        dpad.setGravity(Gravity.CENTER);
        // Row 1: up
        LinearLayout r1 = UiKit.row(c);
        r1.setGravity(Gravity.CENTER);
        r1.addView(UiKit.spacer(c, 44, 0));
        btnDpadUp = dpBtn("↑");
        r1.addView(btnDpadUp);
        r1.addView(UiKit.spacer(c, 44, 0));
        dpad.addView(r1);
        // Row 2: left, stop, right
        LinearLayout r2 = UiKit.row(c);
        r2.setGravity(Gravity.CENTER);
        btnDpadLeft = dpBtn("←");
        btnDpadStop = dpBtn("■");
        btnDpadRight = dpBtn("→");
        r2.addView(btnDpadLeft);
        r2.addView(btnDpadStop);
        r2.addView(btnDpadRight);
        dpad.addView(r2);
        // Row 3: down
        LinearLayout r3 = UiKit.row(c);
        r3.setGravity(Gravity.CENTER);
        r3.addView(UiKit.spacer(c, 44, 0));
        btnDpadDown = dpBtn("↓");
        r3.addView(btnDpadDown);
        r3.addView(UiKit.spacer(c, 44, 0));
        dpad.addView(r3);
        leftCol.addView(dpad);
        twoCols.addView(leftCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Colonna destra: Testa & Carrello
        LinearLayout rightCol = new LinearLayout(c);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setGravity(Gravity.CENTER_HORIZONTAL);
        rightCol.addView(UiKit.subtitle(c, "Testa & Carrello"));
        headText = new TextView(c);
        headText.setText("Testa: 0°");
        headText.setTextColor(UiKit.TEXT_PRIMARY);
        headText.setTextSize(14);
        rightCol.addView(headText);
        LinearLayout headRow = UiKit.row(c);
        headRow.setGravity(Gravity.CENTER);
        btnHeadUp = UiKit.actionButton(c, "Su", UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
        btnHeadDown = UiKit.actionButton(c, "Giù", UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
        btnHeadUp.setOnClickListener(v -> cb.onMoveHead(0.1f));
        btnHeadDown.setOnClickListener(v -> cb.onMoveHead(-0.1f));
        headRow.addView(btnHeadUp);
        headRow.addView(btnHeadDown);
        rightCol.addView(headRow);
        liftText = new TextView(c);
        liftText.setText("Carrello: 0 mm");
        liftText.setTextColor(UiKit.TEXT_PRIMARY);
        liftText.setTextSize(14);
        liftText.setPadding(0, UiKit.dp(c, 10), 0, 0);
        rightCol.addView(liftText);
        LinearLayout liftRow = UiKit.row(c);
        liftRow.setGravity(Gravity.CENTER);
        btnLiftUp = UiKit.actionButton(c, "Alza", UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
        btnLiftDown = UiKit.actionButton(c, "Abbassa", UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
        btnLiftUp.setOnClickListener(v -> cb.onMoveLift(5f));
        btnLiftDown.setOnClickListener(v -> cb.onMoveLift(-5f));
        liftRow.addView(btnLiftUp);
        liftRow.addView(btnLiftDown);
        rightCol.addView(liftRow);
        twoCols.addView(rightCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        cardCmd.addView(twoCols);
        container.addView(cardCmd);

        // ---- Card Cubi BLE & Quick Tap (pieghevole) ----
        LinearLayout cardCubes = UiKit.card(c);
        cardCubes.addView(UiKit.sectionTitle(c, "Cubi BLE & Quick Tap"));
        LinearLayout cubesHeader = UiKit.row(c);
        cubesHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView cubesHeaderTxt = new TextView(c);
        cubesHeaderTxt.setText("Cubi BLE ▾");
        cubesHeaderTxt.setTextColor(UiKit.TEXT_PRIMARY);
        cubesHeaderTxt.setTextSize(14);
        cubesHeaderTxt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        cubesSummary = new TextView(c);
        cubesSummary.setText("Nessun cubo collegato");
        cubesSummary.setTextColor(UiKit.TEXT_MUTED);
        cubesSummary.setTextSize(12);
        cubesHeader.addView(cubesHeaderTxt);
        cubesHeader.addView(UiKit.spacer(c, 0, 0));
        cubesHeader.setPadding(0, 0, 0, UiKit.dp(c, 4));
        cubesHeader.setOnClickListener(v -> {
            boolean expanded = cubesBody.getVisibility() == View.VISIBLE;
            cubesBody.setVisibility(expanded ? View.GONE : View.VISIBLE);
            cubesHeaderTxt.setText(expanded ? "Cubi BLE ▾" : "Cubi BLE ▲");
        });
        cardCubes.addView(cubesHeader);
        cubesBody = new LinearLayout(c);
        cubesBody.setOrientation(LinearLayout.VERTICAL);
        cubesBody.setVisibility(View.GONE);
        btnCubesScan = UiKit.actionButton(c, "Scansione cubi", UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
        btnCubesQuickTap = UiKit.actionButton(c, "Quick Tap", UiKit.ACCENT, Color.BLACK);
        btnCubesScan.setOnClickListener(v -> cb.onCubesScanToggle());
        btnCubesQuickTap.setOnClickListener(v -> cb.onCubesQuickTap());
        LinearLayout cubesBtnRow = UiKit.row(c);
        LinearLayout.LayoutParams cbw = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnCubesScan.setLayoutParams(cbw);
        btnCubesQuickTap.setLayoutParams(cbw);
        cubesBtnRow.addView(btnCubesScan);
        cubesBtnRow.addView(btnCubesQuickTap);
        cubesBody.addView(cubesBtnRow);
        cardCubes.addView(cubesBody);
        container.addView(cardCubes);

        this.root = container;
    }

    private Button dpBtn(String label) {
        Button b = UiKit.actionButton(c, label, UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(UiKit.dp(c, 44), UiKit.dp(c, 44));
        b.setLayoutParams(lp);
        b.setTextSize(18);
        return b;
    }

    private void startDpad(float l, float r) {
        dpadL = l;
        dpadR = r;
        cb.onDrive(l, r);
        dpadTask = new Runnable() {
            public void run() {
                cb.onDrive(l, r);
                dpadHandler.postDelayed(this, 150);
            }
        };
        dpadHandler.postDelayed(dpadTask, 150);
    }

    private void stopDpad() {
        if (dpadTask != null) {
            dpadHandler.removeCallbacks(dpadTask);
            dpadTask = null;
        }
        cb.onStopMotors();
    }

    public View getView() {
        return root;
    }

    public void render(UiState s) {
        // Camera
        if (s.cameraBitmap != null) cameraView.setImageBitmap(s.cameraBitmap);
        pillFace.setText(s.faceDetected ? "Volto" : "Volto —");
        pillMotion.setText(s.motionDetected ? "Movimento" : "Movimento —");
        btnCamStart.setEnabled(s.connected && !s.visualOnly && !s.factoryFirmware);
        btnCamStop.setEnabled(s.connected && !s.visualOnly && !s.factoryFirmware);
        btnSnap.setEnabled(s.connected && !s.visualOnly && !s.factoryFirmware);

        // Stato motori
        boolean wheelBlocked = !s.wheelBlockReason.isEmpty();
        boolean jointBlocked = !s.jointBlockReason.isEmpty();
        if (wheelBlocked) {
            banner.setText(s.wheelBlockReason);
            UiKit.stylePill(c, banner, s.visualOnly || s.factoryFirmware ? UiKit.DANGER_BG : UiKit.WARN_BG,
                    s.visualOnly || s.factoryFirmware ? UiKit.DANGER : UiKit.WARN);
            banner.setVisibility(View.VISIBLE);
        } else if (jointBlocked) {
            banner.setText(s.jointBlockReason);
            UiKit.stylePill(c, banner, UiKit.WARN_BG, UiKit.WARN);
            banner.setVisibility(View.VISIBLE);
        } else {
            banner.setText("Motori pronti");
            UiKit.stylePill(c, banner, UiKit.OK_BG, UiKit.OK);
            banner.setVisibility(View.VISIBLE);
        }

        boolean armEnable = s.connected && !s.visualOnly && !s.factoryFirmware;
        btnArm.setEnabled(armEnable);
        if (s.armed) {
            btnArm.setText(String.format("Motori abilitati · %d s", s.armRemainingSec));
            UiKit.styleChip(c, btnArm, true);
        } else {
            btnArm.setText("Abilita motori · 60 s");
            UiKit.styleChip(c, btnArm, false);
        }
        armBar.setProgress(s.armRemainingSec);
        armBar.setEnabled(armEnable);

        // D-pad
        boolean dpEnabled = s.connected && !wheelBlocked && !s.visualOnly && !s.factoryFirmware;
        UiKit.setEnabledAlpha(btnDpadUp, dpEnabled);
        UiKit.setEnabledAlpha(btnDpadDown, dpEnabled);
        UiKit.setEnabledAlpha(btnDpadLeft, dpEnabled);
        UiKit.setEnabledAlpha(btnDpadRight, dpEnabled);
        UiKit.setEnabledAlpha(btnDpadStop, dpEnabled);

        // Testa & Carrello
        headText.setText(String.format("Testa: %.1f°", s.headDeg));
        liftText.setText(String.format("Carrello: %.0f mm", s.liftMm));
        boolean jointEnabled = s.connected && !jointBlocked && !s.visualOnly && !s.factoryFirmware;
        UiKit.setEnabledAlpha(btnHeadUp, jointEnabled);
        UiKit.setEnabledAlpha(btnHeadDown, jointEnabled);
        UiKit.setEnabledAlpha(btnLiftUp, jointEnabled);
        UiKit.setEnabledAlpha(btnLiftDown, jointEnabled);

        // Cubi
        cubesSummary.setText(s.cubesSummary);
        btnCubesScan.setEnabled(s.connected);
        btnCubesQuickTap.setEnabled(s.connected && s.cubesConnected);
    }

    /* ------------------------------------------------------------------ */
    /* D-pad touch listeners                                              */
    /* ------------------------------------------------------------------ */
    public void attachDpad() {
        View.OnTouchListener listener = new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        if (v == btnDpadUp) startDpad(30, 30);
                        else if (v == btnDpadDown) startDpad(-30, -30);
                        else if (v == btnDpadLeft) startDpad(-30, 30);
                        else if (v == btnDpadRight) startDpad(30, -30);
                        else if (v == btnDpadStop) { cb.onStopMotors(); }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        stopDpad();
                        return true;
                }
                return false;
            }
        };
        btnDpadUp.setOnTouchListener(listener);
        btnDpadDown.setOnTouchListener(listener);
        btnDpadLeft.setOnTouchListener(listener);
        btnDpadRight.setOnTouchListener(listener);
        btnDpadStop.setOnTouchListener(listener);
    }

    public void stopDpadMotors() {
        stopDpad();
    }
}
