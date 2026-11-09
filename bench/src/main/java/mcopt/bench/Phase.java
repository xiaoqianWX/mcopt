package mcopt.bench;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One phase's frames. A frame's time is the gap between two consecutive frame ends; a phase's first frame is measured from
 * the previous phase's last, so the frame times of a phase add up to its length exactly. Measured phases keep every frame
 * time in an array allocated up front: nothing is allocated per frame.
 */
final class Phase {
	private static final List<GarbageCollectorMXBean> GCS = ManagementFactory.getGarbageCollectorMXBeans();
	private static final OperatingSystemMXBean OS = ManagementFactory.getOperatingSystemMXBean();
	/** Frame-time capacity per planned second: far above any fps the game reaches, so the array never fills in practice. */
	private static final int MAX_FPS = 5000;

	final String name;
	/** Planned length in seconds; 0: until a condition (settle, capture). */
	final double seconds;
	private final long[] ns;
	int frames;
	/** Frames while the window was unfocused, and frames under a frame limit (VSync off, unlimited fps: should be 0). */
	int unfocused, limited;
	long t0, t1;
	private long gcCount0, gcMs0, gcCount, gcMs;
	/** The Mac's 1-minute load average as the phase began: other work competing with the game shows here. */
	private double load;

	Phase(String name, double seconds, boolean keepFrames) {
		this.name = name;
		this.seconds = seconds;
		this.ns = keepFrames ? new long[(int) Math.min(Integer.MAX_VALUE - 8, Math.ceil(seconds * MAX_FPS) + 10_000)] : null;
	}

	void begin(long now) {
		this.t0 = now;
		this.gcCount0 = gcCount();
		this.gcMs0 = gcMs();
		this.load = OS.getSystemLoadAverage();
	}

	void add(long frameNs, boolean unfocused, boolean limited) {
		if (this.ns != null && this.frames < this.ns.length) this.ns[this.frames] = frameNs;
		this.frames++;
		if (unfocused) this.unfocused++;
		if (limited) this.limited++;
	}

	boolean timeUp(long now) {
		return this.seconds > 0 && now - this.t0 >= (long) (this.seconds * 1e9);
	}

	void end(long now) {
		this.t1 = now;
		this.gcCount = gcCount() - this.gcCount0;
		this.gcMs = gcMs() - this.gcMs0;
	}

	double elapsed() {
		return (this.t1 - this.t0) / 1e9;
	}

	/** Metrics, and with raw, every frame time in ms. */
	Map<String, Object> metrics(boolean raw) {
		Map<String, Object> m = new LinkedHashMap<>();
		double secs = elapsed();
		m.put("frames", this.frames);
		m.put("seconds", round(secs, 4));
		m.put("avgFps", round(this.frames / secs, 2));
		if (this.ns != null) {
			int n = Math.min(this.frames, this.ns.length);
			if (n < this.frames) m.put("overflow", this.frames - n);
			long[] sorted = Arrays.copyOf(this.ns, n);
			Arrays.sort(sorted);
			if (n > 0) {
				m.put("medianMs", ms(pct(sorted, 0.50)));
				m.put("p95Ms", ms(pct(sorted, 0.95)));
				m.put("p99Ms", ms(pct(sorted, 0.99)));
				m.put("maxMs", ms(sorted[n - 1]));
				// "1% low": the fps of the mean of the slowest 1% of frames (at least one frame)
				int worst = Math.max(1, (int) Math.ceil(n * 0.01));
				double sum = 0;
				for (int i = n - worst; i < n; i++) sum += sorted[i];
				m.put("low1Fps", round(1e9 / (sum / worst), 2));
			}
		}
		m.put("gcCount", this.gcCount);
		m.put("gcMs", this.gcMs);
		m.put("unfocusedFrames", this.unfocused);
		m.put("limitedFrames", this.limited);
		m.put("loadAvg", round(this.load, 2));
		if (raw && this.ns != null) m.put("frameTimesMs", new Json.Ms(this.ns, Math.min(this.frames, this.ns.length)));
		return m;
	}

	/** Nearest rank. */
	private static long pct(long[] sorted, double p) {
		int i = (int) Math.ceil(p * sorted.length) - 1;
		return sorted[Math.clamp(i, 0, sorted.length - 1)];
	}

	private static double ms(long ns) {
		return round(ns / 1e6, 3);
	}

	static double round(double v, int digits) {
		double s = Math.pow(10, digits);
		return Math.round(v * s) / s;
	}

	private static long gcCount() {
		long n = 0;
		for (GarbageCollectorMXBean gc : GCS) n += Math.max(0, gc.getCollectionCount());
		return n;
	}

	private static long gcMs() {
		long n = 0;
		for (GarbageCollectorMXBean gc : GCS) n += Math.max(0, gc.getCollectionTime());
		return n;
	}
}
