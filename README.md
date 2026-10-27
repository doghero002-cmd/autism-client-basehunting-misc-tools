<div align="center">

<img src="docs/readme-banner.svg" alt="Dogs BaseHunting / QQL Tools" width="900">

# Dogs BaseHunting / QQL Tools

**Base hunting, stash discovery, and DonutSMP utilities for AUTISM Client**

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-52a535?style=flat-square&logo=minecraft&logoColor=white)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Fabric-0.19.3%2B-dbd0b3?style=flat-square)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-25%2B-e76f51?style=flat-square&logo=openjdk&logoColor=white)](https://adoptium.net/)

</div>

A client-side addon for the AUTISM Client that bundles base-finding tools for DonutSMP, including
seed cracking, coordinate finding, RTP stash hunting, an auction-house flipper, and a broader set of
utility modules. It loads as its own jar alongside the AUTISM client.



Install **one** jar alongside its matching client:

| Jar | AUTISM Client | API |
| --- | --- | --- |
| [3.0.0](dist/autism-base-finding-tools-3.0.0.jar) | V4 / `5.1-26.2` | v4 |
| [3.0.0+client5.0](dist/autism-base-finding-tools-3.0.0+client5.0.jar) | `5.0-26.2` | v3 |


### Highlights

- **Base Scout:** opt-in, bounded walking exploration that coordinates existing finders, stops on safety/conflict conditions, and records local candidate evidence for manual review. It is not a survival guarantee or a server-verified base detector.
- **Finder improvements:** Classic and Dog+ Sus Chunk modes, optional mined-geode signals, Player Finder light-data/simulation modes, underground-light tracking, and revised chunk/stash candidate handling. These are scouting signals, not proof of a player base.
- **Chat Games:** more offline game formats, bundled trivia answers and word packs, a client-editable 489-entry curated default word/phrase list, preserved custom entries, and optional AI fallback. The much larger English fallback and live game registries remain automatic.
- **Chat Bot / AI:** V4 provider reuse and an optional authenticated local AI-control bridge. Conversational replies remain separate from owner-only movement/actions.
- **Automation and trading:** GoTo screen-pause and route-ownership fixes, safer Gather/flight/building coordination, tighter AH Sniper confirmation checks, and safer AH Sell / inventory handling.

The patch notes follow in the sections below; [Base Scout setup and limitations](docs/base-scout.md) has its own safety guide.

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
| **Finders** | Finders hub, Player Finder, Netherite Finder, Stash Finder, Chunk Finder, Spawner Finder, Sus Chunk Finder, Light Source Finder, Prime Chunk, Activity, Growth, Nether Tunnel Finder |
| **Finder Tools** | Individual Sign, Portal, Structure Detector, Base Webhook, Finder Overlay and Chunk Keeper controls |
| **Maps** | SeedRay, Seed Map, Heat Map Radar, Raid Planner, Base Log Browser, Breadcrumb and Region Map |
| **Navigation** | [Base Scout](docs/base-scout.md), GoTo, Tunnel Base Finder (CRAWL/STANDING/AMETHYST), Elytra Travel, AntiAFK and Sprint |
| **Inventory** | AutoEat, AutoTool, Inventory Cleaner, Auto Store, Auto Smelt, Chest Stealer and Auto Replenish |
| **Entity** | Entity Scanner, AntiTrap, Eye Finder, Item Frame ESP, Bone Dropper, Spawner Protect |
| **Render** | AutoRender, PaperRig, Scoreboard Hider, Storage Recorder |
| **Fake** | Fake Identity (fake /pay, fake incoming payments, fake rank — one module), Fake Player |
| **Trading** | AH Flipper (keyless/API learning, optional capped auto-buy), AH Sniper, Price Check, Shop Buyer, AH Sell |
| **Automation** | AutoMine, AutoFish, Schematic Builder, Gather and AI Remote Control (optional local bridge) |
| **Combat** | Mace PVP, Key Pearl, Auto Firework, Elytra Warner |
| **Safety** | AutoLog, Player Panic, Panic Pay, Flag Detector, Anti-Cheat Guesser, Macro Protector, Spectator Detector, Tab Detector, Session Guard, Coordinate Protector, Position Packet Filter, Fake Latency, Name Protect |
| **ESP** | Hole Tunnel Stairs ESP, Amethyst ESP, Bedrock Hole ESP |
| **Utility** | Parkour, FastPlace, SwingSpeed, CoordSnapper, Waypoints, Weather Notifier, Skin Changer, Translate, Balance Tags |
| **Server Tools** | Home Setter (SET + META), TPASpammer, Auto TPA, Chat Games, ChatBot, Quick Macro |

### Chat Games word and phrase defaults

Auto Chat Games starts with its built-in Minecraft/world vocabulary plus a visible **Default + extra words** list in the module's **Word games** group. That client setting contains the curated portal/structure phrases, bundled trivia answer words, public-plugin vocabulary and country names; add server-specific words or phrases directly there. Older saved lists are upgraded without losing custom entries. New additions apply immediately. The 370k-word English fallback and live Minecraft/world registry names stay automatic rather than becoming thousands of setting rows. The legacy `config/autism/chatgames-words.txt` remains additive.

### Chat Games answer key

Enable Auto Chat Games once to create `config/autism/chatgames-answers.txt` inside your Minecraft instance. Paste a question on one line and its answer on the next:

```text
Which mob explodes when it gets close to you?
creeper

What command sells all drops?
sellall
```

Blank lines and `#` comments are ignored. Optional `Question:`/`Answer:` or `Q:`/`A:` labels also work. Toggle the module off/on after edits to reload. Incomplete pairs, conflicting answers and oversized files produce a warning, not partial guesses. An empty text file removes its overrides.

The optional `config/autism/chatgames-answers.json` file still accepts question-to-answer pairs:

```json
{
   "Which mob explodes when it gets close to you?": "creeper",
   "What block do you use to build a nether portal?": "obsidian",
   "What command sells all drops?": "sellall",
   "Which flower do bees prefer on this server?": "Blue Orchid"
}
```

Replace server-specific examples with that server's actual answers. The Ready message shows how many custom answers loaded. Text entries override matching JSON entries; invalid files are warned about independently and are never overwritten. Use `{}` to clear the JSON key. Built-in and learned answers remain available.

Capitalization, punctuation, spacing and recognized game labels do not affect question matching. Put differently worded questions in separate entries. Custom answers override learned trivia and built-in trivia facts, but cannot override solved math or explicit retype/reverse instructions. Leading slashes in answers are removed before sending; `sellall` is answer text, not an executed command.

Keep this file separate from `chatgames-learned.json`, which the module updates from server reveals, and `chatgames-words.txt`, which supplies unscramble vocabulary. Limits: 2,000 question entries, 256 KB per file, 512 characters per question and 64 per answer; answers must be strings without line breaks/control characters.

LOCAL includes 316 unique bundled questions: [251 independently written variants](src/main/resources/chatgames/minecraft-answers.json) plus [66 public-plugin entries](src/main/resources/chatgames/public-plugin-answers.json), with one shared question deduplicated. They cover mobs, drops, portals, structures, crafting, enchanting, brewing, villagers, redstone, inventory sizes and historical updates. Custom keys take priority over learned answers, which take priority over bundled answers. Custom and learned data remain separate from the bundled files.

The imported entries come from [DragonHunt3r/ChatGames trivia](https://github.com/DragonHunt3r/ChatGames/blob/e5ee5fc21b15e8f9a674288a4bef29c28cb5481f/src/main/resources/trivia.yml), [RareHyperIonYT/ChatGames Minecraft examples](https://github.com/RareHyperIonYT/ChatGames/blob/ac8aa1e4fa01305e87c327a461cde9487bd505d5/common/src/main/resources/games/multiple-choice.yml), PlayerPicked/ChatGame mob riddles and The-Epic/SimpleChatGames trivia. The [source ledger](docs/chatgame-sources.md) records commits, selections, exclusions and the supplied catalogue's availability. [MIT notices](src/main/resources/chatgames/public-plugin-NOTICES.txt) are packaged in the jar. Obsolete counts and incorrect defaults were excluded. Historical version-family answers preserve the plugin's accepted spelling. Multiple-choice entries are adapted to named free-text answers, not letters. This does not expose GensHQ's private configuration.

Vocabulary uses live Minecraft item/block/entity/effect and world registry names, custom words, trivia answers and [70 selected public-plugin words](src/main/resources/chatgames/public-plugin-words.txt). A [large alphabetic English fallback](src/main/resources/chatgames/english-fallback-words.txt) adds 369,652 eligible source words before duplicates with primary vocabulary are removed. It is supplemented by [197 country-name targets](src/main/resources/chatgames/public-country-words.txt) from funnyboy-roks/ChatGames, including multi-word names, with the [Apache license](src/main/resources/chatgames/public-country-LICENSE.txt) packaged. Minecraft/plugin/custom names take priority over both fallback lists. The English list comes from [dwyl/english-words](https://github.com/dwyl/english-words), with [public-domain notices](src/main/resources/chatgames/english-fallback-NOTICES.txt) included. All lists load offline on enable; there are no per-round downloads. More words create more possible anagrams, so ambiguous alternatives stay unanswered until a validated reveal disambiguates them. Spacing-only aliases do not count as different candidates.

Detection also handles the inspected timed word headers, pipe-framed SOLVE IT/REVERSE IT/FILL IN/COPY IT/WHO AM I announcements, and SimpleChatGames' prize wrappers. A nonempty trigger keyword still restricts which announcements the module accepts; clear it to allow different plugin prefixes. Random-range guessing, crafting GUIs, FizzBuzz, letter-based multiple choice and multi-turn hangman are not implemented. See the source ledger for these limits.

LOCAL also recognizes some sentence paraphrases, including nearby exploding mobs, portal materials, dragon dimensions and elytra repair. It is not a trained language model. Exact server wording and expected answer spelling can differ; unknown sentences need a custom answer, a learned server reveal, or a configured AI endpoint. Server-specific commands are not guessed by the bundled bank.

Minecraft vocabulary collection also includes potions (including splash/lingering display names), villager professions, vanilla structure keys, armor trims, banner patterns, paintings, instruments and registered animal variants. World-dependent names refresh when the connection/world changes; vanilla structure names do not require the server to synchronize its structure registry. A real 26.2 registry test checks every key in the six built-in word registries and representative world categories. This covers registry-derived names, not every historical spelling, private server nickname or name outside the solver's supported character/length limits. Keep those in the custom word file.

### Chat Bot conversations and player GoTo

On V4, Chat Bot defaults to **V4_AI**. Both **AI** and **V4_AI** reuse the provider/model selected in V4's existing AI screen and its native text transport; configure authentication there, with no duplicate addon endpoint/model/key setup. OpenAI, DashScope, Moonshot, DeepSeek, Google Gemini and Anthropic requests use V4's own formats, streaming parser and authentication. The addon supplies bounded conversation messages and an empty tool schema, not game observations or private chat-box history. The optional bridge verifies the native V4 transport signature and reports unavailable rather than crashing when it is absent.

V4's **built-in assistant** is a local game-intent interpreter, not a credit-backed conversational model. It cannot answer arbitrary Q&A, and Chat Bot never runs player-supplied intents through it. Select a language-model provider in the existing V4 AI screen for conversations. Earlier references to bundled credits in these notes were an incorrect interpretation of this setting.

Older clients without V4's native transport default to **RULES**; their **AI** mode still accepts a separately configured OpenAI-compatible endpoint. **LOCAL_AI** accepts an installed loopback model and sends no API key. V4 mode follows V4's own authentication settings; both native and standalone conversational transports refuse redirects. No model is downloaded or trained automatically.

Address your player name or the configurable `gemini` trigger, for example `gemini what blocks suit a medieval castle?` followed by `gemini what about the roof?`. The trigger does not select the provider: V4_AI uses the provider actually selected in the V4 chat box. Each player gets separate context, with 12 remembered exchanges by default and a configurable maximum of 20. Replies are plain text, capped at 220 characters for Minecraft chat. Public/private replies, owners/trusted lists and existing rate limits still apply. Only UUID-associated player-chat packets are accepted; servers that replace player chat with unsigned system messages are not supported by this chatbot.

History exists only in memory and expires after five minutes of inactivity. Disabling the module, changing conversation settings, or changing connection/world clears it and cancels pending requests. Cloud mode sends addressed messages and that player's recent history to the configured provider. AI text cannot invoke movement, coordinates, payments or other actions; existing owner-only action rules remain separate.

`.goto <player>` makes a one-time trip to a nearby loaded player's current position. `.goto follow <player>` continuously follows that player; `.goto stop` cancels the trip. Chat Bot's opt-in `come` action uses the one-time trip, not continuous follow. Targets must be loaded nearby: these commands do not teleport or locate someone outside render distance. Movement remains subject to GoTo settings.

Use your configured client command prefix. With a `#` prefix these are `#goto <player>`, `#goto follow <player>` and `#goto stop`.

`#qql ai-test` checks V4's selected provider from inside the client and prints only a sanitized local result. An optional question can follow the command. It sends no server-chat message, advertises no game tools, and has a 30-second deadline. This uses the existing V4 AI-screen configuration, not another provider setup.

### Auction buying, maps and clearing records

AH Flipper currently excludes spawners, shulker boxes (including empty boxes), bundles, potions/tipped arrows, player heads, suspicious stew and fireworks: its price key does not yet represent their value-changing metadata or contents. They are excluded from GUI/API ingestion and cached-book ranking rather than valued as interchangeable items. Ordinary item/enchantment markets remain supported; non-vanilla namespaces stay distinct. These checks do not establish profitability, and live testing should use alerts/paper mode unless a spending budget is explicitly authorized.

- **AH Flipper:** alerts and paper trading are the default. Enable the module under **Trading**, then open `/ah <item>` or set **Re-scan watchlist**. A blank watchlist does not open auctions merely because the module is enabled. The panel's **Open /ah** button also works before any deals exist. For purchases, enable **Auto-buy flips**, set a positive **Buy budget**, and leave **AH Sniper** off so the flipper can use its purchase executor. Only fresh sales-backed deals qualify; ask-only estimates never buy. The default limit is **one attempt per enable**. Attempts reserve their full price even when unconfirmed, so failed verification cannot cause repeated spending. Re-enable the flipper to reset the limits. Auto-buy does not automatically sell the purchased item.
- **Flipper appears idle:** use `flip status` with your configured client prefix (e.g. `.flip status`) **without closing the auction page**. It reports the scan state, current listing count, sales history, filter-rejection reasons, API errors and whether buying is off. The empty panel shows the same diagnostics. A raw legacy-coloured seller line is normalized before listing identity is formed, so different sellers do not collapse into a single unknown-seller ask. While fewer than **Min sales** exist, enabled **Ask-only fallback** remains alert-only instead of disappearing after the first sale. It still requires at least six comparable other asks and your profit/ROI limits; a complete scan does not guarantee a profitable deal.
- **Keyless passes:** automatic paging retains the whole pass and page limit when the server opens a replacement menu. Page counters are excluded from the scan identity, while search/filter text and observed `/ah` queries stay separate. A missing page times out rather than waiting forever; capped/interrupted passes and passes first opened on page 2 or later cannot infer sales. Watchlist-owned menus close on completion so later idle scans can run; manually opened or replaced screens are not closed. Two complete passes of the same search, under 20 minutes apart, are needed to infer vanished listings. Disappearance is not proof of a sale.
- **AH Sniper:** set the target item and maximum full-stack price, then enable it. It checks the live listing again before clicking and handles the purchase confirmation. Unconfirmed purchases stop the sniper rather than retrying blindly.
- **Price Check:** auto-paging stays stopped after movement, the last page or the page-turn limit; close/reopen the auction screen or re-enable the module to start again. A server opening a new menu for an automatic page turn does not reset that limit. Disconnecting clears scan and idle-reopen state. Recording is passive by default; **TRACK_ITEM** may reopen `/ah` while idle when **Re-scan every** is nonzero.
- **Region Map:** the built-in image requires no file setup. Custom PNGs are optional; unreadable files fall back to the built-in map and warn once. Toggle the module off/on to retry a repaired file at the same path.
- **Shared heatmaps:** the optional URL must be HTTP(S). Responses are limited to 64 KiB and must contain nonnegative integer counts for cells 1–81, e.g. `{"cells":{"41":3},"cellBlocks":50000}`; flat cell/count objects also work. If supplied, `cellBlocks` must match the configured grid size. Invalid data leaves the last valid map intact. Changing/blanking the URL or changing the grid size cancels the old request and clears its global overlay, without uploading local findings. Clearing the tracker also discards pending confirmations.
- **Storage Recorder:** **Record → Clear key** is an optional keybind, unbound by default. It runs the same action as **Clear record**, only during gameplay with no screen open. Clearing affects the current server/dimension, not the module toggle. Already-cleared positions stay hidden until observed absent or the session ends; new positions continue recording.

### DonutSMP stash tools

- **Donut RTP Stash Finder** — Uses RTP plus directed digging around key areas to locate stash blocks and log the results to `bases.txt`.
- **Relog Loader** — Digs down, relogs, and re-pulls chunks so finder modules can read fresh terrain more reliably.

### Tunnel movement and suspicious chunks

- **Sus Chunk Finder:** **Classic** (default) and **Dog+** are independently adapted from the Anubis reference. Classic evaluates mature amethyst and bud supports; immature natural growth vetoes the surrounding patch. Dog+ uses cached block-light layers to find dark cave cells next to an open level-4 edge. Both retain equal heat peaks. Successful samples are not rescanned until reset; the cap is 128 chunks per tick with a soft time budget. Optional **Detect mined clusters** adds settled exposed-geode candidates and is off by default. These are scouting signals, not proof of bases or players.
- **Player Finder:** replaces Player Chunks under the same saved module ID, with **Light Data** and **Simulation Activity** modes. Light Data checks explicitly transmitted zero-filled skylight sections near cave air; empty masks and missing payloads do not count. Simulation Activity requires several distinct block/entity sources outside your server simulation distance plus one chunk, excluding visible players' nearby areas. Memory expires after 45 seconds by default. Enable Light Data before travelling into new chunks; it cannot reconstruct packets received while disabled. Both modes require a skylit dimension and suppress results while you are below Y0.
- **Migration:** removed Sus mode names reset to Classic; old **ANBS+ Scan** labels migrate specifically to Dog+. Old mixed-detector flags are not imported. Prime now detects underground light/redstone changes; Light Source Finder renders underground block-light cells; Chunk Finder uses the reference basic stone-stack detector. Seed, ore, stash and other unmatched specialists remain separate. See the [current validation and limitations](docs/BASE-DETECTION-VALIDATION.md).
- **Tunnel Base Finder:** recovery tracks horizontal movement within blocks instead of waiting for a block-coordinate change. Screens opened during mining pause movement. Base thresholds apply within a local 3x3-chunk area; **Require spawner** now requires an underground spawner in that same area. **Spawner critical** still alerts on any loaded spawner. Alert-only discoveries are deduplicated by location until the module is re-enabled.
- **Combined base alerts:** qualifying multi-finder candidates are checked before limiting announcements, so weak nearby signals cannot hide a stronger, slightly farther candidate. The per-location deduplication and ten-second alert cooldown remain; these are still candidate alerts, not proof of a base.
- **Settings:** Sus and Player Finder each expose one two-mode selector. Tunnel controls remain grouped into movement, supplies, safety, and base detection. Existing module IDs are preserved; obsolete Sus settings no longer affect detection.
- **Camera-follow steering:** the deadband extends beyond the 45-degree cardinal boundary. Small camera jitter near a boundary keeps the current tunnel heading; deliberate turns still change it immediately. The rule handles yaw wraparound at +/-180 degrees.
- **Gravel and lava recovery:** gravel uses one short backward retreat without flipping yaw, followed by stationary clearing. Unsafe retreats or a column that never clears stop the module. Lava releases movement before routing to a new lane. Recovery turns stop after 200 active rotation ticks if they cannot finish. These changes do not guarantee anticheat compatibility.
- **FreeLook:** while the official client's FreeLook is active, camera collision clipping and smart culling are bypassed. The fork also has a **Camera Through Walls** setting. This changes the camera, not player collision, and does not make blocks transparent.

### Shared automation behavior

- **AH Sniper:** Target settings support name include/exclude and minimum stack count; live listing and confirmation checks still apply.
- **AH Sell:** configure an Items list, or deliberately enable **Sell all hotbar items**. Unfiltered selling is off by default. Unconfirmed listings stop without retries.
- **Auto Eat/Tool/Replenish:** risky or rare foods are excluded by default, manual slot changes are respected, and replenishment uses an atomic inventory swap with depleted-stack tracking.
- **Auto Store:** acts only on the displayed container's menu, identifies player slots by inventory ownership rather than menu order, and always leaves armor/offhand slots alone. **Keep hotbar** also works with reordered slots. Deposits respect destination input rules and stack limits; an item that cannot fit does not block other matching items that can.
- **Panic Pay:** recipient and amount are validated; disconnect follows a matching payment acknowledgement. Unknown replies time out without retrying payment or automatically disconnecting.
- **Coord Snapper:** CENTER remains the default; COPY_OWN/COPY_TARGET copy locally to the clipboard. No coordinate webhook is added.

- **GoTo and ore mining:** opening a screen pauses built-in navigation before planning, following or inventory actions run. Mining is cancelled during menu/container waits, while the route remains available to resume. External Baritone behavior is controlled by that integration, not the built-in follower.
- **GoTo controls:** movement, safety, mining, supplies, display and performance have separate settings groups. Diagnostics are under **Show advanced** rather than occupying the normal travel panel. Setting IDs and saved values are unchanged.
- **Gather:** waits for unrelated mining, flights, following and exploration. With **Pause .goto to gather** on, it retains the original goal type, exact target, backend, breaking permission and completion callback. Disabling Gather restores its suspended trip when possible. An explicit mining stop or leaving the world discards that suspension.
- **Elytra Travel and Schematic Builder:** release their held inputs during ordinary menu pauses; stopping flight releases input immediately. Builder preview-only operation does not claim movement keys. Builder-managed restock screens still run their restock flow.
- **AntiAFK:** yields to the addon's active navigation, gathering, flight, tunnel and building tasks instead of adding idle strafe/spin inputs.
- **Macro Protector:** stops navigation, gathering and flight as well as the older automation modules. A gather shutdown cannot restart a suspended route after that safety stop.
- **Shared stuck detection:** tracks accumulated movement rather than requiring one tick to exceed the progress threshold. Normal slow walking no longer appears stationary to the diagnostic counter.

Automated movement can still trigger server anti-cheats. The regression tests cover scoring, floor geometry, local clustering, and progress counters; live hazard recovery, shop screens, and movement need in-game verification.

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

1. Download the matching jar from [Releases](../../releases); install **one**, not both:

   | Jar suffix | AUTISM Client | Addon API |
   | --- | --- | --- |
   | `<version>.jar` | V4 / `5.1-26.2` | v4 |
   | `<version>+client5.0.jar` | `5.0-26.2` | v3 |

2. Drop it in your `mods` folder alongside the AUTISM client and Baritone if you plan to use movement-based tools.
3. Launch the game and open the module menu to enable the features you want.

## AI remote control

Requires the verified AI-enabled V4 host; ordinary V4/5.0 hosts without that controller cannot use this module. The addon still loads without AI. Enable **AI Remote Control** in Automation. Host access checks, AI configuration and plan approval remain controlled by the client. The module starts disabled and disables on world leave.

From the workspace root:

```powershell
& '.\tools\client-ai.ps1' -Action Status
$task = & '.\tools\client-ai.ps1' -Action Submit -Prompt 'Summarize enabled finder modules without moving or changing settings.'
& '.\tools\client-ai.ps1' -Action Status
& '.\tools\client-ai.ps1' -Action Cancel -TaskId $task.taskId
```

Submission returns a task ID, not proof of completion. Check Status for rejection, progress, approval-waiting or completion; approve plans only in the client's AI panel. Enabling the module initializes the host normally if needed. The script reads the short-lived bearer token from the game directory's `run` folder without printing it. `/ai/status`, `/ai/task` and `/ai/cancel` require that token and reject browser-origin requests. Unsupported controller builds fail closed. Offline live QA verified genuine read-only delegation: the host answered with the actual dimension ID and the final API returned it in `result`. Safe rejection, completed-task cancellation and token cleanup also passed; existing plans/settings were restored unchanged. For simultaneous instances, use JVM option `-Dautism.seedcracker.bridge.port=25661` and script argument `-Port 25661`.

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

The build resolves the exact vendored client jar from `libs/` using `autism` in `gradle/libs.versions.toml`, not a Maven version range. Java 25 is required.

Build both variants for each release, with the same source revision:

1. API v4: set `autism = "5.1-26.2"` and `mod-version = "<version>"`; build against `libs/autism-5.1-26.2.jar`.
2. API v3: set `autism = "5.0-26.2-dev"` and `mod-version = "<version>+client5.0"`; build against `libs/autism-5.0-26.2-dev.jar`.
3. Restore the API v4 catalog values after building. Attach both jars to the GitHub Release and label their client versions.

On this Windows workspace:

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\ms-25.0.4"
.\gradlew.bat build --offline
```

The runtime dependency is `autism: "*"`; the addon's API handshake still checks compatibility. Use the matching release variant rather than relying on cross-version compatibility.

## Credits

- **SeedCrackerX** by KaptainWutax and 19MisterX98 (MIT) — seed-cracking engine.

## License

[The Unlicense](LICENSE)
