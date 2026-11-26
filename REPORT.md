# Cozmo S200 — Stato del lavoro notturno (2026-11-26)

## Obiettivo
Completare il **redesign delle 4 tab** (Compagno / Guida / Mente / Sistema) e le **fix funzionali**
del progetto Android `dev.s200.cozmo`, con deliverable verificabili: build verde, test verdi,
APK firmato in `dist/`, e verifica sul dispositivo redroid (`localhost:5556`).

## Stato build (ripreso da disco, non ricostruito da zero)
- Ultimo APK: `dist/cozmo-s200-0.5.0.apk` (144104 byte, firmato, sha256 registrato da `build.sh`).
- `git status`: 11 file modificati + 2 nuovi (`res/values/`, `src/dev/s200/cozmo/ui/CompanionFaceView.java`).
- Diff: 655 insertions / 621 deletions. Nessun commit delle modifiche notturne (lavoro in-progress su disco).

## Cosa è FATTO
### Design system / UiKit (`src/dev/s200/cozmo/ui/UiKit.java`)
- Palette centralizzata: `BG_APP #0A1219`, `BG_BAR #101B24`, `BG_CARD #14232D`, `BORDER_CARD #2B404C`,
  `ACCENT #83F4CB`, `WARN #FFD49A`, `DANGER #FF9B91`, testo `#F2F7F6/#B5C9D1/#96AEB9`.
- Helper: `card()`, `row()`, `sectionTitle()`, `subtitle()`, `pill()`, `banner()`, `actionButton()`,
  `styleChip()` (chip selezionati/non con border coerente), `input()`, `horizontalBar()`, `spacer()`.
- Bottone con ripple (`RippleDrawable`) e altezza minima 48dp (target touch).
- `res/values/` aggiunto per risorse condivise (id `chip_state`, drawable `icon`).

### 4 tab (`src/dev/s200/cozmo/ui/`)
- **Compagno** (`CompanionTab.java`): face view custom `CompanionFaceView` (occhi animati),
  bisogni (energia/gioco/comforto), chips personalità (CURIOSO/AFFETTUOSO/BIRICHINO/TRANQUILLO),
  vocalizzazioni, LED zaino (6 preset), faro, vita autonoma (master + Voce/Gesti/Cingoli).
- **Guida** (`DriveTab.java`): D-pad, slider velocità, head/lift, brake/reset, quick-tap cubi.
- **Mente** (`MindTab.java`): personalità, memoria persone, Gemini policy/prompt, stato AI.
- **Sistema** (`SystemTab.java`): connessione Wi-Fi robot, stato link/telemetria, batteria,
  diagnostica pacchetti, impostazioni (auto-head, auto-shutdown), power off, STOP.
- `UiState.java`: state object condiviso; `UiCallbacks.java`: interfaccia callback da 4 tab verso `MainActivity`.

### MainActivity (`src/dev/s200/cozmo/MainActivity.java`)
- Root `LinearLayout` verticale: **top bar** (dot stato + stato + tag LIVE/VISUAL/FACTORY, batteria,
  motori, pulsante STOP) → divider → **host** (FrameLayout, peso 1) → divider → **bottom nav 4 tab**
  (bottoni 58dp, pill, attivo = fondo ACCENT + testo ACCENT_DARK).
- `showTab(index)` con visibilità GONE/VISIBLE, stile nav, e **stop dei motori D-pad** quando si
  esce dalla tab Guida (`stopDpadMotors()`).
- `onPause`/`onDestroy` → stop motori (fail-safe).
- Aggiornamento UI ogni 300ms via `Handler` (`poll`), solo per la tab attiva.

### Fix funzionali applicati
- **Motori D-pad**: stop automatico al cambio tab / pausa / distruzione dell'Activity (prima restavano
  attivi). Aggiunto anche in `DriveTab.stopDpadMotors()`.
- **Link.java**: `+5` righe — verifica dello stato per il fail-safe dei motori.
- **Manifest**: `android:versionName="0.5.0"`, `versionCode=31`; `stopWithTask="false"` sul servizio.
- **build.sh**: signing su output separato (`build/signed.apk`) perché `/tmp` del host non è scrivibile
  (apksigner falliva in-place); `aapt2 compile/link`; resource shrink non applicato.

## Root cause individuato stanotte (BUG DI VERIFICA, NON DELL'APP)
Il logcat mostrava `START u0 {cmp=it.fabriziodp.hallidayai/.MainActivity} from uid 2000` ripetuto.
**Cause:**
1. `uid 2000` = shell, cioè **i miei stessi comandi `adb shell uiautomator dump`**, non Cozmo.
2. `it.fabriziodp.hallidayai` è il **dock/launcher WebView** del tablet redroid, NON l'app Halliday
   che l'utente stava usando. Il dump XML conteneva testi ChatGPT ("Livello Thinking", "Account",
   "Ricerca web") = contenuto della WebView del dock.
3. Il mio dump XML era quindi **del dock, non di Cozmo** → verifica UI falsata.
4. Inoltre la bottom nav è in **fondo allo schermo** (y ≈ 684 nei 1280×800); i tap a y=560
   colpivano la zona centrale → spiegato perché i tap non switchavano tab.

**Conclusione:** non c'è evidence di bug di app-switching in Cozmo. Il comportamento era 100% tooling.

## Cosa RESTA
1. **[IN CORSO] Cozmo come HOME activity** — il tablet redroid non ha nessuna HOME registrata e il
   dock WebView ruba il primo piano. Fix: `MainActivity` con `intent-filter` `CATEGORY_HOME` +
   `CATEGORY_DEFAULT`, e un'Activity di rientro rapido.
2. Verifica UI reale sul redroid **senza `uiautomator`** (solo screenshot + coordinate derivate dal layout),
   per evitare di rilanciare il dock.
3. Test/verifica funzionale delle 4 tab (switch + rendering + fail-safe motori).
4. Rigenerare APK firmato + `idsig`, sha256, e report finale.

## ERRORI NOTI
- `uiautomator dump` **rilancia l'app di default del dock** → non usarlo più per la verifica.
- `adb` senza `-s` fallisce: ci sono 2 device (`localhost:5556` = redroid/tablet, `100.94.42.57:5555` = robot reale).
  **Usare sempre `-s localhost:5556` per l'app, `-s 100.94.42.57:5555` per il robot.**
- `/tmp` del host non scrivibile → `apksigner` deve scrivere su un path separato (già gestito in `build.sh`).
- Il robot reale non è raggiungibile via Wi-Fi dal tablet in questa sessione (TODO credenziali/rete).

## ASSUNZIONI
- Il target utente è il **tablet redroid** (`localhost:5556`), non il robot fisico.
- Cozmo deve poter funzionare come app normale; l'aggiunta di HOME è un **miglioramento** che non
  impedisce l'avvio normale (l'intent-filter LAUNCHER resta presente e ha priorità).
- Il dock `it.fabriziodp.hallidayai` è installato dall'utente e non va disinstallato.
