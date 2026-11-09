package mcopt.bench;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/** <game dir>/mcopt-bench/run.properties, written by tools/bench.py. Every key is optional. */
final class RunConfig {
	static final Path DIR = FabricLoader.getInstance().getGameDir().resolve("mcopt-bench");
	static final Path FILE = DIR.resolve("run.properties");

	final String label;
	final int renderDistance;
	final double still, spin, flight, flightSpeed, settleMin, settleMax;
	final long seed;
	final String world;
	final boolean screenshot, quit, hideGui;
	final int startX, startZ;
	/** Window size in points (0: as launched). macOS shrinks a window that doesn't fit the screen; the result records what it got. */
	final int windowWidth, windowHeight;
	/** Fullscreen at the display's own resolution (overrides the window size): how people play, and macOS may composite it differently. */
	final boolean fullscreen;
	/** Sodium option ids to screenshot with the cursor on them (UiShots), instead of the benchmark. Empty: the benchmark. */
	final java.util.List<String> ui;
	final double height;
	final float yaw, pitch;
	/** A wall of this many glowing signs in front of the start point (0: none), all within the 16 blocks their outlines show at. */
	final int signs;
	/** What the signs say: ascii (one glyph page) or cjk (Chinese mixed with Latin and symbols: many glyph pages, as on Chinese servers). */
	final String signText;

	private RunConfig(Properties p) {
		this.label = p.getProperty("label", "run").trim().replaceAll("[^A-Za-z0-9._-]", "_");
		this.renderDistance = Integer.parseInt(p.getProperty("renderDistance", "16").trim());
		this.still = num(p, "still", 10);
		this.spin = num(p, "spin", 20);
		this.flight = num(p, "flight", 20);
		this.flightSpeed = num(p, "flightSpeed", 20);
		this.settleMin = num(p, "settleMin", 8);
		this.settleMax = num(p, "settleMax", 120);
		this.seed = Long.parseLong(p.getProperty("seed", "20261005").trim());
		this.world = p.getProperty("world", "mcopt-bench").trim();
		this.screenshot = bool(p, "screenshot", true);
		this.quit = bool(p, "quit", true);
		// The HUD off in every config: mcopt bundles an fps counter that the baseline doesn't have.
		this.hideGui = bool(p, "hideGui", true);
		this.startX = Integer.parseInt(p.getProperty("startX", "0").trim());
		this.startZ = Integer.parseInt(p.getProperty("startZ", "0").trim());
		this.windowWidth = Integer.parseInt(p.getProperty("windowWidth", "0").trim());
		this.windowHeight = Integer.parseInt(p.getProperty("windowHeight", "0").trim());
		this.fullscreen = bool(p, "fullscreen", false);
		this.ui = java.util.Arrays.stream(p.getProperty("ui", "").split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
		this.height = num(p, "height", 40);
		this.yaw = (float) num(p, "yaw", 0);
		this.pitch = (float) num(p, "pitch", 15);
		this.signs = Integer.parseInt(p.getProperty("signs", "0").trim());
		this.signText = p.getProperty("signText", "ascii").trim();
	}

	static boolean present() {
		return Files.isRegularFile(FILE);
	}

	static RunConfig load() throws IOException {
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
			p.load(r);
		}
		return new RunConfig(p);
	}

	Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("label", this.label);
		m.put("renderDistance", this.renderDistance);
		m.put("still", this.still);
		m.put("spin", this.spin);
		m.put("flight", this.flight);
		m.put("flightSpeed", this.flightSpeed);
		m.put("signs", this.signs);
		m.put("signText", this.signText);
		m.put("settleMin", this.settleMin);
		m.put("settleMax", this.settleMax);
		m.put("seed", this.seed);
		m.put("world", this.world);
		m.put("screenshot", this.screenshot);
		m.put("quit", this.quit);
		m.put("hideGui", this.hideGui);
		m.put("startX", this.startX);
		m.put("startZ", this.startZ);
		m.put("windowWidth", this.windowWidth);
		m.put("fullscreen", this.fullscreen);
		m.put("ui", String.join(",", this.ui));
		m.put("windowHeight", this.windowHeight);
		m.put("height", this.height);
		m.put("yaw", this.yaw);
		m.put("pitch", this.pitch);
		return m;
	}

	private static double num(Properties p, String key, double def) {
		String v = p.getProperty(key);
		return v == null || v.isBlank() ? def : Double.parseDouble(v.trim());
	}

	private static boolean bool(Properties p, String key, boolean def) {
		String v = p.getProperty(key);
		return v == null || v.isBlank() ? def : Boolean.parseBoolean(v.trim());
	}
}
