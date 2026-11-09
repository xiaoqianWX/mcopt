package mcopt.bench;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/** Whether the terrain around the camera is built: Sodium's build queue when Sodium is loaded, else vanilla's. */
final class Terrain {
	private static boolean sodium = FabricLoader.getInstance().isModLoaded("sodium");

	private Terrain() {
	}

	static String source() {
		return sodium ? "sodium" : "vanilla";
	}

	static boolean built(Minecraft mc) {
		if (sodium) {
			try {
				return Sodium.built();
			} catch (LinkageError e) {
				sodium = false; // another Sodium API: fall back to the chunk-count heuristic alone
				Bench.log("Sodium's terrain API is missing (" + e + "), settling on vanilla's queue and chunk counts");
			}
		}
		return mc.levelRenderer.hasRenderedAllSections();
	}

	/** Sections with geometry in the render lists (Sodium), -1 without Sodium. Stable once nothing more is coming. */
	static int visible() {
		if (!sodium) return -1;
		try {
			return Sodium.visible();
		} catch (LinkageError e) {
			return -1;
		}
	}

	// Separate class: it's only loaded (and Sodium's classes resolved) when Sodium is installed.
	private static final class Sodium {
		static boolean built() {
			SodiumWorldRenderer r = SodiumWorldRenderer.instanceNullable();
			try {
				return r != null && r.isTerrainRenderComplete();
			} catch (NullPointerException noSectionManagerYet) {
				return false;
			}
		}

		static int visible() {
			SodiumWorldRenderer r = SodiumWorldRenderer.instanceNullable();
			try {
				return r == null ? -1 : r.getVisibleChunkCount();
			} catch (NullPointerException noSectionManagerYet) {
				return -1;
			}
		}
	}
}
