package mcopt.bench;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceInfo;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Util;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;

/**
 * The fps benchmark (tools/bench.py drives it; active only with mcopt-bench/run.properties, see BenchMixinPlugin).
 * From the title screen it opens (or first creates) a fixed-seed world, makes it deterministic (spectator, noon, clear,
 * no mob spawns), puts the camera at a fixed start point and runs the phases: settle (until the terrain is built), still,
 * capture (screenshot, not measured), spin (one 360 degree turn), flight (straight line). The camera pose is a function of
 * the wall-clock time within the phase, so runs at different fps follow the same path. Writes
 * mcopt-bench/results/LABEL-TIMESTAMP.json (+ .png) and quits.
 */
public final class Bench {
	/** The current phase, read reflectively by mcopt (Cpu.phase(), the recorder): idle, settle, still, capture, spin, flight, done. */
	public static volatile String phase = "idle";

	private static final int SETTLE = 0, STILL = 1, CAPTURE = 2, SPIN = 3, FLIGHT = 4, DONE = 5;
	/** Terrain built and chunk counts unchanged this long before settle may end. */
	private static final long STABLE_NS = 2_000_000_000L;
	private static final long CAPTURE_TIMEOUT_NS = 30_000_000_000L;

	private enum State { BOOT, UI, LOADING, SETUP, RUNNING, FINISHING, STOPPED }

	private static State state = State.BOOT;
	private static RunConfig cfg;
	private static String stamp, startedAt;
	private static Path results;
	private static boolean worldCreated;
	private static long loadStartNs, loadNs;
	private static CompletableFuture<double[]> setup;
	// the start pose (sx, sy, sz, the ground under it) and the flight direction
	private static double sx, sy, sz, ground, dx, dz;
	private static Phase[] phases;
	private static int cur = -1;
	private static long lastFrameNs;
	// settle: set by the tick, read by the frame hook
	private static boolean settled, settleTimedOut;
	private static long stableSince;
	private static int lastChunks = -1, lastVisible = -2, settleChunks, settleVisible;
	private static long nextSettleLog;
	private static volatile boolean shotDone;
	private static volatile String shotError;
	private static Path shotFile;
	private static int interruptions;
	private static Map<String, Object> options, window;

	private Bench() {
	}

	/** The cursor position UiShots holds (GUI-scaled), NaN when it holds none; for MouseHandlerMixin. */
	public static double uiMouseX() {
		return UiShots.mouseX;
	}

	public static double uiMouseY() {
		return UiShots.mouseY;
	}

	static void log(String s) {
		System.out.println("[mcopt-bench] " + s);
	}

	/** Client tick (20 Hz, render thread): everything that isn't per frame. */
	public static void tick(Minecraft mc) {
		try {
			switch (state) {
				case BOOT -> boot(mc);
				case UI -> {
					if (!UiShots.tick(mc)) return;
					state = State.STOPPED;
					if (cfg.quit) mc.stop();
				}
				case LOADING -> {
					if (mc.level == null || mc.player == null || mc.gui.screen() != null || mc.getSingleplayerServer() == null) return;
					loadNs = System.nanoTime() - loadStartNs;
					log(String.format("in the world after %.1f s, setting it up", loadNs / 1e9));
					IntegratedServer server = mc.getSingleplayerServer();
					setup = server.submit(() -> setupServer(server));
					state = State.SETUP;
				}
				case SETUP -> {
					if (!setup.isDone()) return;
					double[] start = setup.join();
					sx = start[0];
					sy = start[1];
					sz = start[2];
					ground = start[3];
					double yawRad = Math.toRadians(cfg.yaw);
					dx = -Math.sin(yawRad);
					dz = Math.cos(yawRad);
					if (cfg.hideGui && !mc.gui.hud.isHidden()) mc.gui.hud.toggle();
					log(String.format("start %.1f %.1f %.1f (ground %d), yaw %.1f pitch %.1f; settling", sx, sy, sz, (int) ground, cfg.yaw, cfg.pitch));
					long now = System.nanoTime();
					phases[SETTLE].begin(now);
					stableSince = now;
					nextSettleLog = now + 5_000_000_000L;
					cur = SETTLE;
					phase = "settle";
					state = State.RUNNING;
				}
				case RUNNING -> {
					// a key press in the window (Esc, chat) would open a screen, and a pausing one would stop the server
					Screen screen = mc.gui.screen();
					if (screen != null) {
						interruptions++;
						log("closing " + screen.getClass().getSimpleName() + " (opened during the run)");
						mc.gui.setScreen(null);
					}
					if (cur == SETTLE) checkSettle(mc);
				}
				case FINISHING -> {
					state = State.STOPPED;
					write(mc, null);
					if (cfg.quit) mc.stop();
				}
				case STOPPED -> {
				}
			}
		} catch (Throwable t) {
			fail(mc, t);
		}
	}

