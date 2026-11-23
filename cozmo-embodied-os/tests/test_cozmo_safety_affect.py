"""Test del percorso REALE della sicurezza: cliff, umore, evento, comando HTTP.

Perche' un file SEPOLATO e non aggiunte a test_cozmo_os.py: la suite
esistente copriva la SIMULAZIONE (il twin letto direttamente), ma i
difetti reali erano nel PERCORSO DI PRODUZIONE:

  - il comando `cliff` della UI (`EmbodiedRobot.drop_off_table`) sollevava
    FrozenInstanceError: `Pose` e' un dataclass FROZEN, quindi
    `self.twin.pose.x = 999.0` non puo' funzionare. Il bottone "Soffio
    del tavolo" moriva con HTTP 000 e il cliff NON accadeva MAI: la
    sicurezza non era mai stata esercitata da quel percorso. T07
    leggeva `twin.pose` direttamente e passava VERDE: la prova
    esercitava il twin, non il comando.
  - x=999 non e' "cadere dal tavolo": e' la posizione DENTRO IL VUOTO.
    Il cliff e' una maschera di 4 sensori IR: il robot deve trovarsi
    appena oltre il bordo, non lontano.
  - gli eventi L2 erano livello-e non fronte: un cliff TENUTO per 0.5 s
    a 10 Hz generava 5 eventi "cliff" (la paura dipendeva dalla DURATA),
    e `tracker.visible` emetteva "object_moved" ogni tick mentre un
    oggetto era semplicemente IN VISTA: bastava una faccia ferma per
    saturare `curious` a 1.0.
  - un singolo cliff (+0.5 di paura) restava sotto la soglia di
    "spaventato" (0.6): il robot era NEUTRO davanti a un burrone.

Ogni test nasce da un difetto REALE, misurato dal vivo (HTTP 000 +
FrozenInstanceError nel log; mood che saturava in 10 s; estop False).
"""

from __future__ import annotations

import json
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from cozmo_os import kinematics as kin                       # noqa: E402
from cozmo_os.brain.l2_limbic import LimbicSystem            # noqa: E402
from cozmo_os.constants import TABLE_HALF_X_MM               # noqa: E402
from cozmo_os.run_sim import EmbodiedRobot                   # noqa: E402

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


def _robot() -> EmbodiedRobot:
    r = EmbodiedRobot(with_table=True, log_events=False)
    r.twin.pose = kin.Pose(0.0, 0.0, 0.0)
    return r


# -------------------------------------------------------------------- T11

def t11_drop_off_table_is_not_a_noop() -> None:
    """`drop_off_table` deve spostare davvero il robot oltre il bordo.

    Difetto reale: `self.twin.pose.x = 999.0` su un dataclass FROZEN
    sollevava FrozenInstanceError -> il comando moriva con HTTP 000 e la
    posa restava invariata. Il test chiama il metodo DI PRODUZIONE
    (non il twin direttamente come faceva T07) e verifica l'EFFETTO.
    """
    robot = _robot()
    before = robot.twin.pose
    ok = True
    err = ""
    try:
        robot.drop_off_table()
    except Exception as e:            # noqa: BLE001
        ok = False
        err = f"{type(e).__name__}: {e}"
    check("T11a drop_off_table non solleva eccezioni", ok, err)
    after = robot.twin.pose
    check("T11b drop_off_table ha SPOSTATO il robot",
          ok and (after.x != before.x or after.y != before.y),
          f"posa invariata: {before} -> {after}")
    check("T11c il robot e' oltre il bordo del tavolo",
          ok and not robot.twin._over_table(after.x, after.y),
          f"posa={after} (il robot e' ancora sul tavolo)")
    # il cliff e' una MASCHERA di 4 sensori: non basta x fuori tavolo,
    # i sensori devono leggere 'nessun piano'
    check("T11d i sensori cliff scattano (maschera non zero)",
          ok and robot.twin.state().cliff != 0,
          f"cliff={robot.twin.state().cliff}")
    # e NON deve essere 'nel vuoto': il robot deve essere vicino al
    # bordo (x=999 e' la posizione DENTRO IL VUOTO, non una caduta)
    check("T11e il robot e' appena oltre il bordo (non nel vuoto)",
          ok and abs(after.x) < TABLE_HALF_X_MM * 4.0,
          f"x={after.x:.1f} (troppo lontano: non simula una caduta)")


