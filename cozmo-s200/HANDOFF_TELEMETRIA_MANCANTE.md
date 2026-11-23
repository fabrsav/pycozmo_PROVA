# Handoff — Cozmo S200: telemetria mancante dopo handshake

**Progetto:** `/home/vpsuser/Projects/cozmo-s200`
**Versione installata:** `dev.s200.cozmo` 0.3.0 (versionCode 9), APK `dist/cozmo-s200-0.3.0.apk`
**Telefono:** DOOGEE S200, Android 16 / API 36, arm64-v8a
**ADB:** `adb -s 100.94.42.57:5555` (Tailscale, attivo)
**Robot:** Anki Cozmo 1ª gen, firmware **2381**, rete `Cozmo_71278E` (BSSID `5e:cf:7f:8d:eb:13`), endpoint UDP `172.31.1.1:5551`
**Log dispositivo:** `run-as dev.s200.cozmo tail -N files/events.ndjson`

---

## Stato attuale (cosa funziona / cosa no)

### ✅ Funziona
- ADB raggiunge il telefono: `100.94.42.57:5555`, package `dev.s200.cozmo` presente, PID 6075.
- Wi-Fi del telefono si associa alla rete del robot `Cozmo_71278E` (wlan1, RSSI ≈ -37, 4-way handshake completato, stato `COMPLETED`).
- **Handshake UDP riuscito**: l'app invia `0xff` reset, riceve `0xf4`/`0xf1`/`0xff` e infine `0xee` (ACK del reset). Log: `Handshake riuscito, attendo firmware`.
- L'app riceve pacchetti dal robot (campo `rx` nei `stats` cresce: 1 → 152 → 303 → 455 → 606 …), zero frame invalidi, zero pendenti.
- In passato (versione 0.1.5 / 0.3.0 precedenti) utente ha confermato **occhi visibili** e **camera 320×240 funzionante** (stanza al buio, immagine scura coerente).

### ❌ Non funziona
- **Telemetria `0xf0` non arriva mai** dopo l'handshake. L'app resta bloccata su:
  - `Inizializzato, attendo telemetria`
  - poi `init_retry_sync: Nessun 0xf0 dopo 2500 ms da initializeRobot: singolo retry Enable + SyncTime`
  - poi `Tentativo scaduto: ripristino la rete precedente` → il telefono ritorna sulla rete domestica FASTWEB e la sessione Cozmo muore.
- Il ping ICMP a `172.31.1.1` perde tutti i pacchetti (previsto: il robot risponde solo su UDP 5551, non a ICMP).
- Nell'ultimo controllo l'app Cozmo è finita in **background** (top activity = `ai.opencode.mobilecode/ai.opencode.app.MainActivity`, la chat), quindi il foreground service `connectedDevice` potrebbe non tenere vivo il lock Wi-Fi.

---

## Log recenti (estratto commentato)

```
Cerco la rete Wi-Fi locale di Cozmo
Richiesta Wi-Fi · conferma il dialogo Android      <-- dialogo Android di sistema da accettare sul telefono
Wi-Fi disponibile · wlan1
Handshake in corso
init_rx ft=9 seq=0..4 ... [t5:0xf4, t5:0xf1, t2:0xff, t4:0xb0, t4:0xd2, t4:0xc9, t4:0xb0]   <-- handshake in corso
Handshake riuscito, attendo firmware
init_rx ft=9 seq=65535..65535 ... [t5:0xf4, t11:0xff, t5:0xf1, t5:0xf4]                      <-- rollover sequenza modulo 65534
init_fallback_enable Pacchetti attivi (0xc9/0xd2/0xf1) senza 0xee dopo 250 ms: invio Enable (0x25)
init_rx ft=9 seq=5..5 ack=1 exp=5 pkts=[t5:0xf4@5, t5:0xf1@5, t4:0xee@5]                     <-- 0xee ricevuto: reset accettato
Inizializzato, attendo telemetria
init_retry_sync Nessun 0xf0 dopo 2500 ms da initializeRobot: singolo retry Enable + SyncTime  <-- TELEMETRIA MANCANTE
stats rx=152 tx=25 stati=0 ...                                                  <-- riceve pacchetti ma non telemetria structurata
…
Tentativo scaduto: ripristino la rete precedente                              <-- fallback alla rete domestica
```

