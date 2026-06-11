package com.pktracker;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.awt.image.BufferedImage;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.InteractingChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.hiscore.HiscoreClient;
import net.runelite.client.hiscore.HiscoreEndpoint;
import net.runelite.client.hiscore.HiscoreResult;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "PK Tracker",
	description = "Tracks PK kills, deaths, K/D ratio, loot history, session profit, and looks up opponent stats on fight start",
	tags = {"pvp", "pk", "kill", "loot", "wilderness", "bounty"}
)
public class PkTrackerPlugin extends Plugin
{
	static final String CONFIG_GROUP = "pktracker";
	private static final String HISTORY_KEY = "killHistory";

	// How many ticks after last player combat a death still counts as a PvP death (~30s)
	private static final int PVP_COMBAT_TIMEOUT_TICKS = 50;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private PkTrackerConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private Gson gson;

	@Inject
	private HiscoreClient hiscoreClient;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private net.runelite.client.game.SpriteManager spriteManager;

	private PkTrackerPanel panel;
	private NavigationButton navButton;

	// --- Session state ---
	@Getter
	private int sessionKills;
	@Getter
	private int sessionDeaths;
	@Getter
	private long sessionLootValue;
	@Getter
	private int killStreak;
	@Getter
	private final List<KillEntry> sessionKillList = new ArrayList<>();

	// --- Persistent state ---
	@Getter
	private List<KillEntry> killHistory = new ArrayList<>();

	// --- Combat tracking ---
	private int lastPlayerCombatTick = -1;
	private final Map<String, Instant> recentLookups = new HashMap<>();
	private final Map<String, Instant> recentKillCredits = new HashMap<>();

	@Override
	protected void startUp()
	{
		loadHistory();

		panel = new PkTrackerPanel(this, spriteManager, itemManager);

		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/pktracker_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("PK Tracker")
			.icon(icon)
			.priority(5)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		SwingUtilities.invokeLater(panel::refresh);
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		saveHistory();
		panel = null;
		navButton = null;
	}

	// ------------------------------------------------------------------
	// Item valuation
	// ------------------------------------------------------------------

	private static final int COINS_ID = 995;
	private static final int PLATINUM_TOKEN_ID = 13204;

	/**
	 * GE price per single unit of an item. Coins and platinum tokens have
	 * no GE price, so they're valued at face value (1 and 1000 gp).
	 */
	private long unitValue(int itemId)
	{
		if (itemId == COINS_ID)
		{
			return 1;
		}
		if (itemId == PLATINUM_TOKEN_ID)
		{
			return 1000;
		}
		return itemManager.getItemPrice(itemId);
	}

