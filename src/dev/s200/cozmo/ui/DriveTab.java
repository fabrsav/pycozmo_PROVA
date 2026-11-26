package dev.s200.cozmo.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

public final class DriveTab {
    private static final int DRIVE_PERIOD_MS = 125;
    private final Context c;
    private final UiCallbacks cb;
    private final ScrollView root;
    private final Button arm, forward, reverse, left, right, brake;
    private final Button headUp, headDown, liftUp, liftDown;
    private final TextView motorStatus, headAngle, liftHeight;
    private final ProgressBar armTime;
    private final ImageView camera;
    private final TextView cameraStatus, faceTag, motionTag;
    private final Button cameraSwitch, snapshot, scan, quickTap;
    private final TextView cubeStatus;
    private boolean cameraRunning;
    private Bitmap lastFrame;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Button heldDirection;
    private float leftSpeed, rightSpeed;
    private final Runnable releasePulse = this::stopDpadMotors;
    private final Runnable repeatDrive = new Runnable() {
        @Override public void run() {
            if (heldDirection == null) return;
            if (!heldDirection.isEnabled() || !root.isShown() || !root.hasWindowFocus()) {
                stopDpadMotors();
                return;
            }
            cb.onDrive(leftSpeed, rightSpeed);
            handler.postDelayed(this, DRIVE_PERIOD_MS);
        }
    };

