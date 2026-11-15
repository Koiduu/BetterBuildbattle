# Better Battle: 38-second ad

1920x1080, 60 fps, 38 s, on a 120 BPM grid: a bar is 2 s, every cut is on a bar line and every big
hit is on a beat. Any 120 BPM track will lock to it.

## The idea

Each scene acts its feature out and carries one caption. The caption is always in the same place
and stays up for more than two seconds. No scene shares a layout or a transition with another.
Two scenes are 3D: the camera tilts over the real list of achievements, and flies around a capture
of the real profile page.

| Time | What happens | The one line | Cut out with |
|---|---|---|---|
| 0:00-0:06 | A gold block drops and types "Build Battle needed an update." It tears apart, "So we built one." slams in and breaks into the block-built logo. | (the two lines) | blocks cover the screen and drop away |
| 0:06-0:12 | A build drops in block by block. Six votes fly in and the crowd bar fills. An SS stamp slams down. | Every build gets a grade. | the stamp grows until it fills the frame |
| 0:12-0:18 | A Speed Builders build goes up while a giant timer runs. Two bars race: your old best against this run. You win by 0.82 s. | Race your own best time. | whip pan |
| 0:18-0:24 | One achievement pops like it does in game, then the camera tilts over the full list and scrolls all 124 as the number counts. | 124 achievements to chase. | the list scrolls off the top |
| 0:24-0:32 | The profile page rises as a window in 3D. The camera flies to the rank, the activity grid, the achievements and the Speed Builders bests, lighting each one up, then pulls back. | Your whole career, on one page. | gold fills the frame |
| 0:32-0:38 | BUILD. BATTLE. BETTER. on three beats, then the logo, DOWNLOAD FREE, battle.aspir3.net. | | |

The profile in the tour is `site_profile.png`, a capture of the website preview (`node tools/preview.mjs`,
then `/p/Koidu`) with its sample data. To show a different player, capture that page at 1280 px wide,
2x scale, and replace the file; the camera stops are in `STOPS` in `ad.html`.

## Voice-over

`vo.py` makes a guide track with Piper. The lines and their start times are in `LINES`. The "ryan"
voice used for the guide is licensed CC BY-NC-SA, so it is for timing only: record the lines
yourself, or use a voice licensed for commercial use, before publishing.

## Files (made by `render.mjs` and `sfx.py` into `promo/out/`)

| File | Track | Starts at |
|---|---|---|
| `BetterBattle_video_only.mp4` | V1 | 0:00 |
| `sfx_mix.wav` (every effect, already timed) | A1 | 0:00 |
| `music_bed_120bpm.wav` (placeholder beat, swap for a real 120 BPM track) | A2 | 0:00 |
| `vo_mix.wav` (guide voice-over, see above) or your own | A3 | 0:00 |
| `sfx/*.wav`, `cues.csv` | the 17 one-shots and when each plays, to move or layer hits by hand | |
| `markers.edl` | timeline markers for every cut, hit and caption | |

`BetterBattle_ad_v4_voiceover.mp4` and `BetterBattle_ad_v4_no_voice.mp4` are the same video with the audio already mixed.

## In Resolve

1. Project Settings: 1920x1080, 60 fps, before importing.
2. Drop the video on V1 and the two audio files on A1 and A2, all at the start.
3. Timelines > Import > Timeline Markers from EDL, pick `markers.edl`.
4. To use real footage: cut it in over a scene, starting a quarter second after its cut marker and
   ending a quarter second before the next, so the transitions stay. The scenes that swap best are
   the timer (a real Speed Builders round) and the grade (the end of a real game).
5. Duck the music about 6 dB under the voice-over.
6. Deliver H.264, 1080p60, around 20 Mb/s.

## Rebuilding

    python3 promo/sfx.py promo/out
    python3 promo/vo.py path/to/voice.onnx promo/out
    node promo/render.mjs preview promo/out/BetterBattle_video_only.mp4 60

`promo/ad.html` plays in a browser; add `?t=12` to start from that second.
