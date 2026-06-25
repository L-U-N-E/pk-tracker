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
import net.runelite.api.Hitsplat;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.MenuOpened;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.overlay.OverlayManager;
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
import net.runelite.client.hiscore.HiscoreManager;
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
	description = "Tracks PK kills, deaths, K/D and loot with persistent history, per-player notes, and opponent stat lookup on fight start",
	tags = {"pvp", "pk", "kill", "loot", "wilderness", "bounty", "notes"}
)
public class PkTrackerPlugin extends Plugin
{
	static final String CONFIG_GROUP = "pktracker";
	private static final String HISTORY_KEY = "killHistory";
	private static final String NOTES_KEY = "playerNotes";

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
	private HiscoreManager hiscoreManager;

	@Inject
	private java.util.concurrent.ScheduledExecutorService executor;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private net.runelite.client.game.SpriteManager spriteManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private net.runelite.client.ui.DrawManager drawManager;

	@Inject
	private net.runelite.client.util.ImageCapture imageCapture;

	private PkTrackerPanel panel;
	private NavigationButton navButton;
	private int menuIconIndex = -1;
	private PkDamageOverlay damageOverlay;
	private PkSessionOverlay sessionOverlay;
	private PkOpponentOverlay opponentOverlay;

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

	// Persistent per-player notes, keyed by lowercased sanitized name
	private Map<String, String> playerNotes = new HashMap<>();

	// --- Combat tracking ---
	private int lastPlayerCombatTick = -1;
	private final Map<String, Instant> recentLookups = new HashMap<>();
	private final Map<String, Instant> recentKillCredits = new HashMap<>();

	// --- Current fight tracking ---
	@Getter
	private String currentOpponentName;

	// --- Damage overlay tracking ---
	@Getter
	private int damageDealt;
	@Getter
	private int damageTaken;
	private net.runelite.api.Actor overlayOpponent;
	private String bhTargetName; // set on BH target assignment (may be far away)
	private int bhTargetTick = -1; // tick when the BH target was assigned
	private int lastCombatTick = -1;
	private int safeZoneTick = -1;

	// Last known local player name, captured on the client thread so the
	// screenshot-folder button (on the Swing thread) has a reliable value
	private volatile String lastKnownPlayerName;

	// Latest opponent stats, exposed for the optional opponent overlay
	@Getter
	private OpponentStats lastOpponentStats;

	@Override
	protected void startUp()
	{
		loadHistory();
		loadNotes();

		panel = new PkTrackerPanel(this, spriteManager, itemManager);

		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/pktracker_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("PK Tracker")
			.icon(icon)
			.priority(5)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		damageOverlay = new PkDamageOverlay(this, config);
		overlayManager.add(damageOverlay);
		sessionOverlay = new PkSessionOverlay(this, config);
		overlayManager.add(sessionOverlay);
		opponentOverlay = new PkOpponentOverlay(this, config, spriteManager);
		overlayManager.add(opponentOverlay);

		// Pull the in-game Slayer skull sprite once, then use it for both the
		// sidebar button and the right-click menu icon
		clientThread.invokeLater(this::loadIcons);

		SwingUtilities.invokeLater(panel::refresh);
	}

	private void loadIcons()
	{
		if (client.getModIcons() == null)
		{
			return; // sprites not ready yet; retried on a later invoke
		}

		spriteManager.getSpriteAsync(net.runelite.api.SpriteID.SKILL_SLAYER, 0, sprite ->
		{
			if (sprite == null)
			{
				return;
			}
			clientThread.invokeLater(() ->
			{
				registerMenuIcon(sprite);
				swapNavButtonIcon(sprite);
			});
		});
	}

