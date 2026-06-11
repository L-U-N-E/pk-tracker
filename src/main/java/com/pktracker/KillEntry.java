package com.pktracker;

import java.util.List;

/**
 * A single PK kill record. Plain fields so Gson can serialize it
 * into config for persistent loot history.
 */
public class KillEntry
{
	public String victimName;
	public int combatLevel;
	public int world;
	public long timestamp; // epoch millis
	public long totalValue;
	public List<LootItem> loot;

	public KillEntry()
	{
	}

	public KillEntry(String victimName, int combatLevel, int world, long timestamp, long totalValue, List<LootItem> loot)
	{
		this.victimName = victimName;
		this.combatLevel = combatLevel;
		this.world = world;
		this.timestamp = timestamp;
		this.totalValue = totalValue;
		this.loot = loot;
	}

	public static class LootItem
	{
		public int itemId;
		public int quantity;
		public long totalPrice; // price * quantity at time of kill
		public String name;     // captured at kill time so the panel never needs the client thread

		public LootItem()
		{
		}

		public LootItem(int itemId, int quantity, long totalPrice, String name)
		{
			this.itemId = itemId;
			this.quantity = quantity;
			this.totalPrice = totalPrice;
			this.name = name;
		}
	}
}
