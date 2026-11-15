"""Synthesizes the ad's sound effects and a placeholder 120 BPM beat, all from numpy.

  python3 promo/sfx.py <out-dir>

Writes:
  <out>/sfx/*.wav            every one-shot, for layering by hand in the editor
  <out>/sfx_mix.wav          all effects placed on the 30s timeline (drop at 0:00)
  <out>/music_bed_120bpm.wav placeholder chiptune beat, bar lines on every cut
  <out>/cues.csv             what plays when
"""
import csv
import os
import sys
import wave

import numpy as np

SR = 48000
TOTAL = 30.0
rs = np.random.RandomState(7)


def n(sec):
    return int(sec * SR)


def tt(sec):
    return np.arange(n(sec)) / SR


def env(sec, attack=0.002, decay=6.0):
    t = tt(sec)
    return np.minimum(t / attack, 1.0) * np.exp(-decay * t)


def sweep(f0, f1, sec, curve=3.0):
    """Sine whose pitch slides from f0 to f1 (fast at first)."""
    t = tt(sec)
    f = f1 + (f0 - f1) * np.exp(-curve * t / sec * 3)
    return np.sin(2 * np.pi * np.cumsum(f) / SR)


def svf(x, cutoff, q=1.2, mode="bp"):
    """State-variable filter; cutoff can change sample by sample."""
    cutoff = np.broadcast_to(np.asarray(cutoff, dtype=float), x.shape)
    low = band = 0.0
    out = np.empty_like(x)
    for i in range(len(x)):
        f = 2 * np.sin(np.pi * min(cutoff[i], SR / 6.5) / SR)
        low += f * band
        high = x[i] - low - band / q
        band += f * high
        out[i] = band if mode == "bp" else low if mode == "lp" else high
    return out


def onepole_lp(x, hz):
    a = np.exp(-2 * np.pi * hz / SR)
    out = np.empty_like(x)
    y = 0.0
    for i in range(len(x)):
        y = (1 - a) * x[i] + a * y
        out[i] = y
    return out


def reverb(x, sec=1.2, mix=0.35, seed=1):
    r = np.random.RandomState(seed)
    ir = r.randn(n(sec)) * np.exp(-5.0 * tt(sec) / sec * 1.4)
    ir = onepole_lp(ir, 5000)
    size = len(x) + len(ir)
    wet = np.fft.irfft(np.fft.rfft(x, size) * np.fft.rfft(ir, size), size)
    wet /= max(1e-9, np.abs(wet).max())
    dry = np.concatenate([x, np.zeros(len(ir))])
    return dry * (1 - mix * 0.3) + wet * mix * np.abs(x).max()


def norm(x, peak=0.9):
    return x * (peak / max(1e-9, np.abs(x).max()))


# ---------- one-shots
def thud():
    return norm(np.tanh(sweep(110, 42, 0.35) * env(0.35, 0.002, 11) * 2.2))


def sub_hit():
    body = sweep(160, 38, 0.7, 4) * env(0.7, 0.002, 5.5)
    click = rs.randn(n(0.7)) * env(0.7, 0.0005, 160) * 0.5
    return norm(np.tanh((body + click) * 2.5))


def impact():
    body = sweep(190, 32, 1.1, 4) * env(1.1, 0.002, 3.8)
    crack = svf(rs.randn(n(1.1)), 1800, 0.8, "lp") * env(1.1, 0.001, 22) * 0.9
    return norm(reverb(np.tanh((body + crack) * 2.8), 1.6, 0.4, 3))


def whoosh(sec=0.45, lo=300, hi=5200):
    t = tt(sec) / sec
    cut = lo + (hi - lo) * np.sin(np.pi * t) ** 2
    amp = np.sin(np.pi * t) ** 1.5
    return norm(svf(rs.randn(n(sec)), cut, 1.6, "bp") * amp)


def riser(sec=1.0):
    t = tt(sec) / sec
    noise = svf(rs.randn(n(sec)), 400 + 7000 * t ** 2, 1.4, "bp")
    f = 110 * 2 ** (t * 2.0)
    saw = 2 * ((np.cumsum(f) / SR) % 1.0) - 1
    return norm((noise * 0.8 + onepole_lp(saw, 2500) * 0.35) * t ** 2.2)


