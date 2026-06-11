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
}
