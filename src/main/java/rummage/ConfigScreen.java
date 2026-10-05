package rummage;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class ConfigScreen extends Screen {
	private static final int W = 150, ROW = 24, GAP = 10;
	private static final String COLOR_LABEL = "Outline color";
	private final Screen parent;
	private final Config config = Config.INSTANCE;
	private int left, right, top, colorY;

	public ConfigScreen(Screen parent) {
		super(Component.literal("Rummage"));
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

		int labelW = font.width(COLOR_LABEL) + 6;
		EditBox color = new EditBox(font, left + labelW, colorY, W - labelW - 24, 20, Component.literal(COLOR_LABEL));
		color.setMaxLength(6);
		color.setValue(String.format("%06X", config.color));
		color.setResponder(s -> {
			String hex = s.replaceAll("[^0-9a-fA-F]", "");
			if (!hex.equals(s)) color.setValue(hex);
			else if (s.length() == 6) config.color = Integer.parseInt(s, 16);
		});
		addRenderableWidget(color);
		slider(right, colorY, "Outline thickness", 1, 10, config.thickness, v -> config.thickness = v);

		AbstractSliderButton speed = slider(right, top + ROW * 3, "RGB speed", 0.1f, 10, config.rgbSpeed, v -> config.rgbSpeed = v);
		speed.active = config.gamerMode;
		toggle(left, top + ROW * 3, W, "Gamer Mode: ", () -> config.gamerMode, v -> {
			config.gamerMode = v;
			speed.active = v;
		});

		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 - 100, height - 27, 200, 20).build());
	}

	private AbstractSliderButton slider(int x, int y, String label, float min, float max, float current, Consumer<Float> set) {
		return addRenderableWidget(new AbstractSliderButton(x, y, W, 20, Component.empty(), (current - min) / (max - min)) {
			{ updateMessage(); }

			private float get() { return (float) (min + value * (max - min)); }

			@Override
			protected void updateMessage() {
				setMessage(Component.literal(String.format("%s: %.1f", label, get())));
			}

			@Override
			protected void applyValue() {
				set.accept(get());
			}
		});
	}

	private void toggle(int x, int y, int w, String label, BooleanSupplier get, Consumer<Boolean> set) {
		addRenderableWidget(Button.builder(toggleText(label, get.getAsBoolean()), b -> {
			set.accept(!get.getAsBoolean());
			b.setMessage(toggleText(label, get.getAsBoolean()));
		}).bounds(x, y, w, 20).build());
	}

	private static Component toggleText(String label, boolean on) {
		return Component.literal(label).append(on ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.centeredText(font, title, width / 2, 15, 0xFFFFFFFF);
		g.text(font, COLOR_LABEL, left, colorY + 6, 0xFFFFFFFF);
		g.fill(left + W - 20, colorY, left + W, colorY + 20, config.currentColor());
	}

	@Override
	public void onClose() {
		Config.save();
		minecraft.setScreen(parent);
	}
}
