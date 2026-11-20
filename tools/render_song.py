"""
Radio - built by Sasha Wildfang for Fractal Network.

Renders a .nbs song to a .wav file the way Radio plays it, so you can hear how a
song sounds with a set of note sounds without starting the server.

    python render_song.py "Imagine Dragons - Believer.nbs" --out believer.wav --pack "C:/.../plugins/Nexo/pack"
    python render_song.py "Imagine Dragons - Believer.nbs" --out believer-vanilla.wav

With --pack, every note uses that pack's radio sounds (like pack-sounds-for-all-notes);
without it, the vanilla note block sounds (out-of-range notes moved by octaves).

Needs: pip install numpy soundfile
"""
import argparse
import json
import struct
import sys
from pathlib import Path

import numpy as np
import soundfile as sf

sys.path.insert(0, str(Path(__file__).resolve().parent))
import make_note_sounds as vanilla  # noqa: E402

RATE = 48000


def read_nbs(path: Path):
    """[(seconds, instrument, key with cents, volume)] like Radio's NbsDecoder."""
    data = path.read_bytes()
    pos = 0

    def take(fmt):
        nonlocal pos
        value = struct.unpack_from(fmt, data, pos)
        pos += struct.calcsize(fmt)
        return value[0]

    def string():
        nonlocal pos
        length = take("<i")
        pos += length

    length = take("<H")
    version, instruments = 0, 10
    if length == 0:
        version, instruments = take("<B"), take("<B")
        if version >= 3:
            take("<H")
    layers = take("<H")
    for _ in range(4):
        string()
    tempo = take("<H") / 100 or 10.0
    pos += 3 + 20
    string()
    if version >= 4:
        pos += 4
    raw = []
    tick = -1
    while (jump := take("<H")) != 0:
        tick += jump
        layer = -1
        while (layer_jump := take("<H")) != 0:
            layer += layer_jump
            instrument, key = take("<B"), take("<B")
            velocity, pitch = 100, 0
            if version >= 4:
                velocity = take("<B")
                take("<B")
                pitch = take("<h")
            raw.append((tick, layer, instrument, key + pitch / 100, velocity))
    layer_volume = [100] * (max(layers, max(n[1] for n in raw) + 1))
    try:
        for i in range(layers):
            string()
            if version >= 4:
                take("<B")
            layer_volume[i] = take("<B")
            if version >= 2:
                take("<B")
    except struct.error:
        pass
    notes = []
    for tick, layer, instrument, key, velocity in raw:
        if instrument >= min(instruments, 16):
            continue
        volume = min(1.0, layer_volume[layer] / 100 * velocity / 100)
        if volume > 0:
            notes.append((tick / tempo, instrument, key, volume))
    return notes


def resolve(key: float, pack: bool):
    """(octave, pitch) like Radio's NoteRange."""
    octave = 0
    if pack and (key < 33 or key > 57):
        octave = max(-2, min(2, int(np.floor((key - 33) / 24))))
    low, high = 33 + 24 * octave, 57 + 24 * octave
    while key < low:
        key += 12
    while key > high:
        key -= 12
    return octave, 2.0 ** ((key - 24 * octave - 45) / 12)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("song", help="the .nbs file")
    parser.add_argument("--out", required=True, help="the .wav file to write")
    parser.add_argument("--pack", help="pack folder with assets/radio (leave out for vanilla sounds)")
    parser.add_argument("--seconds", type=float, default=0, help="only render this many seconds")
    args = parser.parse_args()

    notes = read_nbs(Path(args.song))
    if args.seconds:
        notes = [n for n in notes if n[0] < args.seconds]

    cache = {}
    if not args.pack:
        assets = Path.home() / "AppData" / "Roaming" / ".minecraft" / "assets"
        objects_dir, objects = vanilla.find_assets(assets)
        events = json.loads(vanilla.asset_path(objects_dir, objects, "minecraft/sounds.json").read_text(encoding="utf-8"))

    def sound(instrument: str, octave: int):
        name = f"{instrument}_{octave}"
        if name not in cache:
            if args.pack:
                path = Path(args.pack) / "assets" / "radio" / "sounds" / "note" / f"{name}.ogg"
            else:
                path = vanilla.vanilla_file(objects_dir, objects, events, instrument)
            samples, rate = sf.read(path, dtype="float64")
            if samples.ndim > 1:
                samples = samples.mean(axis=1)
            if rate != RATE:
                samples = np.interp(np.arange(0, len(samples), rate / RATE), np.arange(len(samples)), samples)
            cache[name] = samples
        return cache[name]

    end = max(n[0] for n in notes) + 4
    mix = np.zeros(int(end * RATE))
    for seconds, instrument, key, volume in notes:
        octave, pitch = resolve(key, bool(args.pack))
        samples = sound(vanilla.INSTRUMENTS[instrument], octave)
        played = np.interp(np.arange(0, len(samples) - 1, pitch), np.arange(len(samples)), samples)
        start = int(seconds * RATE)
        mix[start:start + len(played)] += played[: len(mix) - start] * volume
    peak = np.max(np.abs(mix)) or 1.0
    sf.write(args.out, (mix / peak * 0.9).astype(np.float32), RATE)
    print(f"Wrote {args.out} ({len(notes)} notes, {end:.0f} s)")


if __name__ == "__main__":
    main()
