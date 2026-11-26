package dev.s200.cozmo.ui;

public interface UiCallbacks {
    // Emergenza
    void onEmergencyStop();

    // Tab 1: Compagno
    void onOpenConnection();
    void onPet();
    void onPlay();
    void onSelectPersonality(String id);
    void onTriggerExpression(String id);
    void onTriggerChoreography(String id);
    void onSpeak(String text);
    void onPlayVocalization(String id);
    void onSetBackpackColor(int colorInt);
    void onToggleHeadlight(boolean enabled);
    void onToggleAutonomousMaster(boolean enabled);
    void onToggleAutonomousVoice(boolean enabled);
    void onToggleAutonomousMotion(boolean enabled);
    void onToggleAutonomousWheels(boolean enabled);

    // Tab 2: Guida
    void onSetCameraEnabled(boolean enabled);
    void onSnapPhoto();
    void onArmMotors();
    void onDrive(float leftMmps, float rightMmps);
    void onStopMotors();
    void onMoveHead(float deltaOrTargetRad);
    void onMoveLift(float deltaOrTargetMm);
    void onCubesScanToggle();
    void onCubesQuickTap();

    // Tab 3: Mente
    void onGreetOrRegisterPerson(String name);
    void onSetGeminiMode(String mode);
    void onSendGeminiPrompt(String prompt);

    // Tab 4: Sistema
    void onConnectWifi(String ssid, String password);
    void onConnectExistingWifi(String ssid);
    void onDisconnect();
    void onToggleStartupHead(boolean enabled);
    void onToggleCriticalBatteryShutdown(boolean enabled);
    void onPowerOffRobot();
    void onSaveGeminiKey(String apiKey);
    void onTestGeminiKey();
    void onOpenLauncherApp(String packageName);
    void onOpenSystemSettings();
}
