# Seed Based Tools

A standalone [AUTISM Client](https://github.com/) addon bundling seed-based and coordinate tools
into one package: a world **seed cracker**, a **bedrock coordinate finder**, and **DonutSMP
stash-hunting automation**. Built as a normal AUTISM addon (its own jar) — not part of the
client itself and not a Meteor addon.

> **⚠ Anti-cheat warning:** the Donut RTP Stash Finder and Relog Loader modules use automated
> RTP / Baritone / elytra movement that server anti-cheats may flag. Use them at your own risk.
> While either is active, a flashing on-screen warning banner stays up so you don't forget.

## Tools

### 1. SeedCracker
Cracks the world seed from structures, biomes and decorators using the
[SeedCrackerX](https://github.com/19MisterX98/seedcrackerX) engine.

- **SeedCracker module** — toggle the cracker on/off from the module menu. Enabling/disabling
  flips SeedCrackerX's `active` flag; an optional "Reset data on disable" setting clears
  collected data when toggled off.
- **Fully configurable in the client GUI** — every SeedCrackerX option is an AUTISM module
  setting, so there's no command needed:
  - **Structures** — Buried treasure, Desert temple, End city, Jungle temple, Monument,
    Swamp hut, Shipwreck, Pillager outpost, Igloo, Trial chambers
  - **Decorators** — End pillars, End gateway, Dungeon, Emerald ore, Desert well, Warped fungus,
    Biome data
  - **Cracking** — Debug logging, Anti-xray bypass
  - **Rendering** — Render mode (OFF / ON / XRAY)
  - **Database** — Submit to database, Anonymous submits
- **Seed + progress HUD element** — shows live cracking progress (bits of structure data
  gathered out of the 32 needed, plus remaining candidate seeds) and turns green with the world
  seed once cracked. Movable/toggleable in the HUD editor.
- The full `/seedcracker` command tree and cloth-config GUI still work too (`/seedcracker gui`).

### 2. Bedrock Finder
Locates your world coordinates from a bedrock-floor pattern you draw. The game derives bedrock
at Y=-60 from the world seed mixed with each block's X/Y/Z (bedrock when the draw falls under
the 20% depth threshold), so a marked pattern only fits specific coordinates — finding it pins
your location.

- **Pattern grid GUI** — a 16×16 grid. Left-click paints **bedrock** (green), right-click paints
  **not-bedrock** (red), click again to clear; drag to paint. Includes seed + radius fields,
  Use Cracked Seed / Clear / Search / Cancel buttons, and a live progress bar. Matches are
  reported in chat.
- **Opens from the client menu** — module settings → **Open Grid GUI**, no command needed.
  Also available as `.bfinder` (aliases `.bedrockfinder`, `.bf`), which follows the client's
  configured command prefix automatically.
- **Seed auto-fill** — pulls the cracked seed from the SeedCracker tool when the GUI opens
  (or enter it manually). Searches all 4 rotations multi-threaded around your position.

### 3. Donut RTP Stash Finder
Automates stash hunting on DonutSMP. RTPs around the map and, when you land within a
configurable distance of 0,0 (default 50k), digs down with Baritone and searches for stash
blocks for a while.

- **RTP loop** — configurable command (default `/rtp`) and cooldown.
- **Region rotation** — each RTP picks a random DonutSMP region (`west`, `eu central`,
  `eu west`, `asia`, `oceania`) and never repeats the one just used, so TP spots don't cluster.
  `/rtp east` is available as an opt-in toggle (warning: often full and can break RTPing).
- **Stuck-RTP recovery** — if your coords haven't changed within a configurable timeout
  (default 10s, up to 60s) after an RTP, it relogs and tries a different region automatically.
- **Distance threshold** — base search only activates when closer than this to 0,0
  (default 50000, configurable).
- **Baritone base search** — digs down to a configurable Y then mines toward the target blocks
  (chests, hoppers, shulker boxes, ... — configurable list) for a configurable duration
  (default 15 min). Requires Baritone (baritone-meteor / upstream); detection still works
  without it.
- **Save & RTP mode** — logs any detected base's coords + dimension + timestamp to `bases.txt`
  and RTPs away instead of searching. Optional auto-disable after a find.

### 4. Relog Loader
One-shot chunk-loading module (the "relog loading" trick). Digs down to a configurable Y,
disconnects and rejoins so the server resends chunks, then flies out with the **elytra**
(Baritone's ElytraProcess) so ESP can read the loaded bedrock/deepslate region — including
signatures normal chunk loading hides.

- **Elytra flight** — uses Baritone's auto-elytra process (falls back to Baritone walking if
  no elytra/native lib). Configurable fly radius and duration. Requires an elytra or riptide
  trident (setting-gated).
- **Logs ESP detections** — while flying it scans the freshly-loaded chunks for base blocks
  (configurable list/radius) and appends new finds to `bases.txt`.
- Separate from the stash finder so you can run a load cycle on demand.

Both DonutSMP modules write to `bases.txt` in the AUTISM config folder.

## Requirements

- Minecraft `26.2`
- Java 25+
- [Fabric Loader](https://fabricmc.net/) `0.19.3+` and Fabric API
- AUTISM Client (any version — the addon declares `autism: "*"` and relies on the client's
  `apiVersion()` handshake for real compatibility)

The seedfinding, latticg and cloth-config libraries are bundled jar-in-jar, so no extra mods
are required beyond AUTISM Client + Fabric.

## Install

1. Download the jar from [Releases](../../releases) and drop it into your `mods` folder
   alongside the AUTISM client.
2. Launch the game.
3. Enable the **SeedCracker** module to start cracking, and open the **Bedrock Finder** module's
   settings → Open Grid GUI to locate coordinates.

## Build

The addon compiles against the AUTISM Client API from your local Maven repo.

```powershell
# 1. Publish the AUTISM Client API locally (run from the AUTISM Client project folder).
.\gradlew.bat publishToMavenLocal --no-daemon

# 2. Build this addon (run from this folder).
.\gradlew.bat build --no-daemon
```

The jar is produced at `build/libs/autism-seedcracker-<version>.jar`.

> If you build against a released client rather than a dev build, update the `autism` version
> in `gradle/libs.versions.toml` to match the artifact in your local Maven repo.

## Project layout

| Path | Purpose |
| --- | --- |
| `com/autism/seedcracker/SeedcrackerAddon` | AUTISM `autism` entrypoint — registers modules, command, HUD |
| `com/autism/seedcracker/SeedcrackerInit` | Fabric `client` entrypoint — boots the SeedCrackerX engine |
| `com/autism/seedcracker/modules/SeedcrackerModule` | SeedCracker module + GUI settings |
| `com/autism/seedcracker/modules/BedrockFinderModule` | Bedrock Finder module (opens grid GUI) |
| `com/autism/seedcracker/bedrock/...` | Bedrock matching engine, grid GUI, seed provider |
| `com/autism/seedcracker/commands/BedrockFinderCommand` | `.bfinder` command |
| `com/autism/seedcracker/hud/SeedHud` | Seed / progress HUD element |
| `kaptainwutax/seedcrackerX/...` | SeedCrackerX engine (commands, cracker, finders, GUI) |

## Credits

- **SeedCrackerX** — the seed-cracking engine.
- The original engines are kept under their own packages so they stay close to upstream.

## License

MIT — matching SeedCrackerX. See `LICENSE`.