def t11b_drop_off_table_is_idempotent_in_effect() -> None:
    """Chiamato due volte, il comando deve continuare a funzionare.

    Difetto reale (stessa causa): la prima chiamata moriva con
    FrozenInstanceError. Se un'eccezione lascia il robot in uno stato
    coerente, la seconda chiamata deve poterlo spostare ancora.
    """
    robot = _robot()
    ok = True
    err = ""
    try:
        robot.drop_off_table()
        x1 = robot.twin.pose.x
        robot.drop_off_table()
        x2 = robot.twin.pose.x
    except Exception as e:            # noqa: BLE001
        ok = False
        err = f"{type(e).__name__}: {e}"
        x1 = x2 = 0.0
    check("T11f drop_off_table e' richiamabile senza eccezioni", ok, err)
    check("T11g la seconda chiamata sposta ancora il robot",
          ok and x2 != x1, f"x1={x1:.1f} x2={x2:.1f}")


# -------------------------------------------------------------------- T12

def t12_cliff_triggers_the_estop() -> None:
    """Un cliff deve FERmare il robot (veto di sicurezza), non solo accendersi.

    Difetto reale: il percorso del comando cliff non esercitava MAI
    l'estop (perche' il comando moriva prima). Il test esercita la
    catena PRODUZIONE: riflesso -> safety.report_sensor -> veto.

    SEMANTICA CORRETTA (vedi T12b): al cliff il robot NON deve restare
    fermo con le ruote a 0: deve INDIETREGGIARE. Fermarsi al bordo e'
    esattamente il modo in cui il robot cade. L'estop continua a essere
    dichiarato (il supervisore deve saperlo), ma il comando motorio di
    fuga deve partire. Per le ALTRE emergenze (pickup/caduta) le ruote
    restano a zero: il test lo verifica esplicitamente, cosi' il fix non
    apre un buco di sicurezza.
    """
    robot = _robot()
    # il robot e' sul tavolo: nessun estop
    out_ok = robot.reflex.decide(1 / 50, robot.twin.state(),
                                 robot.twin.camera_frame(), safety=robot.safety)
    check("T12a sul tavolo non c'e' estop",
          not out_ok.estop, f"estop={out_ok.estop} reason={out_ok.reason!r}")
    # ora il robot cade
    robot.drop_off_table()
    out = robot.reflex.decide(1 / 50, robot.twin.state(),
                              robot.twin.camera_frame(), safety=robot.safety)
    check("T12b oltre il bordo l'estop scatta", out.estop,
          f"estop={out.estop} reason={out.reason!r}")
    check("T12c al cliff il robot ordina il retromarcia (non resta fermo)",
          out.vl < 0.0 and out.vr < 0.0,
          f"vl={out.vl} vr={out.vr} reason={out.reason!r}")
    check("T12d l'estop dichiara il cliff",
          out.estop and ("cliff" in (out.reason or "").lower()
                         or "margine tavolo" in (out.reason or "").lower()),
          f"reason={out.reason!r}")

    # CONTROLLO DI SICUREZZA: un'altra emergenza (pickup) deve ANCORA
    # azzerare le ruote. Se il fix avesse aperto un buco, qui fallirebbe.
    robot2 = _robot()
    now = time.monotonic()
    robot2.twin.simulate_pickup(now, duration_s=2.0)
    robot2.twin.step(1 / 50, now)
    out_pick = robot2.reflex.decide(1 / 50, robot2.twin.state(),
                                    robot2.twin.camera_frame(),
                                    safety=robot2.safety)
    check("T12c-bis col robot sollevato le ruote restano FERME",
          out_pick.estop and out_pick.vl == 0.0 and out_pick.vr == 0.0,
          f"estop={out_pick.estop} vl={out_pick.vl} vr={out_pick.vr} "
          f"reason={out_pick.reason!r}")


