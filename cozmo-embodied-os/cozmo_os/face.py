"""Renderizzatore procedurale della faccia OLED di Cozmo (128x64, 1 bit).

Modello a 19 parametri, fedele all'approccio di pycozmo/procedural_face.py:
forma globale (centro, scala, rotazione) + palpebre per occhio + effetti
(hotspot, glow). Nessuna dipendenza esterna: il buffer prodotto è esattamente
quello che il protocollo spedisce al firmware (1024 byte, page-oriented).
"""

from __future__ import annotations

import math
from dataclasses import dataclass, fields

from .constants import OLED_BUFFER_BYTES, OLED_H, OLED_W

# distanza orizzontale dei centri occhio dal centro globale [px]
EYE_OFFSET_X = 18.0
# raggi di riferimento (unità "scala")
PUPIL_R = 0.42
HOTSPOT_R = 0.14
GLOW_R = 1.28


@dataclass
class FaceParams:
    """I 19 parametri della faccia. Default = occhi aperti, neutri."""
    center_x: float = 64.0
    center_y: float = 32.0
    scale_x: float = 26.0
    scale_y: float = 26.0
    angle: float = 0.0
    upper_lid_y: float = -22.0
    upper_lid_angle: float = 0.0
    upper_lid_bend: float = 0.0
    lower_lid_y: float = 22.0
    lower_lid_angle: float = 0.0
    lower_lid_bend: float = 0.0
    upper_lid_y_l: float = -22.0
    upper_lid_angle_l: float = 0.0
    upper_lid_bend_l: float = 0.0
    lower_lid_y_l: float = 22.0
    lower_lid_angle_l: float = 0.0
    lower_lid_bend_l: float = 0.0
    hotspot: float = 1.0
    glow: float = 0.0

    def clamp(self) -> "FaceParams":
        """Mantiene i parametri in un range fisicamente sensato."""
        p = dict()
        for f in fields(self):
            p[f.name] = getattr(self, f.name)
        p["center_x"] = min(max(p["center_x"], 10.0), OLED_W - 10.0)
        p["center_y"] = min(max(p["center_y"], 10.0), OLED_H - 10.0)
        p["scale_x"] = min(max(p["scale_x"], 4.0), 40.0)
        p["scale_y"] = min(max(p["scale_y"], 4.0), 34.0)
        for k in ("upper_lid_y", "lower_lid_y", "upper_lid_y_l", "lower_lid_y_l"):
            p[k] = min(max(p[k], -40.0), 40.0)
        p["hotspot"] = min(max(p["hotspot"], 0.0), 1.0)
        p["glow"] = min(max(p["glow"], 0.0), 1.0)
        return FaceParams(**p)


def _lid_occludes(local_y: float, local_x: float, lid_y: float,
                  lid_angle: float, lid_bend: float, upper: bool) -> bool:
    """Vero se il pixel è coperto da una palpebra (coordinate y verso il basso)."""
    line = lid_y + math.tan(lid_angle) * local_x + lid_bend * local_x * local_x
    return local_y < line if upper else local_y > line


def _render_rows(p: FaceParams) -> list[int]:
    """Rasterizza la faccia in 64 righe da 128 bit (int)."""
    p = p.clamp()
    rows = [0] * OLED_H
    ca, sa = math.cos(-p.angle), math.sin(-p.angle)

    eyes = (
        (p.center_x - EYE_OFFSET_X, p.upper_lid_y, p.upper_lid_angle,
         p.upper_lid_bend, p.lower_lid_y, p.lower_lid_angle, p.lower_lid_bend),
        (p.center_x + EYE_OFFSET_X, p.upper_lid_y_l, p.upper_lid_angle_l,
         p.upper_lid_bend_l, p.lower_lid_y_l, p.lower_lid_angle_l,
         p.lower_lid_bend_l),
    )

    for row_idx in range(OLED_H):
        py = row_idx + 0.5
        bits = 0
        for col_idx in range(OLED_W):
            px = col_idx + 0.5
            lit = False
            for (ex, uy, ua, ub, ly, la, lb) in eyes:
                dx, dy = px - ex, py - p.center_y
                # rotazione globale della forma oculare
                lx = dx * ca - dy * sa
                ly_ = dx * sa + dy * ca
                u, w = lx / p.scale_x, ly_ / p.scale_y
                r2 = u * u + w * w
                if r2 > GLOW_R * GLOW_R:
                    continue
                inside = r2 <= 1.0 or (p.glow > 0.0 and r2 <= GLOW_R * GLOW_R
                                       and (1.0 - p.glow) * p.glow > 0)
                # palpebre (agiscono solo dentro l'occhio, il glow resta anello)
                if r2 <= 1.0 or r2 <= 1.25:
                    if _lid_occludes(ly_, lx, uy, ua, ub, upper=True):
                        inside = False
                    elif _lid_occludes(ly_, lx, ly, la, lb, upper=False):
                        inside = False
                if not inside:
                    continue
                # pupilla scura
                if r2 <= PUPIL_R * PUPIL_R:
                    # hotspot: pixel chiaro dentro la pupilla, in alto a sinistra
                    hx, hy = u + 0.16, w + 0.16
                    if hx * hx + hy * hy <= HOTSPOT_R * HOTSPOT_R and p.hotspot > 0.5:
                        lit = True
                    continue
                lit = True
            if lit:
                bits |= 1 << col_idx
        rows[row_idx] = bits
    return rows


