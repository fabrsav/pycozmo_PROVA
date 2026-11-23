# Cozmo S200

App Android nativa per collegare direttamente Anki Cozmo di prima generazione al DOOGEE S200, sviluppata e installata tramite ADB/Tailscale. Il driver gira sul telefono; la VPS e una sessione ADB permanente non sono necessari durante l'uso.

## Uso sul telefono

1. Accendi Cozmo sulla basetta. Solleva e abbassa due volte il carrello per visualizzare la password della sua rete Wi-Fi.
2. In Tailscale apri il menu account, **App split tunneling**, e seleziona soltanto **Cozmo S200** fra le app escluse. La VPN resta attiva per il resto del telefono. L'esclusione consente al socket dell'app di usare la rete locale del robot.
3. Apri **Cozmo S200**, verifica il nome della rete e inserisci la password. Tocca **Collega** e conferma il dialogo Android, se richiesto.
4. Attendi **Connesso · telemetria attiva**, con il contatore degli stati che aumenta. Un'associazione Wi-Fi da sola non dimostra la connessione al robot.
5. Gli occhi si attivano al collegamento, salvo una precedente disattivazione. Usa **Occhi animati sul robot** per cambiarne lo stato e **Avvia camera** per vedere l'immagine del robot. L'illustrazione in cima alla pagina è dichiarata come anteprima.
6. Per testa e carrello abilita i movimenti per 60 secondi. I cingoli sono limitati a impulsi brevi e restano bloccati se il robot segnala di trovarsi sulla basetta. **STOP** è sempre visibile e disponibile anche nella notifica.
7. **Scollega** chiude il collegamento e rilascia la rete temporanea. Per una rete Cozmo già selezionata nelle impostazioni Android è disponibile **Usa Wi-Fi già collegato**.

La password viene salvata sul telefono solo dopo una connessione Wi-Fi riuscita, cifrata con AES-GCM e una chiave non esportabile di Android Keystore. Non viene inclusa nei sorgenti o nell'APK. Il backup Android dell'app è disabilitato.

L'opzione **Solleva la testa al collegamento**, attiva inizialmente, è pensata per la basetta stampata in 3D: dopo la calibrazione e con telemetria recente, il robot sulla basetta porta dolcemente la testa a circa +10°. Il movimento viene eseguito una volta per connessione e non abilita i cingoli. STOP e il rilevamento di sollevamento/caduta lo interrompono.

## Architettura implementata

- Java e API Android, senza Python, Termux, root o dipendenze native nell'APK.
- Richiesta Wi-Fi locale con `WifiNetworkSpecifier`, callback posseduta dal servizio e associazione del solo socket UDP alla rete Cozmo.
- Foreground service di tipo `connectedDevice`, notifica con STOP e gestione esplicita della chiusura.
- Framing reale `COZ\x03RE\x01`, handshake, numeri di sequenza modulo 65534, ACK cumulativi, riordino e ritrasmissione limitata con backoff.
- Inizializzazione tramite firma firmware, Enable, BodyInfo, SetOrigin, SyncTime, arresto su cliff e abilitazione del motore di animazione.
- Stato batteria, testa/carrello, cingoli, accelerazione e flag ricevuti dal robot.
- Display logico 128×32 con codifica RLE del protocollo, occhi procedurali con saccadi e battiti di ciglia, 9 espressioni e 4 profili caratteriali (`Curioso`, `Affettuoso`, `Birichino`, `Tranquillo`) con modalità **Vita autonoma**.
- Sintesi audio locale a 22050 Hz PCM 16-bit codificata µ-law (`AudioSynth`) con vocalizzi emotivi e sintesi sillabica.
- Ricomposizione delle immagini `ImageChunk`, conversione JPEG minimizzato, decodifica su worker dedicato e percezione 2D locale (`VisionAnalyzer` + `FaceDetector`).
- Supporto BLE per i 3 Light Cubes (`Cubes`) con gestione LED e minigioco **Quick Tap**.
- Memoria sociale locale SQLite (`MemoryDb`) per persone incontrate, eventi episodici e persistenza dei bisogni (`Energy`, `Play`, `Comfort`).
- Supervisore AI locale offline (`AiSupervisor`) con validazione e limiti di sicurezza sulle intenzioni, oltre a comandi background via `RobotService` e stato persistito in `files/state.json`.
- Supervisione di sicurezza: telemetria obsoleta, sollevamento/caduta, lease dei cingoli di 250 ms e dei giunti di 850 ms; nessun replay dei movimenti dopo una nuova connessione.
- Log locali esportabili con il selettore documenti Android, senza password.

