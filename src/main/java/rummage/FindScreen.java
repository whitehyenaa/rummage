package rummage;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class FindScreen extends Screen {
	private static final int W = 300, FIELD_W = 246, ROW_H = 18;
	private final Screen parent;
	private TextFieldWidget field;
	private String query = "";
	private List<Item> matches = List.of();
	private int left, listTop, rows, scroll;

	public FindScreen(Screen parent) {
		super(Text.literal("Find Item"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		left = width / 2 - W / 2;
		int fieldY = 50;
		field = new TextFieldWidget(textRenderer, left, fieldY, FIELD_W, 20, Text.literal("Search item"));
		field.setMaxLength(256);
		field.setText(query);
		field.setChangedListener(s -> refresh());
		addDrawableChild(field);
		addDrawableChild(ButtonWidget.builder(Text.literal("Find"), b -> find())
				.dimensions(left + FIELD_W + 4, fieldY, W - FIELD_W - 4, 20).build());

		listTop = fieldY + 24;
		rows = MathHelper.clamp((height - listTop - 40) / ROW_H, 3, 8);
		int bottom = listTop + rows * ROW_H + 8, half = (W - 10) / 2;
		ButtonWidget clear = addDrawableChild(ButtonWidget.builder(Text.literal("Clear Outlines"), b -> {
			Rummage.clearHighlights();
			b.active = false;
		}).dimensions(left, bottom, half, 20).build());
		clear.active = Rummage.hasHighlights();
		addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, b -> close())
				.dimensions(left + W - half, bottom, half, 20).build());

		setInitialFocus(field);
		refresh();
	}

	private void refresh() {
		query = field.getText();
		String q = query.toLowerCase(Locale.ROOT).trim();
		scroll = 0;
		matches = Registries.ITEM.stream()
				.filter(i -> i != Items.AIR && (name(i).contains(q) || Registries.ITEM.getId(i).toString().contains(q)))
				.sorted(Comparator.comparing(i -> !name(i).startsWith(q)))
				.toList();
	}

	private static String name(Item item) {
		return item.getName().getString().toLowerCase(Locale.ROOT);
	}

	private void find() {
		String q = field.getText().trim();
		if (q.isEmpty()) return;
		client.setScreen(null);
		Rummage.find(q);
	}

	private int rowAt(double x, double y) {
		if (x < left || x >= left + W || y < listTop) return -1;
		int index = scroll + (int) ((y - listTop) / ROW_H);
		return (y - listTop) / ROW_H < rows && index < matches.size() ? index : -1;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 20, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer, "Search item", left, 40, 0xFFA0A0A0);

		context.fill(left, listTop, left + W, listTop + rows * ROW_H, 0xC0000000);
		if (matches.isEmpty()) context.drawTextWithShadow(textRenderer, "No items match", left + 4, listTop + 5, 0xFFA0A0A0);
		int hovered = rowAt(mouseX, mouseY);
		for (int r = 0; r < rows && scroll + r < matches.size(); r++) {
			Item item = matches.get(scroll + r);
			int y = listTop + r * ROW_H;
			if (scroll + r == hovered) context.fill(left, y, left + W, y + ROW_H, 0x40FFFFFF);
			context.drawItem(new ItemStack(item), left + 2, y + 1);
			context.drawTextWithShadow(textRenderer, item.getName().getString(), left + 22, y + 5, 0xFFFFFFFF);
			String id = Registries.ITEM.getId(item).toString();
			context.drawTextWithShadow(textRenderer, id, left + W - 6 - textRenderer.getWidth(id), y + 5, 0xFF808080);
		}

		if (matches.size() > rows) {
			int listH = rows * ROW_H, barH = Math.max(8, listH * rows / matches.size());
			int barY = listTop + (listH - barH) * scroll / (matches.size() - rows);
			context.fill(left + W - 2, barY, left + W, barY + barH, 0xFFA0A0A0);
		}
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		int index = rowAt(click.x(), click.y());
		if (index >= 0) {
			field.setText(Registries.ITEM.getId(matches.get(index)).toString());
			if (doubled) find();
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		scroll = MathHelper.clamp(scroll - (int) Math.signum(vertical), 0, Math.max(0, matches.size() - rows));
		return true;
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (input.getKeycode() == GLFW.GLFW_KEY_ENTER || input.getKeycode() == GLFW.GLFW_KEY_KP_ENTER) {
			find();
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}
}
