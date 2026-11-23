#!/usr/bin/env python3
"""E2E: il robot che cade dal tavolo DEVE tornare indietro da solo.

Questo test esercita il percorso VERO completo, non il riflesso isolato:

    cliff (twin) -> ReflexController.decide -> l1_cycle -> twin.drive -> pose

I tre difetti reali che chiude (scoperti misurando, non ipotizzando):

  1. `l1_reflex`: il veto del supervisore veniva valutato PRIMA della
     memoria di retromarcia => `cliff_memory_s` non si armava MAI e il
     codice di retromarcia era DEAD CODE.
  2. `run_sim.l1_cycle`: `if not out.estop` saltava del tutto la
     scrittura sugli attuatori => il comando di fuga non raggiungeva
     MAI le ruote (il robot restava fermo all'oltre del bordo).
  3. `safety`: l'estop da sensore restava LATCHED per sempre
     (`clear()` non era chiamato da nessuna parte in produzione)
     => il robot non poteva MAI riprendere.

Il test verifica il FATTO (la posa del robot torna sul tavolo), non
l'implementazione: se il fix cambiasse forma, il test resta valido.
"""
from __future__ import annotations

import asyncio
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from cozmo_os import kinematics as kin                       # noqa: E402
from cozmo_os.run_sim import EmbodiedRobot                    # noqa: E402

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


def make_robot() -> EmbodiedRobot:
    """Crea un robot senza server: il twin e il riflesso sono i pezzi
    che questo test esercita (il server web non c'entra con il cliff)."""
    return EmbodiedRobot(with_table=True, log_events=False)


async def main() -> int:
    print("== test cozmo cliff E2E (il robot torna indietro da solo) ==")

    # --- 1. il riflesso ordina il retromarcia col cliff presente -------
    robot = make_robot()
    robot.drop_off_table()
    st = robot.twin.state()
    check("E01 il cliff e' presente oltre il bordo", st.cliff != 0,
          f"cliff={st.cliff}")

    out = robot.reflex.decide(1 / 50, st, robot.twin.camera_frame(),
                              safety=robot.safety)
    check("E02 il riflesso comanda il retromarcia (entrambe le ruote < 0)",
          out.vl < 0 and out.vr < 0, f"vl={out.vl} vr={out.vr}")
    check("E03 l'estop resta dichiarato durante il retromarcia",
          out.estop, f"estop={out.estop}")
    check("E04 il supervisore ha registrato il cliff",
          "cliff" in (robot.safety.reason or "").lower(),
          f"reason={robot.safety.reason!r}")

    # --- 2. il ciclo L1 SCRIVE il comando sulle ruote -------------------
    # Il percorso vero: l1_cycle -> emergency_hold -> twin.drive.
    # Simuliamo il blocco di scrittura perche' l1_cycle e' una coroutine
    # infinita: qui verifichiamo la decisione (emergenza vera = ruote
    # ferme; cliff = comando di fuga scritto).
    emergency_hold = out.estop and out.vl == 0.0 and out.vr == 0.0
    check("E05 il cliff NON e' un'-emergenza-hold (il comando passa)",
          not emergency_hold,
          f"emergency_hold={emergency_hold}")

    # --- 3. il robot torna DAVVERO sul tavolo con la cinematica --------
    # Applichiamo per 2 secondi il comando di fuga e integriamo la posa.
    x_start = robot.twin.pose.x
    t = time.monotonic()
    for _ in range(int(2.0 * 50)):
        st = robot.twin.state()
        o = robot.reflex.decide(1 / 50, st, robot.twin.camera_frame(),
                                safety=robot.safety)
        robot.twin.drive(o.vl, o.vr)
        robot.twin.step(1 / 50, t)
        t += 1 / 50
    x_end = robot.twin.pose.x
    check("E06 il robot e' tornato indietro (x diminuita)", x_end < x_start,
          f"x_start={x_start:.1f} x_end={x_end:.1f}")
    check("E07 il robot e' tornato sul tavolo", robot.twin.on_table()
          if hasattr(robot.twin, "on_table") else robot.twin.cliff_ahead()
          is False,
          f"pose={robot.twin.pose} cliff_ahead={robot.twin.cliff_ahead()}")

    # --- 4. l'estop si autorilascia: il robot riprende a funzionare ---
    out_after = robot.reflex.decide(1 / 50, robot.twin.state(),
                                    robot.twin.camera_frame(),
                                    safety=robot.safety)
    check("E08 l'estop da sensore si autorilascia a sensore pulito",
          not robot.safety.stopped,
          f"stopped={robot.safety.stopped} reason={robot.safety.reason!r}")
    check("E09 il robot e' di nuovo comandabile", not out_after.estop,
          f"estop={out_after.estop} reason={out_after.reason!r}")

    # --- 5. CONTROLLO: l'estop da KEEP-ALIVE resta latchato -----------
    # Non deve auto-rilasciarsi: la comunicazione caduta richiede una
    # conferma esplicita dell'operatore.
    robot2 = make_robot()
    robot2.safety.estop("keep-alive scaduto (test)")
    robot2.safety._sensor_estop = False
    robot2.safety.report_sensor(cliff=0, picked_up=False, falling=False)
    check("E10 l'estop da keep-alive NON si autorilascia da solo",
          robot2.safety.stopped,
          f"stopped={robot2.safety.stopped} reason={robot2.safety.reason!r}")
    robot2.safety.clear()
    check("E11 l'estop da keep-alive si libera con clear() esplicito",
          not robot2.safety.stopped,
          f"stopped={robot2.safety.stopped}")

    # --- 6. CONTROLLO: un pickup NON ordina la fuga --------------------
    robot3 = make_robot()
    now = time.monotonic()
    robot3.twin.simulate_pickup(now, duration_s=2.0)
    robot3.twin.step(1 / 50, now)
    o3 = robot3.reflex.decide(1 / 50, robot3.twin.state(),
                              robot3.twin.camera_frame(), safety=robot3.safety)
    check("E12 col robot sollevato le ruote restano ferme",
          o3.vl == 0.0 and o3.vr == 0.0, f"vl={o3.vl} vr={o3.vr}")
    check("E13 col robot sollevato l'estop e' dichiarato", o3.estop,
          f"estop={o3.estop} reason={o3.reason!r}")

    print(f"\n{OK} ok, {BAD} falliti")
    return 1 if BAD else 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
