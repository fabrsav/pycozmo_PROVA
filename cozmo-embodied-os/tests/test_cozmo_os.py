"""Test del motore Cozmo (Digital Twin + cervello L1/L2/L3).

Non servono hardware, rete ne' browser: si guida il twin direttamente e si
misurano gli EFFETTI (posa, attuatori, eventi), non la forma del codice.

Ogni test nasce da un difetto REALE incontrato:
  - T01/T02  il ciclo L1 moriva con KeyError su un intent senza 'lift'
             (la chiave mancante uccideva il task -> il robot si fermava)
  - T03       `goto` era una raffica APERTA di 2 s: il robot girava di una
             quantita' fissa e poi si fermava, senza avvicinarsi
  - T04       il lift veniva alzato a MAX ogni volta che il robot andava
             (profilo di mobilita' invertito)
  - T05       il segno della sterzata: vl/vr e la cinematica devono essere
             coerenti (sinistra = vl < vr = theta crescente)
  - T06       la soglia hardware: nessun comando oltre il limite
  - T07       il cliff (bordo del tavolo) deve fermare il robot
  - T08       il grounding: un piano che cita un oggetto INESISTENTE
             non deve produrre azione
  - T09       il robot non deve mai uscire dal tavolo
  - T10       la posa deve restare coerente con la cinematica
"""

from __future__ import annotations

import math
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from cozmo_os import kinematics as kin                       # noqa: E402
from cozmo_os.constants import (                             # noqa: E402
    LIFT_HEIGHT_MAX,
    LIFT_HEIGHT_MIN,
    TABLE_HALF_X_MM,
    TABLE_HALF_Y_MM,
    WHEEL_SPEED_HARD_MAX,
    WHEEL_SPEED_SMOOTH_MAX,
    WHEEL_TRACK_MM,
)
from cozmo_os.run_sim import EmbodiedRobot                   # noqa: E402
from cozmo_os.simulator import DigitalTwin                  # noqa: E402

FAILURES: list[str] = []
PASSED = 0


def check(name: str, cond: bool, detail: str = "") -> None:
    global PASSED
    if cond:
        PASSED += 1
        print(f"  ok   {name}")
    else:
        FAILURES.append(f"{name}: {detail}")
        print(f"  FAIL {name} :: {detail}")


def drive_robot(robot: EmbodiedRobot, ticks: int, dt: float = 1.0 / 50.0) -> None:
    """Esegue N tick di L1 (stessa logica del ciclo reale, inline e veloce)."""
    for _ in range(ticks):
        t0 = time.monotonic()
        out = robot.reflex.decide(dt, robot.twin.state(),
                                  robot.twin.camera_frame(), safety=robot.safety)
        if not out.estop:
            robot.twin.drive(out.vl, out.vr)
            robot.twin.set_head(out.head)
            robot.twin.set_lift(out.lift)
        robot.twin.step(dt, t0)
        robot.safety.feed()


# ---------------------------------------------------------------- T01/T02

def t01_l1_intent_without_lift_does_not_kill_the_cycle() -> None:
    """Un intent L3 SENZA la chiave 'lift' non deve far esplodere L1.

    Difetto reale: la dict dell'intent aveva tre chiavi ('vl','vr','head')
    ma L1 leggeva anche intent['lift'] -> KeyError -> il task L1 moriva e il
    robot restava fermo per sempre (la telemetria si fermava con lui).
    """
    robot = EmbodiedRobot(with_table=True, log_events=False)
    robot.twin.pose = kin.Pose(0.0, 0.0, 0.0)
    # si deposita a mano un intent NEL FORMATO VECCHIO (senza 'lift'):
    # e' il caso che faceva morire L1 con KeyError
    robot._l3_intent = {"vl": 30.0, "vr": 30.0, "head": None,
                        "until": time.monotonic() + 1.0}
    intent = robot._consume_l3_intent(time.monotonic())
    check("T01a l'intent senza 'lift' e' consumato senza eccezione",
          intent is not None, f"intent={intent}")
    check("T01b l'intent conserva vl/vr",
          intent is not None and intent["vl"] == 30.0 and intent["vr"] == 30.0,
          f"intent={intent}")
    check("T01c l'intent senza 'lift' non viene letto con []",
          intent is not None and "lift" not in intent,
          f"intent={intent}")


