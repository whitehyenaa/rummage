package rummage;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class FindScreen extends Screen {
	private static final int W = 300, FIELD_W = 246, ROW_H = 18;
	private final Screen parent;
	private EditBox field;
	private String query = "";
	private List<Item> matches = List.of();
	private int left, listTop, rows, scroll;

	public FindScreen(Screen parent) {
		super(Component.literal("Find Item"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		left = width / 2 - W / 2;
		int fieldY = 50;
		field = new EditBox(font, left, fieldY, FIELD_W, 20, Component.literal("Search item"));
		field.setMaxLength(256);
		field.setValue(query);
		field.setResponder(s -> refresh());
		addRenderableWidget(field);
		addRenderableWidget(Button.builder(Component.literal("Find"), b -> find())
				.bounds(left + FIELD_W + 4, fieldY, W - FIELD_W - 4, 20).build());

		listTop = fieldY + 24;
		rows = Mth.clamp((height - listTop - 40) / ROW_H, 3, 8);
		int bottom = listTop + rows * ROW_H + 8, half = (W - 10) / 2;
		Button clear = addRenderableWidget(Button.builder(Component.literal("Clear Outlines"), b -> {
			Rummage.clearHighlights();
			b.active = false;
		}).bounds(left, bottom, half, 20).build());
		clear.active = Rummage.hasHighlights();
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
				.bounds(left + W - half, bottom, half, 20).build());

		setInitialFocus(field);
		refresh();
	}

	private void refresh() {
		query = field.getValue();
		String q = query.toLowerCase(Locale.ROOT).trim();
		scroll = 0;
		matches = BuiltInRegistries.ITEM.stream()
				.filter(i -> i != Items.AIR && (name(i).toLowerCase(Locale.ROOT).contains(q) || id(i).contains(q)))
				.sorted(Comparator.comparing(i -> !name(i).toLowerCase(Locale.ROOT).startsWith(q)))
				.toList();
	}

	private static String name(Item item) {
		return new ItemStack(item).getHoverName().getString();
	}

	private static String id(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

	private void find() {
		String q = field.getValue().trim();
		if (q.isEmpty()) return;
		minecraft.gui.setScreen(null);
		Rummage.find(q);
	}

	private int rowAt(double x, double y) {
		if (x < left || x >= left + W || y < listTop) return -1;
		int index = scroll + (int) ((y - listTop) / ROW_H);
		return (y - listTop) / ROW_H < rows && index < matches.size() ? index : -1;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.centeredText(font, title, width / 2, 20, 0xFFFFFFFF);
		g.text(font, "Search item", left, 40, 0xFFA0A0A0);

		g.fill(left, listTop, left + W, listTop + rows * ROW_H, 0xC0000000);
		if (matches.isEmpty()) g.text(font, "No items match", left + 4, listTop + 5, 0xFFA0A0A0);
		int hovered = rowAt(mouseX, mouseY);
		for (int r = 0; r < rows && scroll + r < matches.size(); r++) {
			Item item = matches.get(scroll + r);
			int y = listTop + r * ROW_H;
			if (scroll + r == hovered) g.fill(left, y, left + W, y + ROW_H, 0x40FFFFFF);
			g.item(new ItemStack(item), left + 2, y + 1);
			g.text(font, name(item), left + 22, y + 5, 0xFFFFFFFF);
			String id = id(item);
			g.text(font, id, left + W - 6 - font.width(id), y + 5, 0xFF808080);
		}

		if (matches.size() > rows) {
			int listH = rows * ROW_H, barH = Math.max(8, listH * rows / matches.size());
			int barY = listTop + (listH - barH) * scroll / (matches.size() - rows);
			g.fill(left + W - 2, barY, left + W, barY + barH, 0xFFA0A0A0);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		int index = rowAt(click.x(), click.y());
		if (index >= 0) {
			field.setValue(id(matches.get(index)));
			if (doubled) find();
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		scroll = Mth.clamp(scroll - (int) Math.signum(vertical), 0, Math.max(0, matches.size() - rows));
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent input) {
		if (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) {
			find();
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
