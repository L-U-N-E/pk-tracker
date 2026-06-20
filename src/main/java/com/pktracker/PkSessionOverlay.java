package com.pktracker;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Custom-drawn session stats overlay: kills, deaths, K/D, streak, loot.
 * Styled to match the panel; always visible when enabled.
 */
public class PkSessionOverlay extends Overlay
{
	private static final Color BG = new Color(40, 40, 40, 220);
	private static final Color BORDER = new Color(70, 70, 70);
	private static final Color TEXT = Color.WHITE;
	private static final Color GREEN = new Color(0, 200, 83);
	private static final Color RED = new Color(244, 67, 54);
	private static final Color LIGHT = new Color(170, 170, 170);

	private static final int PAD = 8;
	private static final int WIDTH = 140;
	private static final int ROW_H = 18;

	private final PkTrackerPlugin plugin;
	private final PkTrackerConfig config;

	public PkSessionOverlay(PkTrackerPlugin plugin, PkTrackerConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.sessionOverlay())
		{
			return null;
		}

		final int kills = plugin.getSessionKills();
		final int deaths = plugin.getSessionDeaths();
		final String kd = deaths == 0
			? kills + ".00"
			: String.format("%.2f", (double) kills / deaths);

		final int rows = 6; // title + 5 stats
		final int height = PAD * 2 + rows * ROW_H;

		g.setColor(BG);
		g.fillRect(0, 0, WIDTH, height);
		g.setColor(BORDER);
		g.drawRect(0, 0, WIDTH - 1, height - 1);

		final Font font = FontManager.getRunescapeSmallFont();
		g.setFont(font);
		final FontMetrics fm = g.getFontMetrics();

		int y = PAD + ROW_H - 5;

		g.setColor(TEXT);
		g.drawString("PK Session", PAD, y);
		y += ROW_H;

		drawRow(g, fm, "Kills", String.valueOf(kills), GREEN, y); y += ROW_H;
		drawRow(g, fm, "Deaths", String.valueOf(deaths), RED, y); y += ROW_H;
		drawRow(g, fm, "K/D", kd, TEXT, y); y += ROW_H;
		drawRow(g, fm, "Streak", String.valueOf(plugin.getKillStreak()), TEXT, y); y += ROW_H;
		drawRow(g, fm, "Loot", formatValue(plugin.getSessionLootValue()), GREEN, y);

		return new Dimension(WIDTH, height);
	}

	private void drawRow(Graphics2D g, FontMetrics fm, String label, String value, Color valueColor, int y)
	{
		g.setColor(LIGHT);
		g.drawString(label, PAD, y);
		g.setColor(valueColor);
		int vx = WIDTH - PAD - fm.stringWidth(value);
		g.drawString(value, vx, y);
	}

	private static String formatValue(long value)
	{
		if (value >= 1_000_000)
		{
			return String.format("%.1fM", value / 1_000_000.0);
		}
		if (value >= 1_000)
		{
			return String.format("%.1fK", value / 1_000.0);
		}
		return String.valueOf(value);
	}
}
