package com.pktracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.QuantityFormatter;

public class PkTrackerPanel extends PluginPanel
{
	private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("MMM d, HH:mm");
	private static final Color PROFIT_GREEN = new Color(0, 200, 83);
	private static final Color UNRANKED_GRAY = new Color(130, 130, 130);

	private final PkTrackerPlugin plugin;
	private final SpriteManager spriteManager;
	private final net.runelite.client.game.ItemManager itemManager;

	// One shared font for every stat number so all sections match
	private static final Font VALUE_FONT = FontManager.getRunescapeBoldFont();

	// Session stat value labels
	private final JLabel killsValue = statValue();
	private final JLabel deathsValue = statValue();
	private final JLabel kdValue = statValue();
	private final JLabel streakValue = statValue();
	private final JLabel lootValue = statValue();

	// Opponent section
	private final JLabel opponentTitle = new JLabel("No opponent");
	private final JLabel opponentStatus = new JLabel(" ");
	private final JPanel skillGrid = new JPanel(new GridLayout(0, 3, 4, 6));
	private final JPanel minigameRow = new JPanel(new GridLayout(1, 3, 4, 0));

	// Kill list
	private final JPanel killListPanel = new JPanel();
	private final JToggleButton sessionToggle = new JToggleButton("Session", true);
	private final JToggleButton historyToggle = new JToggleButton("History");

	public PkTrackerPanel(PkTrackerPlugin plugin, SpriteManager spriteManager, net.runelite.client.game.ItemManager itemManager)
	{
		this.plugin = plugin;
		this.spriteManager = spriteManager;
		this.itemManager = itemManager;

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		addSection(buildHeader());
		addSection(buildStatsPanel());
		addSection(buildOpponentPanel());
		addSection(buildButtonsPanel());
		addSection(buildKillListSection());
	}

	/**
	 * All top-level sections share the same alignment so they render at
	 * the same width instead of drifting (a BoxLayout quirk).
	 */
	private void addSection(JPanel section)
	{
		section.setAlignmentX(Component.LEFT_ALIGNMENT);
		if (getComponentCount() > 0)
		{
			add(Box.createVerticalStrut(8));
		}
		add(section);
	}

	// ------------------------------------------------------------------
	// Layout builders
	// ------------------------------------------------------------------

	private JPanel buildHeader()
	{
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel title = new JLabel("PK Tracker");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		header.add(title, BorderLayout.WEST);
		header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		return header;
	}

	private JPanel buildStatsPanel()
	{
		JPanel container = new JPanel(new GridLayout(3, 2, 6, 6));
		container.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		container.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		container.add(statCell("Kills", killsValue));
		container.add(statCell("Deaths", deathsValue));
		container.add(statCell("K/D", kdValue));
		container.add(statCell("Streak", streakValue));
		container.add(statCell("Loot", lootValue));

		JPanel filler = new JPanel();
		filler.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		container.add(filler);

		container.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
		return container;
	}

	private JPanel statCell(String name, JLabel value)
	{
		JPanel cell = new JPanel(new BorderLayout());
		cell.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel label = new JLabel(name);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		cell.add(label, BorderLayout.NORTH);
		cell.add(value, BorderLayout.CENTER);
		return cell;
	}

	private static JLabel statValue()
	{
		JLabel label = new JLabel("-");
		label.setFont(VALUE_FONT);
		label.setForeground(Color.WHITE);
		return label;
	}

