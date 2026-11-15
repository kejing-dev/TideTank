# Changelog · 更新记录

## v0.1.1 · 2026-11-15 (QA #1 rework)
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
