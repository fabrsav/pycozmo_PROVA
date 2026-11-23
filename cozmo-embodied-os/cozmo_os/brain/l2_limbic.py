"""L2 - Sistema limbico: 5-10 Hz, modello piccolo (<90 ms). Umore e bisogni.

Personalità con variabili di stato che decadono: energia, socialità, curiosità,
agitazione. Ogni strato superiore (L3) e ogni azione dell'app consulta questi
valori: sono la bussola che rende il comportamento COERENTE invece di reattivo.
Le espressioni facciali e i vocalizzi sono scelti da qui, non da chi parla.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

from .. import audio as cozmo_audio
from ..face import FaceParams


@dataclass
class Mood:
    """Stato emotivo: normalizzato 0..1, con tassi di decadimento propri."""
    energy: float = 0.8        # batteria/stanchezza
    social: float = 0.6        # bisogno/voglia di interazione
    curious: float = 0.7       # attenzione verso il nuovo
    arousal: float = 0.3       # attivazione generale (calma <-> agitazione)
    fear: float = 0.0          # paura (cliff, caduta, suoni forti)

    def clamp_all(self) -> None:
        for f in ("energy", "social", "curious", "arousal", "fear"):
            setattr(self, f, min(1.0, max(0.0, getattr(self, f))))

    def label(self) -> str:
        """Etichetta dominante: decisione trasparente e testabile.

        Le soglie sono allineate all'ampiezza degli eventi: un singolo
        cliff vale +0.7 di paura (vedi update), quindi una soglia a 0.6
        lo rendeva "spaventato" (0.7 > 0.6) mentre il valore di +0.5
        precedente restava sotto soglia e lasciava il robot NEUTRO
        davanti a un burrone: l'etichetta non dipendeva dall'evento ma
        da quanti tick di cliff erano passati.
        """
        if self.fear > 0.6:
            return "spaventato"
        if self.energy < 0.25:
            return "esausto"
        if self.arousal > 0.75:
            return "eccitato"
        if self.social > 0.75 and self.curious > 0.6:
            return "giocoso"
        if self.curious > 0.75:
            return "curioso"
        if self.social < 0.25:
            return "riservato"
        return "neutro"


class LimbicSystem:
    """Aggiorna l'umore dagli eventi e ne deriva espressione e vocalizzo."""

    DECAY = {"energy": 0.002, "social": 0.004, "curious": 0.003,
             "arousal": 0.02, "fear": 0.05}

    def __init__(self, mood: Mood | None = None):
        self.mood = mood or Mood()

    # -- eventi ------------------------------------------------------------

    def update(self, dt: float, events: list[str] | None = None) -> Mood:
        """Decadimento naturale + risposta agli eventi del ciclo."""
        m = self.mood
        for k, rate in self.DECAY.items():
            setattr(m, k, getattr(m, k) - rate * dt)
        events = events or []
        for ev in events:
            if ev == "picked_up":
                m.fear = min(1.0, m.fear + 0.35)
                m.arousal = min(1.0, m.arousal + 0.2)
            elif ev == "cliff":
                # +0.7: un singolo cliff deve superare la soglia di
                # "spaventato" (0.6). Con +0.5 il robot restava neutro
                # davanti al burrone e servivano 2 eventi per accorgersi.
                m.fear = min(1.0, m.fear + 0.7)
                m.arousal = min(1.0, m.arousal + 0.2)
            elif ev == "face_seen":
                m.social = min(1.0, m.social + 0.08)
                m.curious = min(1.0, m.curious + 0.04)
            elif ev == "object_moved":
                m.curious = min(1.0, m.curious + 0.1)
            elif ev == "praised":
                m.social = min(1.0, m.social + 0.15)
                m.arousal = min(1.0, m.arousal + 0.1)
            elif ev == "idle_long":
                m.energy = min(1.0, m.energy - 0.02)
                m.arousal = max(0.0, m.arousal - 0.1)
        m.clamp_all()
        return m

    # -- espressione -------------------------------------------------------

    def face_for(self, mood_label: str | None = None) -> FaceParams:
        """Parametri facciali derivati dallo stato, non da una stringa scelta."""
        m = self.mood
        label = mood_label or m.label()
        p = FaceParams()
        if label == "spaventato":
            p = FaceParams(center_y=34.0, scale_x=30.0, scale_y=16.0,
                           upper_lid_y=-30.0, upper_lid_y_l=-30.0,
                           lower_lid_y=8.0, lower_lid_y_l=8.0, glow=0.0)
        elif label == "eccitato":
            p = FaceParams(center_y=30.0, scale_x=28.0, scale_y=28.0,
                           upper_lid_y=-28.0, upper_lid_y_l=-28.0,
                           lower_lid_y=26.0, lower_lid_y_l=26.0, glow=0.15)
        elif label == "giocoso":
            p = FaceParams(center_y=30.0, scale_y=20.0,
                           upper_lid_y=-10.0, upper_lid_y_l=-10.0,
                           lower_lid_y=12.0, lower_lid_y_l=12.0)
        elif label == "curioso":
            p = FaceParams(center_x=64.0, center_y=31.0, scale_x=26.0,
                           scale_y=24.0, upper_lid_y=-18.0, upper_lid_y_l=-18.0,
                           lower_lid_y=16.0, lower_lid_y_l=16.0,
                           angle=0.12, glow=0.05)
        elif label == "riservato":
            p = FaceParams(center_y=36.0, scale_x=22.0, scale_y=18.0,
                           upper_lid_y=-8.0, upper_lid_y_l=-8.0,
                           lower_lid_y=6.0, lower_lid_y_l=6.0)
        elif label == "esausto":
            p = FaceParams(center_y=38.0, scale_y=16.0,
                           upper_lid_y=-6.0, upper_lid_y_l=-6.0,
                           lower_lid_y=8.0, lower_lid_y_l=8.0, glow=0.0)
        # arousal modula l'apertura della pupilla (glow) e l'energia la scala
        p.scale_y *= 0.8 + 0.4 * m.energy
        p.glow = min(1.0, p.glow + 0.1 * m.arousal)
        return p

    def chirp_name(self, mood_label: str | None = None) -> str:
        """Vocalizzo corrispondente allo stato."""
        label = mood_label or self.mood.label()
        return {"spaventato": "alert", "esausto": "sleepy", "eccitato": "excited",
                "giocoso": "happy", "curioso": "question",
                "riservato": "sleepy", "neutro": "hello"}[label]


@dataclass
class HomeostaticDrives:
    """Bisogni che spingono l'esplorazione (dopamina della curiosità)."""

    novelty_seek: float = 0.7
    contact_seek: float = 0.5

    def update(self, dt: float, events: list[str] | None = None) -> None:
        for ev in events or []:
            if ev == "face_seen":
                self.contact_seek = max(0.0, self.contact_seek - 0.1)
                self.novelty_seek = min(1.0, self.novelty_seek + 0.05)
            elif ev == "object_moved":
                self.novelty_seek = max(0.0, self.novelty_seek - 0.15)
        self.novelty_seek = min(1.0, self.novelty_seek + 0.01 * dt)
        self.contact_seek = min(1.0, self.contact_seek + 0.008 * dt)

    def suggest_explore(self) -> bool:
        """Se la novità scarseggia, L1 potrebbe voler esplorare."""
        return self.novelty_seek > 0.8