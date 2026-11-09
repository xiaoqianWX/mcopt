package mcopt.metal;

import java.util.Map;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * mcopt in Sodium's video settings (the sodium:config_api_user entrypoint):
 * <ul>
 * <li>Sodium's Graphics API option is replaced by one that names what each choice starts on a Mac ("Metal (mcopt)" instead of
 * "Default") and says what's running now and why. Same id and binding (the game's own option), so other mods' overlays on it
 * (Iris adds one) still apply.</li>
 * <li>An mcopt page: what's drawing and how mcopt sits under Sodium, the perf profile, and far terrain's settings (all saved
 * in config/mcopt.properties).</li>
 * </ul>
 * The renderer, the profile, far terrain itself and what it keeps per cell apply at the next launch (the renderer is picked
 * before the window opens, and those flags are read as classes load); far terrain's distance, detail, haze and servers
 * choice apply at once (FarTerrain).
 */
public final class SodiumPage implements ConfigEntryPoint {
	private static final String ID = "mcopt-metal";
	private static final Identifier GRAPHICS_API = Identifier.fromNamespaceAndPath("sodium", "general.graphics_api");
	private static final Identifier FAR = Identifier.fromNamespaceAndPath(ID, "far_terrain");
	private static final Identifier FAR_TEXTURES = Identifier.fromNamespaceAndPath(ID, "far_textures");
	private static final StorageEventHandler VANILLA = () -> Minecraft.getInstance().options.save();
	/** Pending writes to config/mcopt.properties, flushed together when Sodium applies. */
	private static final Map<String, String> pending = new java.util.TreeMap<>();
	private static final StorageEventHandler CONFIG = () -> {
		synchronized (pending) {
			if (pending.isEmpty()) return;
			Profile.write(pending);
			pending.clear();
		}
	};

	/** What "Running now" shows: the backend this launch draws with. */
	public enum Running {
		METAL, OPENGL, VULKAN, OTHER;

		static Running now() {
			String name = Renderer.running();
			if (name == null) return OTHER;
			return switch (name) {
				case "Metal" -> METAL;
				case "OpenGL" -> OPENGL;
				case "Vulkan" -> VULKAN;
				default -> OTHER;
			};
		}
	}

	@Override
	public void registerConfigLate(ConfigBuilder builder) {
		builder.registerOwnModOptions()
			.setName("mcopt")
			.setColorTheme(builder.createColorTheme().setBaseThemeRGB(0xFF6FA8DC))
			.registerOptionReplacement(GRAPHICS_API, graphicsApi(builder))
			.addPage(builder.createOptionPage()
				.setName(Component.literal("mcopt"))
				.addOptionGroup(builder.createOptionGroup()
					.setName(Component.literal("Renderer"))
					.addOption(running(builder)))
				.addOptionGroup(builder.createOptionGroup()
					.setName(Component.literal("Optimizations"))
					.addOption(profile(builder)))
				.addOptionGroup(builder.createOptionGroup()
					.setName(Component.literal("Far terrain"))
					.addOption(farTerrain(builder))
					.addOption(farDistance(builder))
					.addOption(farDetail(builder))
					.addOption(farHaze(builder))
					.addOption(farServers(builder))
					.addOption(farTextures(builder))
					.addOption(farPlants(builder))
					.addOption(farForget(builder))));
	}

	private static OptionBuilder graphicsApi(ConfigBuilder builder) {
		return builder.createEnumOption(GRAPHICS_API, PreferredGraphicsApi.class)
			.setStorageHandler(VANILLA)
			.setName(Component.translatable("options.graphicsApi"))
			.setTooltip(api -> Component.literal(choiceText(api) + "\n\n" + runningText()))
			.setElementNameProvider(api -> Component.literal(Renderer.label(api)))
			.setDefaultValue(PreferredGraphicsApi.DEFAULT)
			.setFlags(OptionFlag.REQUIRES_GAME_RESTART)
			.setBinding(api -> Minecraft.getInstance().options.preferredGraphicsBackend().set(api),
				() -> Minecraft.getInstance().options.preferredGraphicsBackend().get());
	}