def t12b_cliff_backs_away_from_the_edge() -> None:
    """Dopo il cliff il riflesso deve indietreggiare (memoria breve).

    DIFETTO REALE corretto qui: il veto del supervisore veniva valutato
    PRIMA della memoria di retromarcia, quindi la `cliff_memory_s` non
    si armava MAI e il codice di retromarcia era DEAD CODE. Con l'estop
    scattato, `run_sim.l1_cycle` non scriveva nulla sugli attuatori:
    il robot restava FERMO all'oltre del bordo del tavolo.

    Il percorso verificato qui e' quello VERO:
      1. il cliff viene riportato al supervisore (che lo dichiara),
      2. il riflesso ordina il retromarcia (SPEED_AVOID < 0),
      3. quando il sensore torna pulito l'estop da sensore si
         autorilascia e il robot riprende a funzionare.
    """
    robot = _robot()
    robot.drop_off_table()
    # il sensore e' gia' oltre il bordo: il cliff e' presente
    st_cliff = robot.twin.state()
    check("T12e-pre il sensore cliff e' attivo", st_cliff.cliff != 0,
          f"cliff={st_cliff.cliff}")

    # 1. il riflesso vede il cliff: comanda il retromarcia
    out = robot.reflex.decide(1 / 50, st_cliff,
                              robot.twin.camera_frame(), safety=robot.safety)
    check("T12e il riflesso comanda il retromarcia (vl<0 e vr<0)",
          out.vl < 0.0 and out.vr < 0.0,
          f"vl={out.vl} vr={out.vr} reason={out.reason!r}")
    check("T12f l'estop e' dichiarato insieme al retromarcia",
          out.estop, f"estop={out.estop} reason={out.reason!r}")

    # 2. il robot torna dentro il tavolo -> il sensore si pulisce
    robot.twin.pose = kin.Pose(0.0, 0.0, 0.0)
    robot.twin.step(1 / 50, time.monotonic())
    st_ok = robot.twin.state()
    check("T12g il sensore e' pulito sul tavolo", st_ok.cliff == 0,
          f"cliff={st_ok.cliff}")

    # 3. l'estop da sensore si autorilascia: il robot riprende
    out2 = robot.reflex.decide(1 / 50, st_ok,
                               robot.twin.camera_frame(), safety=robot.safety)
    check("T12h dopo il cliff l'estop da sensore si autorilascia",
          not robot.safety.stopped,
          f"stopped={robot.safety.stopped} reason={robot.safety.reason!r}")
    check("T12i il robot non e' piu' bloccato in estop",
          not out2.estop, f"estop={out2.estop} reason={out2.reason!r}")


# -------------------------------------------------------------------- T13

def t13_l2_events_are_edge_triggered() -> None:
    """Gli eventi L2 devono essere sul FRONTE, non sul LIVELLO.

    Difetto reale: `if st.cliff: evs.append("cliff")` a 10 Hz emetteva
    un evento per ogni tick in cui il cliff era attivo: la paura
    dipendeva dalla DURATA del cliff, non dal fatto che il robot fosse
    spaventato. Lo stesso per `picked_up`.
    """
    robot = _robot()
    robot.drop_off_table()
    prev: dict = {}
    st = robot.twin.state()

    first = robot._edge_events(st, prev)
    check("T13a il PRIMO tick col cliff emette un solo evento cliff",
          first.count("cliff") == 1, f"eventi={first}")

    # cliff TENUTO: i tick successivi NON devono re-emettere
    second = robot._edge_events(st, prev)
    third = robot._edge_events(st, prev)
    check("T13b un cliff TENUTO non re-emette eventi",
          "cliff" not in second and "cliff" not in third,
          f"second={second} third={third}")

    # il cliff che FINISCE e ricomincia deve emettere di nuovo
    robot.twin.pose = kin.Pose(0.0, 0.0, 0.0)
    robot._edge_events(robot.twin.state(), prev)      # fine cliff
    robot.drop_off_table()
    again = robot._edge_events(robot.twin.state(), prev)
    check("T13c un NUOVO cliff emette di nuovo l'evento",
          again.count("cliff") == 1, f"eventi={again}")


