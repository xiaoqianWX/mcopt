package mcopt.metal.lod;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.FrustumIntersection;

/**
 * The far terrain of one dimension of one world: keeps the clipmap's windows filled. Each level needs the tiles of its
 * ring (between the finer level's switch distance and its own); the coarsest level needs its whole window, so whatever a
 * finer level still lacks falls back to it. Missing tiles go to the workers, most urgent first (the larger on screen, the
 * sooner; tripled when out of view); a worker loads the tile from the disk cache or generates it from the world
 * generator's noise, packs it into the clipmap's words, and the render thread writes it into the window.
 *
 * The game's own chunks overwrite the generated estimate: level 0 cell for cell, each coarser cell from the columns under it.
 * Tiles that took real chunks are saved back to the cache from the clipmap every few seconds.
 *
 * Without a generator (noise null: a server's world, or one that isn't noise-generated) the chunks are all there is: a tile
 * the cache doesn't have starts empty (every cell "no data", where the walk falls back to a coarser level) and the chunks
 * the client receives fill it in, so the far terrain is wherever the player has been and grows as they explore.
 */
final class LodField {
	/** 11: coarser cells from all the real columns under them (10 took the one at their corner: pillars of builds). */
	static final int FORMAT = 11;
	/** The world generator's noise, or null: far terrain from the game's own chunks only. */
	final @org.jspecify.annotations.Nullable LodNoise noise;
	/** No generator: tiles come from the cache and the chunks the client receives (noise == null). */
	final boolean chunksOnly;
	final LodClip clip;
	/** The game's own trees on level-0 tiles (null: -Dmcopt.lod.trees=false). */
	final @org.jspecify.annotations.Nullable LodForest forest;
	final Path cache;
	/** Tiles are read from and written to the cache (false: -Dmcopt.lod.cache=false, or a server on Session). */
	final boolean persist;
	private final ConcurrentHashMap<Long, Boolean> pending = new ConcurrentHashMap<>();
	private final PriorityBlockingQueue<Job> jobs = new PriorityBlockingQueue<>();
	private final ConcurrentLinkedQueue<Runnable> results = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<Runnable> saves = new ConcurrentLinkedQueue<>();
	private final List<Thread> threads = new ArrayList<>();
	private volatile boolean closed;
	private final AtomicLong seq = new AtomicLong();
	/** Window origins per level as the workers see them (a job whose tile left its window is dropped). */
	private final int[] winTx, winTz;

	// stats
	final AtomicLong generated = new AtomicLong(), loaded = new AtomicLong(), genNanos = new AtomicLong(), chunksSummarized = new AtomicLong();
	/** chunksOnly: tiles that started empty (neither cached nor generated). */
	final AtomicLong empty = new AtomicLong();
	/** Render thread: chunks snapshotted and the time it took (ns). */
	long snapshots, snapNanos;
	long startNanos = System.nanoTime(), settledNanos, firstSettledNanos;
	boolean settled;
	int needed, missing;

	private record Job(double priority, long seq, long key, Runnable task) implements Comparable<Job> {
		@Override
		public int compareTo(Job o) {
			int c = Double.compare(this.priority, o.priority);
			return c != 0 ? c : Long.compare(this.seq, o.seq);
		}
	}