	private static String choiceText(PreferredGraphicsApi api) {
		String blocked = Renderer.metalBlocked();
		return switch (api) {
			case DEFAULT -> blocked == null
				? "Metal (mcopt): mcopt's own renderer for Apple Silicon. Sodium still builds, sorts and culls the chunks; mcopt draws them, and everything else, straight on Metal. If Metal can't start, the game falls back to OpenGL, then Vulkan."
				: "Default: Metal would come first, but it's off for this launch: " + blocked + ". The game starts OpenGL, then Vulkan if OpenGL fails.";
			case OPENGL -> "OpenGL: Apple's OpenGL 4.1, which macOS runs on top of Metal. Pick it for mods that call OpenGL directly. mcopt's other optimizations stay on.";
			case VULKAN -> "Vulkan (MoltenVK): Mojang's experimental Vulkan renderer, translated to Metal by MoltenVK. mcopt's other optimizations stay on.";
		};
	}

	/** "Running now: X." plus why, when it isn't what the option asked for. Applies after a restart. */
	private static String runningText() {
		String running = Renderer.running();
		StringBuilder s = new StringBuilder("Running now: ").append(Renderer.describe(running)).append('.');
		PreferredGraphicsApi chosen = Minecraft.getInstance().options.preferredGraphicsBackend().get();
		if (chosen == PreferredGraphicsApi.DEFAULT && Renderer.metalBlocked() == null && running != null && !running.equals("Metal")) {
			s.append(" Metal was asked for but didn't start; the game log says why.");
		}
		return s.append(" A change applies after a restart.").toString();
	}

	private static OptionBuilder running(ConfigBuilder builder) {
		return builder.createEnumOption(Identifier.fromNamespaceAndPath(ID, "running"), Running.class)
			.setStorageHandler(() -> { })
			.setName(Component.literal("Running now"))
			.setTooltip(r -> Component.literal(runningText() + "\n\n" + howItWorks()))
			.setElementNameProvider(r -> Component.literal(switch (r) {
				case METAL -> "Metal (mcopt)";
				case OPENGL -> "OpenGL";
				case VULKAN -> "Vulkan (MoltenVK)";
				case OTHER -> Renderer.describe(Renderer.running());
			}))
			// read-only, but not disabled: Sodium strikes disabled options through and hides their value
			.setAllowedValues(java.util.EnumSet.of(Running.now()))
			.setDefaultValue(Running.now())
			.setBinding(r -> { }, Running::now);
	}

	private static String howItWorks() {
		StringBuilder s = new StringBuilder(
			"How mcopt works with Sodium: Sodium builds, sorts and culls the chunk meshes as usual. On Metal, mcopt replaces the layer "
			+ "underneath, the game's GPU backend, so Sodium's draws (and the rest of the game's) go to Metal directly, and its ~2000 "
			+ "terrain draws a frame become a few. mcopt's CPU-side patches to Sodium and the game work on every backend. "
			+ "Pick the renderer with Graphics API on Sodium's General page.");
		if (FabricLoader.getInstance().isModLoaded("distanthorizons")) {
			s.append("\n\nDistant Horizons: ").append(Renderer.metalBlocked() == null
				? "draws with its Blaze3D renderer, which runs on Metal."
				: "uses OpenGL, so Metal is off.");
		}
		return s.toString();
	}

	private static OptionBuilder profile(ConfigBuilder builder) {
		String active = Profile.active();
		boolean fromCommandLine = System.getProperty("mcopt.profile") != null; // Profile itself only reads "profile" from the file
		boolean custom = !active.equals("alpha") && !active.equals("none");
		String tier = Profile.smallTier()
			? "This Mac is on the small tier (under 10 GPU cores or 8 GB of memory), so the bigger chunk cache stays out."
			: "This Mac gets the full set.";
		return builder.createBooleanOption(Identifier.fromNamespaceAndPath(ID, "profile"))
			.setStorageHandler(CONFIG)
			.setName(Component.literal("Performance profile"))
			.setTooltip(on -> Component.literal(
				"mcopt's measured speed-ups (the alpha profile): skips rewriting unchanged region files, faster chunk meshing, a "
				+ "bigger Sodium chunk-copy cache, a cached render-list walk, culling recovery, sign text in one draw per glyph page instead of one per change of page (glowing and Chinese signs above all), and a lazily created narrator. "
				+ tier + "\n\nOff: only the renderer you picked, without these. Saved as profile=" + (on ? "alpha" : "none")
				+ " in config/mcopt.properties; applies after a restart."
				+ (fromCommandLine ? "\n\nSet on the command line (-Dmcopt.profile), which wins over this." : "")
				+ (custom ? "\n\nconfig/mcopt.properties names another profile (" + active + "); edit the file to change it." : "")))
			.setImpact(OptionImpact.MEDIUM)
			.setDefaultValue(true)
			.setEnabled(!fromCommandLine && !custom)
			.setFlags(OptionFlag.REQUIRES_GAME_RESTART)
			.setBinding(on -> stage("profile", on ? "alpha" : "none"), () -> !nextLaunch("profile", Profile.active()).equals("none"));
	}

