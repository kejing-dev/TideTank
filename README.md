# 潮汐水族 Tide Tank

[中文](#中文) · [English](#english)

## 中文

桌上一只 60 cm 的海水缸（PICO OS 6 Shared Space 体积窗口）。拿起饲料罐在水面上划，饲料一路撒下去，鱼群跟着抢；拖潮汐闸的黄铜把手让水涨落，落潮露出礁石、涨满潮外海的门打开。换着饲料和潮位，把缸里藏着的 6 种海洋生物一个个引出来，图鉴一格格点亮。

- 体积窗口 1.19 × 0.78 × 0.64 m（缸 0.84 m 宽，v0.1.1） + 右侧控制窗（600 × 600 dp，原生 TabBar：水缸 / 图鉴 / 设置）
- 三种饲料（浮片 / 沉粒 / 丰年虾）、三档潮位、6 种生物各有出场条件
- 中英双语、静音、退出；全离线

## English

A 60 cm reef tank on your desk (PICO OS 6 Shared Space volume). Pick up the food jar and sweep it over the water: food
trails down and the school chases it. Drag the brass tide handle: low tide bares the rocks, high tide opens the sea gate.
Mix food and tide to coax out all six hidden creatures and fill the guide.

Build: see `AGENTS.md`. Package `io.embodit.tidetank`.

Third-party: 3D creatures and props generated with Tripo (the developer's account), decimated / calibrated by
`tools/build_models.py`; sounds are CC0 recordings (freesound.org, Kenney) listed in `tools/make_sounds.py`; Noto Sans (OFL);
Material Symbols (Apache-2.0).
