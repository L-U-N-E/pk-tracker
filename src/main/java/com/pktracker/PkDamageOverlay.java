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
 * Custom-drawn damage overlay: "You" vs "Opponent" damage for the current
 * fight. Styled to match the session and opponent overlays. Visible only
 * while a fight is active.
 */
public class PkDamageOverlay extends Overlay
{
	private static final Color BG = new Color(40, 40, 40, 220);
	private static final Color BORDER = new Color(70, 70, 70);
	private static final Color TEXT = Color.WHITE;
	private static final Color GREEN = new Color(0, 200, 83);
	private static final Color RED = new Color(244, 67, 54);
	private static final Color LIGHT = new Color(170, 170, 170);

	private static final int PAD = 8;
	private static final int WIDTH = 130;
	private static final int ROW_H = 18;

	private final PkTrackerPlugin plugin;
	private final PkTrackerConfig config;

	public PkDamageOverlay(PkTrackerPlugin plugin, PkTrackerConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.damageOverlay() || !plugin.isEngaged())
		{
			return null;
		}

		final int rows = 3; // title + 2 lines
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
		g.drawString("Damage", PAD, y);
		y += ROW_H;

		drawRow(g, fm, "You", String.valueOf(plugin.getDamageDealt()), GREEN, y);
		y += ROW_H;
		drawRow(g, fm, "Opponent", String.valueOf(plugin.getDamageTaken()), RED, y);

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
}
