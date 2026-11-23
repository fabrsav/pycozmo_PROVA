# Handoff per Antigravity — Cozmo su S200

Riprendi il lavoro già eseguito per l'utente. Lavora sul progetto esistente, senza ricreare il driver da zero. L'obiettivo complessivo è un'app Android alternativa per Anki Cozmo, con connessione indipendente dall'app ufficiale e progressiva ricostruzione di personalità, animazioni, audio, cubi, percezione, memoria e AI opzionale.

## Indicazioni più recenti dell'utente

- L'utente ha confermato: **«Ora vedo gli occhi»**. La scritta precedente «Cozmo 01» è stata osservata durante un tentativo di riconnessione fallito dopo un aggiornamento; non va trattata come lo stato attuale del display.
- **La fotocamera funziona. L'utente è in camera al buio**: un'immagine scura non è una prova di guasto o di decoder errato. Non sostituire la camera del robot con quella del telefono e non avviare un'indagine sulla luminosità senza nuovi elementi.
- Cozmo usa una **basetta stampata in 3D** che ostruisce la camera quando la testa è abbassata. È già stato aggiunto e verificato il sollevamento automatico della testa al collegamento.
- L'utente vuole ora questo handoff per proseguire con Antigravity. Il precedente agente interrompe le modifiche e lascia l'app installata e funzionante.

## Percorsi

- Progetto Android reale: `/home/vpsuser/GoogleDrive/Projects/cozmo-s200`
- Leggi `AGENTS.md`, `README.md`, `VERIFICA.md` e `THIRD_PARTY.md` del progetto, oltre alle istruzioni pertinenti di `/home/vpsuser/AGENTS.md`.
- Prompt operativo esteso: `/home/vpsuser/GoogleDrive/Documents/Codex/2026-11-16-su-s200-ho-un-anki-cozmo/Prompt_Cozmo_S200_ADB.md`
- APK corrente: `/home/vpsuser/GoogleDrive/Projects/cozmo-s200/dist/cozmo-s200-0.1.5.apk`
- Codice upstream di riferimento: `upstream/pycozmo`, commit `821a0ed6d8b30efc7ea83c20e5874cdff969aa75`, repository `https://github.com/Kurtisone/pycozmo`.

**Non usare come driver** il vecchio progetto `/home/vpsuser/GoogleDrive/Projects/cozmo-embodied-os`: contiene un protocollo simulato con framing inventato `AA55`, diverso da quello fisico. Non è stato cancellato né integrato nel nuovo APK.

## Telefono e rete già funzionanti

- Telefono fisico verificato: **DOOGEE S200, Android 16, API 36, arm64-v8a**.
- ADB su Tailscale: **`100.94.42.57:5555`**. Le precedenti porte wireless `33411` e `38707` sono superate; la porta TCP 5555 è stata attivata e verificata sul telefono corretto.
- Usa sempre `adb -s 100.94.42.57:5555 ...` e verifica il modello prima delle installazioni. Non usare gli emulatori presenti sulla VPS come destinazione o come prova del telefono.
- Package: **`dev.s200.cozmo`**, versione installata **0.1.5**, versionCode **6**.
- SHA-256 dell'APK locale e di quello letto dal package manager sul telefono, verificati identici:
  `e310a5038cb1c83ed3f9107bfb35c1d98419981f6f6d336ddfb93ccb17a1d3a3`.
- Rete robot: **`Cozmo_71278E`**, rispettando maiuscole/minuscole.
- Endpoint robot: **`172.31.1.1:5551`, UDP**.
- Il telefono ha usato `wlan1` per Cozmo mantenendo la rete domestica su `wlan0`. La connessione locale viene richiesta dall'app con `WifiNetworkSpecifier`; solo il socket UDP è associato al relativo `Network`.
- **Tailscale deve escludere soltanto l'app Cozmo S200** tramite App split tunneling. È già configurato. Senza esclusione `Network.bindSocket` falliva con `EPERM`. Non disattivare tutta la VPN: serve per ADB e per il resto del telefono.
- L'app esclusa non può usare direttamente gli indirizzi della tailnet: consideralo se verrà aggiunto un backend VPS.