	private static OptionBuilder farTerrain(ConfigBuilder builder) {
		boolean dh = FabricLoader.getInstance().isModLoaded("distanthorizons");
		boolean metal = Renderer.runningMetal();
		return builder.createBooleanOption(FAR)
			.setStorageHandler(CONFIG)
			.setName(Component.literal("Far terrain (experimental)"))
			.setTooltip(on -> Component.literal(
				"mcopt's own distant terrain past the render distance, drawn on Metal. In singleplayer it's generated from the world "
				+ "itself. On a server it shows only what you've been near: the chunks the server sends are kept, so it grows as you "
				+ "explore, and the fog stays where it is until there's terrain to show. Nothing extra is asked of the server. Far "
				+ "away, a build shows where it covers at least half of a far-terrain cell (cells get coarser with distance). "
				+ "Experimental: on Macs with fewer than 10 GPU cores it costs most of the fps. Saved as mcopt.lod in "
				+ "config/mcopt.properties; applies after a restart. The settings under it apply at once unless they say otherwise."
				+ (dh ? "\n\nOff while Distant Horizons is installed: it draws the far terrain." : "")
				+ (!dh && !metal ? "\n\nNeeds the Metal renderer." : "")))
			.setImpact(OptionImpact.HIGH)
			.setDefaultValue(false)
			.setEnabled(!dh && metal)
			.setFlags(OptionFlag.REQUIRES_GAME_RESTART)
			.setBinding(on -> stage("mcopt.lod", on ? "true" : "false"),
				() -> Boolean.parseBoolean(nextLaunch("mcopt.lod", String.valueOf(Boolean.getBoolean("mcopt.lod")))));
	}

	/** Far terrain can run on this Mac this launch (the settings under it still save for later when not). */
	private static boolean farAvailable() {
		return !FabricLoader.getInstance().isModLoaded("distanthorizons") && Renderer.runningMetal();
	}

	private static OptionBuilder farDistance(ConfigBuilder builder) {
		return builder.createIntegerOption(Identifier.fromNamespaceAndPath(ID, "far_distance"))
			.setStorageHandler(CONFIG)
			.setName(Component.literal("Far distance"))
			.setTooltip(Component.literal(
				"How far far terrain reaches, in chunks. Farther costs GPU time (mostly with the size of the screen) and memory, and in "
				+ "singleplayer the time to generate it (on its own threads, what's in view first). On a server the fog only reaches "
				+ "as far as what you've explored. Saved as mcopt.lod.distance; applies at once."))
			.setImpact(OptionImpact.HIGH)
			.setRange(FarTerrain.MIN_DISTANCE, FarTerrain.MAX_DISTANCE, FarTerrain.DISTANCE_STEP)
			.setValueFormatter(v -> Component.literal(v + " chunks"))
			.setDefaultValue(FarTerrain.DEFAULT_DISTANCE)
			.setEnabledProvider(state -> farAvailable() && state.readBooleanOption(FAR), FAR)
			.setBinding(v -> {
				FarTerrain.setDistance(v);
				stage("mcopt.lod.distance", String.valueOf(v));
			}, () -> {
				int v = Math.clamp(FarTerrain.distanceChunks(), FarTerrain.MIN_DISTANCE, FarTerrain.MAX_DISTANCE);
				return FarTerrain.MIN_DISTANCE + Math.round((v - FarTerrain.MIN_DISTANCE) / (float) FarTerrain.DISTANCE_STEP) * FarTerrain.DISTANCE_STEP;
			});
	}

