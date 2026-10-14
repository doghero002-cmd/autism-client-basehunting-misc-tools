# Anubis finder adaptation and sniper comparison

## 2026-10-12 implementation update

This update supersedes the older retention decisions below for Prime, Light, Chunk Scanner and Sus. Current artifacts, tests and runtime receipts are in [the validation checkpoint](BASE-DETECTION-VALIDATION.md). Addon 334 tests and fork 1,293 tests pass with no failures/errors/skips. Offline runtime QA corrected controller initialization, enum identity and answer reporting; it verified genuine end-to-end read-only delegation and preserved the existing approval-waiting session. All 60 original AI-store files were restored with unchanged hashes. A before/after live CPU profile removed the light-map-copy hotspot.

| Evidence | Finding | Production path and regression check |
| --- | --- | --- |
| Reference Prime packet baselines/light differences/redstone changes and source explanations | The old stranded-fluid module was not the matching finder | `PrimeChunkFinderModule` now owns `PrimePacketTracker`; both `ClientLevelMixin` hooks observe server block changes. `PrimeLightTest` checks nibble/source filters. Runtime packet/mixin behavior is not yet live-verified. |
| `LightEspModule.buildBoxes` and `util/LightSnapshot.collect` | Reference detection uses block light stronger than skylight below Y0, not lamp counts | `UndergroundLight` and existing `LightSourceFinderModule`; tests cover denoising, interior suppression and the 12,000-cell cap. Immutable snapshots alone enter its cancellable worker. |
| `ChunkScannerModule.scan/isArea/keep/hottestPerPatch` plus verified block mappings | The inventory's Stash mapping was incorrect; the basic counterpart is Chunk Finder | `StoneStackScanner` replaces the old broad `ChunkFinderModule` worker. Fixtures cover caps/materials, unknown neighbors, equal peaks and prefix/direct heat equivalence. Seed-dependent Advanced Scanner is not ported. |
| `GeodeGlowScan` and Sus mined-geode collector/cluster/settle/source-light paths | Cached light and optional mined-geode coverage were missing | `GeodeGlowSnapshot`, `MinedGeodeTracker` and `SusChunkFinderModule`; coordinate, threshold, cluster and source-peak fixtures pass. Optional mined mode is off by default. |
| Reference hidden-debris parser and current 26.2 section buffers | Full consumption and malformed-input rejection were missing from the existing port | `HiddenDebrisScannerTest` exercises real hidden/used/trailing/truncated/invalid palettes. The 26.2 fluid-count header remains. |

Module IDs remain stable. Old `ANBS+ Scan` labels migrate only for Sus's owned enum through `SusModeSettingMixin`. Successful Sus chunks are cached rather than rescanned by later packets; the earlier packet-rescan statement below is historical. The speed ceiling is 128 with soft deadlines, not a guaranteed scan count or measured FPS improvement. Fixed-Y chunk markers default to 16 and their beams stop at that plane.

Unmatched Stash/Spawner/Portal/Sign/Structure/Growth/Activity/Tunnel specialists remain available. Reference Player Detection is the visible-player safety workflow, not the hidden-player counterpart. Existing Player Finder adaptations remain. Reference advanced seed/probe engines and all visual styles are not represented as exact ports.

The addon additionally has a disabled-by-default local AI control bridge. Controller descriptors, phase fields and SHA256 are verified from bytecode; runtime phase identity is resolved independently of `Enum.name()`. Opt-in activation uses the normal host initializer. An original approval-waiting session was preserved; two read-only questions then completed in an isolated idle session with unchanged settings. The final artifact returned `ANSWERED` and `result="minecraft:overworld"`, matching actual game state. Cancellation and token cleanup were verified, all 60 original store files restored, and the other game left running. Host authorization and plan approval are never bypassed, and model credentials are not exposed. See README for the local caller.

Licensing boundary: reference metadata declares GPL-3.0-only; the project LICENSE is Unlicense. Independently authored behavior adaptations and private local builds are not a clearance for public redistribution. No project commit, tag or public release was made.

The Sus detector now offers Classic and ANBS+ Scan instead of twelve overlapping modes. Player Finder replaces the visible-player cache with Light Data and Simulation Activity. Both replacements preserve their module IDs and are present in the addon and native fork. The reference was inspected statically, never executed. These are independently authored adaptations, not exact copies of Anubis or guarantees of player detection. Live behaviour still needs verification after restarting with the new jars.

## Scope and sample

[Scope and authorization](../../notes/anubis-0.9.9.1/scope.md). Report flavor: ordinary reverse engineering, not malware or vulnerability analysis.

