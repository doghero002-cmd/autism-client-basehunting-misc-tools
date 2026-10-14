# Base detection validation checkpoint

This checkpoint improves candidate reliability; it does not establish flawless detection or calibrated base probabilities.

## Active implementation checkpoint: 2026-10-12

The matching basic finder replacements and guarded V4 AI bridge are implemented and build-tested. Offline client tests verify passive finder activation, safe AI rejection/cleanup and successful end-to-end read-only delegation with the actual host controller. This is not complete Anubis feature parity or a guarantee of flawless base detection.

Implemented in both projects:

- Prime now uses baseline-aware underground light-packet differences, redstone updates, amethyst/lamp explanations, bounded queues/history and own-chunk exclusions. The old stranded-fluid implementation is removed. Initial snapshots cannot starve pending differences.
- Light Source Finder now collects underground block light stronger than skylight, applies the reference weak-region denoising and renders at most 12,000 exposed cells. Collection is resumable with a 2 ms soft budget. Its read-only ownership handoff no longer copies the full hit map on the client thread, and further collection after transfer throws. Worker neighbor lookups reuse a mutable cursor without storing mutable keys.
- Chunk Finder now uses the reference basic stone-stack detector at Y 0..32, with same-material blob walls, stone/deepslate caps and connected heat maxima. The unsafe live-world background heuristic worker is removed. Heat spreading uses equivalent bounded prefix sums. The reference seed-dependent Advanced Scanner is not implemented.
- Netherite retains its existing hidden-palette/reveal workflow and Minecraft 26.2's extra fluid-count header. Parsing now rejects trailing/truncated/invalid data without debug chat. Its reference advanced probe engine is not represented as an exact port.
- Sus Dog+ uses the reference 72-layer cached block-light edge test without the old 768-edge truncation. Missing neighboring chunks defer a sample. Optional mined-geode detection is disabled by default and adds exposed-lining cell clusters, 40-tick settling, growth rejection and torch-light source exceptions.
- Old `ANBS+ Scan` display tokens normalize to `ANBS_PLUS` only for the owned Sus enum through a scoped decoder mixin. Unrelated enums remain unchanged. Unit tests exercise the normalization and real decoders; runtime mixin application still needs a client launch.

The earlier inventory incorrectly mapped reference Chunk Scanner to Stash Finder. Its actual basic counterpart is Chunk Finder. Stash, Spawner, Portal, Sign, Structure, Growth, Activity and Tunnel specialists have no equivalent finder in this reference and remain available rather than being silently removed. Player Finder's earlier Light Data/Simulation Activity adaptation remains; reference Player Detection is the visible-player safety workflow, not its hidden-player counterpart.

Addon-only AI control:

- Enable **AI Remote Control** in Automation. It is disabled by default and automatically disables on world leave. Ordinary hosts without the verified AI controller fail closed; no AI class is needed to load the addon.
- `tools/client-ai.ps1` exposes Status, Submit and Cancel through authenticated loopback `/ai` endpoints. The token is generated into the game directory's `run` folder, excluded from workspace backups, and removed on disable. Tokens and host model credentials are never returned by the API.
- Adapter descriptors and phase constants are checked against controller SHA256 `67B22C40A86D8F57AB320DC731589765B0C15373F339C4E64871BEE450252DF0`. Opt-in activation uses its normal public singleton initializer when necessary. State identity resolves through exact static fields: obfuscated field names do not equal runtime enum names such as IDLE or PAUSED. Submit/status/stop invoke verified methods on the client thread; the public V4 API itself has no submit method.
- Host access checks and plan approval remain intact. Busy/paused/approval-waiting hosts and existing user queues are not replaced. Cancellation requires the owned task ID and matching world/session/objective; completed tasks are no-ops. HTTP fixtures cover authorization, origins, methods, malformed/oversized bodies and disabled state.
- The original approval-waiting PAUSED session was correctly preserved and rejected new work. End-to-end delegation then succeeded in an isolated idle QA session using the same settings: the actual host answered `minecraft:overworld`, independently checked against live game state. The original AI store was restored with all 60 file hashes unchanged; no existing plan was approved, replaced or cancelled.

### Offline runtime evidence

The designated Prism instance `autism client(1)` was launched as offline player `doghero9` into `New World`, not connected to DonutSMP. Another game owned port 25660 and was left untouched. QA used `-Dautism.seedcracker.bridge.port=25661`; the caller accepts `-Port 25661`. Default operation remains 25660 and Host checks accept only the configured port.

