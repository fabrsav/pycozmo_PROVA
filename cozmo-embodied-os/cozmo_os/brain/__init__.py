"""Cervello embodied a tre strati + router dei modelli.

Strato 1 (L1) - Riflessi: 30-60 Hz, locale, nessuna rete. Sopravvivenza.
Strato 2 (L2) - Limbico: 5-10 Hz, modello piccolo (<90 ms). Umore, bisogni.
Strato 3 (L3) - Corteccia: 0.1-1 Hz, modelli grandi. Spazio, narrazione,
        pianificazione, grounding semantico.
"""

from .router import ModelRouter, LocalProvider, CloudProvider, GroqProvider, GeminiProvider
from .l1_reflex import ReflexController, ReflexOutput
from .l2_limbic import LimbicSystem, Mood
from .l3_cortex import CortexPlanner, EpisodicMemory, GroundingResult

__all__ = [
    "ModelRouter", "LocalProvider", "CloudProvider", "GroqProvider",
    "GeminiProvider", "ReflexController", "ReflexOutput", "LimbicSystem",
    "Mood", "CortexPlanner", "EpisodicMemory", "GroundingResult",
]