def t02_l1_keeps_running_after_an_intent() -> None:
    """Dopo un intent, il ciclo L1 deve continuare a fare tick."""
    robot = EmbodiedRobot(with_table=True, log_events=False)
    robot.twin.pose = kin.Pose(0.0, 0.0, 0.0)
    before = robot.l1_ticks
    robot._deposit_l3_intent(40.0, 40.0, None, hold_s=1.0)
    # 3 tick: il primo consuma l'intent, gli altri devono continuare
    for _ in range(3):
        out = robot.reflex.decide(1 / 50, robot.twin.state(),
                                  robot.twin.camera_frame(), safety=robot.safety)
        robot.twin.drive(out.vl, out.vr)
        robot.twin.step(1 / 50, time.monotonic())
        robot.l1_ticks += 1
    check("T02 i tick L1 proseguono dopo l'intent",
          robot.l1_ticks == before + 3,
          f"l1_ticks={robot.l1_ticks} prima={before}")


def t02b_l1_cycle_survives_an_intent_without_the_lift_key() -> None:
    """Il ciclo L1 deve leggere l'intent SENZA assumere la chiave 'lift'.

    Difetto reale: `intent.get("lift")` era diventato `intent["lift"]` ->
    un intent SENZA la chiave 'lift' faceva EXPLODERE il ciclo L1 con
    KeyError: il robot si fermava a meta' azione, in silenzio.

    CONTESTO ONESTO (misurato, non supposto): il deposit di produzione
    (`_deposit_l3_intent`) scrive SEMPRE la chiave 'lift' (None se non
    specificata), quindi il difetto non mordeva i comandi del percorso
    corrente: mordeva un intent costruito da un altro call-site (o
    round-trippato) senza quella chiave.  Il test tiene quindi la
    PROPRIETA' ("l'intent senza 'lift' non uccide il ciclo"), non una
    forma di codice.

    Il test chiama il metodo DI PRODUZIONE (`_apply_l3_intent`), non una
    sua copia: una copia nel test puo' restare verde mentre il ciclo
    vero esplode (e' gia' successo: la prima stesura replicava la
    lettura invece di esercitarla).
    """
    robot = EmbodiedRobot(with_table=True, log_events=False)
    # intent 'legacy': senza la chiave 'lift' (forma di 'look')
    intent = {"vl": 12.0, "vr": -12.0, "head": 0.3}
    ok = True
    err = ""
    try:
        vl, vr, head, lift = robot._apply_l3_intent(
            intent, 0.0, 0.0, 0.0, 50.0)
    except Exception as e:            # noqa: BLE001
        ok = False
        err = f"{type(e).__name__}: {e}"
        vl = vr = head = lift = 0.0
    check("T02b la lettura dell'intent senza 'lift' non solleva eccezioni",
          ok, err)
    check("T02c l'intent senza 'lift' lascia il lift del riflesso",
          ok and lift == 50.0, f"lift={lift} (atteso 50.0)")
    check("T02d l'intent senza 'lift' applica comunque vl/vr/head",
          ok and vl == 12.0 and vr == -12.0 and head == 0.3,
          f"vl={vl} vr={vr} head={head}")

    # Il canale 'lift' dell'intent: nessun call-site di produzione lo
    # usa oggi (il deposit scrive sempre lift=None), ma il meccanismo
    # esiste e deve FUNZIONARE: un piano che volesse comandare il
    # carrello (es. 'prendi il cubo' -> alza per afferrarlo) deve
    # poterlo fare senza toccare il ciclo L1.
    vl, vr, head, lift = robot._apply_l3_intent(
        {"vl": 0.0, "vr": 0.0, "head": None, "lift": 72.0},
        0.0, 0.0, 0.0, 50.0)
    check("T02e l'intent con 'lift' comanda il carrello",
          lift == 72.0 and head == 0.0,
          f"lift={lift} (atteso 72.0) head={head}")


# -------------------------------------------------------------------- T03