- Installed QA host controller fingerprint matched the verified adapter. QA addon hashes were checked against built artifacts before launch.
- Live discovery initially showed the module disabled. Enabling with the actual installed command syntax `.toggle AI-Remote-Control` activated it. The normal initializer fix changed real readiness from false to true.
- Live enum labels were IDLE/PAUSED, exposing a defect missed by descriptor-only tests. Exact-field phase identity now has a regression fixture.
- Task `b06c65b0-7df8-4cc6-b6d5-ee05f56f6741` returned QUEUED, then FAILED with the existing host in PAUSED/awaitingApproval. Cancellation returned FAILED and the approval state remained unchanged. This is verified rejection/ownership safety, not successful completion.
- Repeated real module-disable checks confirmed `enabled=false` and deletion of the token file. Only positively identified QA PIDs were stopped for rebuilds; the other game was not stopped or sent commands.
- Prime, Sus, Light, Chunk Finder and Waypoints each logged successful enable messages in the actual updated client. No relevant mixin injection or finder-worker exception appeared in the inspected runtime slice. Specific ore/base detection, respawn/dimension behavior and marker geometry still need dedicated scenarios.
- A bounded 20-second JFR profile with those passive modules enabled recorded 2,350 execution samples and zero garbage collections. Of 1,980 samples containing addon finder/module frames, 522 contained `UndergroundLight.hits`, 1,260 `surface`, and 177 `LightSourceFinderModule.tick`. These are sampled CPU counts, not FPS or duration estimates. The main-thread map copy was removed based on this evidence, with output-equivalence and read-only handoff tests in both projects.
- Raw recording: `build/live-qa-20261012/passive-finders.jfr`, intentionally excluded from backups. It is local diagnostic data, not a distributable artifact.
- A second 20-second recording on the final optimized addon (`optimized-finders.jfr`) recorded 1,047 execution samples, 396 with addon finder/module frames, zero `UndergroundLight.hits` samples, 24 `surface` samples and 259 `LightSourceFinderModule.tick` samples. Mean sampled process user CPU fraction was 0.0809 versus 0.1074 in the first recording; neither recorded a GC. Different process warm-up and scan phases can affect these counts, so this is observed profiling evidence, not an FPS guarantee or controlled accuracy study.
- The profiled QA artifact had SHA256 `B8C54028996024ADF6E7FF64DFAA989D3FB62378D5540FAFC10D6C0013F3D96B`. Passive modules were disabled before a graceful close of verified QA PID 24204; its temporary launcher override was restored and the other client remained healthy. The final result-reporting artifact is identified below.

### Successful live delegation receipt

- To avoid replacing the existing user's plan, the inactive QA AI store was moved outside the active path and its 60 original files hashed. A clean test store contained only a byte-identical copy of the existing settings, without original sessions. No model provider, credentials or approval policy was changed.
- Task `277eba6f-38c2-43e7-9104-cf0943f83d0c` progressed QUEUED -> RUNNING -> ANSWERED through the actual authenticated bridge. The host's answer `minecraft:overworld` matched `/state.dimension`; cancelling that completed task returned ANSWERED without changing it.
- That first success exposed a reporting defect: the host's operation summary remained `Not started`, while its actual question answer was in the verified status-message getter. `taskResult` now uses that answer for the ANSWERED phase, with completion-summary fallback and the existing 8,000-character response bound. A regression fixture covers the live case.
- Final artifact SHA256 `98D8C108F77278D87E86575D6B724EFE81AA7BAC09C6E6973BA686BCD3BAD277` was installed and verified. Task `fa2338c4-7fb1-4ebe-9801-906ecbc28c1c` progressed QUEUED -> ANSWERED and returned `result="minecraft:overworld"`, `message="minecraft:overworld"`, `awaitingApproval=false`. Its task ID and result were independently asserted against the submitted ID and live world. This is genuine host execution, not a mock response or only a unit test.
- QA PID 12520 was closed gracefully after module disable and token deletion. All 60 original AI-store files were restored byte-identically, the original launcher argument behavior restored, and the other client's port-25660 endpoint still returned `ok`. Isolated test records remain local outside the active store and workspace backup.

The reference declares GPL-3.0-only; the project's existing LICENSE is Unlicense. These independently authored behavior adaptations are not a legal clearance for redistribution. No public release, tag or project commit was made; review licensing before public redistribution.

Additional retained fixes, in both projects:

