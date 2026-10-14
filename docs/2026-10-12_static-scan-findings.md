# Static scanner findings for the addon

These regex warnings identify capabilities, not proof of malware or a clean bill of health. The supplied `staticFlagsTriggered.json` provides rule IDs and severities but not regex definitions or matched locations. This review did not execute packaged binaries, hide code, or change features to evade detection.

## Artifact inspected

- File: `build/libs/autism-base-finding-tools-2.4.5.jar`
- SHA256: `98D8C108F77278D87E86575D6B724EFE81AA7BAC09C6E6973BA686BCD3BAD277`
- Inspection: source search, ZIP/nested-JAR metadata, static `javap` inspection of the cached SQLite dependency. No native executable was run.
- New scouting code was still under development and was not part of that inspected artifact.
- Supplied `fileProperties.txt` confirms the same SHA256 and size, 16,556,495 bytes. The scanner-hosted executable URL was not downloaded.
- A structured JDK class-file pass (`tools/InspectJarCapabilities.java`) inspected 666 top-level classes. It found the browser opener and two session-authentication calls, but no direct `Runtime.exec`, `ProcessBuilder.start`, `System.load` or `System.loadLibrary` invocation in those classes. Nested dependencies were assessed separately.

## Supplied flag metadata

The report lists `064-execution` at level 3, `001-session` and `x25-variablenames` at level 2, and `054-bin` at level 1. It also lists level-0 entries for nested jars, reflection, a known malicious link, separate-JAR execution and CFR decompilation failure. Severity zero is not treated as proof that a finding was absent.

The nested-JAR description explicitly says their contents were not scanned. Therefore the confirmed SQLite process probes cannot be declared the exact source of this scanner's level-3 execution warning without its matching rule/location. A top-level call to Minecraft's browser-opening platform utility is another relevant capability; coarse matching of executor names or RuntimeException is not process execution.

## Evidence and interpretation

| Reported warning | Verified evidence | Interpretation and boundary |
| --- | --- | --- |
| Launches separate programs | Nested `sqlite-jdbc-3.53.1.0.jar` contains `OSInfo` and `ProcessRunner`, with OS probes including `uname -o`, `uname -m`, `which readelf` and `Runtime.exec`. The structured top-level audit found no direct process-launch invocation; `DatabaseCommand.openURL` calls Minecraft's `Util$OS.openUri` to open the public sheet in a browser. | These are confirmed packaged capabilities, not exact attribution of `064-execution`. The scanner says nested contents were not scanned; matched locations are still needed. No claim of detection evasion or full safety follows from this evidence. |
| Accesses Minecraft session information | `kaptainwutax/seedcrackerX/util/Database.joinFakeServerForAuth` passes the profile UUID and access token to Minecraft/authlib's session service. `TimeMachine` calls it only when database submissions are enabled and the connection is a non-local multiplayer server with more than ten players. `Config.databaseSubmits` defaults to false. | This is real sensitive session access for optional database authentication. The Google upload payload does not contain the token, but does contain server address, dimension, seed, version and username. Keep submissions disabled unless that sharing is intended. Authentication uses the client's configured session service; this review does not certify every possible alternate-auth provider. |
| Likely obfuscated code / suspicious variable names | The addon contains mathematical libraries and references to obfuscated names in the existing host AI adapter. The exact class/regex match was not provided. | Short variable names alone cannot establish malicious obfuscation. No exact attribution or claim of complete deobfuscation is made. |
| Binary data or binary download | The archive bundles JOCL 2.0.5 and SQLite JDBC native libraries, including Windows DLLs and Linux/macOS libraries. They are explicitly included in `build.gradle.kts` for OpenCL GPU cracking and SQLite persistence. | Embedded native assets explain the binary-data portion of the warning. They are not, by themselves, evidence that the addon downloads executable payloads. Native-code trust and dependency provenance still matter. |

The literal top-level HTTP hosts found by the static pass were `api.donutsmp.net`, `api.openai.com`, `docs.google.com` and `script.google.com`. This is not a blacklist verdict. The `x18-malicious_link` entry does not identify its matched URL, so it is unresolved rather than dismissed or attributed to the scanner's download domain.

## Privacy caveat

The legacy database upload's anonymous option sends the username with a backend anonymization flag; it does not remove the username on the client. Database lookup also fetches a public CSV during SeedCracker initialization. These behaviors should be evaluated separately from token theft or binary downloads.

Source anchors:

- `src/main/java/kaptainwutax/seedcrackerX/util/Database.java`: authentication, POST payload and CSV lookup.
- `src/main/java/kaptainwutax/seedcrackerX/cracker/storage/TimeMachine.java`: submission opt-in gate.
- `src/main/java/kaptainwutax/seedcrackerX/config/Config.java`: default submissions disabled.
- `build.gradle.kts`: explicit SQLite and JOCL jar-in-jar dependencies.

The next useful scanner evidence is its exact matched classes/methods or regex patterns. Removing unused functionality or publishing an explicitly documented native-free variant can reduce actual capabilities; renaming or concealing code is not a security fix. No existing feature was silently removed in this review.