La password Wi-Fi è già salvata sul telefono, cifrata con AES-GCM e Android Keystore. L'interfaccia la recupera dopo riavvii e aggiornamenti. Non serve chiederla di nuovo per le normali riconnessioni. Non stamparla, non estrarla dalle preferenze e non inserirla in sorgenti o archivi. La chiave di firma di sviluppo è locale in `.private/debug.keystore`: conservarla per gli aggiornamenti, senza pubblicarla.

## Implementazione esistente

È un APK Java nativo: niente Python o Termux incorporati, niente root. Il driver, il keep-alive e la supervisione restano sul telefono.

- `src/dev/s200/cozmo/core/Wire.java`: framing reale `COZ\x03RE\x01`, codec, telemetria, RLE del display.
- `Reliable.java`: ACK cumulativi, riordino e finestra limitata, modulo 65534.
- `Link.java`: UDP, handshake, inizializzazione, ping, ritrasmissioni, comandi e avvio della testa.
- `Safety.java`: abilitazione manuale dei movimenti per 60 s, telemetria recente, interruzioni e scadenza dei comandi.
- `CameraAssembler.java`: ricomposizione dei frammenti della camera.
- `CameraFrames.java`: conversione JPEG minimizzato e decodifica su un worker separato con coda limitata.
- `RobotService.java`: foreground service `connectedDevice`, gestione Wi-Fi e ciclo di vita.
- `MainActivity.java`, `Eyes.java`, `Secrets.java`: interfaccia italiana, occhi procedurali e credenziali cifrate.

Gli occhi partono al collegamento salvo una precedente disattivazione; l'opzione viene memorizzata. STOP li disattiva e l'interruttore si aggiorna correttamente. Camera e movimenti sono disabilitati nell'interfaccia finché non arriva telemetria valida.

### Basetta 3D e testa

L'opzione **«Solleva la testa al collegamento»** è attiva inizialmente. Il driver attende telemetria recente, robot sulla basetta, calibrazione della testa completata e assenza di stati pericolosi. Poi, una sola volta per connessione, porta dolcemente la testa a **+0.18 rad** in circa 1.2 s. Un'apposita lease scade a 1.7 s; STOP annulla anche un eventuale avvio ancora in attesa. Questo gesto non abilita i cingoli.

**Verificato sul robot:** telemetria passata da circa `-0.4137` a **`+0.1779 rad`**, cioè **+10.2°**. L'anteprima successiva mostrava la stanza oltre la basetta. Non riportare la testa al minimo al termine dell'inizializzazione.

### Dettagli di protocollo da non perdere

1. Ritenta il RESET di handshake ogni 500 ms, fino a CONNECT o timeout di 8 s. Un singolo primo datagramma può non ricevere risposta dopo associazione/aggiornamento. Non continuare i RESET dopo CONNECT.
2. Il limite di 1051 byte riguarda i frame inviati al robot; i datagrammi ricevuti dalla camera possono superarlo.
3. Il firmware fisico **2381** invia nel primo `ImageChunk` un conteggio totale **zero**; il valore definitivo compare nell'ultimo frammento. Encoding e risoluzione vanno conservati dal primo frammento, senza pretendere metadati identici in quelli successivi.
4. Il display fisico 128×64 usa qui immagini logiche interlacciate **128×32**, codificate RLE. Ogni frame di animazione inizia con audio o `OutputSilence`; l'inizializzazione abilita `EnableAnimationState`.
5. Per l'audio futuro, il codice upstream converte PCM in **744 campioni µ-law non complementato a 22050 Hz per pacchetto**. Il WAV PCM16 mono è un formato sorgente, non il payload da inviare direttamente.

## Comandi utili

```bash
cd /home/vpsuser/GoogleDrive/Projects/cozmo-s200
bash test.sh
bash build.sh
bash scripts/install_s200.sh 100.94.42.57:5555

adb -s 100.94.42.57:5555 shell getprop ro.product.model
adb -s 100.94.42.57:5555 shell run-as dev.s200.cozmo tail -20 files/events.ndjson
```