	private static OptionBuilder farDetail(ConfigBuilder builder) {
		return builder.createEnumOption(Identifier.fromNamespaceAndPath(ID, "far_detail"), FarTerrain.Detail.class)
			.setStorageHandler(CONFIG)
			.setName(Component.literal("Far detail"))
			.setTooltip(d -> {
				int at = FarTerrain.distanceChunks();
				return Component.literal(
					"How far out far terrain keeps block-sized cells before coarser ones take over (each level's cells twice as wide, "
					+ "reaching twice as far): to about " + (d.n / 2 - 68) + " blocks here. Higher stays sharper further out and costs "
					+ "GPU time and memory, about " + FarTerrain.memoryMB(d.n, at) + " MB at this distance (Low " + FarTerrain.memoryMB(1024, at)
					+ ", Medium " + FarTerrain.memoryMB(2048, at) + ", High " + FarTerrain.memoryMB(4096, at) + "). Saved as mcopt.lod.n; "
					+ "applies at once.");
			})
			.setElementNameProvider(d -> Component.literal(switch (d) {
				case LOW -> "Low";
				case MEDIUM -> "Medium";
				case HIGH -> "High";
			}))
			.setImpact(OptionImpact.HIGH)
			.setDefaultValue(FarTerrain.Detail.MEDIUM)
			.setEnabledProvider(state -> farAvailable() && state.readBooleanOption(FAR), FAR)
			.setBinding(d -> {
				FarTerrain.setDetail(d);
				stage("mcopt.lod.n", String.valueOf(d.n));
			}, FarTerrain::detail);
	}

	private static OptionBuilder farHaze(ConfigBuilder builder) {
		return builder.createEnumOption(Identifier.fromNamespaceAndPath(ID, "far_haze"), FarTerrain.Haze.class)
			.setStorageHandler(CONFIG)
			.setName(Component.literal("Far haze"))
			.setTooltip(h -> Component.literal(
				"How much the game's distance haze fades far terrain into the sky. Normal: fully by the far terrain's edge, as the "
				+ "game hazes its own terrain by 1024 blocks. Light: half as much. Heavy: fully by 60% of the way. Off: clear up to "
				+ "the edge, where it still fades out. Rain and fog stay as the game makes them. Saved as mcopt.lod.haze; applies at "
				+ "once."))
			.setElementNameProvider(h -> Component.literal(switch (h) {
				case OFF -> "Off";
				case LIGHT -> "Light";
				case NORMAL -> "Normal";
				case HEAVY -> "Heavy";
			}))
			.setImpact(OptionImpact.LOW)
			.setDefaultValue(FarTerrain.Haze.NORMAL)
			.setEnabledProvider(state -> farAvailable() && state.readBooleanOption(FAR), FAR)
			.setBinding(h -> {
				FarTerrain.setHaze(h);
				stage("mcopt.lod.haze", h.name().toLowerCase(java.util.Locale.ROOT));
			}, FarTerrain::haze);
	}

	private static OptionBuilder farServers(ConfigBuilder builder) {
		return builder.createEnumOption(Identifier.fromNamespaceAndPath(ID, "far_servers"), FarTerrain.Servers.class)
			.setStorageHandler(CONFIG)
			.setName(Component.literal("Far terrain on servers"))
			.setTooltip(v -> Component.literal(
				"A server's world can't be generated on your Mac, so there far terrain is made of the chunks the server sends you. "
				+ "Saved: they're kept on disk per server address and dimension (in mcopt-lod/servers), so what you explored before "
				+ "is there next time. This session: kept only until you leave, nothing written to disk. Off: no far terrain on "
				+ "servers. Nothing extra is asked of the server either way. Saved as mcopt.lod.servers; applies at once."))
			.setElementNameProvider(v -> Component.literal(switch (v) {
				case OFF -> "Off";
				case SESSION -> "This session";
				case SAVED -> "Saved";
			}))
			.setImpact(OptionImpact.LOW)
			.setDefaultValue(FarTerrain.Servers.SAVED)
			.setEnabledProvider(state -> farAvailable() && state.readBooleanOption(FAR), FAR)
			.setBinding(v -> {
				FarTerrain.setServers(v);
				stage("mcopt.lod.servers", v.name().toLowerCase(java.util.Locale.ROOT));
			}, FarTerrain::servers);
	}