def t13b_object_moved_is_not_merely_visibility() -> None:
    """"object_moved" non deve essere emesso da una semplice visibilita'.

    Difetto reale: `if self.reflex.tracker.visible: evs.append(
    "object_moved")` -> l'evento diceva "un oggetto si e' mosso" ma
    veniva emesso ogni tick mentre un oggetto era IN VISTA. Bastava
    una faccia ferma (o un oggetto fermo) per saturare `curious` a 1.0
    in 10 s: il mood non dipendeva dal movimento ma dalla presenza.
    """
    robot = _robot()
    prev: dict = {}
    # il tracker vede un oggetto (visibile gia' true)
    robot.reflex.tracker.visible = True
    evs = robot._edge_events(robot.twin.state(), prev)
    check("T13d l'acquisizione di un bersaglio emette object_moved",
          "object_moved" in evs, f"eventi={evs}")
    # lo stesso oggetto, ancora visibile,帧 dopo frame
    evs2 = robot._edge_events(robot.twin.state(), prev)
    evs3 = robot._edge_events(robot.twin.state(), prev)
    check("T13e un bersaglio FERMO non re-emette object_moved",
          "object_moved" not in evs2 and "object_moved" not in evs3,
          f"second={evs2} third={evs3}")
    # se il bersaglio sparisce e torna, e' un NUOVO evento
    robot.reflex.tracker.visible = False
    robot._edge_events(robot.twin.state(), prev)
    robot.reflex.tracker.visible = True
    evs4 = robot._edge_events(robot.twin.state(), prev)
    check("T13f un bersaglio che riapparisce emette di nuovo l'evento",
          "object_moved" in evs4, f"eventi={evs4}")


def t13c_picked_up_is_edge_triggered() -> None:
    """`picked_up` non deve saturare la paura per ogni tick in cui vale.

    NOTA sul percorso: `state().picked_up` non e' un flag grezzo ma un
    campo RICALCOLTATO in `twin.step()` (`self.picked_up = t <
    self._pickup_until`). Scrivere direttamente `_pickup_until` non
    basta: occorre passare da `simulate_pickup()` e far avanzare la
    fisica di un tick. Il test esercita quindi il percorso vero.
    """
    robot = _robot()
    prev: dict = {}
    now = time.monotonic()
    robot.twin.simulate_pickup(now, duration_s=5.0)
    robot.twin.step(1 / 50, now)
    st = robot.twin.state()
    check("T13g-pre il robot risulta sollevato", st.picked_up,
          f"picked_up={st.picked_up}")
    first = robot._edge_events(st, prev)
    check("T13g il sollevamento emette un solo evento",
          first.count("picked_up") == 1, f"eventi={first}")
    second = robot._edge_events(st, prev)
    check("T13h il sollevamento TENUTO non re-emette l'evento",
          "picked_up" not in second, f"eventi={second}")


# -------------------------------------------------------------------- T14

def t14_single_cliff_makes_the_robot_scared() -> None:
    """UN solo cliff deve rendere il robot 'spaventato'.

    Difetto reale: l'evento cliff valeva +0.5 di paura, la soglia di
    "spaventato" e' 0.6 -> un singolo cliff restava sotto soglia e il
    robot era NEUTRO davanti a un burrone. Servivano 2 cliff (che, con
    il trigger di livello, erano 2 tick dello STESSO cliff) per accorgersi.
    """
    limb = LimbicSystem()
    limb.update(1.0 / 10.0, ["cliff"])
    check("T14a un solo cliff supera la soglia di paura",
          limb.mood.fear > 0.6, f"fear={limb.mood.fear:.3f}")
    check("T14b un solo cliff rende il robot spaventato",
          limb.mood.label() == "spaventato",
          f"label={limb.mood.label()!r} fear={limb.mood.fear:.3f}")
    # e il viso deve essere quello SPAVENTATO, non quello neutrale
    face = limb.face_for()
    neutral = LimbicSystem()
    neutral.update(1.0 / 10.0, [])
    nface = neutral.face_for()
    check("T14c il viso e' diverso da quello neutro",
          abs(face.scale_y - nface.scale_y) > 1.0
          or abs(face.glow - nface.glow) > 0.005,
          f"spaventato scale_y={face.scale_y} glow={face.glow} / "
          f"neutro scale_y={nface.scale_y} glow={nface.glow}")


