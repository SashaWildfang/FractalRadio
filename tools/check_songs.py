"""
Radio - built by Sasha Wildfang for Fractal Network.

Checks how every song sounds with a pack's note sounds compared to the vanilla note
block sounds, one instrument part at a time, so problems can be traced to a sound:

  clarity  how much the part's pitch moves, real / vanilla. Below ~0.8 notes blur together.
  key      how closely the part's notes match vanilla's (1.0 = same notes). Low = out of tune.
  level    how loud the part is, real / vanilla. Far from 1.0 = too loud or too quiet in the mix.

    python check_songs.py --songs "C:/.../Radio-Songs" --pack "C:/.../plugins/Nexo/pack" --report report.json

Needs: pip install numpy soundfile
"""
import argparse
import json
import multiprocessing
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
import soundfile as sf

sys.path.insert(0, str(Path(__file__).resolve().parent))
import make_note_sounds as vanilla  # noqa: E402
import render_song  # noqa: E402

RATE = 24000          # enough for judging pitch and level, and twice as fast as 48 kHz
SECONDS = 45          # the first 45 s of each song
FRAME = RATE // 8

_sounds = {}
_pack = None
_vanilla = None


def _init(pack):
    global _pack, _vanilla
    _pack = Path(pack)
    assets = Path.home() / "AppData" / "Roaming" / ".minecraft" / "assets"
    objects_dir, objects = vanilla.find_assets(assets)
    events = json.loads(vanilla.asset_path(objects_dir, objects, "minecraft/sounds.json").read_text(encoding="utf-8"))
    _vanilla = (objects_dir, objects, events)


def _load(path):
    samples, rate = sf.read(path, dtype="float64")
    if samples.ndim > 1:
        samples = samples.mean(axis=1)
    return np.interp(np.arange(0, len(samples), rate / RATE), np.arange(len(samples)), samples)


def _sound(instrument, octave, real):
    key = (instrument, octave, real)
    if key not in _sounds:
        if real:
            _sounds[key] = _load(_pack / "assets" / "radio" / "sounds" / "note" / f"{instrument}_{octave}.ogg")
        else:
            _sounds[key] = _load(vanilla.vanilla_file(*_vanilla, instrument))
    return _sounds[key]


def _render(notes, real):
    mix = np.zeros((SECONDS + 4) * RATE)
    for seconds, instrument, key, volume in notes:
        octave, pitch = render_song.resolve(key, real)
        samples = _sound(vanilla.INSTRUMENTS[instrument], octave, real)
        played = np.interp(np.arange(0, len(samples) - 1, pitch), np.arange(len(samples)), samples)
        start = int(seconds * RATE)
        mix[start:start + len(played)] += played[: len(mix) - start] * volume
    return mix


def _chroma(mix):
    frames = len(mix) // FRAME
    window = np.hanning(FRAME)
    freqs = np.fft.rfftfreq(FRAME, 1 / RATE)
    band = (freqs > 50) & (freqs < 4000)
    classes = np.round(69 + 12 * np.log2(freqs[band] / 440)).astype(int) % 12
    chroma, energy = [], []
    for i in range(frames):
        spectrum = np.abs(np.fft.rfft(mix[i * FRAME:(i + 1) * FRAME] * window))[band] ** 2
        total = spectrum.sum()
        energy.append(total)
        chroma.append(np.bincount(classes, weights=spectrum, minlength=12) / (total + 1e-12))
    return np.array(chroma), np.array(energy)


def _movement(chroma, energy):
    loud = energy > energy.max() * 1e-3
    pairs = loud[1:] & loud[:-1]
    if pairs.sum() < 8:
        return None
    return float(np.abs(np.diff(chroma, axis=0)).sum(axis=1)[pairs].mean())


def check(path):
    try:
        notes = [n for n in render_song.read_nbs(Path(path)) if n[0] < SECONDS]
    except Exception as e:  # noqa: BLE001 - report unreadable songs instead of stopping
        return {"song": path, "error": str(e)}
    parts = defaultdict(list)
    for note in notes:
        parts[note[1]].append(note)
    result = {"song": path, "parts": {}}
    for instrument, part in parts.items():
        if len(part) < 8:
            continue
        name = vanilla.INSTRUMENTS[instrument]
        van, real = _render(part, False), _render(part, True)
        van_rms, real_rms = np.sqrt(np.mean(van ** 2)), np.sqrt(np.mean(real ** 2))
        cv, ev = _chroma(van)
        cr, er = _chroma(real)
        mv, mr = _movement(cv, ev), _movement(cr, er)
        profile_v, profile_r = (cv * ev[:, None]).sum(0), (cr * er[:, None]).sum(0)
        key = float(np.corrcoef(profile_v, profile_r)[0, 1]) if profile_v.std() and profile_r.std() else None
        result["parts"][name] = {
            "notes": len(part),
            "clarity": round(mr / mv, 3) if mv and mr else None,
            "key": round(key, 3) if key is not None else None,
            "level": round(float(real_rms / van_rms), 3) if van_rms else None,
        }
    return result


def summarize(results):
    by_instrument = defaultdict(lambda: defaultdict(list))
    for result in results:
        for name, part in result.get("parts", {}).items():
            for metric in ("clarity", "key", "level"):
                if part[metric] is not None:
                    by_instrument[name][metric].append(part[metric])
    print(f"{'instrument':15s} {'songs':>5s} {'clarity':>8s} {'key':>6s} {'level':>6s}   (medians; worst 10%)")
    for name in vanilla.INSTRUMENTS:
        metrics = by_instrument.get(name)
        if not metrics:
            continue
        line = f"{name:15s} {len(metrics['level']):5d}"
        for metric in ("clarity", "key", "level"):
            values = np.array(metrics[metric]) if metrics[metric] else np.array([np.nan])
            line += f" {np.median(values):6.2f}/{np.percentile(values, 10):.2f}"
        print(line)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--songs", required=True)
    parser.add_argument("--pack", required=True)
    parser.add_argument("--report", default="song-report.json")
    args = parser.parse_args()
    songs = sorted(str(p) for p in Path(args.songs).rglob("*.nbs"))
    with multiprocessing.Pool(initializer=_init, initargs=(args.pack,)) as pool:
        results = []
        for i, result in enumerate(pool.imap_unordered(check, songs, chunksize=4), 1):
            results.append(result)
            if i % 50 == 0:
                print(f"  {i}/{len(songs)} songs checked", flush=True)
    Path(args.report).write_text(json.dumps(results, indent=1), encoding="utf-8")
    errors = [r for r in results if "error" in r]
    print(f"Checked {len(results)} songs ({len(errors)} unreadable). Report: {args.report}")
    summarize(results)


if __name__ == "__main__":
    main()