def render_rows(p: FaceParams) -> list[int]:
    """Facce renderizzate come righe di bit (128 bit per riga)."""
    return _render_rows(p)


def pack_display(rows: list[int]) -> bytes:
    """Converte 64 righe da 128 bit nel buffer OLED (1024 B, page-oriented).

    Il firmware si aspetta i byte in colonne da 8 pixel partendo dall'alto:
    byte[col + 16*page] dove page = riga // 8 e bit = riga % 8.
    """
    if len(rows) != OLED_H:
        raise ValueError(f"attese {OLED_H} righe, ricevute {len(rows)}")
    buf = bytearray(OLED_BUFFER_BYTES)
    for r in range(OLED_H):
        bits = rows[r]
        if bits >> OLED_W:
            raise ValueError("bit oltre il colonna 127")
        page, bit = divmod(r, 8)
        for c in range(OLED_W):
            if bits & (1 << c):
                buf[c + 16 * page] |= 1 << bit
    return bytes(buf)


def render_packed(p: FaceParams) -> bytes:
    """Render diretto al buffer di trasmissione (1024 B)."""
    return pack_display(_render_rows(p))


def ink_pixels(p: FaceParams) -> int:
    """Numero di pixel accesi: misura oggettiva per i test (non a occhio)."""
    return bin(sum(_render_rows(p))).count("1")


# ---- Espressioni predefinite (parametri, non immagini) ----

def expression(name: str) -> FaceParams:
    """Dizionario delle espressioni caratteriali (curve = parametri)."""
    table = {
        "neutral": FaceParams(),
        "happy": FaceParams(center_y=30.0, scale_y=20.0,
                            upper_lid_y=-10.0, upper_lid_y_l=-10.0,
                            lower_lid_y=12.0, lower_lid_y_l=12.0),
        "sad": FaceParams(center_y=36.0, scale_y=16.0,
                          upper_lid_y=-6.0, upper_lid_y_l=-6.0,
                          lower_lid_y=8.0, lower_lid_y_l=8.0,
                          upper_lid_angle=0.15, upper_lid_angle_l=-0.15),
        "blink": FaceParams(upper_lid_y=6.0, upper_lid_y_l=6.0,
                            lower_lid_y=-6.0, lower_lid_y_l=-6.0),
        "surprised": FaceParams(scale_x=30.0, scale_y=30.0,
                                upper_lid_y=-28.0, upper_lid_y_l=-28.0,
                                lower_lid_y=28.0, lower_lid_y_l=28.0),
        "sleepy": FaceParams(upper_lid_y=-8.0, upper_lid_y_l=-8.0,
                             lower_lid_y=6.0, lower_lid_y_l=6.0),
        "angry": FaceParams(upper_lid_y=-4.0, upper_lid_y_l=-4.0,
                            lower_lid_y=10.0, lower_lid_y_l=10.0,
                            upper_lid_angle=-0.35, upper_lid_angle_l=0.35),
        "wink": FaceParams(upper_lid_y=6.0, upper_lid_y_l=6.0,
                           lower_lid_y=-6.0, lower_lid_y_l=-6.0),
    }
    if name not in table:
        raise KeyError(f"espressione ignota: {name}")
    return table[name]