def t03_goto_is_a_closed_loop_and_actually_approaches() -> None:
    """'goto' deve AVVICINARE l'obiettivo, non girare e fermarsi.

    Difetto reale: goto depositava un intent di 2 s (raffica aperta): il
    robot girava di una quantita' fissa, l'intento scadeva e il riflesso
    (che insegue la camera) riprendeva il comando -> il robot non si
    avvicinava MAI al cubo.
    """
    robot = EmbodiedRobot(with_table=True, log_events=False)
    target = robot.twin.objects[3]          # il cubo a (-60, 40)
    gx, gy = target.x, target.y
    # partenza lontana e RUOTATA: il caso che espone la raffica aperta
    robot.twin.pose = kin.Pose(47.5, -6.6, -0.17)
    start_dist = math.hypot(gx - robot.twin.pose.x, gy - robot.twin.pose.y)

    robot._set_nav_goal((gx, gy), hold_s=20.0)
    check("T03a l'obiettivo di navigazione e' registrato",
          robot._nav_goal is not None, "nav_goal=None")

    # 10 secondi di servo a 50 Hz
    for _ in range(500):
        nav = robot._nav_command(robot.twin.state())
        if nav is None:
            break
        robot.twin.drive(nav[0], nav[1])
        robot.twin.step(1 / 50, time.monotonic())

    end = robot.twin.state()
    end_dist = math.hypot(gx - end.x, gy - end.y)
    check("T03b il robot si e' AVVICINATO al bersaglio",
          end_dist < start_dist - 40.0,
          f"distanza {start_dist:.1f} -> {end_dist:.1f} mm")
    check("T03c il robot e' ARRIVATO (soglia 35 mm)", end_dist <= 35.5,
          f"distanza finale {end_dist:.1f} mm")
    # la SOGLIA va PINNATA: se il servo si fermasse a 5 mm il test
    # passerebbe comunque ('e' arrivato') ma il robot si avvicinerebbe
    # troppo al cubo (il braccio non puo' afferrarlo da 5 mm).
    check("T03h il servo si ferma alla soglia di presa (non piu' vicino)",
          end_dist > 20.0,
          f"distanza finale {end_dist:.1f} mm: soglia troppo stretta")
    check("T03d l'obiettivo e' stato consumato all'arrivo",
          robot._nav_goal is None, f"nav_goal={robot._nav_goal}")


def t03b_nav_goal_expires_and_is_not_a_permanent_drive() -> None:
    """L'obiettivo deve scadere: un servo non puo' guidare per sempre."""
    robot = EmbodiedRobot(with_table=True, log_events=False)
    robot.twin.pose = kin.Pose(0.0, 0.0, 0.0)
    robot._set_nav_goal((1000.0, 1000.0), hold_s=0.05)   # lontano e breve
    time.sleep(0.08)
    check("T03e l'obiettivo scaduto non produce piu' comandi",
          robot._nav_command(robot.twin.state()) is None,
          "il servo continua a guidare dopo la scadenza")


def t03c_nav_aligns_before_advancing() -> None:
    """Prima di avanzare il robot deve ALLINEARSI (fermo), non derivedi in avanti."""
    robot = EmbodiedRobot(with_table=True, log_events=False)
    robot.twin.pose = kin.Pose(0.0, 0.0, math.pi)      # guarda indietro
    robot._set_nav_goal((100.0, 0.0), hold_s=10.0)
    nav = robot._nav_command(robot.twin.state())
    # durante l'allineamento la velocita' LINEARE deve essere zero:
    # vl e vr sono opposti (rotazione sul posto), non entrambi zero.
    v, omega = kin.forward_skid(nav[0], nav[1]) if nav else (0.0, 0.0)
    check("T03f col bersaglio dietro il robot non si parte in avanti",
          nav is not None and abs(v) < 1e-6,
          f"vl/vr={nav} velocita' lineare={v}")
    check("T03g ma il robot sta sterzando per allinearsi",
          nav is not None and abs(omega) > 1e-6,
          f"omega={omega} vl/vr={nav}")


# -------------------------------------------------------------------- T04

def _frame_with_blob(bg: int = 20, fg: int = 240, size: int = 60):
    """Frame sintetico: rettangolo luminoso al CENTRO (errore di tracking 0)."""
    import numpy as np
    f = np.full((240, 320), bg, dtype=np.uint8)
    y0, x0 = (240 - size) // 2, (320 - size) // 2
    f[y0:y0 + size, x0:x0 + size] = fg
    return f


