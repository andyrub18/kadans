#!/usr/bin/env python3
"""Kadans' sounds, drawn from sine waves: nothing recorded, nothing borrowed, the same on every run.

    python3 branding/sounds.py        # from the repository root; standard library only

One voice, a marimba (the owner's choice among three, 2026-11-24), for three moments: a reminder, the end of a focus
phase, the end of a break. Written where the apps read them: Android's `res/raw` (a notification channel's sound) and
the desktop app's resources (played by the app itself). Never edit a generated file: change the numbers here.

Android fixes a channel's sound when the channel is made: a reminder that sounds different needs a new channel
(docs/ARCHITECTURE.md → "Sounds"), so the reminder is not to be retuned lightly.
"""
import math
import struct
import wave
from pathlib import Path

RATE = 44100
E5, A5, CS6, E6, A6 = 659.25, 880.00, 1108.73, 1318.51, 1760.00

# The marimba: partials as (multiple of the note, loudness, seconds for it to fade to about a third), and its attack.
PARTIALS = [(1.0, 1.00, 0.22), (4.0, 0.25, 0.07), (10.0, 0.06, 0.03)]
ATTACK = 0.003

# A moment: notes as (when it starts in seconds, pitch, loudness).
MOMENTS = {
    # Two rising notes, said twice: "look here". It is also what repeats when a reminder keeps ringing.
    "reminder": [(0.00, E5, 0.9), (0.14, A5, 1.0), (0.62, E5, 0.9), (0.76, A5, 1.0)],
    # Coming down, unhurried: put it down, take a break.
    "focus-over": [(0.00, E6, 0.9), (0.24, CS6, 0.9), (0.48, A5, 1.0)],
    # Going up, quicker: back to it.
    "break-over": [(0.00, A5, 0.9), (0.13, CS6, 0.9), (0.26, E6, 0.95), (0.39, A6, 1.0)],
}

ROOT = Path(__file__).resolve().parent.parent
ANDROID = ROOT / "clients/app/shared/src/androidMain/res/raw"
DESKTOP = ROOT / "clients/app/shared/src/jvmMain/resources/sounds"


def render(notes):
    tail = max(fade for _, _, fade in PARTIALS) * 4.5
    length = int((max(start for start, _, _ in notes) + tail) * RATE)
    samples = [0.0] * length
    for start, pitch, loud in notes:
        first = int(start * RATE)
        for multiple, level, fade in PARTIALS:
            step = 2 * math.pi * pitch * multiple / RATE
            for i in range(length - first):
                t = i / RATE
                envelope = min(1.0, t / ATTACK) * math.exp(-t / fade)
                if envelope < 1e-4 and t > ATTACK:
                    break
                samples[first + i] += loud * level * envelope * math.sin(step * i)
    peak = max(abs(s) for s in samples)
    samples = [s / peak * 0.7 for s in samples]  # room above: never clipped, never the loudest thing a phone plays
    fade_out = int(0.05 * RATE)
    for i in range(fade_out):
        samples[-1 - i] *= i / fade_out
    return samples


def write(path, samples):
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(RATE)
        out.writeframes(b"".join(struct.pack("<h", int(s * 32767)) for s in samples))


def main():
    for moment, notes in MOMENTS.items():
        samples = render(notes)
        # Android names a resource with letters, digits and underscores only.
        write(ANDROID / f"kadans_{moment.replace('-', '_')}.wav", samples)
        write(DESKTOP / f"{moment}.wav", samples)
        print(f"{moment}: {len(samples) / RATE:.2f} s")


if __name__ == "__main__":
    main()
