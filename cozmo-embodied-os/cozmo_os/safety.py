"""Supervisore di sicurezza: keep-alive watchdog, cliff, pickup.

Regola del firmware: se il flusso UDP si interrompe per 3-5 s il robot entra
in safe-state. Questo modulo implementa la sorveglianza lato server: il loop
di comunicazione chiama feed() a ogni ciclo; alla scadenza il supervisore
emette l'estop. Gli eventi sensoriali (cliff, pickup) forzano l'estop
immediato senza aspettare la scadenza.
"""

from __future__ import annotations

import asyncio
import time


class SafetySupervisor:
    """Watchdog + latch degli eventi di sicurezza. Thread/task-safe."""

    def __init__(self, timeout_s: float = 3.5, on_estop=None):
        if timeout_s <= 0:
            raise ValueError("timeout deve essere positivo")
        self.timeout_s = timeout_s
        self._last_feed = time.monotonic()
        self._estop = False
        self._reason = ""
        # True se l'estop attivo e' LEGATO A UN SENSORE (cliff/pickup/
        # caduta): in quel caso si autorilascia quando il sensore torna
        # pulito. False per l'estop da keep-alive, che richiede clear().
        self._sensor_estop = False
        self._on_estop = on_estop
        self._task: asyncio.Task | None = None
        self._events: list[str] = []

    # -- ciclo di vita -----------------------------------------------------

    async def run(self) -> None:
        """Loop di sorveglianza: gira finché il task non viene cancellato."""
        while True:
            await asyncio.sleep(min(0.25, self.timeout_s / 3.0))
            if self._estop:
                continue
            idle = time.monotonic() - self._last_feed
            if idle > self.timeout_s:
                self.estop(f"keep-alive scaduto ({idle:.1f}s > {self.timeout_s}s)")

    def start(self) -> asyncio.Task:
        if self._task is None:
            self._task = asyncio.get_event_loop().create_task(self.run())
        return self._task

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            self._task = None

    # -- alimentazione e eventi -------------------------------------------

    def feed(self) -> None:
        """Chiamato a ogni giro del loop di comunicazione (reset del timer)."""
        if not self._estop:
            self._last_feed = time.monotonic()

    def estop(self, reason: str) -> None:
        """Ferma i motori e blocca nuovi comandi fino a clear().

        `sensor=True` marca l'estop come LEGATO A UN SENSORE: quando il
        sensore torna pulito, `report_sensor` lo rilascia da solo.
        L'estop da keep-alive resta latchato (serve clear() esplicito).
        """
        if not self._estop:
            self._estop = True
            self._reason = reason
            self._events.append(reason)
            if self._on_estop is not None:
                self._on_estop(reason)

    def report_sensor(self, cliff: int = 0, picked_up: bool = False,
                      falling: bool = False) -> None:
        """Eventi sensoriali dal RobotState: estop immediato, senza attendere.

        DIFETTO REALE: l'estop da sensore restava LATCHED per sempre.
        `clear()` non era chiamato da nessuna parte del codice di
        produzione, quindi dopo un cliff (o un pickup finito) il robot
        non poteva MAI riprendere: il veto bloccava ogni comando
        successivo.

        BARRIERA: l'estop da SENSORE si cancella da solo quando il
        sensore torna pulito; l'estop da KEEP-ALIVE (comunicazione
        caduta) NO, perche' significa "non so piu' cosa sta succedendo"
        e lì serve una conferma esplicita dell'operatore.
        """
        # il sensore torna pulito: l'estop da sensore non e' piu' valido
        if self._estop and self._sensor_estop and not (cliff or picked_up
                                                        or falling):
            self.clear()
            self._events.append("estop sensore rilasciato: sensore pulito")
        if cliff:
            self.estop(f"cliff rilevato (mask={cliff:#06b})")
            self._sensor_estop = True
        if picked_up:
            self.estop("robot sollevato da terra")
            self._sensor_estop = True
        if falling:
            self.estop("robot in caduta")
            self._sensor_estop = True

    def clear(self) -> None:
        """Ripristina il regime dopo una conferma esplicita dell'operatore."""
        self._estop = False
        self._reason = ""
        self._sensor_estop = False
        self._last_feed = time.monotonic()

    # -- letture -----------------------------------------------------------

    @property
    def stopped(self) -> bool:
        return self._estop

    @property
    def reason(self) -> str:
        return self._reason

    def veto_command(self) -> str | None:
        """Se l'estop è attivo restituisce il motivo (il chiamante non muove)."""
        return self._reason if self._estop else None