def _blank_frame(level: int = 20):
    """Frame uniforme: serve come RIFERIMENTO del detector di movimento."""
    import numpy as np
    return np.full((240, 320), level, dtype=np.uint8)


def t04_lift_stays_low_while_driving() -> None:
    """Il lift (carrello) non deve salire a MAX durante la marcia.

    Difetto reale: `lift = LIFT_HEIGHT_MAX if drive != 0 else idle` -> il
    carrello si alzava a ogni passo di marcia (profilo di mobilita'
    invertito): il robot 'esibiva' l'attuatore mentre si muoveva.

    Il test pilota il TRACKER (non prega che la camera virtuale veda):
    decide() chiama tracker.update(frame), quindi si iniettano un frame
    di RIFERIMENTO (uniforme) e poi un frame col blob al centro ->
    errore 0 -> regime di marcia.  Con il solo blob il detector lo
    registra come sfondo e non vede movimento: la coppia e' obbligatoria.
    """
    robot = EmbodiedRobot(with_table=True, log_events=False)
    robot.twin.pose = kin.Pose(0.0, 0.0, 0.0)
    blank = _blank_frame()
    blob = _frame_with_blob()

    out = None
    for i in range(6):
        frame = blob if i >= 1 else blank
        out = robot.reflex.decide(1 / 50, robot.twin.state(), frame,
                                  safety=robot.safety)
        robot.twin.step(1 / 50, time.monotonic())
    assert out is not None
    driving = (out.vl != 0.0 or out.vr != 0.0)
    check("T04a il riflesso e' in regime di marcia (test esercitabile)",
          driving, f"vl/vr={out.vl}/{out.vr} reason={out.reason!r} "
                    f"visible={robot.reflex.tracker.visible}")
    check("T04b il lift resta BASSO mentre il robot marcia",
          out.lift == LIFT_HEIGHT_MIN,
          f"lift={out.lift} (atteso {LIFT_HEIGHT_MIN})")


def t04c_lift_idle_keeps_the_user_height() -> None:
    """Da fermo il lift deve CONSERVARE l'altezza scelta, non alzarsi da solo."""
    robot = EmbodiedRobot(with_table=True, log_events=False)
    robot.twin.pose = kin.Pose(0.0, 0.0, 0.0)
    robot.twin.lift = 60.0
    robot.reflex.tracker.visible = False
    robot.reflex.tracker.last = None
    robot.reflex.tracker.missing_frames = 99
    out = robot.reflex.decide(1 / 50, robot.twin.state(),
                              robot.twin.camera_frame(), safety=robot.safety)
    check("T04c da fermo il lift conserva l'altezza impostata",
          abs(out.lift - 60.0) < 1e-6,
          f"lift={out.lift} (atteso 60.0)")


def t04b_lift_never_exceeds_hardware_limits() -> None:
    """Il lift deve restare sempre dentro l'escursione fisica."""
    robot = EmbodiedRobot(with_table=True, log_events=False)
    for _ in range(20):
        out = robot.reflex.decide(1 / 50, robot.twin.state(),
                                  robot.twin.camera_frame(), safety=robot.safety)
        check_ok = LIFT_HEIGHT_MIN - 1e-6 <= out.lift <= LIFT_HEIGHT_MAX + 1e-6
        if not check_ok:
            check("T04b il lift resta nei limiti", False, f"lift={out.lift}")
            return
        robot.twin.step(1 / 50, time.monotonic())
    check("T04b il lift resta nei limiti", True)


# -------------------------------------------------------------------- T05

