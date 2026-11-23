# Handoff — 22 novembre 2026: telemetria bloccata dal corpo spento

**App installata:** `dev.s200.cozmo` **0.3.7 / versionCode 16**
**APK:** `dist/cozmo-s200-0.3.7.apk`
**Telefono:** DOOGEE S200, ADB `100.94.42.57:5555`
**Robot:** Cozmo 1ª gen, firmware 2381, SSID `Cozmo_71278E`, UDP `172.31.1.1:5551`

## Cosa funziona

- Installazione e avvio dell'app sul S200.
- Associazione Wi-Fi a `Cozmo_71278E` su `wlan1` (`172.31.1.2/24`).
- Handshake UDP: reset `0xff`, risposta `0xff`, ricezione `0xf1`/`0xf4`/`0xb0`/`0xd2`/`0xc9`.
- Invio dei comandi di boot `0x45`, `0x60`, `0x4b`, `0x9f`.
- Il robot registra/forwarda i comandi (`DebugData 0xb0` con gli ID `0x45`, `0x60`, `0x4b`, `0x9f`).

## Cosa non funziona

- Il robot non invia `BodyInfo` (`0xed`).
- Il robot non invia `RobotState` (`0xf0`): `state_count=0`, `telemetryCount=0`.
- L'unico pacchetto `0xee` osservato è di 12 byte e non è una `FirmwareSignature` JSON valida:
  - esempio: `920abd57ec5740020400c8b9`
  - gli ultimi due byte cambiano a ogni connessione; i primi dieci restano stabili.
- La testa trasmette `0xf1` (AnimationState) e `0xf4` (ImageImuData), quindi il Wi-Fi e la testa funzionano.

## Causa fisica accertata

Alla domanda «quando togli Cozmo dalla basetta resta acceso?» l'utente ha risposto: **si spegne subito**.

Questo spiega i sintomi:

1. Sulla basetta la testa è alimentata e l'AP Wi-Fi resta attivo.
2. Il corpo non riceve alimentazione sufficiente dalla batteria.
3. `Enable` non accende il body controller: niente `BodyInfo`, niente `RobotState`.
4. Sollevando Cozmo l'intero robot si spegne e l'AP sparisce.

Non è quindi un problema del driver 0.3.5.

## Stato del codice

- `Link.java` è tornato alla sequenza nota buona della 0.1.4:
  - `0xee` → solo `Enable` (`0x25` due volte);
  - `0xed` → `SetOrigin`, `EnableStopOnCliff`, `SyncTime`, `EnableAnimationState`;
  - fallback timer solo se `0xed` non arriva.
- Restano attivi i log diagnostici:
  - `firmware_hex` per il payload `0xee`;
  - `rx_trace` limitato agli ottanta pacchetti ricevuti finché `states=0`.
- Test host: 423 controlli core, 6 scenari handshake, 8 controlli Gemini, display 4096 pixel.

## Scoperta successiva: payload CLAD errati (v0.3.6+)

I debug `0xb0` decodificati con `pycozmo.robot_debug` mostrano:

- `WiFi.Update: CLAD message 0x45 size 21 doesn't match size in buffer 25`
- `WiFi.Update: CLAD message 0x60 size 13 doesn't match size in buffer 2`
- `WiFi.Update: CLAD message 0x4b size 13 doesn't match size in buffer 9`
- `WiFi.Messages: Received message not expected here tag=9f`

Il firmware 2381 quindi si aspetta:

- `0x45` (SetOrigin): **20 byte** di dati, non 24;
- `0x60` (EnableStopOnCliff): **12 byte** di dati, non 1;
- `0x4b` (SyncTime): **12 byte** di dati, non 8;
- `0x9f` (EnableAnimationState): non accettato in questa fase, rimosso.

Dopo la correzione (v0.3.6) il robot ha iniziato ad ACKare i pacchetti (`ack=1`), ma invia frame con tipo diverso da 9/11 che l'app conta come `invalidi`. La v0.3.7 aggiunge una traccia temporanea per identificare questo frame.

## Prossimi passi

1. Lasciare Cozmo sulla basetta per una carica prolungata (1–2 ore).
2. Controllare il LED della basetta: se lampeggia rosso, provare un alimentatore/cavo USB 5 V adeguato.
3. Se dopo la carica si spegne ancora appena sollevato, verificare il connettore della batteria o sostituire la batteria.
4. Quando il corpo resta acceso fuori dalla basetta, riprovare la 0.3.5: deve arrivare `0xed` e poi `0xf0`.
5. Solo dopo `state_count > 0` riprendere i test di occhi, testa e personalità.
