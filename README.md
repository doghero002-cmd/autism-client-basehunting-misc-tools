# Dogs BaseHunting/QQL Tools

A client-side addon for the AUTISM Client that bundles tools for finding bases on DonutSMP —
seed cracking, coordinate finding, RTP stash hunting, an auction-house flipper, and a set of
misc utilities. It loads as its own jar alongside the AUTISM client.

> **⚠ Anti-cheat warning:** several modules move or act automatically (RTP, Baritone, elytra,
> auto-eat, auto-log, etc.) and may be flagged by server anti-cheats. Use at your own risk.
>
> PLEASE USE PRISM LAUNCHER :SOB: minecrafts default launcher can mess things up

## What's inside

The addon currently includes these modules:

| Tab | What it does |
| --- | --- |
| **Core** | **Seedcracker** (SeedCrackerX seed cracking), **Bedrock Finder**, **Donut RTP Stash Finder**, and **Relog Loader**. |
| **Finders** | Stash, Chunk, Spawner, SusChunk, SeedRay, Chunk Waypoints, Finder Overlay, Player Chunks, Light Source Finder, Prime Chunk, Activity, Growth, Heat Map Radar, Raid Planner, Base Webhook, Chunk Keeper, Base Log Browser, Tunnel Base Finder, Tunnel Base Water, Nether Tunnel Finder, and Structure Detector. |
| **Entity** | Entity Scanner, AntiTrap, Eye Finder, Item Frame ESP, Bone Dropper, and Spawner Protect. |
| **Fake** | FakePay, FakePayments, and FakeRoles. |
| **Render** | AutoRender, PaperRig, and Scoreboard Hider. |
| **Trading** | AH Flipper, AH Sniper, Shop Buyer, and AH Sell. |
| **Dogs Misc Tools** | Sprint, AntiAFK, FastPlace, FreeLook, AutoEat, AutoMine, SwingSpeed, CoordSnapper, FakePlayer, AutoLog, Flag Detector, Macro Protector, Spectator Detector, Panic Pay, Anti-Cheat Guesser, Fake Latency, Position Packet Filter, Coordinate Protector, Auto Store, Auto Smelt, AutoTool, TPASpammer, Tab Detector, Weather Notifier, Home Setter, Skin Changer, Chat Games, Region Map, Schematic Builder, Elytra Warner, Hole ESP, Hole Tunnel Stairs ESP, Amethyst ESP, Bedrock Hole ESP, Key Pearl, Auto Firework, Auto TPA, Quick Macro, and Name Protect. |

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
# Build this addon (run from this folder).
.\gradlew.bat build --no-daemon
```

The jar is produced at `build/libs/`.

> **AUTISM Client API:** the matching API jar is vendored in `libs/` and resolved via a
> `flatDir` repository, so the build is self-contained — it works on a fresh machine or CI
> runner with an empty `~/.m2` (no `publishToMavenLocal` needed). To upgrade the client,
> drop the new `autism-<version>.jar` into `libs/` and bump `autism` in
> `gradle/libs.versions.toml`. Currently pinned to AUTISM Client `5.0-26.2-dev`.

> **AUTISM Client version:** the build resolves the API with a Maven version range (`[3.4,)`),
> so it uses the newest client you have published to mavenLocal rather than requiring one exact
> version, and the built jar declares `autism: "*"` (loads on any client version). Note that a
> new major client release (e.g. 5.0) may change the API — if a build breaks after upgrading,
> the addon source may need to be updated for the new API. if so please dm eeee_37659 so i can fix it

## Credits

- **SeedCrackerX** by KaptainWutax and 19MisterX98 (MIT) — seed-cracking engine.


## License

MIT — see `LICENSE`.