	private JPanel buildOpponentPanel()
	{
		JPanel opponentPanel = new JPanel();
		opponentPanel.setLayout(new BoxLayout(opponentPanel, BoxLayout.Y_AXIS));
		opponentPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		opponentPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		JLabel section = new JLabel("OPPONENT");
		section.setFont(FontManager.getRunescapeSmallFont());
		section.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		opponentTitle.setFont(FontManager.getRunescapeBoldFont());
		opponentTitle.setForeground(Color.WHITE);

		opponentStatus.setFont(FontManager.getRunescapeSmallFont());
		opponentStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		skillGrid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		skillGrid.setVisible(false);

		minigameRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		minigameRow.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, ColorScheme.DARK_GRAY_COLOR),
			BorderFactory.createEmptyBorder(6, 0, 0, 0)));
		minigameRow.setVisible(false);

		JPanel lookupBar = buildLookupBar();

		for (Component c : new Component[]{section, opponentTitle, opponentStatus, skillGrid, minigameRow, lookupBar})
		{
			((javax.swing.JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
		}

		opponentPanel.add(section);
		opponentPanel.add(Box.createVerticalStrut(4));
		opponentPanel.add(opponentTitle);
		opponentPanel.add(opponentStatus);
		opponentPanel.add(Box.createVerticalStrut(6));
		opponentPanel.add(skillGrid);
		opponentPanel.add(Box.createVerticalStrut(6));
		opponentPanel.add(minigameRow);
		opponentPanel.add(Box.createVerticalStrut(8));
		opponentPanel.add(lookupBar);
		opponentPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 320));
		return opponentPanel;
	}

	private JPanel buildLookupBar()
	{
		JPanel bar = new JPanel(new BorderLayout(4, 0));
		bar.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		final JTextField nameField = new JTextField();
		nameField.setToolTipText("Look up any player's combat stats");

		JButton lookupButton = new JButton("Lookup");
		lookupButton.setFocusPainted(false);

		Runnable doLookup = () ->
		{
			String name = nameField.getText().trim();
			if (!name.isEmpty())
			{
				plugin.lookupPlayer(name);
				nameField.setText("");
			}
		};
		lookupButton.addActionListener(e -> doLookup.run());
		nameField.addActionListener(e -> doLookup.run()); // Enter key

		bar.add(nameField, BorderLayout.CENTER);
		bar.add(lookupButton, BorderLayout.EAST);
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		return bar;
	}

	private JPanel buildButtonsPanel()
	{
		JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JButton reset = new JButton("Reset session");
		reset.setFocusPainted(false);
		reset.addActionListener(e -> plugin.resetSession());

		JButton clear = new JButton("Clear history");
		clear.setFocusPainted(false);
		clear.addActionListener(e ->
		{
			int confirm = JOptionPane.showConfirmDialog(this,
				"Permanently delete all stored kill history?",
				"Clear history", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (confirm == JOptionPane.YES_OPTION)
			{
				plugin.clearHistory();
			}
		});

		buttons.add(reset);
		buttons.add(clear);
		buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		return buttons;
	}

	private JPanel buildKillListSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel toggles = new JPanel(new GridLayout(1, 2));
		toggles.setBackground(ColorScheme.DARK_GRAY_COLOR);
		toggles.setAlignmentX(Component.LEFT_ALIGNMENT);
		sessionToggle.setFocusPainted(false);
		historyToggle.setFocusPainted(false);
		sessionToggle.addActionListener(e ->
		{
			sessionToggle.setSelected(true);
			historyToggle.setSelected(false);
			refresh();
		});
		historyToggle.addActionListener(e ->
		{
			historyToggle.setSelected(true);
			sessionToggle.setSelected(false);
			refresh();
		});
		toggles.add(sessionToggle);
		toggles.add(historyToggle);
		toggles.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

		killListPanel.setLayout(new BoxLayout(killListPanel, BoxLayout.Y_AXIS));
		killListPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		killListPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		section.add(toggles);
		section.add(Box.createVerticalStrut(6));
		section.add(killListPanel);
		return section;
	}

	// ------------------------------------------------------------------
	// Refresh / updates (always called on the Swing EDT)
	// ------------------------------------------------------------------

	public void refresh()
	{
		killsValue.setText(String.valueOf(plugin.getSessionKills()));
		deathsValue.setText(String.valueOf(plugin.getSessionDeaths()));
		streakValue.setText(String.valueOf(plugin.getKillStreak()));

		int kills = plugin.getSessionKills();
		int deaths = plugin.getSessionDeaths();
		if (deaths == 0)
		{
			kdValue.setText(kills == 0 ? "-" : kills + ".00");
		}
		else
		{
			kdValue.setText(String.format("%.2f", (double) kills / deaths));
		}

		lootValue.setText(QuantityFormatter.quantityToStackSize(plugin.getSessionLootValue()));
		lootValue.setForeground(PROFIT_GREEN);
		lootValue.setToolTipText(exactGp(plugin.getSessionLootValue()));

		rebuildKillList();

		revalidate();
		repaint();
	}

	private static String exactGp(long value)
	{
		return java.text.NumberFormat.getIntegerInstance().format(value) + " gp";
	}

	private void rebuildKillList()
	{
		killListPanel.removeAll();

		List<KillEntry> entries = sessionToggle.isSelected()
			? plugin.getSessionKillList()
			: plugin.getKillHistory();

		if (entries.isEmpty())
		{
			JLabel empty = new JLabel(sessionToggle.isSelected()
				? "No kills this session yet."
				: "No kill history yet.");
			empty.setFont(FontManager.getRunescapeSmallFont());
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setHorizontalAlignment(SwingConstants.CENTER);
			killListPanel.add(empty);
			return;
		}

		for (KillEntry entry : entries)
		{
			killListPanel.add(buildKillRow(entry));
			killListPanel.add(Box.createVerticalStrut(4));
		}
	}

	private JPanel buildKillRow(KillEntry entry)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);

		// --- Header: name + value ---
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.setAlignmentX(Component.LEFT_ALIGNMENT);

		String title = entry.victimName + (entry.combatLevel > 0 ? " (lvl " + entry.combatLevel + ")" : "");
		JLabel name = new JLabel(title);
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(Color.WHITE);

		JLabel value = new JLabel(QuantityFormatter.quantityToStackSize(entry.totalValue) + " gp");
		value.setFont(FontManager.getRunescapeSmallFont());
		value.setForeground(PROFIT_GREEN);
		value.setToolTipText(exactGp(entry.totalValue));

		header.add(name, BorderLayout.WEST);
		header.add(value, BorderLayout.EAST);

		JLabel sub = new JLabel(TIME_FORMAT.format(new Date(entry.timestamp)) + "  ·  W" + entry.world);
		sub.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.PLAIN, 10f));
		sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sub.setAlignmentX(Component.LEFT_ALIGNMENT);

		row.add(header);
		row.add(sub);

		// --- Item icon grid (loot tracker style) ---
		if (entry.loot != null && !entry.loot.isEmpty())
		{
			JPanel itemGrid = new JPanel(new GridLayout(0, 5, 2, 2));
			itemGrid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			itemGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
			itemGrid.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

			for (KillEntry.LootItem item : entry.loot)
			{
				itemGrid.add(buildItemCell(item));
			}

			// Pad the final grid row so items stay left-aligned at icon size
			int remainder = entry.loot.size() % 5;
			if (remainder != 0)
			{
				for (int i = remainder; i < 5; i++)
				{
					JPanel filler = new JPanel();
					filler.setBackground(ColorScheme.DARKER_GRAY_COLOR);
					itemGrid.add(filler);
				}
			}

			row.add(itemGrid);
		}

		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	private JLabel buildItemCell(KillEntry.LootItem item)
	{
		JLabel iconLabel = new JLabel();
		iconLabel.setPreferredSize(new Dimension(36, 32));
		iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
		iconLabel.setToolTipText("<html>"
			+ (item.name != null ? item.name : "Item " + item.itemId)
			+ (item.quantity > 1 ? " x " + QuantityFormatter.formatNumber(item.quantity) : "")
			+ "<br>" + exactGp(item.totalPrice) + "</html>");

		try
		{
			// Async game icon with stack quantity drawn on it, like the loot tracker
			itemManager.getImage(item.itemId, item.quantity, item.quantity > 1).addTo(iconLabel);
		}
		catch (Throwable t)
		{
			iconLabel.setText("?");
			iconLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}

		return iconLabel;
	}

	// ------------------------------------------------------------------
	// Opponent stat display (hiscore-style grid)
	// ------------------------------------------------------------------

	public void setOpponentLoading(String name)
	{
		opponentTitle.setText(name);
		opponentStatus.setText("Looking up stats…");
		opponentStatus.setVisible(true);
		skillGrid.setVisible(false);
		minigameRow.setVisible(false);
		revalidate();
		repaint();
	}

	public void setOpponentError(String name)
	{
		opponentTitle.setText(name);
		opponentStatus.setText("Lookup failed (unranked?)");
		opponentStatus.setVisible(true);
		skillGrid.setVisible(false);
		minigameRow.setVisible(false);
		revalidate();
		repaint();
	}

	public void setOpponent(OpponentStats stats)
	{
		opponentTitle.setText(stats.name + "   ⚔ " + stats.combatLevel);
		opponentStatus.setVisible(false);

		skillGrid.removeAll();
		skillGrid.add(skillCell("Attack", stats.attack));
		skillGrid.add(skillCell("Strength", stats.strength));
		skillGrid.add(skillCell("Defence", stats.defence));
		skillGrid.add(skillCell("Hitpoints", stats.hitpoints));
		skillGrid.add(skillCell("Ranged", stats.ranged));
		skillGrid.add(skillCell("Magic", stats.magic));
		skillGrid.add(skillCell("Prayer", stats.prayer));
		skillGrid.setVisible(true);

		minigameRow.removeAll();
		minigameRow.add(minigameCell("BH Hunter", HiscoreSkill.BOUNTY_HUNTER_HUNTER, stats.bhHunter));
		minigameRow.add(minigameCell("BH Rogue", HiscoreSkill.BOUNTY_HUNTER_ROGUE, stats.bhRogue));
		minigameRow.add(minigameCell("LMS", HiscoreSkill.LAST_MAN_STANDING, stats.lms));
		minigameRow.setVisible(true);

		revalidate();
		repaint();
	}

	private JPanel skillCell(String skillName, int level)
	{
		JPanel cell = new JPanel(new BorderLayout(4, 0));
		cell.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		cell.setToolTipText(skillName);

		JLabel iconLabel = new JLabel();
		ImageIcon icon = loadIcon(
			"/skill_icons_small/" + skillName.toLowerCase() + ".png",
			"/skill_icons/" + skillName.toLowerCase() + ".png");
		if (icon != null)
		{
			iconLabel.setIcon(icon);
		}
		else
		{
			iconLabel.setText(skillName.substring(0, 3));
			iconLabel.setFont(FontManager.getRunescapeSmallFont());
			iconLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}

		JLabel value = new JLabel(String.valueOf(level));
		value.setFont(VALUE_FONT);
		value.setForeground(Color.WHITE);

		cell.add(iconLabel, BorderLayout.WEST);
		cell.add(value, BorderLayout.CENTER);
		return cell;
	}

	/**
	 * Minigame cells use the game's own sprites via SpriteManager —
	 * the same way RuneLite's hiscore panel renders them.
	 */
	private JPanel minigameCell(String label, HiscoreSkill skill, int score)
	{
		JPanel cell = new JPanel(new BorderLayout(4, 0));
		cell.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		cell.setToolTipText(label + (score < 0 ? " — unranked" : " score: " + score));

		final JLabel iconLabel = new JLabel();
		try
		{
			spriteManager.getSpriteAsync(skill.getSpriteId(), 0, sprite ->
			{
				if (sprite == null)
				{
					return;
				}
				final BufferedImage scaled = ImageUtil.resizeImage(
					ImageUtil.resizeCanvas(sprite, 25, 25), 20, 20);
				SwingUtilities.invokeLater(() ->
				{
					iconLabel.setText(null);
					iconLabel.setIcon(new ImageIcon(scaled));
					iconLabel.revalidate();
					iconLabel.repaint();
				});
			});
		}
		catch (Throwable t)
		{
			// fall through to text fallback below
		}

		// Text fallback shown until/unless the sprite arrives
		if (iconLabel.getIcon() == null)
		{
			iconLabel.setText(shortLabel(label));
			iconLabel.setFont(FontManager.getRunescapeSmallFont());
			iconLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}

		JLabel value = new JLabel(score < 0 ? "-" : String.valueOf(score));
		value.setFont(VALUE_FONT);
		value.setForeground(score < 0 ? UNRANKED_GRAY : Color.WHITE);

		cell.add(iconLabel, BorderLayout.WEST);
		cell.add(value, BorderLayout.CENTER);
		return cell;
	}

	private static String shortLabel(String label)
	{
		switch (label)
		{
			case "BH Hunter":
				return "BH-H";
			case "BH Rogue":
				return "BH-R";
			default:
				return label;
		}
	}

	private static ImageIcon loadIcon(String... paths)
	{
		for (String path : paths)
		{
			try
			{
				BufferedImage img = ImageUtil.loadImageResource(PkTrackerPanel.class, path);
				if (img != null)
				{
					return new ImageIcon(img);
				}
			}
			catch (Throwable ignored)
			{
				// resource not present at this path in this client version
			}
		}
		return null;
	}
}
