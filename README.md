<div align="center">

<img src="docs/readme-banner.svg" alt="Dogs BaseHunting / QQL Tools" width="900">

# Dogs BaseHunting / QQL Tools

**Base hunting, stash discovery, and DonutSMP utilities for AUTISM Client**

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-52a535?style=flat-square&logo=minecraft&logoColor=white)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Fabric-0.19.3%2B-dbd0b3?style=flat-square)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-25%2B-e76f51?style=flat-square&logo=openjdk&logoColor=white)](https://adoptium.net/)
[![License](https://img.shields.io/badge/license-The%20Unlicense-f4a261?style=flat-square)](LICENSE)

</div>

A client-side addon for the AUTISM Client that bundles base-finding tools for DonutSMP, including
seed cracking, coordinate finding, RTP stash hunting, an auction-house flipper, and a broader set of
utility modules. It loads as its own jar alongside the AUTISM client.

<div align="center">

[Overview](#overview) · [Features](#features) · [Quick Start](#quick-start) · [Requirements](#requirements) · [Install](#install) · [Build](#build) · [Credits](#credits) · [License](#license)

</div>

<div align="center">

| FIND | CRACK | SCAN | AUTOMATE |
| :---: | :---: | :---: | :---: |
| Base and stash discovery | Seed and terrain analysis | ESP, overlays, and radar | RTP, Baritone, and utility/macro tools |

</div>

> [!WARNING]
> Several modules move or act automatically (RTP, Baritone, elytra, auto-eat, auto-log, etc.) and may be flagged by server anti-cheats. Use at your own risk.
>
> Please use Prism Launcher if possible; the default Minecraft launcher can cause issues.

## Overview

This addon combines a handful of different modules into one package so you can use a single jar with AUTISM Client instead of juggling multiple separate mods. The project focuses on base hunting, stash discovery, seed utility work, and general quality-of-life tools.

## Features

### What this pack is for

This addon bundles base-finding, stash scouting, seed-cracking, and utility tooling into a single AUTISM Client jar. It is designed for DonutSMP-style hunting workflows and for players who want a compact, all-in-one toolset without juggling separate mods.

### Core modules

- **Seedcracker** — SeedCrackerX-style seed cracking and structure analysis.
- **Bedrock Finder** — Terrain and coordinate-based discovery tools.
- **Texture Cracker** — Visual and texture-led crack/search helpers.
- **Donut RTP Stash Finder** — RTP-based stash hunting around 0,0 and nearby structures.
- **Relog Loader** — Forces chunk refreshes so discovery tools can read newly generated terrain.

### Module groups

| Section | Included tools |
| --- | --- |
| **Core** | Seedcracker, Bedrock Finder, Texture Cracker, Donut RTP Stash Finder, Relog Loader |
| **Finders** | **Finders hub** (one entry bundling the simple set-and-forget finders: Sign, Portal, Player Chunks, Structure Detector, Base Webhook, Finder Overlay), Netherite Finder, Stash Finder, Chunk Finder, Spawner Finder, SusChunk Finder (DOGS/GEODE/BETA/… modes), SeedRay, Seed Map, Light Source Finder, Prime Chunk, Activity, Growth, Heat Map Radar, Raid Planner, Chunk Keeper, Base Log Browser, Tunnel Base Finder (CRAWL/STANDING/AMETHYST mining styles), Nether Tunnel Finder |
| **Entity** | Entity Scanner, AntiTrap, Eye Finder, Item Frame ESP, Bone Dropper, Spawner Protect |
| **Render** | AutoRender, PaperRig, Scoreboard Hider, Storage Recorder |
| **Fake** | Fake Identity (fake /pay, fake incoming payments, fake rank — one module), Fake Player |
| **Trading** | AH Flipper (API-key mode *and* keyless mode — page scans + your own confirmed sales from chat/fills), AH Sniper, Shop Buyer, AH Sell |
| **Dogs Misc Tools** | Sprint, AntiAFK, FastPlace, AutoEat, AutoMine, SwingSpeed, CoordSnapper, FakePlayer, AutoLog, Player Panic, Flag Detector, Macro Protector, Spectator Detector, Panic Pay, Anti-Cheat Guesser, Fake Latency, Position Packet Filter, Coordinate Protector, Auto Store, Auto Smelt, Chest Stealer, Auto Replenish, Balance Tags, AutoTool, TPASpammer, Tab Detector, Weather Notifier, Home Setter (SET + META modes), Skin Changer, Chat Games, Region Map, Schematic Builder, Elytra Warner, Translate, Hole Tunnel Stairs ESP, Amethyst ESP, Bedrock Hole ESP, Key Pearl, Auto Firework, Auto TPA, Quick Macro, Name Protect, Mace PVP, Waypoints (death + logout + finder-marker Y) |

### DonutSMP stash tools

- **Donut RTP Stash Finder** — Uses RTP plus directed digging around key areas to locate stash blocks and log the results to `bases.txt`.
- **Relog Loader** — Digs down, relogs, and re-pulls chunks so finder modules can read fresh terrain more reliably.

> For tunnel base hunting, the water variant of the tunnel finder is usually the safer and less disruptive option for avoiding flags.

## Recommended setup by task

### Best stash-finding setup

- **Stash Finder**
- **Relog Loader**
- **Prime Chunk Finder**
- **AutoRender**
- **Elytra Warner**
- **Auto Firework**
- **Spectator Detector**
- **Amethyst ESP**
- **Storage Recorder**
- **Chunk Keeper**

### Best tunnel-base setup

- **Tunnel Base Finder** (Water / Dogs mode)
- **Storage Recorder**
- **Prime Chunk Finder**
- **Finder Overlay**
- **Amethyst ESP**

### Useful utility modules

- **Player Panic** — immediate defensive stop/disable state for risky automation.
- **Chest Stealer** — fast container pickup and inventory management.
- **Auto Replenish** — automatic item restock workflow.
- **Balance Tags** — tags and tracking for balance-heavy inventory work.
- **Translate** and **Home Setter's META mode** — helpful multitool utilities for quick in-game workflows.

## Quick Start

1. Download the jar from the releases page and place it in your `mods` folder alongside the AUTISM client.
2. Install the required dependencies, especially [Fabric Loader](https://fabricmc.net/) and [Baritone](https://github.com/doghero002-cmd/baritone), if you plan to use movement-based modules.
3. Launch the game, open the module menu, and enable only the tools you want to use.

### New here? Use a loadout

Instead of picking from the full module list, open the **QQL Setup** module (first in the menu) and press one button for what you want to do, or run the `.qql` command in chat:

- `.qql` — list every loadout.
- `.qql stash` — Stash Hunting (stash finder, relog loader, prime chunk, auto-render, ESP, …).
- `.qql tunnel` — Tunnel Base hunting.
- `.qql trading` — AH flipper + sniper + price check (sniper capped to 1 buy by default).
- `.qql pvp` — everyday survival QoL + anti-death safety nets.
- `.qql safety` — staff / anti-cheat detection and panic exits only.
- Add `keep` to layer a loadout on top of your current modules (e.g. `.qql stash keep`); `.qql off` turns everything off.

Most modules now hide their expert options behind a **Show advanced** toggle, so the default settings panel stays short — flip it on when you want the fine-tuning.

## Requirements

- Minecraft `26.2`
- Java `25+`
- [Fabric Loader](https://fabricmc.net/) `0.19.3+` and Fabric API
- [AUTISM Client](https://github.com/AutismDevelopment/Autism-Client) (any compatible version)
- [Baritone](https://github.com/cabaletta/baritone) (`baritone-meteor`) — required for the RTP / Relog / search movement
- DonutSMP API key — optional; the AH Flipper's API mode uses it, but keyless mode works without one (page scans + your own confirmed sales)

## Install

1. Download the jar from [Releases](../../releases).
2. Drop it in your `mods` folder alongside the AUTISM client and Baritone if you plan to use movement-based tools.
3. Launch the game and open the module menu to enable the features you want.

## Build

From the project root, build the addon with either of these commands:

```bash
# Linux / macOS
./gradlew build --no-daemon
```

```powershell
# Windows
.\gradlew.bat build --no-daemon
```

The jar is produced in `build/libs/`. A pre-built copy of the latest release is also tracked under `dist/`.

> **Required for builds:** the AUTISM Client API jar must be present in `libs/` to compile. The build resolves it via a `flatDir` repository. The repo tracks the pinned compile target `libs/autism-5.0-26.2-dev.jar`. The additional client jars used during development (`autism-5.1-26.2.jar` = Client V4, `autism-earlyfix.jar`) are **not tracked** — if you need to build against Client V4 / EarlyFix, drop `autism-<version>.jar` into `libs/` and point `autism` in `gradle/libs.versions.toml` at that version, then rebuild.
>
> To upgrade the client, drop the new `autism-<version>.jar` into `libs/` and bump `autism` in `gradle/libs.versions.toml`. The project is currently pinned to AUTISM Client `5.1-26.2` (V4, addon API v4). At runtime the addon declares the lower of that and the installed client's API version, so the same jar also loads on the public `5.0-26.2` client (API v3).
>
> **AUTISM Client version:** the build resolves the API with a Maven version range (`[3.4,)`), so it uses the newest client you have published locally instead of locking to one exact version. The built jar declares `autism: "*"` and loads on compatible client versions. If a new major client release changes the API, the addon may need source updates. Incase for people with v6 (v 5.1 jar) put that in libs folder for build to be compatible 

## Credits

- **SeedCrackerX** by KaptainWutax and 19MisterX98 (MIT) — seed-cracking engine.

## License

This repository is licensed under [The Unlicense](LICENSE).

---

The project is released under The Unlicense, which means you are free to use, modify, and redistribute it however you want, as long as the software is provided as-is without warranty.
