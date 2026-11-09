package mcopt.metal;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Flag profiles: one place that turns on a set of -Dmcopt.* switches without editing the launcher's JVM arguments.
 * <ul>
 * <li>{@code -Dmcopt.profile=NAME}, or {@code profile=NAME} in {@code <game dir>/config/mcopt.properties}, applies
 * the jar's {@code /mcopt/profiles/NAME.properties} (e.g. {@code recommended}: the measured, accepted wins).</li>
 * <li>Any {@code mcopt.*} key in config/mcopt.properties is applied too, and wins over the profile's value.</li>
 * <li>A flag given on the command line wins over both: a key is only set when System.getProperty(key) is still null.</li>
 * </ul>
 * With no profile named anywhere, {@code alpha} applies (the perf-only set); {@code profile=none} applies no profile,
 * exactly the old no-profile behaviour. If config/mcopt.properties is absent it is written with {@code profile=alpha}
 * and comment lines on how to turn it off and how to try far terrain. For {@code alpha} on the small tier (GPU under
 * 10 cores, or 8 GB of RAM or less, or either unreadable) the keys in {@link #SMALL_OUT} are left out. It runs first in every mixin config plugin
 * and in the preLaunch entrypoint, before any of our classes read a flag; the first call does the work, later calls
 * return at once.
 */
public final class Profile {
	private static boolean applied;
	/** Alpha keys left out on the small tier: the 4096-entry clone cache costs memory an 8 GB / small-GPU Mac lacks. */
	static final java.util.List<String> SMALL_OUT = java.util.List.of("mcopt.chunk.clones", "mcopt.chunk.clonesCleanup");
	static final String DEFAULT = "alpha";

	private Profile() {
	}

	public static synchronized void apply() {
		if (applied) return;
		applied = true;
		Properties file = new Properties();
		Path cfg = gameDir().resolve("config").resolve("mcopt.properties");
		if (Files.isRegularFile(cfg)) {
			try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
				file.load(r);
			} catch (IOException e) {
				System.out.println("[mcopt] profile: can't read " + cfg + ": " + e);
			}
		} else if (!Files.exists(cfg)) {
			writeDefault(cfg);
		}
		String name = System.getProperty("mcopt.profile", file.getProperty("profile", "")).trim();
		if (name.isEmpty()) name = DEFAULT;
		Map<String, String> flags = new TreeMap<>();
		if (!name.isEmpty() && !name.equals("none")) {
			Properties p = new Properties();
			try (InputStream in = Profile.class.getResourceAsStream("/mcopt/profiles/" + name + ".properties")) {
				if (in == null) {
					System.out.println("[mcopt] profile: no profile named '" + name + "' (ignored)");
				} else {
					p.load(in);
				}
			} catch (IOException e) {
				System.out.println("[mcopt] profile " + name + ": " + e);
			}
			p.stringPropertyNames().forEach(k -> flags.put(k, p.getProperty(k).trim()));
			if (name.equals("alpha")) tier(flags);
		}
		file.stringPropertyNames().stream().filter(k -> k.startsWith("mcopt.")).forEach(k -> flags.put(k, file.getProperty(k).trim()));
		if (flags.isEmpty()) return;
		StringBuilder set = new StringBuilder(), kept = new StringBuilder();
		flags.forEach((k, v) -> {
			if (System.getProperty(k) == null) {
				System.setProperty(k, v);
				set.append(' ').append(k).append('=').append(v);
			} else {
				kept.append(' ').append(k).append('=').append(System.getProperty(k));
			}
		});
		System.out.println("[mcopt] profile " + (name.isEmpty() ? "(config only)" : name) + ": set" + set
			+ (kept.isEmpty() ? "" : "; kept from the command line:" + kept));
	}

	/** Small tier: GPU cores < 10 or RAM <= 8 GB, or either unreadable. Logs the tier and drops {@link #SMALL_OUT}. */
	private static void tier(Map<String, String> flags) {
		boolean small = smallTier();
		StringBuilder out = new StringBuilder();
		if (small) for (String k : SMALL_OUT) if (flags.remove(k) != null) out.append(' ').append(k);
		System.out.println("[mcopt] profile alpha: tier " + (small ? "small" : "full") + " (gpu cores "
			+ (cores < 0 ? "unknown" : cores) + ", ram " + (mem < 0 ? "unknown" : String.format("%.1f GB", mem / (double) (1L << 30))) + ")"
			+ (out.isEmpty() ? "" : "; left out:" + out));
	}

	private static int gpuCores() {
		String s = run("/usr/sbin/ioreg", "-rd1", "-c", "AGXAccelerator");
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"gpu-core-count\"\\s*=\\s*(\\d+)").matcher(s);
		return m.find() ? Integer.parseInt(m.group(1)) : -1;
	}

	private static long memBytes() {
		try {
			return Long.parseLong(run("/usr/sbin/sysctl", "-n", "hw.memsize").trim());
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static String run(String... cmd) {
		try {
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null"))).start();
			byte[] b = p.getInputStream().readAllBytes();
			p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
			return new String(b, StandardCharsets.UTF_8);
		} catch (Exception e) {
			return "";
		}
	}

	/** The profile this launch applied: "alpha", "none", another name, from the command line or the file. */
	public static String active() {
		String name = System.getProperty("mcopt.profile", fileValue("profile")).trim();
		return name.isEmpty() ? DEFAULT : name;
	}

	private static int cores = Integer.MIN_VALUE;
	private static long mem;

	/** Small tier on this Mac (see {@link #tier}): GPU cores < 10 or RAM <= 8 GB, or either unreadable. */
	public static synchronized boolean smallTier() {
		if (cores == Integer.MIN_VALUE) {
			cores = gpuCores();
			mem = memBytes();
		}
		return cores < 10 || mem <= 8L << 30;
	}

	/** key's value as written in config/mcopt.properties ("" when absent), not what the command line may have set. */
	public static String fileValue(String key) {
		Properties file = new Properties();
		try (Reader r = Files.newBufferedReader(configFile(), StandardCharsets.UTF_8)) {
			file.load(r);
		} catch (IOException e) {
			return "";
		}
		return file.getProperty(key, "").trim();
	}

	/**
	 * Sets keys in config/mcopt.properties for the next launch, keeping its comments and order: a key's line is rewritten in
	 * place (a commented-out "#key=..." line counts, so "#mcopt.lod=true" becomes "mcopt.lod=true"), others are appended.
	 */
	public static synchronized void write(Map<String, String> changes) {
		Path cfg = configFile();
		if (!Files.exists(cfg)) writeDefault(cfg);
		java.util.List<String> lines;
		try {
			lines = new java.util.ArrayList<>(Files.readAllLines(cfg, StandardCharsets.UTF_8));
		} catch (IOException e) {
			System.out.println("[mcopt] profile: can't read " + cfg + ": " + e);
			return;
		}
		for (Map.Entry<String, String> change : changes.entrySet()) {
			String key = java.util.regex.Pattern.quote(change.getKey());
			int live = -1, commented = -1;
			for (int i = 0; i < lines.size(); i++) {
				String l = lines.get(i).strip();
				if (l.matches(key + "\\s*[=:].*")) live = i;
				else if (commented < 0 && l.matches("#\\s*" + key + "\\s*[=:].*")) commented = i;
			}
			int at = live >= 0 ? live : commented;
			if (at >= 0) lines.set(at, change.getKey() + "=" + change.getValue());
			else lines.add(change.getKey() + "=" + change.getValue());
		}
		try {
			Path tmp = Files.createTempFile(cfg.getParent(), "mcopt", ".properties");
			Files.write(tmp, lines, StandardCharsets.UTF_8);
			Files.move(tmp, cfg, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			System.out.println("[mcopt] profile: wrote " + changes + " to " + cfg + " (next launch)");
		} catch (IOException e) {
			System.out.println("[mcopt] profile: can't write " + cfg + ": " + e);
		}
	}

	private static Path configFile() {
		return gameDir().resolve("config").resolve("mcopt.properties");
	}

	private static void writeDefault(Path cfg) {
		String text = """
			# mcopt settings. Written on first launch; edit freely.
			#
			# The alpha profile: measured vanilla performance options (chunk meshing, render lists, startup).
			# To turn it off, change the next line to:  profile=none
			profile=alpha
			#
			# Far terrain (EXPERIMENTAL, off by default; for Macs with 10 or more GPU cores): remove the # below.
			#mcopt.lod=true
			""";
		try {
			Files.createDirectories(cfg.getParent());
			Files.writeString(cfg, text, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
			System.out.println("[mcopt] profile: wrote " + cfg + " (profile=alpha)");
		} catch (IOException e) {
			System.out.println("[mcopt] profile: can't write " + cfg + ": " + e);
		}
	}

	private static Path gameDir() {
		try {
			return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
		} catch (Throwable t) {
			return Path.of("").toAbsolutePath();
		}
	}
}
