"""Digital Twin di Cozmo: fisica, sensori, camera virtuale, OLED, audio.

Nessuna dipendenza da hardware: il twin riproduce ciò che il firmware reale
espone (RobotState, ImageChunk, eventi cliff/pickup/IMU) e ciò che il server
comanda (ruote, testa, lift, bitmap OLED, PCM audio). Gli algoritmi di
controllo vengono validati QUI prima di toccare il robot fisico.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np

from . import audio as cozmo_audio
from . import kinematics as kin
from . import protocol
from .constants import (
    CAMERA_H,
    CAMERA_HFOV_DEG,
    CAMERA_VFOV_DEG,
    CAMERA_W,
    HEAD_ANGLE_MAX,
    HEAD_ANGLE_MIN,
    LIFT_HEIGHT_MAX,
    LIFT_HEIGHT_MIN,
    TABLE_HALF_X_MM,
    TABLE_HALF_Y_MM,
    WHEEL_TRACK_MM,
)
from .face import FaceParams, render_packed
from .protocol import RobotState


@dataclass
class Cube:
    """Light Cube: posizione sul tavolo + stato LED e movimento."""
    x: float
    y: float
    order: int = 0
    lit: float = 0.0          # luminosità LED 0..1
    moving: bool = False


@dataclass
class WorldObject:
    """Oggetto arbitrario sulla scrivania (tazza, chiavi, mano...)."""
    kind: str                 # "cube" | "mug" | "keys" | "hand" | "face"
    x: float
    y: float
    size: float               # dimensione caratteristica [mm]
    confidence: float = 1.0


@dataclass
class Imu:
    ax: float = 0.0
    ay: float = 0.0
    az: float = 9.81          # gravità a riposo sul tavolo
    gyro: float = 0.0


class DigitalTwin:
    """Il corpo virtuale di Cozmo più il suo mondo."""

    def __init__(self, with_table: bool = True):
        # --- stato fisico del robot ---
        self.pose = kin.Pose(0.0, 0.0, 0.0)
        self.head = 0.0                       # rad
        self.lift = LIFT_HEIGHT_MIN
        self.vl = 0.0
        self.vr = 0.0
        self.battery_mv = 3900
        self.imu = Imu()
        self.picked_up = False
        self.falling = False
        self._pickup_until = -1.0
        self._falling_until = -1.0
        # --- mondo ---
        self.with_table = with_table
        self.cubes = [
            Cube(-60.0, 40.0, order=1, lit=0.0),
            Cube(55.0, -35.0, order=2, lit=0.0),
            Cube(10.0, 70.0, order=3, lit=0.0),
        ]
        self.objects: list[WorldObject] = [
            WorldObject("face", 40.0, 180.0, 180.0, confidence=0.9),
            WorldObject("mug", 120.0, -60.0, 85.0),
            WorldObject("keys", -130.0, -70.0, 70.0),
            WorldObject("cube", -60.0, 40.0, 44.0),
            WorldObject("cube", 55.0, -35.0, 44.0),
            WorldObject("cube", 10.0, 70.0, 44.0),
        ]
        # --- periferiche virtuali ---
        self._display_buf = render_packed(FaceParams())
        self.audio_played: list[tuple[float, int]] = []   # (t, nbytes)
        self._audio_clock = 0.0
        self.net_deadline = 0.0
        # contatori evento
        self.cliff_events = 0
        self.pickup_events = 0

    # ------------------------------------------------------------------ misc

    def _over_table(self, x: float, y: float) -> bool:
        if not self.with_table:
            return True
        return abs(x) <= TABLE_HALF_X_MM and abs(y) <= TABLE_HALF_Y_MM

    def _cliff_mask(self) -> int:
        """4 sensori IR: i 4 punti di contatto dei cingoli col tavolo."""
        half_l = WHEEL_TRACK_MM / 2.0 + 4.0
        half_f = 24.0
        c, s = math.cos(self.pose.theta), math.sin(self.pose.theta)
        pts = [
            (self.pose.x + half_f * c - half_l * s, self.pose.y + half_f * s + half_l * c),
            (self.pose.x + half_f * c + half_l * s, self.pose.y + half_f * s - half_l * c),
            (self.pose.x - half_f * c - half_l * s, self.pose.y - half_f * s + half_l * c),
            (self.pose.x - half_f * c + half_l * s, self.pose.y - half_f * s - half_l * c),
        ]
        mask = 0
        for i, (px, py) in enumerate(pts):
            if not self._over_table(px, py):
                mask |= 1 << i
        return mask

    # ------------------------------------------------------------- comandi

    def drive(self, vl: float, vr: float) -> None:
        self.vl, self.vr = vl, vr

    def set_head(self, angle: float) -> None:
        self.head = kin.clamp_head(angle)

    def set_lift(self, height: float) -> None:
        self.lift = kin.clamp_lift(height)

    def show_face(self, params: FaceParams) -> None:
        self._display_buf = render_packed(params)

    def play_audio(self, pcm: bytes, now: float | None = None) -> None:
        self._audio_clock = now if now is not None else self._audio_clock
        self.audio_played.append((self._audio_clock, len(pcm)))

    def simulate_pickup(self, t: float, duration_s: float = 1.5) -> None:
        """Simula il sollevamento manuale da parte di un umano."""
        self._pickup_until = t + duration_s

    def simulate_fall(self, t: float, duration_s: float = 1.5) -> None:
        self._falling_until = t + duration_s

    # --------------------------------------------------------------- step

    def step(self, dt: float, t: float) -> RobotState:
        """Avanza la fisica di dt secondi e restituisce il RobotState."""
        if dt <= 0:
            raise ValueError("dt deve essere positivo")
        # sollevamento: i comandi sono ignorati e l'IMU segna l'accelerazione
        self.picked_up = t < self._pickup_until
        if self.picked_up:
            self.cliff_events += 0  # l'evento è contato alla riga sotto
            self.pickup_events += 1
            self.imu = Imu(ax=0.0, ay=0.0, az=11.5, gyro=0.0)
            self.vl = self.vr = 0.0
        else:
            # integrazione odometrica esatta
            self.pose = kin.integrate_pose(self.pose, self.vl, self.vr, dt)
            # inerzia/testa/lift si muovono verso i valori comandati (rate tipici)
            self.imu = Imu(ax=0.0, ay=0.0, az=9.81, gyro=0.0)
        # caduta fuori dal tavolo (twin della sicura reale)
        if self.with_table and not self.picked_up:
            self._check_ground(t)
        cliff = self._cliff_mask()
        return self.state()

    def _check_ground(self, t: float) -> None:
        mask = self._cliff_mask()
        if mask and not self.falling:
            self.falling = True
            self._falling_until = t + 1.5
            self.cliff_events += 1
        if self.falling and t >= self._falling_until:
            self.falling = False

    def state(self) -> RobotState:
        return RobotState(
            msg_id=int(self._audio_clock * 1000) & 0xFFFFFFFF,
            x=self.pose.x, y=self.pose.y, theta=self.pose.theta,
            head=self.head, lift=self.lift,
            battery_mv=self.battery_mv,
            cliff=self._cliff_mask(),
            picked_up=self.picked_up, falling=self.falling,
            ax=self.imu.ax, ay=self.imu.ay, az=self.imu.az,
            gyro=self.imu.gyro,
        )

    # -------------------------------------------------------- periferiche

    @property
    def display(self) -> bytes:
        return self._display_buf

    def camera_frame(self) -> np.ndarray:
        """Frame 320x240 in scala di grigi: proiezione degli oggetti di mondo.

        Modello pinhole semplificato: la camera è solidale alla testa, la sua
        direzione è theta + head; gli oggetti entro FOV sono proiettati con
        dimensione decrescente con la distanza. Il frame è l'input dei
        detector locali (perception.py) e dei modelli cloud.
        """
        img = np.full((CAMERA_H, CAMERA_W), 24, dtype=np.uint8)
        hfov = math.radians(CAMERA_HFOV_DEG)
        vfov = math.radians(CAMERA_VFOV_DEG)
        # fondo: muri/tavolo per dare patter.
        # Nota NumPy 2.x: `img += <int64>` su un array uint8 fallisce
        # (UFuncOutputCastingError: same_kind). Il pattern si calcola in
        # float/int largo e si applica con un cast esplicito.
        yy, xx = np.mgrid[0:CAMERA_H, 0:CAMERA_W]
        pattern = (((yy // 24) % 2) * 6).astype(np.int16)
        img = (img.astype(np.int16) + pattern).astype(np.uint8)
        cam_heading = self.pose.theta + self.head
        for obj in self.objects:
            dx, dy = obj.x - self.pose.x, obj.y - self.pose.y
            dist = math.hypot(dx, dy)
            if dist < 1e-6:
                continue
            bearing = math.atan2(dy, dx) - cam_heading
            bearing = (bearing + math.pi) % (2 * math.pi) - math.pi
            if abs(bearing) > hfov / 2:
                continue
            # proiezione: asse orizzontale = bearing, verticale = dal lift
            px = int((0.5 + bearing / hfov) * CAMERA_W)
            vert = math.degrees(obj.size / max(dist, 1.0))
            py = int(CAMERA_H * 0.5 - (vert / math.degrees(vfov)) * CAMERA_H * 0.5)
            r = max(2, int(obj.size * 0.9 / max(dist, 1.0) * 12))
            shade = {"cube": 200, "mug": 160, "keys": 110, "hand": 240,
                     "face": 230}.get(obj.kind, 140)
            y0, y1 = max(0, py - r), min(CAMERA_H, py + r)
            x0, x1 = max(0, px - r), min(CAMERA_W, px + r)
            if y1 > y0 and x1 > x0:
                block = img[y0:y1, x0:x1]
                yy2, xx2 = np.mgrid[0:block.shape[0], 0:block.shape[1]]
                mask2 = (yy2 - block.shape[0] / 2) ** 2 + (xx2 - block.shape[1] / 2) ** 2 <= (r * 0.8) ** 2
                block[mask2] = shade
        return img

    def robot_state_frame(self) -> bytes:
        """RobotState serializzato come sul filo (per i test del protocollo)."""
        return protocol.pack_robot_state(self.state())

    def cliff_ahead(self) -> bool:
        return bool(self._cliff_mask() & 0b0011)