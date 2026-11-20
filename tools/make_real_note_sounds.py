"""
Radio - built by Sasha Wildfang for Fractal Network.

Makes Radio's note sounds from real instrument recordings instead of the vanilla
note block sounds: piano for harp, plucked double bass for bass, a real harp for
guitar, flute, glockenspiel, xylophone, marimba, bassoon, kick drum, snare and so on.

The recordings come from VSCO 2 Community Edition, a free orchestral sample
library released as CC0 (public domain): https://github.com/sgossner/VSCO-2-CE
Download it (or just the files listed in SAMPLES below), then run:

    python make_real_note_sounds.py --samples "C:/path/to/VSCO-2-CE" --out "C:/path/to/plugins/Nexo/pack"

This writes the same sound names as make_note_sounds.py (radio:note.<instrument>_<octave>),
so turn on both resource-pack options in Radio's config.yml:
    extended-range: true
    pack-sounds-for-all-notes: true

For each instrument and each two-octave range, the recording closest in pitch is
used, so notes are never stretched far from how the instrument really sounds.
Drums aren't pitched: every range gets the same hit. Instruments with no real
counterpart (bit, pling) keep their vanilla sound.

Needs: pip install numpy soundfile
"""
import argparse
import json
import re
import sys
from pathlib import Path

import numpy as np
import soundfile as sf

sys.path.insert(0, str(Path(__file__).resolve().parent))
import make_note_sounds as vanilla  # noqa: E402

OCTAVES = [-2, -1, 0, 1, 2]

# The note (MIDI number) each vanilla instrument plays at pitch 1.0, i.e. NBS key 45.
# Instruments sound in different octaves: bass is two octaves below harp, bells two above.
BASE_NOTE = {
    "harp": 66, "bass": 42, "didgeridoo": 42, "guitar": 54, "flute": 78, "cow_bell": 78,
    "bell": 90, "chime": 90, "xylophone": 90, "iron_xylophone": 66, "bit": 66, "banjo": 66, "pling": 66,
}

# Instrument -> (VSCO folder, file name filter, longest note in seconds).
# Notes are kept short, close to the vanilla sounds' length (about 0.4 s): a note block song has no note
# lengths, so long ring-outs pile up in fast riffs and blur into one droning note. Measured on Believer,
# 3 s piano notes halved how clearly the melody moves; 0.4 s brings it back to vanilla's clarity.
PITCHED = {
    "harp": ("Keys/Upright Piano", r"Player_dyn1_rr1_0(00|10|22|34|44)\.wav$", 0.45),
    "bass": ("Strings/Solo Contrabass/Pizz", r"_v1_rr1\.wav$", 0.45),
    "guitar": ("Strings/Harp", r"\.wav$", 0.4),
    "banjo": ("Strings/Violin Section/Pizz", r"_v2_rr1\.wav$", 0.35),
    "flute": ("Woodwinds/Flute/susNV", r"_v1_1\.wav$", 0.45),
    "bell": ("Percussion/Glock", r"\.wav$", 0.6),
    "chime": ("Percussion/Glock", r"\.wav$", 0.6),
    "xylophone": ("Percussion/Xylo", r"\.wav$", 0.35),
    "iron_xylophone": ("Percussion/Marimba", r"\.wav$", 0.45),
    "didgeridoo": ("Woodwinds/Bassoon/sus", r"_v1_1\.wav$", 0.45),
}

# Instrument -> (VSCO file, loudness compared to the vanilla sound, longest hit in seconds).
UNPITCHED = {
    "basedrum": ("VSCO 1 Percussion/drums/bass/bdrum_muted_mf_1.wav", 0.9, 0.3),
    "snare": ("VSCO 1 Percussion/drums/snare/drum1/snare1_mp_1.wav", 0.3, 0.6),
    "hat": ("VSCO 1 Percussion/varMetal/various/cabasa_1.wav", 0.35, 0.4),
    "cow_bell": ("VSCO 1 Percussion/varMetal/various/cowbell_mf_1.wav", 0.8, 0.8),
}

# Overall loudness of pitched instruments compared to their vanilla sound.
PITCHED_GAIN = {"flute": 0.8, "didgeridoo": 0.8, "xylophone": 0.7}

# Mallets ring with overtones that aren't in tune with the note (a glockenspiel's loudest one is 2.76x
# its pitch), and a bassoon's low notes are strongest on a flat 7th harmonic. In a song these clash with
# the other parts, so everything above this many times the note's pitch is filtered out.
OVERTONE_LIMIT = {"bell": 1.8, "chime": 1.8, "xylophone": 2.2, "didgeridoo": 5.0}

KEEP_VANILLA = ["bit", "pling"]

NOTE_NAMES = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}
FADE_SECONDS = 0.25


