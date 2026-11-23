"""Entry point del Digital Twin: `python -m cozmo_os.run_sim`.

Avvia i tre cicli (L1 50 Hz, L2 10 Hz, L3 1 Hz), il watchdog di sicurezza,
la web UI e il ponte eventi. Tutto headless: nessun robot fisico richiesto.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import math
import signal
import time
from pathlib import Path

from .brain.l1_reflex import ReflexController
from .brain.l2_limbic import LimbicSystem, Mood
from .brain.l3_cortex import CortexPlanner, SpatialMap
from .brain.router import LocalProvider, ModelRouter
from .constants import (AUDIO_SAMPLE_RATE, TABLE_HALF_X_MM,
                       WHEEL_SPEED_HARD_MAX, WHEEL_TRACK_MM)
from .face import FaceParams, expression, ink_pixels
from .kinematics import Pose
from .perception import SingleObjectTracker
from .safety import SafetySupervisor
from .simulator import DigitalTwin
from .webui import serve

LOG_DIR = Path(__file__).resolve().parent.parent / "logs"
L1_HZ = 50.0
L2_HZ = 10.0
L3_HZ = 1.0


class EventBus:
    """Bus degli eventi osservabili: NDJSON su disco + SSE + memoria recente."""

    def __init__(self, path: Path | None = None, keep: int = 400):
        self.path = path
        self.recent: list[dict] = []
        self.keep = keep
        self.subscribers: list[asyncio.Queue] = []
        if self.path is not None:
            self.path.parent.mkdir(parents=True, exist_ok=True)

    def emit(self, kind: str, data: dict) -> dict:
        ev = {"t": round(time.time(), 3), "kind": kind, **data}
        self.recent.append(ev)
        if len(self.recent) > self.keep:
            self.recent = self.recent[-self.keep:]
        for q in list(self.subscribers):
            if not q.full():
                q.put_nowait(ev)
        if self.path is not None:
            try:
                with self.path.open("a") as fh:
                    fh.write(json.dumps(ev) + "\n")
            except OSError:
                pass
        return ev

    def subscribe(self) -> asyncio.Queue:
        q: asyncio.Queue = asyncio.Queue(maxsize=200)
        self.subscribers.append(q)
        return q

    def drain_once(self, q: asyncio.Queue) -> list[dict]:
        """Svuota una coda subscriber e restituisce gli eventi pronti.

        Usata dal thread SSE (mondo sync) per non dover usare `await` fuori
        dal loop: il drain e' non bloccante e lascia la coda intatta se vuota.
        """
        out: list[dict] = []
        while True:
            try:
                out.append(q.get_nowait())
            except asyncio.QueueEmpty:
                break
        return out

    def snapshot(self) -> list[dict]:
        return self.recent[-120:]


def _speak_len(pcm: bytes) -> float:
    return len(pcm) / (AUDIO_SAMPLE_RATE * 2)


class EmbodiedRobot:
    """Unisce fisica, percezione, cervello e periferiche in un unico soggetto."""

    def __init__(self, with_table: bool = True, log_events: bool = True,
                 with_cortex_model: bool = False):
        self.twin = DigitalTwin(with_table=with_table)
        self.tracker = SingleObjectTracker()
        self.reflex = ReflexController(tracker=self.tracker)
        self.limbic = LimbicSystem(Mood())
        self.router = ModelRouter()
        if not with_cortex_model:
            # nessuna rete per default: il locale basta a validare il ciclo
            self.router.chain = [LocalProvider()]
        self.cortex = CortexPlanner(router=self.router, spatial=SpatialMap())
        self.safety = SafetySupervisor(timeout_s=3.5)
        self.events = EventBus(LOG_DIR / "events.ndjson" if log_events else None)
        self._speak_pcm = b""
        self._speak_pos = 0
        self._speak_until = -1.0
        self._last_voice_t = -10.0
        self.l1_ticks = 0
        # intenzione L3 in corso: la esegue L1 (non gli attuatori diretti)
        self._l3_intent: dict | None = None
        # obiettivo di navigazione persistente (goto): L1 lo RI-pianifica
        # ogni tick a partire dalla posa reale ( servo ad anello chiuso ),
        # invece di eseguire una raffica aperta che finisce col fermarsi.
        self._nav_goal: dict | None = None
        self._dbg_last: dict = {}

    # ------------------------------------------------------------- azioni

    def speak(self, name: str, now: float | None = None) -> None:
        from . import audio as cozmo_audio
        pcm = cozmo_audio.chirp(name)
        self._speak_pcm = pcm
        self._speak_pos = 0
        now = now if now is not None else time.monotonic()
        self._speak_until = now + _speak_len(pcm)
        self._last_voice_t = now
        self.events.emit("voice", {"chirp": name, "bytes": len(pcm)})

    def face_name(self, name: str) -> None:
        p = expression(name)
        self.twin.show_face(p)
        self.events.emit("face", {"expression": name, "ink": ink_pixels(p)})

    def pickup(self, state: bool, now: float | None = None) -> None:
        now = now if now is not None else time.monotonic()
        if state:
            self.twin.simulate_pickup(now)
            self.events.emit("world", {"event": "pickup", "on": True})
        else:
            self.twin._pickup_until = -1.0
            self.twin.falling = False
            self.events.emit("world", {"event": "pickup", "on": False})

    def drop_off_table(self) -> None:
        """Sposta il robot oltre il bordo: i sensori IR lo rilevano.

        Due dettagli che rendevano questo comando un NO-OP:
          1) `Pose` e' un dataclass FROZEN: `self.twin.pose.x = 999.0` solleva
             FrozenInstanceError -> il bottone "Soffio del tavolo" della UI
             moriva con HTTP 000 e il cliff non accadeva MAI (la sicurezza
             non era mai stata esercitata da quel percorso).
             La posa va SOSTITUITA, non mutata.
          2) x=999 non e' "cadere dal tavolo": e' la posizione del robot
             DENTRO IL VUOTO, lontano dal bordo. Il cliff e' una maschera
             di 4 sensori IR: il robot deve trovarsi appena OLTRE il
             bordo, cosi' i sensori anteriori leggono "nessun piano" come
             nella realta'. Per questo si mette il robot appena fuori dal
             tavolo, lungo la sua direzione di marcia.
        """
        # appena oltre il bordo, sul lato verso cui guarda il robot
        ahead = self.twin.pose.x + (TABLE_HALF_X_MM + 30.0)
        self.twin.pose = Pose(ahead, self.twin.pose.y, self.twin.pose.theta)
        self.events.emit("world", {"event": "cliff_simulated"})

    async def ask(self, text: str) -> str:
        """Domanda in linguaggio naturale -> piano L3 verificato + esecuzione.

        Catena completa (era incompiuta: la UI chiamava un metodo che non
        esisteva, quindi L3 non era raggiungibile da nessuno):
          1. osservazione corrente dal twin (pose, oggetti, umore);
          2. piano dal router, NORMALIZZATO in (azione, parametro);
          3. grounding: il piano cita un oggetto che ESISTE nell'osservazione?
             Se non esiste, il piano si rifiuta e si dice perche' (nessuna
             azione su un'entita' allucinata);
          4. esecuzione del passo riconosciuto (look/face/speak/goto);
          5. risposta in italiano con l'esito, emessa anche sul bus eventi.
        """
        st = self.twin.state()
        observation = " ".join(
            [f"pose=({round(st.x, 1)},{round(st.y, 1)})",
             f"oggetti={[o.kind for o in self.twin.objects]}",
             f"umore={self.limbic.mood.label()}"]
        )
        self.cortex.memory.record(time.monotonic(), f"ask({text}) | {observation}")
        plan = self.cortex.plan_action(text, time.monotonic())
        step = plan.action
        param = plan.parameter

        # un piano non riconosciuto non viene indovinato: si dichiara
        if not plan.understood or not step:
            self.events.emit("l3", {"ask": text, "plan": plan.raw, "grounded": "no",
                                    "executed": False, "note": "piano non riconosciuto"})
            answer = (f"Ho capito '{text}' ma non so trasformarlo in un'azione "
                      f"concreta (risposta del modello: {plan.raw[:80]!r}).")
            self.events.emit("l3_answer", {"ask": text, "answer": answer})
            return answer

        # grounding: nessuna azione su un'entita' non osservata.
        # Solo le azioni che PUNTANO a un oggetto ne hanno bisogno:
        # `face` e `speak` sono auto-referite e non devono essere respinte.
        needs_referent = step in ("look", "goto")
        grounded = self.cortex.ground(f"{text} {plan.render()}", observation)
        if needs_referent and not grounded.ok and grounded.referent is None:
            self.events.emit("l3", {"ask": text, "plan": plan.render(), "grounded": "no",
                                    "note": grounded.note, "executed": False})
            answer = f"Non ho capito a cosa ti riferisci ({grounded.note})."
            self.events.emit("l3_answer", {"ask": text, "answer": answer})
            return answer

        # esecuzione: solo passi riconosciuti, il resto resta un rifiuto onesto
        executed = False
        detail = ""
        if step == "look":
            # orienta TESTA e CORPO verso il referente grounding: e' un'azione
            # L3 (decisione), non un riflesso: il piano dice dove guardare.
            target = self._referent_position(grounded.referent)
            if target is not None:
                dx = target[0] - st.x
                dy = target[1] - st.y
                want = math.atan2(dy, dx) - st.theta
                want = max(-0.6, min(0.6, want))
                self._deposit_l3_intent(0.0, 0.0, want, hold_s=2.0)
                exc = expression("surprised")
                self.twin.show_face(exc)
                self.events.emit("face", {"expression": "surprised",
                                          "ink": ink_pixels(exc), "via": "l3"})
                detail = f"guardo verso {grounded.referent}"
                executed = True
            else:
                detail = "nessun oggetto guardabile"
        elif step == "face":
            name = param.split()[0].lower() if param else "neutral"
            try:
                exc = expression(name)
                self.twin.show_face(exc)
                self.events.emit("face", {"expression": name,
                                          "ink": ink_pixels(exc), "via": "l3"})
                detail = f"faccia {name}"
                executed = True
            except KeyError:
                detail = f"espressione sconosciuta: {name}"
        elif step == "speak":
            name = param.split()[0].lower() if param else "hello"
            try:
                self.speak(name)
                detail = f"dico '{name}'"
                executed = True
            except Exception:
                detail = f"voce sconosciuta: {name}"
        elif step == "goto":
            # goto = avvicinarsi al landmark in modo GRADUALE: si fissa un
            # OBIETTIVO di navigazione e L1 lo ri-pianifica ogni tick dalla
            # posa reale (servo ad anello chiuso). Una raffica aperta di 2 s
            # girerebbe di una quantita' fissa e poi si fermerebbe: il piano
            # 'vai verso X' resterebbe incompiuto.
            target = self._referent_position(grounded.referent)
            if target is not None:
                dx = target[0] - st.x
                dy = target[1] - st.y
                dist = math.hypot(dx, dy)
                self._set_nav_goal(target, hold_s=15.0)
                # prima raffica: allinea il corpo verso il bersaglio
                bearing = math.atan2(dy, dx) - st.theta
                bearing = (bearing + math.pi) % (2 * math.pi) - math.pi
                omega = max(-2.0, min(2.0, bearing * 2.0))
                half = omega * WHEEL_TRACK_MM / 2.0
                v = 0.0 if abs(bearing) > 0.5 else max(20.0, min(70.0, dist * 0.6))
                vl = max(-WHEEL_SPEED_HARD_MAX, min(WHEEL_SPEED_HARD_MAX, v - half))
                vr = max(-WHEEL_SPEED_HARD_MAX, min(WHEEL_SPEED_HARD_MAX, v + half))
                self._deposit_l3_intent(vl, vr, None, hold_s=1.0)
                detail = f"avanzo verso {grounded.referent} (dist {dist:.0f} mm)"
                executed = True
            else:
                detail = "nessun oggetto raggiungibile"

        if not executed:
            answer = (f"Ho capito '{text}' ma il passo '{step}' non e' eseguibile "
                      f"qui ({detail}).")
            self.events.emit("l3", {"ask": text, "plan": plan.render(), "grounded": grounded.ok,
                                    "executed": False, "detail": detail})
            self.events.emit("l3_answer", {"ask": text, "answer": answer})
            return answer

        answer = f"Fatto: {detail}."
        self.events.emit("l3", {"ask": text, "plan": plan.render(), "grounded": grounded.ok,
                                "executed": True, "referent": grounded.referent,
                                "detail": detail})
        self.events.emit("l3_answer", {"ask": text, "answer": answer})
        return answer

    def _referent_position(self, referent: str | None) -> tuple[float, float] | None:
        """Posizione (mm) del referente grounding, se esiste nel mondo."""
        if not referent:
            return None
        kind = referent.split(":")[0]
        for obj in self.twin.objects:
            if obj.kind == kind:
                return (obj.x, obj.y)
        return None

    def _deposit_l3_intent(self, vl: float, vr: float, head: float | None,
                           hold_s: float, lift: float | None = None) -> None:
        """Deposita un intento L3 che L1 esegue per `hold_s` secondi.

        Il comando diretto sugli attuatori sarebbe sovrascritto al tick dopo:
        qui L3 dichiara l'obiettivo e l'esecuzione (tick a tick, col veto del
        riflesso) resta a L1.
        """
        self._l3_intent = {
            "vl": vl, "vr": vr, "head": head, "lift": lift,
            "until": time.monotonic() + hold_s,
        }

    def _consume_l3_intent(self, now: float) -> dict | None:
        """Restituisce l'intento L3 valido, scartando quelli scaduti."""
        if self._l3_intent is None:
            return None
        if now >= self._l3_intent["until"]:
            self._l3_intent = None
            return None
        return self._l3_intent

    def _apply_l3_intent(self, intent: dict | None,
                         vl: float, vr: float, head: float,
                         lift: float) -> tuple[float, float, float, float]:
        """Applica un intent L3 ai comandi del riflesso.

        Funzione SEPARATA dal ciclo: e' il punto unico in cui l'intent
        viene letto, quindi e' anche il punto che i test esercitano
        davvero (prima la lettura era incastrata nel ciclo e il test ne
        rispecchiava una COPIA: la copia poteva restare verde mentre il
        ciclo vero esplodeva).

        Le chiavi 'head' e 'lift' sono OPTIONALI: il deposit di produzione
        scrive sempre 'lift' (None se non specificata), ma un intent costruito
        da ALTRI call-site (o round-trippato) puo' non averla.  Per questo si
        legge con .get(): `intent["lift"]` faceva EXPLODERE il ciclo L1 con
        KeyError, fermando il robot a meta' azione, in silenzio.
        """
        if intent is None:
            return vl, vr, head, lift
        vl, vr = intent["vl"], intent["vr"]
        if intent.get("head") is not None:
            head = intent["head"]
        if intent.get("lift") is not None:
            lift = intent["lift"]
        return vl, vr, head, lift

    # ---------------------------------------------------------- navigazione

    def _set_nav_goal(self, target: tuple[float, float],
                      hold_s: float = 12.0) -> None:
        """Fissa un obiettivo di navigazione verso (x, y) sul tavolo.

        L1 lo RI-pianifica a ogni tick dalla posa reale (feedback chiuso):
        una raffica aperta di 2 s gira di una quantita' fissa e poi si ferma,
        quindi non avvicina mai. L'obiettivo scade da solo (o quando arriva).
        """
        self._nav_goal = {"x": float(target[0]), "y": float(target[1]),
                         "until": time.monotonic() + hold_s}

    def _clear_nav_goal(self) -> None:
        self._nav_goal = None

    def _nav_command(self, st) -> tuple[float, float] | None:
        """Comando ruote (vl, vr) verso l'obiettivo, o None se non c'e'."""
        goal = self._nav_goal
        if goal is None:
            return None
        if time.monotonic() >= goal["until"]:
            self._nav_goal = None
            return None
        dx = goal["x"] - st.x
        dy = goal["y"] - st.y
        dist = math.hypot(dx, dy)
        if dist <= 35.0:                       # arrivato (soglia del braccio)
            self._nav_goal = None
            return 0.0, 0.0
        bearing = math.atan2(dy, dx) - st.theta
        bearing = (bearing + math.pi) % (2 * math.pi) - math.pi
        # prima si allinea (fermo), poi avanza: evitare il cerchio eterno
        if abs(bearing) > 0.25:
            v, omega = 0.0, max(-1.6, min(1.6, bearing * 2.0))
        else:
            v = max(20.0, min(65.0, dist * 0.6))
            omega = max(-1.2, min(1.2, bearing * 2.0))
        half = omega * WHEEL_TRACK_MM / 2.0
        vl = max(-WHEEL_SPEED_HARD_MAX, min(WHEEL_SPEED_HARD_MAX, v - half))
        vr = max(-WHEEL_SPEED_HARD_MAX, min(WHEEL_SPEED_HARD_MAX, v + half))
        return vl, vr

    # --------------------------------------------------------------- cicli

    async def l1_cycle(self) -> None:
        dt = 1.0 / L1_HZ
        while True:
            t0 = time.monotonic()
            out = self.reflex.decide(dt, self.twin.state(),
                                     self.twin.camera_frame(), safety=self.safety)
            self._dbg_last = {"estop": out.estop, "reason": out.reason,
                              "intent": self._l3_intent is not None,
                              "safety": self.safety.reason}
            # DIFETTO REALE: `if not out.estop` saltava del tutto la
            # scrittura sugli attuatori, quindi il comando di
            # RETROMARCIA del riflesso (SPEED_AVOID) non raggiungeva
            # MAI le ruote: al cliff il robot restava fermo all'oltre
            # del bordo, con l'estop acceso.
            #
            # Ora l'estop FERMA le ruote SOLO se il riflesso non
            # comanda una fuga dal bordo: il cliff e' l'unico caso in
            # cui "fermare" e "cadere" sono la stessa cosa. La regola
            # e' esplicita nel campo `estop` + il comando motorio:
            #   - estop e comando ZERO  -> ruote ferme (emergenza vera)
            #   - estop e comando != 0  -> il riflesso ordina la fuga,
            #                                si esegue il comando
            # Per ogni altro evento (pickup, falling, keep-alive scaduto)
            # il riflesso restituisce 0.0, quindi le ruote restano ferme.
            emergency_hold = out.estop and out.vl == 0.0 and out.vr == 0.0
            if not emergency_hold:
                # L3 non scrive direttamente sugli attuatori: L1 gira a 50 Hz
                # e sovrascriverebbe il comando 20 ms dopo. L3 deposita un
                # INTENTO con scadenza, L1 lo esegue per la sua durata.
                vl, vr, head, lift = out.vl, out.vr, out.head, out.lift
                intent = self._consume_l3_intent(t0)
                vl, vr, head, lift = self._apply_l3_intent(
                    intent, vl, vr, head, lift)
                # obiettivo di navigazione: ha la precedenza sul riflesso
                # (il riflesso insegue cio' che VEDE, il piano dice DOVE
                # andare) ed e' ri-pianificato ogni tick dalla posa reale.
                nav = self._nav_command(self.twin.state())
                if nav is not None:
                    vl, vr = nav
                    self._dbg_last["nav"] = True
                else:
                    self._dbg_last["nav"] = False
                # ma l'estop (cliff) ha comunque la precedenza sul PIANO:
                # non si puo' navigare verso il bordo mentre si scappa.
                if out.estop:
                    vl, vr = out.vl, out.vr
                    self._dbg_last["nav"] = False
                self.twin.drive(vl, vr)
                self.twin.set_head(head)
                self.twin.set_lift(lift)
                self._dbg_last["vl"] = round(vl, 1)
                self._dbg_last["vr"] = round(vr, 1)
            else:
                # ruote bloccate: azzeriamo esplicitamente il comando
                # residuo, altrimenti l'integratore riparte con la
                # velocita' precedente quando il veto si sblocca.
                self.twin.drive(0.0, 0.0)
                self._dbg_last["vl"] = 0.0
                self._dbg_last["vr"] = 0.0
            state = self.twin.step(dt, t0)
            self.safety.feed()
            if self._speak_pos < len(self._speak_pcm):
                end = min(len(self._speak_pcm), self._speak_pos + 1764)  # 20 ms
                self.twin.play_audio(self._speak_pcm[self._speak_pos:end], t0)
                self._speak_pos = end
            self.l1_ticks += 1
            if self.l1_ticks % int(L1_HZ) == 0:
                self.events.emit("state", {
                    "pose": {"x": round(state.x, 1), "y": round(state.y, 1),
                             "theta": round(state.theta, 3)},
                    "head": round(state.head, 2), "lift": round(state.lift, 2),
                    "batt_mv": state.battery_mv, "cliff": state.cliff,
                    "picked_up": state.picked_up,
                    "reflex": {"reason": out.reason, "expression": out.expression}})
            await asyncio.sleep(max(0.0, dt - (time.monotonic() - t0)))

    def _edge_events(self, st, prev: dict) -> list[str]:
        """Trasforma uno STATO (livello) in EVENTI (fronte).

        Prima questo era scritto inline nel ciclo L2 e aveva tre difetti:
          1) `if st.cliff: evs.append("cliff")` -> un cliff TENUTO per 0.5 s
             a 10 Hz generava 5 eventi "cliff": la paura dipendeva dalla
             DURATA del cliff, non dal fatto che il robot fosse spaventato.
          2) `if tracker.visible: evs.append("object_moved")` -> l'evento
             diceva "un oggetto si e' mosso" ma veniva emesso ogni tick
             mentre un oggetto era semplicemente VISIBILE: bastava una
             faccia ferma in vista per saturare `curious` a 1.0.
          3) `picked_up` stesso problema: tenere in mano il robot per un
             secondo lo faceva sembrare spaventato 10 volte.

        Qui ogni evento e' sul FRONTE (transizione falso->vero): una
        sola volta per occorrenza. "object_moved" e' per definizione un
        evento, non uno stato: viene emesso quando il tracker ACQUISCE
        un oggetto, non finche' lo vede.
        """
        evs: list[str] = []
        # cliff: fronte (il sensore segnala via mask)
        cliff_now = bool(st.cliff)
        if cliff_now and not prev.get("cliff", False):
            evs.append("cliff")
        # sollevato: fronte
        picked_now = bool(st.picked_up)
        if picked_now and not prev.get("picked_up", False):
            evs.append("picked_up")
        # oggetto: evento per ACQUISIZIONE di un bersaglio (non visibilita')
        visible_now = bool(self.reflex.tracker.visible)
        if visible_now and not prev.get("visible", False):
            evs.append("object_moved")
        prev["cliff"] = cliff_now
        prev["picked_up"] = picked_now
        prev["visible"] = visible_now
        return evs

    async def l2_cycle(self) -> None:
        dt = 1.0 / L2_HZ
        prev_label = self.limbic.mood.label()
        prev_events: dict = {}
        while True:
            t0 = time.monotonic()
            st = self.twin.state()
            evs = self._edge_events(st, prev_events)
            self.limbic.update(dt, evs)
            label = self.limbic.mood.label()
            if label != prev_label:
                self.events.emit("mood", {"label": label,
                                          "mood": dict(self.limbic.mood.__dict__)})
                prev_label = label
            self.twin.show_face(self.limbic.face_for())
            if (self.limbic.mood.arousal > 0.85
                    and self.limbic.mood.fear < 0.4
                    and time.monotonic() - self._last_voice_t > 4.0):
                self.speak(self.limbic.chirp_name())
            await asyncio.sleep(max(0.0, dt - (time.monotonic() - t0)))

    async def l3_cycle(self) -> None:
        dt = 1.0 / L3_HZ
        while True:
            t0 = time.monotonic()
            st = self.twin.state()
            obs = {"pose": (round(st.x, 1), round(st.y, 1)),
                   "objects": [(o.kind, round(o.x, 1), round(o.y, 1))
                               for o in self.twin.objects],
                   "mood": self.limbic.mood.label()}
            self.cortex.memory.record(time.monotonic(),
                                      f"obs pose={obs['pose']} mood={obs['mood']}")
            self.events.emit("l3_ctx", obs)
            await asyncio.sleep(max(0.0, dt - (time.monotonic() - t0)))

    async def safety_cycle(self) -> None:
        while True:
            self.safety.feed()
            await asyncio.sleep(0.5)

    async def frame_publisher(self) -> None:
        """Fotogrammi periodici: il flusso che i modelli grandi consumeranno."""
        import os
        import numpy as np
        interval = float(os.environ.get("COZMO_FRAME_PUBLISH_S", "5"))
        base = LOG_DIR / "frames"
        base.mkdir(parents=True, exist_ok=True)
        n = 0
        while True:
            try:
                np.save(base / f"frame_{n % 60:06d}.npy", self.twin.camera_frame())
                self.events.emit("frame", {"n": n})
                n += 1
            except OSError as e:
                self.events.emit("error", {"where": "frame_publisher", "msg": str(e)})
            await asyncio.sleep(interval)