	private static OptionBuilder farTextures(ConfigBuilder builder) {
		return builder.createBooleanOption(FAR_TEXTURES)
			.setStorageHandler(CONFIG)
			.setName(Component.literal("Far block textures"))
			.setTooltip(on -> Component.literal(
				"Far terrain's finest level drawn with the blocks' own textures, fading into their average color as the blocks "
				+ "shrink to a pixel. Off: flat colors, a little faster and lighter on memory. Saved as mcopt.lod.textures; applies "
				+ "after a restart."))
			.setImpact(OptionImpact.MEDIUM)
			.setDefaultValue(true)
			.setFlags(OptionFlag.REQUIRES_GAME_RESTART)
			.setEnabledProvider(state -> farAvailable() && state.readBooleanOption(FAR), FAR)
			.setBinding(on -> stage("mcopt.lod.textures", String.valueOf(on)),
				() -> !"false".equals(nextLaunch("mcopt.lod.textures", System.getProperty("mcopt.lod.textures", "true"))));
	}

	private static OptionBuilder farPlants(ConfigBuilder builder) {
		return builder.createBooleanOption(Identifier.fromNamespaceAndPath(ID, "far_plants"))
			.setStorageHandler(CONFIG)
			.setName(Component.literal("Far plants"))
			.setTooltip(on -> Component.literal(
				"Grass, flowers and ferns on far terrain's finest level, where the real chunks have them (needs block textures). "
				+ "Off: a little faster. Saved as mcopt.lod.plants; applies after a restart."))
			.setImpact(OptionImpact.LOW)
			.setDefaultValue(true)
			.setFlags(OptionFlag.REQUIRES_GAME_RESTART)
			.setEnabledProvider(state -> farAvailable() && state.readBooleanOption(FAR) && state.readBooleanOption(FAR_TEXTURES), FAR, FAR_TEXTURES)
			.setBinding(on -> stage("mcopt.lod.plants", String.valueOf(on)),
				() -> !"false".equals(nextLaunch("mcopt.lod.plants", System.getProperty("mcopt.lod.plants", "true"))));
	}

	/** Forget: drops what far terrain keeps of the world or server the player is in (after asking) and starts it over. */
	private static OptionBuilder farForget(ConfigBuilder builder) {
		return builder.createExternalButtonOption(Identifier.fromNamespaceAndPath(ID, "far_forget"))
			.setName(Component.literal("Forget this world's far terrain"))
			.setTooltip(Component.literal(
				"Deletes what far terrain has kept of the world or server you're in (every dimension of it, in mcopt-lod) and starts "
				+ "over from what's around you: in singleplayer it's generated again, on a server it grows back as you explore. For a "
				+ "server that changed its map, or far terrain that looks wrong. Asks first."))
			.setEnabled(farAvailable() && FarTerrain.running())
			.setScreenConsumer(SodiumPage::confirmForget);
	}

	private static void confirmForget(net.minecraft.client.gui.screens.Screen parent) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			mc.gui.setScreen(new net.minecraft.client.gui.screens.AlertScreen(() -> mc.gui.setScreen(parent), Component.literal("Not in a world"),
				Component.literal("Join the world or server whose far terrain you want to forget, then press this again.")));
			return;
		}
		var server = mc.getSingleplayerServer();
		var data = mc.getCurrentServer();
		String name = server != null ? server.getWorldData().getLevelName() : data != null ? data.name : "this server";
		mc.gui.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(yes -> {
			if (yes) FarTerrain.forget();
			mc.gui.setScreen(parent);
		}, Component.literal("Forget the far terrain of " + name + "?"), Component.literal(server != null
			? "What mcopt has kept of it is deleted, and it's generated again around you."
			: "What mcopt has kept of it is deleted. It grows back as you explore.")));
	}

	private static void stage(String key, String value) {
		synchronized (pending) {
			pending.put(key, value);
		}
	}

	/** What the next launch reads for key: a staged change, else the file, else this launch's value. The controls show that, so an applied change stays shown. */
	private static String nextLaunch(String key, String current) {
		synchronized (pending) {
			if (pending.containsKey(key)) return pending.get(key);
		}
		String file = Profile.fileValue(key);
		return file.isEmpty() ? current : file;
	}
}