| Property | Value |
| --- | --- |
| Reference | Anubis Beta 0.9.9.1.jar |
| Mod/version | anubisplus / 0.9.9.1+mc1.21.11 |
| Size | 21,206,373 bytes |
| SHA256 | 20A03C38A39D013BF5F6836818C0B665478EB3381181FD372CD661C438FD752A |
| License | GPL-3.0-only; no reference source copied into shipped modules |
| Entry points | net.anubis.bootstrap.AnubisNativeBootstrap; net.anubis.client.AnubisClientMod |
| Dependencies observed | Fabric client environment and Minecraft 1.21.11 intermediary classes |
| Inspection tools | JDK 25 javap, Vineflower 1.12.0, Yarn 1.21.11+build.4 metadata |
| Implementation target | Minecraft 26.2 official mappings, AUTISM API v4 with existing v3 compatibility |

## Evidence and findings

All jar-derived evidence uses the sample hash above. Decompiler output is a reading aid; the Classic growth and sniper timeout branches were also checked with javap. Raw extracted classes and decompiled reference code stay under the ignored notes/anubis-0.9.9.1/build directory, outside shipped sources.

| Evidence | Source reference | Reproduction |
| --- | --- | --- |
| E1 | fabric.mod.json and selected class inventory | Open jar as ZIP and inspect metadata; Get-FileHash verifies the sample |
| E2 | SusChunkFinderModule: modes, scanClassic, updateChunk, computeVisible | javap -c -p net.anubis.client.module.impl.render.SusChunkFinderModule; selected Vineflower output |
| E3 | GeodeGlowScan: scan, edge, lightAt | javap -c -p net.anubis.client.util.GeodeGlowScan; selected Vineflower output |
| E4 | LightDataClassifier, ChunkLightSnapshot, DarkSectionCheck | javap -c -p the classes under net.anubis.client.util.playerbypass |
| E5 | SimulationSignals and PlayerBypass2Module | javap -c -p net.anubis.client.util.playerbypass2.SimulationSignals; selected Vineflower output |
| E6 | SnipeTarget.matches and SniperBot purchase/timeout/accounting branches | javap -c -p the classes under net.anubis.client.module.impl.donutmacros |
| E7 | Local AHSniperModule.verifyPurchase, scanListingSlots and API generation guards | Local source inspection; hash n/a because this is a changing worktree |
| E8 | ApiCompatTest and native SettingRoundTripTest finder regressions | Execute the real settings decoders: all twelve removed Sus modes fall back to Classic; new choices deserialize and native stored values round-trip |
| E9 | Built output jars and Minecraft Camera.class | ZIP entry assertions verified both finder/policy class sets and official camera mixin registration; javap verified getMaxZoom(float):float and extractRenderState(CameraRenderState,float):void |
| E10 | Production SusChunkFinderModule and PlayerChunksModule diffs, registrations and compiled Sus scan dispatcher | git diff --numstat reports 1,585 removed legacy Sus lines and 94 removed player-cache lines; javap of the current jar shows chunk/light packet handling and calls to both classic and glow |
| E11 | Reference hasBlocksInRange and computeVisible; local terrainInBand and geodePeakEligible | Reference uses Y-16 through Y30 and threshold 1 for empty terrain; focused tests verify this and suppression by a stronger neighbour even when that neighbour is below its own threshold |

| Finding | Severity | Evidence | Confidence | Location and status |
| --- | --- | --- | --- | --- |
| F1: Classic distinguishes immature natural growth from detached buds | n/a_re | E2 | High | SusChunkFinderModule.classic; adapted with an additional unknown-support veto |
| F2: ANBS+ checks a dark cell beside an open level-4 block-light edge | n/a_re | E3 | High | SusChunkFinderModule.glow and SusScore.glowEdge; adapted, skylight excluded |
| F3: explicit zero-filled skylight payload differs from an empty mask | n/a_re | E4 | High | PlayerFinderSignals and PlayerChunksModule; adapted with a solid-surroundings filter |
| F4: activity beyond one's simulation bubble is a signal, not direct player visibility | n/a_re | E5 | High | PlayerChunksModule; simplified adaptation with settling, teleport quiet time, visible-player exclusions and expiry |
| F5: a closed auction screen can satisfy Anubis's timeout success branch | n/a_re | E6 | High | SniperBot; rejected as purchase proof |
| F6: our sniper verifies post-confirmation inventory growth and limits confirmed buys | n/a_re | E7 | High | AHSniperModule; retained without weakening purchase safeguards |
| F7: removed modes migrate through the actual settings API, and replacement classes are shipped | n/a_re | E8/E9 | High | Addon and fork settings tests and output jars; verified, not inferred solely from source |
| F8: the existing production finders are replaced, not supplemented only with tests | n/a_re | E10 | High | Existing registered IDs route to the new two-mode implementations in both codebases; chunk packets rescan both Sus modes and light updates rescan ANBS+ |
| F9: both Sus modes now use the reference terrain-dependent visibility rule | n/a_re | E11 | High | Production terrain-band scan and common peak policy implemented and regression-tested in both clients |

## Production replacement evidence

