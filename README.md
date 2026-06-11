# PK Tracker

A RuneLite side panel plugin for tracking your PKing performance in Old School RuneScape.

Made by **LUNE**.

## Features

- **Session K/D** — kills, deaths, K/D ratio, and current kill streak
- **Kill detection that works everywhere** — kills are credited from the
  "You have defeated ..." game message, so they count in the Wilderness,
  on PvP worlds, and in Bounty Hunter
- **Loot tracking with item icons** — player loot piles, bounty crates,
  and loot chests/keys are recorded with full item grids and GE values
  (coins and platinum tokens counted at face value)
- **Persistent kill history** — loot history is saved to your RuneLite
  config and survives client restarts (cap configurable)
- **Automatic opponent stat lookup** — when a fight starts or a Bounty
  Hunter target is assigned, the opponent's combat stats are looked up
  and shown hiscore-style with skill icons, plus their BH Hunter,
  BH Rogue, and LMS ranks
- **Manual lookup** — type any name into the panel to scout their stats

## Notes

- Bounty crate / loot chest contents are captured when the container is
  opened, and require the built-in Loot Tracker plugin to be enabled
  (it is by default).
- Stat lookups use the official hiscores: unranked players will show a
  lookup failure, and levels shown are base levels, not boosted levels.
- Deaths only count while recently in combat with another player
  (configurable), so PvM deaths don't touch your K/D.

## Configuration

| Option | Default | Description |
| --- | --- | --- |
| Lookup stats on fight start | on | Auto hiscore lookup when a PvP fight begins or a BH target is assigned |
| Opponent stats in chat | off | Also print looked-up stats in the chatbox |
| Kill message in chat | on | Chat message with loot value when loot is received |
| Only count PvP deaths | on | Ignore deaths with no recent player combat |
| Max history entries | 250 | Cap on stored kill history |
| Lookup cooldown | 120s | Min time before re-looking-up the same opponent |

## Development

Built from the [example-plugin](https://github.com/runelite/example-plugin)
template. Open the folder as a Gradle project, then run
`PkTrackerPluginTest` (or the `runClient` Gradle task) to launch a
development client with the plugin loaded.