	private static void boot(Minecraft mc) throws Exception {
		if (mc.gui.overlay() != null) return;
		Screen screen = mc.gui.screen();
		if (!(screen instanceof TitleScreen) && !(screen instanceof AccessibilityOnboardingScreen)) return;
		cfg = RunConfig.load();
		stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		startedAt = OffsetDateTime.now().toString();
		results = RunConfig.DIR.resolve("results");
		Files.createDirectories(results);
		phases = new Phase[] {
			new Phase("settle", cfg.settleMax, false),
			new Phase("still", cfg.still, true),
			new Phase("capture", 0, false),
			new Phase("spin", cfg.spin, true),
			new Phase("flight", cfg.flight, true)
		};
		log(String.format("%s: title screen after %.1f s, %s", cfg.label, ManagementFactory.getRuntimeMXBean().getUptime() / 1e3, cfg.toMap()));
		if (!cfg.ui.isEmpty()) {
			state = State.UI;
			UiShots.start(mc, cfg.ui, results, cfg.label + "-" + stamp);
			return;
		}
		applyOptions(mc);
		if (cfg.fullscreen) mc.getWindow().setFullscreen(true);
		else if (cfg.windowWidth > 0 && cfg.windowHeight > 0) mc.getWindow().setWindowed(cfg.windowWidth, cfg.windowHeight);
		loadStartNs = System.nanoTime();
		worldCreated = !mc.getLevelSource().levelExists(cfg.world);
		state = State.LOADING;
		if (worldCreated) {
			// the first run creates the world: its numbers include generation, tools/bench.py discards it as the warm-up
			log("creating world " + cfg.world + " (seed " + cfg.seed + ")");
			LevelSettings settings = new LevelSettings(
				cfg.world, GameType.SPECTATOR, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
			mc.createWorldOpenFlows().createFreshLevel(cfg.world, settings, new WorldOptions(cfg.seed, true, false), WorldPresets::createNormalWorldDimensions, screen);
		} else {
			log("opening world " + cfg.world);
			mc.createWorldOpenFlows().openWorld(cfg.world, () -> mc.gui.setScreen(new TitleScreen()));
		}
	}

	/** The instance is dedicated to the bench, so these are saved too. */
	private static void applyOptions(Minecraft mc) {
		Options o = mc.options;
		o.renderDistance().set(cfg.renderDistance);
		o.enableVsync().set(false);
		o.framerateLimit().set(260); // 260 = unlimited
		// AFK would drop to 30 fps a minute after the last input, and nobody touches the game during a run
		o.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
		o.pauseOnLostFocus = false;
		o.fov().set(70);
		o.fovEffectScale().set(0.0); // no FOV change from flying
		o.bobView().set(false);
		o.guiScale().set(2);
		o.fullscreen().set(cfg.fullscreen);
		o.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0); // runs happen while the Mac is used for other things
		o.onboardAccessibility = false;
		o.tutorialStep = TutorialSteps.NONE;
		mc.getTutorial().setStep(TutorialSteps.NONE);
		o.save();
	}