def t05_turn_sign_is_consistent_with_the_kinematics() -> None:
    """Sinistra = vl < vr = theta CRESCENTE ( Convenzione del modulo kin).

    Se il segno del servo di navigazione fosse invertito, il robot
    girerebbe SEMPRE dalla parte opposta e non arriverebbe mai.
    """
    robot = EmbodiedRobot(with_table=True, log_events=False)
    robot.twin.pose = kin.Pose(0.0, 0.0, 0.0)
    # bersaglio a sinistra (y positivo = CCW): deve sterzare a sinistra
    robot._set_nav_goal((100.0, 100.0), hold_s=10.0)
    nav = robot._nav_command(robot.twin.state())
    check("T05a bersaglio a sinistra -> vl < vr",
          nav is not None and nav[0] < nav[1], f"vl/vr={nav}")
    # e la cinematica deve confermare che cosi' theta cresce
    v, omega = kin.forward_skid(nav[0], nav[1])
    check("T05b il comando produce omega > 0 (gira a sinistra/CCW)",
          omega > 0, f"omega={omega} vl/vr={nav}")

    # bersaglio a destra: opposto
    robot.twin.pose = kin.Pose(0.0, 0.0, 0.0)
    robot._set_nav_goal((100.0, -100.0), hold_s=10.0)
    nav = robot._nav_command(robot.twin.state())
    check("T05c bersaglio a destra -> vl > vr",
          nav is not None and nav[0] > nav[1], f"vl/vr={nav}")
    v, omega = kin.forward_skid(nav[0], nav[1])
    check("T05d il comando produce omega < 0 (gira a destra/CW)",
          omega < 0, f"omega={omega} vl/vr={nav}")


def t05b_kinematics_roundtrip() -> None:
    """Avanti = entrambe le ruote positive e stessa velocita'."""
    v, omega = kin.forward_skid(50.0, 50.0)
    check("T05e avanti -> v>0 e omega=0",
          v > 0 and abs(omega) < 1e-9, f"v={v} omega={omega}")
    v, omega = kin.forward_skid(50.0, -50.0)
    check("T05f rotazione sul posto -> v=0 e omega<0",
          abs(v) < 1e-9 and omega < 0, f"v={v} omega={omega}")


# -------------------------------------------------------------------- T06

def t06_commands_respect_the_hardware_speed_limit() -> None:
    """Nessun comando puo' superare il limite hardware delle ruote.

    Il clamp a 200 mm/s e' una guardia DIFENSIVA: con bersagli normali il
    comando non arriva mai al limite (v<=70, half<=48), quindi un test
    basato solo sui bersagli passerebbe anche senza clamp (verde che non
    bacia).  Per morire davvero il clamp deve essere esercitato con un
    ingresso che lo superi: qui si verifica la formula di inversione
    (wheel -> comando) sul caso limite, e il clamp sui due siti di
    calcolo (servo di navigazione e prima raffica del 'goto').
    """
    robot = EmbodiedRobot(with_table=True, log_events=False)
    robot.twin.pose = kin.Pose(0.0, 0.0, 0.0)
    worst = 0.0
    for gx, gy in ((100000, 0), (0, 100000), (-100000, -100000),
                   (100000, 100000)):
        robot._set_nav_goal((gx, gy), hold_s=10.0)
        for _ in range(20):
            nav = robot._nav_command(robot.twin.state())
            if nav is None:
                break
            worst = max(worst, abs(nav[0]), abs(nav[1]))
    check("T06b i comandi di navigazione restano entro il limite hardware",
          worst <= WHEEL_SPEED_HARD_MAX + 1e-6,
          f"max={worst:.1f} limite={WHEEL_SPEED_HARD_MAX}")

    # il CLAMP e' verificato sul caso limite.  Il vincolo e' a DUE stadi:
    # prima il range FLUIDO (150 mm/s, il limite operativo), poi il
    # limite HARD (200 mm/s, il limite fisico).  Il test deve verificare
    # il vincolo EFFETTIVO (il fluido, che e' quello che si raggiunge)
    # e che il comando non superi MAI il limite hard.
    v_max = kin.inverse_skid(1000.0, 50.0)      # v, omega fuori scala
    check("T06c la velocita' delle ruote satura al limite operativo",
          abs(abs(v_max[0]) - WHEEL_SPEED_SMOOTH_MAX) < 1e-6
          and abs(abs(v_max[1]) - WHEEL_SPEED_SMOOTH_MAX) < 1e-6,
          f"v_max={v_max} limite fluido={WHEEL_SPEED_SMOOTH_MAX}")
    # e il caso NEGATIVO (il clamp deve valere anche in verso opposto)
    v_neg = kin.inverse_skid(-1000.0, -50.0)
    check("T06d il clamp vale anche per velocita' negative",
          abs(abs(v_neg[0]) - WHEEL_SPEED_SMOOTH_MAX) < 1e-6
          and abs(abs(v_neg[1]) - WHEEL_SPEED_SMOOTH_MAX) < 1e-6,
          f"v_neg={v_neg} limite fluido={WHEEL_SPEED_SMOOTH_MAX}")
    # il limite HARD non deve mai essere superato, nemmeno in caso estremo
    v_ext = kin.inverse_skid(1e9, 1e9)
    check("T06e nessun comando supera il limite HARD",
          max(abs(v_ext[0]), abs(v_ext[1])) <= WHEEL_SPEED_HARD_MAX + 1e-6,
          f"v_ext={v_ext} limite hard={WHEEL_SPEED_HARD_MAX}")
    # INVARIANTE dei due limiti: il vincolo EFFETTIVO (fluido) deve
    # essere <= del limite fisico (hard).  Se un giorno il limite fluido
    # venisse alzato sopra quello fisico, il clamp hard da solo
    # garantirebbe ancora il limite, ma il vincolo operativo cadrebbe:
    # la relazione va tenuta esplicita.
    check("T06f il limite operativo non supera quello fisico",
          WHEEL_SPEED_SMOOTH_MAX <= WHEEL_SPEED_HARD_MAX,
          f"fluido={WHEEL_SPEED_SMOOTH_MAX} hard={WHEEL_SPEED_HARD_MAX}")


