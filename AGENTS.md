# 潮汐水族 Tide Tank: project guidance

A 60 cm reef tank on the desk for PICO OS 6 Shared Space (Spatial SDK BOM 6.1.9), package `io.embodit.tidetank`, v0.1.1.
Design contract: spatial-app-factory `apps/aquarium/design-package.json` v1.2 (gate B passed 2026-11-15 with the defaults:
Shared Space volume, one jar cycling three foods, three tide stops, six creatures, whole-fish sway because the Tripo
models have no rig). Skeleton from the factory's Zen Orb (volume + `Placement.right` control window, theme, sound pool,
board-input template), TabBar / volume slider from Kinetica; the tank, the simulation and the window pages are new.
GitHub: `kejing-dev/TideTank` (private).

## Surfaces
- **Tank volume** (default container `TideTankVolume`, Volumetric 1.19 x 0.784 x 0.644 m, fixed scale, gravity aligned,
  uniform resize). Origin = volume centre, +X right, +Y up, +Z toward the user (`domain/Geo.kt` holds every measure).
  v0.1.1 (QA #1 q1-2, D54): every measure is in *tank units* (the design's 60 cm tank, volume 0.85 x 0.56 x 0.46) and the
  scene root is drawn `Geo.SCALE` = 1.4 x: the tank is 0.84 m, 70.6 % of the volume width, 27 deg at the system's 1.75 m.
  Hits and drags are converted back to tank units (`TankListener.toVolume` / `dragScale`).
  Start-up (q1-3): the shell (procedural glass, water, sand, base, post marks, handle, jar holder) shows first (~0.5 s);
  the 12 GLBs attach as they arrive (`arrivals`, drained in `onFrame`) and fade in over 0.7 s; the food pool follows.
  Walnut base + brass plaque (3D words), 5 glass panes, water body (scaled to the level), sand bed, Tripo rock slope /
  anemone / 3 seagrass tufts, sea gate (deep board + brass grille that fades open at high tide), 16 creatures, a 120-slot
  food pool, bubbles, the dashed drop column, the jar on its pedestal (lid colour = food), the tide post with gauge marks,
  float, the brass handle and the 「面板」 board (tap = reopen the control window), one name tag per kind.
- **Control window** (`TideTankControl`, Planar 600 x 440 dp fixed (v0.1.2; was 600 x 600), `Placement.bottom(TideTankVolume)` via `domain/ControlPlacement.kt` (v0.1.2; was right)): native TabBar
  水缸 / 图鉴 / 设置. In place: how-to card (S01), all-found card (S09), guide detail (S08b), confirm rows (S11).

## Code
| Path | What |
|---|---|
| `domain/Geo.kt` | all metres (tank, rock slope, jar travel, post, tags, eyes), `Tide` (+ hysteresis snap), `Food`, `Kind`, `Spot` |
| `domain/Tank.kt` | the single state source: jar carry / pour, tap feed, finding rules, particles, boids + per-kind behaviour, fullness, tide phases (visitors out -> grille -> water -> grille), queue, hints, tag placement |
| `domain/Texts.kt` | creature words (name / clue / fact / plain) and every plaque / status line, zh + en, copied from the design |
| `data/Stores.kt` | `files/tank.json` (atomic) + settings prefs |
| `ui/tank/*` | `TankScene` (entities, per-frame copy in `onFrame` via `TankFrameSystem`), `TankScreen` (SpatialView + input routing), `Meshes`, `TextTex`, `input/BoardInput.kt` (factory template + `onPress`) |
| `ui/control/*` | the window pages; every Text logs `OVERFLOW <tag>` when cut, every page logs `BLANK <state> content=… blank=…%` |
| `tools/` | `build_models.py` (Tripo GLB calibration, D25), `make_sounds.py` (CC0), `build_textures.py`, `build_thumbs.py`, `build_icon.py`, `preview_models.py`, `tt.sh` |

Input (design input): board-input template, holders `jar` (6.5 x 9 x 6.5 cm), `handle` (7 x 6 x 4 cm), `tide:L|M|H` (gauge plates,
4.6 x 4.6 x 1 cm, v0.1.1), `sign`, `floor:sand:<0..11>`,
`floor:rock:<0..2>`, `floor:anemone`, `floor:grass`. Tap jar = next food; tap handle = next tide (L -> M -> H -> L); tap a gauge plate = that tide; tap floor = a pinch at the converted hit point
(cell centre fallback); drag jar = carry (x = start + dX, z = start + dZ - dY, x1.5 gain, scaled by the view's content/physical
ratio); drag handle = vertical only, snap on release. Glass, water and creatures have no collider.

## Build / test (kj-mbp-m5)
```bash
export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home
./gradlew assembleDebug :app:testDebugUnitTest      # 53 JVM tests: rules, diet, fullness, queue, tide phases, hysteresis, layout / volume bounds, spawn, boids, calibration, sight lines, hit replay, save, drag total, volume slider
~/.factory-venv/bin/python tools/build_models.py ; ~/.factory-venv/bin/python tools/make_sounds.py ; ~/.factory-venv/bin/python tools/build_icon.py --icon512 icon.png
DEV=<borrowed serial> tools/tt.sh install
```
Emulators are shared: borrow with `python3 -I bin/emu_borrow.py borrow aquarium` in the factory, return after.

## Debug broadcast (QA screenshots)
`adb shell am broadcast -a io.embodit.tidetank.DEBUG -p io.embodit.tidetank --es cmd <cmd> --es a "<arg>"` (or `tools/tt.sh cmd`).
Log tag `TideTank`. Every command goes through the same calls as the 3D input / window (no state shortcut).

| State | Command (pre-state) |
|---|---|
| S00 准备中 | `s00hold on`, force-stop + launch, capture (the save load waits until `s00hold off`, max 60 s; the tank shell is already up, the panel says 在放水……), `s00hold off` |
| S01 第一次 | `firstrun on` + relaunch, or `goto S01` |
| S02 闲着 L / M / H | `goto S02`, then `tide L|M|H` (wait 3.5 s; H also waits for the gate) |
| S03 拿着罐子 | `goto S03` (= `carry <food> 0.1 0.05 hold`: grab, move over the water, pours 1.5 s, then `hold`); `hold off` + `release` after |
| S04 开饭 | `food flakes|pellets|shrimp` then `tap 0.1 0.0`; capture 1-2 s later |
| S05 涨落潮 | `goto S05` (M<->L, frozen half way); `hold off` |
| S06 新发现 | `find chromis|clownfish|hermitCrab|seahorse|blueTang|moonJelly` (sets tide + food, taps the real spot; crab / tang / jelly wait for the tide, ~4-7 s); `findall` runs all six 9 s apart |
| S08 图鉴 | `page guide` (0 / 3 / 6 found: `reset` first, then `find` …) |
| S08b 详情 | `detail <kind>` (seen or not) |
| S09 集齐 | appears after the sixth find; `result on` |
| S10 设置 | `page settings` |
| S11 确认 | `confirm quit|reset`; `yes` = the confirm button |
| S12 回来了 | force-stop + relaunch with a save |
Other: `tide`, `food`, `tap x z`, `tapjar`, `tapsign`, `taphandle`, `tapgauge L|M|H`, `sweep <food> x0 x1 z`, `handle <dy>…`, `hint`, `hold on|off`, `lang zh|en|system`,
`sound on|off`, `volume 0..2`, `pump on|off`, `reduced on|off`, `closepanel`, `openpanel`, `testf on|off`, `dump`,
`placeside right|bottom|left [dp]` / `placeoff <dp>` (try another control-window placement, tuning only; ships bottom 0).
Cold start: logcat `STARTUP <segment> at <ms> since process start` and one `STARTUP summary` line when the tank shell shows (q2-2).

## Known
- The Mac emulator cannot send 3D pointer / pinch: jar carry and handle drag are driven by the broadcast through the same
  Tank calls; real hand / controller / eye-hand need the headset (D53 Shared Space risk, see factory feedback).
- Tripo models have no rig: fish sway as a whole (gate B q7).
- Name tags face +Z (the volume front; the system turns the volume to the user). No head pose is read.