	// ------------------------------------------------------------------
	// Kills & loot
	// ------------------------------------------------------------------

	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived event)
	{
		final Player victim = event.getPlayer();
		final String name = Text.sanitize(victim.getName());

		long totalValue = 0;
		List<KillEntry.LootItem> loot = new ArrayList<>();
		for (ItemStack stack : event.getItems())
		{
			long price = unitValue(stack.getId()) * stack.getQuantity();
			String itemName = itemManager.getItemComposition(stack.getId()).getName();
			totalValue += price;
			loot.add(new KillEntry.LootItem(stack.getId(), stack.getQuantity(), price, itemName));
		}
		loot.sort(Comparator.comparingLong((KillEntry.LootItem i) -> i.totalPrice).reversed());

		// Credits the kill unless the chat message already did
		KillEntry entry = creditKill(name, victim.getCombatLevel());
		entry.loot.addAll(loot);
		entry.totalValue += totalValue;
		sessionLootValue += totalValue;

		saveHistory();
		announceLoot(name, victim.getCombatLevel(), totalValue);
		SwingUtilities.invokeLater(panel::refresh);
	}

	/**
	 * Bounty crates (Bounty Hunter) and loot chests/keys (PvP worlds) don't
	 * produce a player loot pile — their contents arrive via LootReceived,
	 * posted by the Loot Tracker plugin when the container is opened.
	 */
	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		if (event.getType() != LootRecordType.EVENT)
		{
			return;
		}

		String name = event.getName() == null ? "" : event.getName();
		String lower = name.toLowerCase();
		if (!lower.contains("bounty crate") && !lower.contains("loot chest") && !lower.contains("loot key"))
		{
			return;
		}

		long totalValue = 0;
		List<KillEntry.LootItem> loot = new ArrayList<>();
		for (ItemStack stack : event.getItems())
		{
			long price = unitValue(stack.getId()) * stack.getQuantity();
			String itemName = itemManager.getItemComposition(stack.getId()).getName();
			totalValue += price;
			loot.add(new KillEntry.LootItem(stack.getId(), stack.getQuantity(), price, itemName));
		}
		loot.sort(Comparator.comparingLong((KillEntry.LootItem i) -> i.totalPrice).reversed());

		// Not a kill itself — kills are credited by the defeat chat message
		KillEntry entry = new KillEntry(
			name,
			-1,
			client.getWorld(),
			Instant.now().toEpochMilli(),
			totalValue,
			loot
		);
		sessionLootValue += totalValue;
		sessionKillList.add(0, entry);
		killHistory.add(0, entry);
		trimHistory();
		saveHistory();

		announceLoot(name, -1, totalValue);
		SwingUtilities.invokeLater(panel::refresh);
	}

	/**
	 * Counts a kill for the given player, unless one was already credited
	 * for them in the last few seconds (chat message and loot pile can both
	 * fire for the same kill). Returns the entry to attach loot to.
	 */
	private KillEntry creditKill(String name, int combatLevel)
	{
		final String key = name.toLowerCase();
		Instant last = recentKillCredits.get(key);
		if (last != null && Instant.now().isBefore(last.plusSeconds(15)))
		{
			KillEntry existing = findRecentEntry(name);
			if (existing != null)
			{
				if (combatLevel > 0)
				{
					existing.combatLevel = combatLevel;
				}
				return existing;
			}
		}

		recentKillCredits.put(key, Instant.now());
		sessionKills++;
		killStreak++;

		KillEntry entry = new KillEntry(
			name,
			combatLevel,
			client.getWorld(),
			Instant.now().toEpochMilli(),
			0,
			new ArrayList<>()
		);
		sessionKillList.add(0, entry);
		killHistory.add(0, entry);
		trimHistory();
		saveHistory();
		SwingUtilities.invokeLater(panel::refresh);
		return entry;
	}

	private KillEntry findRecentEntry(String name)
	{
		long cutoff = Instant.now().minusSeconds(60).toEpochMilli();
		for (KillEntry e : sessionKillList)
		{
			if (e.timestamp >= cutoff && name.equalsIgnoreCase(e.victimName))
			{
				return e;
			}
		}
		return null;
	}

	private void announceLoot(String name, int combatLevel, long totalValue)
	{
		if (!config.killChatMessage() || totalValue <= 0)
		{
			return;
		}
		String message = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append("PK Tracker: ")
			.append(ChatColorType.NORMAL)
			.append(name + (combatLevel > 0 ? " (lvl " + combatLevel + ")" : "") + " — ")
			.append(ChatColorType.HIGHLIGHT)
			.append(QuantityFormatter.quantityToStackSize(totalValue) + " gp")
			.append(ChatColorType.NORMAL)
			.append(".")
			.build();
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(message)
			.build());
	}

	// ------------------------------------------------------------------
	// Deaths
	// ------------------------------------------------------------------

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (event.getActor() != client.getLocalPlayer())
		{
			return;
		}

		boolean recentlyInPvp = lastPlayerCombatTick >= 0
			&& client.getTickCount() - lastPlayerCombatTick <= PVP_COMBAT_TIMEOUT_TICKS;

		if (config.pvpDeathsOnly() && !recentlyInPvp)
		{
			return;
		}

		sessionDeaths++;
		killStreak = 0;

		SwingUtilities.invokeLater(panel::refresh);
	}

	// ------------------------------------------------------------------
	// Fight detection & stat lookup
	// ------------------------------------------------------------------

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		final Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}

		final Actor source = event.getSource();
		final Actor target = event.getTarget();

		Player opponent = null;
		if (source == local && target instanceof Player)
		{
			opponent = (Player) target;
		}
		else if (target == local && source instanceof Player)
		{
			opponent = (Player) source;
		}

		if (opponent == null || opponent == local)
		{
			return;
		}

		lastPlayerCombatTick = client.getTickCount();

		if (!config.lookupOnFightStart())
		{
			return;
		}

		final String rawName = opponent.getName();
		if (rawName == null)
		{
			return;
		}

		// In-game names use non-breaking spaces; the hiscore API needs real spaces
		final String name = Text.sanitize(rawName);
		if (name.isEmpty())
		{
			return;
		}

		Instant last = recentLookups.get(name);
		if (last != null && Instant.now().isBefore(last.plusSeconds(config.lookupCooldown())))
		{
			return;
		}
		recentLookups.put(name, Instant.now());

		lookupOpponent(name);
	}

	// ------------------------------------------------------------------
	// Bounty Hunter target detection (chat based)
	// ------------------------------------------------------------------

	private static final java.util.List<java.util.regex.Pattern> TARGET_PATTERNS = java.util.Arrays.asList(
		// Actual message: "You have been assigned a new target: <name>"
		java.util.regex.Pattern.compile("(?i)you have been assigned a new target:?\\s*(.+?)[.!]?$"),
		java.util.regex.Pattern.compile("(?i)you(?:'|’)?ve been assigned a (?:new )?target:?\\s*(.+?)[.!]?$"),
		java.util.regex.Pattern.compile("(?i)your target is(?: now)?:?\\s*(.+?)[.!]?$"),
		java.util.regex.Pattern.compile("(?i)you(?:'|’)?ve been paired with:?\\s*(.+?)[.!]?$")
	);

	// Fired on PvP kills in the wilderness, BH and PvP worlds
	private static final java.util.regex.Pattern KILL_PATTERN =
		java.util.regex.Pattern.compile("(?i)you have defeated (.+?)[.!]?$");

	@Subscribe
	public void onChatMessage(net.runelite.api.events.ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}

		String message = Text.removeTags(event.getMessage());

		// --- Kill detection (always on) ---
		java.util.regex.Matcher killMatcher = KILL_PATTERN.matcher(message);
		if (killMatcher.find())
		{
			String victimName = Text.sanitize(killMatcher.group(1)).trim();
			if (!victimName.isEmpty())
			{
				log.debug("Kill credited from chat: {}", victimName);
				creditKill(victimName, -1);
			}
			return;
		}

		// --- Bounty Hunter target assignment -> stat lookup ---
		if (!config.lookupOnFightStart())
		{
			return;
		}

		for (java.util.regex.Pattern pattern : TARGET_PATTERNS)
		{
			java.util.regex.Matcher m = pattern.matcher(message);
			if (m.find())
			{
				String targetName = Text.sanitize(m.group(1)).trim();
				if (!targetName.isEmpty())
				{
					log.debug("Detected BH target from chat: {}", targetName);
					lookupPlayer(targetName);
				}
				return;
			}
		}
	}

	/**
	 * Public lookup that bypasses the fight-start cooldown — used for
	 * Bounty Hunter target assignment and the panel's manual lookup box.
	 */
	public void lookupPlayer(String name)
	{
		String sanitized = Text.sanitize(name).trim();
		if (sanitized.isEmpty())
		{
			return;
		}
		recentLookups.put(sanitized, Instant.now());
		lookupOpponent(sanitized);
	}

	private void lookupOpponent(final String name)
	{
		SwingUtilities.invokeLater(() -> panel.setOpponentLoading(name));

		hiscoreClient.lookupAsync(name, HiscoreEndpoint.NORMAL)
			.whenCompleteAsync((result, ex) ->
			{
				try
				{
					if (ex != null || result == null)
					{
						log.warn("Hiscore lookup failed for '{}'", name, ex);
						SwingUtilities.invokeLater(() -> panel.setOpponentError(name));
						return;
					}

					OpponentStats stats = buildStats(name, result);
					SwingUtilities.invokeLater(() -> panel.setOpponent(stats));

					if (config.statsChatMessage())
					{
						String message = new ChatMessageBuilder()
							.append(ChatColorType.HIGHLIGHT)
							.append(name)
							.append(ChatColorType.NORMAL)
							.append(" (cb " + stats.combatLevel + "): ")
							.append("Atk " + stats.attack
								+ " / Str " + stats.strength
								+ " / Def " + stats.defence
								+ " / HP " + stats.hitpoints
								+ " / Rng " + stats.ranged
								+ " / Mag " + stats.magic
								+ " / Pray " + stats.prayer)
							.build();
						chatMessageManager.queue(QueuedMessage.builder()
							.type(ChatMessageType.CONSOLE)
							.runeLiteFormattedMessage(message)
							.build());
					}
				}
				catch (Exception e)
				{
					// Never let an exception leave the panel stuck on "Looking up…"
					log.warn("Error processing hiscore result for '{}'", name, e);
					SwingUtilities.invokeLater(() -> panel.setOpponentError(name));
				}
			});
	}

	private static OpponentStats buildStats(String name, HiscoreResult result)
	{
		int att = skillLevel(result, HiscoreSkill.ATTACK, 1);
		int str = skillLevel(result, HiscoreSkill.STRENGTH, 1);
		int def = skillLevel(result, HiscoreSkill.DEFENCE, 1);
		int hp = skillLevel(result, HiscoreSkill.HITPOINTS, 10);
		int rng = skillLevel(result, HiscoreSkill.RANGED, 1);
		int mag = skillLevel(result, HiscoreSkill.MAGIC, 1);
		int pray = skillLevel(result, HiscoreSkill.PRAYER, 1);

		int combat = Experience.getCombatLevel(att, str, def, hp, mag, rng, pray);

		int bhHunter = minigameScore(result, HiscoreSkill.BOUNTY_HUNTER_HUNTER);
		int bhRogue = minigameScore(result, HiscoreSkill.BOUNTY_HUNTER_ROGUE);
		int lms = minigameScore(result, HiscoreSkill.LAST_MAN_STANDING);

		return new OpponentStats(name, att, str, def, hp, rng, mag, pray, combat,
			bhHunter, bhRogue, lms);
	}

	/**
	 * Null-safe minigame score: -1 means unranked.
	 */
	private static int minigameScore(HiscoreResult result, HiscoreSkill skill)
	{
		try
		{
			net.runelite.client.hiscore.Skill s = result.getSkill(skill);
			return s == null ? -1 : s.getLevel();
		}
		catch (Exception e)
		{
			return -1;
		}
	}

	/**
	 * Null-safe skill level: unranked skills come back as null or -1.
	 */
	private static int skillLevel(HiscoreResult result, HiscoreSkill skill, int min)
	{
		net.runelite.client.hiscore.Skill s = result.getSkill(skill);
		if (s == null)
		{
			return min;
		}
		return Math.max(min, s.getLevel());
	}

	// ------------------------------------------------------------------
	// Session / history management (called from the panel)
	// ------------------------------------------------------------------

	public void resetSession()
	{
		sessionKills = 0;
		sessionDeaths = 0;
		sessionLootValue = 0;
		killStreak = 0;
		sessionKillList.clear();
		SwingUtilities.invokeLater(panel::refresh);
	}

	public void clearHistory()
	{
		killHistory.clear();
		saveHistory();
		SwingUtilities.invokeLater(panel::refresh);
	}

	private void trimHistory()
	{
		int max = config.maxHistoryEntries();
		while (killHistory.size() > max)
		{
			killHistory.remove(killHistory.size() - 1);
		}
	}

	private void loadHistory()
	{
		String json = configManager.getConfiguration(CONFIG_GROUP, HISTORY_KEY);
		if (json == null || json.isEmpty())
		{
			killHistory = new ArrayList<>();
			return;
		}

		try
		{
			Type type = new TypeToken<List<KillEntry>>()
			{
			}.getType();
			List<KillEntry> loaded = gson.fromJson(json, type);
			killHistory = loaded != null ? loaded : new ArrayList<>();
		}
		catch (Exception e)
		{
			log.warn("Failed to load PK kill history", e);
			killHistory = new ArrayList<>();
		}
	}

	private void saveHistory()
	{
		try
		{
			configManager.setConfiguration(CONFIG_GROUP, HISTORY_KEY, gson.toJson(killHistory));
		}
		catch (Exception e)
		{
			log.warn("Failed to save PK kill history", e);
		}
	}

	@com.google.inject.Provides
	PkTrackerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PkTrackerConfig.class);
	}
}