	LodField(@org.jspecify.annotations.Nullable LodNoise noise, LodClip clip, Path cache, boolean persist) {
		this.noise = noise;
		this.chunksOnly = noise == null;
		this.clip = clip;
		this.cache = cache;
		this.persist = persist;
		this.forest = LodConfig.TREES && noise != null ? new LodForest(noise) : null;
		this.winTx = new int[clip.levels];
		this.winTz = new int[clip.levels];
		for (int i = 0; i < LodConfig.THREADS; i++) {
			Thread t = new Thread(this::work, "mcopt-lod-" + i);
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY + 1);
			t.start();
			this.threads.add(t);
		}
		System.out.printf("mcopt-lod: field ready: reach %d chunks, %d levels of %d x %d cells (%.0f MB), switch at %s, %d workers, %s, cache %s%s%n",
			(int) (LodConfig.reachBlocks() / 16), clip.levels, clip.n, clip.n, clip.bytes() / 1048576.0, java.util.Arrays.toString(clip.switchDist), LodConfig.THREADS,
			noise == null ? "from received chunks only" : "generated", cache, persist ? "" : " (not used)");
	}

	/**
	 * Stops the workers. save: what real chunks changed goes to the cache first, with the saves still queued; else (Forget)
	 * nothing is saved, and the workers are waited for (a second or two at most), so none writes into the folder after.
	 */
	void close(boolean save) {
		if (save) this.flush();
		this.closed = true;
		for (Thread t : this.threads) t.interrupt();
		if (this.scanner != null) this.scanner.shutdownNow();
		if (!save) {
			long until = System.nanoTime() + 2_000_000_000L;
			for (Thread t : this.threads) {
				try {
					t.join(Math.max(1, (until - System.nanoTime()) / 1_000_000));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}

	private void work() {
		while (!this.closed) {
			Job j;
			try {
				j = this.jobs.take();
			} catch (InterruptedException e) {
				return;
			}
			if (j.task == null && LodYield.ON && !LodYield.admit(LodTile.levelOf(j.key))) {
				this.jobs.add(j);   // -Dmcopt.lod.yield: the server has chunk work near the player, so this job waits in the queue
				LodYield.idle();
				continue;
			}
			try {
				if (j.task != null) j.task.run();
				else this.generate(j.key);
			} catch (Throwable t) {
				System.out.println("mcopt-lod: job failed: " + t);
				t.printStackTrace(System.out);
				if (j.task == null) this.pending.remove(j.key);
			}
		}
	}

	private Path file(long key) {
		return this.cache.resolve("L" + LodTile.levelOf(key)).resolve(LodTile.txOf(key) + "." + LodTile.tzOf(key) + ".lod");
	}

	private boolean stillWanted(long key) {
		int l = LodTile.levelOf(key), x = LodTile.txOf(key) - this.winTx[l], z = LodTile.tzOf(key) - this.winTz[l];
		return x >= 0 && z >= 0 && x < this.clip.tilesPerSide && z < this.clip.tilesPerSide;
	}

	private void generate(long key) {
		if (!this.stillWanted(key)) {
			this.pending.remove(key);
			return;
		}
		int level = LodTile.levelOf(key), tx = LodTile.txOf(key), tz = LodTile.tzOf(key);
		int[] g = new int[LodTile.CELLS], c = new int[LodTile.CELLS];
		int[] cr = level < this.clip.crownLevels ? new int[LodTile.CELLS] : null;
		int[] tw = level == 0 && LodConfig.TEXTURES ? new int[LodTile.CELLS] : null;
		int[] rn = cr != null ? new int[LodTile.CELLS] : null;
		int[] pl = level == 0 && this.clip.plants ? new int[2 * LodTile.CELLS] : null;
		long start = System.nanoTime();
		boolean fromDisk = this.persist && this.load(key, g, c, cr, tw, rn, pl);
		LodNoise noise = this.noise;
		if (!fromDisk && noise == null) {
			// nothing known here yet: the tile goes in empty (its words all 0) for the chunks the client receives to fill
			// (a cache file that failed partway may have left words behind)
			for (int[] a : new int[][] {g, c, cr, tw, rn, pl}) if (a != null) java.util.Arrays.fill(a, 0);
			this.empty.incrementAndGet();
		} else if (!fromDisk) {
			LodTile t = new LodTile(level, tx, tz);
			LodForest forest = this.forest;
			boolean exact = level < Math.min(2, LodConfig.TREE_LEVELS) && forest != null && forest.available();
			t.impostorTrees = !exact;
			noise.generate(t);
			if (exact) {
				if (level == 0) forest.offer(t);
				forest.plant(t);
				noise.dressTrees(t);
				noise.dressGround(t);
			}
			pack(t, g, c, cr, rn, pl);
			if (tw != null) System.arraycopy(t.tex, 0, tw, 0, LodTile.CELLS);
			this.genNanos.addAndGet(System.nanoTime() - start);
			this.generated.incrementAndGet();
			if (this.persist) this.save(key, g, c, cr, tw, rn, pl);
		} else {
			this.loaded.incrementAndGet();
		}
		this.results.add(() -> {
			this.pending.remove(key);
			if (this.clip.put(level, tx, tz, g, c, cr, tw, rn, pl)) {
				List<LodChunks.Summary> real = this.waiting.remove(key);
				if (real != null) for (LodChunks.Summary s : real) this.applyChunk(s, level);
			}
			this.arrived(key);
		});
	}

	/**
	 * A generated tile as clipmap words. With cr (levels that keep crowns), a tree crown floating over its ground becomes a
	 * crown cell: the geometry word its top and thickness, the color word its colors, the crown word the ground under it.
	 */
	static void pack(LodTile t, int[] g, int[] c, int @org.jspecify.annotations.Nullable [] cr, int @org.jspecify.annotations.Nullable [] rn,
		int @org.jspecify.annotations.Nullable [] pl) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < t.cells(); i++) {
			if (pl != null) {
				pl[i] = 0;
				pl[LodTile.CELLS + i] = 0;
			}
			boolean wet = t.water[i] != LodTile.DRY && t.water[i] > t.height[i];
			int surface = wet ? t.water[i] : t.height[i];
			if (cr != null && t.canopyHi[i] >= t.canopyLo[i] && !t.standing[i] && t.canopyHi[i] + 1 > surface) {
				// (the wet flag marks a water top: a crown's top is leaves)
				g[i] = LodClip.crownGeomWord(t.canopyHi[i] + 1, t.canopyHi[i] + 1 - t.canopyLo[i], false);
				c[i] = LodClip.colorWord(t.crownTop[i], t.crownSide[i]);
				cr[i] = LodClip.crownWord(surface, t.top[i]);
				if (rn != null) rn[i] = t.crownRuns[i];
				continue;
			}
			g[i] = LodClip.geomWord(surface, wet) | (t.fringe[i] && !wet && t.level == 0 ? LodClip.GEOM_FRINGE : 0);
			c[i] = LodClip.colorWord(t.top[i], t.side[i]);
			BlockState plant = t.plantLower[i];
			if (pl != null && plant != null && !wet && t.level == 0) {
				// the game's random offset of the plant (its position's hash), as it will be drawn there
				var off = plant.getOffset(pos.set(t.x0 + (i % t.size), surface, t.z0 + (i / t.size)));
				BlockState upper = t.plantUpper[i];
				pl[i] = LodClip.plantWordA(LodPalette.id(plant), upper != null ? LodPalette.id(upper) : 0, t.plantBlocks[i]);
				pl[LodTile.CELLS + i] = LodClip.plantWordB(t.plantColor[i], off.x(), off.y(), off.z());
				if ((pl[i] & 1023) != 0) g[i] |= LodClip.plantBits(t.plantBlocks[i]);
			}
			if (cr != null) cr[i] = LodClip.belowWord(t.below[i]);
			if (rn != null) rn[i] = 0;
		}
	}

	private boolean load(long key, int[] g, int[] c, int @org.jspecify.annotations.Nullable [] cr, int @org.jspecify.annotations.Nullable [] tw,
		int @org.jspecify.annotations.Nullable [] rn, int @org.jspecify.annotations.Nullable [] pl) {
		Path f = this.file(key);
		if (!Files.exists(f)) return false;
		try (DataInputStream in = new DataInputStream(new InflaterInputStream(new BufferedInputStream(Files.newInputStream(f)), new java.util.zip.Inflater(), 65536))) {
			if (in.readInt() != FORMAT) return false;
			for (int i = 0; i < LodTile.CELLS; i++) g[i] = in.readInt();
			for (int i = 0; i < LodTile.CELLS; i++) c[i] = in.readInt();
			boolean hasCrowns = in.readBoolean();
			if (hasCrowns && cr != null) for (int i = 0; i < LodTile.CELLS; i++) cr[i] = in.readInt();
			else if (cr != null) java.util.Arrays.fill(cr, 0);
			if (hasCrowns && cr == null) for (int i = 0; i < LodTile.CELLS; i++) in.readInt();
			if (hasCrowns) for (int i = 0; i < LodTile.CELLS; i++) {
				int w = in.readInt();
				if (rn != null) rn[i] = w;
			}
			else if (rn != null) java.util.Arrays.fill(rn, 0);
			// palette numbers are this session's: the cache keeps the block states' names
			boolean hasTex = in.readBoolean();
			if (hasTex) {
				int n = in.readInt();
				int[] ids = new int[n];
				for (int k = 0; k < n; k++) ids[k] = LodPalette.idOf(in.readUTF());
				for (int i = 0; i < LodTile.CELLS; i++) {
					int w = in.readInt();
					if (tw != null) tw[i] = LodPalette.word(remap(ids, w & 1023), remap(ids, (w >> 10) & 1023), remap(ids, (w >> 20) & 1023));
				}
			} else if (tw != null) {
				java.util.Arrays.fill(tw, 0);
			}
			// plants: their words, block numbers as names like the texture words
			boolean hasPlants = in.readBoolean();
			if (hasPlants) {
				int n = in.readInt();
				int[] ids = new int[n];
				for (int k = 0; k < n; k++) ids[k] = LodPalette.idOf(in.readUTF());
				for (int i = 0; i < LodTile.CELLS; i++) {
					int a = in.readInt(), b = in.readInt();
					if (pl != null) {
						pl[i] = remap(ids, a & 1023) | remap(ids, (a >> 10) & 1023) << 10 | a & ~0xFFFFF;
						pl[LodTile.CELLS + i] = b;
					}
				}
			} else if (pl != null) {
				java.util.Arrays.fill(pl, 0);
			}
			if (pl == null || !hasPlants) for (int i = 0; i < LodTile.CELLS; i++) g[i] &= ~LodClip.GEOM_PLANT_BITS;
			return true;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private static int remap(int[] ids, int local) {
		return local > 0 && local < ids.length ? ids[local] : 0;
	}

	private void save(long key, int[] g, int[] c, int @org.jspecify.annotations.Nullable [] cr, int @org.jspecify.annotations.Nullable [] tw,
		int @org.jspecify.annotations.Nullable [] rn, int @org.jspecify.annotations.Nullable [] pl) {
		Path f = this.file(key);
		try {
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp" + Thread.currentThread().threadId());
			try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)),
				new Deflater(Deflater.BEST_SPEED), 65536))) {
				out.writeInt(FORMAT);
				for (int i = 0; i < LodTile.CELLS; i++) out.writeInt(g[i]);
				for (int i = 0; i < LodTile.CELLS; i++) out.writeInt(c[i]);
				out.writeBoolean(cr != null);
				if (cr != null) for (int i = 0; i < LodTile.CELLS; i++) out.writeInt(cr[i]);
				if (cr != null) for (int i = 0; i < LodTile.CELLS; i++) out.writeInt(rn != null ? rn[i] : 0);
				out.writeBoolean(tw != null);
				if (tw != null) {
					// palette numbers as a local table of block state names (numbers differ between sessions)
					java.util.LinkedHashMap<Integer, Integer> local = new java.util.LinkedHashMap<>();
					local.put(0, 0);
					int[] words = new int[LodTile.CELLS];
					for (int i = 0; i < LodTile.CELLS; i++) {
						int w = tw[i], a = w & 1023, b = (w >> 10) & 1023, d = (w >> 20) & 1023;
						for (int id : new int[] {a, b, d}) local.putIfAbsent(id, local.size());
						words[i] = local.get(a) | local.get(b) << 10 | local.get(d) << 20;
					}
					out.writeInt(local.size());
					for (int id : local.keySet()) out.writeUTF(LodPalette.nameOf(id));
					for (int i = 0; i < LodTile.CELLS; i++) out.writeInt(words[i]);
				}
				out.writeBoolean(pl != null);
				if (pl != null) {
					java.util.LinkedHashMap<Integer, Integer> local = new java.util.LinkedHashMap<>();
					local.put(0, 0);
					int[] words = new int[LodTile.CELLS];
					for (int i = 0; i < LodTile.CELLS; i++) {
						int w = pl[i], a = w & 1023, b = (w >> 10) & 1023;
						local.putIfAbsent(a, local.size());
						local.putIfAbsent(b, local.size());
						words[i] = local.get(a) | local.get(b) << 10 | w & ~0xFFFFF;
					}
					out.writeInt(local.size());
					for (int id : local.keySet()) out.writeUTF(LodPalette.nameOf(id));
					for (int i = 0; i < LodTile.CELLS; i++) {
						out.writeInt(words[i]);
						out.writeInt(pl[LodTile.CELLS + i]);
					}
				}
			}
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			System.out.println("mcopt-lod: cache write failed: " + e);
		}
	}

	// ---- the game's own chunks ----

	/**
	 * Chunks that arrived before the tile they write into, by tile: level 0's (the generator fills coarser levels), and with
	 * chunksOnly every level's, whose tiles over the chunks' area are all requested (see requestNear).
	 */
	private final java.util.HashMap<Long, List<LodChunks.Summary>> waiting = new java.util.HashMap<>();
	/** Level and tile of every tile real chunks changed since it was saved. */
	private final java.util.Set<Long> dirtyTiles = new java.util.HashSet<>();

	/** Render thread: a chunk the client loaded or is unloading becomes far terrain (colors worked out on a worker). */
	void chunk(net.minecraft.world.level.chunk.LevelChunk chunk) {
		long t0 = System.nanoTime();
		LodChunks.Snapshot snap = LodChunks.snapshot(chunk);
		this.snapNanos += System.nanoTime() - t0;
		this.snapshots++;
		this.jobs.add(new Job(-2, this.seq.incrementAndGet(), 0, () -> {
			LodChunks.Summary sum = LodChunks.summarize(snap);
			this.chunksSummarized.incrementAndGet();
			this.results.add(() -> this.applyChunk(sum));
		}));
	}

	/**
	 * Writes a chunk's columns into every resident tile that covers it: level 0 column for column, each coarser cell from all
	 * the columns under it (see putReduced).
	 */
	private void applyChunk(LodChunks.Summary s) {
		for (int level = 0; level < this.clip.levels; level++) this.applyChunk(s, level);
	}

	private void applyChunk(LodChunks.Summary s, int level) {
		int bx = s.chunkX() * 16, bz = s.chunkZ() * 16;
		int span = this.clip.span(level);
		int tx = Math.floorDiv(bx, span), tz = Math.floorDiv(bz, span);
		if (!this.clip.resident(level, tx, tz)) {
			if ((level == 0 || this.chunksOnly) && this.clip.inWindow(level, tx, tz)) {
				List<LodChunks.Summary> list = this.waiting.computeIfAbsent(LodTile.key(level, tx, tz), k -> new ArrayList<>());
				list.removeIf(o -> o.chunkX() == s.chunkX() && o.chunkZ() == s.chunkZ());
				list.add(s);
			}
			return;
		}
		if (level == 0 && LodConfig.VERIFY) this.verify(s);
		int cell = 1 << level;
		if (level == 0) {
			for (int k = 0; k < 256; k++) {
				int cx = bx + (k & 15), cz = bz + (k >> 4);
				boolean wet = s.water()[k] != LodTile.DRY && s.water()[k] > s.height()[k];
				int clear = (s.clear()[k] ? LodClip.GEOM_CLEAR : 0) | LodClip.depthBits(s.depth()[k]);
				int surface = wet ? s.water()[k] : s.height()[k];
				if (s.crownLo()[k] > surface) {
					// leaves over air: a crown floating over the ground under it
					this.clip.putCell(level, cx, cz, LodClip.crownGeomWord(s.crownHi()[k], s.crownHi()[k] - s.crownLo()[k], false) | clear,
						LodClip.colorWord(s.top()[k], s.side()[k]), LodClip.crownWord(surface, s.groundColor()[k]), s.tex()[k], s.runs()[k], 0, 0);
				} else {
					int pa = s.plantA()[k];
					this.clip.putCell(level, cx, cz, LodClip.geomWord(Math.max(surface, s.crownHi()[k]), wet) | clear | (s.fringe()[k] && !wet ? LodClip.GEOM_FRINGE : 0)
						| ((pa & 1023) != 0 && !wet ? LodClip.plantBits((pa >> 20 & 3) + 1) : 0),
						LodClip.colorWord(s.top()[k], s.side()[k]), LodClip.belowWord(s.below()[k]), s.tex()[k], 0, pa, s.plantB()[k]);
				}
			}
		} else if (cell <= 16) {
			// cells inside the chunk: each from its cell x cell columns
			for (int z = 0; z < 16; z += cell) {
				for (int x = 0; x < 16; x += cell) this.putReduced(s, level, (bx + x) >> level, (bz + z) >> level, x, z, cell);
			}
		} else {
			// a cell wider than the chunk: from the four finer cells under it where their tile is here (each of those from
			// its own chunks), else (that tile not resident) from this chunk alone if it holds the cell's corner
			int cx = bx >> level, cz = bz >> level;
			if (!this.putFromFiner(level, cx, cz) && bx == cx << level && bz == cz << level) this.putReduced(s, level, cx, cz, 0, 0, 16);
		}
		this.clip.refresh(level, tx, tz);
		this.dirtyTiles.add(LodTile.key(level, tx, tz));
	}

	// (render thread only)
	private final int[] redTop = new int[256], redSort = new int[256];

	/**
	 * A summary column's top for a coarser cell: its ground, water or tree crown, whichever is highest. A build floating over
	 * open space counts as the ground under it: a coarser cell has no crown word, so it would stand on the ground as a pillar.
	 */
	private static int columnTop(LodChunks.Summary s, int k) {
		boolean wet = s.water()[k] != LodTile.DRY && s.water()[k] > s.height()[k];
		int surface = wet ? s.water()[k] : s.height()[k];
		return s.build()[k] ? surface : Math.max(surface, s.crownHi()[k]);
	}

	/**
	 * One coarser cell from the summary's size x size columns at (x0, z0) in the chunk. Its height is the one at least half of
	 * them reach (the upper median), so a lamp post, a spire or a tower's corner doesn't stand for the whole cell, as the
	 * column at the cell's corner used to: a build shows at a level where it covers half a cell. Its colors are the mean of
	 * the columns at about that height (and as wet), its other bits those of the first column at it.
	 */
	private void putReduced(LodChunks.Summary s, int level, int cx, int cz, int x0, int z0, int size) {
		int n = size * size;
		for (int z = 0, k = 0; z < size; z++) {
			for (int x = 0; x < size; x++, k++) this.redTop[k] = columnTop(s, (z0 + z) * 16 + x0 + x);
		}
		System.arraycopy(this.redTop, 0, this.redSort, 0, n);
		java.util.Arrays.sort(this.redSort, 0, n);
		int h = this.redSort[n / 2];
		int rep = -1;
		for (int k = 0; k < n && rep < 0; k++) if (this.redTop[k] == h) rep = (z0 + k / size) * 16 + x0 + k % size;
		boolean wet = s.water()[rep] != LodTile.DRY && s.water()[rep] > s.height()[rep];
		int surface = wet ? s.water()[rep] : s.height()[rep];
		int clear = (s.clear()[rep] ? LodClip.GEOM_CLEAR : 0) | LodClip.depthBits(s.depth()[rep]);
		if (level < this.clip.crownLevels && s.crownLo()[rep] > surface && !s.build()[rep]) {
			// (-Dmcopt.lod.crownLevels over 1) a crown cell: the column's own
			this.clip.putCell(level, cx, cz, LodClip.crownGeomWord(s.crownHi()[rep], s.crownHi()[rep] - s.crownLo()[rep], false) | clear,
				LodClip.colorWord(s.top()[rep], s.side()[rep]), LodClip.crownWord(surface, s.groundColor()[rep]), 0, s.runs()[rep], 0, 0);
			return;
		}
		long tr = 0, tg = 0, tb = 0, sr = 0, sg = 0, sb = 0;
		int m = 0;
		for (int k = 0; k < n; k++) {
			if (Math.abs(this.redTop[k] - h) > 1) continue;
			int i = (z0 + k / size) * 16 + x0 + k % size;
			if ((s.water()[i] != LodTile.DRY && s.water()[i] > s.height()[i]) != wet) continue;
			// (coarser cells: their walls are mostly below the top block; a floating build's column: its ground's)
			int t = s.build()[i] ? s.groundColor()[i] : s.top()[i];
			int d = LodColors.mix(s.build()[i] ? t : s.side()[i], s.below()[i], 0.6F);
			tr += t >> 16 & 255;
			tg += t >> 8 & 255;
			tb += t & 255;
			sr += d >> 16 & 255;
			sg += d >> 8 & 255;
			sb += d & 255;
			m++;
		}
		int top = (int) (tr / m) << 16 | (int) (tg / m) << 8 | (int) (tb / m), side = (int) (sr / m) << 16 | (int) (sg / m) << 8 | (int) (sb / m);
		this.clip.putCell(level, cx, cz, LodClip.geomWord(h, wet) | clear, LodClip.colorWord(top, side), 0, 0, 0, 0, 0);
	}

	/**
	 * A cell from the four cells under it on the next finer level, as putReduced does from columns: the upper median of the
	 * ones with data (at least two of them, else the cell is left as it is). False when their tile isn't resident.
	 */
	private boolean putFromFiner(int level, int cx, int cz) {
		int f = level - 1;
		if (!this.clip.resident(f, Math.floorDiv(cx * 2, LodClip.TILE), Math.floorDiv(cz * 2, LodClip.TILE))) return false;
		int[] g = new int[4], c = new int[4], y = new int[4];
		int n = 0;
		for (int q = 0; q < 4; q++) {
			int gw = this.clip.cellGeom(f, cx * 2 + (q & 1), cz * 2 + (q >> 1));
			if ((gw & LodClip.GEOM_VALID) == 0) continue;
			g[n] = gw;
			c[n] = this.clip.cellColor(f, cx * 2 + (q & 1), cz * 2 + (q >> 1));
			y[n] = (gw & 0xFFF) - 512;
			n++;
		}
		if (n < 2) return true;
		int[] sorted = java.util.Arrays.copyOf(y, n);
		java.util.Arrays.sort(sorted);
		int h = sorted[n / 2], rep = 0;
		while (y[rep] != h) rep++;
		boolean wet = (g[rep] & LodClip.GEOM_WET) != 0;
		int[] sum = new int[6];
		int m = 0;
		for (int k = 0; k < n; k++) {
			if (Math.abs(y[k] - h) > 1 || ((g[k] & LodClip.GEOM_WET) != 0) != wet) continue;
			for (int half = 0; half < 2; half++) {
				int w = c[k] >>> (16 * half) & 0xFFFF;
				sum[half * 3] += w >> 11;
				sum[half * 3 + 1] += w >> 5 & 63;
				sum[half * 3 + 2] += w & 31;
			}
			m++;
		}
		int color = 0;
		for (int half = 0; half < 2; half++) color |= ((sum[half * 3] / m) << 11 | (sum[half * 3 + 1] / m) << 5 | sum[half * 3 + 2] / m) << (16 * half);
		int keep = LodClip.GEOM_CLEAR | 127 << LodClip.GEOM_DEPTH_SHIFT;
		this.clip.putCell(level, cx, cz, LodClip.geomWord(h, wet) | (g[rep] & keep), color, 0, 0, 0, 0, 0);
		return true;
	}

	// ---- -Dmcopt.lod.verify: generated far terrain against the real chunks, column by column ----

	private final java.util.Set<Long> verified = new java.util.HashSet<>();
	/** columns, no data, ground exact, ground off by 1, crown agree (both or neither), crowns both, crowns exact (bottom, top, runs, ground),
	 * plants agree (presence), plants both, plants same block, top block same, side block same, top color within 2 (of 31/63) */
	private final long[] vs = new long[16];
	private int verifyLogged, groundLogged, chunkLogged;
	/** Open dry ground without trees: real minus generated surface y, -8..8 (clamped). */
	private final long[] dyHist = new long[17];
	/** Columns: dry in both, wet only in the real chunk, wet only in far terrain, wet in both. */
	private final long[] wetStats = new long[4];
	/** Per verified chunk with misses: surface columns off << 16 | crown columns off. */
	private final java.util.HashMap<Long, Integer> chunkMiss = new java.util.HashMap<>();

	private void verify(LodChunks.Summary s) {
		long key = LodForest.chunkKey(s.chunkX(), s.chunkZ());
		if (!this.verified.add(key)) return;
		int bx = s.chunkX() * 16, bz = s.chunkZ() * 16;
		long groundBefore = this.vs[0] - this.vs[1] - this.vs[2], crownBefore = this.vs[0] - this.vs[1] - this.vs[4];
		for (int k = 0; k < 256; k++) {
			int x = bx + (k & 15), z = bz + (k >> 4);
			int[] w = this.clip.cellWords(x, z);
			this.vs[0]++;
			if ((w[0] & LodClip.GEOM_VALID) == 0) {
				this.vs[1]++;
				continue;
			}
			boolean wet = s.water()[k] != LodTile.DRY && s.water()[k] > s.height()[k];
			int surface = wet ? s.water()[k] : s.height()[k];
			boolean realCrown = s.crownLo()[k] > surface;
			boolean lodWet = (w[0] & LodClip.GEOM_WET) != 0;
			// (a frozen lake's top is ice in the real chunk: water all the same)
			BlockState realTopState = LodPalette.stateOf(s.tex()[k] & 1023);
			boolean realWater = wet || realTopState != null && realTopState.is(net.minecraft.world.level.block.Blocks.ICE);
			this.wetStats[(realWater ? 1 : 0) + (lodWet ? 2 : 0)]++;
			boolean lodCrown = (w[0] & LodClip.GEOM_CROWN) != 0;
			int lodTop = (w[0] & 0xFFF) - 512;
			if (realCrown == lodCrown) this.vs[4]++;
			if (realCrown && lodCrown) {
				this.vs[5]++;
				int lodGround = (w[2] & 0xFFF) - 512, thick = (w[0] >>> 15) & 63;
				if (lodGround == surface) this.vs[2]++;
				else if (Math.abs(lodGround - surface) <= 1) this.vs[3]++;
				if (lodTop == s.crownHi()[k] && lodTop - thick == s.crownLo()[k] && w[3] == s.runs()[k] && lodGround == surface) this.vs[6]++;
			} else if (!realCrown && !lodCrown) {
				int real = Math.max(surface, s.crownHi()[k]);
				if (lodTop == real) this.vs[2]++;
				else if (Math.abs(lodTop - real) <= 1) this.vs[3]++;
				// open ground (no tree in either): how far off, and what is there
				BlockState realTop = LodPalette.stateOf(s.tex()[k] & 1023), lodTopState = LodPalette.stateOf(w[4] & 1023);
				boolean tree = realTop != null && (realTop.is(net.minecraft.tags.BlockTags.LEAVES) || realTop.is(net.minecraft.tags.BlockTags.LOGS))
					|| lodTopState != null && (lodTopState.is(net.minecraft.tags.BlockTags.LEAVES) || lodTopState.is(net.minecraft.tags.BlockTags.LOGS));
				if (!tree && !wet) {
					int dy = real - lodTop;
					this.dyHist[Math.clamp(dy + 8, 0, 16)]++;
					if (Math.abs(dy) > 1 && this.groundLogged < 30) {
						this.groundLogged++;
						System.out.printf("mcopt-lod: verify: ground at %d,%d: real %d (%s), far terrain %d (%s)%n", x, z, real, realTop, lodTop, lodTopState);
					}
				}
			}
			if (realCrown != lodCrown && this.verifyLogged < 12) {
				this.verifyLogged++;
				System.out.printf("mcopt-lod: verify: crown mismatch at %d,%d: real %s (lo %d hi %d ground %d), far terrain %s (top %d thick %d)%n", x, z,
					realCrown ? "crown" : "no crown", s.crownLo()[k], s.crownHi()[k], surface, lodCrown ? "crown" : "no crown", lodTop, (w[0] >>> 15) & 63);
			}
			int realPlant = s.plantA()[k] & 1023, lodPlant = w[5] & 1023;
			if (realPlant != 0 == (lodPlant != 0)) this.vs[7]++;
			if (realPlant != 0 && lodPlant != 0) {
				this.vs[8]++;
				if (realPlant == lodPlant) this.vs[9]++;
			}
			if (!realCrown && !lodCrown && !wet && LodConfig.TEXTURES) {
				this.vs[13]++;
				if (sameSprite(s.tex()[k] & 1023, w[4] & 1023, true)) this.vs[10]++;
				if (sameSprite(s.tex()[k] >> 10 & 1023, w[4] >> 10 & 1023, false)) this.vs[11]++;
				int a = LodClip.rgb565(s.top()[k]), b = w[1] & 0xFFFF;
				if (Math.abs((a >> 11) - (b >> 11)) <= 2 && Math.abs((a >> 5 & 63) - (b >> 5 & 63)) <= 4 && Math.abs((a & 31) - (b & 31)) <= 2) this.vs[12]++;
			}
		}
		// per chunk: columns whose surface or crown disagree (clusters: structures, lakes, cascades of trees)
		int groundMiss = (int) (this.vs[0] - this.vs[1] - this.vs[2] - groundBefore), crownMiss = (int) (this.vs[0] - this.vs[1] - this.vs[4] - crownBefore);
		if (groundMiss + crownMiss > 0) this.chunkMiss.put(key, groundMiss << 16 | crownMiss);
		if (groundMiss >= 192 && this.chunkLogged < 16) {
			// a whole chunk off: what is there (its middle column and a corner)
			this.chunkLogged++;
			StringBuilder b = new StringBuilder();
			for (int k : new int[] {8 * 16 + 8, 0}) {
				int[] w = this.clip.cellWords(bx + (k & 15), bz + (k >> 4));
				b.append(String.format(" [%d,%d real h %d water %d top %s under-tex %s | far terrain y %d%s top %s]", bx + (k & 15), bz + (k >> 4), s.height()[k],
					s.water()[k] == LodTile.DRY ? -1 : s.water()[k], LodPalette.stateOf(s.tex()[k] & 1023), LodPalette.stateOf(s.tex()[k] >> 20 & 1023),
					(w[0] & 0xFFF) - 512, (w[0] & LodClip.GEOM_WET) != 0 ? " wet" : "", LodPalette.stateOf(w[4] & 1023)));
			}
			System.out.println("mcopt-lod: verify: chunk off" + b);
		}
		if (this.verified.size() % 64 == 0) {
			long n = this.vs[0] - this.vs[1], dry = Math.max(1, this.vs[13]);
			System.out.printf("mcopt-lod: verify: %d chunks, %d columns (%d without data): ground exact %.2f%% (+-1 %.2f%%), crowns agree %.2f%% (%d both, exact %.2f%%), "
					+ "plants agree %.2f%% (%d both, same block %.2f%%); dry open ground: top block %.2f%%, side block %.2f%%, top color %.2f%%%n",
				this.verified.size(), this.vs[0], this.vs[1], 100.0 * this.vs[2] / Math.max(1, n), 100.0 * (this.vs[2] + this.vs[3]) / Math.max(1, n),
				100.0 * this.vs[4] / Math.max(1, n), this.vs[5], 100.0 * this.vs[6] / Math.max(1, this.vs[5]), 100.0 * this.vs[7] / Math.max(1, n), this.vs[8],
				100.0 * this.vs[9] / Math.max(1, this.vs[8]), 100.0 * this.vs[10] / dry, 100.0 * this.vs[11] / dry, 100.0 * this.vs[12] / dry);
			System.out.println("mcopt-lod: verify: open ground real - far terrain y, -8..8: " + java.util.Arrays.toString(this.dyHist));
			System.out.println("mcopt-lod: verify: water (dry both, real only, far terrain only, both): " + java.util.Arrays.toString(this.wetStats));
			int[] buckets = new int[6];   // chunks by surface misses: 0, 1-15, 16-63, 64-127, 128-191, 192+
			int[] crownBuckets = new int[6];
			for (long v : this.verified) {
				int m = this.chunkMiss.getOrDefault(v, 0), gm = m >>> 16, cm = m & 0xFFFF;
				buckets[gm == 0 ? 0 : gm < 16 ? 1 : gm < 64 ? 2 : gm < 128 ? 3 : gm < 192 ? 4 : 5]++;
				crownBuckets[cm == 0 ? 0 : cm < 16 ? 1 : cm < 64 ? 2 : cm < 128 ? 3 : cm < 192 ? 4 : 5]++;
			}
			System.out.println("mcopt-lod: verify: chunks by columns off (0, 1-15, 16-63, 64-127, 128-191, 192+): surface " + java.util.Arrays.toString(buckets)
				+ ", crowns " + java.util.Arrays.toString(crownBuckets));
			StringBuilder worst = new StringBuilder();
			this.chunkMiss.entrySet().stream().sorted((a, b) -> Integer.compare(b.getValue() >>> 16, a.getValue() >>> 16)).limit(12)
				.forEach(e -> worst.append(' ').append((int) (e.getKey() >> 32) * 16).append(',').append((int) (long) e.getKey() * 16).append(':')
					.append(e.getValue() >>> 16).append('/').append(e.getValue() & 0xFFFF));
			System.out.println("mcopt-lod: verify: worst chunks (block x,z: surface/crown columns off):" + worst);
		}
	}

	/** Whether two palette numbers draw the same sprite on that face (a snow layer's top is a snow block's). */
	private static boolean sameSprite(int a, int b, boolean top) {
		if (a == b) return true;
		BlockState sa = LodPalette.stateOf(a), sb = LodPalette.stateOf(b);
		if (sa == null || sb == null) return false;
		float[] ua = top ? LodColors.look(sa).topUv() : LodColors.look(sa).sideUv(), ub = top ? LodColors.look(sb).topUv() : LodColors.look(sb).sideUv();
		return java.util.Arrays.equals(ua, ub);
	}

	private long lastSave = System.nanoTime();

	/**
	 * Every few seconds: tiles real chunks changed go back to the disk cache (read back from the clipmap, saved on a worker).
	 * By time, not frames: without a generator the cache is the only copy of what the player has seen, and a disconnect
	 * shouldn't take more than a few seconds of it.
	 */
	void saveDirty(boolean all) {
		if (!this.persist) this.dirtyTiles.clear();
		if (this.dirtyTiles.isEmpty()) return;
		long now = System.nanoTime();
		if (!all && now - this.lastSave < 5_000_000_000L) return;
		this.lastSave = now;
		for (long key : this.dirtyTiles) {
			if (this.clip.resident(LodTile.levelOf(key), LodTile.txOf(key), LodTile.tzOf(key))) this.saveTile(key, all);
		}
		this.dirtyTiles.clear();
	}

	/** Everything real chunks changed to the cache now: the dirty tiles, and the saves still queued for the workers. */
	void flush() {
		this.saveDirty(true);
		Runnable r;
		while ((r = this.saves.poll()) != null) r.run();
	}

	/** A resident tile's words, read back from the clipmap, to the disk cache: now, or on a worker. */
	private void saveTile(long key, boolean now) {
		int level = LodTile.levelOf(key), tx = LodTile.txOf(key), tz = LodTile.tzOf(key);
		int[] g = new int[LodTile.CELLS], c = new int[LodTile.CELLS];
		int[] cr = level < this.clip.crownLevels ? new int[LodTile.CELLS] : null;
		int[] tw = level == 0 && LodConfig.TEXTURES ? new int[LodTile.CELLS] : null;
		int[] rn = cr != null ? new int[LodTile.CELLS] : null;
		int[] pl = level == 0 && this.clip.plants ? new int[2 * LodTile.CELLS] : null;
		this.clip.read(level, tx, tz, g, c, cr, tw, rn, pl);
		if (now) {
			this.save(key, g, c, cr, tw, rn, pl);
			return;
		}
		// (queued saves are also kept here, for close to run the ones no worker got to)
		Runnable r = () -> this.save(key, g, c, cr, tw, rn, pl);
		this.saves.add(r);
		this.jobs.add(new Job(0, this.seq.incrementAndGet(), 0, () -> {
			if (this.saves.remove(r)) r.run();
		}));
	}

	/**
	 * Before the windows move to (cx, cz): dirty tiles they would leave go to the cache now (clearing a slot drops its words,
	 * and without a generator the cache is the only other copy). Chunks waiting for a tile that left are dropped after.
	 */
	private void saveLeaving(double cx, double cz) {
		if (!this.persist || this.dirtyTiles.isEmpty()) return;
		int h = this.clip.tilesPerSide / 2;
		var it = this.dirtyTiles.iterator();
		while (it.hasNext()) {
			long key = it.next();
			int level = LodTile.levelOf(key), tx = LodTile.txOf(key), tz = LodTile.tzOf(key), span = this.clip.span(level);
			int x = tx - (Math.floorDiv((int) Math.floor(cx), span) - h), z = tz - (Math.floorDiv((int) Math.floor(cz), span) - h);
			if (x >= 0 && z >= 0 && x < this.clip.tilesPerSide && z < this.clip.tilesPerSide) continue;
			it.remove();
			if (this.clip.resident(level, tx, tz)) this.saveTile(key, false);
		}
	}

	private void dropWaitingOutside() {
		if (!this.waiting.isEmpty()) this.waiting.keySet().removeIf(k -> !this.clip.inWindow(LodTile.levelOf(k), LodTile.txOf(k), LodTile.tzOf(k)));
	}

	/**
	 * Without a generator, every level keeps its tiles over the chunks the client has (within the render distance and a
	 * little), though the finer level draws there: so the chunks reach each level's tiles and its cache, for when that level
	 * draws this area from afar. A tile nearer than this to the camera is requested at every level.
	 */
	private boolean requestNear(double near) {
		return this.chunksOnly && near < this.rdBlocks + 64;
	}

	// ---- render thread ----

	long frame;
	private boolean dirty = true;
	private double reqX = Double.NaN, reqZ;

	/** Takes the workers' results (bounded per frame so a burst can't stall one). */
	void integrate() {
		long deadline = System.nanoTime() + 1_500_000;
		Runnable r;
		while ((r = this.results.poll()) != null) {
			r.run();
			if (System.nanoTime() > deadline) break;
		}
	}

	/**
	 * Moves the windows with the camera and queues what they lack. The scan over every level's window runs when the camera
	 * moved a few blocks or tiles arrived.
	 */
	void update(double cx, double cz, FrustumIntersection frustum, double camY) {
		this.saveLeaving(cx, cz);
		boolean moved = this.clip.recenter(cx, cz);
		if (moved) this.dropWaitingOutside();
		for (int l = 0; l < this.clip.levels; l++) {
			this.winTx[l] = this.clip.winTx[l];
			this.winTz[l] = this.clip.winTz[l];
		}
		if (!moved && !this.dirty && Math.abs(cx - this.reqX) < 16 && Math.abs(cz - this.reqZ) < 16) return;
		this.dirty = false;
		this.missingKeys.clear();
		this.reqX = cx;
		this.reqZ = cz;
		double reach = LodConfig.reachBlocks();
		int needed = 0, missing = 0;
		int top = this.clip.levels - 1;
		for (int l = 0; l <= top; l++) {
			int span = this.clip.span(l);
			// the ring this level draws: from the finer level's switch distance (less a tile) to its own (plus a tile)
			double inner = l == 0 || l == top ? -1 : this.clip.switchDist[l - 1] - span;
			double outer = Math.min(this.clip.switchDist[l] + span, reach + span);
			for (int z = 0; z < this.clip.tilesPerSide; z++) {
				for (int x = 0; x < this.clip.tilesPerSide; x++) {
					int tx = this.clip.winTx[l] + x, tz = this.clip.winTz[l] + z;
					double x0 = (double) tx * span, z0 = (double) tz * span;
					double dx = Math.max(0, Math.max(x0 - cx, cx - (x0 + span))), dz = Math.max(0, Math.max(z0 - cz, cz - (z0 + span)));
					double near = Math.sqrt(dx * dx + dz * dz);
					if (near > outer) continue;
					// entirely inside the inner radius: the finer level covers it (but see requestNear)
					double fx = Math.max(Math.abs(x0 - cx), Math.abs(x0 + span - cx)), fz = Math.max(Math.abs(z0 - cz), Math.abs(z0 + span - cz));
					if (Math.sqrt(fx * fx + fz * fz) < inner && !this.requestNear(near)) continue;
					needed++;
					if (this.clip.resident(l, tx, tz)) continue;
					missing++;
					long key = LodTile.key(l, tx, tz);
					this.missingKeys.add(key);
					if (this.pending.putIfAbsent(key, Boolean.TRUE) != null) continue;
					double priority = near / span;
					if (l == top) priority -= 4;  // the fallback for everything comes first
					if (frustum != null && !frustum.testAab((float) (x0 - cx), (float) (-64 - camY), (float) (z0 - cz), (float) (x0 + span - cx),
						(float) (320 - camY), (float) (z0 + span - cz))) priority = priority * 3 + 2;
					this.jobs.add(new Job(priority, this.seq.incrementAndGet(), key, null));
				}
			}
		}
		this.needed = needed;
		this.missing = missing;
		if (missing == 0 && !this.settled) {
			this.settled = true;
			this.settledNanos = System.nanoTime();
			if (this.firstSettledNanos == 0) this.firstSettledNanos = this.settledNanos;
		}
		if (missing > 0) this.settled = false;
	}

	/**
	 * -Dmcopt.lod.prio=screen: the queue in the order the screen needs: the coarsest level's tiles in view first (the fallback
	 * under everything), then tiles in view by projected area (largest first), then tiles that come into view within a second
	 * at the camera's current velocity, then the rest by distance; level-0 tiles the real terrain covers whole last. The
	 * queue is sorted again when the camera turns (10 degrees), moves (16 blocks) or every half second while tiles are missing.
	 */
	static final boolean PRIO_SCREEN = "screen".equals(System.getProperty("mcopt.lod.prio", ""));
	private final org.joml.Vector3f scanFwd = new org.joml.Vector3f();
	private double velX, velZ, lastX = Double.NaN, lastZ;
	private long lastT, scanT;

	/** The screen-ordered scan's thread (PRIO_SCREEN): the render thread only hands it a snapshot and takes its result. */
	private final java.util.concurrent.ExecutorService scanner = PRIO_SCREEN ? java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "mcopt-lod-scan");
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	}) : null;
	private final java.util.concurrent.atomic.AtomicBoolean scanning = new java.util.concurrent.atomic.AtomicBoolean();
	/** The real terrain's reach (render distance x 16 blocks), set by Lod every frame: level-0 tiles wholly inside it are real chunks' to fill. */
	volatile double rdBlocks;
	/** Scan thread only: when each missing tile was first seen missing in view (its priority grows with the wait). */
	private final java.util.HashMap<Long, Long> firstSeen = new java.util.HashMap<>();
	private volatile ScanResult scanResult;

	private record ScanResult(int needed, int missing, java.util.HashSet<Long> missingKeys) {
	}

	/**
	 * As update, ordering the queue by what the screen needs (PRIO_SCREEN). The render thread moves the windows and decides
	 * when to scan; the scan itself (every level's window, projected areas, the queue re-sorted) runs on its own thread from
	 * a snapshot of the camera and the mask, and its result (what is missing) is taken over at a later frame.
	 */
	void updateScreen(double cx, double cz, FrustumIntersection frustum, double camY, org.joml.Matrix4f viewProj, org.joml.Vector3f fwd,
		java.util.function.Supplier<LodSeam.ChunkMask> maskSnapshot) {
		long now = System.nanoTime();
		if (!Double.isNaN(this.lastX) && now > this.lastT) {
			double dt = (now - this.lastT) / 1e9, a = Math.min(1, dt / 0.25);
			this.velX += ((cx - this.lastX) / dt - this.velX) * a;
			this.velZ += ((cz - this.lastZ) / dt - this.velZ) * a;
		}
		this.lastX = cx;
		this.lastZ = cz;
		this.lastT = now;
		this.saveLeaving(cx, cz);
		boolean moved = this.clip.recenter(cx, cz);
		if (moved) this.dropWaitingOutside();
		for (int l = 0; l < this.clip.levels; l++) {
			this.winTx[l] = this.clip.winTx[l];
			this.winTz[l] = this.clip.winTz[l];
		}
		ScanResult r = this.scanResult;
		if (r != null) {
			this.scanResult = null;
			r.missingKeys().removeIf(k -> this.clip.resident(LodTile.levelOf(k), LodTile.txOf(k), LodTile.tzOf(k)));
			this.missingKeys.clear();
			this.missingKeys.addAll(r.missingKeys());
			this.needed = r.needed();
			this.missing = this.missingKeys.size();
			if (this.missing == 0 && !this.settled) {
				this.settled = true;
				this.settledNanos = System.nanoTime();
				if (this.firstSettledNanos == 0) this.firstSettledNanos = this.settledNanos;
			}
			if (this.missing > 0) this.settled = false;
		}
		boolean turned = fwd.dot(this.scanFwd) < Math.cos(Math.toRadians(10));
		boolean stale = this.missing > 0 && now - this.scanT > 500_000_000L;
		if (!moved && !this.dirty && !turned && !stale && Math.abs(cx - this.reqX) < 16 && Math.abs(cz - this.reqZ) < 16) return;
		if (!this.scanning.compareAndSet(false, true)) return;
		this.dirty = false;
		this.scanFwd.set(fwd);
		this.scanT = now;
		this.reqX = cx;
		this.reqZ = cz;
		int[] wx = this.winTx.clone(), wz = this.winTz.clone();
		org.joml.Matrix4f vp = new org.joml.Matrix4f(viewProj);
		LodSeam.ChunkMask masked = maskSnapshot.get();
		double px = cx + this.velX, pz = cz + this.velZ;
		this.scanner.execute(() -> {
			try {
				this.scanResult = this.scanScreen(cx, cz, camY, px, pz, vp, wx, wz, masked);
			} catch (RuntimeException e) {
				System.out.println("mcopt-lod: scan failed: " + e);
			} finally {
				this.scanning.set(false);
			}
		});
	}

	private ScanResult scanScreen(double cx, double cz, double camY, double px, double pz, org.joml.Matrix4f viewProj, int[] winTx, int[] winTz,
		LodSeam.ChunkMask masked) {
		FrustumIntersection frustum = new FrustumIntersection(viewProj, false);
		double reach = LodConfig.reachBlocks();
		int needed = 0, missing = 0;
		int top = this.clip.levels - 1;
		java.util.HashSet<Long> keys = new java.util.HashSet<>();
		long now = System.nanoTime();
		java.util.HashMap<Long, Double> again = new java.util.HashMap<>();
		org.joml.Vector4f v = new org.joml.Vector4f();
		// (px, pz: where the camera is a second from now)
		boolean fast = Math.hypot(px - cx, pz - cz) > 8;
		for (int l = 0; l <= top; l++) {
			int span = this.clip.span(l);
			double inner = l == 0 || l == top ? -1 : this.clip.switchDist[l - 1] - span;
			double outer = Math.min(this.clip.switchDist[l] + span, reach + span);
			for (int z = 0; z < this.clip.tilesPerSide; z++) {
				for (int x = 0; x < this.clip.tilesPerSide; x++) {
					int tx = winTx[l] + x, tz = winTz[l] + z;
					double x0 = (double) tx * span, z0 = (double) tz * span;
					double dx = Math.max(0, Math.max(x0 - cx, cx - (x0 + span))), dz = Math.max(0, Math.max(z0 - cz, cz - (z0 + span)));
					double near = Math.sqrt(dx * dx + dz * dz);
					if (near > outer) continue;
					double fx = Math.max(Math.abs(x0 - cx), Math.abs(x0 + span - cx)), fz = Math.max(Math.abs(z0 - cz), Math.abs(z0 + span - cz));
					if (Math.sqrt(fx * fx + fz * fz) < inner && !this.requestNear(near)) continue;
					needed++;
					if (this.clip.resident(l, tx, tz)) continue;
					missing++;
					long key = LodTile.key(l, tx, tz);
					keys.add(key);
					double priority;
					if (l == 0 && allMasked(tx, tz, masked)) {
						priority = 1000 + near / span;
					} else if (l == 0 && fast && Math.sqrt(fx * fx + fz * fz) < this.rdBlocks - 16) {
						// moving fast, wholly inside the render distance: real chunks will overwrite it (until then level 1 stands in;
						// a camera at rest gets these first: they're what it sees while its real chunks load)
						priority = 900 + near / span;
					} else if (frustum.testAab((float) (x0 - cx), (float) (-64 - camY), (float) (z0 - cz), (float) (x0 + span - cx), (float) (320 - camY),
						(float) (z0 + span - cz))) {
						// in view: the larger first, and a tile that has waited a second counts as twice as large (none starves)
						double waited = (now - this.firstSeen.computeIfAbsent(key, k -> now)) / 1e9;
						priority = (l == top ? -100 : 0) - log2(area(viewProj, x0 - cx, 40 - camY, z0 - cz, span, 120, v)) - waited;
					} else if (frustum.testAab((float) (x0 - px), (float) (-64 - camY), (float) (z0 - pz), (float) (x0 + span - px), (float) (320 - camY),
						(float) (z0 + span - pz))) {
						priority = (l == top ? -50 : 30) - log2(area(viewProj, x0 - px, 40 - camY, z0 - pz, span, 120, v));
					} else {
						// the coarsest level (the fallback under everything) before any finer tile out of view
						priority = (l == top ? 50 : 100) + near / span;
					}
					if (this.pending.putIfAbsent(key, Boolean.TRUE) != null) {
						again.put(key, priority);
						continue;
					}
					this.jobs.add(new Job(priority, this.seq.incrementAndGet(), key, null));
				}
			}
		}
		if (!again.isEmpty()) {
			// queued generation jobs take their new priority (jobs a worker took meanwhile are simply gone from the drain)
			java.util.ArrayList<Job> all = new java.util.ArrayList<>();
			this.jobs.drainTo(all);
			for (Job j : all) {
				Double p = j.task == null ? again.get(j.key) : null;
				this.jobs.add(p == null ? j : new Job(p, j.seq, j.key, null));
			}
		}
		this.firstSeen.keySet().retainAll(keys);
		return new ScanResult(needed, missing, keys);
	}

	private static double log2(double a) {
		return Math.log(Math.max(1, a)) / Math.log(2);
	}

	private static boolean allMasked(int tx, int tz, LodSeam.ChunkMask masked) {
		for (int z = 0; z < 4; z++) {
			for (int x = 0; x < 4; x++) {
				if (!masked.masked(tx * 4 + x, tz * 4 + z)) return false;
			}
		}
		return true;
	}

	/** Screen pixels of a box's projected bounds (clipped to the screen; corners behind the camera clamp to its plane). */
	static double area(org.joml.Matrix4f m, double x0, double y0, double z0, double span, double height, org.joml.Vector4f v) {
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		for (int i = 0; i < 8; i++) {
			v.set((float) (x0 + ((i & 1) != 0 ? span : 0)), (float) (y0 + ((i & 2) != 0 ? height : 0)), (float) (z0 + ((i & 4) != 0 ? span : 0)), 1);
			m.transform(v);
			float w = Math.max(v.w, 1e-2F);
			float nx = v.x / w, ny = v.y / w;
			minX = Math.min(minX, nx);
			maxX = Math.max(maxX, nx);
			minY = Math.min(minY, ny);
			maxY = Math.max(maxY, ny);
		}
		double ax = Math.max(0, Math.min(1, maxX) - Math.max(-1, minX)), ay = Math.max(0, Math.min(1, maxY) - Math.max(-1, minY));
		return ax * ay * 0.25 * 3456 * 2234;
	}

	/** Tiles the last scan found missing; arrivals tick them off (settled when none is left), only a move scans again. */
	private final java.util.HashSet<Long> missingKeys = new java.util.HashSet<>();

	private void arrived(long key) {
		if (this.missingKeys.remove(key)) {
			this.missing = this.missingKeys.size();
			if (this.missing == 0 && !this.settled) {
				this.settled = true;
				this.settledNanos = System.nanoTime();
				if (this.firstSettledNanos == 0) this.firstSettledNanos = this.settledNanos;
			}
		}
	}

	int pendingJobs() {
		return this.pending.size();
	}

	int queued() {
		return this.jobs.size();
	}
}
