package rummage;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class ConfigScreen extends Screen {
	private static final int W = 150, ROW = 24, GAP = 10;
	private static final String COLOR_LABEL = "Outline color";
	private final Screen parent;
	private final Config config = Config.INSTANCE;
	private int left, right, top, colorY;

	public ConfigScreen(Screen parent) {
		super(Text.literal("Rummage"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		left = width / 2 - W - GAP / 2;
		right = width / 2 + GAP / 2;
		top = height / 6;

		int full = W * 2 + GAP;
		colorY = top + ROW * 2;

		toggle(left, top, full, "Mod Toggle: ", () -> config.enabled, v -> config.enabled = v);
		toggle(left, top + ROW, full, "Clear outlines automatically: ", () -> config.autoClear, v -> config.autoClear = v);

		int labelW = textRenderer.getWidth(COLOR_LABEL) + 6;
		TextFieldWidget color = new TextFieldWidget(textRenderer, left + labelW, colorY, W - labelW - 24, 20, Text.literal(COLOR_LABEL));
		color.setTextPredicate(s -> s.matches("[0-9a-fA-F]{0,6}"));
		color.setText(String.format("%06X", config.color));
		color.setChangedListener(s -> { if (s.length() == 6) config.color = Integer.parseInt(s, 16); });
		addDrawableChild(color);
		slider(right, colorY, "Outline thickness", 1, 10, config.thickness, v -> config.thickness = v);

		SliderWidget speed = slider(right, top + ROW * 3, "RGB speed", 0.1f, 10, config.rgbSpeed, v -> config.rgbSpeed = v);
		speed.active = config.gamerMode;
		toggle(left, top + ROW * 3, W, "Gamer Mode: ", () -> config.gamerMode, v -> {
			config.gamerMode = v;
			speed.active = v;
		});

		addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close()).dimensions(width / 2 - 100, height - 27, 200, 20).build());
	}

	private SliderWidget slider(int x, int y, String label, float min, float max, float current, Consumer<Float> set) {
		return addDrawableChild(new SliderWidget(x, y, W, 20, Text.empty(), (current - min) / (max - min)) {
			{ updateMessage(); }

			private float get() { return (float) (min + value * (max - min)); }

			@Override
			protected void updateMessage() {
				setMessage(Text.literal(String.format("%s: %.1f", label, get())));
			}

			@Override
			protected void applyValue() {
				set.accept(get());
			}
		});
	}

	private void toggle(int x, int y, int w, String label, BooleanSupplier get, Consumer<Boolean> set) {
		addDrawableChild(ButtonWidget.builder(toggleText(label, get.getAsBoolean()), b -> {
			set.accept(!get.getAsBoolean());
			b.setMessage(toggleText(label, get.getAsBoolean()));
		}).dimensions(x, y, w, 20).build());
	}

	private static Text toggleText(String label, boolean on) {
		return Text.literal(label).append(on ? ScreenTexts.ON : ScreenTexts.OFF);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 15, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer, COLOR_LABEL, left, colorY + 6, 0xFFFFFFFF);
		context.fill(left + W - 20, colorY, left + W, colorY + 20, config.currentColor());
	}

	@Override
	public void close() {
		Config.save();
		client.setScreen(parent);
	}
}
