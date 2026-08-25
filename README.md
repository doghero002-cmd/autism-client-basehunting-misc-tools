# Seed Based Tools

A standalone [AUTISM Client](https://github.com/) addon bundling two seed-based tools into one
package: a world **seed cracker** and a **bedrock coordinate finder**. Built as a normal AUTISM
addon (its own jar) — not part of the client itself and not a Meteor addon.

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

- **SeedCrackerX** by KaptainWutax and 19MisterX98 (MIT License) — the seed-cracking engine.
- Bedrock pattern matching adapted from BedrockPatternFinder (v26.11).
- The original engines are kept under their own packages so they stay close to upstream.

## License

MIT — matching SeedCrackerX. See `LICENSE`.