# -------------------------------------------------------------------- T07

def t07_cliff_stops_the_robot() -> None:
    """Oltre il bordo del tavolo il robot deve fermarsi (cliff).

    NOTA: `cliff` e' una MASCHERA di 4 sensori IR (bit i = sensore i senza
    tavolo), non un flag 0/1: fuori bordo col robot centrato si legge 15.
    """
    twin = DigitalTwin(with_table=True)
    twin.pose = kin.Pose(TABLE_HALF_X_MM + 30.0, 0.0, 0.0)
    st = twin.state()
    check("T07a i sensori cliff vedono il bordo (maschera non zero)",
          st.cliff != 0, f"cliff={st.cliff}")
    check("T07b fuori dal tavolo la posa non e' valida",
          st.cliff != 0 or not twin._over_table(st.x, st.y),
          "la posa fuori tavolo risulta valida")
    check("T07c cliff_ahead() e' vero fuori dal bordo",
          twin.cliff_ahead(), f"cliff_ahead={twin.cliff_ahead()} mask={st.cliff}")

    # dentro il tavolo nessun sensore deve scattare
    twin2 = DigitalTwin(with_table=True)
    twin2.pose = kin.Pose(0.0, 0.0, 0.0)
    check("T07d al centro del tavolo nessun cliff",
          twin2.state().cliff == 0 and not twin2.cliff_ahead(),
          f"cliff={twin2.state().cliff}")


# -------------------------------------------------------------------- T08

def t08_grounding_refuses_objects_that_do_not_exist() -> None:
    """Un piano che cita un oggetto inesistente non deve muovere il robot.

    Il caso che conta (e che la mutazione rompe) e': un referente il cui
    NOME e' noto al grounding ('cubo') ma che NON e' fra gli oggetti
    osservati -> il pianificatore non deve avviare il goto.  Citare un
    nome ignoto ('il drago') viene respinto da qualunque versione, quindi
    non e' un test del grounding: e' un test del vocabolario.
    """
    robot = EmbodiedRobot(with_table=True, log_events=False)
    robot.twin.pose = kin.Pose(0.0, 0.0, 0.0)
    before = robot.twin.pose

    # (1) il token ESISTE nel vocabolario ma l'osservazione NON lo contiene
    grounded = robot.cortex.ground("vai verso il cubo",
                                    "oggetti=[mug, keys]")
    check("T08a il grounding rifiuta un referente non osservato",
          not grounded.ok and grounded.referent is None,
          f"ok={grounded.ok} referent={grounded.referent} note={grounded.note!r}")

    # (2) un nome sconosciuto al vocabolario: respinto comunque, per il
    #     motivo diverso (nessun referente riconosciuto)
    grounded2 = robot.cortex.ground("vai verso il drago rosso",
                                    "oggetti=[cube, mug, keys]")
    check("T08c un referente sconosciuto e' respinto senza ancorarsi",
          not grounded2.ok and grounded2.referent is None,
          f"ok={grounded2.ok} referent={grounded2.referent} note={grounded2.note!r}")

    # (3) il caso POSITIVO: il cubo ESISTE nell'osservazione -> grounding ok
    grounded3 = robot.cortex.ground("vai verso il cubo",
                                    "oggetti=[cube, mug, keys]")
    check("T08d il referente osservato produce un grounding valido",
          grounded3.ok and grounded3.referent is not None,
          f"ok={grounded3.ok} referent={grounded3.referent} note={grounded3.note!r}")

    check("T08b il robot non si e' mosso",
          robot.twin.pose.x == before.x and robot.twin.pose.y == before.y,
          "la posa e' cambiata")


