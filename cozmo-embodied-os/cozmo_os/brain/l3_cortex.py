"""L3 - Corteccia: 0.1-1 Hz, modelli grandi. Spazio, narrazione, piani.

Qui si decide COSA fare: costruire una mappa spaziale incrementale, agganciare
il linguaggio al mondo (grounding semantico), pianificare la sequenza di
movimenti che realizza l'intento. Il router consegna testo o struttura; il
grounding verifica ogni entità contro la mappa prima di accettarla.
"""

from __future__ import annotations

import math
from collections import deque
from dataclasses import dataclass, field

from .router import ModelRouter, LocalProvider


@dataclass(frozen=True)
class GroundingResult:
    ok: bool
    referent: str | None      # es. "cube:1" | "face:persona"
    confidence: float
    note: str = ""


@dataclass(frozen=True)
class Plan:
    """Un passo di piano NORMALIZZATO: azione + parametro.

    I provider non sono tutti uguali: il cloud puo' rispettare il formato
    `azione|parametro`, il provider locale risponde in prosa. Il piano viene
    quindi SEMPRE riportato a questa forma prima di essere eseguito, cosi'
    l'esecutore non deve mai indovinare.
    """
    action: str               # goto | look | face | speak
    parameter: str = ""
    raw: str = ""             # risposta originale del provider
    understood: bool = True   # False se il testo non conteneva un'azione

    def render(self) -> str:
        return f"{self.action}|{self.parameter}" if self.understood else self.raw


class SpatialMap:
    """Mappa spaziale incrementale a griglia di occupazione + oggetti noti."""

    def __init__(self, resolution: float = 25.0):
        self.resolution = resolution
        self.obstacles: dict[tuple[int, int], int] = {}   # celle -> peso
        self.landmarks: list[tuple[float, float, str]] = []

    def _cell(self, x: float, y: float) -> tuple[int, int]:
        return (int(x // self.resolution), int(y // self.resolution))

    def mark_free(self, x: float, y: float) -> None:
        self.obstacles[self._cell(x, y)] = 0

    def mark_obstacle(self, x: float, y: float, weight: int = 1) -> None:
        c = self._cell(x, y)
        self.obstacles[c] = self.obstacles.get(c, 0) + weight

    def add_landmark(self, x: float, y: float, kind: str) -> None:
        self.landmarks.append((x, y, kind))

    def nearest_landmark(self, x: float, y: float, kind: str | None = None):
        best, bestd = None, float("inf")
        for lx, ly, lk in self.landmarks:
            if kind and lk != kind:
                continue
            d = math.hypot(lx - x, ly - y)
            if d < bestd:
                best, bestd = (lx, ly, lk), d
        return best, bestd

    def occupancy_ratio(self) -> float:
        if not self.obstacles:
            return 0.0
        occupied = sum(1 for w in self.obstacles.values() if w > 0)
        return occupied / len(self.obstacles)


class EpisodicMemory:
    """Memoria episodica circolare: gli eventi da raccontare e da imparare."""

    def __init__(self, capacity: int = 200):
        self.capacity = capacity
        self.events: deque[tuple[float, str]] = deque(maxlen=capacity)

    def record(self, t: float, what: str) -> None:
        self.events.append((t, what))

    def recent(self, n: int = 5) -> list[str]:
        return [w for _, w in list(self.events)[-n:]]

    def recent_text(self) -> str:
        return "\n".join(self.recent(6))

    def to_text(self) -> str:
        return "\n".join(w for _, w in self.events)


class CortexPlanner:
    """Pianificatore linguistico + grounding verificato sulla mappa."""

    def __init__(self, router: ModelRouter | None = None,
                 spatial: SpatialMap | None = None, memory: EpisodicMemory | None = None):
        self.router = router or ModelRouter([LocalProvider()])
        self.spatial = spatial or SpatialMap()
        self.memory = memory or EpisodicMemory()

    # -- grounding: il linguaggio deve ancorarsi al mondo ------------------

    def ground(self, utterance: str, observation: str) -> GroundingResult:
        """Verifica che il referente citato esista nell'osservazione corrente.

        L'allucinazione è un rischio quando un modello grande nomina un oggetto:
        qui si pretende che l'entità citata compaia fra gli oggetti osservati.
        """
        u = utterance.lower()
        for token, kind in (("cubo", "cube"), ("tazza", "mug"),
                            ("chiavi", "keys"), ("mano", "hand"),
                            ("persona", "face"), ("faccia", "face"),
                            ("volto", "face")):
            if token in u:
                if kind in observation.lower():
                    return GroundingResult(True, kind, 0.9)
                return GroundingResult(False, None, 0.0,
                                       f"'{token}' citato ma non osservato")
        return GroundingResult(False, None, 0.0, "nessun referente riconosciuto")

    # -- pianificazione ----------------------------------------------------

    # parole->azione per i provider che rispondono in prosa
    PROSE_ACTIONS = (
        ("guarda", "look"), ("orient", "look"), ("sguardo", "look"),
        ("saluta", "speak"), ("ciao", "speak"), ("volto", "face"),
        ("faccia", "face"), ("sorridi", "face"),
        ("vai", "goto"), ("avvicin", "goto"), ("prendi", "goto"),
    )

    def parse_plan(self, text: str) -> Plan:
        """Riporta la risposta di un provider alla forma `azione|parametro`.

        Priorita' al formato esplicito (che e' il contratto del prompt), poi
        al primo verbo riconosciuto nella prosa. Se non c'e' niente, il piano
        e' marcato `understood=False`: l'esecutore rifiuta invece di indovinare.
        """
        raw = (text or "").strip()
        if not raw:
            return Plan("", "", raw, understood=False)
        # 1) formato esplicito: la riga deve ESSERE un'azione|parametro
        for line in raw.splitlines():
            line = line.strip().strip("-*• ").strip()
            if "|" in line:
                act, _, par = line.partition("|")
                act = act.strip().lower()
                if act in ("goto", "look", "face", "speak"):
                    return Plan(act, par.strip(), raw)
        # 2) prosa: cerca un verbo noto
        low = raw.lower()
        for needle, act in self.PROSE_ACTIONS:
            if needle in low:
                return Plan(act, "", raw)
        return Plan("", "", raw, understood=False)

    def plan_action(self, intent: str, t: float, budget_ms: float = 1500.0) -> Plan:
        """Traduce un intento in una sequenza di azioni eseguibili da L1.

        Il piano prodotto è verificato: ogni azione cita il sottosistema che la
        esegue (drive/head/lift/speak/face) così ilSupervisore di sicurezza può
        applicarla o rifiutarla.
        """
        prompt = (
            f"Sei il pianificatore di Cozmo, robot embodied su un tavolo.\n"
            f"Contesto:\n{self.memory.recent_text()}\n"
            f"Intento: {intent}\n"
            f"Rispondi con UNA riga nel formato: <azione>|<parametro> dove azione in "
            f"[goto,look,face,speak].")
        reply = self.router.route(prompt, budget_ms, prefer=None)
        plan = self.parse_plan(reply.text)
        self.memory.record(t, f"plan({intent}) -> {plan.render()[:80]}")
        return plan


    def narrate(self, t: float, budget_ms: float = 1500.0):
        """Genera un breve commento (voce del robot) da pubblicare con EventStream."""
        prompt = ("Racconta in una frase breve e in italiano cosa ha appena fatto "
                  "Cozmo:\n" + self.memory.recent_text())
        reply = self.router.route(prompt, budget_ms)
        self.memory.record(t, "narrate")
        return reply


