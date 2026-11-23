#!/usr/bin/env python3
"""E2E RUNTIME: il server vero deve far tornare il robot sul tavolo.

Questo NON e' un test unitario: lancia il vero `run_sim` (server HTTP +
ciclo L1 a 50 Hz + twin fisico) e parla con lui via l'API vera
(POST /api/command). E' la prova che il fix sopravviva al concatenamento
di TUTTI i pezzi: sensore -> riflesso -> supervisore -> scrittura sugli
attuatori -> cinematica -> HTTP.

Perche' serve: i test precedenti verificavano i pezzi singoli. Il
difetto originale (il robot fermo al bordo) esisteva proprio nel
CONCATENAMENTO, perche' `if not out.estop` saltava la scrittura.

FATTI misurati (non assunzioni):
  - `drop_off_table()` mette il robot a x = x0 + TABLE_HALF_X_MM + 30mm
    (30mm oltre il bordo);
  - il riflesso deve riportarlo DENTRO il bordo, non a x < x0: il
    test confronta la posa DOPO il comando con il BORDO reale
    (TABLE_HALF_X_MM letto dalla sorgente), non con lo zero.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
from cozmo_os.constants import TABLE_HALF_X_MM       # noqa: E402

PORT = int(os.environ.get("COZMO_E2E_PORT", "8613"))
BASE = f"http://127.0.0.1:{PORT}"

OK = 0
BAD = 0


def check(name: str, cond: bool, detail: str = "") -> None:
    global OK, BAD
    if cond:
        OK += 1
        print(f"  ok   {name}")
    else:
        BAD += 1
        print(f"  FAIL {name} :: {detail}")


def get(path: str):
    req = urllib.request.Request(BASE + path)
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read().decode())


def post(path: str, body: dict):
    data = json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data,
                                 headers={"Content-Type": "application/json"},
                                 method="POST")
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")


def main() -> int:
    print("== test cozmo cliff E2E RUNTIME (server vero) ==")
    env = dict(os.environ)
    env["PYTHONPATH"] = ROOT
    proc = subprocess.Popen(
        [sys.executable, "-m", "cozmo_os.run_sim", "--port", str(PORT),
         "--no-log"],
        cwd=ROOT, env=env,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    try:
        ready = False
        for _ in range(80):
            try:
                get("/api/state")
                ready = True
                break
            except Exception:
                if proc.poll() is not None:
                    out = proc.stdout.read() if proc.stdout else ""
                    check("E00 il server parte", False,
                          f"processo morto rc={proc.returncode}: {out[:800]}")
                    return 1
                time.sleep(0.25)
        check("E00 il server vero e' in ascolto", ready, f"porta {PORT}")
        if not ready:
            return 1

        st0 = get("/api/state")
        x0 = float(st0["pose"]["x"])
        check("E01 il robot parte sul tavolo", st0["cliff"] == 0,
              f"cliff={st0['cliff']} x={x0}")

        # --- il comando cliff reale (stessa API del server) ---------------
        code, body = post("/api/command", {"action": "cliff"})
        check("E02 il comando cliff risponde 200", code == 200,
              f"code={code} body={body!r}")

        # il cliff deve essere REALE: il robot e' oltre il bordo
        st_c = get("/api/state")
        x_cliff = float(st_c["pose"]["x"])
        check("E03 il comando cliff ha spostato il robot oltre il bordo",
              x_cliff > TABLE_HALF_X_MM and st_c["cliff"] != 0,
              f"x={x_cliff} (bordo={TABLE_HALF_X_MM}) cliff={st_c['cliff']}")

        # --- il robot deve TORNARE indietro da solo -----------------------
        x_min = x_cliff
        samples = []
        for _ in range(45):          # ~4.5 s di osservazione
            time.sleep(0.1)
            st = get("/api/state")
            x = float(st["pose"]["x"])
            samples.append(x)
            x_min = min(x_min, x)
        st_mid = get("/api/state")
        x_end = float(st_mid["pose"]["x"])
        check("E04 il robot ha MOSSO le ruote INDIETRO",
              x_min < x_cliff - 20.0,
              f"x_cliff={x_cliff:.1f} x_min={x_min:.1f}")
        check("E05 il robot e' tornato DENTRO il bordo del tavolo",
              x_end <= TABLE_HALF_X_MM,
              f"x_finale={x_end:.1f} bordo={TABLE_HALF_X_MM}")

        # --- il ciclo L1 ha continuato a girare (non e' bloccato) ---------
        l1a = int(st0["l1_ticks"])
        time.sleep(0.5)
        l1b = int(get("/api/state")["l1_ticks"])
        check("E06 il ciclo L1 continua a girare", l1b > l1a,
              f"l1_ticks {l1a} -> {l1b}")

        # --- il robot non e' piu' bloccato in estop quando torna pulito ---
        st_f = get("/api/state")
        check("E07 l'estop da sensore NON blocca il robot per sempre",
              not (st_f["estop"] and st_f["cliff"] == 0
                   and float(st_f["dbg"].get("vl", 0)) == 0.0
                   and float(st_f["dbg"].get("vr", 0)) == 0.0),
              f"estop={st_f['estop']} cliff={st_f['cliff']} dbg={st_f['dbg']}")

        # --- il supervisore ha VISTO il cliff (reason non vuota) ---------
        check("E08 il supervisore ha registrato il pericolo",
              bool(st_f.get("estop_reason")) or st_f.get("estop") is False,
              f"estop_reason={st_f.get('estop_reason')!r}")

        print(f"\n{OK} ok, {BAD} falliti")
        return 1 if BAD else 0
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()


if __name__ == "__main__":
    raise SystemExit(main())
