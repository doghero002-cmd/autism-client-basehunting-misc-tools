# AUTISM SeedCracker Addon

A standalone AUTISM Client addon that ports the
[SeedCrackerX](https://github.com/19MisterX98/seedcrackerX) seed-cracking engine into the
client. It finds the seed of multiplayer or singleplayer worlds from structures, biomes and
decorators — with a toggleable module and an in-game HUD readout.

Built as a normal AUTISM addon (its own jar), **not** part of the client itself and not a
Meteor addon.

## Features

- **SeedCracker module** — toggle the cracker on/off from the AUTISM module menu.
  Enabling/disabling flips SeedCrackerX's `active` flag; an optional "Reset data on disable"
  setting clears collected data when toggled off.
- **Seed HUD element** — shows live cracking progress (bits of structure data gathered out of
  the 32 needed, plus remaining candidate seeds) and turns green with the world seed once
  cracked. Movable/toggleable in the client's HUD editor.
- **Full SeedCrackerX engine** — the entire `/seedcracker` command tree and cloth-config GUI
  work unchanged (`/seedcracker gui` for settings).
- **Self-contained** — the seedfinding, latticg and cloth-config libraries are bundled
  jar-in-jar, so no extra mods are required beyond AUTISM Client + Fabric.

## Requirements

- Minecraft `26.2`
- Java 25+
- [Fabric Loader](https://fabricmc.net/) `0.19.3+` and Fabric API
- AUTISM Client (any version — the addon declares `autism: "*"` and relies on the client's
  `apiVersion()` handshake for real compatibility)

## Install

1. Drop `autism-seedcracker-<version>.jar` into your `mods` folder alongside the AUTISM client.
2. Launch the game.
3. Enable the **SeedCracker** module from the AUTISM menu, then run `/seedcracker gui` to
   configure which structures/decorators to use.

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
| `com/autism/seedcracker/SeedcrackerAddon` | AUTISM `autism` entrypoint — registers the module + HUD |
| `com/autism/seedcracker/SeedcrackerInit` | Fabric `client` entrypoint — boots the SeedCrackerX engine |
| `com/autism/seedcracker/modules/SeedcrackerModule` | Toggleable module |
| `com/autism/seedcracker/hud/SeedHud` | Seed / progress HUD element |
| `kaptainwutax/seedcrackerX/...` | SeedCrackerX engine (commands, cracker, finders, GUI) |

## Credits

- **SeedCrackerX** by KaptainWutax and 19MisterX98 (MIT License) — the cracking engine.
- AUTISM addon port keeps the original engine under its own package so it stays close to
  upstream.

## License

MIT — matching SeedCrackerX. See `LICENSE`.