	/** Server thread: deterministic world state and the start point. Returns x, y, z, ground. */
	private static double[] setupServer(MinecraftServer server) {
		GameRules rules = server.getGameRules();
		rules.set(GameRules.ADVANCE_TIME, false, server);
		rules.set(GameRules.ADVANCE_WEATHER, false, server);
		rules.set(GameRules.SPAWN_MOBS, false, server);
		rules.set(GameRules.SPAWN_MONSTERS, false, server);
		rules.set(GameRules.SPAWN_PATROLS, false, server);
		rules.set(GameRules.SPAWN_PHANTOMS, false, server);
		rules.set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
		rules.set(GameRules.SPAWN_WARDENS, false, server);
		CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
		server.getCommands().performPrefixedCommand(source, "time set noon");
		server.getCommands().performPrefixedCommand(source, "weather clear");
		ServerLevel level = server.overworld();
		level.getChunk(cfg.startX >> 4, cfg.startZ >> 4); // loads (or generates) it, for the height
		int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, cfg.startX, cfg.startZ);
		double x = cfg.startX + 0.5, y = groundY + cfg.height, z = cfg.startZ + 0.5;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			p.setGameMode(GameType.SPECTATOR);
			p.teleportTo(level, x, y, z, Set.of(), cfg.yaw, cfg.pitch, true);
		}
		if (cfg.signs > 0) signWall(level, x, y, z);
		return new double[] {x, y, z, groundY};
	}

	/**
	 * A stone wall 9 blocks ahead with up to 21 x 11 wall signs on it facing the camera, centred where it looks: four lines of
	 * glow ink each, in turn every dye color (black ink is outlined at any distance, the others within 16 blocks: all are here).
	 */
	private static void signWall(ServerLevel level, double x, double y, double z) {
		net.minecraft.core.Direction ahead = net.minecraft.core.Direction.fromYRot(cfg.yaw);
		net.minecraft.core.Direction across = ahead.getClockWise();
		int n = Math.min(cfg.signs, 21 * 11), width = Math.min(n, 21), rows = (n + width - 1) / width;
		int cx = (int) Math.floor(x), cz = (int) Math.floor(z);
		int cy = (int) Math.round(y - 8 * Math.tan(Math.toRadians(cfg.pitch)));
		net.minecraft.world.item.DyeColor[] colors = net.minecraft.world.item.DyeColor.values();
		net.minecraft.world.level.block.state.BlockState sign = net.minecraft.world.level.block.Blocks.OAK_WALL_SIGN.defaultBlockState()
			.setValue(net.minecraft.world.level.block.WallSignBlock.FACING, ahead.getOpposite());
		for (int k = 0; k < n; k++) {
			int col = k % width - width / 2, row = k / width - rows / 2;
			net.minecraft.core.BlockPos at = new net.minecraft.core.BlockPos(cx, cy + row, cz).relative(ahead, 8).relative(across, col);
			level.setBlock(at.relative(ahead), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
			level.setBlock(at, sign, 2);
			if (level.getBlockEntity(at) instanceof net.minecraft.world.level.block.entity.SignBlockEntity e) {
				var lines = "cjk".equals(cfg.signText)
					? java.util.List.<net.minecraft.network.chat.Component>of(net.minecraft.network.chat.Component.literal("\u2605" + cjk(k, 0, 4) + String.format(" %03d", k)),
						net.minecraft.network.chat.Component.literal(cjk(k, 1, 6)), net.minecraft.network.chat.Component.literal("Shop " + cjk(k, 2, 3) + " \u25B6"),
						net.minecraft.network.chat.Component.literal(cjk(k, 3, 2) + " " + (k * 7 % 100) + cjk(k, 4, 1)))
					: java.util.List.<net.minecraft.network.chat.Component>of(net.minecraft.network.chat.Component.literal(String.format("Glow sign %03d", k)),
						net.minecraft.network.chat.Component.literal("mcopt bench"), net.minecraft.network.chat.Component.literal("ABCDEFGHIJKLM"),
						net.minecraft.network.chat.Component.literal("0123456789"));
				e.setText(new net.minecraft.world.level.block.entity.SignText(lines, lines, colors[k % colors.length], true),
					net.minecraft.world.level.block.entity.SignTextSlot.FRONT);
			}
		}
		log(String.format("%d glowing signs, %d x %d, 8 blocks %s", n, width, rows, ahead.getSerializedName()));
	}

	/**
	 * n CJK ideographs for sign k's part `part`, spread over the whole block (U+4E00..U+9FFF) so the wall's ~3700 characters
	 * are mostly distinct and fill a dozen or so glyph pages; the Latin, digits and symbols around them are on other pages, so
	 * lines switch pages mid-line, as signs on Chinese servers do.
	 */
	private static String cjk(int k, int part, int n) {
		StringBuilder b = new StringBuilder(n);
		for (int j = 0; j < n; j++) {
			int h = ((k * 8 + part) * 8 + j) * 0x9E3779B9;
			h ^= h >>> 15;
			b.append((char) (0x4E00 + Math.floorMod(h, 0x5200)));
		}
		return b.toString();
	}

	private static void checkSettle(Minecraft mc) {
		long now = System.nanoTime();
		double t = (now - phases[SETTLE].t0) / 1e9;
		boolean built = Terrain.built(mc);
		int chunks = mc.level.getChunkSource().getLoadedChunksCount();
		int visible = Terrain.visible();
		if (!built || chunks != lastChunks || visible != lastVisible) {
			stableSince = now;
			lastChunks = chunks;
			lastVisible = visible;
		}
		if (now >= nextSettleLog) {
			nextSettleLog = now + 5_000_000_000L;
			log(String.format("settle %.0f s: built %b, %d chunks, %d visible sections", t, built, chunks, visible));
		}
		boolean stable = built && now - stableSince >= STABLE_NS;
		if (t >= cfg.settleMin && stable || t >= cfg.settleMax) {
			settleTimedOut = !(t >= cfg.settleMin && stable);
			settleChunks = chunks;
			settleVisible = visible;
			settled = true; // the next frame ends the phase
		}
	}

	/** Once per presented frame (render thread). No allocation on this path. */
	public static void frame(Minecraft mc) {
		long now = System.nanoTime();
		long frameNs = now - lastFrameNs;
		lastFrameNs = now;
		if (cur < 0 || cur >= DONE) return;
		try {
			Phase p = phases[cur];
			p.add(frameNs, !mc.getWindow().isFocused(), mc.gameRenderer.gameRenderState().framerateLimit < 260);
			boolean over = switch (cur) {
				case SETTLE -> settled;
				case CAPTURE -> shotDone || now - p.t0 > CAPTURE_TIMEOUT_NS;
				default -> p.timeUp(now);
			};
			if (over) next(mc, now);
		} catch (Throwable t) {
			fail(mc, t);
		}
	}

	private static void next(Minecraft mc, long now) {
		Phase done = phases[cur];
		done.end(now);
		log(String.format("%s: %d frames in %.2f s (%.1f fps)%s", done.name, done.frames, done.elapsed(), done.frames / done.elapsed(),
			cur == SETTLE ? settleTimedOut ? ", timed out" : ", terrain built" : ""));
		int n = cur + 1;
		while (n < DONE && skipped(n)) n++;
		cur = n;
		if (n == DONE) {
			phase = "done";
			state = State.FINISHING; // the next tick writes the result
			return;
		}
		Phase p = phases[n];
		p.begin(now);
		phase = p.name;
		if (n == STILL || options == null) {
			options = options(mc);
			window = window(mc);
		}
		if (n == CAPTURE) screenshot(mc);
	}

	private static boolean skipped(int n) {
		return n == CAPTURE ? !cfg.screenshot : n != SETTLE && phases[n].seconds <= 0;
	}

	/** The end of still: a real frame of the measured view (the last one, still in the main target), written off-thread. */
	private static void screenshot(Minecraft mc) {
		shotFile = results.resolve(cfg.label + "-" + stamp + ".png");
		try {
			Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> Util.ioPool().execute(() -> {
				try (image) {
					image.writeToFile(shotFile);
				} catch (Throwable t) {
					shotError = t.toString();
				}
				shotDone = true;
			}));
		} catch (Throwable t) {
			shotError = t.toString();
			shotDone = true;
		}
	}

	/** Camera.update's head: this frame's pose, a function of the time since the phase began. */
	public static void pose() {
		if (cur < 0) return;
		try {
			LocalPlayer player = Minecraft.getInstance().player;
			if (player == null) return;
			double x = sx, z = sz;
			float yaw = cfg.yaw;
			if (cur == SPIN) {
				yaw += (float) (360.0 * Math.min(1.0, (System.nanoTime() - phases[SPIN].t0) / (cfg.spin * 1e9)));
			} else if (cur >= FLIGHT) {
				double s = cur == FLIGHT ? Math.min(cfg.flight, (System.nanoTime() - phases[FLIGHT].t0) / 1e9) : Math.max(0, cfg.flight);
				x += dx * cfg.flightSpeed * s;
				z += dz * cfg.flightSpeed * s;
			}
			// old position and rotation too: the camera interpolates between them, and they must not differ
			player.snapTo(x, sy, z, yaw, cfg.pitch);
			player.setDeltaMovement(Vec3.ZERO);
		} catch (Throwable t) {
			fail(Minecraft.getInstance(), t);
		}
	}

	private static void fail(Minecraft mc, Throwable t) {
		if (state == State.STOPPED) return;
		state = State.STOPPED;
		cur = -1;
		phase = "failed";
		StringWriter sw = new StringWriter();
		t.printStackTrace(new PrintWriter(sw));
		log("failed: " + sw);
		try {
			if (cfg != null) write(mc, sw.toString());
		} catch (Throwable e) {
			log("couldn't write the result: " + e);
		}
		if (cfg == null || cfg.quit) mc.stop();
	}

	private static void write(Minecraft mc, String error) throws Exception {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("label", cfg.label);
		r.put("timestamp", startedAt);
		if (error != null) r.put("error", error);
		r.put("backend", backend());
		r.put("window", window != null ? window : window(mc));
		r.put("options", options != null ? options : options(mc));
		r.put("run", cfg.toMap());
		Map<String, Object> world = new LinkedHashMap<>();
		world.put("name", cfg.world);
		world.put("created", worldCreated);
		world.put("loadSeconds", Phase.round(loadNs / 1e9, 2));
		r.put("world", world);
		r.put("start", map("x", sx, "y", sy, "z", sz, "ground", ground, "yaw", cfg.yaw, "pitch", cfg.pitch));
		if (phases != null && phases[SETTLE].t1 != 0) {
			Map<String, Object> settle = new LinkedHashMap<>();
			settle.put("seconds", Phase.round(phases[SETTLE].elapsed(), 2));
			settle.put("timedOut", settleTimedOut);
			settle.put("source", Terrain.source());
			settle.put("loadedChunks", settleChunks);
			settle.put("visibleSections", settleVisible);
			settle.put("frames", phases[SETTLE].frames);
			r.put("settle", settle);
		}
		if (shotFile != null) {
			Map<String, Object> shot = new LinkedHashMap<>();
			shot.put("file", shotFile.toString());
			shot.put("ok", shotDone && shotError == null);
			if (shotError != null) shot.put("error", shotError);
			if (phases[CAPTURE].t1 != 0) shot.put("seconds", Phase.round(phases[CAPTURE].elapsed(), 3));
			r.put("screenshot", shot);
		}
		Map<String, Object> measured = new LinkedHashMap<>();
		Map<String, Object> raw = new LinkedHashMap<>();
		if (phases != null) {
			for (int i : new int[] {STILL, SPIN, FLIGHT}) {
				Phase p = phases[i];
				if (p.t1 == 0) continue; // skipped, or not reached
				measured.put(p.name, p.metrics(false));
				raw.put(p.name, p.metrics(true).get("frameTimesMs"));
			}
		}
		r.put("phases", measured);
		r.put("screenInterruptions", interruptions);
		r.put("mcoptFlags", mcoptFlags());
		r.put("java", java());
		r.put("os", map("name", System.getProperty("os.name"), "version", System.getProperty("os.version"), "arch", System.getProperty("os.arch"),
			"cpus", Runtime.getRuntime().availableProcessors()));
		r.put("mods", mods());
		r.put("frameTimesMs", raw);

		Path out = results.resolve(cfg.label + "-" + stamp + ".json");
		Path tmp = results.resolve(out.getFileName() + ".tmp");
		Files.writeString(tmp, Json.write(r), StandardCharsets.UTF_8);
		Files.move(tmp, out, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		log("wrote " + out);
	}

	private static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	private static Map<String, Object> backend() {
		Map<String, Object> m = new LinkedHashMap<>();
		try {
			DeviceInfo info = RenderSystem.getDevice().getDeviceInfo();
			m.put("backendName", info.backendName());
			m.put("driverInfo", info.driverInfo());
			m.put("name", info.name());
			m.put("vendorName", info.vendorName());
		} catch (RuntimeException e) {
			m.put("error", e.toString());
		}
		return m;
	}

	private static Map<String, Object> window(Minecraft mc) {
		Window w = mc.getWindow();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("width", w.getScreenWidth());
		m.put("height", w.getScreenHeight());
		m.put("framebufferWidth", w.getWidth());
		m.put("framebufferHeight", w.getHeight());
		m.put("guiScale", w.getGuiScale());
		m.put("fullscreen", mc.options.fullscreen().get());
		m.put("focused", w.isFocused());
		m.put("x", w.getX());
		m.put("y", w.getY());
		// the display it's on: its refresh rate bounds what presentation does (Vulkan's FIFO, Metal's pacing)
		Monitor monitor = w.findBestMonitor();
		if (monitor != null) {
			VideoMode mode = monitor.currentMode();
			m.put("monitor", map("name", monitor.name(), "mode", mode.getWidth() + "x" + mode.getHeight() + "@" + mode.getRefreshRate(),
				"bounds", monitor.x() + "," + monitor.y() + " " + monitor.w() + "x" + monitor.h()));
		}
		return m;
	}

	private static Map<String, Object> options(Minecraft mc) {
		Options o = mc.options;
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("renderDistance", o.renderDistance().get());
		m.put("simulationDistance", o.simulationDistance().get());
		m.put("vsync", o.enableVsync().get());
		m.put("framerateLimit", o.framerateLimit().get());
		m.put("inactivityFpsLimit", o.inactivityFpsLimit().get().getSerializedName());
		m.put("fov", o.fov().get());
		m.put("guiScale", o.guiScale().get());
		m.put("graphicsPreset", String.valueOf(o.graphicsPreset().get()));
		m.put("preferredGraphicsBackend", o.preferredGraphicsBackend().get().getSerializedName());
		m.put("cloudStatus", String.valueOf(o.cloudStatus().get()));
		m.put("biomeBlendRadius", o.biomeBlendRadius().get());
		m.put("ambientOcclusion", o.ambientOcclusion().get());
		m.put("mipmapLevels", o.mipmapLevels().get());
		m.put("entityDistanceScaling", o.entityDistanceScaling().get());
		m.put("prioritizeChunkUpdates", String.valueOf(o.prioritizeChunkUpdates().get()));
		m.put("chunkSectionFadeInTime", o.chunkSectionFadeInTime().get());
		m.put("hudHidden", mc.gui.hud.isHidden());
		return m;
	}

	/** mcopt's switches as it applied them (profile, config/mcopt.properties, -D): what this run actually tested. */
	private static Map<String, Object> mcoptFlags() {
		Map<String, Object> m = new TreeMap<>();
		for (String k : System.getProperties().stringPropertyNames()) {
			if (k.startsWith("mcopt.")) m.put(k, System.getProperty(k));
		}
		return m;
	}

	private static Map<String, Object> java() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("version", System.getProperty("java.version"));
		m.put("vendor", System.getProperty("java.vendor"));
		m.put("vm", System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
		m.put("maxHeapMb", Runtime.getRuntime().maxMemory() >> 20);
		m.put("inputArgs", ManagementFactory.getRuntimeMXBean().getInputArguments());
		return m;
	}

	/** Top-level mods, and the ones nested in them except Fabric API's modules. */
	private static List<Map<String, Object>> mods() {
		List<Map<String, Object>> list = new ArrayList<>();
		List<ModContainer> all = new ArrayList<>(FabricLoader.getInstance().getAllMods());
		all.sort(Comparator.comparing(c -> c.getMetadata().getId()));
		for (ModContainer c : all) {
			String parent = c.getContainingMod().map(p -> p.getMetadata().getId()).orElse(null);
			if ("fabric-api".equals(parent)) continue;
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", c.getMetadata().getId());
			m.put("version", c.getMetadata().getVersion().getFriendlyString());
			if (parent != null) m.put("in", parent);
			list.add(m);
		}
		return list;
	}
}
