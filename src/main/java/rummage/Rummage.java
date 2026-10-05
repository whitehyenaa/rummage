package rummage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.command.CommandSource;
import net.minecraft.command.DefaultPermissions;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import rummage.mixin.HandledScreenAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class Rummage implements ClientModInitializer {
	static final Logger LOG = LoggerFactory.getLogger("rummage");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type TYPE = new TypeToken<Map<String, Entry>>() {}.getType();

	private static final RenderLayer XRAY_LINES = RenderLayer.of("rummage_xray_lines", RenderSetup.builder(
			RenderPipelines.register(RenderPipeline.builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
					.withLocation(Identifier.of("rummage", "pipeline/xray_lines"))
					.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
					.build())).build());

	record Entry(String dim, int x, int y, int z, Map<String, Integer> items, boolean ender) {
		BlockPos pos() { return new BlockPos(x, y, z); }
	}

	record Hit(Entry entry, String name, int count, double dist) {}

	private static Map<String, Entry> containers = new HashMap<>();
	private static Path file;
	private static BlockPos lastClicked;
	private static List<BlockPos> highlights = List.of();
	private static String highlightDim;

	@Override
	public void onInitializeClient() {
		Config.load();

		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (world.isClient() && isContainer(world, hit.getBlockPos())) lastClicked = hit.getBlockPos();
			return ActionResult.PASS;
		});

		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			BlockPos pos = lastClicked;
			lastClicked = null;
			if (!Config.INSTANCE.enabled || pos == null || !(screen instanceof HandledScreen<?> hs)) return;
			String dim = client.world.getRegistryKey().getValue().toString();
			if (Config.INSTANCE.autoClear) unhighlight(client.world, pos);
			ScreenEvents.remove(screen).register(s -> record(dim, pos, hs.getScreenHandler()));
		});

		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("rummage", "main"));
		KeyBinding findKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.rummage.find", GLFW.GLFW_KEY_F9, category));
		KeyBinding clearKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.rummage.clear", GLFW.GLFW_KEY_F10, category));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.world != null && !highlights.isEmpty()) forgetBroken(client.world);
			while (findKey.wasPressed()) {
				if (Config.INSTANCE.enabled && client.currentScreen == null) client.setScreen(new FindScreen(null));
			}
			while (clearKey.wasPressed()) {
				if (client.player != null && hasHighlights()) {
					clearHighlights();
					client.player.sendMessage(Text.literal("Outlines cleared"), true);
				}
			}
		});

		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!Config.INSTANCE.enabled || !(screen instanceof InventoryScreen)) return;
			HandledScreenAccessor panel = (HandledScreenAccessor) screen;
			ButtonWidget button = ButtonWidget.builder(Text.literal("Find"), b -> client.setScreen(new FindScreen(screen)))
					.dimensions(panel.rummage$getX(), panel.rummage$getY() - 22, 40, 20).build();
			Screens.getButtons(screen).add(button);
			ScreenEvents.beforeRender(screen).register((s, ctx, mx, my, delta) -> button.setPosition(panel.rummage$getX(), panel.rummage$getY() - 22));
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> load(client));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> highlights = List.of());

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
				literal("find").requires(src -> cheats()).then(argument("item", StringArgumentType.greedyString())
						.suggests((ctx, builder) -> CommandSource.suggestIdentifiers(Registries.ITEM.getIds(), builder))
						.executes(ctx -> {
							find(StringArgumentType.getString(ctx, "item"));
							return 1;
						}))));

		WorldRenderEvents.END_MAIN.register(ctx -> {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (!Config.INSTANCE.enabled || highlights.isEmpty() || mc.world == null || mc.player == null) return;
			if (!mc.world.getRegistryKey().getValue().toString().equals(highlightDim)) return;
			Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
			boolean xray = cheats();
			int color = Config.INSTANCE.currentColor();
			VertexConsumer lines = ctx.consumers().getBuffer(XRAY_LINES);
			MatrixStack.Entry matrix = ctx.matrices().peek();
			for (BlockPos pos : highlights) {
				if (!xray && blocked(mc.world, cam, pos)) continue;
				VoxelShape shape = mc.world.getBlockState(pos).getOutlineShape(mc.world, pos);
				Box box = (shape.isEmpty() ? VoxelShapes.fullCube() : shape).getBoundingBox().offset(pos).offset(cam.negate());
				drawFrontEdges(matrix, lines, box, color, Config.INSTANCE.thickness);
			}
			if (ctx.consumers() instanceof VertexConsumerProvider.Immediate immediate) immediate.draw(XRAY_LINES);
		});
	}

	static boolean hasHighlights() {
		return !highlights.isEmpty();
	}

	static void clearHighlights() {
		highlights = List.of();
	}

	private static void unhighlight(ClientWorld world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		BlockPos other = state.getBlock() instanceof ChestBlock && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE
				? pos.offset(ChestBlock.getFacing(state)) : pos;
		boolean ender = isEnder(world, pos);
		highlights = highlights.stream().filter(p -> !p.equals(pos) && !p.equals(other) && !(ender && isEnder(world, p))).toList();
	}

	private static void forgetBroken(ClientWorld world) {
		String dim = world.getRegistryKey().getValue().toString();
		if (!dim.equals(highlightDim)) return;
		List<BlockPos> broken = highlights.stream().filter(p -> world.isChunkLoaded(p) && !isContainer(world, p)).toList();
		if (broken.isEmpty()) return;
		highlights = highlights.stream().filter(p -> !broken.contains(p)).toList();
		broken.forEach(p -> containers.remove(dim + " " + p.toShortString()));
		save();
	}

	private static boolean isEnder(BlockView world, BlockPos pos) {
		return world.getBlockState(pos).isOf(Blocks.ENDER_CHEST);
	}

	private static boolean isContainer(BlockView world, BlockPos pos) {
		return world.getBlockEntity(pos) instanceof Inventory || isEnder(world, pos);
	}

	static boolean cheats() {
		var player = MinecraftClient.getInstance().player;
		return player != null && player.getPermissions().hasPermission(DefaultPermissions.GAMEMASTERS);
	}

	private static void drawFrontEdges(MatrixStack.Entry m, VertexConsumer vc, Box b, int color, float width) {
		double[] xs = {b.minX, b.maxX}, ys = {b.minY, b.maxY}, zs = {b.minZ, b.maxZ};
		boolean[] fx = {b.minX > 0, b.maxX < 0}, fy = {b.minY > 0, b.maxY < 0}, fz = {b.minZ > 0, b.maxZ < 0};
		for (int i = 0; i < 2; i++) {
			for (int j = 0; j < 2; j++) {
				if (fy[i] || fz[j]) line(m, vc, xs[0], ys[i], zs[j], xs[1], ys[i], zs[j], color, width);
				if (fx[i] || fz[j]) line(m, vc, xs[i], ys[0], zs[j], xs[i], ys[1], zs[j], color, width);
				if (fx[i] || fy[j]) line(m, vc, xs[i], ys[j], zs[0], xs[i], ys[j], zs[1], color, width);
			}
		}
	}

	private static void line(MatrixStack.Entry m, VertexConsumer vc, double x1, double y1, double z1, double x2, double y2, double z2, int color, float width) {
		Vector3f normal = new Vector3f((float) (x2 - x1), (float) (y2 - y1), (float) (z2 - z1)).normalize();
		vc.vertex(m, (float) x1, (float) y1, (float) z1).color(color).normal(m, normal).lineWidth(width);
		vc.vertex(m, (float) x2, (float) y2, (float) z2).color(color).normal(m, normal).lineWidth(width);
	}

	private static boolean blocked(ClientWorld world, Vec3d from, BlockPos target) {
		return BlockView.<Boolean, Object>raycast(from, Vec3d.ofCenter(target), null, (c, p) -> {
			if (p.equals(target) || isContainer(world, p)) return null;
			return world.getBlockState(p).isOpaqueFullCube() ? Boolean.TRUE : null;
		}, c -> Boolean.FALSE);
	}

	private static void record(String dim, BlockPos pos, ScreenHandler handler) {
		Map<String, Integer> items = new TreeMap<>();
		for (Slot slot : handler.slots) {
			if (slot.inventory instanceof PlayerInventory || !slot.hasStack()) continue;
			ItemStack stack = slot.getStack();
			items.merge(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), Integer::sum);
		}
		String key = dim + " " + pos.toShortString();
		if (isEnder(MinecraftClient.getInstance().world, pos)) {
			containers.replaceAll((k, e) -> e.ender() ? new Entry(e.dim(), e.x(), e.y(), e.z(), items, true) : e);
			containers.put(key, new Entry(dim, pos.getX(), pos.getY(), pos.getZ(), items, true));
		} else if (items.isEmpty()) containers.remove(key);
		else containers.put(key, new Entry(dim, pos.getX(), pos.getY(), pos.getZ(), items, false));
		save();
	}

	static void find(String query) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.player == null) return;
		Consumer<Text> out = mc.inGameHud.getChatHud()::addMessage;
		if (!Config.INSTANCE.enabled) {
			out.accept(Text.literal("Rummage is turned off (Mod Menu).").formatted(Formatting.RED));
			return;
		}
		var world = mc.world;
		String here = world.getRegistryKey().getValue().toString();
		if (containers.values().removeIf(e -> e.dim().equals(here) && world.isChunkLoaded(e.pos())
				&& !isContainer(world, e.pos()))) save();

		String q = query.toLowerCase(Locale.ROOT).trim();
		Identifier exact = Identifier.tryParse(q);
		String exactId = exact != null && Registries.ITEM.containsId(exact) ? exact.toString() : null;

		List<Hit> hits = new ArrayList<>();
		for (Entry e : containers.values()) {
			double dist = e.dim().equals(here) ? Math.sqrt(mc.player.getBlockPos().getSquaredDistance(e.pos())) : Double.MAX_VALUE;
			e.items().forEach((id, count) -> {
				String name = Registries.ITEM.get(Identifier.of(id)).getName().getString();
				boolean match = exactId != null ? id.equals(exactId) : name.toLowerCase(Locale.ROOT).contains(q) || id.contains(q);
				if (match) hits.add(new Hit(e, name, count, dist));
			});
		}

		if (hits.isEmpty()) {
			out.accept(Text.literal("No remembered container has \"" + query + "\"."));
			return;
		}
		hits.sort(Comparator.comparingDouble(Hit::dist));
		if (!cheats()) {
			long count = hits.stream().map(Hit::entry).distinct().count();
			out.accept(Text.literal("Found \"" + query + "\" in " + count + (count == 1 ? " container." : " containers.")));
		} else {
			for (Hit h : hits.subList(0, Math.min(10, hits.size()))) {
				Entry e = h.entry();
				String where = h.dist() == Double.MAX_VALUE ? " (" + e.dim() + ")" : " (" + Math.round(h.dist()) + "m)";
				out.accept(Text.literal(h.name() + " x" + h.count() + " at " + e.x() + " " + e.y() + " " + e.z() + where + (e.ender() ? " [Ender Chest]" : "")));
			}
			if (hits.size() > 10) out.accept(Text.literal("...and " + (hits.size() - 10) + " more."));
		}

		highlights = hits.stream().filter(h -> h.dist() != Double.MAX_VALUE).map(h -> h.entry().pos()).distinct().toList();
		highlightDim = here;
	}

	private static void load(MinecraftClient client) {
		String world = client.isIntegratedServerRunning()
				? "sp_" + client.getServer().getSavePath(WorldSavePath.ROOT).normalize().getFileName()
				: client.getCurrentServerEntry() != null ? "mp_" + client.getCurrentServerEntry().address : "unknown";
		file = FabricLoader.getInstance().getConfigDir().resolve("rummage")
				.resolve(world.replaceAll("[^a-zA-Z0-9._-]", "_") + ".json");
		containers = new HashMap<>();
		try {
			if (Files.exists(file)) {
				Map<String, Entry> loaded = GSON.fromJson(Files.readString(file), TYPE);
				if (loaded != null) containers.putAll(loaded);
			}
		} catch (IOException | JsonParseException e) {
			LOG.error("Couldn't read {}", file, e);
		}
	}

	private static void save() {
		if (file == null) return;
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(containers));
		} catch (IOException e) {
			LOG.error("Couldn't write {}", file, e);
		}
	}
}
