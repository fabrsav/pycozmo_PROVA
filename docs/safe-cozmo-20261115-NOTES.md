# CoZmo embodied OS — checkpoint locale (2026-11-15)

## Perché questo checkpoint esiste

Il progetto vive in `/home/vpsuser/GoogleDrive/Projects/cozmo-embodied-os`, ma al
momento della sua creazione **il mount di GoogleDrive era GIÙ**: quel path
era quindi lo **strato locale** su `/dev/sda1` (verificato con `findmnt -T`),
non il Drive remoto. Verificato inoltre con `rclone lsf gdrive:Projects/`:
il progetto **non esiste sul Drive remoto** — quindi nessuna versione remota
può coprire i sorgenti locali al rientro del mount.

Conseguenze:
- il lavoro **non** andrà perso al rientro del mount (non esiste una copia
  remota che possa sovrascrivere);
- ma il progetto è **fuori dalla sua casa prevista** e vive sul root disk
  (78% pieno): una pulizia del disco lo cancellerebbe senza allarme.

Per questo il checkpoint è su **disco locale** (`/home/vpsuser/safe-cozmo-20261115/`),
che sopravvive al flap del mount, ed è **registrato in `~/.local/state/progetti.json`**:
il guard `oh-project-audit` (ogni 30 min) controlla l'esistenza delle chiavi e
notifica (con escalation ogni 6h) se qualcosa sparisce.

## Cosa contiene

- `cozmo_os/` — i sorgenti VIVI al momento della consegna (l1_reflex.py,
  safety.py, run_sim.py contengono i tre fix di sicurezza).
- `tests/` — le 4 suite funzionali, il banco mutazionale della sicurezza
  (`mutate_cliff_safety.py`), il banco storico (`mutation_check.sh`) e il
  trigger unico (`run_all.sh`).
- `markers.txt` — i marker di CONTENUTO dei tre fix. Stanno **nel checkpoint**
  (non nello script) e sono verificati su TUTTI i file che li possono contenere:
  un marker scritto a mano nello script invecchia e bloccherebbe la catena.
- `CHECKSUMS.txt` — md5 di ogni file (escluso il checksum stesso).

## Comandi di ri-applicazione

```sh
# 1. verifica che il checkpoint sia integro PRIMA di usarlo
cd /home/vpsuser/safe-cozmo-20261115 && md5sum -c CHECKSUMS.txt

# 2. copia i sorgenti e i test nel progetto (idempotente per md5)
PROJ=/home/vpsuser/GoogleDrive/Projects/cozmo-embodied-os
cp -a cozmo_os "$PROJ/" && cp -a tests "$PROJ/"

# 3. verifica i marker nel file DEPLOYATO (tutti i file, non uno scelto)
while IFS= read -r m; do
  [ -z "$m" ] && continue
  grep -rqF "$m" "$PROJ/cozmo_os/" || echo "MANCA: $m"
done < markers.txt

# 4. ri-prova i guard (dal progetto, non dal checkpoint)
cd "$PROJ" && bash tests/run_all.sh
```

Il passo 4 è quello che decide: se i guard tornano verdi, il ri-applicatore ha
riposto la catena di sicurezza. Se sono rossi, **non** si dichiara chiuso.

## Stato al momento della consegna (misurato, non ricordato)

- suite funzionali: 42 + 41 + 13 + 9 = **105 ok, 0 falliti**
- banco modulo (`mutation_check.sh`): **9/9** mutazioni morse
- banco sicurezza (`mutate_cliff_safety.py`): **5/5** direzioni (control incluso)
- trigger unico `tests/run_all.sh`: **6/6 verde**
- md5 sorgenti chiave:
  - `cozmo_os/safety.py` = `8cf22eb80524320160af151e83dcb87d`
  - `cozmo_os/brain/l1_reflex.py` = `c91f848ed73874c34360f625ea85bcb9`
  - `cozmo_os/run_sim.py` = `dbd4b91422d25c425f5178d64900d967`
- mount Drive al momento della consegna: **GIÙ** (path = strato locale)

I tre md5 sopra sono i valori **misurati in questo momento**: se un check
futuro li ricalcola e differiscono, il progetto è cambiato e questo
checkpoint va rinnovato (fonti + NOTE + CHECKSUMS insieme, mai uno solo).
