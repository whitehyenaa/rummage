package rummage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rummage.mixin.HandledScreenAccessor;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public class Rummage implements ClientModInitializer {
	static final Logger LOG = LoggerFactory.getLogger("rummage");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type TYPE = new TypeToken<Map<String, Entry>>() {}.getType();

	private static final RenderType XRAY_LINES = RenderType.create("rummage_xray_lines", RenderSetup.builder(
			RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
					.withLocation(Identifier.fromNamespaceAndPath("rummage", "pipeline/xray_lines"))
					.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
					.build())).createRenderSetup());

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

		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() && isContainer(level, hit.getBlockPos())) lastClicked = hit.getBlockPos();
			return InteractionResult.PASS;
		});

		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			BlockPos pos = lastClicked;
			lastClicked = null;
			if (!Config.INSTANCE.enabled || pos == null || !(screen instanceof AbstractContainerScreen<?> cs)) return;
			String dim = dim(client.level);
			if (Config.INSTANCE.autoClear) unhighlight(client.level, pos);
			ScreenEvents.remove(screen).register(s -> record(dim, pos, cs.getMenu()));
		});

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("rummage", "main"));
		KeyMapping findKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rummage.find", GLFW.GLFW_KEY_F9, category));
		KeyMapping clearKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rummage.clear", GLFW.GLFW_KEY_F10, category));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.level != null && !highlights.isEmpty()) forgetBroken(client.level);
			while (findKey.consumeClick()) {
				if (Config.INSTANCE.enabled && client.screen == null) client.setScreen(new FindScreen(null));
			}
			while (clearKey.consumeClick()) {
				if (client.player != null && hasHighlights()) {
					clearHighlights();
					client.player.sendOverlayMessage(Component.literal("Outlines cleared"));
				}
			}
		});

		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!Config.INSTANCE.enabled || !(screen instanceof InventoryScreen)) return;
			HandledScreenAccessor panel = (HandledScreenAccessor) screen;
			Button button = Button.builder(Component.literal("Find"), b -> client.setScreen(new FindScreen(screen)))
					.bounds(panel.rummage$getX(), panel.rummage$getY() - 22, 40, 20).build();
			Screens.getWidgets(screen).add(button);
			ScreenEvents.beforeTick(screen).register(s -> button.setPosition(panel.rummage$getX(), panel.rummage$getY() - 22));
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> load(client));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> highlights = List.of());

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
				literal("find").requires(src -> cheats()).then(argument("item", StringArgumentType.greedyString())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.ITEM.keySet(), builder))
						.executes(ctx -> {
							find(StringArgumentType.getString(ctx, "item"));
							return 1;
						}))));

		LevelRenderEvents.END_MAIN.register(ctx -> {
			Minecraft mc = Minecraft.getInstance();
			if (!Config.INSTANCE.enabled || highlights.isEmpty() || mc.level == null || mc.player == null) return;
			if (!dim(mc.level).equals(highlightDim)) return;
			Vec3 cam = mc.gameRenderer.getMainCamera().position();
			boolean xray = cheats();
			int color = Config.INSTANCE.currentColor();
			MultiBufferSource.BufferSource buffers = ctx.bufferSource();
			VertexConsumer lines = buffers.getBuffer(XRAY_LINES);
			PoseStack.Pose pose = ctx.poseStack().last();
			for (BlockPos pos : highlights) {
				if (!xray && blocked(mc.level, cam, pos)) continue;
				VoxelShape shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
				AABB box = (shape.isEmpty() ? Shapes.block() : shape).bounds().move(pos).move(cam.reverse());
				drawFrontEdges(pose, lines, box, color, Config.INSTANCE.thickness);
			}
			buffers.endBatch(XRAY_LINES);
		});
	}

	private static String dim(Level level) {
		return level.dimension().identifier().toString();
	}

	static boolean hasHighlights() {
		return !highlights.isEmpty();
	}

	static void clearHighlights() {
		highlights = List.of();
	}

	private static void unhighlight(ClientLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		BlockPos other = state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE
				? pos.relative(ChestBlock.getConnectedDirection(state)) : pos;
		boolean ender = isEnder(level, pos);
		highlights = highlights.stream().filter(p -> !p.equals(pos) && !p.equals(other) && !(ender && isEnder(level, p))).toList();
	}

	private static void forgetBroken(ClientLevel level) {
		String dim = dim(level);
		if (!dim.equals(highlightDim)) return;
		List<BlockPos> broken = highlights.stream().filter(p -> level.isLoaded(p) && !isContainer(level, p)).toList();
		if (broken.isEmpty()) return;
		highlights = highlights.stream().filter(p -> !broken.contains(p)).toList();
		broken.forEach(p -> containers.remove(dim + " " + p.toShortString()));
		save();
	}

	private static boolean isEnder(BlockGetter level, BlockPos pos) {
		return level.getBlockState(pos).is(Blocks.ENDER_CHEST);
	}

	private static boolean isContainer(BlockGetter level, BlockPos pos) {
		return level.getBlockEntity(pos) instanceof Container || isEnder(level, pos);
	}

	static boolean cheats() {
		var player = Minecraft.getInstance().player;
		return player != null && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	private static void drawFrontEdges(PoseStack.Pose m, VertexConsumer vc, AABB b, int color, float width) {
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

	private static void line(PoseStack.Pose m, VertexConsumer vc, double x1, double y1, double z1, double x2, double y2, double z2, int color, float width) {
		Vector3f normal = new Vector3f((float) (x2 - x1), (float) (y2 - y1), (float) (z2 - z1)).normalize();
		vc.addVertex(m, (float) x1, (float) y1, (float) z1).setColor(color).setNormal(m, normal).setLineWidth(width);
		vc.addVertex(m, (float) x2, (float) y2, (float) z2).setColor(color).setNormal(m, normal).setLineWidth(width);
	}

	private static boolean blocked(ClientLevel level, Vec3 from, BlockPos target) {
		return BlockGetter.<Boolean, Object>traverseBlocks(from, Vec3.atCenterOf(target), null, (c, p) -> {
			if (p.equals(target) || isContainer(level, p)) return null;
			return level.getBlockState(p).isSolidRender() ? Boolean.TRUE : null;
		}, c -> Boolean.FALSE);
	}

	private static void record(String dim, BlockPos pos, AbstractContainerMenu menu) {
		Map<String, Integer> items = new TreeMap<>();
		for (Slot slot : menu.slots) {
			if (slot.container instanceof Inventory || !slot.hasItem()) continue;
			ItemStack stack = slot.getItem();
			items.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
		}
		String key = dim + " " + pos.toShortString();
		if (isEnder(Minecraft.getInstance().level, pos)) {
			containers.replaceAll((k, e) -> e.ender() ? new Entry(e.dim(), e.x(), e.y(), e.z(), items, true) : e);
			containers.put(key, new Entry(dim, pos.getX(), pos.getY(), pos.getZ(), items, true));
		} else if (items.isEmpty()) containers.remove(key);
		else containers.put(key, new Entry(dim, pos.getX(), pos.getY(), pos.getZ(), items, false));
		save();
	}

	static void find(String query) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return;
		Consumer<Component> out = mc.gui.getChat()::addClientSystemMessage;
		if (!Config.INSTANCE.enabled) {
			out.accept(Component.literal("Rummage is turned off (Mod Menu).").withStyle(ChatFormatting.RED));
			return;
		}
		var level = mc.level;
		String here = dim(level);
		if (containers.values().removeIf(e -> e.dim().equals(here) && level.isLoaded(e.pos())
				&& !isContainer(level, e.pos()))) save();

		String q = query.toLowerCase(Locale.ROOT).trim();
		Identifier exact = Identifier.tryParse(q);
		String exactId = exact != null && BuiltInRegistries.ITEM.containsKey(exact) ? exact.toString() : null;

		List<Hit> hits = new ArrayList<>();
		for (Entry e : containers.values()) {
			double dist = e.dim().equals(here) ? Math.sqrt(mc.player.blockPosition().distSqr(e.pos())) : Double.MAX_VALUE;
			e.items().forEach((id, count) -> {
				String name = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id))).getHoverName().getString();
				boolean match = exactId != null ? id.equals(exactId) : name.toLowerCase(Locale.ROOT).contains(q) || id.contains(q);
				if (match) hits.add(new Hit(e, name, count, dist));
			});
		}

		if (hits.isEmpty()) {
			out.accept(Component.literal("No remembered container has \"" + query + "\"."));
			return;
		}
		hits.sort(Comparator.comparingDouble(Hit::dist));
		if (!cheats()) {
			long count = hits.stream().map(Hit::entry).distinct().count();
			out.accept(Component.literal("Found \"" + query + "\" in " + count + (count == 1 ? " container." : " containers.")));
		} else {
			for (Hit h : hits.subList(0, Math.min(10, hits.size()))) {
				Entry e = h.entry();
				String where = h.dist() == Double.MAX_VALUE ? " (" + e.dim() + ")" : " (" + Math.round(h.dist()) + "m)";
				out.accept(Component.literal(h.name() + " x" + h.count() + " at " + e.x() + " " + e.y() + " " + e.z() + where + (e.ender() ? " [Ender Chest]" : "")));
			}
			if (hits.size() > 10) out.accept(Component.literal("...and " + (hits.size() - 10) + " more."));
		}

		highlights = hits.stream().filter(h -> h.dist() != Double.MAX_VALUE).map(h -> h.entry().pos()).distinct().toList();
		highlightDim = here;
	}

	private static void load(Minecraft client) {
		String world = client.hasSingleplayerServer()
				? "sp_" + client.getSingleplayerServer().getWorldPath(LevelResource.ROOT).normalize().getFileName()
				: client.getCurrentServer() != null ? "mp_" + client.getCurrentServer().ip : "unknown";
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