	private void registerMenuIcon(BufferedImage sprite)
	{
		try
		{
			// Resize then pad the canvas so the icon sits on the text baseline.
			// The menu renders the icon anchored high, so we offset it down and
			// size the canvas tall enough to avoid clipping the bottom.
			BufferedImage scaled = ImageUtil.resizeImage(sprite, 13, 13);
			BufferedImage img = new BufferedImage(13, 20, BufferedImage.TYPE_INT_ARGB);
			java.awt.Graphics g = img.getGraphics();
			g.drawImage(scaled, 0, 7, null); // offset down to drop the icon onto the baseline
			g.dispose();
			net.runelite.api.IndexedSprite indexed = ImageUtil.getImageIndexedSprite(img, client);

			final net.runelite.api.IndexedSprite[] current = client.getModIcons();
			final net.runelite.api.IndexedSprite[] newIcons =
				java.util.Arrays.copyOf(current, current.length + 1);
			menuIconIndex = newIcons.length - 1;
			newIcons[menuIconIndex] = indexed;
			client.setModIcons(newIcons);
		}
		catch (Exception e)
		{
			log.debug("Could not register PK Lookup menu icon", e);
			menuIconIndex = -1;
		}
	}

	private void swapNavButtonIcon(BufferedImage sprite)
	{
		try
		{
			BufferedImage navIcon = ImageUtil.resizeImage(sprite, 24, 24);
			NavigationButton newNav = NavigationButton.builder()
				.tooltip("PK Tracker")
				.icon(navIcon)
				.priority(5)
				.panel(panel)
				.build();
			SwingUtilities.invokeLater(() ->
			{
				clientToolbar.removeNavigation(navButton);
				navButton = newNav;
				clientToolbar.addNavigation(navButton);
			});
		}
		catch (Exception e)
		{
			log.debug("Could not swap nav button icon", e);
		}
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		if (damageOverlay != null)
		{
			overlayManager.remove(damageOverlay);
		}
		if (sessionOverlay != null)
		{
			overlayManager.remove(sessionOverlay);
		}
		if (opponentOverlay != null)
		{
			overlayManager.remove(opponentOverlay);
		}
		saveHistory();
		panel = null;
		navButton = null;
		damageOverlay = null;
		sessionOverlay = null;
		opponentOverlay = null;
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
		final Actor dead = event.getActor();

		// Opponent died — credit the kill and capture the screenshot.
		// ActorDeath fires reliably in every mode (wilderness, PvP worlds, BH),
		// unlike the kill chat message which varies or only broadcasts in some
		// situations (e.g. clan). We only credit a player opponent we were
		// actually fighting (overlayOpponent), so PvM deaths don't count.
		if (overlayOpponent != null && dead == overlayOpponent && dead instanceof Player)
		{
			final String victimName = Text.sanitize(((Player) dead).getName());

			if (config.killScreenshot())
			{
				captureScreenshot("Kill " + victimName);
			}

			if (!victimName.isEmpty())
			{
				log.debug("Kill credited from opponent death: {}", victimName);
				creditKill(victimName, dead.getCombatLevel());
			}
		}

		if (dead != client.getLocalPlayer())
		{
			return;
		}

		// Local player died — capture the death screenshot
		if (config.killScreenshot())
		{
			captureScreenshot("Death");
		}

		boolean recentlyInPvp = lastPlayerCombatTick >= 0
			&& client.getTickCount() - lastPlayerCombatTick <= PVP_COMBAT_TIMEOUT_TICKS;

		if (config.pvpDeathsOnly() && !recentlyInPvp)
		{
			return;
		}

		sessionDeaths++;
		killStreak = 0;

		currentOpponentName = null;
		SwingUtilities.invokeLater(panel::refresh);
	}