def load_mono(path: Path):
    data, rate = sf.read(path, dtype="float64")
    if data.ndim > 1:
        data = data.mean(axis=1)
    return data, rate


def trim_start(samples: np.ndarray) -> np.ndarray:
    """Cuts the silence before the note so it plays exactly on time."""
    peak = np.max(np.abs(samples)) or 1.0
    start = int(np.argmax(np.abs(samples) > peak * 0.05))
    start = max(0, start - 32)
    return samples[start:]


def cut(samples: np.ndarray, rate: int, seconds: float) -> np.ndarray:
    """Shortens a note to `seconds`, dying away naturally over the last 60% instead of stopping dead."""
    limit = int(seconds * rate)
    if len(samples) > limit:
        samples = samples[:limit].copy()
    else:
        samples = samples.copy()
    fade = max(1, min(len(samples), int(max(FADE_SECONDS, seconds * 0.6) * rate)))
    samples[-fade:] *= np.exp(-np.linspace(0, 5, fade)) * np.linspace(1, 0, fade)
    return samples


def attack_level(samples: np.ndarray, rate: int) -> float:
    """Loudness of the first 0.3 s, which is what you hear in a busy song."""
    head = samples[: int(0.3 * rate)]
    return float(np.sqrt(np.mean(head ** 2))) or 1.0


def named_note(file_name: str):
    """MIDI note from a name like KSHarp_A#2_mf.wav (C4 = 60), or None."""
    match = re.search(r"_([A-G])(#?)(-?\d)(?=[_.])", file_name)
    if not match:
        return None
    letter, sharp, octave = match.groups()
    return 12 * (int(octave) + 1) + NOTE_NAMES[letter] + (1 if sharp else 0)


def piano_note(file_name: str):
    """The upright piano files are numbered: Player_dyn1_rr1_000 is A0 (21), each step two notes up."""
    match = re.search(r"_(\d{3})\.wav$", file_name)
    return 21 + 2 * int(match.group(1)) if match else None


