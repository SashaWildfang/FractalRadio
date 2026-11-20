"""
Radio - built by Sasha Wildfang for Fractal Network.

Makes the extra note block sounds Radio uses with resource-pack.extended-range: true.

A note block sound only covers two octaves, so this writes copies of every vanilla
instrument shifted by two and four octaves up and down. With them, Radio plays
every note of a song at its real pitch instead of moving it into range.

It also writes each instrument at its normal pitch (<instrument>_0), which Radio
uses for every note when resource-pack.pack-sounds-for-all-notes is on.

With --soft, every sound is made smoother for music: a gentler attack, the harsh
top end rounded off, and a short reverb so notes ring out instead of stopping dead.
Drums get less of this so they stay punchy.

The sounds are made from your own Minecraft install's assets, and written as a
resource pack folder (or straight into a pack folder, e.g. Nexo's):

    python make_note_sounds.py --out "C:/path/to/plugins/Nexo/pack" --soft

That adds assets/radio/sounds/note/*.ogg and assets/radio/sounds.json. Then
regenerate the pack (Nexo: /nexo reload pack) and set extended-range: true in
Radio's config.yml.

Needs: pip install numpy soundfile
"""
import argparse
import json
import os
from pathlib import Path

import numpy as np
import soundfile as sf

# NBS instrument name -> vanilla sound event (block.note_block.<event>)
INSTRUMENTS = [
    "harp", "bass", "basedrum", "snare", "hat", "guitar", "flute", "bell", "chime", "xylophone",
    "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling",
]
# Each step is two octaves (24 keys), matching Radio's NoteRange. 0 is the normal pitch.
OCTAVES = [-2, -1, 0, 1, 2]
# Drums keep more of their punch when softened.
PERCUSSION = {"basedrum", "snare", "hat"}
MAX_SECONDS = 8.0
FADE_SECONDS = 0.5


def find_assets(root: Path):
    """Returns (objects folder, index) for the newest asset index that has sounds.json."""
    indexes = sorted((root / "indexes").glob("*.json"), key=lambda p: p.stat().st_mtime, reverse=True)
    for index_file in indexes:
        objects = json.loads(index_file.read_text(encoding="utf-8"))["objects"]
        if "minecraft/sounds.json" in objects:
            return root / "objects", objects
    raise SystemExit(f"No Minecraft asset index with sounds found in {root}. Start the game once, then try again.")


def asset_path(objects_dir: Path, objects: dict, name: str) -> Path:
    digest = objects[name]["hash"]
    return objects_dir / digest[:2] / digest


def vanilla_file(objects_dir: Path, objects: dict, sounds: dict, instrument: str) -> Path:
    """The .ogg the vanilla sound event plays (its first entry)."""
    entry = sounds["block.note_block." + instrument]["sounds"][0]
    name = entry["name"] if isinstance(entry, dict) else entry
    name = name.split(":")[-1]
    return asset_path(objects_dir, objects, f"minecraft/sounds/{name}.ogg")