def glitch(sec=0.25):
    out = np.zeros(n(sec))
    pos = 0
    while pos < len(out):
        ln = rs.randint(n(0.008), n(0.035))
        seg = out[pos:pos + ln]
        if rs.rand() < 0.75:
            f = rs.choice([110, 220, 440, 880, 1760, 3520])
            seg[:] = np.sign(np.sin(2 * np.pi * f * np.arange(len(seg)) / SR)) if rs.rand() < 0.6 else rs.randn(len(seg))
        pos += ln
    step = 6     # crush it
    out = np.repeat(out[::step], step)[:n(sec)]
    return norm(out * np.linspace(1, 0.6, len(out)), 0.7)


def tick(f=2100):
    return norm(np.sin(2 * np.pi * f * tt(0.05)) * env(0.05, 0.0005, 90) + rs.randn(n(0.05)) * env(0.05, 0.0003, 600) * 0.6, 0.7)


def key():
    return norm(svf(rs.randn(n(0.04)), rs.uniform(1800, 3200), 2.0, "bp") * env(0.04, 0.0005, 130), 0.6)


def pop(f0=950, f1=380):
    return norm(sweep(f0, f1, 0.09, 2) * env(0.09, 0.001, 38))


def bell(freq, sec=0.7):
    t = tt(sec)
    x = sum(a * np.sin(2 * np.pi * freq * h * t) * np.exp(-d * t) for h, a, d in [(1, 1, 6), (2.0, 0.4, 9), (3.01, 0.2, 14), (4.2, 0.08, 20)])
    return x * np.minimum(t / 0.002, 1)


def chime(notes=(1318.5, 1975.5), gap=0.09):
    out = np.zeros(n(gap * len(notes) + 0.8))
    for i, f in enumerate(notes):
        b = bell(f)
        out[n(gap * i):n(gap * i) + len(b)] += b
    return norm(reverb(out, 0.9, 0.3, 5), 0.8)


def shutter():
    out = np.zeros(n(0.2))
    for at, g in [(0.0, 1.0), (0.07, 0.7)]:
        c = svf(rs.randn(n(0.03)), 3200, 1.0, "bp") * env(0.03, 0.0005, 150)
        out[n(at):n(at) + len(c)] += c * g
    return norm(out, 0.8)


def clatter(sec=0.75, count=46):
    """Lots of little blocks snapping into place."""
    out = np.zeros(n(sec + 0.15))
    for i in range(count):
        at = (i / count) ** 0.8 * sec + rs.uniform(-0.01, 0.01)
        f = rs.choice([330, 392, 494, 587, 659, 784, 988])
        c = (np.sign(np.sin(2 * np.pi * f * tt(0.05))) * 0.5 + rs.randn(n(0.05)) * 0.25) * env(0.05, 0.0005, 85)
        s = max(0, n(at))
        out[s:s + len(c)] += c * rs.uniform(0.4, 1.0)
    return norm(onepole_lp(out, 6000), 0.75)


ONE_SHOTS = {
    "thud": thud(), "sub_hit": sub_hit(), "impact": impact(), "whoosh": whoosh(), "swish": whoosh(0.28, 900, 7000),
    "riser": riser(1.0), "glitch": glitch(), "tick": tick(), "tock": tick(1500), "key": key(), "pop": pop(),
    "pop_hi": pop(1400, 620), "chime": chime(), "chime_hi": chime((1568, 2093, 2637), 0.07),
    "notify": chime((784, 1046.5, 1318.5), 0.08), "shutter": shutter(), "clatter": clatter(),
}

# ---------- the timeline (seconds, sound, gain, pan -1..1, what it's for)
CUES = []


def cue(t, name, gain=1.0, pan=0.0, note=""):
    CUES.append((round(t, 3), name, gain, pan, note))


# Hook, 0-4s
cue(0.28, "thud", 0.9, 0, "block lands")
for i in range(30):
    cue(0.35 + i * 0.035, "key", 0.28, rs.uniform(-0.3, 0.3), "typing" if i == 0 else "")
cue(1.0, "riser", 0.45, 0, "tension into the slam")
cue(1.75, "glitch", 0.6, 0, "line tears apart")
cue(2.0, "impact", 1.0, 0, "SO WE BUILT ONE")
cue(2.48, "whoosh", 0.5, 0, "words break into blocks")
cue(2.5, "clatter", 0.7, 0, "logo assembles")
cue(3.0, "sub_hit", 0.7, 0, "ribbon lands")
cue(3.0, "pop", 0.6, 0, "")
cue(3.08, "chime", 0.5, 0, "logo complete")
# Every cut gets a whoosh that peaks on the bar line, and a thud as the blocks drop
for c in (4, 8, 12, 16, 20, 24):
    cue(c - 0.24, "whoosh", 0.75, 0, "block wipe")
    cue(c + 0.02, "thud", 0.6, 0, "")