    public DriveTab(Context context, UiCallbacks callbacks) {
        c = context;
        cb = callbacks;
        root = new ScrollView(c);
        root.setFillViewport(true);
        LinearLayout page = new LinearLayout(c);
        page.setOrientation(LinearLayout.VERTICAL);
        int gap = dp(14);
        page.setPadding(gap, gap, gap, 0);
        root.addView(page);

        LinearLayout tracks = UiKit.card(c);
        tracks.addView(UiKit.sectionTitle(c, "Cingoli"));
        motorStatus = UiKit.subtitle(c, "");
        tracks.addView(motorStatus);
        arm = UiKit.actionButton(c, "Abilita motori · 60 s", UiKit.ACCENT, UiKit.ACCENT_DARK);
        arm.setOnClickListener(v -> cb.onArmMotors());
        tracks.addView(arm, block(8));
        armTime = UiKit.horizontalBar(c, 60);
        armTime.setProgressTintList(android.content.res.ColorStateList.valueOf(UiKit.ACCENT));
        tracks.addView(armTime);

        forward = direction("↑", "Avanti", 30, 30);
        reverse = direction("↓", "Indietro", -30, -30);
        left = direction("←", "Ruota a sinistra", -30, 30);
        right = direction("→", "Ruota a destra", 30, -30);
        brake = UiKit.actionButton(c, "Frena", UiKit.DANGER_BG, UiKit.DANGER);
        brake.setOnClickListener(v -> {
            if (heldDirection != null) stopDpadMotors();
            else cb.onStopMotors();
        });

        tracks.addView(padRow(null, forward, null), block(10));
        tracks.addView(padRow(left, brake, right), block(4));
        tracks.addView(padRow(null, reverse, null), block(4));
        TextView drivingHint = UiKit.subtitle(c, "Tieni premuta una freccia per muovere Cozmo.");
        drivingHint.setGravity(Gravity.CENTER);
        tracks.addView(drivingHint, block(8));
        page.addView(tracks);

        LinearLayout joints = UiKit.card(c);
        joints.addView(UiKit.sectionTitle(c, "Testa e carrello"));
        headAngle = reading();
        headDown = jointButton("Giù", "Abbassa la testa", () -> cb.onMoveHead(-0.1f));
        headUp = jointButton("Su", "Alza la testa", () -> cb.onMoveHead(0.1f));
        joints.addView(jointRow("Testa", headAngle, headDown, headUp));
        liftHeight = reading();
        liftDown = jointButton("Abbassa", "Abbassa il carrello", () -> cb.onMoveLift(-5f));
        liftUp = jointButton("Alza", "Alza il carrello", () -> cb.onMoveLift(5f));
        joints.addView(jointRow("Carrello", liftHeight, liftDown, liftUp), block(8));
        page.addView(joints);

        LinearLayout video = UiKit.card(c);
        video.addView(UiKit.sectionTitle(c, "Telecamera"));
        FrameLayout viewport = new FrameLayout(c);
        viewport.setBackground(UiKit.box(c, UiKit.BG_APP, UiKit.BORDER_CARD, 12, 1));
        viewport.setClipToOutline(true);
        camera = new ImageView(c);
        camera.setContentDescription("Vista dalla telecamera di Cozmo");
        camera.setScaleType(ImageView.ScaleType.FIT_CENTER);
        viewport.addView(camera, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        cameraStatus = UiKit.subtitle(c, "");
        cameraStatus.setGravity(Gravity.CENTER);
        int inset = dp(10);
        cameraStatus.setPadding(inset, 0, inset, 0);
        viewport.addView(cameraStatus, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        LinearLayout tags = UiKit.row(c);
        tags.setPadding(inset, 0, inset, inset);
        faceTag = UiKit.pill(c, "Volto rilevato", UiKit.BG_BAR, UiKit.TEXT_PRIMARY);
        motionTag = UiKit.pill(c, "Movimento", UiKit.BG_BAR, UiKit.TEXT_PRIMARY);
        tags.addView(faceTag);
        LinearLayout.LayoutParams secondTag = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        secondTag.setMarginStart(dp(4));
        tags.addView(motionTag, secondTag);
        viewport.addView(tags, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM));
        video.addView(viewport, new LinearLayout.LayoutParams(MATCH_PARENT, dp(148)));

        cameraSwitch = UiKit.actionButton(c, "Avvia camera", UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
        cameraSwitch.setOnClickListener(v -> cb.onSetCameraEnabled(!cameraRunning));
        snapshot = UiKit.actionButton(c, "Scatta", UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
        snapshot.setOnClickListener(v -> cb.onSnapPhoto());
        video.addView(buttonPair(cameraSwitch, snapshot), block(8));
        page.addView(video);

        LinearLayout cubes = UiKit.card(c);
        cubes.addView(UiKit.sectionTitle(c, "Cubi"));
        cubeStatus = UiKit.subtitle(c, "");
        cubes.addView(cubeStatus);
        scan = UiKit.actionButton(c, "Cerca cubi", UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
        scan.setOnClickListener(v -> cb.onCubesScanToggle());
        quickTap = UiKit.actionButton(c, "Quick Tap", UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
        quickTap.setOnClickListener(v -> cb.onCubesQuickTap());
        cubes.addView(buttonPair(scan, quickTap), block(8));
        page.addView(cubes);
    }

    private int dp(int value) { return UiKit.dp(c, value); }

    private LinearLayout.LayoutParams block(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        lp.topMargin = dp(topMargin);
        return lp;
    }

    private LinearLayout buttonPair(Button first, Button second) {
        LinearLayout row = UiKit.row(c);
        row.addView(first, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        row.addView(UiKit.spacer(c, 6, 0));
        row.addView(second, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        return row;
    }

    private LinearLayout padRow(Button first, Button middle, Button last) {
        LinearLayout row = UiKit.row(c);
        row.setGravity(Gravity.CENTER);
        for (Button button : new Button[]{first, middle, last}) {
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, dp(48), 1f);
            cell.setMargins(dp(2), 0, dp(2), 0);
            row.addView(button == null ? new View(c) : button, cell);
        }
        return row;
    }

    private TextView reading() {
        TextView value = UiKit.subtitle(c, "—");
        value.setTextColor(UiKit.ACCENT);
        value.setTypeface(Typeface.MONOSPACE);
        return value;
    }

    private LinearLayout jointRow(String name, TextView reading, Button minus, Button plus) {
        LinearLayout row = UiKit.row(c);
        LinearLayout label = new LinearLayout(c);
        label.setOrientation(LinearLayout.VERTICAL);
        TextView title = UiKit.subtitle(c, name);
        title.setTextColor(UiKit.TEXT_PRIMARY);
        title.setTextSize(15);
        label.addView(title);
        label.addView(reading);
        row.addView(label, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        row.addView(minus, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.76f));
        row.addView(UiKit.spacer(c, 6, 0));
        row.addView(plus, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.76f));
        return row;
    }

    private Button jointButton(String text, String description, Runnable action) {
        Button b = UiKit.actionButton(c, text, UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
        b.setContentDescription(description);
        b.setPadding(dp(4), dp(6), dp(4), dp(6));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private Button direction(String arrow, String name, float left, float right) {
        Button b = UiKit.actionButton(c, arrow, UiKit.BG_CHIP, UiKit.TEXT_PRIMARY);
        b.setContentDescription(name);
        b.setTextSize(23);
        b.setPadding(0, 0, 0, 0);
        b.setOnClickListener(v -> {
            // Keyboard and accessibility actions send one bounded pulse.
            if (heldDirection == null) beginDrive(b, left, right, false);
        });
        b.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (heldDirection == null) beginDrive(b, left, right, true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float x = event.getX(), y = event.getY();
                    if (heldDirection == b && (x < 0 || x >= b.getWidth() || y < 0 || y >= b.getHeight())) {
                        stopDpadMotors();
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    if (heldDirection == b) stopDpadMotors();
                    return true;
                default:
                    return true;
            }
        });
        return b;
    }

    private void beginDrive(Button button, float left, float right, boolean repeat) {
        if (!button.isEnabled() || !root.isShown() || !root.hasWindowFocus()) return;
        heldDirection = button;
        leftSpeed = left;
        rightSpeed = right;
        button.setPressed(true);
        // The D-pad lives inside a ScrollView: keep it from stealing the drag.
        ViewParent parent = button.getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
        cb.onDrive(left, right);
        handler.postDelayed(repeat ? repeatDrive : releasePulse, DRIVE_PERIOD_MS);
    }

    public void stopDpadMotors() {
        if (heldDirection == null) return;
        handler.removeCallbacks(repeatDrive);
        handler.removeCallbacks(releasePulse);
        heldDirection.setPressed(false);
        ViewParent parent = heldDirection.getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
        heldDirection = null;
        cb.onStopMotors();
    }

    public View getView() { return root; }

    public void render(UiState s) {
        boolean controlsOnline = s.connected && !s.factoryFirmware && !s.visualOnly && !s.recoveryRunning;
        boolean wheelsReady = controlsOnline && s.armed && s.wheelBlockReason.isEmpty();
        boolean jointsReady = controlsOnline && s.armed && s.jointBlockReason.isEmpty();
        if (!wheelsReady) stopDpadMotors();

        String reason = s.wheelBlockReason;
        if (s.recoveryRunning) reason = "Ripristino firmware in corso";
        else if (!s.connected) reason = "In attesa della telemetria di Cozmo.";
        else if (wheelsReady) reason = "Cingoli pronti";
        motorStatus.setText(reason);
        motorStatus.setTextColor(wheelsReady ? UiKit.OK : UiKit.TEXT_SECONDARY);
        arm.setText(s.armed ? "Rinnova abilitazione · " + s.armRemainingSec + " s" : "Abilita motori · 60 s");
        UiKit.setEnabledAlpha(arm, controlsOnline && s.canArm);
        armTime.setVisibility(s.armed ? View.VISIBLE : View.GONE);
        armTime.setProgress(s.armRemainingSec);
        UiKit.setEnabledAlpha(forward, wheelsReady);
        UiKit.setEnabledAlpha(reverse, wheelsReady);
        UiKit.setEnabledAlpha(left, wheelsReady);
        UiKit.setEnabledAlpha(right, wheelsReady);
        UiKit.setEnabledAlpha(brake, controlsOnline && s.armed);
        UiKit.setEnabledAlpha(headDown, jointsReady);
        UiKit.setEnabledAlpha(headUp, jointsReady);
        UiKit.setEnabledAlpha(liftDown, jointsReady);
        UiKit.setEnabledAlpha(liftUp, jointsReady);
        headAngle.setText(s.connected ? String.format(Locale.ITALY, "%.1f°", s.headDeg) : "—");
        liftHeight.setText(s.connected ? String.format(Locale.ITALY, "%.0f mm", s.liftMm) : "—");

        cameraRunning = s.cameraRunning;
        Bitmap frame = controlsOnline && cameraRunning ? s.cameraBitmap : null;
        if (frame != lastFrame) {
            camera.setImageBitmap(frame);
            lastFrame = frame;
        }
        cameraStatus.setVisibility(frame == null ? View.VISIBLE : View.GONE);
        cameraStatus.setText(!s.connected ? "Collega Cozmo per vedere dalla telecamera."
                : !controlsOnline ? "Telecamera non disponibile in questa sessione"
                : cameraRunning ? "In attesa del primo fotogramma…" : "Telecamera spenta");
        faceTag.setVisibility(frame != null && s.faceDetected ? View.VISIBLE : View.GONE);
        motionTag.setVisibility(frame != null && s.motionDetected ? View.VISIBLE : View.GONE);
        cameraSwitch.setText(cameraRunning ? "Ferma camera" : "Avvia camera");
        UiKit.setEnabledAlpha(cameraSwitch, controlsOnline);
        UiKit.setEnabledAlpha(snapshot, frame != null);

        cubeStatus.setText(s.connected ? s.cubesSummary : "Collega Cozmo per cercare i cubi.");
        UiKit.setEnabledAlpha(scan, controlsOnline);
        quickTap.setText(s.quickTapActive ? "Ferma Quick Tap" : "Quick Tap");
        UiKit.setEnabledAlpha(quickTap, s.quickTapActive || (controlsOnline && s.cubesConnected));
    }
}