- Sus scan label is `Dog+`; its enum key remains `ANBS_PLUS`. Current and retired display labels round-trip through the scoped migration described above.
- Sus chunks-per-tick ceiling is 128, default 2, with the existing 3 ms soft deadline. This is a cap, not a guarantee of 128 completed scans each tick.
- Successful Sus samples survive range changes and chunk unloads within the same world/mode session. Packet and queue paths skip cached chunks. Changing speed/render settings does not reset history; mode changes, disable/re-enable and world changes do.
- Incomplete Classic scans with unknown bud-support chunks are not cached, so they can retry when support data arrives. This is a deliberate safety difference from treating missing data as placed growth.
- The Anubis `DonutFolia` server-brand branch is ported: Classic uses section palette membership there, not per-block growth counts.
- Equal heat peaks are kept as in the reference. The previous additional coordinate/evidence tie suppression is no longer used by the module.
- Sus rebuilds enumerate the configured active area rather than iterating the complete retained history. Retained history still grows with visited chunks until reset; long-session memory cost needs measurement.
- Finder marker Y is fixed, defaults to 16, and is configurable in addon Waypoints (`marker-y-level`) or native Chunk Waypoints (`y-level`). Disabling the controller retains its last height. Beam endpoints use the marker plane; height is clamped to the dimension.
- Renderer overlap/density and hotspot-colour queries use fixed 5x5 neighbourhood map lookups instead of scanning all marker pairs. The sparse multi-source overlap early-exit defect is covered by fixtures. These are algorithmic improvements, not measured live FPS results.

Reference anchors are under `notes/anubis-0.9.9.1/build/all-decompiled/net/anubis/client/module/impl`, not a `modules` directory. Deciding paths: Prime packet/snapshot analysis; LightEsp `buildBoxes` and `util/LightSnapshot`; ChunkScanner `scan`, `isArea`, `keep` and `hottestPerPatch`; Sus `collectGeode`, `computeMined`, `sourceLight`, `computeVisible` and `isPeak`; `util/GeodeGlowScan`; and `util/netherite/HiddenDebrisScanner`.

Remaining validation, not claimed completed:

1. Load these exact artifacts in the designated QA instance; verify packet baselines, own-chunk exclusions, dimension changes, respawn, unload/reload and decoder mixin application.
2. Reproduce the reported missed-base case with coordinates/mode and a labelled negative control. A large natural geode or geode above a base is not sufficient ground truth.
3. Measure mixed/geode scans, optional mined-cluster checks and allocation pressure. Sus/Prime still have whole-section operations inside soft deadlines; 128 is a ceiling, not a latency guarantee. Retained no-rescan history grows until reset.
4. Successful read-only delegation, rejected/completed-task cancellation and disable/token cleanup are live-verified. Longer-running movement/inventory workflows and provider-specific failure behavior are outside this smoke test; host authorization and approval remain authoritative.

The source review caught and corrected a mistaken mode distinction: both Anubis reference modes use peak filtering, and neither suppresses equal peaks. Follow the inspected source, not that earlier conversational inference.

## Verified changes

Both the addon and native fork now have:

- Anubis-style equal regional geode heat peaks retained without additional tie suppression.
- Exact chunk/source registry keys, including distinct Java-hash-colliding source names.
- Physical expiry pruning and a 2.5-second TTL compatible with one-second reporting.
- Empty results for nonpositive reader limits and rollback-safe expiry.
- Deterministic candidate grouping and a circular 48-block merge radius.
- Candidate coordinates anchored on the strongest actual report, rather than an empty centroid.
- Conservative combination: correlated finder names cannot increase the strongest score.
- Reporter scores refreshed after 100 client ticks, with at most one chunk scored per reporter per tick.
- Fair rescoring of large flag sets, suppression of unloaded chunks, and empty/world-change cache resets.
- Cooldown-suppressed alerts remain eligible for later delivery.
- Registry and alert state cleared on respawn/dimension transitions as well as login.
- Combined alerts labelled as candidates with heuristic scores, not confirmed bases with probabilities.
- Storage/workstation evidence counted from block palettes, including coloured shulkers, non-entity workstations and all anvil variants.
- Shared terrain classification uses Minecraft's palette frequency counter instead of classifying every block cell separately.

## Reproduce the checks

From the workspace root, using the existing JDK 25 wrapper:

```powershell
& '.\tools\gradle-quiet.ps1' -Project '.\autism-seedcracker' -Tasks @('build','--offline')
& '.\tools\gradle-quiet.ps1' -Project '.\swccs-client' -Tasks @('build','--offline')
```

