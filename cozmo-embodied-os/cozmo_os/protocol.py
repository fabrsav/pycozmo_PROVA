"""Framing binario in stile cozmoclad per il trasporto UDP.

Frame: [0xAA 0x55 sync][type u8][seq u16 LE][len u16 LE][payload][crc16 LE]
Il CRC16-CCITT protegge il payload: un frame corrotto viene scartato dal
parser senza mai far cadere il loop di comunicazione (keep-alive first).
"""

from __future__ import annotations

import struct
from dataclasses import dataclass

SYNC = b"\xaa\x55"

# tipi di messaggio
MSG_ROBOT_STATE = 0x01
MSG_DRIVE = 0x02
MSG_HEAD = 0x03
MSG_LIFT = 0x04
MSG_DISPLAY = 0x05
MSG_AUDIO = 0x06
MSG_PING = 0x07
MSG_PONG = 0x08

# strutture CLAD-equivalenti (little endian)
_ROBOT_STATE = struct.Struct("<IffffffHfff")   # id, x, y, theta, head, lift, batt_mv, ax, ay, az
_CLIFF = struct.Struct("<B")                    # 4 bit cliff + pickup/falling


def crc16_ccitt(data: bytes, init: int = 0xFFFF) -> int:
    """CRC16-CCITT (polinomio 0x1021, riflesso) — tabella-less, chiaro e testato."""
    crc = init
    for byte in data:
        crc ^= byte << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) if (crc & 0x8000) else (crc << 1)
            crc &= 0xFFFF
    return crc


def build_frame(msg_type: int, seq: int, payload: bytes) -> bytes:
    if not 0 <= msg_type <= 0xFF:
        raise ValueError("tipo messaggio fuori range")
    if len(payload) > 0xFFFF:
        raise ValueError("payload troppo grande per un frame UDP")
    head = SYNC + struct.pack("<BHH", msg_type, seq, len(payload))
    crc = crc16_ccitt(head + payload)
    return head + payload + struct.pack("<H", crc)


@dataclass(frozen=True)
class Frame:
    msg_type: int
    seq: int
    payload: bytes
    crc_ok: bool


def parse_frame(buf: bytes) -> Frame | None:
    """Estrae il primo frame valido da un buffer; None se non c'è (o è rotto)."""
    start = buf.find(SYNC)
    if start < 0:
        return None
    buf = buf[start:]
    if len(buf) < 8:
        return None
    msg_type, seq, length = struct.unpack("<BHH", buf[2:8])
    end = 8 + length + 2
    if len(buf) < end:
        return None
    payload = buf[8:8 + length]
    crc_rx = struct.unpack("<H", buf[8 + length:end])[0]
    crc_calc = crc16_ccitt(buf[:8 + length])
    return Frame(msg_type, seq, payload, crc_rx == crc_calc)


# ---- RobotState (server <- Cozmo) ----

@dataclass(frozen=True)
class RobotState:
    msg_id: int
    x: float
    y: float
    theta: float
    head: float
    lift: float
    battery_mv: int
    cliff: int            # bit0..3 sensori fronte/sx, fronte/dx, retro/sx, retro/dx
    picked_up: bool
    falling: bool
    ax: float
    ay: float
    az: float
    gyro: float


def pack_robot_state(st: RobotState) -> bytes:
    flags = (st.cliff & 0x0F) | (0x10 if st.picked_up else 0) | (0x20 if st.falling else 0)
    body = _ROBOT_STATE.pack(st.msg_id, st.x, st.y, st.theta, st.head, st.lift,
                             st.battery_mv, st.ax, st.ay, st.az)
    return body + _CLIFF.pack(flags)


def unpack_robot_state(payload: bytes) -> RobotState:
    (msg_id, x, y, theta, head, lift, batt, ax, ay, az) = _ROBOT_STATE.unpack_from(payload, 0)
    (flags,) = _CLIFF.unpack_from(payload, _ROBOT_STATE.size)
    return RobotState(msg_id, x, y, theta, head, lift, batt,
                      flags & 0x0F, bool(flags & 0x10), bool(flags & 0x20),
                      ax, ay, az)


# ---- Comandi motori (server -> Cozmo) ----

@dataclass(frozen=True)
class DriveCmd:
    vl: float
    vr: float


@dataclass(frozen=True)
class HeadCmd:
    angle: float


@dataclass(frozen=True)
class LiftCmd:
    height: float


_DRIVE = struct.Struct("<ff")
_HEAD = struct.Struct("<f")
_LIFT = struct.Struct("<f")


def pack_drive(cmd: DriveCmd) -> bytes:
    return _DRIVE.pack(cmd.vl, cmd.vr)


def unpack_drive(payload: bytes) -> DriveCmd:
    vl, vr = _DRIVE.unpack_from(payload, 0)
    return DriveCmd(vl, vr)


def pack_head(cmd: HeadCmd) -> bytes:
    return _HEAD.pack(cmd.angle)


def unpack_head(payload: bytes) -> HeadCmd:
    (a,) = _HEAD.unpack_from(payload, 0)
    return HeadCmd(a)


def pack_lift(cmd: LiftCmd) -> bytes:
    return _LIFT.pack(cmd.height)


def unpack_lift(payload: bytes) -> LiftCmd:
    (h,) = _LIFT.unpack_from(payload, 0)
    return LiftCmd(h)


def pack_display(bitmap: bytes) -> bytes:
    if len(bitmap) != 1024:
        raise ValueError("il display si trasmette in frame da 1024 byte")
    return bitmap


def pack_audio(chunk: bytes) -> bytes:
    return chunk