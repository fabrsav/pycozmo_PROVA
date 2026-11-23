"""Cozmo Embedded OS - Digital Twin headless del robot Anki Cozmo.

Architettura a tre strati (piu' watchdog di sicurezza):

- L1 riflessi   30-60 Hz, solo locale, nessuna rete: cadute, bordi, sguardo.
- L2 limbico    5-10 Hz, modello piccolo: umore, bisogni, espressione.
- L3 corteccia  0.1-1 Hz, modelli grandi: spazio, grounding, pianificazione.

Regola architetturale portante: la rete decide COSA fare, mai se non cadere.
Per questo il supervisore di sicurezza e i riflessi non passano mai dal router.

Entry point: ``python -m cozmo_os.run_sim``.
"""

__version__ = "0.1.0"

__all__ = ["__version__"]
