# Verifica della consegna al 21 novembre 2026 (Milestone M0–M5 · v0.3.0)

## Installazione corrente

- DOOGEE S200, Android 16 / API 36, arm64-v8a.
- ADB verificato: `100.94.42.57:5555`.
- App `dev.s200.cozmo`, versione **0.3.0 / versionCode 9**.
- APK locale e installato byte-identici (`dist/cozmo-s200-0.3.0.apk`), SHA-256: `a18c15cf6da5706c2cb3a7efd62283869cc7dbc85b7689b9d004f0c6cbd37e00`.
- Robot fisico: firmware 2381 (`Cozmo_71278E`), collegamento UDP diretto attraverso il Wi-Fi dell'S200 (`wlan1`).

## Verifiche M0–M5 completate (21 novembre 2026)

- **Host (`bash test.sh`)**:
  - **423 controlli core** in `CoreTest` (inclusa matrice completa `9 espressioni × 4 profili × 5 punti di interpolazione` con verifica budget RLE `<= 1024` byte, `EmotionGraph` con `repetitionPenalty`, `NeedsModel` con `fullness_cooldown` e floor cortese `0.25` dopo >2h offline, `AudioSynth` con 4 `VoiceProfile` e tetto `<= 90` frame, `Choreographer` con 10 clip multi-modali, preemption `SAFETY_REFLEX` e backpressure audio `> 120` frame, simulazione accelerata di 10 minuti su `BehaviorScheduler`, e classificatore italiano `AiSupervisor` su 12 frasi × 4 profili con sanitizzazione segreti).
  - **4 scenari UDP deterministici M0** in `HandshakeRetryTest` (avvio a freddo con primo RESET perso, riconnessione a caldo sulla traccia reale del 21 nov 2026 senza `0xee`/`0xed`, perdita di `0xed` con fallback, e idempotenza su doppia ricezione di `0xee`/`0xed`).
  - **8 controlli WebSocket** in `GeminiLiveTest` e decodifica display Java su 4096 pixel tramite il decoder Python originale di PyCozmo.
- **Dispositivo S200 via ADB (`100.94.42.57:5555`)**:
  - Ripristino automatico di `play` e `comfort` dal vecchio fondo (`0.03`) al floor cortese `0.25` (`LOW`) e successiva promozione a fascia `NORMAL` (`play=0.53`, `comfort=0.67`) tramite azioni `CARE` (`comfort` e `play`).
  - Verifica `AI_PROMPT` (`Intenzione applicata [locale]: HAPPY clip=GREET_STRANGER voce=happy zaino=cyan`), `AI_TEST` e nuovi campi `state.json` (`initPath`, `connectReadyMs`, `telemetryCount`, `autonomousVoice=true`, `autonomousMotion=false`, `autonomousWheels=false`, `activeClip`, `aiPolicy="ON_DEMAND"`, `rleFallbacks=0`).

## Risultati

La versione corrente riceve telemetria e immagini 320×240. L'utente ha confermato gli occhi visibili e il funzionamento della camera. La stanza è al buio: l'immagine scura è coerente con l'ambiente dichiarato.

Per la basetta stampata in 3D, la testa viene sollevata automaticamente dopo calibrazione e telemetria valida sulla basetta. Il target di +0.18 rad è stato riscontrato come +0.1779 rad nella telemetria; nell'anteprima si vedeva la stanza oltre la base. I cingoli rimanevano a 0 / 0 mm/s.

Nell'ultimo controllo della versione 0.1.5 erano già presenti oltre 5.700 aggiornamenti di stato e oltre 2.400 immagini, con zero frame di protocollo invalidi registrati. L'APK era identico a quello compilato localmente.

La prova prolungata del trasporto sulla versione 0.1.2, prima delle successive modifiche di interfaccia, retry iniziale e posa d'avvio, ha registrato:

| Misura | Risultato |
| --- | --- |
| Durata telemetria | 894.457 s |
| Stati registrati al campione finale | 29.701 |
| Frequenza misurata | 33.205 Hz |
| Durata campionamento camera | 791.779 s |
| Immagini al campione finale | 11.551 |
| Frequenza camera | 14.587 FPS |
| Frame di protocollo invalidi | 0 |
| Errori di decoding camera registrati | 0 |
| Pacchetti pendenti al controllo finale | 0 |

Sono passati 80 controlli core (frame PyCozmo, 65.536 campioni µ-law, `AudioSynth`, `Personality` e profili caratteriali, `Cubes` / Quick Tap, `VisionAnalyzer`, `AiSupervisor`), una prova UDP con perdita intenzionale del primo RESET e il confronto del display generato con il decoder originale PyCozmo su 4096 pixel.

Nella prova silenziosa su robot fisico (con sollevamento testa, occhi e audio disattivati) sono stati ricevuti oltre 1.934 pacchetti di telemetria con zero frame invalidi (batteria da 4,73 V a 4,80 V, transizione carica `0x3310` -> `0x1310`, testa `-23,7°`, carrello `32 mm`) e 29 fotogrammi `320×240` analizzati da `VisionAnalyzer` (`dark_scene=true`, `mean_luma=10.37`).

## Limiti della verifica

- La prova fisica degli attuatori, dell'audio 22050 Hz µ-law, dei LED zaino/faro e dei profili caratteriali con vita autonoma su robot fisico resta da eseguire quando è possibile fare rumore e muovere la testa.
- I Light Cubes non erano accesi durante il controllo passivo (`heard=0`, `connected=0`).
- Non sono state eseguite prove fisiche dei cingoli fuori dalla basetta, del carrello con carico o di anticaduta su un bordo.
