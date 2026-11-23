"""L1 - Riflessi: 30-60 Hz, locale, nessuna rete. La sopravvivenza col corpo.

Flusso per ciclo: sensori -> percezione locale -> legge di controllo ->
uscita motoria. Il router dei modelli NON compare in questo file: se il
cervello pensa in 500 ms e il robot cade dal tavolo in 200 ms, il pensiero
è inutile. La rete si usa in L2/L3 per decidere COSA fare, mai per NON cadere.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

from .. import kinematics as kin
from ..constants import (
    LIFT_HEIGHT_MAX,
    LIFT_HEIGHT_MIN,
)
from ..perception import SingleObjectTracker


@dataclass(frozen=True)
class ReflexOutput:
    vl: float
    vr: float
    head: float
    lift: float
    estop: bool
    expression: str
    reason: str


class ReflexController:
    """Controllo reattivo di basso livello (servo visivo + regole di sicurezza)."""

    KP_TURN = 2.2            # rad/s per unità di errore normalizzato
    KP_HEAD = 12.0           # rad/s per unità di errore (testa segue lo sguardo)
    MAX_TURN = 1.4           # rad/s
    MAX_HEAD_RATE = 3.0      # rad/s
    SPEED_APPROACH = 60.0    # mm/s verso l'obiettivo
    SPEED_AVOID = -40.0      # mm/s: retromarcia dal bordo
    CLIFF_BACKOFF_S = 0.8    # memoria breve del pericolo
    SCAN_HEAD_RATE = 0.8     # rad/s: ricerca con la testa
    HEAD_TARGET_MIN = math.radians(-20.0)
    HEAD_TARGET_MAX = math.radians(25.0)

    def __init__(self, tracker: SingleObjectTracker | None = None):
        self.tracker = tracker or SingleObjectTracker()
        self._head_target = 0.0
        self._head_current = 0.0
        self.cliff_memory_s = 0.0
        self._scan_dir = 1.0

    # -- percezione --------------------------------------------------------

    def update_perception(self, frame: np.ndarray):
        """Aggiorna il tracker col frame della camera e restituisce il blob."""
        return self.tracker.update(frame)

    # -- legge di controllo ------------------------------------------------

    def decide(self, dt: float, robot_state, frame: np.ndarray | None = None,
               safety=None) -> ReflexOutput:
        cliff = getattr(robot_state, "cliff", 0)
        picked_up = getattr(robot_state, "picked_up", False)
        falling = getattr(robot_state, "falling", False)

        # 1. il SUPERVISORE va SEMPRE informato dei sensori, PRIMA di
        #    qualunque decisione: e' un osservatore, non una saracinesca.
        #    (Se il report sta piu' in basso, il ramo di retromarcia
        #    esce PRIMA e il supervisore non vede MAI il cliff: il veto
        #    non scatta e nessuno sa cosa e' successo.)
        if safety is not None:
            safety.report_sensor(cliff=cliff, picked_up=picked_up,
                                 falling=falling)

        # 2. SICUREZZA: il cliff e' l'unico evento che esige una FUGA FISICA.
        #
        # DIFETTO REALE (colto dai test): il veto del supervisore veniva
        # valutato PRIMA della memoria di retromarcia, quindi la
        # `cliff_memory_s` non si armava MAI: il codice di retromarcia
        # era dead code. Con l'estop scattato, `run_sim.l1_cycle` non
        # scriveva nulla sugli attuatori e il robot restava FERMO
        # all'oltre il bordo del tavolo (con la sola ruota a 0).
        #
        # La sem corretta: il cliff non e' "stop", e' "SCAPPA". Il veto
        # continua a essere emesso (il supervisore deve saperlo), ma il
        # comando motorio di retromarcia deve partire lo stesso.
        # Per questo il cliff viene valutato PRIMA del veto, e il veto
        # viene comunque applicato al comando con l'AND: nessuna
        # eccezione e' introdotta per gli altri eventi (pickup/falling/
        # keep-alive), che restano a ruote ferme.
        cliff_now = bool(cliff)
        if cliff_now:
            # armiamo la memoria di retromarcia SUBITO, prima di ogni
            # eventuale ritorno: il pericolo e' gia' accaduto.
            self.cliff_memory_s = self.CLIFF_BACKOFF_S

        # 3. memoria breve del cliff: retrocedere PRIMA di fare qualunque
        #    cosa. Il robot deve riportare le ruote dentro il tavolo.
        if self.cliff_memory_s > 0:
            self.cliff_memory_s -= dt
            return ReflexOutput(self.SPEED_AVOID, self.SPEED_AVOID,
                                self._head_current, LIFT_HEIGHT_MIN,
                                cliff_now, "surprised",
                                "margine tavolo: retrocedo")

        # 4. sicurezza hardware: il veto ha la precedenza assoluta
        veto = safety.veto_command() if safety is not None else None
        if veto is not None:
            return ReflexOutput(0.0, 0.0, self._head_current, LIFT_HEIGHT_MIN,
                                True, "surprised", veto)

        # 5. sollevamento: il robot è tra le mani di un umano
        if picked_up:
            return ReflexOutput(0.0, 0.0, self._head_current, LIFT_HEIGHT_MAX,
                                False, "excited", "sono tra le mani di un umano")

        # 6. inseguimento visivo dell'oggetto più grande
        if frame is not None:
            self.tracker.update(frame)
        err = self.tracker.error_x()
        if self.tracker.visible:
            turn = max(-self.MAX_TURN, min(self.MAX_TURN, self.KP_TURN * err))
            drive = self.SPEED_APPROACH
            # la testa si inclina nella direzione dell'errore (servo visivo)
            self._head_target = kin.clamp_head(
                self._head_target + self.KP_HEAD * err * dt)
            expression, reason = "happy", f"inseguo oggetto (err={err:+.2f})"
        else:
            turn, drive = 0.0, 0.0
            # cerca: movimento lento di scansione della testa
            self._head_target += self._scan_dir * self.SCAN_HEAD_RATE * dt
            if self._head_target > self.HEAD_TARGET_MAX:
                self._head_target = self.HEAD_TARGET_MAX
                self._scan_dir = -1.0
            elif self._head_target < self.HEAD_TARGET_MIN:
                self._head_target = self.HEAD_TARGET_MIN
                self._scan_dir = 1.0
            expression, reason = "question", "obiettivo non visibile: scansiono"

        # 5. rate-limit della testa: la cinematica fisica non è istantanea
        max_step = self.MAX_HEAD_RATE * dt
        diff = self._head_target - self._head_current
        self._head_current += max(-max_step, min(max_step, diff))

        vl, vr = kin.inverse_skid(drive, turn)
        # profilo di mobilita': il carrello (lift) sta BASSO mentre ci si muove
        # (un lift alzato durante la marcia e' un'intrusione non richiesta:
        # il riflesso non deve esibire l'attuatore a ogni passo). Da fermo il
        # carrello conserva la posizione scelta dall'utente o dal piano.
        lift = LIFT_HEIGHT_MIN if drive != 0 else self._lift_idle(robot_state)
        return ReflexOutput(vl, vr, self._head_current, lift, False,
                            expression, reason)

    def _lift_idle(self, robot_state) -> float:
        cur = float(getattr(robot_state, "lift", LIFT_HEIGHT_MIN))
        return kin.clamp_lift(cur)