	/**
	 * Captures the next rendered game frame and saves it to the PK Tracker
	 * screenshot subfolder. Grabs the next frame so the killing hitsplats,
	 * which are usually still on screen this tick, are included.
	 */
	private void captureScreenshot(String fileName)
	{
		drawManager.requestNextFrameListener(image ->
		{
			try
			{
				java.awt.image.BufferedImage bufferedImage =
					new java.awt.image.BufferedImage(
						image.getWidth(null),
						image.getHeight(null),
						java.awt.image.BufferedImage.TYPE_INT_ARGB);
				java.awt.Graphics g = bufferedImage.getGraphics();
				g.drawImage(image, 0, 0, null);
				g.dispose();

				imageCapture.saveScreenshot(
					bufferedImage,
					fileName,
					"PK Tracker", // subfolder under .runelite/screenshots
					false,        // don't notify
					false);       // don't copy to clipboard
			}
			catch (Exception e)
			{
				log.debug("Failed to save PK screenshot", e);
			}
		});
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

		// Start tracking this fight (resets damage counters on a new opponent)
		setCurrentOpponent(opponent);

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

		// Respect the lookup cooldown only if we ALREADY have this opponent's
		// stats showing. If stats are missing (e.g. cleared during a long run to
		// a far BH target), bypass the cooldown so they re-fetch immediately on
		// engage — no name-only gap.
		boolean haveStatsForThem = lastOpponentStats != null
			&& lastOpponentStats.name != null
			&& lastOpponentStats.name.equalsIgnoreCase(name);

		if (haveStatsForThem)
		{
			Instant last = recentLookups.get(name);
			if (last != null && Instant.now().isBefore(last.plusSeconds(config.lookupCooldown())))
			{
				return;
			}
		}
		recentLookups.put(name, Instant.now());

		lookupOpponent(name);
	}

	// ------------------------------------------------------------------
	// Current opponent (drives note display)
	// ------------------------------------------------------------------

	private void setCurrentOpponent(Player opponent)
	{
		final String name = Text.sanitize(opponent.getName());

		// If this is the BH target we were already running to, keep the damage
		// counters; otherwise (a genuinely new opponent) reset them.
		boolean sameAsBhTarget = bhTargetName != null
			&& bhTargetName.equalsIgnoreCase(name);

		if (overlayOpponent != opponent && !sameAsBhTarget)
		{
			damageDealt = 0;
			damageTaken = 0;
		}
		overlayOpponent = opponent;
		bhTargetName = null; // actor now drives the fight
		bhTargetTick = -1;
		lastCombatTick = client.getTickCount();

		currentOpponentName = name;

		// Surface any saved note for this player
		final String note = getPlayerNote(name);
		SwingUtilities.invokeLater(() -> panel.setOpponentNote(name, note));

		SwingUtilities.invokeLater(panel::refresh);
	}

	// ------------------------------------------------------------------
	// Damage overlay: track hits dealt/taken, reset after inactivity
	// ------------------------------------------------------------------

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		// Track damage if either overlay that displays it is enabled
		if (!config.damageOverlay() && !config.opponentOverlay())
		{
			return;
		}

		final Player local = client.getLocalPlayer();
		if (local == null || overlayOpponent == null)
		{
			return;
		}

		final Hitsplat hitsplat = event.getHitsplat();
		final Actor target = event.getActor();
		final int amount = hitsplat.getAmount();

		// Damage we dealt: our hitsplat landing on the tracked opponent
		if (target == overlayOpponent && hitsplat.isMine())
		{
			damageDealt += amount;
			lastCombatTick = client.getTickCount();
		}
		// Damage we took: any hitsplat on us during the fight
		else if (target == local)
		{
			damageTaken += amount;
			lastCombatTick = client.getTickCount();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Keep the local player name fresh for the screenshot-folder button
		final Player local = client.getLocalPlayer();
		if (local != null && local.getName() != null)
		{
			lastKnownPlayerName = local.getName();
		}

		// Keep the fight "active" while the opponent is still present, even
		// during hitless stretches (eating, praying, running a pillar, brief
		// line-of-sight breaks). We refresh on EITHER still interacting OR the
		// opponent still being rendered nearby — a long fight has many ticks
		// where getInteracting() is momentarily null but the fight is ongoing.
		if (overlayOpponent != null && local != null)
		{
			boolean stillInteracting =
				local.getInteracting() == overlayOpponent
				|| overlayOpponent.getInteracting() == local;

			// Opponent still loaded/rendered in the scene = fight not over
			boolean opponentStillPresent =
				overlayOpponent instanceof Player
				&& !((Player) overlayOpponent).isDead()
				&& ((Player) overlayOpponent).getName() != null;

			if (stillInteracting || opponentStillPresent)
			{
				lastCombatTick = client.getTickCount();
			}
		}

		// BH target assigned but not yet engaged: keep the overlay up while
		// running to them. Only clear if no fight starts within ~60s — long
		// enough to reach a far target across the map, but still clears a
		// genuinely skipped/inactive target eventually.
		if (bhTargetName != null && overlayOpponent == null)
		{
			if (bhTargetTick >= 0 && client.getTickCount() - bhTargetTick > 100) // ~60s
			{
				clearDamageFight();
			}
			else
			{
				lastCombatTick = client.getTickCount();
			}
		}

		final boolean activeFight = overlayOpponent != null || bhTargetName != null
			|| lastOpponentStats != null;

		// On entering a safe zone, start a short grace period instead of
		// clearing instantly, so the overlay lingers ~5s then fades.
		if (activeFight && !inPvpArea())
		{
			if (safeZoneTick < 0)
			{
				safeZoneTick = client.getTickCount();
			}
			else if (client.getTickCount() - safeZoneTick > 8) // ~5s
			{
				clearDamageFight();
			}
		}
		else
		{
			safeZoneTick = -1;
		}

		// Otherwise reset after the configured no-combat delay AND no interaction
		if (activeFight
			&& lastCombatTick >= 0
			&& client.getTickCount() - lastCombatTick > (config.damageResetDelay() * 1000 / 600))
		{
			clearDamageFight();
		}
	}

