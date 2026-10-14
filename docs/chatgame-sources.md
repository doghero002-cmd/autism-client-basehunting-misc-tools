# Chat-Game Source Ledger

## Result

This catalogue pass adds 24 question/answer pairs, 35 selected Minecraft/plugin
word targets and 197 country-name targets. The bundled answer bank now has 316
unique normalized questions, including 66 public-plugin entries. The selected
primary word file has 70 entries. The existing 370,105-entry English source list
is unchanged; 369,652 of its entries qualify before primary-word deduplication.

Only sources with verified reuse terms were imported. Metadata and source-link
checks are not exhaustive reviews of every plugin or configuration. An HTTP 403
means the page could not be read here, not that the resource lacks a license.
No paid downloads, authentication bypasses or unofficial mirrors were used.

## Imported Sources

| Source | Immutable Commit | Imported Material | License |
| --- | --- | --- | --- |
| [DragonHunt3r/ChatGames](https://github.com/DragonHunt3r/ChatGames) | `e5ee5fc21b15e8f9a674288a4bef29c28cb5481f` | Previous import: 40 trivia entries; selected legacy material aliases | MIT |
| [RareHyperIonYT/ChatGames](https://github.com/RareHyperIonYT/ChatGames) | `ac8aa1e4fa01305e87c327a461cde9487bd505d5` | Previous import: two Minecraft free-text adaptations; ordinary scramble targets | MIT |
| [funnyboy-roks/ChatGames](https://github.com/funnyboy-roks/ChatGames) | `823f084c37ba94047b12fb4efa43b2ed4dba04fd` | 197 country targets; observed timed word-game formats | Apache-2.0 |
| [PlayerPicked/ChatGame](https://github.com/PlayerPicked/ChatGame) | `2edfe38e425556e7be265e3bdd0c15a81f58dea3` | Seven default mob riddles; observed framed announcement formats | MIT |
| [The-Epic/SimpleChatGames](https://github.com/The-Epic/SimpleChatGames) | `f45eca102b36daf3357dd27a7a9795ac261ef1b1` | 17 selected trivia entries; 35 selected word targets; prize-wrapper format | MIT |

PlayerPicked/ChatGame is the resolved destination of the supplied MonGen's Cave
source link. The-Epic/SimpleChatGames is EpicEbic's Hangar project.

Source paths and copyright/permission notices are packaged in
[public-plugin-NOTICES.txt](../src/main/resources/chatgames/public-plugin-NOTICES.txt).
The full Apache license is packaged in
[public-country-LICENSE.txt](../src/main/resources/chatgames/public-country-LICENSE.txt).
The unmodified country file's SHA256 is
`3af2520cfbc41709862d767403948a6496791293e2fdb60cf23e513bfe85e519`.

## Selection Rules

- Preserve an upstream accepted answer, selecting one valid answer when several
  are accepted. Server overrides can provide different expected spelling.
- Exclude obsolete mob/item/biome counts and unclear edition/version claims.
- Exclude SimpleChatGames' bat-leather, fixed-spawn-block and chest-from-chest
  claims, plus misleading block/tool questions. Its hunger question uses the
  accepted answer `steak`, not its incorrect `golden carrot` alternative.
- Riddles are plugin answer targets, not complete descriptions of mob behavior.
  Typographic apostrophes in the imported riddles are converted to ASCII.
- Country names are plugin word targets, not a geopolitical classification.
  They use the fallback tier so `China` cannot make Minecraft `chain` ambiguous.
- Phrases unscramble, but are not indexed for single-word fill-in matching.
- Unknown or ambiguous answers remain unanswered. No server's private answer
  key is recovered by inspecting a public default pack.

## Announcement Coverage

| Observed Format | Added Handling |
| --- | --- |
| funnyboy-roks: `You have 30 seconds to unscramble this word!` then `> mdnaiod` | Typed header and round-owned word decoration |
| funnyboy-roks: `You have 45 seconds to fill in the blanks!` then `> d_am_nd` | Typed fill header and decorated payload |
| PlayerPicked: pipe-framed SOLVE IT, REVERSE IT, FILL IN, COPY IT and WHO AM I | Header classification and one framing pipe removed from its own payload |
| SimpleChatGames: `Solve ... to win a prize!` | Known wrapper removed before math/trivia lookup |
| SimpleChatGames: `Unscramble ... to win a prize!` | Known wrapper converted to an explicit unscramble prompt |

Both combined and split messages are tested. The existing five-second pending
header association limit remains. Literal retype payloads retain their own
characters. A configured nonempty keyword remains a filter; these parser tests
do not prove live native-host event dispatch or server acceptance.

Not implemented: funnyboy-roks FizzBuzz; PlayerPicked random range, crafting GUI,
multi-turn hangman and ambiguous first-letter mob games; multiple-choice answer
letters. No statistical win-rate claim is made.

## Supplied GitHub Catalogue

| Link | Outcome |
| --- | --- |
| [DragonHunt3r/ChatGames](https://github.com/DragonHunt3r/ChatGames) | Existing licensed import retained |
| [funnyboy-roks/ChatGames](https://github.com/funnyboy-roks/ChatGames) | Country import and timed word formats added |
| [RareHyperIonYT/ChatGames](https://github.com/RareHyperIonYT/ChatGames) | Existing licensed import retained |
| [antbag-pm-pl/ChatGames](https://github.com/antbag-pm-pl/ChatGames) | PocketMine/Bedrock, GPL-3.0; its five default words are already covered, so no copied pack |
| [EAlbiero/minecraft-minigames-plugin](https://github.com/EAlbiero/minecraft-minigames-plugin) | Describes world minigames launched by commands; no root license shown, no data imported |
| [UwUAroze/ChatMinigames](https://github.com/UwUAroze/ChatMinigames) | No root license shown; no data imported |
| [Anbuleone/chatgames](https://github.com/Anbuleone/chatgames) | Advertises math/scramble/reverse/trivia; no root license shown, no data imported |

## SpigotMC Catalogue

Official listing metadata was checked once. Listings without a reusable source
link were not treated as permission to copy a configuration pack.

| Resource | Outcome |
| --- | --- |
| [ChatGames 102505](https://www.spigotmc.org/resources/%E2%9C%A8-chatgames-%E2%9C%A8-multiple-game-types-fully-customisable-hiscores-fully-translatable.102505/) | Reachable; no public GitHub pack link found in the listing check |
| [Lite ChatGames 105831](https://www.spigotmc.org/resources/lite-chatgames-1-8-1-21.105831/) | Reachable; no public GitHub pack link found |
| [Simple Chat Games 64888](https://www.spigotmc.org/resources/simple-chat-games.64888/) | DragonHunt3r duplicate source |
| [ChatGames 107010](https://www.spigotmc.org/resources/chatgames.107010/) | HTTP 403; no import |
| [ChatGames 99274](https://www.spigotmc.org/resources/chatgames.99274/) | Links Waterbroodje/ChatGames; no root license shown, no import |
| [ChatGames 103134](https://www.spigotmc.org/resources/chatgames.103134/) | Reachable; no public GitHub pack link found |
| [ChatGames 86734](https://www.spigotmc.org/resources/chatgames-1-8-1-16.86734/) | Reachable; no public GitHub pack link found |
| [SimpleChatGames 108655](https://www.spigotmc.org/resources/simplechatgames.108655/) | The-Epic source; selected data imported |
| [Chat Minigames 106650](https://www.spigotmc.org/resources/chat-minigames.106650/) | UwUAroze duplicate source; no import |
| [Chat MiniGames 73169](https://www.spigotmc.org/resources/chat-minigames.73169/) | Reachable; no public GitHub pack link found |
| [pChatGames 126614](https://www.spigotmc.org/resources/pchatgames.126614/) | Reachable; no public GitHub pack link found |
| [Chat Games 127146](https://www.spigotmc.org/resources/chat-games-interactive-chat-game-system.127146/) | MonGen's Cave link resolves to PlayerPicked; selected data imported |
| [Chat Games 13703](https://www.spigotmc.org/resources/chat-games-mini-game-rewards-configurable.13703/) | Reachable; no public GitHub pack link found |

## Modrinth Catalogue

The public project API was used rather than assuming the supplied URL category
identified a distinct project. Licenses below are API metadata unless separately
verified against an imported source.

| Project | License Metadata | Outcome |
| --- | --- | --- |
| [chat-games](https://modrinth.com/plugin/chat-games) | MIT | RareHyperIonYT duplicate source |
| [chatgames](https://modrinth.com/plugin/chatgames) | Apache-2.0 | funnyboy-roks duplicate source |
| [chatgame](https://modrinth.com/plugin/chatgame) | MIT | MonGen's Cave/PlayerPicked source; selected data imported |
| [nl_chatgames](https://modrinth.com/plugin/nl_chatgames) | All Rights Reserved | No public source URL; no import |
| [dg-chatgames](https://modrinth.com/plugin/dg-chatgames) | MIT | No public source URL in API; no pack imported |
| [chatreactions](https://modrinth.com/plugin/chatreactions) | MIT | No public source URL in API; no pack imported |
| [cmb-minigames](https://modrinth.com/plugin/cmb-minigames) | CC-BY-NC-4.0 | Source tree checked; no selected answer pack, no import under noncommercial terms |
| [minigames-with-friends](https://modrinth.com/plugin/minigames-with-friends) | GPL-3.0-only | Source tree checked; no selected chat-game answer pack, no import |
| [chat_minigames](https://modrinth.com/mod/chat_minigames) | MIT | No public source URL in API; no pack imported |
| [matchboxplugin](https://modrinth.com/plugin/matchboxplugin) | MIT | Linked OhACD/MatchboxPlugin has no root license detected; no verified answer pack imported |

## Other Official Listings

| Resource | Outcome |
| --- | --- |
| [Hangar SimpleChatGames](https://hangar.papermc.io/EpicEbic/SimpleChatGames) | MIT; same The-Epic source, not a second dataset |
| [Polymart ChatGames 3012](https://polymart.org/resource/chatgames.3012) | Reachable, redirects to voxel.shop; no public pack source found |
| [Polymart Kix's Chat Games 1085](https://polymart.org/resource/kix-x27-s-chat-games.1085) | Reachable, redirects to voxel.shop; no public pack source found |
| [Polymart FunGames 3549](https://polymart.org/resource/fungames.3549) | Reachable, redirects to voxel.shop; TAB dependency link is not a word/answer source |
| [Polymart configuration 6351](https://polymart.org/resource/chatgames-plugin-configuration.6351) | Reachable, redirects to voxel.shop; no configuration pack copied |
| [BuiltByBit AxChatGames](https://builtbybit.com/resources/axchatgames-ultimate-chat-game-plugin.74309/) | HTTP 403; no import |
| [BuiltByBit NexusGames](https://builtbybit.com/resources/nexusgames-advanced-chat-games.90213/) | HTTP 403; no import |
| [BuiltByBit ChatGamesAdvanced](https://builtbybit.com/resources/chatgamesadvanced.69240/) | HTTP 403; no import |
| [BuiltByBit MpChatGames](https://builtbybit.com/resources/mpchatgames.90427/) | HTTP 403; no import |
| [BuiltByBit EmperorChatGames](https://builtbybit.com/resources/emperorchatgames.97632/) | HTTP 403; no import |
| [BuiltByBit chat-games tag](https://builtbybit.com/tags/chat-games/) | HTTP 403; Bishyy, LokiChatGames, VxChatGames, ChatGames Reborn, EpicGames and GuessTheNumber entries unresolved |
| [BuiltByBit chatgames tag](https://builtbybit.com/tags/chatgames/) | HTTP 403; AtyxChatGames, FoChatGames, cChatGames, ZenithChatGames, pikz ChatGames and ChatGames Lite entries unresolved |
| [BuiltByBit chatgame tag](https://builtbybit.com/tags/chatgame/) | HTTP 403; ProChatGames, ChatGames Revamped and ChatGamesAdvanced entries unresolved |
| [BuiltByBit chat-game tag](https://builtbybit.com/tags/chat-game/) | HTTP 403; Cubenix configuration unresolved |
| [CurseForge Chat Games](https://www.curseforge.com/minecraft/mc-mods/chat-games) | HTTP 403; no import |

The supplied SpigotUnlocked Kix's mirror was deliberately not requested. The
official Kix's listing above was checked instead.

## Verification

The focused `ChatGameSolverTest` gate has 52 passing tests with no failures,
errors or skips. Coverage includes every bundled question, both new announcement
families, prize wrappers, source resource counts/notices, country phrase aliases,
Minecraft-first priority, incorrect-fact exclusions, round expiry and literal
retype preservation. Tests use fixtures, not a live plugin server.

These changes are addon-only. Native-host initialization/event dispatch remains
unverified; compiling and passing parser tests do not prove the running client
has sent or won a round with this build. No model has been trained.

## Minecraft Registry Coverage

The collector now uses six built-in registries: items, blocks, entity types,
effects, potions and villager professions. It also gathers vanilla structure
keys before joining a server, and world registry IDs for biomes, dimension
types, enchantments, structures, trim patterns/materials, banner patterns,
paintings, instruments and wolf/cat/frog/pig/cow/chicken/zombie-nautilus variants.
Available enchantment/trim/instrument descriptions and painting titles are added.

The actual Minecraft 26.2 bootstrap/vanilla-lookup regression passed: 3,764 raw
built-in names and 2,426 indexed names after world additions and normalization.
These numbers exclude custom/plugin/trivia/English fallback additions. Every key
in the six built-in registries is checked against the collected set. Tests also
exercise structure names before world lookup and ten representative registry
targets, including Sculk Shrieker, Long Swiftness, Sentry Armor Trim, Rusty Wolf,
Warm Cow and Wind Burst. Item translation names are collected without creating
an ItemStack before 26.2's item components are bound.

This is not a guarantee for every custom server name or historical alias. The
existing character/length filters and ambiguous-anagram abstention still apply.

## Hugging Face Dataset Assessment

[minecraft-question-answer-700k](https://huggingface.co/datasets/asdfsfdgasfdasdf/minecraft-question-answer-700k)
was inspected, not imported. Its card describes 694,814 synthetic question/answer
rows generated from Minecraft Wiki pages, with `question`, `answer` and `source`
fields and CC-BY-NC-SA 3.0 licensing.

The bounded sample contains 140 rows: indices 0-99, 100000-100009,
300000-300009, 500000-500009 and 694804-694813. It spans 23 distinct source URLs;
mean answer length is 231.3 characters. Thirteen answers fit a preliminary
64-character/six-word filter, but that is not a correctness filter or an estimate
for the entire dataset. The initial larger-page request failed with HTTP 502;
the smaller pages subsequently succeeded.

Observed problems include literal question/answer placeholders, source-code
concatenation fragments, unrelated capital-of-France questions and fabricated
mechanics. Rows 2-4 incorrectly describe edible wheat, inedible carrots/potatoes,
and sugar changing bread's flavour. The linked [Wheat](https://minecraft.wiki/w/Wheat),
[Carrot](https://minecraft.wiki/w/Carrot) and [Bread](https://minecraft.wiki/w/Bread)
pages contradict these claims. Row 100009 associates axolotl clay pools with
basalt deltas, another unsuitable candidate.

Potential value: topic discovery and candidate question phrasing, such as hunger
saturation, cake consumption and structure loot. Each selected fact would need
independent edition/version verification, a short unambiguous answer and proper
provenance. Copied/adapted dataset material must retain its attribution,
noncommercial and share-alike obligations; it is not an MIT answer pack. No rows
were added to the bundled key and no training was performed.

## RareHyperIon Click Feasibility

The inspected `ac8aa1e4fa01305e87c327a461cde9487bd505d5` implementation uses ordinary
chat click events, not a custom client protocol:
[ReactionGame](https://github.com/RareHyperIonYT/ChatGames/blob/ac8aa1e4fa01305e87c327a461cde9487bd505d5/common/src/main/java/dev/rarehyperion/chatgames/game/types/ReactionGame.java)
attaches `/chatgames answer <UUID>` to reaction buttons. The server validates the
current reaction round's token. Decoy buttons carry different opaque UUIDs;
client-visible tokens alone do not identify the winning button.

Support is feasible by retaining Component click metadata, selecting a single
button or the explicitly named visible target, and sending only the recognized
answer command. It should be opt-in, round/connection-owned, cancelled on round
replacement/end, and reject unrelated commands, URL/file actions and ambiguous
choices. The inspected default MultipleChoiceGame instead expects a typed
letter; that is a separate answer-selection feature. Click support is a proposal,
not implemented or live-tested by this source review.