# Every feature: two title slams and the card sliding in
for T in (4, 8, 12, 16, 20):
    cue(T + 0.25, "sub_hit", 0.7, -0.3, "title line 1")
    cue(T + 0.5, "sub_hit", 0.9, -0.3, "title line 2")
    cue(T + 0.5, "tick", 0.5, -0.3, "")
    cue(T + 0.36, "swish", 0.45, 0.5, "card slides in")
    cue(T + 3.7, "swish", 0.35, 0, "everything leaves")
# A: grade
cue(5.0, "pop", 0.8, 0.4, "SS badge"); cue(5.0, "thud", 0.5, 0.4, "")
cue(5.5, "pop_hi", 0.5, 0.4, "+120 XP"); cue(5.65, "pop_hi", 0.5, 0.5, "NEW BEST")
cue(6.3, "chime_hi", 0.6, 0.4, "LEVEL UP")
# B: timer
for i in range(9):
    cue(8.6 + i * 0.1, "tick" if i % 2 == 0 else "tock", 0.4, 0.4, "timer runs" if i == 0 else "")
cue(9.5, "chime", 0.55, 0.4, "timer stops: PB"); cue(9.5, "pop", 0.6, 0.3, "")
for i in range(3):
    cue(9.7 + i * 0.15, "tick", 0.35, 0.4, "")
cue(10.0, "chime_hi", 0.65, 0.5, "WORLD RECORD"); cue(10.0, "pop_hi", 0.6, 0.5, "")
# C: achievements
for i in range(14):
    cue(12.5 + i * 0.05, "key", 0.3, 0.3, "counter rolls to 124" if i == 0 else "")
for i, f in enumerate(("pop", "pop", "pop_hi")):
    cue(13.5 + i * 0.25, f, 0.75, 0.4, "achievement %d" % (i + 1)); cue(13.74 + i * 0.25, "tick", 0.4, 0.4, "")
cue(14.25, "chime_hi", 0.55, 0.4, "")
# D: profile + share
cue(16.9, "pop_hi", 0.45, 0.4, ""); cue(17.0, "pop_hi", 0.45, 0.5, "")
cue(17.6, "tick", 0.5, 0.2, "Share card button")
cue(18.0, "shutter", 0.9, 0.4, "card captured")
cue(18.5, "chime", 0.6, 0.5, "Link copied"); cue(18.5, "pop", 0.6, 0.5, "")
# E: Discord
for i in range(15):
    cue(20.7 + i * 0.047, "key", 0.35, 0.3, "typing /stats" if i == 0 else "")
cue(21.5, "notify", 0.7, 0.4, "bot replies")
for i in range(3):
    cue(22.3 + i * 0.15, "pop_hi", 0.5, 0.2 + i * 0.2, "")
# End, 24-30s
cue(23.0, "riser", 0.7, 0, "build into the end card")
cue(24.5, "sub_hit", 0.85, -0.3, "BUILD."); cue(25.0, "sub_hit", 0.95, 0, "BATTLE.")
cue(25.5, "impact", 1.0, 0.2, "BETTER.")
cue(25.98, "whoosh", 0.5, 0, ""); cue(26.0, "clatter", 0.7, 0, "logo assembles")
cue(27.0, "impact", 1.0, 0, "DOWNLOAD FREE"); cue(27.05, "chime_hi", 0.6, 0, "")
cue(27.5, "sub_hit", 0.8, 0, "battle.aspir3.net"); cue(27.5, "pop", 0.5, 0, "")
cue(28.0, "tick", 0.4, 0, ""); cue(28.3, "tick", 0.4, 0, "")


def place(buf, x, t, gain=1.0, pan=0.0):
    s = n(t)
    x = x[:max(0, buf.shape[0] - s)]
    buf[s:s + len(x), 0] += x * gain * np.sqrt((1 - pan) / 2) * 1.414
    buf[s:s + len(x), 1] += x * gain * np.sqrt((1 + pan) / 2) * 1.414


def master(buf, peak=0.89):
    buf = np.tanh(buf * 0.9)
    return buf * (peak / np.abs(buf).max())


def write(path, x):
    if x.ndim == 1:
        x = np.stack([x, x], axis=1)
    with wave.open(path, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())


