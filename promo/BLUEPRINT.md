# Better Battle: 30-second ad, DaVinci Resolve blueprint

Timeline: 1920x1080, 60 fps, 30 s. The edit sits on a 120 BPM grid, so one bar is 2 s
(120 frames), every cut is on a bar line and every big hit is on a beat (30 frames).

## Files (made by `render.mjs` and `sfx.py` into `promo/out/`)

| File | What it is | Track | Starts at |
|---|---|---|---|
| `01_hook.mp4` | The hook, finished | V1 | 0:00 |
| your clips A to E | 4 s each (record longer, trim to fit) | V1 | 0:04, 0:08, 0:12, 0:16, 0:20 |
| `02_overlay_A_graded.mov` ... `E_discord.mov` | Titles, stat card and block wipes, with transparency | V2 | 0:04, 0:08, 0:12, 0:16, 0:20 |
| `03_end.mp4` | The end card, finished | V1 | 0:24 |
| `sfx_mix.wav` | Every sound effect, already timed | A1 | 0:00 |
| `music_bed_120bpm.wav` | Placeholder beat. Swap for any 120 BPM track | A2 | 0:00 |
| your voice-over | | A3 | see below |
| `sfx/*.wav` | The 17 one-shots, to add or move hits by hand | | `cues.csv` lists every hit |
| `markers.edl` | Timeline markers for every cut and beat hit | | |

## Shot list

| Time | On screen | Record this | Voice-over |
|---|---|---|---|
| 0:00-0:04 | A gold block drops and types "Build Battle needed an update." It tears apart, "So we built one." slams in and breaks into the block-built logo. | nothing | "Build Battle needed an update. So we built one." |
| 0:04-0:08 | EVERY BUILD, GRADED. Card: SS grade, crowd score, level-up bar. | End of a Solo game, the recap showing in chat | "Every build graded. Every win ranked." |
| 0:08-0:12 | EVERY SECOND, TIMED. Card: timer stops on a PB, world record tag. | A Speed Builders round with the timer on screen | "Speed Builders, with a real timer, personal bests and world records." |
| 0:12-0:16 | EVERY GAME, COUNTS. Card: counter rolls to 124, three achievements. | An achievement popping, then the Achievements screen | "A hundred and twenty-four achievements, plus weekly challenges." |
| 0:16-0:20 | EVERY STAT, SHAREABLE. Card: share card, shutter flash, "Link copied". | Your profile on battle.aspir3.net, clicking Share card | "Your whole career on one profile. Share it anywhere." |
| 0:20-0:24 | EVERY FRIEND, CHALLENGED. Card: /stats typed, the bot replies. | Running /stats in the Discord | "Flex it in Discord, or call out a friend." |
| 0:24-0:30 | BUILD. BATTLE. BETTER. on three beats, then the logo, DOWNLOAD FREE and the site. | nothing | "Build. Battle. Better. Free, at battle dot aspir3 dot net." |

Recording tips: 1080p60, hide the F3 screen, record 6 to 10 s per shot so there is room to pick the best 4.
The stat card covers the right third of the frame and the title covers the bottom left, so keep the action
in the upper left and centre.

## Building it in Resolve

1. File > Project Settings: 1920x1080, timeline and playback 60 fps. Do this before importing anything.
2. Import the whole `out` folder into the Media Pool.
3. Right-click the timeline > Timelines > Import > Timeline Markers from EDL, pick `markers.edl`.
4. Place the files using the table above. With snapping on (N), clips lock to the markers.
5. If the overlays show a black background, right-click one in the Media Pool > Clip Attributes > Alpha Mode: Straight.
6. Polish, in order of payoff:
   - Punch-in: on each footage clip, keyframe Zoom from 1.00 to 1.06 across its 4 s (Inspector > Transform),
     so nothing on screen is ever still.
   - Speed ramp: Retime Controls (Ctrl+R) on each clip, fast for the first half second, then normal speed,
     landing on the moment the stat card arrives (0.7 s in).
   - Duck the music: on A2, drop about 6 dB under the voice-over (Fairlight > sidechain compressor keyed
     from A3, or just keyframe the volume).
   - Colour: a light contrast and saturation boost on the footage only makes the overlays sit better.
7. Deliver: H.264, 1080p60, around 20 Mb/s for YouTube.

## What changed from the first script, and why

- No Hypixel icon or logo anywhere. The ad says "Build Battle" and ends with "fan-made, not affiliated with
  Hypixel", so nobody can take it for an official update.
- "Custom titles, badges, cosmetic loadouts" is gone: that isn't in the product. The profile beat shows the
  real share card instead.
- "Copied to Clipboard!" is now "Link copied", the text the site really shows.
- The call to action is battle.aspir3.net, where the download is, not GitHub.
- Glass panels became Minecraft-style bevelled panels, matching the website and the mod's own menus.
- Achievements got their own beat: 124 of them is one of the strongest things the mod has, and it's free.
