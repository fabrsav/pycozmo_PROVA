#!/usr/bin/env python3
"""Banco MUTAZIONALE: i tre difetti devono ricomparire se li si rompe.

Un test che passa sul codice sano ma non e' mai stato visto fallire e'
un test mai provato. Qui si rompe, su COPIE ISOLATE (mai il vivo), ognuno
dei tre difetti reali, e si verifica che il test cada per NOME.

Le tre rotture (nell'ordine in cui sono state scoperte):

  1. `veto-first`  : il veto del supervisore torna PRIMA della memoria
                      di retromarcia -> `cliff_memory_s` non si arma ->
                      il retromarcia e' dead code.
  2. `no-write`    : `if not out.estop` saltala scrittura sugli
                      attuatori -> il comando non raggiunge le ruote.
  3. `latch`       : l'estop da sensore resta latched per sempre ->
                      il robot non riprende MAI.

CONTROLLO: la copia INTATTA deve passare TUTTI i test. Se il control
fallisce, la sandbox non rappresenta la produzione e ogni altra
direzione e' indistinguibile da un errore del banco.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "cozmo_os")
TESTS = os.path.join(ROOT, "tests")

MUTATIONS = [
    ("control", "copy-intatta", []),
    ("veto-first", "veto valutato PRIMA della memoria di retromarcia", [
        ("cozmo_os/brain/l1_reflex.py",
         """        # 3. memoria breve del cliff: retrocedere PRIMA di fare qualunque
        #    cosa. Il robot deve riportare le ruote dentro il tavolo.
        if self.cliff_memory_s > 0:""",
         """        # 3. memoria breve del cliff: retrocedere PRIMA di fare qualunque
        #    cosa. Il robot deve riportare le ruote dentro il tavolo.
        _vf_veto = safety.veto_command() if safety is not None else None
        if _vf_veto is not None:
            return ReflexOutput(0.0, 0.0, self._head_current, LIFT_HEIGHT_MIN,
                                True, "surprised", _vf_veto)
        if self.cliff_memory_s > 0:"""),
    ]),
    ("no-write", "la scrittura sugli attuatori saltata con l'estop", [
        ("cozmo_os/run_sim.py",
         """            emergency_hold = out.estop and out.vl == 0.0 and out.vr == 0.0
            if not emergency_hold:""",
         """            emergency_hold = out.estop and out.vl == 0.0 and out.vr == 0.0
            if not out.estop:"""),
    ]),
    ("latch", "l'estop da sensore resta latched per sempre", [
        ("cozmo_os/safety.py",
         """        if self._estop and self._sensor_estop and not (cliff or picked_up
                                                        or falling):
            self.clear()
            self._events.append("estop sensore rilasciato: sensore pulito")""",
         """        if False:
            self.clear()
            self._events.append("estop sensore rilasciato: sensore pulito")"""),
    ]),
    ("no-report", "il supervisore NON vede il cliff (report spostato dopo)", [
        ("cozmo_os/brain/l1_reflex.py",
         """        if safety is not None:
            safety.report_sensor(cliff=cliff, picked_up=picked_up,
                                 falling=falling)

        # 2. SICUREZZA:""",
         """        # 2. SICUREZZA:"""),
    ]),
]

SUITES = ["tests/test_cozmo_cliff_e2e.py",
          "tests/test_cozmo_cliff_runtime.py"]


def run(suite: str, root: str) -> tuple[int, str]:
    env = dict(os.environ)
    env["PYTHONPATH"] = root
    p = subprocess.run([sys.executable, os.path.join(root, suite)],
                       cwd=root, env=env, capture_output=True, text=True,
                       timeout=300)
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def main() -> int:
    print("== banco mutazionale: i tre difetti devono ricomparire ==")
    failures = 0
    for name, desc, patches in MUTATIONS:
        sandbox = tempfile.mkdtemp(prefix="cozmo-mut-", dir="/tmp/opencode")
        try:
            shutil.copytree(SRC, os.path.join(sandbox, "cozmo_os"))
            shutil.copytree(TESTS, os.path.join(sandbox, "tests"))
            applied = 0
            for rel, old, new in patches:
                path = os.path.join(sandbox, rel)
                with open(path, encoding="utf-8") as f:
                    txt = f.read()
                if txt.count(old) != 1:
                    print(f"  FAIL {name} :: ANCOR A VUOTO in {rel} "
                          f"(count={txt.count(old)})")
                    failures += 1
                    applied = -1
                    break
                with open(path, "w", encoding="utf-8") as f:
                    f.write(txt.replace(old, new))
                applied += 1
            if applied != len(patches):
                continue

            broken = []
            passed = []
            for suite in SUITES:
                rc, out = run(suite, sandbox)
                if rc == 0:
                    passed.append(suite)
                else:
                    # quali check sono caduti? (nome del check FAIL)
                    failed_names = [ln.strip() for ln in out.splitlines()
                                    if ln.strip().startswith("FAIL")]
                    broken.append((suite, failed_names))

            if name == "control":
                if passed:
                    print(f"  ok   control :: la copia intatta passa "
                          f"{len(passed)}/{len(SUITES)} suite")
                else:
                    print(f"  FAIL control :: la copia intatta FALLISCE "
                          f"(sandbox non rappresentativa): {passed}")
                    failures += 1
            else:
                if broken:
                    names = sorted({n for _, fs in broken for n in fs})
                    print(f"  ok   {name} :: cade per nome: "
                          f"{', '.join(n.split('::')[0].strip() for n in names[:6])}")
                else:
                    print(f"  FAIL {name} :: la rottura NON e' stata "
                          f"rilevata (il test non ha denti)")
                    failures += 1
        finally:
            shutil.rmtree(sandbox, ignore_errors=True)

    print(f"\n{'OK' if failures == 0 else 'FALLITI'}: "
          f"{len(MUTATIONS) - failures}/{len(MUTATIONS)} direzioni")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
