"""Tide Tank sounds (design sound.list, D51 / B20 / B24): real CC0 recordings for every physical sound, synthesis only for
the two UI cues (new-find marimba, all-found arpeggio). Chain: slice -> optional pitch -> 60 Hz high-pass -> short
cosine attack / tail -> peak -1 dBFS. 44.1 kHz mono 16-bit WAV into assets/sounds (the pump bed is an OGG loop).
Variant rotation and +-5 % pitch jitter at playback (SoundPlayer, D51).

  ~/.factory-venv/bin/python tools/make_sounds.py

Sources (pages state Creative Commons 0; LICENSE.txt in each folder):
  ~/Downloads/cc0-sounds/freesound-aquarium   792927 / 792931 water drops (qubodup), 168861 bubble popping (ianslattery),
                                              173930 Water Pour (JohnsonBrandEditing), 595794 water swishing (Joma86),
                                              640597 small bubbles (gokalp_gonen)
  ~/Downloads/cc0-sounds/freesound-aura-sand  146408 Sand on Paper (crashoverride6)
  ~/Downloads/cc0-sounds/freesound-marbles    628997 Marbles hitting each other (KaleidacousticsAudio)
  ~/Downloads/cc0-sounds/kenney_impact-sounds impactGlass_light / impactMetal_light / impactPlank_medium (Kenney, CC0)
"""
import os, subprocess, wave
import numpy as np
from scipy.signal import butter, sosfiltfilt, resample

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "sounds")
LIB = os.path.expanduser("~/Downloads/cc0-sounds")
SR = 44100


