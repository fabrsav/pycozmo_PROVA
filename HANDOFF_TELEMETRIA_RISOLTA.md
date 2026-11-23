# Handoff — 22 novembre 2026: telemetria risolta

**App stabile:** `dev.s200.cozmo` **0.4.0 / versionCode 19**  
**APK:** `dist/cozmo-s200-0.4.0.apk`  
**Robot:** Cozmo 1ª gen, firmware 2381, `Cozmo_71278E`, UDP `172.31.1.1:5551`

## Esito

Collegamento e telemetria verificati sul robot fisico:

- `connected: true`
- `state_count` crescente (511+ al primo controllo)
- `connectReadyMs: 2665`
- `invalidi: 0`
- `status: "Connesso · telemetria attiva"`
- personalità reattiva: espressione `CURIOUS`, profilo `CURIOSO`
- telemetria compatta: `head=-0.436 rad (-25°)`, `lift=32 mm`, `on_charger=true`, `unsafe=false`

## Cause reali (due, entrambe software)

### 1. Payload CLAD errati nei comandi di boot

I debug `0xb0` del Wi‑Fi controller, decodificati con `pycozmo.robot_debug`, riportavano:

- `CLAD message 0x45 size 21 doesn't match size in buffer 25`
- `CLAD message 0x60 size 13 doesn't match size in buffer 2`
- `CLAD message 0x4b size 13 doesn't match size in buffer 9`
- `Received message not expected here tag=9f`

Correzioni in `Link.java`:

- `0x45` SetOrigin: 20 byte dati (non 24)
- `0x60` EnableStopOnCliff: 12 byte dati (non 1)
- `0x4b` SyncTime: 12 byte dati (non 8)
- `0x9f` rimosso dalla fase di boot

Dopo questa correzione il robot ha iniziato ad ACKare e a inviare `0xf0`.

### 2. RobotState compatto da 65 byte

Il robot inviava `0xf0` con `len=65`, ma `Wire.Telemetry.parse` richiedeva almeno 91 byte e sollevava `Short RobotState`, incrementando `invalidi` senza mai incrementare `stati`.

Il formato compatto è stato mappato da tracce live:

- offset 0: timestamp u32
- offset 36: head angle (rad)
- offset 40: lift angle (rad)
- offset 44: lift height (mm)
- offset 52: carica batteria percentuale
- offset 56: status u32
- offset 60/62: due sensori cliff u16
- offset 64: touch

`Wire.Telemetry.parse` ora accetta sia il formato compatto sia quello completo da 91 byte, mantenendo la stessa semantica per safety e personalità.

## Test

- 426 controlli core (incluso il nuovo caso `compact RobotState`)
- 6 scenari handshake
- 8 controlli Gemini
- display 4096 pixel decodificato dal decoder PyCozmo originale

## Prossimi passi

Con telemetria verificata sono sbloccati i controlli di movimento, audio e personalità. Riprendere le prove normali da `VERIFICA.md` e la sequenza di personalità, mantenendo i controlli di sicurezza现有的.
