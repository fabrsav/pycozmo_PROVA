package dev.s200.cozmo.ui;

import android.graphics.Bitmap;
import java.util.Collections;
import java.util.List;

public final class UiState {
    // Connessione & modalità
    public boolean connected;
    public boolean connecting;
    public boolean visualOnly;
    public boolean factoryFirmware;
    public String connectionLabel = "Disconnesso";

    // Batteria & sicurezza motori
    public float batteryVolts;
    public boolean onCharger;
    public boolean canArm;
    public boolean armed;
    public int armRemainingSec;
    public String jointBlockReason = "";
    public String wheelBlockReason = "";
    public float headDeg;
    public float liftMm;

    // Compagno / Personalità / Bisogni (0..100)
    public Bitmap faceBitmap;
    public String activeBehavior = "In attesa";
    public String activeMood = "Neutro";
    public int energyPct;
    public int playPct;
    public int comfortPct;
    public String personalityId = "";
    public boolean autonomousMaster;
    public boolean autonomousVoice;
    public boolean autonomousMotion;
    public boolean autonomousWheels;
    public boolean headlightOn;
    public int selectedLedColor;

    // Guida / Camera / Cubi
    public Bitmap cameraBitmap;
    public boolean cameraRunning;
    public boolean faceDetected;
    public boolean motionDetected;
    public String cubesSummary = "Nessun cubo collegato";
    public boolean cubesConnected;
    public boolean quickTapActive;

    // Mente / Relazioni / Gemini
    public List<String> knownPeople = Collections.emptyList();
    public String geminiMode = "ON_DEMAND";
    public String geminiStatus = "Inattivo";
    public String geminiLastIntent = "";
    public boolean geminiKeyConfigured;

    // Sistema / Opzioni hardware / Recovery / Diagnostica
    public boolean startupHeadOnCharger;
    public boolean criticalBatteryShutdown;
    public boolean recoveryRunning;
    public String recoveryStatus = "";
    public int recoveryProgressPct;
    public String statusHex = "0x0000";
    public int[] cliff = new int[4];
    public long txPackets;
    public long rxPackets;
    public long invalidPackets;
    public List<String> recentEvents = Collections.emptyList();
}
