"""Sintetizzatore vocale di Cozmo: PCM 22050 Hz, 16-bit, mono, LE.

Cozmo non ha un timbro campionato libero da licenza: il motore procedurale
qui implementato genera vocalizzi nel suo registro (chip-chirp con formanti
e vibrato) e resta l'unica sorgente finché gli asset Wwise originali non
vengono estratti dall'APK ufficiale in locale (pycozmo_resources).
"""

from __future__ import annotations

import math
import wave
from dataclasses import dataclass

import numpy as np

from .constants import AUDIO_SAMPLE_RATE


@dataclass(frozen=True)
class ChirpSpec:
    """Parametri di un vocalizzo."""
    f0: float = 600.0          # frequenza iniziale [Hz]
    f1: float = 900.0          # frequenza finale [Hz]
    dur: float = 0.35          # durata [s]
    vibrato_hz: float = 24.0   # profondità del trillo
    vibrato_depth: float = 0.06
    noise: float = 0.08        # componente "robotica"
    attack: float = 0.02       # ADSR [s]
    release: float = 0.05
    formant: float = 2.0       # armonico dominante del "timbro"
    gain: float = 0.8


def synth_chirp(spec: ChirpSpec, seed: int = 1234) -> bytes:
    """Genera il PCM grezzo (int16 LE mono) del vocalizzo."""
    n = int(spec.dur * AUDIO_SAMPLE_RATE)
    t = np.arange(n, dtype=np.float64) / AUDIO_SAMPLE_RATE
    # sweep di frequenza
    phase = 2.0 * np.pi * np.cumsum(spec.f0 + (spec.f1 - spec.f0) * t / max(spec.dur, 1e-6)) / AUDIO_SAMPLE_RATE
    # vibrato (trillo caratteristico)
    vib = spec.vibrato_depth * np.sin(2.0 * np.pi * spec.vibrato_hz * t)
    sig = np.sin(phase * (1.0 + vib))
    # formante: armonico che dà il carattere "vocale"
    sig += 0.45 * np.sin(spec.formant * phase * (1.0 + vib))
    # componente rumorosa (grain robotico)
    rng = np.random.default_rng(seed)
    sig += spec.noise * rng.standard_normal(n)
    # inviluppo ADSR semplificato
    env = np.ones(n)
    na = max(1, int(spec.attack * AUDIO_SAMPLE_RATE))
    nr = max(1, int(spec.release * AUDIO_SAMPLE_RATE))
    env[:na] = np.linspace(0.0, 1.0, na)
    env[-nr:] = np.linspace(1.0, 0.0, nr)
    sig = sig * env * spec.gain
    # normalizza per non superare il fondo scala
    peak = float(np.max(np.abs(sig))) or 1.0
    if peak > 0.99:
        sig = sig * (0.99 / peak)
    return (sig * 32767.0).astype("<i2").tobytes()


# ---- Lessico vocale: preset che esprimono stati emotivi distinti ----

VOCAB: dict[str, ChirpSpec] = {
    "happy":    ChirpSpec(f0=520, f1=1180, dur=0.40, vibrato_hz=26, formant=2.5, gain=0.85),
    "excited":  ChirpSpec(f0=640, f1=1560, dur=0.30, vibrato_hz=32, formant=3.0, gain=0.9),
    "sad":      ChirpSpec(f0=780, f1=380,  dur=0.55, vibrato_hz=12, vibrato_depth=0.03, formant=1.5, gain=0.6),
    "question": ChirpSpec(f0=560, f1=1020, dur=0.45, vibrato_hz=20, formant=2.2, gain=0.75),
    "alert":    ChirpSpec(f0=880, f1=880,  dur=0.18, vibrato_hz=0,  vibrato_depth=0, noise=0.03, formant=1.0, gain=0.95),
    "hello":    ChirpSpec(f0=480, f1=760,  dur=0.32, vibrato_hz=18, formant=2.0, gain=0.8),
    "sleepy":   ChirpSpec(f0=420, f1=300,  dur=0.7,  vibrato_hz=6,  vibrato_depth=0.02, formant=1.2, gain=0.45),
    "frustrated": ChirpSpec(f0=700, f1=500, dur=0.38, vibrato_hz=14, vibrato_depth=0.05, noise=0.12, formant=1.8, gain=0.8),
}


def chirp(name: str, seed: int = 1234) -> bytes:
    if name not in VOCAB:
        raise KeyError(f"vocalizzo ignoto: {name}")
    return synth_chirp(VOCAB[name], seed=seed)


def write_wav(pcm: bytes, path: str) -> None:
    """Scrive il PCM in un WAV (per l'analisi umana degli artefatti)."""
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(AUDIO_SAMPLE_RATE)
        w.writeframes(pcm)


def pcm_stats(pcm: bytes) -> dict:
    """Statistiche usate dai test: energia, picco, lunghezza."""
    x = np.frombuffer(pcm, dtype="<i2").astype(np.float64)
    if x.size == 0:
        return {"samples": 0, "rms": 0.0, "peak": 0, "dur_s": 0.0}
    return {
        "samples": int(x.size),
        "rms": float(np.sqrt(np.mean(x * x))),
        "peak": int(np.max(np.abs(x))),
        "dur_s": x.size / AUDIO_SAMPLE_RATE,
    }


def split_chunks(pcm: bytes, ms: int = 20) -> list[bytes]:
    """Divide il flusso nei chunk che il firmware consuma via UDP."""
    step = AUDIO_SAMPLE_RATE * ms // 1000 * 2
    return [pcm[i:i + step] for i in range(0, len(pcm), step)]