**Interpretazione:** il robot risponde al reset e invia pacchetti keepalive/ack, ma non entra nello stato di invio telemetria periodica (`0xf0`). Senza `0xf0` non si sblocca né l'inizializzazione completa (BodyInfo, SetOrigin, SyncTime, EnableAnimationState) né la telemetria di testa/carrello/cingoli/batteria.

---

## Comandi ADB utili (da eseguire sul telefono tramite ADB)

| Azione | Comando |
|--------|---------|
| Verifica modello | `adb -s 100.94.42.57:5555 shell getprop ro.product.model` |
| App in primo piano? | `adb -s 100.94.42.57:5555 shell dumpsys activity activities \| grep -E "topResumedActivity\|mResumedActivity"` |
| Wi-Fi su Cozmo? | `adb -s 100.94.42.57:5555 shell dumpsys wifi \| grep -E "Cozmo_71278E|curState=|COMPLETED|ConnectModeState"` |
| Log app (ultimi N) | `adb -s 100.94.42.57:5555 shell run-as dev.s200.cozmo tail -N files/events.ndjson` |
| Schermata | `adb -s 100.94.42.57:5555 exec-out screencap -p > cozmo.png` |
| Clic UI per testo | `python3 scripts/device_click.py 'Testo bottone'` (usa `shlex.join`, protegge spazi) |
| Attendi telemetria | `python3 scripts/wait_connected.py` (richiede evidenza recente, non solo click) |
| Ping robot (ICMP, previsto fallire) | `adb -s 100.94.42.57:5555 shell ping -c 3 -W 2 172.31.1.1` |
| Build APK | `cd /home/vpsuser/Projects/cozmo-s200 && bash build.sh` |
| Test host | `bash test.sh` |
| Installa | `bash scripts/install_s200.sh 100.94.42.57:5555` |

---

## Prompt di handoff per un'altra IA (solo chatbot, modalità orchestratore)

Copia e incolla il blocco seguente nella chat con l'altra IA. L'IA non ha strumenti: deve fare da **orchestratore**, cioè dettare all'utente (che esegue materialmente i comandi ADB sul telefono fisico) cosa fare passo dopo passo, quali comandi copincollare e cosa verificare ad ogni passo.

