package com.pktracker;

/**
 * Holds the result of a hiscore lookup for the current fight opponent.
 */
public class OpponentStats
{
	public final String name;
	public final int attack;
	public final int strength;
	public final int defence;
	public final int hitpoints;
	public final int ranged;
	public final int magic;
	public final int prayer;
	public final int combatLevel;

	// Minigame scores (-1 = unranked)
	public final int bhHunter;
	public final int bhRogue;
	public final int lms;

	public OpponentStats(String name, int attack, int strength, int defence,
		int hitpoints, int ranged, int magic, int prayer, int combatLevel,
		int bhHunter, int bhRogue, int lms)
	{
		this.name = name;
		this.attack = attack;
		this.strength = strength;
		this.defence = defence;
		this.hitpoints = hitpoints;
		this.ranged = ranged;
		this.magic = magic;
		this.prayer = prayer;
		this.combatLevel = combatLevel;
		this.bhHunter = bhHunter;
		this.bhRogue = bhRogue;
		this.lms = lms;
	}
}