Current full build checkpoint: addon 334 tests; native fork 1,293 tests. Both completed successfully with zero failures, errors or skips, verified from the full-suite XML reports. A forced `compileJava --rerun-tasks --offline` also passed after stale IDE tail/classpath diagnostics appeared.

Focused fixtures include `finder/BaseTrackerTest`, `FinderReportTest`, `BaseAlertsTest`, `BaseConfidenceTest`, `ChunkFlagRendererTest`, `UndergroundLightTest`, `StoneStackScannerTest`, `GeodeGlowSnapshotTest`, `MinedGeodeTrackerTest`, `netherite/HiddenDebrisScannerTest`, `util/pure/PrimeLightTest` and `SusScoreTest`. Addon `bridge/ClientAiControlTest` includes actual HTTP and vendored-bytecode contract checks.

The 150-chunk reporter fixture runs 300 ticks and verifies that every chunk is scored twice. It verifies scan-count fairness, not elapsed frame time. Three correlated 55-point reports remain a 55-point candidate; adding a stronger 90-point report anchors the candidate on that report.

Real Minecraft block-palette fixtures verify workstation multiplicity, coloured storage, plain stone, sparse mineshaft signals and a mixed storage room. The storage room scores 83; plain stone scores zero. These are controlled evidence fixtures, not a representative world dataset.

The CPU fixture uses 24 solid-stone sections, 100 warm-up scans and 100 timed scans. Measurements on this machine:

| Project | Before median / p95 | Palette counter median / p95 |
| --- | --- | --- |
| Addon | 4.939 / 5.553 ms | 0.010 / 0.018 ms |
| Native fork | 4.266 / 5.944 ms | 0.010 / 0.016 ms |

This demonstrates the uniform-palette fast path only. Mixed-palette scans, allocation pressure, geode-mode scans and in-game frame times still need measurements. Timing assertions do not impose machine-dependent performance thresholds.

## Limits and pending measurements

- Mature natural geodes, natural lighting and activity remain scouting signals, not proof of a player base.
- Shared terrain scoring can still mistake generated structures for construction. No labelled-world precision/recall study has been completed.
- The five-second freshness window is measured in client ticks. Large flag sets may temporarily omit expired scores instead of presenting stale evidence.
- The scan cap applies per reporter, not globally across all enabled modules. Only the uniform-stone shared scorer CPU cost has been measured; live frame-time impact remains unmeasured.
- Removing a flag stops refreshing its registry entry; its previous report can remain visible for up to 2.5 seconds.
- Strongest-score fusion deliberately produces fewer high-score alerts than the previous noisy-OR. Alert coverage needs labelled fixture and live review before threshold changes.
- Compilation validates mixin references, but dimension-transition behavior has not been observed on the updated live build.
- The read-only bridge at `127.0.0.1:25660` was unavailable, and the documented QA instance was not running. No new DonutSMP results are claimed.

## Next nine hours

These are nine consecutive one-hour work budgets, starting when the next execution session begins. This is a plan, not a claim that these hours have elapsed or that work will continue after a chat turn ends. Accuracy validation remains the first priority; builds alone do not close it.

