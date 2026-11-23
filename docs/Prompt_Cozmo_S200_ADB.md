# Prompt operativo — Cozmo su S200 Android via ADB/Tailscale

## 1. Mandato e risultato finale

Progetta, sviluppa, compila e installa sul mio telefono Android S200 un'app alternativa per il mio Anki Cozmo di prima generazione. L'app ufficiale non riesce a collegarsi al robot: identifica il problema e realizza una connessione indipendente dall'app ufficiale, sulla quale costruire progressivamente le funzioni originarie e quelle avanzate descritte qui.

L'obiettivo finale è un Cozmo espressivo e autonomo: occhi animati, vocalizzi, movimenti coordinati, reazioni ai sensori, interazione con persone e cubi, memoria e funzioni AI opzionali. Il primo traguardo obbligatorio è però una connessione reale e documentata S200–Cozmo. Un telecomando minimo è una tappa intermedia, non il completamento dell'intero progetto.

Questa è un'app Android per S200. La macchina Linux remota serve per sviluppo, compilazione e diagnosi; non si trova necessariamente nella stessa rete Wi-Fi del robot. Non progettare il sistema come se la VPS potesse collegarsi direttamente all'access point di Cozmo.

## 2. Accesso al telefono e limiti operativi

- L'indirizzo Tailscale del telefono è `100.94.42.57`.
- Nella schermata di debug wireless fornita compare `100.94.42.57:33411`. Usalo come endpoint iniziale da verificare: la porta potrebbe essere cambiata.
- In sessioni precedenti è stato usato anche `100.94.42.57:5555`. Utilizzalo soltanto se risulta effettivamente disponibile e appartenente allo stesso telefono.
- Verifica prima le sessioni ADB già connesse. La porta di collegamento e quella di associazione del debug wireless hanno funzioni diverse; non confonderle. La presenza del computer tra i dispositivi associati non dimostra che esista una connessione ADB attiva.
- Usa sempre `adb -s <seriale_verificato>` per ogni operazione sul dispositivo. Verifica produttore, modello, versione Android, livello API e ABI prima di installare.
- Il target di installazione e collaudo è il mio S200 fisico. Eventuali test host non sostituiscono la prova sul telefono e sul robot.
- Mantieni l'app ufficiale e i suoi dati. Installa la nuova app con un package separato, per esempio `dev.s200.cozmo`.
- Non presumere root, Shizuku, Termux o runtime Python già disponibili. Verifica ciò che esiste e preferisci una soluzione senza root.
- Il risultato deve poter funzionare senza una sessione ADB permanente: ADB serve a installazione e diagnosi.

Procedi autonomamente con ricognizione, sviluppo e verifiche reversibili. Se una procedura richiede un'azione fisica sul robot, una password Wi-Fi sconosciuta o un dialogo di sistema che non puoi completare, indica l'azione precisa necessaria e continua le attività indipendenti. Per questa implementazione non eseguire reset, flash o aggiornamenti del firmware.

## 3. Verifica delle fonti e scelta del motore

Studia il codice effettivamente disponibile e registra URL, licenza, commit e versioni delle dipendenze. Parti da:

- https://github.com/Kurtisone/pycozmo
- https://github.com/zayfod/pycozmo
- https://github.com/anki/cozmo-python-sdk

L'SDK ufficiale comunica attraverso l'app Cozmo: non usarlo come unica soluzione a un'app ufficiale che non si collega. PyCozmo è il riferimento da valutare per il controllo diretto del robot, ma la sua disponibilità su desktop non dimostra che funzioni già dentro un APK Android.

Confronta due possibilità mediante una prova concreta:

1. Riutilizzare PyCozmo con Python incorporato nell'APK, verificando supporto di ABI, dipendenze native, thread, socket e instradamento Android.
2. Implementare in Kotlin/Java il sottoinsieme necessario del protocollo, riutilizzando specifiche, generatori e test esistenti invece di inventare il formato dei pacchetti.

Preferisci il riuso verificato del protocollo. Scegli il percorso che porta a un APK mantenibile e realmente eseguibile sull'S200; spiega la scelta dopo averne verificato i punti critici. Un runtime esterno può servire a una diagnosi temporanea, ma non sostituisce l'app richiesta.

Non assumere corrette le affermazioni del documento di partenza su versioni, modelli AI, repository, percentuali di recupero degli asset o funzioni già complete. Verificale nelle fonti primarie. Viccyware, Webots e ROS possono essere riferimenti successivi, dopo averne verificato la pertinenza a Cozmo: non rendere il loro porting un prerequisito per collegare il robot.

## 4. Ricognizione iniziale e diagnosi dell'app ufficiale

Esegui una ricognizione mirata, evitando dump integrali di dati personali:

1. Controlla ADB, toolchain Android/Java/Gradle, architettura della macchina di sviluppo e spazio disponibile. Usa le versioni installate se compatibili e rendi riproducibile la build.
2. Identifica il telefono con `adb devices -l` e proprietà Android mirate.
3. Individua package e versione dell'app Cozmo presente, permessi rilevanti ed eventuali errori di avvio o collegamento nei log filtrati.
4. Verifica stato Wi-Fi, disponibilità dell'SSID Cozmo, assegnazione IP e instradamento. Se necessario, chiedi che il robot venga acceso sulla basetta e che venga mostrata la password Wi-Fi con la procedura verificata per quel modello.
5. Distingui almeno: problema dell'app, mancata associazione Wi-Fi, rete locale non raggiungibile, conflitto con VPN e incompatibilità dell'handshake/protocollo. Non dedurre lo stato meccanico o della batteria dalla sola impossibilità di collegarsi.

Prima di aprire una seconda sessione di controllo, verifica che l'app ufficiale non stia contendendo la connessione. Se necessario, arrestala temporaneamente senza disinstallarla né cancellarne i dati.

## 5. Architettura di rete per S200

La topologia prevista è:

```text
Macchina di sviluppo ── ADB su Tailscale ── S200 Android
                                              │
                                              ├── Wi-Fi locale ── Cozmo
                                              └── dati mobili ── Internet/Tailscale, se disponibili
```

Il valore predefinito usato da PyCozmo per il robot è `172.31.1.1:5551` su UDP. Trattalo come punto di partenza configurabile da verificare con rete, codice e risposte effettive del robot. Non confondere questo endpoint con quello ADB del telefono.

Sul telefono:

- Gestisci il Wi-Fi di Cozmo come rete locale che può non avere Internet. Usa API adatte alla versione Android rilevata, per esempio `WifiNetworkSpecifier` dove applicabile, gestendo permessi e consenso di sistema.
- Ottieni il `Network` corrispondente al Wi-Fi di Cozmo e associa a esso i socket del robot. Per un `DatagramSocket`, valuta `Network.bindSocket` prima di connettere il socket. Se il driver usa Python o codice nativo, verifica concretamente il collegamento con queste API.
- Evita di associare indiscriminatamente tutto il processo alla rete Cozmo: chiamate cloud e collegamenti alla VPS devono poter usare un percorso con Internet, quando disponibile.
- Misura la convivenza fra Wi-Fi locale, dati mobili e VPN Tailscale. Non darla per garantita sul firmware del telefono.
- Non introdurre una seconda VPN per il ponte con Cozmo: potrebbe contendere la VPN Android usata da Tailscale.
- Il solo ADB non fornisce automaticamente alla VPS un accesso UDP alla rete del robot. Un eventuale ponte deve essere implementato esplicitamente nell'app.

Prima di cambiare Wi-Fi, verifica il percorso di recupero di ADB: il cambio rete potrebbe interrompere o modificare il debug wireless. Prepara istruzioni precise per riconnettersi, conserva log locali e limita le modifiche alle impostazioni necessarie al test. Se non è disponibile Internet contemporaneamente, il controllo di base deve funzionare comunque sul telefono; le funzioni cloud rimangono temporaneamente indisponibili.

## 6. Driver, protocollo e controllo locale

Separa almeno questi componenti:

- gestione delle reti Android e delle relative disconnessioni;
- trasporto e protocollo Cozmo;
- stato e telemetria del robot;
- supervisore di sicurezza e arbitraggio dei comandi;
- animazioni, audio e comportamenti;
- interfaccia utente e diagnostica;
- eventuale connessione a servizi AI o alla VPS.

Implementa handshake e compatibilità di versione, framing binario, numeri di sequenza, ACK, ritrasmissioni, keep-alive e chiusura secondo il codice verificato. Non considerare riuscita una connessione solo perché un socket è aperto o un datagramma è stato inviato: servono risposte valide e aggiornamenti del robot correttamente decodificati.

Il loop di rete e la supervisione del movimento devono restare sul telefono, indipendenti da rendering, conversioni audio e chiamate AI. Usa code limitate e timestamp monotoni, scarta i comandi scaduti e impedisci il replay di movimenti dopo una riconnessione. Verifica tempi e comportamento effettivi del firmware senza presumere un timeout universale.

Per l'esecuzione prolungata usa il meccanismo Android appropriato, valutando un foreground service con notifica e tipo compatibile con le operazioni svolte. Verifica comportamento a schermo spento, restrizioni energetiche, ciclo di vita e consumo. Non promettere prestazioni hard real-time su Android.

## 7. Prima app funzionante e criteri di avanzamento

