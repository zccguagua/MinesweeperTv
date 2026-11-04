"""Generates the short sound effects used by the game.

Run from the project root:

    python tools/generate_sfx.py

Writes 44.1 kHz 16-bit mono WAV files into app/src/main/res/raw/. The clips are
synthesised so the repository does not have to carry any third-party audio.
"""

import math
import os
import struct
import wave

RATE = 44100
OUT_DIR = os.path.join("app", "src", "main", "res", "raw")


def envelope(t, duration, attack=0.004, tau=0.03):
    """Linear attack, exponential decay, and a short fade at the very end."""
    if t < attack:
        return t / attack
    value = math.exp(-(t - attack) / tau)
    fade_start = duration - 0.008
    if t > fade_start:
        value *= max(0.0, (duration - t) / 0.008)
    return value


def tone(freq, t, harmonics=(1.0,)):
    return sum(
        amp * math.sin(2 * math.pi * freq * (i + 1) * t)
        for i, amp in enumerate(harmonics)
    )


def normalize(samples, target):
    """Scales the clip so its loudest sample sits at [target]."""
    peak = max((abs(s) for s in samples), default=0.0)
    if peak == 0.0:
        return samples
    scale = target / peak
    return [s * scale for s in samples]


def write_wav(name, samples):
    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, name)
    with wave.open(path, "w") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(RATE)
        frames = b"".join(
            struct.pack("<h", max(-32767, min(32767, int(s * 32767)))) for s in samples
        )
        f.writeframes(frames)
    peak = max(abs(s) for s in samples) if samples else 0.0
    print(f"{path}: {len(samples) / RATE * 1000:.0f} ms, peak {peak:.2f}")


def reveal():
    """Soft blip when a cell is opened."""
    duration = 0.055
    samples = []
    for i in range(int(RATE * duration)):
        t = i / RATE
        sweep = 820 - 900 * t  # slight downward chirp
        samples.append(0.26 * envelope(t, duration, tau=0.018) * math.sin(2 * math.pi * sweep * t))
    return samples


def flag():
    """Short, slightly brighter tick when a flag is placed or removed."""
    duration = 0.045
    samples = []
    for i in range(int(RATE * duration)):
        t = i / RATE
        samples.append(
            0.20 * envelope(t, duration, tau=0.012) * tone(1240, t, (1.0, 0.35, 0.12))
        )
    return samples


def win():
    """Rising C-E-G-C arpeggio."""
    notes = (523.25, 659.25, 783.99, 1046.50)
    step = 0.115
    duration = step * len(notes) + 0.28
    samples = [0.0] * int(RATE * duration)
    for index, freq in enumerate(notes):
        start = index * step
        length = 0.30
        for i in range(int(RATE * length)):
            t = i / RATE
            sample = 0.38 * envelope(t, length, attack=0.006, tau=0.11) * tone(
                freq, t, (1.0, 0.3, 0.1)
            )
            pos = int((start + t) * RATE)
            if pos < len(samples):
                samples[pos] += sample
    return samples


def lose():
    """Descending minor fall for a lost game."""
    notes = (349.23, 293.66, 246.94)
    step = 0.15
    duration = step * len(notes) + 0.30
    samples = [0.0] * int(RATE * duration)
    for index, freq in enumerate(notes):
        start = index * step
        length = 0.32
        for i in range(int(RATE * length)):
            t = i / RATE
            sample = 0.34 * envelope(t, length, attack=0.008, tau=0.12) * tone(
                freq, t, (1.0, 0.45, 0.2)
            )
            pos = int((start + t) * RATE)
            if pos < len(samples):
                samples[pos] += sample
    return samples


if __name__ == "__main__":
    write_wav("sfx_reveal.wav", normalize(reveal(), 0.32))
    write_wav("sfx_flag.wav", normalize(flag(), 0.30))
    write_wav("sfx_win.wav", normalize(win(), 0.80))
    write_wav("sfx_lose.wav", normalize(lose(), 0.75))
