# Guarded Base Scout

Base Scout walks loaded overworld terrain, runs existing finders, and stops at nearby candidates for manual review. It is off by default and currently available in the addon, not the native fork.

**Logout is not a survival guarantee.** Instant damage, client freezes, combat tags, server rules and network failures can defeat a client-side guard. Do not leave valuables on a character used for experiments. A detector score is not a calibrated probability or proof of a base.

## Start A Session

Start on solid ground, with at least 16 hunger points, full armor providing at least 12 armor points, each damageable armor piece at least 25% durable, and a totem held in either hand. Creative mode skips the gear requirement, not terrain, health or proximity checks. Stop other movement automation first.

In client chat:

```text
.scout status
.scout start
.scout stop
```

The default session lasts at most 15 minutes and remains within 512 horizontal blocks of its starting point. Movement uses the built-in walking planner: no block breaking, placement, swimming, downward steps, gap parkour, bucket clutches or door opening. It does not RTP, tunnel, fly or use Baritone.

Base Scout enables Sus Chunk Finder, Light Source Finder, Chunk Finder and Stash Finder if needed. It stops only its own movement route and disables only finders it enabled. Previous scan budgets are restored unless another controller changed them. Existing finder detections are not erased.

Finder modes remain unchanged. Donut-mode Stash Finder requires below-Y0 storage: an above-ground storage fixture does not test that profile. Stash confidence now uses the shared bounded reporter, refreshing still-flagged chunks instead of keeping their initial score forever. Candidate selection checks every fresh report within the stop radius; eight nearer weak reports cannot hide a qualifying ninth candidate.

## Stops And Pauses

- Damage, fire, lava, health at or below the reserve (default 18 half-hearts), air below 240, or falling at least 2.5 blocks stops movement and attempts an emergency disconnect once scouting has started.
- A visible non-spectator player within 64 blocks, a hostile within 16 blocks, hunger or gear failure, manual movement input, position correction, or no packets for 3 seconds stops the session.
- A gap of more than 2 seconds between movement guard checks stops the session when the client thread resumes. It cannot react while that thread is frozen.
- Unsafe or unloaded terrain ahead, conflicting automation, changed settings, world changes, time/radius limits, 30 seconds without progress, and evidence-writing failures also stop the session.
- An open screen pauses walking. The session time limit still applies.
- Candidate score at least 70 within 128 blocks stops the session and writes an unreviewed candidate.

A stop is latched. Investigate the reason and explicitly use `.scout start` to re-arm; it never automatically resumes after danger. `.scout stop` closes the evidence writer. Detection cannot see players or hazards outside the information delivered to the client.

## Increase Scan Load

```text
.scout stop
.scout load 8
.scout start
```

The range is 1 through 128 chunks per tick. Individual finder limits and soft time budgets still apply. Start low and increase in separate sessions; higher settings can stall the client. This is a manual stress control, not adaptive FPS regulation. Changing load while moving stops the current session.

## Review Evidence

Local evidence is saved under the game directory at `run/scout-evidence/<session UUID>.jsonl`. Start, periodic observation, candidate and stop records contain session settings, position and available vitals. Nothing in this journal is automatically uploaded. Records are bounded to 512 per session, 8192 characters each and a 64-entry write queue; failures stop scouting.

Stop scouting, obtain the actual session and candidate UUIDs from the log, then use:

```text
.scout review <session-UUID> <candidate-UUID> confirmed-base
.scout review <session-UUID> <candidate-UUID> natural
.scout review <session-UUID> <candidate-UUID> unknown
```

Use `confirmed-base` only after independent inspection, `natural` for a verified natural feature, and `unknown` when uncertain. Candidates must already belong to the specified session. Reviews do not automatically modify detector thresholds. Precision and missed-base rates require labelled positive and negative areas; candidate-only logs cannot measure recall.

## Summarize A Longer Hunt

From the workspace root, point the read-only tool at the relevant game directory:

```powershell
$evidence = Join-Path $env:APPDATA 'PrismLauncher\instances\autism client(1)\minecraft\run\scout-evidence'
& .\tools\scout-evidence.ps1 -EvidenceDirectory $evidence
& .\tools\scout-evidence.ps1 -EvidenceDirectory $evidence -Scope Local
& .\tools\scout-evidence.ps1 -EvidenceDirectory $evidence -Scope Unspecified
```

New journal records identify local worlds. The default summary includes only explicitly multiplayer sessions; older logs without that field are `Unspecified`, not assumed multiplayer. Each candidate counts once, its last valid manual review wins, and `unknown`/unreviewed labels are excluded from the confirmed fraction. Coverage, stop reasons and source-group counts are included. This fraction is not overall accuracy or recall, and can be biased by which candidates were reviewed.

Input is bounded to 1000 session files, 4 MB per file and 8192 characters per record. Wrong session IDs, orphan reviews, duplicate candidates, malformed JSON and invalid scores fail closed. The tool does not change modules, tune thresholds, print base coordinates or upload data.

```powershell
& .\tools\tests\scout-evidence.tests.ps1
```

## Verification

The automated checks cover safety verdicts, guarded planner configuration, movement-owner identity and bounded journal lifecycle/manual review. Live QA results and remaining limitations belong in the session's test notes; a green build alone does not establish server survival or detection accuracy.

### Local QA, 2026-10-12

- Offline `New World`, designated Prism instance `autism client(1)`, isolated bridge port 25661. The active client on 25660 was not controlled.
- Unprepared survival session `720fbaf2-c5aa-407d-8fb8-f0286a727d06` stopped before movement with `supplies or hunger reserve`; health remained 20.
- Creative walking session `27283e58-be11-4b87-b35d-5d8959d18b27` moved from approximately (71.639, 72, 65.700) to (57.318, 73, 43.706), with sprinting false and health 20. Explicit stop left movement idle and disabled all four scout-enabled finders.
- Scan-load-128 session `7d712e62-eec4-4ca8-ac4b-d05403eead1d` stopped after about 12 seconds when the character left walking mode. Stop evidence was retained and owned finders were disabled. This is not a sustained maximum-load performance benchmark.
- Restored scan load 2, disabled scouting, restored the original position and survival mode, then requested graceful closure of only the QA game.

### Additional Integration Trials

- Bare elevated-platform control `4afea8a6-115d-42cf-8c2d-fc382f9f025c` ran 29.26 seconds with no candidate. Enabling the initially disabled host Speed module while scouting produced `another automation became active`, latched the stop and left movement idle. Speed was then disabled again.
- Initial above-ground storage was not a valid Donut-mode positive fixture. A subsequent trial remained paused by a chat screen; it had identical start/end positions and is not a completed negative test. These inconclusive attempts are not counted as detector misses.
- Profile-correct artificial positive session `df1468c4-e780-47cf-b8e7-c342c4053f75` used 32 below-Y0 chests and nearby workstations. Stash reported X:5000 Z:5000 at score 70; the scout stopped. Candidate `47970546-2741-42ed-901f-6fff2168f2fb` was manually reviewed through the real command and recorded `confirmed-base`. This is a constructed fixture, not discovery of a server player's base.
- Armored local survival trial `b85c3540-68d8-404c-9f7b-21eb2f1e65e9` started at health 20 with default protection enabled. One generic damage unit produced `damage received` at health 19, followed by an end record. The log records integrated-server shutdown and world saving immediately afterward; the QA process later exited normally. An HTTP state read after exit was unavailable, not an independent idle-state receipt.
- All 88 original QA save files were restored byte-identically after the process exited. Temporary platforms, storage, equipment and damage were removed. Only the temporary launcher JVM override was reverted; launcher-written play-time metadata was preserved.

Main-thread stall detection compiled with the safety/ownership checks; an intentional live freeze was not tested. Manual keyboard takeover, DonutSMP combat tags and remote-server emergency disconnect behavior remain unverified. The local positive/control pair and manual review exercise the workflow but do not establish precision or recall on real-world bases.