Realizza prima un'interfaccia semplice in italiano con:

- scelta/configurazione della rete Cozmo;
- pulsanti Collega e Scollega;
- stati distinti per Wi-Fi associato, handshake riuscito e telemetria attiva;
- versione del robot quando disponibile, batteria e ultimo pacchetto ricevuto;
- visualizzazione dei sensori effettivamente decodificati;
- anteprima della camera dopo aver validato il trasporto;
- comandi di testa, carrello e cingoli con limiti conservativi e durata breve;
- STOP sempre accessibile, stato degli errori ed esportazione dei log pertinenti.

Ordine delle prove reali:

1. Associazione Wi-Fi e ricezione di telemetria senza comandi motori.
2. Comando verificabile a basso rischio, per esempio un'immagine sul display, poi piccoli movimenti di testa/carrello in condizioni adatte.
3. Prove brevi dei cingoli su una superficie bassa, libera e contenuta, con comando di arresto verificato.
4. Sessione stabile di almeno dieci minuti, registrando disconnessioni, frequenza della telemetria e uso delle risorse.
5. Disconnessione, riconnessione e verifica dell'assenza di movimenti residui.

Non inviare sequenze di movimento a un robot la cui posizione fisica non è stata verificata. Se manca il robot o non è disponibile una condizione di prova adatta, consegna il codice e distingui le verifiche eseguite da quelle ancora da effettuare.

## 8. Sicurezza e gestione dei guasti

Introduci limiti di velocità, accelerazione, corsa dei giunti e durata dei comandi basati sulle costanti del driver e sulle prove reali. La sicurezza ha priorità su comportamento autonomo, telecomando e AI.

Gestisci esplicitamente telemetria obsoleta, perdita del collegamento, sospensione dell'app, sollevamento, sensori di caduta e batteria insufficiente, quando tali informazioni sono disponibili. L'arresto ha priorità; non applicare una retromarcia automatica indiscriminata dopo un evento di precipizio, perché la direzione sicura dipende dalla situazione.

Il pulsante STOP invia il comando di arresto quando il collegamento esiste. In caso di perdita radio, un nuovo comando non può raggiungere il robot: verifica separatamente il comportamento del watchdog del firmware in una prova controllata e documenta i limiti. Non dichiarare garantita l'anticaduta sulla sola base di un test simulato.

## 9. Personalità ed esperienza simile all'originale

Dopo la connessione stabile, ricostruisci progressivamente:

- occhi procedurali, battiti di ciglia, saccadi ed espressioni, con formato del display verificato nel driver;
- animazioni coordinate di occhi, testa, carrello, cingoli e suoni;
- vocalizzi procedurali e, successivamente, brevi parole con elaborazione vocale coerente con il carattere desiderato;
- stati emotivi e bisogni Energy, Play e Comfort, con persistenza locale;
- reazioni a sollevamento, movimento dei cubi, volti e inattività;
- controllo dei cubi e giochi, verificando il supporto effettivo;
- rilevamento e inseguimento visivo di persone/oggetti, con controllo locale e limiti di movimento;
- memoria di nomi e incontri in un database locale, con registrazione esplicita delle persone e possibilità di cancellazione.

Per gli asset originali, verifica quali risorse siano disponibili nell'APK già posseduto o in fonti accessibili e utilizzabili. Documenta origine, licenza e procedura di estrazione. Non rendere un download da un mirror non verificato indispensabile per la prima connessione; predisponi occhi e suoni procedurali come alternativa.

Verifica risoluzioni, codifiche audio, limiti dei motori e frequenze realmente supportate. Tratta i valori del documento precedente come ipotesi tecniche finché non sono confermati. Misura fluidità e latenze sull'S200 anziché imporre arbitrariamente 30/60 FPS o tempi inferiori a 15 ms.

## 10. AI e architettura ibrida opzionale

Mantieni tre livelli separati:

1. Trasporto, controllo e supervisione locale sul telefono.
2. Comportamenti, animazioni e percezione locale compatibili con le risorse misurate dell'S200.
3. Ragionamento, conversazione e analisi più pesanti, eventualmente su VPS o cloud.

Prevedi un'interfaccia configurabile per provider Google/Gemini, Groq, NVIDIA NIM o modelli locali. Verifica nomi dei modelli, disponibilità, SDK e capacità nella documentazione corrente: non assumere valide le versioni e le date di dismissione citate nel testo precedente. Implementa le integrazioni scelte quando siano disponibili accesso e credenziali autorizzate; non stampare chiavi né inserirle nel repository.

Le decisioni AI devono diventare intenzioni strutturate e validate dal supervisore locale, con limiti e scadenza. Non permettere a un modello di inviare liberamente pacchetti o comandi motori. Timeout, risposte malformate o assenza di Internet devono lasciare funzionanti controllo locale e STOP.