async def main_async(args) -> None:
    robot = EmbodiedRobot(with_table=not args.no_table,
                           log_events=not args.no_log,
                           with_cortex_model=args.cortex_model)
    httpd = serve(robot, args.port)
    robot.events.emit("boot", {"port": args.port, "cortex_model": args.cortex_model})
    tasks = [asyncio.create_task(robot.l1_cycle()),
             asyncio.create_task(robot.l2_cycle()),
             asyncio.create_task(robot.l3_cycle()),
             asyncio.create_task(robot.safety_cycle()),
             asyncio.create_task(robot.frame_publisher())]
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)
    print(f"[cozmo] Digital Twin attivo su http://127.0.0.1:{args.port}")
    print(f"[cozmo] eventi: {robot.events.path}")
    await stop.wait()
    for t in tasks:
        t.cancel()
    httpd.shutdown()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Digital Twin di Cozmo")
    ap.add_argument("--port", type=int, default=8601)
    ap.add_argument("--no-table", action="store_true",
                    help="mondo senza bordo tavolo (cliff mai attivo)")
    ap.add_argument("--no-log", action="store_true", help="non scrivere NDJSON")
    ap.add_argument("--cortex-model", action="store_true",
                    help="usa i provider cloud per L3 (default: solo locale)")
    args = ap.parse_args(argv)
    try:
        asyncio.run(main_async(args))
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())