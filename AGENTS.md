# 潮汐水族 Tide Tank: project guidance

A 60 cm reef tank in front of you for PICO OS 6 **Full Space** (one Mixed Stage, Spatial SDK BOM 6.1.9), package `io.embodit.tidetank`, v0.1.4.
v0.1.3 (gate C, 2026-11-16, profile D53 补): moved from a Shared Space volume to a Mixed Stage so the food jar can be a
toggle that follows a hand / controller (volumes get no hand / controller / head poses: NOT_IN_FULL_SPACE).
Design contract: spatial-app-factory `apps/aquarium/design-package.json` v1.2 (gate B passed 2026-11-15 with the defaults:
Shared Space volume, one jar cycling three foods, three tide stops, six creatures, whole-fish sway because the Tripo
models have no rig). Skeleton from the factory's Zen Orb (volume + `Placement.right` control window, theme, sound pool,
board-input template), TabBar / volume slider from Kinetica; the tank, the simulation and the window pages are new.
GitHub: `kejing-dev/TideTank` (private).

## Surfaces
- **Tank** (Mixed Stage, `Main.kt` DefaultStage, manifest `pico.spatial.stage.*`): the scene root is placed yaw-only from the
  head (`domain/ControlPlacement.placeTank`: tank centre 0.88 m ahead, rim 0.14 m under the eye (v0.1.4 q4-1: every box corner inside the +-39 deg view); re-aimed for 2 s after the
  first valid head pose, then locked; no head for 5 s -> fallback). Drawn at `Geo.SCALE` = 1.0 (the design's 60 cm tank);
  every measure is in tank units, origin = old volume centre, +X right, +Y up, +Z toward the user (`domain/Geo.kt`).
  Shell first, the 12 GLBs fade in as they arrive. Stick x turns the tank (60 deg/s) while the jar is home.
- **Control window** (`TideTankControl`, Planar 600 x 440 dp fixed): opened from the Stage through the level gate
  (`platform/WindowCtl` + `domain/LevelGate`); the system puts it 1.75 m straight ahead; the tank sits under it.
  Settings [叫回面前] = put the window away, wait for a still head (RecallSampler), re-place the tank, reopen the window.
  Native TabBar 水缸 / 图鉴 / 设置; how-to card (S01, five rows: pick / pour / put back / tide / find), all-found card,
  guide detail, confirm rows. Exit closes the window and the Stage, then finishAndRemoveTask.

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

Input. **The jar is a toggle (D53 补)** — `domain/JarInput` + `JarHold`, fed every frame by `ui/tank/StageRig`:
- press = rising edge of grip OR trigger (controller) or a pinch (thumb-index < 1.5 cm, hand tracking);
- press with the controller tip / pinch point <= 10 cm from the jar = DIRECT pick (follows the hand in 6DoF from the pick
  offset, `Geo.clampDirect` keeps it out of the water and the glass); press with the controller's own ray on the jar
  (7 cm sphere) = RAY pick (the hand's Stage translation moves it 1:1 over the water in X / Z on the carry plane y 0.17);
  eye-hand / hand ray come as a system tap on the jar (`JarInput.systemTap`, anchored to the hand that pinched last);
- letting go does nothing; the holder's next press puts it back on the pedestal; system taps within 1.5 s of a tracker
  toggle are the same press and are swallowed;
- roll since the pick about the controller / wrist forward axis tips the jar; >= 60 deg pours, faster up to 110 deg; food
  falls straight under the mouth; the 3D tip card 「翻手倒食物 · 再按 grip / 扳机放回」 shows at a pick until the first
  put-back; a controller holding the jar hides the Stage upper-limb rendering (`LIMB Hidden`).
Other targets through the board-input template (system pointer): `handle` drag (vertical, snap) / tap (next tide), gauge
plates `tide:L|M|H`, `sign` (= [叫回面前]), `floor:*` taps feed at the hit point. Food changes on the panel.

## Build / test (kj-mbp-m5)
```bash
export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home
./gradlew assembleDebug :app:testDebugUnitTest      # 74 JVM tests (HoldTest = the jar toggle replayed at 30 Hz, ControlPlacementTest = Full Space placement): rules, diet, fullness, queue, tide phases, hysteresis, layout / volume bounds, spawn, boids, calibration, sight lines, hit replay, save, drag total, volume slider
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
| S03 拿着罐子 | `goto S03` (= `carry <food> 0.1 0.05 hold`: injected controller touches the jar + click, moves over the water, rolls 90 deg, pours, then `hold`); `hold off` + `click` (put back) after. Tip card: only until the first put-back of a run (cold start first) |
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
`recall`.
Jar / input injection (same per-frame StageRig path as real input, nothing sets the jar directly): `ctrl x y z [roll] [0|1]`
(right controller tip at a tank point, pointing into the tank), `ctrlaim x y z [0|1]` (controller at the hip, its ray on
a tank point), `ctrlmove dx dy dz [steps]` (Stage metres), `ctrlroll deg`, `press 0|1`, `click`, `ctrloff`,
`hand x y z [roll] [0|1]`, `handoff`, `head x y z yawDeg pitchDeg` / `headoff`, `stick x [sec]`, `tapjar` (a system tap).
Cold start: logcat `STARTUP <segment> at <ms> since process start` and one `STARTUP summary` line when the tank shell shows (q2-2).

## Known
- The Mac emulator has no 3D pointer, no controller (`controller support=DEVICE_NOT_SUPPORTED`) and no hand data; the
  emulator camera sits ~0.27 m ahead of the head pose it reports, so the tank looks closer in captures. Real hand /
  controller / eye-hand need the headset; HoldTest replays event streams through the same JarInput path.
- `pico-cli camera move` turns the capture camera but not the head pose the app reads; use `head …` to test placement /
  the level gate.
- Full Space system windows draw over Stage entities: the carried jar and its tip are kept under the window (unit test).
- Tripo models have no rig: fish sway as a whole (gate B q7).