	private boolean inPvpArea()
	{
		try
		{
			return client.getVarbitValue(net.runelite.api.Varbits.IN_WILDERNESS) == 1
				|| net.runelite.api.WorldType.isPvpWorld(client.getWorldType());
		}
		catch (Exception e)
		{
			return true; // if unsure, don't clear prematurely
		}
	}

	private void clearDamageFight()
	{
		overlayOpponent = null;
		bhTargetName = null;
		bhTargetTick = -1;
		damageDealt = 0;
		damageTaken = 0;
		lastCombatTick = -1;
		safeZoneTick = -1;
		lastOpponentStats = null;
	}

	/**
	 * The saved note for the current opponent, or empty string. Used by the
	 * opponent overlay to display a note when one exists (read-only).
	 */
	public String getCurrentOpponentNote()
	{
		return currentOpponentName != null ? getPlayerNote(currentOpponentName) : "";
	}

	/**
	 * Note for a specific player name (used by the overlay so it always matches
	 * the opponent actually being displayed, including manual/right-click lookups).
	 */
	public String getNoteFor(String name)
	{
		return name != null ? getPlayerNote(name) : "";
	}

	public boolean hasActiveFight()
	{
		return overlayOpponent != null || bhTargetName != null || lastOpponentStats != null;
	}

	/**
	 * True only once actually engaged with an opponent (not merely assigned a
	 * BH target you're still running to). Used by the damage overlay so it
	 * doesn't show before any hits can occur.
	 */
	public boolean isEngaged()
	{
		return overlayOpponent != null;
	}

	/**
	 * Returns the folder where kill/death screenshots are saved. ImageCapture
	 * nests screenshots under the logged-in player's display name, so this
	 * mirrors that: .runelite/screenshots/<player name>/PK Tracker
	 */
	public java.io.File getScreenshotFolder()
	{
		java.io.File base = new java.io.File(
			System.getProperty("user.home")
				+ java.io.File.separator + ".runelite"
				+ java.io.File.separator + "screenshots");

		// Use the name captured on the client thread; falls back to a live
		// read if available. ImageCapture nests under the player's name.
		String name = lastKnownPlayerName;
		if (name == null)
		{
			final Player local = client.getLocalPlayer();
			if (local != null)
			{
				name = local.getName();
			}
		}
		if (name != null && !name.isEmpty())
		{
			base = new java.io.File(base, name);
		}
		return new java.io.File(base, "PK Tracker");
	}


	
	// ------------------------------------------------------------------
	// Bounty Hunter target detection (chat based)
	// ------------------------------------------------------------------

	private static final java.util.List<java.util.regex.Pattern> TARGET_PATTERNS = java.util.Arrays.asList(
		// Actual message: "You have been assigned a new target: <name>"
		java.util.regex.Pattern.compile("(?i)you have been assigned a new target:?\\s*(.+?)[.!]?$"),
		java.util.regex.Pattern.compile("(?i)you(?:'|’)?ve been assigned a (?:new )?target:?\\s*(.+?)[.!]?$"),
		// "Your target is now: <name>" — but NOT "Your target is no longer available"
		java.util.regex.Pattern.compile("(?i)your target is now:?\\s*(.+?)[.!]?$"),
		java.util.regex.Pattern.compile("(?i)you(?:'|’)?ve been paired with:?\\s*(.+?)[.!]?$")
	);

