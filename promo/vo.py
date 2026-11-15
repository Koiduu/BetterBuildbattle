"""Guide voice-over, made with Piper (pip install piper-tts) and a voice model you supply.

  python3 promo/vo.py <voice.onnx> <out-dir>

Writes <out>/vo_mix.wav (every line placed on the 38s timeline) and <out>/vo/NN.wav (each line).
Mind the licence of the voice you pick: several Piper voices, "ryan" among them, are
non-commercial only. Treat those as a timing guide and re-record before publishing.
"""
import os
import subprocess
import sys
import wave

import numpy as np

SR = 48000
TOTAL = 38.0
# (starts at, must end by, text, pace: higher is slower)
LINES = [
    (0.45, 2.3, "Build Battle needed an update.", 1.0),
    (2.6, 3.9, "So we built one.", 1.12),
    (9.35, 11.7, "Every build gets a grade.", 1.05),
    (15.55, 17.7, "Race your own best time.", 1.05),
    (19.9, 23.6, "One hundred and twenty-four achievements to chase.", 1.02),
    (25.1, 28.5, "Your whole career, on one page.", 1.05),
    (32.5, 33.0, "Build.", 1.1),
    (33.0, 33.5, "Battle.", 1.1),
    (33.5, 34.4, "Better.", 1.2),
    (35.55, 37.6, "Download it free.", 1.1),
]
# Deeper and tighter, trailer style: pitch down 5%, low-end and presence lift, compression, a short room.
CHAIN = ("asetrate=22050*0.95,aresample=48000,atempo=1/0.95,highpass=f=70,"
         "equalizer=f=130:t=q:w=1:g=4,equalizer=f=3200:t=q:w=1.2:g=3,"
         "acompressor=threshold=-20dB:ratio=4:attack=5:release=120:makeup=4,"
         "aecho=0.85:0.5:35:0.12,silenceremove=start_periods=1:start_threshold=-45dB,alimiter=limit=0.9")


def main(model, out):
    os.makedirs(os.path.join(out, "vo"), exist_ok=True)
    mix = np.zeros(int(TOTAL * SR))
    for i, (start, end, text, pace) in enumerate(LINES):
        raw = os.path.join(out, "vo", "raw.wav")
        path = os.path.join(out, "vo", "%02d.wav" % (i + 1))
        subprocess.run([sys.executable, "-m", "piper", "-m", model, "-f", raw, "--length-scale", str(pace)],
                       input=text.encode(), check=True, capture_output=True)
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", raw, "-af", CHAIN, "-ac", "1", "-ar", str(SR), path], check=True)
        os.remove(raw)
        with wave.open(path) as w:
            x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2") / 32768.0
        s = int(start * SR)
        x = x[:len(mix) - s]
        mix[s:s + len(x)] += x
        over = start + len(x) / SR - end
        print("%5.2f-%5.2f  %s%s" % (start, start + len(x) / SR, text, "   <-- runs %.2fs long" % over if over > 0 else ""))
    mix = np.clip(mix * 0.95, -1, 1)
    with wave.open(os.path.join(out, "vo_mix.wav"), "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.stack([mix, mix], 1) * 32767).astype("<i2").tobytes())


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "promo/out")