# ---------- placeholder beat: 120 BPM, so one bar is 2s and every cut is a bar line
def music_bed():
    buf = np.zeros((n(TOTAL), 2))
    beat = 0.5
    kick = np.tanh(sweep(130, 44, 0.3, 4) * env(0.3, 0.002, 9) * 2)
    hat = svf(rs.randn(n(0.05)), 9000, 0.9, "hp") * env(0.05, 0.0005, 70)
    clap = (svf(rs.randn(n(0.18)), 1700, 0.9, "bp") * env(0.18, 0.001, 22))

    def sq(freq, sec, duty=0.5, dec=9):
        ph = (freq * tt(sec)) % 1.0
        return onepole_lp(np.where(ph < duty, 1.0, -1.0), 4200) * env(sec, 0.003, dec)

    A = 110.0
    scale = [0, 3, 5, 7, 10, 12, 15, 17]        # A minor pentatonic
    roots = [0, 0, -4, -2]                      # one per bar: Am Am F G
    arp = [0, 2, 4, 7, 4, 2, 5, 3]
    b = 0
    while b * beat < TOTAL:
        t = b * beat
        bar = int(t // 2)
        root = roots[bar % 4]
        intro = t < 2.0
        full = 4.0 <= t < 24.0
        outro = t >= 26.0
        near_cut = any(0 < c - t <= beat for c in (4, 8, 12, 16, 20, 24))   # leave room for the whoosh
        if not intro and not outro and not (24 <= t < 26):
            place(buf, kick, t, 0.9 if full else 0.6)
        if full and b % 2 == 1:
            place(buf, clap, t, 0.5)
        if (full or 2 <= t < 4) and not near_cut:
            place(buf, hat, t + beat / 2, 0.22, 0.3)
            if full:
                place(buf, hat, t + beat / 4, 0.1, -0.3); place(buf, hat, t + 3 * beat / 4, 0.1, -0.3)
        if not outro:
            f = A / 2 * 2 ** (root / 12)
            for k in range(2):      # bass on the off-beats, ducked under the kick
                if not intro and not (near_cut and k == 1):
                    bass = np.sin(2 * np.pi * f * tt(0.22)) * env(0.22, 0.01, 7) + sq(f, 0.22, 0.5, 12) * 0.15
                    place(buf, bass, t + beat / 4 + k * beat / 2, 0.5)
        if full and not near_cut:
            for k in range(4):      # 16th-note arp
                note = scale[arp[(b * 4 + k) % len(arp)] % len(scale)] + root + 24
                place(buf, sq(A * 2 ** (note / 12), 0.11, 0.25, 22), t + k * beat / 4, 0.09, 0.5 if k % 2 else -0.5)
        b += 1
    # a low drone under the hook, and a held chord under the end card
    drone = np.sin(2 * np.pi * 55 * tt(2.0)) * np.linspace(0.2, 0.6, n(2.0)) * 0.5
    place(buf, drone, 0.0, 0.5)
    for note, pan in ((0, -0.4), (7, 0.4), (12, 0), (15, 0.2)):
        t = tt(4.0)
        pad = onepole_lp(np.where((A * 2 ** (note / 12) * t) % 1.0 < 0.5, 1.0, -1.0), 1800) * np.minimum(t / 0.01, 1) * np.exp(-0.9 * t)
        place(buf, pad, 26.0, 0.16, pan)
    return master(buf, 0.7)


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "promo/out"
    os.makedirs(os.path.join(out, "sfx"), exist_ok=True)
    for name, x in ONE_SHOTS.items():
        write(os.path.join(out, "sfx", name + ".wav"), x)
    mix = np.zeros((n(TOTAL), 2))
    for t, name, gain, pan, _ in CUES:
        place(mix, ONE_SHOTS[name], t, gain * 0.55, pan)
    write(os.path.join(out, "sfx_mix.wav"), master(mix))
    write(os.path.join(out, "music_bed_120bpm.wav"), music_bed())
    with open(os.path.join(out, "cues.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["time_s", "timecode_60fps", "sound", "gain", "pan", "what"])
        for t, name, gain, pan, note in sorted(CUES):
            fr = round(t * 60)
            w.writerow([t, "%02d:%02d" % (fr // 60, fr % 60), name, gain, round(pan, 2), note])
    print("wrote %d one-shots, sfx_mix.wav, music_bed_120bpm.wav, cues.csv to %s" % (len(ONE_SHOTS), out))
