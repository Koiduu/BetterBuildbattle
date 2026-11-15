# Better Battle: 36-second ad

1920x1080, 60 fps, 36 s, on a 120 BPM grid: a bar is 2 s, every scene is 6 s (three bars) and
every big hit is on a beat. Any 120 BPM track will lock to it.

## The idea

Each scene acts its feature out and carries one caption. The caption is always in the same place
and stays up for more than two seconds. No scene shares a layout or a transition with another.

| Time | What happens | The one line | Cut out with |
|---|---|---|---|
| 0:00-0:06 | A gold block drops and types "Build Battle needed an update." It tears apart, "So we built one." slams in and breaks into the block-built logo. | (the two lines) | blocks cover the screen and drop away |
| 0:06-0:12 | A build drops in block by block. Six votes fly in and the crowd bar fills. An SS stamp slams down. | Every build gets a grade. | the stamp grows until it fills the frame |
| 0:12-0:18 | A Speed Builders build goes up while a giant timer runs. Two bars race: your old best against this run. You win by 0.82 s. | Race your own best time. | whip pan |
| 0:18-0:24 | One achievement pops like it does in game, shrinks into the first tile of a wall, and all 124 tiles light up as the number counts. | 124 achievements to chase. | the wall falls away |
| 0:24-0:30 | A leaderboard rushes past while your gold row holds the middle and the number climbs from #2,400 to #382. | See where you really rank. | your row grows until it fills the frame |
| 0:30-0:36 | BUILD. BATTLE. BETTER. on three beats, then the logo, DOWNLOAD FREE, battle.aspir3.net. | | |

Voice-over, if you want one: read the hook's two lines, then each caption as it appears, then
"Build. Battle. Better. Free, at battle dot aspir3 dot net." Each caption appears about 3.4 s into
its scene, so start each line there.

## Files (made by `render.mjs` and `sfx.py` into `promo/out/`)

| File | Track | Starts at |
|---|---|---|
| `BetterBattle_video_only.mp4` | V1 | 0:00 |
| `sfx_mix.wav` (every effect, already timed) | A1 | 0:00 |
| `music_bed_120bpm.wav` (placeholder beat, swap for a real 120 BPM track) | A2 | 0:00 |
| your voice-over | A3 | |
| `sfx/*.wav`, `cues.csv` | the 17 one-shots and when each plays, to move or layer hits by hand | |
| `markers.edl` | timeline markers for every cut, hit and caption | |

`BetterBattle_ad_v3.mp4` is the same video with both audio files already mixed in.

## In Resolve

1. Project Settings: 1920x1080, 60 fps, before importing.
2. Drop the video on V1 and the two audio files on A1 and A2, all at the start.
3. Timelines > Import > Timeline Markers from EDL, pick `markers.edl`.
4. To use real footage: cut it in at a scene's cut marker and keep the next transition by ending
   your clip a quarter second before the following cut marker. The scenes that swap best are the
   timer (a real Speed Builders round) and the grade (the end of a real game).
5. Duck the music about 6 dB under the voice-over.
6. Deliver H.264, 1080p60, around 20 Mb/s.

## Rebuilding

    python3 promo/sfx.py promo/out
    node promo/render.mjs preview promo/out/BetterBattle_video_only.mp4 60

`promo/ad.html` plays in a browser; add `?t=12` to start from that second.
