"""Percezione locale a latenza zero.

I rilevamenti che servono ai riflessi (30 fps: tracciare un volto, seguire un
oggetto fermo, reagire a una scomparsa) NON passano dalla rete. Qui stanno i
moduli veloci: differenza col modello di sfondo + centroidi + tracking
singolo-oggetto, uno per uno misurabili su frame sintetici del twin.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from .constants import CAMERA_H, CAMERA_W


@dataclass(frozen=True)
class Blob:
    x: float          # colonna normalizzata 0..1 (orizzontale)
    y: float          # riga normalizzata 0..1
    area: int         # pixel
    cx: int           # colonna in pixel
    cy: int           # riga in pixel


class MotionDetector:
    """Sottrazione dello sfondo con media esponenziale + soglia adattiva."""

    def __init__(self, alpha: float = 0.06, threshold: int = 28):
        if not 0 < alpha < 1:
            raise ValueError("alpha in (0,1)")
        self.alpha = alpha
        self.threshold = threshold
        self._bg: np.ndarray | None = None

    def reset(self, frame: np.ndarray) -> None:
        self._bg = frame.astype(np.float32)

    def detect(self, frame: np.ndarray) -> np.ndarray:
        """Restituisce la maschera binaria del movimento."""
        if self._bg is None:
            self.reset(frame)
            return np.zeros(frame.shape, dtype=bool)
        diff = np.abs(frame.astype(np.float32) - self._bg)
        mask = diff > self.threshold if self.threshold > 0 else diff > (self.alpha * 255)
        self._bg = (1 - self.alpha) * self._bg + self.alpha * frame
        return mask

    @staticmethod
    def largest_blob(mask: np.ndarray, min_area: int = 120) -> Blob | None:
        """Centroide del componente connesso più grande (flood fill 4-vicini)."""
        h, w = mask.shape
        seen = np.zeros((h, w), dtype=bool)
        best: Blob | None = None
        best_area = min_area
        ys, xs = np.nonzero(mask)
        for sy, sx in zip(ys, xs):
            if seen[sy, sx]:
                continue
            stack = [(sy, sx)]
            seen[sy, sx] = True
            area, sx_sum, sy_sum = 0, 0, 0
            while stack:
                y, x = stack.pop()
                area += 1
                sx_sum += x
                sy_sum += y
                for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
                    if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not seen[ny, nx]:
                        seen[ny, nx] = True
                        stack.append((ny, nx))
            if area > best_area:
                best_area = area
                best = Blob(sx_sum / area / w, sy_sum / area / h, area,
                            int(sx_sum / area), int(sy_sum / area))
        return best


class SingleObjectTracker:
    """Tracker a finestra: converge sul blob più grande e lo insegue."""

    def __init__(self, detector: MotionDetector | None = None):
        self.detector = detector or MotionDetector()
        self.visible = False
        self.last: Blob | None = None
        self.missing_frames = 0
        self.max_missing = 15

    def update(self, frame: np.ndarray) -> Blob | None:
        mask = self.detector.detect(frame)
        blob = self.detector.largest_blob(mask)
        if blob is None:
            self.missing_frames += 1
            if self.missing_frames > self.max_missing:
                self.visible = False
                self.last = None
            return self.last
        self.missing_frames = 0
        self.visible = True
        self.last = blob
        return blob

    def error_x(self, blob: Blob | None = None) -> float:
        """Errore normalizzato dal centro immagine (-1..1); 0 se assente."""
        b = blob if blob is not None else self.last
        if b is None:
            return 0.0
        return (b.x - 0.5) * 2.0


def center_of_brightness(frame: np.ndarray) -> tuple[float, float] | None:
    """Centro dei pixel sopra soglia (fonte più rapida di qualunque rete)."""
    thr = int(frame.max()) - 40
    ys, xs = np.nonzero(frame > max(thr, 60))
    if len(xs) == 0:
        return None
    return float(xs.mean()) / CAMERA_W, float(ys.mean()) / CAMERA_H


def darkness_energy(frame: np.ndarray) -> float:
    """Energia media di luminanza: usata dai riflessi (luce/batteria/attenzione)."""
    return float(frame.mean()) / 255.0