Il progetto Python precedente `cozmo-embodied-os` rimane separato: il suo framing simulato non viene usato per il collegamento fisico.

## Build sulla VPS

Toolchain verificata: Linux aarch64, Java 21, `javac`, aapt2 Debian nativo ARM, Android SDK API 34, D8/R8, zipalign e apksigner. L'app usa minSdk 29 e targetSdk 35 ed è stata eseguita sul telefono Android 16/API 36. L'APK di sviluppo è firmato ed è debuggable per consentire diagnosi tramite ADB.

```bash
bash build.sh
```

Output: `dist/cozmo-s200-0.2.0.apk`.

Le tabelle JPEG e la licenza sono incluse in `assets/`. Il sorgente upstream, quando disponibile, viene verificato al commit fissato prima di rigenerare gli asset. In sua assenza la build verifica gli hash degli asset inclusi e può funzionare senza rete.

I percorsi della toolchain possono essere adattati in `build.sh`. Il wrapper globale `~/bin/aapt2` di questa VPS puntava a un file temporaneo inesistente; la build usa esplicitamente il binario ARM. L'aapt2 Debian installato legge android-34.jar, mentre il formato delle risorse di android-35.jar richiede una versione diversa dello strumento.

## Verifiche riproducibili

Per recuperare il riferimento PyCozmo esattamente alla revisione documentata:

```bash
bash scripts/bootstrap_upstream.sh
bash test.sh
```

I test confrontano byte per byte i frame Java con fixture generate dal codec Python upstream. Comprendono parsing incompleto, riordino, duplicati, ACK al rollover, sostituzione dei movimenti scaduti, lease e blocchi di sicurezza, decodifica del display mediante il decoder Python e ricomposizione della camera con metadati parziali osservati sul firmware 2381.

Per le verifiche dell'interfaccia sul telefono, l'APK di test separato non riavvia il processo del robot:

```bash
bash scripts/build_device_checks.sh
adb -s 100.94.42.57:5555 install -r -t build/device-checks/installed.apk
python3 scripts/device_click.py 'Avvia camera'
```

Questo strumento seleziona un controllo per etichetta. Il risultato “Clicked” dimostra il clic, mentre l'effetto sul robot deve essere verificato nei log e nella telemetria. Attendere sempre il collegamento prima dei comandi.

## Installazione

```bash
bash scripts/install_s200.sh 100.94.42.57:5555
```

Lo script verifica modello, API e ABI prima dell'installazione e rilegge la versione installata. Non modifica altre app. Per aggiornare l'installazione esistente conserva la chiave di firma locale in `.private/`: l'archivio sorgente distribuito non contiene chiavi o credenziali.

## Dettagli verificati sul dispositivo

Il target è DOOGEE S200, Android 16, API 36, arm64-v8a. L'endpoint del debug wireless è cambiato da `100.94.42.57:33411` a `:38707` durante la sessione; dopo aver verificato il telefono è stato attivato e provato ADB TCP sulla porta `5555`.

Il robot ha risposto con firmware **2381**. Il telefono ha creato una connessione locale su `wlan1`, mantenendo la rete domestica su `wlan0`. Il primo tentativo di `Network.bindSocket` falliva con `EPERM` sotto Tailscale; l'esclusione della sola app ha risolto il problema.

Due particolarità reali hanno test dedicati: i datagrammi della camera ricevuti possono superare il limite di 1051 byte applicabile ai comandi inviati al robot; il primo frammento di un'immagine può indicare `image_chunk_count=0`, mentre il conteggio definitivo compare nell'ultimo frammento.

L'esclusione da Tailscale impedisce all'app stessa di usare gli indirizzi della tailnet: eventuali integrazioni VPS future devono tenerne conto. Tailscale e ADB del telefono restano utilizzabili.

I risultati della prova sul robot e gli eventuali limiti residui sono riportati in `VERIFICA.md`. I test host non certificano l'anticaduta fisica. Quando manca la radio, un nuovo STOP non può raggiungere il robot: il comportamento del firmware deve essere valutato separatamente.

## Riferimenti

- [PyCozmo, fork usato e commit fissato](https://github.com/Kurtisone/pycozmo/tree/821a0ed6d8b30efc7ea83c20e5874cdff969aa75)
- [PyCozmo originale](https://github.com/zayfod/pycozmo)
- [Wi-Fi Network Request API](https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap)
- [Tailscale: esclusione delle app Android](https://tailscale.com/docs/features/client/android-app-split-tunneling)

La licenza del codice di riferimento e delle tabelle è in `THIRD_PARTY.md` e `assets/PYCOZMO-LICENSE.txt`.
