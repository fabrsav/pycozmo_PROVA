"""Cinematica della trazione differenziale skid-steer, testa e carrello.

Tutte le funzioni sono pure e testate in tests/test_kinematics.py.
Unità: lunghezze in mm, angoli in radianti (input/output), velocità in mm/s.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from .constants import (
    HEAD_ANGLE_MAX,
    HEAD_ANGLE_MIN,
    LIFT_HEIGHT_MAX,
    LIFT_HEIGHT_MIN,
    WHEEL_SPEED_HARD_MAX,
    WHEEL_SPEED_SMOOTH_MAX,
    WHEEL_TRACK_MM,
)


def clamp(value: float, lo: float, hi: float) -> float:
    """Vincola value nell'intervallo [lo, hi] (funzione pura)."""
    return lo if value < lo else hi if value > hi else value


def clamp_wheels(vl: float, vr: float) -> tuple[float, float]:
    """Vincola le velocità ruota al range fluido e poi al limite hardware."""
    def _c(v: float) -> float:
        smooth = clamp(v, -WHEEL_SPEED_SMOOTH_MAX, WHEEL_SPEED_SMOOTH_MAX)
        return clamp(smooth, -WHEEL_SPEED_HARD_MAX, WHEEL_SPEED_HARD_MAX)
    return _c(vl), _c(vr)


def inverse_skid(v: float, omega: float) -> tuple[float, float]:
    """(v, omega) -> (vl, vr).  v [mm/s], omega [rad/s]."""
    half = omega * WHEEL_TRACK_MM / 2.0
    return clamp_wheels(v - half, v + half)


def forward_skid(vl: float, vr: float) -> tuple[float, float]:
    """(vl, vr) -> (v, omega)."""
    v = (vl + vr) / 2.0
    omega = (vr - vl) / WHEEL_TRACK_MM
    return v, omega


def clamp_head(angle: float) -> float:
    """Vincola l'angolo di testa all'escursione fisica reale."""
    return clamp(angle, HEAD_ANGLE_MIN, HEAD_ANGLE_MAX)


def clamp_lift(height: float) -> float:
    """Vincola l'altezza del carrello tra 32 e 92 mm."""
    return clamp(height, LIFT_HEIGHT_MIN, LIFT_HEIGHT_MAX)


@dataclass(frozen=True)
class Pose:
    """Posa odometrica del robot sul piano del tavolo."""
    x: float      # mm
    y: float      # mm
    theta: float  # rad, orientamento


def integrate_pose(pose: Pose, vl: float, vr: float, dt: float) -> Pose:
    """Integra la posa con il modello differenziale esatto (arco + chorda).

    Le velocità sono già clampate dal chiamante o da inverse_skid; qui si
    accettano solo valori entro il limite hardware (altrimenti AssertionError).
    """
    if abs(vl) > WHEEL_SPEED_HARD_MAX + 1e-6 or abs(vr) > WHEEL_SPEED_HARD_MAX + 1e-6:
        raise ValueError(f"velocità fuori limite hardware: vl={vl} vr={vr}")
    if dt <= 0:
        raise ValueError("dt deve essere positivo")
    v, omega = forward_skid(vl, vr)
    if abs(omega) < 1e-9:
        # moto rettilineo
        return Pose(pose.x + v * math.cos(pose.theta) * dt,
                    pose.y + v * math.sin(pose.theta) * dt,
                    pose.theta)
    # moto curvilineo: integrazione esatta dell'arco
    r = v / omega                      # raggio di curvatura (ICR)
    dtheta = omega * dt
    # posa relativa al centro istantaneo di rotazione
    ct, st = math.cos(pose.theta), math.sin(pose.theta)
    nx = pose.x + r * (math.sin(pose.theta + dtheta) - st)
    ny = pose.y - r * (math.cos(pose.theta + dtheta) - ct)
    ntheta = pose.theta + dtheta
    # normalizza l'angolo in (-pi, pi]
    ntheta = (ntheta + math.pi) % (2.0 * math.pi) - math.pi
    return Pose(nx, ny, ntheta)


def turn_radius_for_omega(omega: float) -> float:
    """Raggio di curvatura per una data velocità angolare (inf se rettilineo)."""
    return float("inf") if abs(omega) < 1e-9 else WHEEL_TRACK_MM / 2.0


def speeds_for_turn(v: float, radius: float) -> tuple[float, float]:
    """Velocità ruota per curvare con raggio dato a velocità lineare v."""
    if not math.isfinite(radius) or radius == 0:
        raise ValueError("raggio non valido")
    omega = v / radius
    vl = v - omega * WHEEL_TRACK_MM / 2.0
    vr = v + omega * WHEEL_TRACK_MM / 2.0
    return clamp_wheels(vl, vr)


def head_rate_limited(target: float, current: float, rate: float, dt: float) -> float:
    """Avvicina 'current' a 'target' al massimo di 'rate' [rad/s]."""
    diff = target - current
    step = rate * dt
    if diff > step:
        return clamp_head(current + step)
    if diff < -step:
        return clamp_head(current - step)
    return clamp_head(target)