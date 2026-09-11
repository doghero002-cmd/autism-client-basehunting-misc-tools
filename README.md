# Dogs BaseHunting/QQL Tools

A client-side addon for the AUTISM Client that bundles tools for finding bases on DonutSMP —
seed cracking, coordinate finding, RTP stash hunting, an auction-house flipper, and a set of
misc utilities. It loads as its own jar alongside the AUTISM client.

> **⚠ Anti-cheat warning:** several modules move or act automatically (RTP, Baritone, elytra,
> auto-eat, auto-log, etc.) and may be flagged by server anti-cheats. Use at your own risk.
>
> PLEASE USE PRISM LAUNCHER :SOB: minecrafts default launcher can mess things up

## What's inside

Everything lives in the module menu under these tabs:

| Tab | What it does |
| --- | --- |
| **SeedCracker** | Crack the world seed from structures/biomes (SeedCrackerX), with a seed + progress HUD. Fully configurable in the GUI. |
| **Finders** | Chunk scanners that flag hidden bases: Stash, Chunk, Spawner, SusChunk, Activity, Growth finders, plus the AI **Tunnel Base Finder** (digs a hazard-aware tunnel and alerts on bases) and the **Base Log Browser** (browse/delete logged bases). |
| **Entity** | EntityScanner, AntiTrap, BoneDropper, SpawnerProtect (silk-touch + webhook). |
| **Fake** | FakePay, FakePayments, FakeRoles (fake DonutSMP rank/nametag in chat), FakeScoreboard HUD. |
| **Render** | AutoRender, PaperRig (dispenser RNG), Amethyst ESP, Bedrock Hole ESP, RegionMap HUD. |
| **Movement** | Flight+ (elytra-boost fly). |
| **Trading** | AH Flipper — scans the auction house for underpriced flips (packet mode, or live API with a key). **AH Sniper** — buys a target item the instant it's listed at/under your price (manual GUI or API mode). |
| **Disabler** | Packet/movement disablers ported from Selena + Rise (all marked `[Patched]` — they no longer work on current anti-cheats, kept for reference). |
| **Dogs Misc Tools** | Sprint, AntiAFK, FastPlace, FreeLook, AutoEat, SwingSpeed, CoordSnapper, FakePlayer, AutoLog, AutoTool, TPASpammer, TabDetector, WeatherNotifier, HomeSetter, SkinChanger. |

Plus two DonutSMP stash tools:

- **Donut RTP Stash Finder** — RTPs around the map; when you land near 0,0 it digs down and
  searches for stash blocks (Baritone, with a "legit / smooth movement" profile), logging bases to `bases.txt`.
- **Relog Loader** — digs down, relogs to force the server to resend chunks, then flies so
  ESP can read the region.

## Requirements

- Minecraft `26.2`, Java 25+
- [Fabric Loader](https://fabricmc.net/) `0.19.3+` and Fabric API
- AUTISM Client (any version)
- [Baritone](https://github.com/doghero002-cmd/baritone) (baritone-meteor) — needed for the RTP/Relog/search movement
- DonutSMP API key — only needed for the AH Flipper's *live* mode

## Install

1. Download the jar from [Releases](../../releases).
2. Drop it in your `mods` folder alongside the AUTISM client (and Baritone if you use the movement tools).
3. Launch the game and open the module menu to enable what you want.

## Build

```powershell
# 1. Publish the AUTISM Client API locally (run from the AUTISM Client project folder).
.\gradlew.bat publishToMavenLocal --no-daemon

# 2. Build this addon (run from this folder).
.\gradlew.bat build --no-daemon
```

The jar is produced at `build/libs/`.

## Credits

- **SeedCrackerX** by KaptainWutax and 19MisterX98 (MIT) — seed-cracking engine.


## License

MIT — see `LICENSE`.