Per la percezione usa prioritariamente la camera di Cozmo; indica chiaramente quando un test usa invece quella del telefono. Se serve ascolto ambientale, valuta il microfono dell'S200 con i permessi necessari. Non dedurre distanze 3D affidabili o capacità di presa dalla sola individuazione 2D di un oggetto: calibrazione, geometria e verifica fisica sono passaggi separati.

Se occorre un servizio VPS, mantienilo opzionale e autenticato, preferibilmente raggiungibile tramite Tailscale. La VPS riceve frame o eventi selezionati e restituisce intenzioni; non deve diventare indispensabile per il keep-alive del robot.

## 11. Simulazione e verifiche

Prevedi un'astrazione del driver utilizzabile con robot reale e backend di test. Implementa prima test significativi di codec, handshake, stato della connessione, pacchetti incompleti, perdita/riordino, timeout, arresto e comandi scaduti.

Un simulatore successivo può riprodurre cinematica differenziale, giunti, camera, cubi e sensori di caduta. Distingui sempre simulazione cinematica, simulazione fisica validata e test sull'hardware. I test simulati aiutano lo sviluppo ma non dimostrano raggiungibilità Wi-Fi, compatibilità del firmware, aderenza dei cingoli o arresto reale.

Non anteporre la costruzione di un digital twin completo alla prima connessione sul telefono. Per ogni funzionalità, registra se è soltanto implementata, verificata automaticamente o provata sul robot.

## 12. Compilazione, installazione e consegna

Consegna sorgenti, dipendenze e commit fissati, istruzioni di build, APK, script di installazione sul target verificato e guida operativa in italiano. Separa dati e segreti dalle configurazioni versionate.

Per la ricognizione puoi partire da questi comandi, adattandoli ai risultati effettivi:

```bash
adb devices -l
adb connect 100.94.42.57:33411
adb devices -l
```

Dopo aver verificato endpoint e identità, assegna il seriale reale; quello qui sotto è soltanto il valore mostrato nella schermata:

```bash
S200_SERIAL='100.94.42.57:33411'
adb -s "$S200_SERIAL" shell getprop ro.product.manufacturer
adb -s "$S200_SERIAL" shell getprop ro.product.model
adb -s "$S200_SERIAL" shell getprop ro.build.version.release
adb -s "$S200_SERIAL" shell getprop ro.build.version.sdk
adb -s "$S200_SERIAL" shell getprop ro.product.cpu.abilist
```

Dopo la build, e solo se package e activity corrispondono a quelli effettivamente creati:

```bash
adb -s "$S200_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$S200_SERIAL" shell am start -n dev.s200.cozmo/.MainActivity
```

Verifica installazione tramite package manager, avvio tramite processo/activity e funzionamento tramite log e schermata. Poi verifica separatamente la connessione al robot. Un APK copiato, un'installazione riuscita e un Cozmo controllato sono tre risultati differenti.

Nel resoconto finale indica sinteticamente:

- versione dell'app, APK e sorgenti consegnati;
- identità del telefono ed endpoint ADB effettivamente usato;
- modalità di connessione al Wi-Fi di Cozmo;
- evidenze di handshake, telemetria e comandi confermati;
- funzioni disponibili e funzioni ancora incomplete;
- prove reali, simulate e non eseguite, con motivazione;
- eventuale unica azione fisica o configurazione ancora necessaria.

Inizia dalla ricognizione mirata. Procedi con il collegamento diretto S200–Cozmo e prosegui con le funzioni successive in base alle verifiche ottenute. Non sostituire il lavoro eseguibile con una sola descrizione architetturale e non dichiarare risultati che non hai osservato.

## Fonti tecniche per questo adattamento

- La documentazione originale dell'SDK richiede l'app mobile Cozmo: https://github.com/anki/cozmo-python-sdk/blob/master/docs/source/initial.rst
- PyCozmo descrive il controllo diretto senza l'app ufficiale; il porting Android resta da verificare: https://github.com/zayfod/pycozmo e https://github.com/Kurtisone/pycozmo
- L'endpoint predefinito del robot è definito nel codice del trasporto: https://github.com/Kurtisone/pycozmo/blob/master/pycozmo/conn.py
- Associazione e connessione ADB wireless sono operazioni distinte: https://developer.android.com/tools/adb
- La richiesta di una rete Wi-Fi locale può richiedere consenso di sistema e non fornisce di per sé Internet: https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap
- Android consente l'associazione di un singolo socket a una rete: https://developer.android.com/reference/android/net/Network#bindSocket(java.net.DatagramSocket)
