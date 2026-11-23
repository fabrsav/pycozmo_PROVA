# AGENTS.md — pycozmo_PROVA

- Questa directory (`/home/vpsuser/GoogleDrive/Projects/pycozmo_PROVA`) è il workspace canonico tracciato su GitHub (`fabrsav/pycozmo_PROVA`) per continuare il lavoro su Cozmo e la sua personalità.
- Per tutte le regole operative su **DOOGEE S200**, ADB (`100.94.42.57:5555`), Tailscale split-tunneling, build ARM (`build.sh`), protocollo PyCozmo e sicurezza del robot fisico, leggi e segui `cozmo-s200/AGENTS.md`.
- **`cozmo-embodied-os/`** è esclusivamente un prototipo Python simulato (framing `AA55`): non usarlo mai come driver fisico per il robot reale.
- Conserva intatto il checkpoint locale `/home/vpsuser/safe-cozmo-20261115` (monitorato da `oh-project-audit` tramite `~/.local/state/progetti.json`).
- Conserva localmente `cozmo-s200/.private/debug.keystore` (gitignored) per mantenere la stessa firma negli aggiornamenti APK su DOOGEE S200; non committare mai chiavi, credenziali, APK o artefatti di build.
