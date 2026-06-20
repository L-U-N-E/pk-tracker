# PK Tracker

A RuneLite plugin for tracking your PKing performance in Old School RuneScape — with a side panel, optional on-screen overlays, per-player notes, and automatic kill/death screenshots.

Made by LUNE.

## Features

### Tracking
- **Session K/D** — kills, deaths, K/D ratio, and current kill streak
- **Kill detection that works everywhere** — kills are credited from the "You have defeated ..." game message, so they count in the Wilderness, on PvP worlds, and in Bounty Hunter
- **Loot tracking with item icons** — player loot piles, bounty crates, and loot chests/keys are recorded with full item grids and GE values (coins and platinum tokens counted at face value)
- **Persistent kill history** — loot history is saved to your RuneLite config and survives client restarts (cap configurable)

### Opponent info
- **Automatic stat lookup** — when a fight starts or a Bounty Hunter target is assigned, the opponent's combat stats are looked up and shown hiscore-style with skill icons, plus their BH Hunter, BH Rogue, and LMS ranks
- **Manual lookup** — type any name into the panel to scout their stats
- **Right-click PK Lookup** — right-click any player to look them up instantly; the option is grouped per-player and stays clear of "Walk here" so it doesn't disrupt PKing
- **Per-player notes** — save a note on any player (e.g. to flag a known rat); the note resurfaces with a warning highlight whenever you face or look them up again

### On-screen overlays (all optional, off by default)
- **Session stats overlay** — kills, deaths, K/D, streak, and loot
- **Opponent overlay** — the opponent's skill grid, BH/LMS ranks, and saved note; in Bounty Hunter it appears at target assignment so you can scout while running to them
- **Damage overlay** — live damage you've dealt vs. taken in the current fight, resetting after the fight ends (delay configurable, clears on entering a safe zone)

### Screenshots
- **Kill / death screenshots** — automatically captures the moment you kill an opponent or die, timed to catch the final hitsplats; saved to a PK Tracker folder, with a button in the panel to open it

## Notes
- Bounty crate / loot chest contents are captured when the container is opened, and require the built-in Loot Tracker plugin to be enabled (it is by default).
- Stat lookups use the official hiscores: unranked players will show a lookup failure, and levels shown are base levels, not boosted levels.
- Deaths only count while recently in combat with another player (configurable), so PvM deaths don't touch your K/D.
- Per-player loot totals are intentionally not provided: loot keys and chests are anonymous and stackable, so loot cannot be reliably attributed to a specific victim.

## Configuration

| Option | Default | Description |
| --- | --- | --- |
| Lookup stats on fight start | on | Auto hiscore lookup when a PvP fight begins or a BH target is assigned |
| Opponent stats in chat | off | Also print looked-up stats in the chatbox |
| Kill message in chat | on | Chat message with loot value when loot is received |
| Only count PvP deaths | on | Ignore deaths with no recent player combat |
| Max history entries | 250 | Cap on stored kill history |
| Lookup cooldown | 120s | Min time before re-looking-up the same opponent |
| Right-click PK Lookup | on | Adds a "PK Lookup" option when right-clicking a player |
| Damage overlay | off | On-screen overlay of damage dealt and taken during a fight |
| Fight reset delay | 30s | Seconds of no combat before the damage overlay resets |
| Session stats overlay | off | On-screen overlay of session kills, deaths, K/D, streak, and loot |
| Opponent overlay | off | On-screen overlay of the opponent's stats, ranks, and note |
| Screenshot on kill/death | off | Auto-screenshot the moment you kill or die, capturing the final hitsplats |

## Development
Built from the example-plugin template. Open the folder as a Gradle project, then run `PkTrackerPluginTest` (or the `runClient` Gradle task) to launch a development client with the plugin loaded.
