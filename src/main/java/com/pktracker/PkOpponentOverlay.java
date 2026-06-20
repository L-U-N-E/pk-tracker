package com.pktracker;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import net.runelite.client.ui.FontManager;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageUtil;

/**
 * Custom-drawn opponent overlay: a skill icon grid (like the side panel),
 * BH/LMS ranks, saved note, and live damage. Renders with Graphics2D so the
 * layout matches the panel's grid look. Fades in/out smoothly.
 */
public class PkOpponentOverlay extends Overlay
{
	private static final Color BG = new Color(40, 40, 40, 220);
	private static final Color BORDER = new Color(70, 70, 70);
	private static final Color TEXT = Color.WHITE;
	private static final Color GREEN = new Color(0, 200, 83);
	private static final Color RED = new Color(244, 67, 54);
	private static final Color AMBER = new Color(255, 170, 0);
	private static final Color LIGHT = new Color(170, 170, 170);

	private static final int PAD = 8;
	private static final int WIDTH = 150;
	private static final int ICON = 16;
	private static final int ROW_H = 20;

	// Order matches the side panel grid (3 columns)
	private static final String[] SKILLS = {
		"attack", "strength", "defence",
		"hitpoints", "ranged", "magic", "prayer"
	};

	private final PkTrackerPlugin plugin;
	private final PkTrackerConfig config;
	private final SpriteManager spriteManager;

	private final Map<String, BufferedImage> icons = new HashMap<>();
	private boolean iconsLoaded = false;

	// BH/LMS minigame icons (loaded async from game sprites)
	private BufferedImage bhIcon;
	private BufferedImage lmsIcon;
	private boolean minigameIconsRequested = false;

	// Fade state
	private double alpha = 0.0;
	private static final double FADE_STEP = 0.12;

	public PkOpponentOverlay(PkTrackerPlugin plugin, PkTrackerConfig config, SpriteManager spriteManager)
	{
		this.plugin = plugin;
		this.config = config;
		this.spriteManager = spriteManager;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	private void requestMinigameIcons()
	{
		minigameIconsRequested = true;
		spriteManager.getSpriteAsync(HiscoreSkill.BOUNTY_HUNTER_HUNTER.getSpriteId(), 0, s ->
		{
			if (s != null)
			{
				bhIcon = ImageUtil.resizeImage(s, ICON, ICON);
			}
		});
		spriteManager.getSpriteAsync(HiscoreSkill.LAST_MAN_STANDING.getSpriteId(), 0, s ->
		{
			if (s != null)
			{
				lmsIcon = ImageUtil.resizeImage(s, ICON, ICON);
			}
		});
	}

	private void loadIcons()
	{
		for (String s : SKILLS)
		{
			BufferedImage img = ImageUtil.loadImageResource(getClass(),
				"/skill_icons_small/" + s + ".png");
			if (img == null)
			{
				img = ImageUtil.loadImageResource(getClass(), "/skill_icons/" + s + ".png");
			}
			if (img != null)
			{
				icons.put(s, ImageUtil.resizeImage(img, ICON, ICON));
			}
		}
		iconsLoaded = true;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		final boolean shouldShow = config.opponentOverlay() && plugin.hasActiveFight();

		// Fade toward target
		alpha += shouldShow ? FADE_STEP : -FADE_STEP;
		if (alpha <= 0)
		{
			alpha = 0;
			return null;
		}
		if (alpha > 1)
		{
			alpha = 1;
		}

		if (!iconsLoaded)
		{
			loadIcons();
		}

		final OpponentStats stats = plugin.getLastOpponentStats();
		final String name = stats != null ? stats.name : plugin.getCurrentOpponentName();
		final String note = plugin.getNoteFor(name);
		final boolean hasNote = note != null && !note.isEmpty();

		// Compute height
		int rows = 0;
		rows += 1;                       // title
		if (hasNote) rows += 1;          // note line
		if (stats != null) rows += 3;    // 7 skills in 3 cols = 3 rows
		if (stats != null) rows += 1;    // bh/lms row
		final int height = PAD * 2 + rows * ROW_H;

		java.awt.Composite old = g.getComposite();
		g.setComposite(java.awt.AlphaComposite.getInstance(
			java.awt.AlphaComposite.SRC_OVER, (float) alpha));

		// Background
		g.setColor(BG);
		g.fillRect(0, 0, WIDTH, height);
		g.setColor(BORDER);
		g.drawRect(0, 0, WIDTH - 1, height - 1);

		final Font font = FontManager.getRunescapeSmallFont();
		g.setFont(font);
		final FontMetrics fm = g.getFontMetrics();

		int y = PAD + ROW_H - 6;

		// Title: name + combat level (crossed-swords symbol, like the panel)
		g.setColor(TEXT);
		String title = name != null ? name : "Opponent";
		if (stats != null && stats.combatLevel > 0)
		{
			title = title + "  \u2694 " + stats.combatLevel;
		}
		g.drawString(title, PAD, y);
		y += ROW_H;

		// Note (amber, read-only)
		if (hasNote)
		{
			g.setColor(AMBER);
			String n = "\u26A0 " + note;
			if (fm.stringWidth(n) > WIDTH - PAD * 2)
			{
				// truncate to fit
				while (n.length() > 4 && fm.stringWidth(n + "…") > WIDTH - PAD * 2)
				{
					n = n.substring(0, n.length() - 1);
				}
				n = n + "…";
			}
			g.drawString(n, PAD, y);
			y += ROW_H;
		}

		// Skill grid (3 columns)
		if (stats != null)
		{
			int[] levels = {
				stats.attack, stats.strength, stats.defence,
				stats.hitpoints, stats.ranged, stats.magic, stats.prayer
			};
			final int colW = (WIDTH - PAD * 2) / 3;
			for (int i = 0; i < SKILLS.length; i++)
			{
				int col = i % 3;
				int rowY = y + (i / 3) * ROW_H;
				int x = PAD + col * colW;

				BufferedImage icon = icons.get(SKILLS[i]);
				if (icon != null)
				{
					g.drawImage(icon, x, rowY - ICON + 4, null);
				}
				g.setColor(TEXT);
				g.drawString(String.valueOf(levels[i]), x + ICON + 2, rowY);
			}
			y += 3 * ROW_H;

			// BH / LMS row — icons + numbers
			if (!minigameIconsRequested)
			{
				requestMinigameIcons();
			}
			int mx = PAD;
			if (stats.bhHunter >= 0)
			{
				if (bhIcon != null)
				{
					g.drawImage(bhIcon, mx, y - ICON + 4, null);
					mx += ICON + 2;
				}
				g.setColor(TEXT);
				g.drawString(String.valueOf(stats.bhHunter), mx, y);
				mx += fm.stringWidth(String.valueOf(stats.bhHunter)) + 10;
			}
			if (stats.lms >= 0)
			{
				if (lmsIcon != null)
				{
					g.drawImage(lmsIcon, mx, y - ICON + 4, null);
					mx += ICON + 2;
				}
				g.setColor(TEXT);
				g.drawString(String.valueOf(stats.lms), mx, y);
			}
			if (stats.bhHunter < 0 && stats.lms < 0)
			{
				g.setColor(LIGHT);
				g.drawString("Unranked", PAD, y);
			}
			y += ROW_H;
		}

		g.setComposite(old);
		return new Dimension(WIDTH, height);
	}
}