def t14b_repeated_cliffs_do_not_change_the_label() -> None:
    """Dieci cliff consecutivi non devono cambiare l'etichetta.

    Difetto reale: con il trigger di LIVELLO la paura saturava a 1.0
    e l'etichetta dipendeva dal numero di tick, non dagli eventi.
    """
    limb = LimbicSystem()
    labels = []
    for _ in range(10):
        limb.update(1.0 / 10.0, ["cliff"])
        labels.append(limb.mood.label())
    check("T14d l'etichetta resta stabile sui cliff ripetuti",
          len(set(labels)) == 1 and labels[0] == "spaventato",
          f"etichette={labels}")


def t14c_a_visible_object_does_not_saturate_curiosity() -> None:
    """Un oggetto FERMO in vista non deve saturare la curiosita'.

    Difetto reale: 'object_moved' emesso a ogni tick di visibilita'
    portava `curious` a 1.0 in 10 secondi con una faccia ferma.
    Con il trigger di fronte, l'evento c'e' UNA volta: la curiosita'
    sale di un passo e poi rientra.
    """
    limb = LimbicSystem()
    # 100 tick come 10 s a 10 Hz, con l'evento emesso SOLO all'inizio
    limb.update(1.0 / 10.0, ["object_moved"])
    peak = limb.mood.curious
    for _ in range(99):
        limb.update(1.0 / 10.0, [])
    check("T14e un oggetto fermo non lascia curious a 1.0",
          limb.mood.curious < 1.0, f"curious={limb.mood.curious:.3f} peak={peak:.3f}")
    check("T14f la curiosita' rientra dopo il picco",
          limb.mood.curious < peak,
          f"curious={limb.mood.curious:.3f} peak={peak:.3f}")


# -------------------------------------------------------------------- T15

