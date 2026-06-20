package com.pktracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(PkTrackerPlugin.CONFIG_GROUP)
public interface PkTrackerConfig extends Config
{
	@ConfigItem(
		keyName = "lookupOnFightStart",
		name = "Lookup stats on fight start",
		description = "Automatically look up an opponent's hiscore stats when a fight begins",
		position = 0
	)
	default boolean lookupOnFightStart()
	{
		return true;
	}

	@ConfigItem(
		keyName = "statsChatMessage",
		name = "Opponent stats in chat",
		description = "Also print the opponent's combat stats to the chatbox",
		position = 1
	)
	default boolean statsChatMessage()
	{
		return false;
	}

	@ConfigItem(
		keyName = "killChatMessage",
		name = "Kill message in chat",
		description = "Print a message with the loot value when you get a kill",
		position = 2
	)
	default boolean killChatMessage()
	{
		return true;
	}

	@ConfigItem(
		keyName = "pvpDeathsOnly",
		name = "Only count PvP deaths",
		description = "Only count deaths that happen while in combat with another player (ignores PvM deaths)",
		position = 3
	)
	default boolean pvpDeathsOnly()
	{
		return true;
	}

	@Range(min = 10, max = 1000)
	@ConfigItem(
		keyName = "maxHistoryEntries",
		name = "Max history entries",
		description = "Maximum number of kills kept in the persistent loot history",
		position = 4
	)
	default int maxHistoryEntries()
	{
		return 250;
	}

	@ConfigItem(
		keyName = "lookupCooldown",
		name = "Lookup cooldown (seconds)",
		description = "How long before the same opponent will be looked up again",
		position = 5
	)
	default int lookupCooldown()
	{
		return 120;
	}
	@ConfigItem(
		keyName = "rightClickLookup",
		name = "Right-click PK Lookup",
		description = "Adds a 'PK Lookup' option when you right-click a player",
		position = 6
	)
	default boolean rightClickLookup()
	{
		return true;
	}

	@ConfigItem(
		keyName = "damageOverlay",
		name = "Damage overlay",
		description = "Show an on-screen overlay of damage dealt to and taken from your opponent during a fight",
		position = 7
	)
	default boolean damageOverlay()
	{
		return false;
	}

	@Range(min = 5, max = 120)
	@ConfigItem(
		keyName = "damageResetDelay",
		name = "Fight reset delay (s)",
		description = "Seconds of no combat before the damage overlay resets (also clears instantly on entering a safe zone or starting a new fight)",
		position = 8
	)
	default int damageResetDelay()
	{
		return 30;
	}

	@ConfigItem(
		keyName = "sessionOverlay",
		name = "Session stats overlay",
		description = "Show an on-screen overlay with your session kills, deaths, K/D, streak and loot",
		position = 9
	)
	default boolean sessionOverlay()
	{
		return false;
	}

	@ConfigItem(
		keyName = "opponentOverlay",
		name = "Opponent overlay",
		description = "Show an on-screen overlay with the current opponent's stats, BH/LMS ranks, saved note and damage during a fight",
		position = 10
	)
	default boolean opponentOverlay()
	{
		return false;
	}

	@ConfigItem(
		keyName = "killScreenshot",
		name = "Screenshot on kill/death",
		description = "Automatically screenshot the moment you kill an opponent or die, capturing the final hitsplats (game view only, saved to the PK Tracker folder)",
		position = 11
	)
	default boolean killScreenshot()
	{
		return false;
	}
}