| Replacement | Production implementation | Registration |
| --- | --- | --- |
| Sus: Classic / ANBS+ Scan | [Addon mode selector](../src/main/java/com/autism/seedcracker/modules/SusChunkFinderModule.java#L34), [shared dispatcher](../src/main/java/com/autism/seedcracker/modules/SusChunkFinderModule.java#L133), [Classic scan](../src/main/java/com/autism/seedcracker/modules/SusChunkFinderModule.java#L198), [ANBS+ glow scan](../src/main/java/com/autism/seedcracker/modules/SusChunkFinderModule.java#L227) | [Existing addon Finders registration](../src/main/java/com/autism/seedcracker/SeedcrackerAddon.java#L140) |
| Fork Sus: both modes | [Native production replacement](../../swccs-client/src/main/java/com/swccs/seedcracker/modules/SusChunkFinderModule.java#L34) | [Existing native registration](../../swccs-client/src/main/java/com/swccs/seedcracker/SeedcrackerAddon.java#L144) |
| Player: Light Data / Simulation Activity | [Addon replacement](../src/main/java/com/autism/seedcracker/modules/PlayerChunksModule.java#L31), [packet dispatch](../src/main/java/com/autism/seedcracker/modules/PlayerChunksModule.java#L83) | [Existing player module ID, now in Finders](../src/main/java/com/autism/seedcracker/SeedcrackerAddon.java#L144) |
| Fork Player: both modes | [Native replacement](../../swccs-client/src/main/java/com/swccs/seedcracker/modules/PlayerChunksModule.java#L31) | [Existing native registration](../../swccs-client/src/main/java/com/swccs/seedcracker/SeedcrackerAddon.java#L148) |

The old BETA/DOGS/WATER/XENON/TYPES/chunk-age/tunnel/activity/farm dispatch paths are no longer in SusChunkFinderModule. The visible-player visit-count cache is no longer the Player Finder implementation. Both production replacements use the original module IDs; no parallel legacy detector is registered under those IDs.

## Finder differences and limits

| Area | Current adaptation | Reference features not reproduced |
| --- | --- | --- |
| Sus modes | Classic / ANBS+ Scan, heat spreading, immature-growth veto, Y-16 through Y30 terrain-dependent thresholds, eligible-neighbour peaks | DonutFolia palette shortcut and optional mined-cluster shell analysis; equal peaks deliberately use a deterministic tie break to reduce clutter |
| Sus refresh | Bounded chunk/light packet queue processed on the client tick, plus periodic loaded-chunk rescans; old contributions rebuilt from current samples | Reference post-application event timing and exact queue priorities; the periodic sweep covers delayed lighting application |
| Performance | Chunk count cap, soft 3ms scan deadline checked between chunks, glow count cap | A single chunk may exceed the deadline; no live frame-time benchmark yet |
| Light mode | Explicit full zero payload below Y62; partial updates; nearby-air check; bounded queue; expiry | Persistent protected-chunk history and reference suspect weighting/zone rendering |
| Simulation mode | Distinct changed-block coordinates or moving entity IDs, extra border chunk, settle period, visible-player exclusions | Detailed entity tracking-radius calibration, source weights, decay, lightning/spawn/remove/equipment pins and frozen-neighbour analysis |
| World lifecycle | Clears on mode changes, world changes, leave and disable; loaded/range pruning | Cross-world history intentionally not preserved |

Enable Light Data before entering fresh chunks: it cannot reconstruct earlier packet payloads. Simulation Activity can begin settling already loaded chunks when activity is first observed. Both player modes suppress results below Y0 and in dimensions without skylight. Vanilla dark caves, unusual light serialization, server plugins and remote ticking can still produce misleading signals. No module proves that a player or base is present.

## AH sniper comparison

| Behaviour | Anubis | Current addon/fork decision |
| --- | --- | --- |
| Target filtering | Name variants, shard distinction, minimum/exact enchantments, expiry requirements | Richer than our general item selector; not ported in this change |
| Price | Rounded unit price from total/count | Keep total-stack price limit; a low unit price does not authorize a larger stack bill |
| Listing identity | Item/name/price/count/lore fingerprint | Keep live listing recheck and exact flipper listing matching |
| Confirmation | Chat timestamps plus dialog/lime-button logic; closed-screen timeout fallback | Keep confirmation validation and post-confirmation delivery check; timeout stops rather than buying again |
| Spending | Successful accounting adds Hit.price, which is selected as unit price | Keep total-price reporting and confirmed-purchase cap |
| Retry suppression | Failed listing 60s, successful listing 15s | Existing conservative timeout/cancellation behaviour retained |

Anubis has useful target-selection features, but its purchase success test is not a reason to replace the current sniper wholesale. No live purchase was attempted during this work.

## Related repairs

MineTask rejects missing clients/empty work and resets after failed starts. Gravel recovery backsteps once without a 180-degree yaw flip, then clears the tracked column or stops. Retreats require loaded safe space and a safe floor. Lava stops movement before routing; recovery turns are bounded to 200 active rotation ticks. The fork now performs recovery turns even in camera-follow mode. FreeLook bypasses camera clipping and smart culling only while enabled, without changing player collision or block opacity.

## Path and timeline

- P1, callflow, E2/E3 -> F1/F2: loaded chunk -> selected Classic/ANBS+ scan -> sample -> neighbourhood heat/veto -> eligible peak -> shared chunk renderer.
- P2, callflow, E4/E5 -> F3/F4: bounded packet queue/entity sample -> world/range/settle checks -> expiring signal state -> Player Finder renderer.
- P3, callflow, E6/E7 -> F5/F6: listing -> live recheck -> confirmation -> delivery growth -> confirmed count; timeout -> stop.
- Static inspection: reference metadata and selected finder/sniper/player helper classes inspected; reference mod not run.
- Implementation: two-mode replacements added and mirrored with case-sensitive namespace conversion; recovery and FreeLook patches compiled.
- Verification: both full builds passed offline after the production visibility correction: addon 272 tests; fork 1,240 tests, zero failures or errors. Initial and repeated block-state packets are rejected by a focused regression; terrain-dependent thresholds and peak ordering have dedicated checks.
- Runtime check: localhost bridge responded; active survival session left untouched. New jars are not loaded into that running JVM, so this is not live verification of the changes.

## Reproduce the checks

Run from the workspace root:

```powershell
Get-FileHash "$env:USERPROFILE\Downloads\Anubis Beta 0.9.9.1.jar" -Algorithm SHA256
& "$env:USERPROFILE\.jdks\ms-25.0.4\bin\javap.exe" -c -p -classpath "$env:USERPROFILE\Downloads\Anubis Beta 0.9.9.1.jar" net.anubis.client.module.impl.render.SusChunkFinderModule
& '.\tools\gradle-quiet.ps1' -Project '.\autism-seedcracker' -Tasks @('build','--offline')
& '.\tools\gradle-quiet.ps1' -Project '.\swccs-client' -Tasks @('build','--offline')
& '.\tools\gradle-quiet.ps1' -Project '.\autism-seedcracker' -Tasks @('test','--tests','com.autism.seedcracker.compat.ApiCompatTest','--offline')
& '.\tools\gradle-quiet.ps1' -Project '.\swccs-client' -Tasks @('test','--tests','swccsclient.api.module.SettingRoundTripTest','--offline')
```

Verified addon artifact SHA256 after the production visibility correction: E92F409C346BE726F0E125883BB6BB725457C2F0E6C72A527E9DE157EAFE134B. Both full builds and complete suites passed with this production change. This fingerprint and the packaging checks establish which artifact was inspected; they do not establish that the running game has loaded it.

The focused regressions cover growth vetoes, glow edge rejection, explicit-dark versus empty/missing light payloads, partial update history, simulation border exclusion, signal expiry, single gravel retreat, recovery timeout/reset and MineTask cancellation. They do not establish in-game false-positive rates, full Anubis parity, successful camera mixin application at runtime, or anticheat compatibility.

## Catalogue-wide module comparison

The expanded request was compared against all 130 top-level reference module classes. The [complete source-derived inventory](../../notes/anubis-0.9.9.1/module-overlap.json) includes every module, its reference ID, editable addon/fork counterparts, native-builtin matches and explicit workflow aliases. It identifies 63 direct or related matches and 67 modules with no matched editable counterpart under this conservative mapping. A related-workflow match is not a claim that two modules implement the same algorithm.

This pass is a catalogue-wide feature/compatibility comparison with targeted logic changes. It is not a line-by-line audit of every native combat/render engine, and no unsupported 1.21.11 engine code was substituted for the current 26.2 implementation merely because its module name matched.

### Per-module decisions

Each of the 63 matched inventory entries has a decision below. **Logic** means a deciding implementation was compared and the adopted policy compiled/tested; **Prior** refers to the preceding finder/camera work; **Feature** means the reference's actual constructor/setting contract and local ownership/API boundary were compared, not that its complete algorithm was audited. Retaining a Feature entry is a decision not to ship an unverified engine change, not a claim that its current implementation is optimal.

| Reference entry | Local counterpart | Decision and concrete reason | Evidence scope |
| --- | --- | --- | --- |
| `client/HudModule` | Native hud | Retain host HUD; reference module-list/coordinate/status provider uses its own HUD service. Avoid a duplicate addon HUD implementation. | Feature |
| `client/PerformanceModule` | AutoRender | Retain current render controls; reference background-work/image-memory tuning is not equivalent to toggling our render policy. No unmeasured global cache change adopted. | Feature |
| `client/RadarModule` | HeatMapRadar | Reject replacement: reference is a nearby-player radar, ours is base heat telemetry. Keep distinct data sources. | Feature |
| `client/RegionMapModule` | RegionMap | Retain RTP logging, dimension conversion, image and heatmap controls; reference offers region-grid display settings, not a replacement for those records. | Feature |
| `combat/AimAssistModule` | Native aim-assist | Retain quantized mouse-count smoothing and existing scaffold/totem/rotation arbitration; the reference also uses mouse counts, so no conflicting second rotation path added. | Logic comparison, retained |
| `combat/AnchorAuraModule` | Native AnchorAura | Retain current anchor place/charge/detonate engine; reference relies on its combat lock/rotation services. No isolated verified improvement selected. | Feature |
| `combat/AutoTotemModule` | Native AutoTotem | Retain current inventory transaction engine; reference empty-offhand/double-hand behavior does not justify replacing our swap ownership and GUI integration. | Feature |
| `combat/CrystalAuraModule` | Native CrystalAura | Retain damage/placement engine; reference crystal pipeline is coupled to its own combat services and not drop-in for this host. | Feature |
| `combat/KeyThrowModule` | KeyPearl | Adopt delayed hand-item revalidation and owned-slot return. Keep pearl-only workflow rather than adding unsolicited wind-charge throws. | Logic + InventoryPolicyTest |
| `combat/KillAuraModule` | Native KillAura | Retain native target/attack/rotation pipeline; do not copy the reference's silent-attack service into the addon. | Feature |
| `combat/MaceModule` | MacePvp | Retain already-present enchant preference, smash readiness, cooldown and return controls. Do not replace the separate wind-charge/elytra workflow with reference auto-swap alone. | Settings/implementation comparison, retained |
| `donutmacros/AhSellModule` | AhSell | Adopt explicit targets, held-item/price rechecks, sale-only preview/confirmation and no-retry verification. Retain fixed-price workflow; no automatic undercut/relist loop added. | Logic + AhGuiTest/AuctionUiTest |
| `donutmacros/AhSniperModule` | AHSniper | Adopt precise target filters, strict preview checks, stale-job cancellation and safer fork delivery/cap behavior. Reject closed-screen-as-success and unit-price spending assumptions. | Logic + AhGuiTest/AuctionUiTest |
| `donutmacros/AutoTpaHereModule` | AutoTPA | Retain configured-target teleport macro. Reject bulk online-player discovery and automatic fighting when someone arrives; those are additional behaviors, not safer defaults. | Feature/workflow comparison |
| `donutmacros/PanicPayModule` | PanicPay | Adopt validated arguments and session-bound acknowledgement before disconnect. Reject automatic inventory/armor liquidation and blind payment retries. | Logic + PaymentPolicyTest |
| `misc/AmethystBypassModule` | AmethystEsp | Retain raw-palette amethyst reveal path already available; no second detector or alternate renderer added. | Prior implementation/feature comparison |
| `misc/AntiTrapModule` | AntiTrap | Adopt self-vehicle exclusion and safer reach/LOS/action gates. Reject local entity discard as proof that the server trap was removed. | Logic + verified vanilla APIs |
| `misc/AntiVanishModule` | Native AntiVanish | Retain native player/tab tracking; no reference-only vanished-player heuristic presented as certain player presence. | Feature |
| `misc/AutoClickerModule` | Native auto-clicker | Retain existing input simulation; do not create a competing random-CPS sender tied to reference input hooks. | Feature |
| `misc/AutoEatModule` | AutoEat | Adopt verified risky/rare food exclusions and manual-slot ownership. Keep nutrition priorities/hysteresis; do not add automatic full-hunger healing without explicit policy. | Logic + InventoryPolicyTest |
| `misc/AutoReconnectModule` | Native auto-reconnect | Retain last-server reconnect integration; reference disconnect callbacks belong to a different session/screen lifecycle. | Feature |
| `misc/AutoRelogModule` | RelogLoader | Retain explicit loader/dig workflow; reference one-time deepslate relog is not equivalent and is not automatically enabled. | Feature/workflow comparison |
| `misc/AutoReplenishModule` | AutoReplenish | Adopt depleted-stack memory and atomic SWAP; keep exact-component matching, action pacing and inventory ownership. | Logic + InventoryPolicyTest |
| `misc/ChatMacroModule` | QuickMacro | Retain existing macro controls; reference eight hotkeys/open-chat action are optional UI features, not evidence to replace the current macro executor. | Feature |
| `misc/CoordSnapperModule` | CoordSnapper | Adopt local own/target coordinate clipboard modes. Preserve CENTER default; reject automatic external-coordinate webhooks and unloaded long-range targeting. | Logic + verified clipboard API |
| `misc/FastUseModule` | Native fast-use | Retain host held-use handling; no reference use/packet cadence transplanted without validation against current input arbitration. | Feature |
| `misc/FreecamModule` | Native freecam | Retain detached camera and current extraction integration; reference camera service is not interchangeable with the 26.2 renderer. | Feature |
| `misc/HomeMetaModule` | Native HomeMeta | Retain separate native workflow. Do not fold delete-home/set-home/RTP/home into an unrelated addon utility. | Reference workflow comparison |
| `misc/LegitAutoToolModule` | AutoTool | Adopt user-slot ownership and GUI/item-use yield. Keep current anti-break and block/weapon scoring; no speculative server drill names added. | Logic + InventoryPolicyTest |
| `misc/NameProtectModule` | NameProtect | Adopt whole-name and cross-style redaction; retain display-only chat scope and explicitly do not claim hover/tab/nametag coverage. | Logic + NameMaskTest |
| `misc/NetheriteFinderModule` | NetheriteFinder | Retain existing packet-palette/reveal hooks. Reference advanced probe visuals were not substituted for actual server reveal evidence. | Prior/feature comparison |
| `misc/PlayerBypass2Module` | PlayerChunks: Simulation Activity | Keep independently adapted out-of-simulation activity mode, expiry and own/visible-player exclusions; richer reference signal calibration remains a documented non-parity feature. | Prior implementation + PlayerFinderSignalsTest |
| `misc/PlayerBypassModule` | PlayerChunks: Light Data | Keep explicit-zero-payload mode and nearby-air check; missing/empty data cannot become player proof. Persistent protected-chunk history is not silently recreated. | Prior implementation + PlayerFinderSignalsTest |
| `misc/PlayerDetectionModule` | VisualRange | Retain actual player-range notification; do not replace visible-player evidence with a generic world-presence guess. | Feature/workflow comparison |
| `misc/SkinChangeModule` | SkinChanger | Retain current skin loader/renderer hooks; reference self/other skin services and swap caches are not a drop-in for this host. | Feature |
| `misc/SlowSwingModule` | SwingSpeed | Retain fractional timer carry; reference rounded duration does not automatically improve fractional multiplier response. | Implementation comparison, retained |
| `misc/SpamModule` | Native spam | Retain current chat sender; no duplicate reference spam schedule or implicit activation introduced. | Feature |
| `misc/SpawnerProtectModule` | SpawnerProtect | Adopt clean storage/world exit and foreign-screen/item-use guards. Retain explicit enemy-triggered collection rather than reference break-burst mining triggers. | Logic + compilation |
| `misc/StaffListModule` | TabDetector | Retain tab-based detection; a reference-maintained Donut staff list is not authoritative for every server and was not copied as a universal detector. | Feature |
| `misc/WeatherNotifierModule` | WeatherNotifier | Adopt 20-sample transition stability and fresh world baseline; retain existing rain-stop and thunder alerts. | Logic + WeatherStabilityTest |
| `movement/AutoJumpModule` | Parkour | Retain ledge/parkour assist; reference automatic jumping is a related input feature, not a replacement for route geometry. | Feature/workflow comparison |
| `movement/BoatFlyModule` | Native BoatFly | Retain current ridden-vehicle control; no reference vehicle movement-packet engine transplanted. | Feature |
| `movement/ElytraFlyModule` | ElytraTravel | Retain destination autopilot. Reference packet/bounce/manual elytra modes are separate functionality and not silently enabled as travel behavior. | Settings/workflow comparison |
| `movement/EntityControlModule` | Native EntityControl | Retain rideable-entity input integration; no saddle/control mixin replacement selected for current host. | Feature |
| `movement/SneakModule` | Native sneak | Retain native key handling; avoid a second owner synthesizing sneak through a reference event service. | Feature |
| `movement/SpeedModule` | Native speed | Retain native movement modes; do not claim reference ground-speed modifications are validated for current physics/server constraints. | Feature |
| `movement/SprintModule` | Sprint / native sprint | Adopt valid-condition synthetic-key handling and leave cleanup in the addon-owned module; native toggle-sprint integration retained. | Logic + compilation |
| `render/BlockEspModule` | Native block-esp | Retain current selected-block rendering; reference touching-block shape merging is a renderer feature, not a verified backend replacement. | Feature |
| `render/ChunkScannerModule` | ChunkFinder | Corrected 2026-10-12: adapt the basic stone-stack detector and heat patches; retain Stash Finder separately. Seed-dependent Advanced Scanner is not ported. | Basic detector adaptation + focused fixtures |
| `render/EspModule` | Native esp | Retain current entity outlines/boxes/tracers; reference entity renderer hooks differ from current extraction pipeline. | Feature |
| `render/FreelookModule` | FreeLook | Keep prior collision/culling camera adaptation while enabled. No player-collision or transparent-block claim added. | Prior implementation/compilation |
| `render/FullbrightModule` | Native fullbright | Retain current lightmap integration; reference gamma override is not a reason to add a duplicate addon lightmap owner. | Feature |
| `render/HoleEspModule` | Native HoleEsp | Retain current hole/tunnel geometry renderer; no reference-renderer substitution without geometric/runtime validation. | Feature |
| `render/ItemEspModule` | Native item-esp | Retain dropped-item rendering/filter integration; no second outline/tracer pipeline added. | Feature |
| `render/LightEspModule` | LightSourceFinder | Retain loaded light-source scouting; reference underground artificial-light highlights are not base confirmation. | Feature/workflow comparison |
| `render/NametagsModule` | Native NameTags | Retain current player tags/equipment/health rendering; reference tags require their own layout/render API. | Feature |
| `render/NoRenderModule` | Native NoRender | Retain current overlay/entity effect hooks; no old-version renderer interception copied blindly. | Feature |
| `render/PrimeChunkFinderModule` | PrimeChunkFinder | Adopt bounded world-local scan cache, refresh and stale-result removal. Preserve fluid workflow rather than relabel it as reference light/redstone detection. | Logic + both compilations |
| `render/StorageEspModule` | Native storage-esp | Retain current per-type storage highlighting; no conflicting block-entity renderer installed. | Feature |
| `render/SusChunkFinderModule` | SusChunkFinder | Keep complete prior replacement selector: Classic and ANBS+ Scan, actual distinct scans, packet refresh, terrain-dependent peak policy; old twelve-way dispatcher remains removed. | Prior implementation + SusScoreTest/settings tests |
| `render/TracersModule` | Native tracers | Retain native entity tracer integration; reference tracer service is a renderer counterpart, not a safe code transplant. | Feature |
| `render/TrajectoriesModule` | Native Trajectories | Retain current projectile prediction/rendering; no untested 1.21.11 physics port claimed as better. | Feature |
| `render/XrayModule` | Native xray | Retain current whitelist/fade renderer; no reference terrain-culling implementation added to the addon. | Feature |

The source-derived inventory supplies the exact local source paths for every row. Reference feature descriptions were re-read from all 63 actual constructors before this ledger was written. Implementation/testing evidence for the adopted entries is listed below. The Feature entries deliberately remain compatibility decisions, not fabricated algorithm audits.

### Adopted in this pass

| Reference / local module | Improvement adopted | Verification |
| --- | --- | --- |
| AhSniper / AH Sniper | Name include/exclude, minimum stack and cancellation when targets change; filters repeated at confirmation. Fork additionally gains exact prepared-item rechecks, confirmed-buy cap, confirmation previews, post-confirmation inventory growth, API timeout and stale-response generation guard. Total-stack budget retained. | AhGuiTest / AuctionUiTest, complete builds |
| AhSell / AH Sell | Explicit item targets by default, all-hotbar selling only by opt-in, held-stack/price recheck, sale-only dialog and preview validation, wait for inventory removal, stop rather than retry an unconfirmed listing. | AhGuiTest / AuctionUiTest, complete builds |
| AutoEat / Auto Eat | Verified reference blacklist excludes rotten flesh, spider eye, pufferfish, poisonous potato, raw chicken, chorus fruit, suspicious stew and enchanted golden apples by default; optional risky/rare-food opt-in. Manual slot changes cancel eating without restoring over the user's choice. | InventoryPolicyTest |
| LegitAutoTool / Auto Tool | Slot restoration only while the module still owns the selected slot; no swaps during menus or item use; no stale slot state across worlds. Existing anti-break and weapon/block preferences retained. | InventoryPolicyTest, compilation |
| AutoReplenish / Auto Replenish | Remember the just-depleted selected stack, clear templates on selection/world changes, use one atomic vanilla SWAP rather than a three-click carried-cursor sequence, yield to screens/cursors/item use/mining/container ownership. | InventoryPolicyTest, compilation |
| WeatherNotifier / Weather Notifier | Twenty consecutive changed samples before notification, interrupted-change reset, fresh rain/thunder baseline on world changes. Existing thunder and rain-stop alerts retained. | WeatherStabilityTest |
| PanicPay / Panic Pay | Validate recipient and amount, reject self-payment/command injection, await a recipient-matching system acknowledgement before optional disconnect, bind callbacks to request generation/connection, stop without retry/disconnect after unknown reply or timeout. No automatic inventory sale added. | PaymentPolicyTest |
| NameProtect / Name Protect | Whole-name boundaries, literal alias text, mask usernames spanning styled component runs and preserve unaffected rendered styles. Still chat-only; no claim of full hover/tab/nametag privacy. | NameMaskTest |
| CoordSnapper / Coord Snapper | Preserve CENTER default; optional one-shot COPY_OWN and COPY_TARGET modes write only to the local clipboard, optionally with dimension. Target mode uses the current loaded block hit, not reference long-range unloaded raycasts or webhooks. | Verified KeyboardHandler API and compilation |
| SpawnerProtect / Spawner Protect | Clear storage/mining/rotation state on disable or leave; do not mine through foreign screens or active item use. Existing enemy-triggered collection/shulker workflow retained rather than silently adopting reference block-burst triggers. | Compilation; storage flow needs live validation |
| AntiTrap / Anti Trap | Do not attack the player's vehicle/passenger hierarchy, obey actual vanilla entity interaction reach and line of sight, yield to screens/item use, pace actions, report an attack rather than claim an unverified destruction. Reference client-only entity deletion not adopted. | Verified Player/Entity APIs and compilation |
| Sprint / Sprint | Only synthesize the sprint key under valid forward movement conditions; release only the synthetic key the module claimed; reset across worlds. Do not force sprint input through menus. | Compilation |
| PrimeChunkFinder / Prime Chunk Finder | Bound scan memory to loaded/range-relevant chunks, clear flags across worlds, rescan after five seconds and remove stale results before rescoring. Existing fluid detector retained as a different workflow; reference underground light/redstone algorithm not represented as an exact port. | Both production implementations compile |
| KeyThrow / Key Pearl | Recheck the selected pearl before delayed use, pace the action, restore only the owned slot and never reactivate a one-shot throw across worlds. | InventoryPolicyTest and both implementations compile |

The addon and fork trading architectures differ. The fork uses its own small AuctionUi helper rather than importing the addon's complete flipper dependency graph. Its SpawnerProtect uses the existing native Aim backend; the addon keeps RotationEngine. Selected fork files were checked against the pre-comparison backup using explicit UTF-8 decoding before mechanical adaptation. The user's separately modified SusChunkFinder/SusScore/SusScoreTest files were excluded from this pass's sync.

### Retained or not copied

| Related group | Decision and reason |
| --- | --- |
| SusChunkFinder, PlayerBypass, PlayerBypass2, Freelook | Keep the preceding independently authored finder/camera work. Both Sus modes and both player-signal modes remain registered. Their documented simplifications and live-validation limits still apply. |
| NetheriteFinder, AmethystBypass, ChunkScanner, LightEsp | Keep existing palette/reveal hooks and shared chunk rendering; do not replace real ore-reveal events or specialist stash/light scans with generic glow or packet-activity claims. Reference advanced probes and visual styles were not blindly copied. |
| RegionMap, Radar, Performance | Preserve the current map's RTP dots, dimension mapping, custom-image/heatmap controls and bounded telemetry. A HUD/settings counterpart does not justify replacing its map or render backend. |
| Mace, ElytraFly | Existing Mace PVP already exposes preferred enchantments, smash readiness, cooldown and swap-back controls; elytra travel is a destination autopilot, not the reference's packet/bounce flight engine. Their existing implementations remain; no unverified movement-packet mode added. |
| AutoTpaHere, ChatMacro, StaffList, PlayerDetection, AutoRelog | Related to existing TPA/macro/tab/range/relog tools, but not interchangeable: reference bulk player discovery, repeated TPA requests, macro sending and relog sequences are not silently enabled as defaults. |
| SkinChange, SlowSwing, AutoJump | Keep current skin hooks, fractional swing carry and parkour behavior. Reference rounded swing duration is not automatically better than the existing fractional slider response. |
| HomeMeta | Keep native HomeMeta and the addon's separate home workflows. Do not introduce the reference's delete-home/set-home/RTP cycle into an unrelated utility without an explicit user action. |
| Native AimAssist | Existing quantized mouse-count smoother and scaffold/totem/silent-rotation arbitration retained. Reference mouse-count and target-hysteresis concepts already have native equivalents. |
| Native AnchorAura, AutoTotem, CrystalAura, KillAura, AntiVanish | Feature counterparts retained, not wholesale replaced. This pass did not assert that all native combat algorithms are better or bug-free; no verified replacement was selected that outweighed 26.2 integration and safety risks. |
| Native AutoClicker, AutoReconnect, FastUse, Freecam, Spam, BoatFly, EntityControl, Sneak, Speed | Current host implementations retained. Reference behaviors depend on its own input/event/packet services; no unsupported movement or disconnect engine was copied into the addon. |
| Native Hud, BlockEsp, Esp, Fullbright, HoleEsp, ItemEsp, Nametags, NoRender, StorageEsp, Tracers, Trajectories, Xray | Existing renderer integrations retained. Reference ImGui/render-event implementations and cosmetic defaults are not interchangeable with the current extraction pipeline. No claim of pixel-level parity was made. |
| Remaining 67 unmatched catalogue entries | The inventory records them rather than inventing new counterparts. Cosmetic, audio, order-selling and other reference-only features were not added as unrelated new modules. |

### Migration and runtime boundaries

- AH Sell now requires an explicit Items list or the deliberate **Sell all hotbar items** toggle. Existing configurations relying on an unfiltered hotbar must opt in before activation.
- Auto Eat excludes risky/rare food by default. **Allow risky or rare food** restores deliberate access to those items.
- Panic Pay acknowledges only supported plain success messages addressed to the configured recipient. An unsupported server format leaves you connected and stops after ten seconds; no payment retry occurs. This is not a guarantee of transfer success on every server.
- Coord Snapper's default remains centering. Clipboard modes are local one-shot actions and do not upload coordinates.
- No trades, payments, teleport requests, trap attacks, spawner breaks or new-module activation were performed against the live survival session during this comparison.
- Complete builds passed offline: addon 284 tests, fork 1,251 tests, zero failures/errors. Policy, parser and chat-component tests are executable evidence; GUI/network/gameplay outcomes remain unverified in the running client.