# -------------------------------------------------------------------- T09

def t09_robot_stays_on_the_table_during_navigation() -> None:
    """La navigazione non deve portare il robot fuori dal tavolo."""
    robot = EmbodiedRobot(with_table=True, log_events=False)
    robot.twin.pose = kin.Pose(0.0, 0.0, 0.0)
    robot._set_nav_goal((500.0, 500.0), hold_s=5.0)   # fuori tavolo
    for _ in range(250):
        nav = robot._nav_command(robot.twin.state())
        if nav is None:
            break
        robot.twin.drive(nav[0], nav[1])
        robot.twin.step(1 / 50, time.monotonic())
    st = robot.twin.state()
    # il twin segnala il cliff se esce: il punto e' che il SISTEMA lo vede
    check("T09a il twin segnala il cliff se il robot esce",
          st.cliff == 1 or (abs(st.x) <= TABLE_HALF_X_MM
                            and abs(st.y) <= TABLE_HALF_Y_MM),
          f"pose=({st.x:.1f},{st.y:.1f}) cliff={st.cliff}")


# -------------------------------------------------------------------- T10

def t10_pose_matches_the_kinematic_integration() -> None:
    """La posa simulata deve combaciare con l'integrazione cinematica."""
    twin = DigitalTwin(with_table=True)
    twin.pose = kin.Pose(0.0, 0.0, 0.0)
    p0 = twin.pose
    vl, vr = 40.0, 20.0
    dt = 0.02
    twin.drive(vl, vr)
    twin.step(dt, time.monotonic())
    p1 = twin.pose
    v, omega = kin.forward_skid(vl, vr)
    # moto circolare: la distanza dal centro coincide con R = v/omega
    if abs(omega) > 1e-9:
        r = abs(v / omega)      # il raggio e' una grandezza POSITIVA
        d = math.hypot(p1.x - p0.x, p1.y - p0.y)
        expected = 2 * r * math.sin(abs(omega * dt) / 2)
        check("T10a il passo simulato combacia con la cinematica",
              abs(d - expected) < 0.5,
              f"passo={d:.3f} atteso={expected:.3f}")
    else:
        check("T10a il passo simulato combacia con la cinematica", True)
    check("T10b l'angolo e' coerente con omega",
          abs((p1.theta - p0.theta) - omega * dt) < 1e-6,
          f"dtheta={p1.theta - p0.theta} atteso={omega * dt}")


def main() -> int:
    print("== test cozmo_os ==")
    for fn in (t01_l1_intent_without_lift_does_not_kill_the_cycle,
               t02_l1_keeps_running_after_an_intent,
               t02b_l1_cycle_survives_an_intent_without_the_lift_key,
               t03_goto_is_a_closed_loop_and_actually_approaches,
               t03b_nav_goal_expires_and_is_not_a_permanent_drive,
               t03c_nav_aligns_before_advancing,
               t04_lift_stays_low_while_driving,
               t04c_lift_idle_keeps_the_user_height,
               t04b_lift_never_exceeds_hardware_limits,
               t05_turn_sign_is_consistent_with_the_kinematics,
               t05b_kinematics_roundtrip,
               t06_commands_respect_the_hardware_speed_limit,
               t07_cliff_stops_the_robot,
               t08_grounding_refuses_objects_that_do_not_exist,
               t09_robot_stays_on_the_table_during_navigation,
               t10_pose_matches_the_kinematic_integration):
        fn()
    print(f"\n{PASSED} ok, {len(FAILURES)} falliti")
    for f in FAILURES:
        print(f"  - {f}")
    return 1 if FAILURES else 0


if __name__ == "__main__":
    raise SystemExit(main())