def t15_http_cliff_command_works_and_is_honest() -> None:
    """Il bottone "cliff" della UI deve rispondere 200 e produrre il cliff.

    Difetto reale: la POST /api/command {"action":"cliff"} rispondeva
    HTTP 000 (connessione chiusa) e il log del server conteneva
    `FrozenInstanceError: cannot assign to field 'x'`. Il percorso
    HTTP e' quello che l'utente preme davvero.

    Il server si avvia con l'API DI PRODUZIONE `serve(robot, port)`,
    che restituisce l'oggetto httpd: il test lo chiude in finally, cosi'
    non lascia una porta aperta ne' un thread vivo.
    """
    import socket
    from cozmo_os.webui import serve

    robot = EmbodiedRobot(with_table=True, log_events=False)
    robot.twin.pose = kin.Pose(0.0, 0.0, 0.0)

    # porta libera (liberata subito: il server la riprende)
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()

    httpd = serve(robot, port=port)
    try:
        # il server parte in un thread: si aspetta che la porta risponda
        ready = False
        for _ in range(40):
            try:
                with urllib.request.urlopen(
                        f"http://127.0.0.1:{port}/api/state", timeout=2) as r:
                    if r.status == 200:
                        ready = True
                        break
            except Exception:           # noqa: BLE001
                time.sleep(0.1)
        check("T15-pre il server web e' in ascolto", ready,
              f"porta {port} non risponde")

        body = json.dumps({"action": "cliff"}).encode()
        req = urllib.request.Request(
            f"http://127.0.0.1:{port}/api/command",
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST")
        with urllib.request.urlopen(req, timeout=5) as resp:
            code = resp.status
            payload = resp.read()
        check("T15a la POST /api/command cliff risponde 200",
              code == 200, f"status={code}")
        check("T15b il corpo della risposta e' un JSON valido",
              isinstance(json.loads(payload.decode()), dict),
              f"payload={payload[:120]!r}")
        st = robot.twin.state()
        check("T15c il comando HTTP ha prodotto il cliff",
              st.cliff != 0, f"cliff={st.cliff}")
        check("T15d la posa e' cambiata (il comando ha agito)",
              abs(robot.twin.pose.x) > TABLE_HALF_X_MM,
              f"x={robot.twin.pose.x:.1f}")
    except urllib.error.HTTPError as e:
        check("T15a la POST /api/command cliff risponde 200", False,
              f"HTTPError {e.code}")
    except Exception as e:            # noqa: BLE001
        check("T15a la POST /api/command cliff risponde 200", False,
              f"{type(e).__name__}: {e}")
    finally:
        httpd.shutdown()
        httpd.server_close()


# -------------------------------------------------------------------- T16

def t16_webui_reports_errors_instead_of_dropping_the_connection() -> None:
    """Un'azione che solleva un'eccezione deve rispondere 500 esplicito.

    Difetto reale (lo stesso della FrozenInstanceError): il handler
    POST non aveva un try/except attorno alle azioni -> l'eccezione
    uccideva la connessione (HTTP 000, corpo vuoto). Il client non
    poteva distinguere 'server morto' da 'azione non eseguita'.
    """
    import socket
    from cozmo_os.webui import serve

    robot = EmbodiedRobot(with_table=True, log_events=False)
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()

    httpd = serve(robot, port=port)
    try:
        ready = False
        for _ in range(40):
            try:
                with urllib.request.urlopen(
                        f"http://127.0.0.1:{port}/api/state", timeout=2) as r:
                    if r.status == 200:
                        ready = True
                        break
            except Exception:           # noqa: BLE001
                time.sleep(0.1)
        check("T16-pre il server web e' in ascolto", ready,
              f"porta {port} non risponde")

        body = json.dumps({"action": "azione_inesistente"}).encode()
        req = urllib.request.Request(
            f"http://127.0.0.1:{port}/api/command",
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST")
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                code = resp.status
                payload = resp.read()
        except urllib.error.HTTPError as e:
            code = e.code
            payload = e.read()
        check("T16a un'azione sconosciuta risponde un codice d'errore "
              "leggibile (non una connessione caduta)",
              code in (400, 500), f"status={code} payload={payload[:100]!r}")
        check("T16b il corpo dichiara l'errore in JSON",
              b"error" in payload.lower(), f"payload={payload[:100]!r}")
    except Exception as e:            # noqa: BLE001
        check("T16a un'azione sconosciuta risponde un codice d'errore "
              "leggibile", False, f"{type(e).__name__}: {e}")
    finally:
        httpd.shutdown()
        httpd.server_close()


def main() -> int:
    print("== test cozmo sicurezza/affetto (percorso REALE) ==")
    for fn in (t11_drop_off_table_is_not_a_noop,
               t11b_drop_off_table_is_idempotent_in_effect,
               t12_cliff_triggers_the_estop,
               t12b_cliff_backs_away_from_the_edge,
               t13_l2_events_are_edge_triggered,
               t13b_object_moved_is_not_merely_visibility,
               t13c_picked_up_is_edge_triggered,
               t14_single_cliff_makes_the_robot_scared,
               t14b_repeated_cliffs_do_not_change_the_label,
               t14c_a_visible_object_does_not_saturate_curiosity,
               t15_http_cliff_command_works_and_is_honest,
               t16_webui_reports_errors_instead_of_dropping_the_connection):
        fn()
    print(f"\n{PASSED} ok, {len(FAILURES)} falliti")
    for f in FAILURES:
        print(f"  - {f}")
    return 1 if FAILURES else 0


if __name__ == "__main__":
    raise SystemExit(main())