def shift(samples: np.ndarray, factor: float) -> np.ndarray:
    """Plays the sound `factor` times faster (higher) or slower (lower), like a pitch knob on a sampler."""
    length = max(16, int(round(len(samples) / factor)))
    spectrum = np.fft.rfft(samples)
    out = np.zeros(length // 2 + 1, dtype=complex)
    keep = min(len(out), len(spectrum))
    out[:keep] = spectrum[:keep]  # dropping the top of the spectrum is the low-pass that stops aliasing
    result = np.fft.irfft(out, length) * (length / len(samples))
    return result


def lowpass(samples: np.ndarray, rate: int, cutoff: float) -> np.ndarray:
    """A gentle roll-off above `cutoff` Hz (about 12 dB per octave), so highs are rounded, not cut."""
    spectrum = np.fft.rfft(samples)
    freqs = np.fft.rfftfreq(len(samples), 1 / rate)
    spectrum /= 1 + (freqs / cutoff) ** 2
    return np.fft.irfft(spectrum, len(samples))


def reverb(rate: int, seconds: float) -> np.ndarray:
    """A small, dark room: decaying noise, the same every run."""
    length = int(seconds * rate)
    t = np.arange(length) / rate
    noise = np.random.default_rng(7).standard_normal(length)
    impulse = lowpass(noise, rate, 2500) * np.exp(-t / (seconds / 6.9))  # -60 dB at the end
    return impulse / np.sqrt(np.sum(impulse ** 2))


def soften(samples: np.ndarray, rate: int, instrument: str, room: np.ndarray) -> np.ndarray:
    drum = instrument in PERCUSSION
    level = np.sqrt(np.mean(samples ** 2)) or 1.0
    out = lowpass(samples, rate, 9000 if drum else 5000)
    attack = int((0.002 if drum else 0.006) * rate)
    out[:attack] *= np.linspace(0, 1, attack) ** 2
    # Mix in the room, letting the note ring on after it ends.
    size = len(out) + len(room) - 1
    wet = np.fft.irfft(np.fft.rfft(out, size) * np.fft.rfft(room, size), size)
    mix = 0.12 if drum else 0.3
    out = np.concatenate([out, np.zeros(len(room) - 1)]) * (1 - mix) + wet * mix
    # Keep the loudness of the original so songs mix the same.
    head = out[:len(samples)]
    return out * (level / (np.sqrt(np.mean(head ** 2)) or 1.0))


def finish(samples: np.ndarray, rate: int) -> np.ndarray:
    limit = int(MAX_SECONDS * rate)
    if len(samples) > limit:
        samples = samples[:limit].copy()
        fade = int(FADE_SECONDS * rate)
        samples[-fade:] *= np.linspace(1, 0, fade)
    peak = np.max(np.abs(samples))
    if peak > 1:
        samples = samples / peak
    return samples.astype(np.float32)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", required=True, help="pack folder to write into (the folder that holds assets/)")
    parser.add_argument("--assets", default=os.path.join(os.environ.get("APPDATA", ""), ".minecraft", "assets"),
                        help="Minecraft assets folder (default: %%APPDATA%%/.minecraft/assets)")
    parser.add_argument("--namespace", default="radio", help="sound namespace (default: radio)")
    parser.add_argument("--soft", action="store_true", help="make every sound smoother (gentle attack, rounded highs, short reverb)")
    args = parser.parse_args()

    objects_dir, objects = find_assets(Path(args.assets))
    sounds = json.loads(asset_path(objects_dir, objects, "minecraft/sounds.json").read_text(encoding="utf-8"))

    pack = Path(args.out) / "assets" / args.namespace
    sound_dir = pack / "sounds" / "note"
    sound_dir.mkdir(parents=True, exist_ok=True)
    sounds_json = pack / "sounds.json"
    events = json.loads(sounds_json.read_text(encoding="utf-8")) if sounds_json.exists() else {}

    room = None
    for instrument in INSTRUMENTS:
        data, rate = sf.read(vanilla_file(objects_dir, objects, sounds, instrument), dtype="float64")
        if data.ndim > 1:
            data = data.mean(axis=1)  # positional sounds must be mono
        if room is None:
            room = reverb(rate, 1.2)
        for octave in OCTAVES:
            name = f"{instrument}_{octave}"
            shifted = data.copy() if octave == 0 else shift(data, 4.0 ** octave)
            if args.soft:
                shifted = soften(shifted, rate, instrument, room)
            shifted = finish(shifted, rate)
            sf.write(sound_dir / f"{name}.ogg", shifted, rate, format="OGG", subtype="VORBIS")
            events[f"note.{name}"] = {"sounds": [{"name": f"{args.namespace}:note/{name}"}]}
        print(f"{instrument}: done" + (" (soft)" if args.soft else ""))

    sounds_json.write_text(json.dumps(events, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(INSTRUMENTS) * len(OCTAVES)} sounds to {pack}")


if __name__ == "__main__":
    main()