La build usa Java 21, aapt2 Debian nativo ARM con `android-34.jar`, D8/R8, zipalign e apksigner. MinSdk 29, targetSdk 35. Il wrapper globale `~/bin/aapt2` puntava a un file temporaneo inesistente: usa `build.sh`, che seleziona esplicitamente il binario corretto. Non scaricare emulatori o riscrivere la toolchain.

L'APK di test separato `dev.s200.cozmo.checks` è installato e può rimanere per le verifiche:

```bash
python3 scripts/device_click.py 'Collega'
python3 scripts/wait_connected.py
python3 scripts/device_click.py 'Avvia camera' --probe
python3 scripts/device_click.py 'Avvia camera'
python3 scripts/device_click.py 'Occhi animati sul robot' --checked true
```

`wait_connected.py` va usato subito dopo un nuovo tentativo: richiede evidenza recente. Per controllare una sessione già attiva da tempo leggi invece gli ultimi log. Un risultato “Clicked” dimostra il clic, non l'effetto sul robot. Il controllo tramite etichette evita i problemi delle coordinate che cambiano durante aggiornamenti della pagina e rotazione. L'utente legge anche la chat dallo stesso S200: riduci le interferenze con il suo schermo.

## Verifiche già ottenute e limiti

- **33 controlli core**; fixture binarie generate dal codec Python upstream; prova con veri socket UDP che scarta il primo RESET; decodifica dei 4096 pixel di un'immagine Java mediante il decoder Python.
- Nella prova prolungata del trasporto precedente alle ultime modifiche UI/avvio: **894.457 s** di telemetria, **29.701 stati**, circa **33.205 Hz**; **791.779 s** di camera, **11.551 immagini**, circa **14.587 FPS**; zero errori di decoding registrati e nessuna disconnessione in quella sessione.
- La versione 0.1.5 è stata verificata di nuovo sul telefono: handshake, avvio automatico degli occhi, testa a +10.2°, camera 320×240, oltre 5.700 stati e 2.400 immagini nell'ultimo controllo. Utente: occhi visibili e camera funzionante.
- Collegamento mantenuto anche lasciando l'app in secondo piano. Due tentativi di sleep sono stati seguiti da risveglio automatico dopo circa **1.65 s**: **non dichiarare superata una prova prolungata a schermo spento**.
- I cingoli erano fermi sulla basetta. Non sono state completate prove fisiche dei cingoli fuori dalla basetta o dell'anticaduta su un bordo. Non presentare i test software come certificazione di sicurezza fisica.
- Le ultime migliorie di interfaccia sono compilate e installate; una verifica finale esplicita della sincronizzazione dell'interruttore dopo STOP e un ciclo completo Scollega/Collega rimangono utili. Mantieni occhi attivi e movimenti disabilitati al termine.
- Log e metriche della lavorazione sono in `build/`, compresi `hardware-before-final-ui.ndjson` e `hardware-metrics.json`. Non pubblicare dump, foto o credenziali negli archivi sorgente.

## Lavoro successivo

Questa è una base funzionante, **non la replica completa dell'app originale**. Restano da implementare audio/vocalizzi, asset e animazioni originali, giochi con i cubi, comportamenti più ricchi, percezione e riconoscimento, memoria sociale e integrazioni AI. Il progetto non contiene ancora un backend AI operativo.

Consolida la base verificata, crea un checkpoint locale se utile e continua per tappe secondo il prompt esteso. Per modelli e SDK usa documentazione corrente e credenziali autorizzate; non presumere valide le versioni nominate nel documento originario. Mantieni controllo, scadenze e sicurezza indipendenti dalle chiamate AI. Non eseguire flash, reset di fabbrica o modifiche ai servizi generali della VPS per far funzionare questa app.

Comunica in italiano, esegui i passaggi già autorizzati senza chiedere nuovamente conferma, e distingui sempre codice implementato, APK installato, test host e riscontro sul robot reale.