def detect_note(samples: np.ndarray, rate: int) -> float:
    """Pitch (as a MIDI number) with the YIN method, used to check which octave a file really is."""
    start = int(0.08 * rate)
    window = samples[start:start + int(0.25 * rate)]
    window = window - window.mean()
    n = len(window)
    max_lag = min(n // 2, int(rate / 25))
    size = 1 << (2 * n - 1).bit_length()
    spectrum = np.fft.rfft(window, size)
    corr = np.fft.irfft(spectrum * np.conj(spectrum), size)[:max_lag]
    energy = np.concatenate([[0.0], np.cumsum(window ** 2)])
    lags = np.arange(max_lag)
    # Difference function d(t) = sum (x[i] - x[i+t])^2 over the first half of the window.
    half = n - max_lag
    e0 = energy[half]
    e_t = energy[lags + half] - energy[lags]
    corr_half = np.array([np.dot(window[:half], window[t:t + half]) for t in lags])
    diff = e0 + e_t - 2 * corr_half
    cmnd = np.ones(max_lag)
    running = np.cumsum(diff[1:])
    cmnd[1:] = diff[1:] * np.arange(1, max_lag) / np.where(running == 0, 1, running)
    low = int(rate / 4200)
    candidates = np.where(cmnd[low:] < 0.15)[0]
    if len(candidates):
        lag = low + candidates[0]
        while lag + 1 < max_lag and cmnd[lag + 1] < cmnd[lag]:
            lag += 1
    else:
        lag = low + int(np.argmin(cmnd[low:]))
    return 69 + 12 * np.log2(rate / lag / 440.0)


def tuning_cents(samples: np.ndarray, rate: int, note: int) -> float:
    """How far (in cents) a recording is from its note: orchestral samples are often a little sharp or flat.
    Looks for the strongest peak within a semitone of the note or its octaves. Capped at +-50 cents."""
    window = samples[int(0.01 * rate):int(0.4 * rate)]
    size = 1 << 19
    spectrum = np.abs(np.fft.rfft(window * np.hanning(len(window)), size))
    freqs = np.fft.rfftfreq(size, 1 / rate)
    best = None
    for multiple in (1, 2, 4):
        target = 440.0 * 2 ** ((note - 69) / 12) * multiple
        if target < 35 or target > rate * 0.45:
            continue
        low, high = np.searchsorted(freqs, target * 2 ** (-1 / 12)), np.searchsorted(freqs, target * 2 ** (1 / 12))
        peak = low + int(np.argmax(spectrum[low:high]))
        if best is None or spectrum[peak] > best[0]:
            best = (spectrum[peak], 1200 * np.log2(freqs[peak] / target))
    return 0.0 if best is None else float(np.clip(best[1], -50, 50))


def pitched_samples(root: Path, folder: str, pattern: str):
    """[(MIDI note, samples, rate)] for an instrument, with each file's octave checked against its sound."""
    files = sorted(p for p in (root / folder).glob("*.wav") if re.search(pattern, p.name))
    if not files:
        raise SystemExit(f"No samples found in {root / folder}")
    loaded = []
    for path in files:
        samples, rate = load_mono(path)
        samples = trim_start(samples)
        note = piano_note(path.name) if "Upright Piano" in folder else named_note(path.name)
        loaded.append((note, samples, rate))
    # Sample libraries name octaves differently (glockenspiels sound two octaves above written),
    # so shift every name by the octave the recordings actually sound in.
    offsets = [round((detect_note(s, r) - n) / 12) * 12 for n, s, r in loaded if n is not None]
    offset = int(np.median(offsets)) if offsets else 0
    print(f"  {folder}: {len(loaded)} recordings, octave correction {offset:+d}")
    # Exact tuning: a sample 30 cents flat is treated as a note 0.3 lower, so the shift corrects it.
    return [(n + offset + tuning_cents(s, r, n + offset) / 100, s, r) for n, s, r in loaded if n is not None]


def limit_overtones(samples: np.ndarray, rate: int, note: float, multiple: float) -> np.ndarray:
    """Steeply removes everything above `multiple` times the note's frequency."""
    cutoff = 440.0 * 2 ** ((note - 69) / 12) * multiple
    spectrum = np.fft.rfft(samples)
    freqs = np.fft.rfftfreq(len(samples), 1 / rate)
    spectrum /= np.sqrt(1 + (freqs / cutoff) ** 16)
    return np.fft.irfft(spectrum, len(samples))


def write(sound_dir: Path, name: str, samples: np.ndarray, rate: int):
    peak = np.max(np.abs(samples))
    if peak > 1:
        samples = samples / peak
    sf.write(sound_dir / f"{name}.ogg", samples.astype(np.float32), rate, format="OGG", subtype="VORBIS")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--samples", required=True, help="the VSCO-2-CE folder")
    parser.add_argument("--out", required=True, help="pack folder to write into (the folder that holds assets/)")
    parser.add_argument("--assets", default=None, help="Minecraft assets folder (for bit and pling, and loudness)")
    parser.add_argument("--namespace", default="radio", help="sound namespace (default: radio)")
    args = parser.parse_args()

    root = Path(args.samples)
    assets = Path(args.assets) if args.assets else Path.home() / "AppData" / "Roaming" / ".minecraft" / "assets"
    objects_dir, objects = vanilla.find_assets(assets)
    sounds = json.loads(vanilla.asset_path(objects_dir, objects, "minecraft/sounds.json").read_text(encoding="utf-8"))

    pack = Path(args.out) / "assets" / args.namespace
    sound_dir = pack / "sounds" / "note"
    sound_dir.mkdir(parents=True, exist_ok=True)
    sounds_json = pack / "sounds.json"
    events = json.loads(sounds_json.read_text(encoding="utf-8")) if sounds_json.exists() else {}

    for instrument in vanilla.INSTRUMENTS:
        original, original_rate = load_mono(vanilla.vanilla_file(objects_dir, objects, sounds, instrument))
        target_level = attack_level(original, original_rate)

        for octave in OCTAVES:
            name = f"{instrument}_{octave}"
            if instrument in KEEP_VANILLA:
                out = original.copy() if octave == 0 else vanilla.shift(original, 4.0 ** octave)
                out, rate = vanilla.finish(out, original_rate), original_rate
                level = 1.0
            elif instrument in UNPITCHED:
                file, gain, seconds = UNPITCHED[instrument]
                samples, rate = load_mono(root / file)
                out = cut(trim_start(samples), rate, seconds)
                level = target_level * gain / attack_level(out, rate)
            else:
                folder, pattern, seconds = PITCHED[instrument]
                if octave == OCTAVES[0]:
                    recordings = pitched_samples(root, folder, pattern)
                target = BASE_NOTE[instrument] + 24 * octave
                note, samples, rate = min(recordings, key=lambda r: abs(r[0] - target))
                out = vanilla.shift(samples, 2.0 ** ((target - note) / 12.0))
                out = cut(out, rate, seconds)
                if instrument in OVERTONE_LIMIT:
                    out = limit_overtones(out, rate, target, OVERTONE_LIMIT[instrument])
                level = target_level * PITCHED_GAIN.get(instrument, 1.0) / attack_level(out, rate)
            write(sound_dir, name, out * level, rate)
            events[f"note.{name}"] = {"sounds": [{"name": f"{args.namespace}:note/{name}"}]}
        kind = "vanilla" if instrument in KEEP_VANILLA else "real"
        print(f"{instrument}: done ({kind})")

    sounds_json.write_text(json.dumps(events, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(vanilla.INSTRUMENTS) * len(OCTAVES)} sounds to {pack}")


if __name__ == "__main__":
    main()