```
# Prompt di orchestratore — Cozmo S200: sbloccare la telemetria dopo handshake

Sei un assistente che può solo leggere/scrivere testo (niente accesso adb, niente shell, niente file). 
L'utente ha un telefono fisico DOOGEE S200 (Android 16) con l'app "Cozmo S200" (package dev.s200.cozmo, v0.3.0) 
installata e raggiungibile via ADB su Tailscale: adb -s 100.94.42.57:5555. 
Il robot Cozmo (firmware 2381) crea la rete Wi-Fi Cozmo_71278E, endpoint UDP 172.31.1.1:5551.

OBIETTIVO: far arrivare al telefono la telemetria 0xf0 del robot dopo l'handshake, 
così l'app passa da "Inizializzato, attendo telemetria" a "Connesso · telemetria attiva".

SITUAZIONE NOTA (già verificata):
- Wi-Fi del telefono si associa a Cozmo_71278E (wlan1, 4-way handshake OK).
- Handshake UDP riuscito: reset 0xff inviato, 0xee ricevuto, log "Handshake riuscito, attendo firmware".
- L'app riceve pacchetti dal robot (rx cresce) ma NON arriva il pacchetto telemetria 0xf0.
- Dopo ~2,5 s l'app fa retry Enable+SyncTime, poi timeout e ripristina la rete domestica.
- L'app può finire in background se l'utente apre altre app (es. la chat); il foreground service 
  connectedDevice dovrebbe tenere viva la sessione, ma va verificato.

IL TUO RUOLO (orchestratore):
1. Non eseguire tu comandi. Fornisci all'utente i comandi ADB pronti da copincollare.
2. A ogni passo, chiedi all'utente di riportarti l'output esatto o lo schermo.
3. Procedi per piccoli passi sequenziali, uno alla volta.
4. Non chiedere all'utente di disattivare Tailscale, né di fare flash/reset di fabbrica, 
   né di installare emulatori/ReDroid.
5. Non chiedere né stampare la password Wi-Fi del Cozmo (è nel Android Keystore del telefono).

PROCEDURA SUGGERITA (adatta in base alle risposte dell'utente):

Passo 0 — Verifica preliminare
- Chiedi all'utente di eseguire: 
  adb -s 100.94.42.57:5555 shell getprop ro.product.model
  adb -s 100.94.42.57:5555 shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity"
- Conferma che il modello è S200 e che l'app dev.s200.cozmo è in primo piano (se non lo è, 
  fagli riaprire l'app Cozmo S200).

Passo 1 — Wi-Fi sul robot
- Chiedi di verificare che il telefono sia connesso a Cozmo_71278E:
  adb -s 100.94.42.57:5555 shell dumpsys wifi | grep -E "Cozmo_71278E|curState=|COMPLETED"
- Se non è connesso, fargli cliccare nell'app "Collega" e accettare il dialogo Android di sistema 
  sul telefono entro pochi secondi (oppure usare "Usa Wi-Fi già collegato" se già associato).

Passo 2 — Handshake e log
- Fargli cliccare "Collega" (o "Usa Wi-Fi già collegato").
- Subito dopo, fargli leggere gli ultimi log:
  adb -s 100.94.42.57:5555 shell run-as dev.s200.cozmo tail -30 files/events.ndjson
- Chiedigli di riportarti se appare "Handshake riuscito", "Inizializzato, attendo telemetria" 
  e se il campo rx cresce nei stats.

Passo 3 — Perché manca 0xf0?
- Se appare "Nessun 0xf0 dopo 2500 ms da initializeRobot", il robot non sta inviando telemetria. 
  Possibili cause da far verificare all'utente:
  a) Il Cozmo è acceso e sulla basetta? Sollevare e abbassare il carrello due volte per 
     vedere la password Wi-Fi (segno che è sveglio).
  b) Il Cozmo è in sleep? Premere il tasto del robot per svegliarlo.
  c) L'app ha i movimenti/occhi disabilitati? Abilitare "Occhi animati sul robot" e 
     attendere che compaia telemetria.
  d) Il telefono è rimasto in background? Riportare l'app in primo piano e ripetere.

Passo 4 — Forza riconnessione pulita
- Fargli eseguire nell'app: "Scollega", attendere 5 s, poi "Collega" (o "Usa Wi-Fi già collegato").
- Rileggere i log e verificare se stavolta appare telemetria (cerca righe con kind=telemetry o 
  messaggi che non siano solo stats/init).

Passo 5 — Verifica finale
- L'app deve mostrare "Connesso · telemetria attiva" e il contatore degli stati deve aumentare.
- Solo allora abilitare eventualmente "Avvia camera" o movimenti testa/carrello (cingoli solo 
  se il robot non è sulla basetta).

Se dopo questi passi la telemetria non arriva comunque, reporta all'utente che serve un intervento 
sul codice del driver (file Link.java, Wire.java, Reliable.java nel progetto 
/home/vpsuser/Projects/cozmo-s200/src/dev/s200/cozmo/core/) e non solo sulla procedura utente.

IMPORTANTE: a ogni passo chiedi all'utente l'output esatto dei comandi e uno screenshot 
(adb -s 100.94.42.57:5555 exec-out screencap -p > cozmo.png) prima di passare al successivo.
```

---

## Prossimi passi consigliati (per te, che hai adb)

1. Riporta l'app Cozmo in primo piano (`am start -n dev.s200.cozmo/.MainActivity`).
2. Assicurati che il Cozmo sia sveglio (premi il tasto robot / solleva e abbassa il carrello 2×).
3. Clicca **Scollega**, attendi 5 s, poi **Collega** e accetta subito il dialogo Wi-Fi sul telefono.
4. Leggi gli eventi: se appare `telemetry` o il contatore stati aumenta, la connessione è vera. 
   Se ricompare `Nessun 0xf0 dopo 2500 ms`, il problema è sul lato robot/firmware o nella 
   fase post-handshake del driver (`Link.java` / `Wire.java`).
5. Non usare il ping ICMP come prova: il robot non risponde a ICMP, solo a UDP 5551.
6. Tieni l'app in primo piano durante il test; se va in background la sessione può deauthenticare.

---

## Avvertenze

- **Non disattivare Tailscale**: serve per ADB e per il resto del telefono. Solo l'app Cozmo 
  deve essere esclusa dallo split tunneling (già configurato).
- **Non usare ReDroid** come destinazione di test: il target fisico verificato è lo S200.
- **Non stampare né salvare** la password Wi-Fi del Cozmo: è cifrata con AES-GCM in Android Keystore.
- Escludere `.private/`, `build/`, `upstream/` dagli archivi sorgente.
- I test host (`bash test.sh`) non certificano l'anticaduta fisica né i cingoli fuori dalla basetta.