def load(rel):
    raw = subprocess.run(["ffmpeg", "-v", "quiet", "-i", os.path.join(LIB, rel), "-ac", "1", "-ar", str(SR), "-f", "s16le", "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, "<i2").astype(float) / 32768


def onset(x, thr=0.08):
    idx = np.nonzero(np.abs(x) > thr * np.max(np.abs(x)))[0]
    return x[max(0, idx[0] - int(0.003 * SR)):] if len(idx) else x


def sl(x, t0, secs):
    return x[int(t0 * SR): int((t0 + secs) * SR)].copy()


def pitch(x, semis):
    k = 2 ** (semis / 12)
    return resample(x, int(len(x) / k))


def hp(x, lo=60):
    return sosfiltfilt(butter(2, lo, "highpass", fs=SR, output="sos"), x)


def lp(x, hi):
    return sosfiltfilt(butter(4, hi, "lowpass", fs=SR, output="sos"), x)


def env(x, attack=0.004, fade=0.05):
    x = x.copy()
    a = max(1, int(attack * SR)); n = min(len(x) // 2, int(fade * SR))
    x[:a] *= 0.5 - 0.5 * np.cos(np.linspace(0, np.pi, a))
    x[-n:] *= np.cos(np.linspace(0, np.pi / 2, n)) ** 2
    return x


def peak(x, db=-1.0):
    return x * (10 ** (db / 20) / (np.max(np.abs(x)) + 1e-12))


def save(name, x):
    os.makedirs(OUT, exist_ok=True)
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())
    print(f"{name}.wav {len(x) / SR:.2f} s peak {20 * np.log10(np.max(np.abs(x)) + 1e-12):.1f} dBFS")


def loop_ogg(x, secs, xfade, name, rms_db=-22):
    n = int(secs * SR); m = int(xfade * SR)
    body = x[: n + m].copy()
    w = np.linspace(0, 1, m)
    body[:m] = body[:m] * w + body[n:n + m] * (1 - w)
    body = body[:n]
    body *= 10 ** (rms_db / 20) / np.sqrt(np.mean(body ** 2))
    tmp = os.path.join(OUT, name + ".tmp.wav")
    with wave.open(tmp, "wb") as wv:
        wv.setnchannels(1); wv.setsampwidth(2); wv.setframerate(SR)
        wv.writeframes((np.clip(body, -1, 1) * 32767).astype("<i2").tobytes())
    subprocess.run(["ffmpeg", "-v", "quiet", "-y", "-i", tmp, "-c:a", "libvorbis", "-q:a", "3", os.path.join(OUT, name + ".ogg")], check=True)
    os.remove(tmp)
    print(f"{name}.ogg {secs:.1f} s loop")


def marimba(freqs, gap, tail):
    """UI cue: struck bar = fundamental + 4x partial, fast exponential decay (synthesised, UI only)."""
    total = int((gap * (len(freqs) - 1) + tail) * SR)
    out = np.zeros(total)
    t = np.arange(int(tail * SR)) / SR
    for i, f in enumerate(freqs):
        note = (np.sin(2 * np.pi * f * t) + 0.25 * np.sin(2 * np.pi * 4 * f * t) * np.exp(-t * 18)) * np.exp(-t * 5.5)
        note[: int(0.003 * SR)] *= np.linspace(0, 1, int(0.003 * SR))
        s = int(i * gap * SR)
        out[s:s + len(note)] += note
    return out


def main():
    aq = "freesound-aquarium/"
    # glass jar picked up: Kenney glass, two variants
    save("pick", peak(hp(env(onset(load("kenney_impact-sounds/Audio/impactGlass_light_000.ogg"))[: int(0.5 * SR)], fade=0.15)), -3))
    save("pick_2", peak(hp(env(onset(load("kenney_impact-sounds/Audio/impactGlass_light_002.ogg"))[: int(0.5 * SR)], fade=0.15)), -3))
    # one pinch of food out of the jar: three timbres (flakes = sand on paper, pellets = small marbles, shrimp = sand, brighter)
    sand = load("freesound-aura-sand/146408_1274078-hq.mp3")
    save("shake_flakes", peak(hp(env(sl(sand, 0.6, 0.28), 0.02, 0.10), 150), -4))
    save("shake_flakes_2", peak(hp(env(sl(sand, 3.1, 0.28), 0.02, 0.10), 150), -4))
    marb = onset(load("freesound-marbles/marbles_628997.mp3"))
    save("shake_pellets", peak(hp(env(sl(marb, 0.0, 0.30), 0.003, 0.12), 200), -4))
    save("shake_pellets_2", peak(hp(env(pitch(sl(marb, 0.0, 0.33), 2), 0.003, 0.12), 200), -4))
    save("shake_shrimp", peak(hp(env(pitch(sl(sand, 5.2, 0.32), 4), 0.02, 0.10), 300), -4))
    # food lands in the water: water drops
    save("plop", peak(hp(env(onset(load(aq + "792927.mp3")), fade=0.08)), -1))
    save("plop_2", peak(hp(env(onset(load(aq + "792931.mp3")), fade=0.06)), -1))
    # a fish gets a bite: single bubble pops from 168861 (events at 0.25 s and 1.35 s)
    bub = load(aq + "168861.mp3")
    save("bite", peak(hp(env(onset(sl(bub, 0.18, 0.30)), fade=0.10), 150), -1))
    save("bite_2", peak(hp(env(onset(sl(bub, 1.28, 0.30)), fade=0.10), 150), -1))
    # the tide moves (2.5 s, follows the water level): water pour, slow in / out
    pour = load(aq + "173930.mp3")
    save("tide", peak(hp(env(sl(pour, 1.2, 2.6), 0.35, 0.9)), -1))
    swish = load(aq + "595794.mp3")
    save("tide_2", peak(hp(env(sl(swish, 1.15, 2.6), 0.35, 0.9)), -1))
    # the brass handle snaps to a mark
    save("click", peak(hp(env(onset(load("kenney_impact-sounds/Audio/impactMetal_light_001.ogg"))[: int(0.35 * SR)], fade=0.1)), -2))
    # the sea gate grille: a low wooden thud
    save("gate", peak(hp(env(pitch(onset(load("kenney_impact-sounds/Audio/impactPlank_medium_000.ogg"))[: int(0.6 * SR)], -5), fade=0.2), 40), -1))
    # UI cues (synthesised): new find = three-note marimba up, all six = 3 s rising arpeggio
    save("discover", peak(marimba([523.25, 659.25, 783.99], 0.13, 1.2), -1))
    save("allfound", peak(marimba([392.0, 523.25, 659.25, 783.99, 1046.5, 1318.5], 0.22, 1.9), -1))
    # optional pump / bubbles bed (settings, off by default, B24)
    loop_ogg(lp(hp(load(aq + "640597.mp3")), 5000), 20.0, 1.5, "pump")


if __name__ == "__main__":
    main()
