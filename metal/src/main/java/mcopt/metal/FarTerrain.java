package mcopt.metal;

import java.util.Locale;

/**
 * Far terrain's settings (the mcopt page's Far terrain group), outside mcopt.metal.lod so the page can read and set them
 * without loading that package (it loads only with far terrain on). Start values come from config/mcopt.properties, which
 * Profile has turned into system properties by then; a flag on the command line wins, as everywhere.
 * <ul>
 * <li>Distance, detail and what to do on servers apply at once: the far terrain is rebuilt around the camera (from its cache)
 * at the next frame. Haze applies at the next frame.</li>
 * <li>Block textures and plants change what the far terrain keeps per cell: they apply after a restart (read as
 * mcopt.lod.textures / mcopt.lod.plants by LodConfig).</li>
 * </ul>
 */
public final class FarTerrain {
	/** The Distance slider, in chunks. */
	public static final int MIN_DISTANCE = 64, MAX_DISTANCE = 1024, DISTANCE_STEP = 32, DEFAULT_DISTANCE = 512;

	/** Cells across each level's window (mcopt.lod.n): how far each level reaches, so how soon the far terrain coarsens. */
	public enum Detail {
		LOW(1024), MEDIUM(2048), HIGH(4096);

		public final int n;

		Detail(int n) {
			this.n = n;
		}

		static Detail of(int n) {
			return n <= 1024 ? LOW : n >= 4096 ? HIGH : MEDIUM;
		}
	}

	/**
	 * How far the game's distance haze reaches over the far terrain (mcopt.lod.haze): the haze is stretched so that terrain at
	 * the far terrain's edge is hazed 1 / factor of the way (Normal: fully, as the game hazes its own terrain at 1024 blocks).
	 */
	public enum Haze {
		OFF(1000), LIGHT(2), NORMAL(1), HEAVY(0.6);

		public final double factor;

		Haze(double factor) {
			this.factor = factor;
		}
	}

	/** On a server (mcopt.lod.servers): no far terrain, the chunks it sends kept for the session, or kept on disk per server. */
	public enum Servers {
		OFF, SESSION, SAVED
	}

	private static volatile int distance = Math.max(16, Integer.getInteger("mcopt.lod.radius", Integer.getInteger("mcopt.lod.distance", DEFAULT_DISTANCE)));
	private static volatile int n = Integer.getInteger("mcopt.lod.n", 2048);
	private static volatile double haze = parseHaze(System.getProperty("mcopt.lod.haze", "normal"));
	private static volatile Servers servers = parseServers(System.getProperty("mcopt.lod.servers", "saved"));
	private static volatile int generation;
	private static volatile boolean forget;

	private FarTerrain() {
	}

	/** Far terrain on for this launch (its mixins applied): -Dmcopt.lod=true or a radius, as LodConfig reads it. */
	public static boolean running() {
		return Boolean.getBoolean("mcopt.lod") || System.getProperty("mcopt.lod.radius") != null;
	}

	private static double parseHaze(String s) {
		try {
			return Haze.valueOf(s.trim().toUpperCase(Locale.ROOT)).factor;
		} catch (IllegalArgumentException e) {
			try {
				return Math.max(0.05, Double.parseDouble(s.trim()));
			} catch (NumberFormatException e2) {
				return Haze.NORMAL.factor;
			}
		}
	}

	private static Servers parseServers(String s) {
		try {
			return Servers.valueOf(s.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return Servers.SAVED;
		}
	}

	/** Reach in chunks. */
	public static int distanceChunks() {
		return distance;
	}

	public static int n() {
		return n;
	}

	public static Detail detail() {
		return Detail.of(n);
	}

	public static double hazeFactor() {
		return haze;
	}

	/** The Haze choice nearest the factor in use (a number in the file shows as the nearest). */
	public static Haze haze() {
		Haze best = Haze.NORMAL;
		for (Haze h : Haze.values()) if (Math.abs(Math.log(h.factor / haze)) < Math.abs(Math.log(best.factor / haze))) best = h;
		return best;
	}

	public static Servers servers() {
		return servers;
	}

	/**
	 * About how much memory far terrain's clipmap takes (shared by the CPU and the GPU) with n cells across and this reach,
	 * as LodClip lays it out: per level two words a cell, the crowns' two on level 0, its texture and plant words.
	 */
	public static int memoryMB(int n, int distanceChunks) {
		int levels = 1;
		while ((n / 2.0 - 68) * (1L << (levels - 1)) < distanceChunks * 16.0 && levels < 12) levels++;
		boolean textures = !"false".equals(System.getProperty("mcopt.lod.textures"));
		boolean plants = textures && !"false".equals(System.getProperty("mcopt.lod.plants"));
		long cells = (long) n * n;
		long bytes = cells * 8 * levels + cells / 32 * levels + cells * 8 + (textures ? cells * 4 * (plants ? 3 : 1) : 0);
		return (int) Math.round(bytes / 1048576.0);
	}

	public static void setDistance(int chunks) {
		if (chunks == distance) return;
		distance = chunks;
		generation++;
	}

	public static void setDetail(Detail d) {
		if (d.n == n) return;
		n = d.n;
		generation++;
	}

	public static void setHaze(Haze h) {
		haze = h.factor;
	}

	public static void setServers(Servers s) {
		if (s == servers) return;
		servers = s;
		generation++;
	}

	/** Bumped by every change the far terrain is rebuilt for (Lod compares it every frame). */
	public static int generation() {
		return generation;
	}

	/** The Forget button: the far terrain drops what it keeps of the world or server it's in (its cache too) at the next frame. */
	public static void forget() {
		forget = true;
	}

	/** Lod, once a frame: whether Forget was pressed since. */
	public static boolean takeForget() {
		boolean f = forget;
		forget = false;
		return f;
	}
}