| Elapsed time | Work | Deliverable and gate |
| --- | --- | --- |
| 0:00-1:00 | Establish labelled local cases for natural geodes, villages/ruins, mineshafts, sparse camps, storage rooms and chunk-border construction. Separate negative controls from player-built positives and reserve untouched cases for validation. | Fixture manifest with expected labels, evidence coordinates and reproducible construction. Unknown cases stay unlabelled; do not manufacture ground truth from detector output. |
| 1:00-2:00 | Run Classic, ANBS+ and shared terrain scoring on the manifest. Record false positives, misses, unknown results and marker displacement separately for each mode. | Per-mode confusion counts and localization errors with denominators. Preserve the current build as the comparison baseline; do not tune on the reserved cases. |
| 2:00-3:00 | Fix the highest-impact reproducible accuracy defect from those results in both projects. Add a regression fixture before adjusting thresholds or evidence rules. | Before/after results for the affected cases, passing focused tests in both projects, and no regression on reserved cases. If no defect is demonstrated, expand negative controls instead of making speculative changes. |
| 3:00-4:00 | Measure mixed palettes and both geode scans, queue saturation, large flag sets and all-reporter load. Record median/p95 scan time and stale-result latency; examine allocation pressure. | Repeatable timing results distinguishing CPU fixtures from live frames. Any optimization must preserve fixture outputs and bounded/fair scheduling. Do not generalize the uniform-stone fast path to all chunks. |
| 4:00-5:00 | Load the rebuilt jar in the designated QA instance, verify its SHA-256, then exercise dimension changes, respawn, unload/reload, mode switches, module disable and alert cooldown. | Runtime lifecycle checklist tied to the loaded artifact. If the instance cannot start, use deterministic lifecycle/queue fixtures and record runtime cases as unverified; do not touch another Minecraft instance. |
| 5:00-6:00 | Confirm Donut server identity and the loaded artifact, then passively observe candidate stability and scanner cost. Cross-check visible evidence without assuming an unseen base exists. | Timestamped, privacy-conscious observations labelled confirmed, contradicted or unknown. No trades, payments, attacks, mining, teleport spam or unsolicited chat. If access is unavailable, run local replay/stress cases for this entire hour instead. |
| 6:00-7:00 | Revisit gravel/falling-block recovery, lava stops and rotation/input ownership in base-hunting movement. Reproduce failures locally before changing movement code. | Recovery fixtures and safe local trials; no indefinite movement hold, repeated retreat loop or movement into a known lava hazard. Changes stay within the existing movement owners and must pass their focused tests. |
| 7:00-8:00 | Review candidate presentation and source/reason reporting against the collected results. Distinguish weak geode/activity signals from corroborated construction and stale/unknown evidence. | Consistent candidate/score language and source provenance in touched outputs; no probability or confirmed-base claims without calibration. If presentation already meets this gate, spend the hour on uncovered accuracy cases. |
| 8:00-9:00 | Rerun reserved accuracy cases and both full builds. Compare final counts, localization and timing against the baseline; document regressions and unresolved cases. Scan changed text and save a private backup. | Reproducible final evidence table, green required tests, verified-private backup commit and a ranked remaining-defect list. No project commit, release or unsupported claim of flawless detection. |

### Execution rules

- Each block is a time budget, not a promise that its gate will pass. A failing gate takes priority over starting dependent work; record which later block was displaced.
- Do not spend nine hours waiting for a launcher or network. Use the stated offline substitutes, and retain the live validation gap in the final report.
- Preserve a private backup after each completed green code chunk and at least every two hours during execution. Snapshot the addon source before any addon-to-fork sync; prefer narrow equivalent patches.
- The end-of-window decision is based on measured defects and coverage. Prioritize remaining accuracy or safety failures, then frame-time/memory regressions, then presentation. Do not add unrelated modules to fill time.

## Current downloadable jars

Filesystem verification on 2026-10-12 found exactly one jar in each generated output folder. Dependency jars in `autism-seedcracker/libs` are intentionally retained. Historical private backups and published releases are not part of this local-output cleanup.

| Current artifact | Bytes | SHA-256 |
| --- | --- | --- |
| `autism-seedcracker/build/libs/autism-base-finding-tools-2.4.5.jar` | 16556495 | `98D8C108F77278D87E86575D6B724EFE81AA7BAC09C6E6973BA686BCD3BAD277` |
| `swccs-client/build/libs/Slightly Waxed Cut Copper Stair Client-5.0-26.2-dev.jar` | 37064739 | `3C71E06DF2B286F65D86E1DFC4F8B3EB79AA6E21A6918AC9EAED566E614679A5` |

The earlier deletion command selected `autism-base-finding-tools-*.jar` in the addon output folder, excluded `autism-base-finding-tools-2.4.5.jar`, and removed each selected file using `Remove-Item -LiteralPath`. The subsequent direct check verified all 11 superseded addon filenames absent:

```text
autism-base-finding-tools-2.2.jar
autism-base-finding-tools-2.2.1.jar
autism-base-finding-tools-2.3.jar
autism-base-finding-tools-2.4.jar
autism-base-finding-tools-2.4.1.jar
autism-base-finding-tools-2.4.2.jar
autism-base-finding-tools-2.4.3.jar
autism-base-finding-tools-2.4.3+client5.0.jar
autism-base-finding-tools-2.4.4.jar
autism-base-finding-tools-2.4.4+client5.0.jar
autism-base-finding-tools-2.4.5+client5.0.jar
```

Reproduce the current inventory from the workspace root:

```powershell
$folders = @('.\autism-seedcracker\build\libs', '.\swccs-client\build\libs')
foreach ($folder in $folders) {
	$jars = @(Get-ChildItem -LiteralPath $folder -File -Filter '*.jar')
	if ($jars.Count -ne 1) { throw "Expected one current jar in $folder" }
	$jars | ForEach-Object { $_.FullName; Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256 | Format-List }
}
```

Hashes identify this checkpoint only; rebuilds may change them without changing the version label.