	// Messages signalling the BH target is gone — clear the assignment state
	private static final java.util.regex.Pattern TARGET_LOST_PATTERN =
		java.util.regex.Pattern.compile("(?i)target is no longer available|target has been removed|lost your target|no longer have a target");

	// Fired on PvP kills in the wilderness and PvP worlds: "You have defeated X"
	private static final java.util.regex.Pattern KILL_PATTERN =
		java.util.regex.Pattern.compile("(?i)you have defeated (.+?)[.!]?$");

	// Bounty Hunter kill message: "Target killed: <name>! Kills: <n>"
	private static final java.util.regex.Pattern BH_KILL_PATTERN =
		java.util.regex.Pattern.compile("(?i)target killed:\\s*(.+?)!?\\s*kills:\\s*\\d+");

	@Subscribe
	public void onChatMessage(net.runelite.api.events.ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}

		String message = Text.removeTags(event.getMessage());

		// --- Kill detection (always on) ---
		// Wilderness / PvP worlds: "You have defeated X"
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

		// Bounty Hunter: "Target killed: <name>! Kills: <n>"
		java.util.regex.Matcher bhKillMatcher = BH_KILL_PATTERN.matcher(message);
		if (bhKillMatcher.find())
		{
			String victimName = Text.sanitize(bhKillMatcher.group(1)).trim();
			if (!victimName.isEmpty())
			{
				log.debug("BH kill credited from chat: {}", victimName);
				creditKill(victimName, -1);
			}
			return;
		}

		// --- Bounty Hunter target assignment -> stat lookup ---
		if (!config.lookupOnFightStart())
		{
			return;
		}

		// Target lost: stop treating it as an active assignment, but leave the
		// last known stats so the overlay keeps showing them and fades on the
		// normal inactivity timer (rather than popping a message or vanishing).
		if (TARGET_LOST_PATTERN.matcher(message).find())
		{
			bhTargetName = null;
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
					// Mark this as the active opponent so the overlay shows while
					// running to a BH target that may be far away. Reset damage
					// for the new target.
					bhTargetName = targetName;
					bhTargetTick = client.getTickCount();
					currentOpponentName = targetName;
					damageDealt = 0;
					damageTaken = 0;
					lastCombatTick = client.getTickCount();
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

	// ------------------------------------------------------------------
	// Right-click "PK Lookup" on players
	// ------------------------------------------------------------------

	private static final String LOOKUP_OPTION = "PK Lookup";

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (!config.rightClickLookup())
		{
			return;
		}

		final MenuEntry[] entries = event.getMenuEntries();
		// First pass: find the LOWEST menu index for each distinct player.
		// Lower index = lower in the displayed menu, so inserting there places
		// "PK Lookup" at the bottom of that player's option group (below Walk
		// here / Follow), rather than near the top by their Attack option.
		java.util.Map<String, Integer> lowestIdxByPlayer = new java.util.HashMap<>();
		java.util.Map<String, MenuEntry> entryByPlayer = new java.util.HashMap<>();

		for (int idx = 0; idx < entries.length; idx++)
		{
			final MenuEntry entry = entries[idx];
			MenuAction type = entry.getType();
			boolean isPlayerEntry =
				type == MenuAction.PLAYER_FIRST_OPTION
				|| type == MenuAction.PLAYER_SECOND_OPTION
				|| type == MenuAction.PLAYER_THIRD_OPTION
				|| type == MenuAction.PLAYER_FOURTH_OPTION
				|| type == MenuAction.PLAYER_FIFTH_OPTION
				|| type == MenuAction.PLAYER_SIXTH_OPTION
				|| type == MenuAction.PLAYER_SEVENTH_OPTION
				|| type == MenuAction.PLAYER_EIGHTH_OPTION;

			if (!isPlayerEntry || entry.getPlayer() == null)
			{
				continue;
			}

			final String targetName = entry.getPlayer().getName();
			if (targetName == null)
			{
				continue;
			}

			// Keep the lowest index seen for this player (and that entry)
			if (!lowestIdxByPlayer.containsKey(targetName)
				|| idx < lowestIdxByPlayer.get(targetName))
			{
				lowestIdxByPlayer.put(targetName, idx);
				entryByPlayer.put(targetName, entry);
			}
		}

		// Second pass: insert at each player's lowest index, processing from
		// highest index to lowest so earlier insertions don't shift the indices
		// of players we haven't handled yet.
		java.util.List<String> names = new java.util.ArrayList<>(lowestIdxByPlayer.keySet());
		names.sort((a, b) -> lowestIdxByPlayer.get(b) - lowestIdxByPlayer.get(a));

		for (String targetName : names)
		{
			final int idx = lowestIdxByPlayer.get(targetName);
			final MenuEntry entry = entryByPlayer.get(targetName);
			final String cleanName = Text.sanitize(targetName);

			String optionText = menuIconIndex >= 0
				? "<img=" + menuIconIndex + "> " + LOOKUP_OPTION
				: LOOKUP_OPTION;

			client.createMenuEntry(idx)
				.setOption(optionText)
				.setTarget(entry.getTarget())
				.setType(MenuAction.RUNELITE)
				.onClick(e ->
				{
					lookupPlayer(cleanName);
					// Only pop the panel open if the opponent overlay isn't on —
					// when the overlay is enabled it already shows the result,
					// so opening the panel would be redundant and intrusive.
					if (!config.opponentOverlay())
					{
						SwingUtilities.invokeLater(() -> clientToolbar.openPanel(navButton));
					}
				});
		}
	}

