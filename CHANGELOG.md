# Changelog · 更新记录

## v0.1.2 · 2026-11-15 (QA #2 rework · first release)

中文：
- 控制窗改到缸的正下方（600 × 440 dp），所有打开路径用同一个摆位；每页（缸页、图鉴第三列、设置 [退出应用]）都在默认视野里完整，中英文都不溢出
- 冷启动按段记录耗时；声音文件改到后台线程加载
- 拿起饲料罐时不再往水面 / 玻璃上投暗斑
- 已知问题：控制窗中心在视线下约 30°；坐着打开时缸按站姿高度摆（缸心在视线上约 13°）；模拟器换应用后的第一次冷启动偶尔 8–21 s

English:
- q2-1: the control window opens right under the volume (Placement.bottom, one placement in domain/ControlPlacement.kt for
  every open path), 600 x 440 dp; pages re-spaced (blank 11-18 %, no overflow zh / en). ControlPlacementTest: four corners
  in view for standing 1.6 m and seated 1.2 m eyes, <= 40 deg, not over the tank.
- q2-2: STARTUP segment logging from process start; sound files load off the main thread.
- QA #2 small: a lifted jar casts no ground shadow onto the water / glass.
- Known issues: control window centre ~30 deg below eye level; seated, the tank is placed at standing height (~13 deg above
  the eyes); the first emulator cold start after switching apps can take 8-21 s.

## v0.1.1 · 2026-11-15 (QA #1 rework)

中文：点黄铜手柄换到下一档潮位、点落 / 平 / 涨刻度牌直接换档；缸放大到体积宽度的 70%；约 0.5 秒先出缸体，模型陆续淡入。
- q1-1: tap the brass handle = next tide (L -> M -> H -> L); tap a 落 / 平 / 涨 gauge plate = that tide (collider + system hover
  on each plate); the drag is unchanged (DragTotal). Debug `taphandle`, `tapgauge L|M|H`. TideTapTest.
- q1-2: the tank fills the volume: scene root drawn 1.4 x (tank 0.84 m), volume 1.19 x 0.784 x 0.644 m, pedestal and post
  0.5 cm inward; tank 70.6 % of the volume width, 27 deg at 1.75 m; 1 cm physical margin kept (LayoutTest).
- q1-3: the shell (glass, water, sand, base, post marks, handle, jar holder) is shown in ~0.5 s; the 12 models and the food
  pool attach as they arrive and fade in.
- Small: hermit crab guide picture no longer cut flat; food grains / bubbles / drop column cast no shadow and the water /
  glass take none (the dark smear after feeding); the volume slider has an accessible name, state and one-stop a11y steps;
  loudness file rendered to 30 s.

## v0.1.0 · 2026-11-14

中文：第一版。海水缸体积窗口 + 控制窗；拿罐撒食、点缸底撒一撮、拖潮汐把手（三档吸附 + 滞回）；10 条青魔 boids 群游觅食；6 种生物出场规则、发现队列、图鉴与详情、线索；底座铭牌和名字牌；中英双语、静音、音量三档、水泵声、减少动态、重开控制窗、重置、退出。

English: first version. Tank volume + control window; carry-and-pour feeding, tap-to-feed, the three-stop tide handle with
hysteresis; a boids school; six finding rules, a discovery queue, the guide and its detail page, clues; brass plaque and
name tags; Chinese / English, mute, three-stop volume, pump hum, reduced motion, reopen controls, reset, quit.
