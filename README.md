# pycozmo_PROVA

Repository unificato per lo sviluppo dell'app Android **Cozmo S200** (driver reale PyCozmo e motore di personalità per Anki Cozmo 1ª generazione su DOOGEE S200), insieme al prototipo/simulatore Python precedente e alla documentazione operativa.

## Struttura

- [`cozmo-s200/`](cozmo-s200/README.md) — **Progetto attivo principale**: app Android nativa Java per DOOGEE S200 basata sul protocollo reale di [PyCozmo](https://github.com/Kurtisone/pycozmo) (commit fissato `821a0ed6d8b30efc7ea83c20e5874cdff969aa75`), con motore personalità (`Personality`, `EmotionGraph`, `NeedsModel`, `BehaviorScheduler`, `Choreographer`, `FaceRenderer`, `AudioSynth`), visione 2D (`VisionAnalyzer`), Light Cubes (`Cubes`), memoria SQLite (`MemoryDb`), supervisore/AI (`AiSupervisor`, `GeminiLiveClient`) e `FirmwareRecovery`. Versione corrente nei sorgenti: `0.4.10-recovery` (`versionCode 29`).
- [`cozmo-embodied-os/`](cozmo-embodied-os/cozmo_os/__init__.py) — **Prototipo Python a 3 strati (L1 riflessi, L2 limbico, L3 corteccia)** con protocollo simulato `AA55`, mantenuto come riferimento architetturale e suite di test funzionali/mutazionali (**non usare** come driver fisico per Cozmo).
- [`docs/`](docs/Prompt_Cozmo_S200_ADB.md) — Documentazione operativa e di contesto:
  - `docs/Prompt_Cozmo_S200_ADB.md`: mandato e specifiche operative per S200/ADB.
  - `docs/AUDIT_OPENCODE_COZMO.md`: audit delle sessioni precedenti.
  - `docs/safe-cozmo-20261115-NOTES.md`: note sul checkpoint locale del simulatore Python.

## Comandi rapidi

Dalla root del repository:

```bash
# 1. Scarica/verifica il sorgente upstream PyCozmo al commit fissato (se non già presente)
bash cozmo-s200/scripts/bootstrap_upstream.sh

# 2. Suite test host Android/Java + verifica decoder display PyCozmo
bash cozmo-s200/test.sh

# 3. Compila e firma l'APK (richiede toolchain ARM sulla VPS e .private/debug.keystore locale)
bash cozmo-s200/build.sh

# 4. Installa sul telefono fisico DOOGEE S200 via ADB/Tailscale
bash cozmo-s200/scripts/install_s200.sh 100.94.42.57:5555

# 5. Suite funzionale e banchi mutazionali del prototipo Python
bash cozmo-embodied-os/tests/run_all.sh
```

## Sicurezza e file locali esclusi

- Le credenziali Wi-Fi del robot e l'eventuale chiave API Gemini non vengono mai salvate nel repository o negli APK: restano solo sul telefono, cifrate con Android Keystore (`cozmo-s200/src/dev/s200/cozmo/Secrets.java`).
- `.private/debug.keystore`, `cozmo-s200/build/`, `cozmo-s200/dist/`, `cozmo-s200/upstream/` e `cozmo-embodied-os/logs/` sono esclusi intenzionalmente tramite `.gitignore`.