	private void lookupOpponent(final String name)
	{
		SwingUtilities.invokeLater(() -> panel.setOpponentLoading(name));

		executor.execute(() ->
		{
			HiscoreResult result;
			try
			{
				result = hiscoreManager.lookup(name, HiscoreEndpoint.NORMAL);
			}
			catch (java.io.IOException ex)
			{
				log.warn("Hiscore lookup failed for '{}'", name, ex);
				SwingUtilities.invokeLater(() -> panel.setOpponentError(name));
				return;
			}

			try
			{
				if (result == null)
				{
					log.warn("Hiscore lookup returned no result for '{}'", name);
					SwingUtilities.invokeLater(() -> panel.setOpponentError(name));
					return;
				}

				OpponentStats stats = buildStats(name, result);
				lastOpponentStats = stats;
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

	// ------------------------------------------------------------------
	// Player notes
	// ------------------------------------------------------------------

	/**
	 * Returns the saved note for a player, or empty string if none.
	 */
	public String getPlayerNote(String name)
	{
		if (name == null)
		{
			return "";
		}
		String note = playerNotes.get(name.toLowerCase().trim());
		return note != null ? note : "";
	}

	/**
	 * Saves (or clears, if blank) a note for a player and persists it.
	 */
	public void savePlayerNote(String name, String note)
	{
		if (name == null || name.trim().isEmpty())
		{
			return;
		}
		String key = name.toLowerCase().trim();
		if (note == null || note.trim().isEmpty())
		{
			playerNotes.remove(key);
		}
		else
		{
			playerNotes.put(key, note.trim());
		}
		saveNotes();
	}

	private void loadNotes()
	{
		String json = configManager.getConfiguration(CONFIG_GROUP, NOTES_KEY);
		if (json == null || json.isEmpty())
		{
			playerNotes = new HashMap<>();
			return;
		}
		try
		{
			Type type = new TypeToken<Map<String, String>>()
			{
			}.getType();
			Map<String, String> loaded = gson.fromJson(json, type);
			playerNotes = loaded != null ? loaded : new HashMap<>();
		}
		catch (Exception e)
		{
			log.warn("Failed to load player notes", e);
			playerNotes = new HashMap<>();
		}
	}

	private void saveNotes()
	{
		try
		{
			configManager.setConfiguration(CONFIG_GROUP, NOTES_KEY, gson.toJson(playerNotes));
		}
		catch (Exception e)
		{
			log.warn("Failed to save player notes", e);
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
