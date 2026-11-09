package mcopt.metal;

import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.PreferredGraphicsApi;
import org.jspecify.annotations.Nullable;

/**
 * Which renderer the game starts with, and why: one answer for the backend list, the Graphics API option's labels, the
 * Sodium page and the log. The game's own Graphics API option decides. Default is Metal (mcopt), with OpenGL then Vulkan
 * behind it as fallbacks; OpenGL and Vulkan are exactly what they say, with mcopt's other optimizations still on. Metal steps
 * aside for a launch, and the option says why, when something in it can't run on Metal:
 * <ul>
 * <li>{@code mcopt.metal=false} on the command line or in config/mcopt.properties;</li>
 * <li>Iris, which draws with OpenGL itself (it picks its OpenGL or Vulkan mixins from options.txt at load);</li>
 * <li>Distant Horizons set to its OpenGL renderer. On its default renderer (Blaze3D, the game's own GPU API) it draws on
 * Metal: see mixin.compat.DhRenderApiMixin.</li>
 * </ul>
 */
public final class Renderer {
	private static boolean checked;
	private static @Nullable String blocked;

	private Renderer() {
	}

	/** Why Metal can't run in this launch, or null when it can. Fixed for the launch: everything it reads needs a restart. */
	public static synchronized @Nullable String metalBlocked() {
		if (!checked) {
			checked = true;
			Profile.apply(); // mcopt.metal can come from config/mcopt.properties
			blocked = check();
		}
		return blocked;
	}

	private static @Nullable String check() {
		if (!Boolean.parseBoolean(System.getProperty("mcopt.metal", "true"))) return "mcopt.metal=false is set";
		FabricLoader loader = FabricLoader.getInstance();
		if (loader.isModLoaded("iris")) return "Iris draws with OpenGL";
		if (loader.isModLoaded("distanthorizons") && dhOpenGl(loader.getConfigDir().resolve("DistantHorizons.toml"))) {
			return "Distant Horizons is set to its OpenGL renderer";
		}
		return null;
	}

	private static final Pattern DH_ENGINE = Pattern.compile("(?m)^\\s*renderingEngine\\s*=\\s*\"([A-Z_0-9]+)\"");

	/** DH's renderingEngine = "OPEN_GL" (its default, AUTO, is Blaze3D on 26.3 unless Iris is loaded). */
	static boolean dhOpenGl(Path toml) {
		try {
			Matcher m = DH_ENGINE.matcher(Files.readString(toml, StandardCharsets.UTF_8));
			return m.find() && m.group(1).equals("OPEN_GL");
		} catch (IOException e) {
			return false; // no config yet: DH writes AUTO
		}
	}

	/** The backend the game is drawing with ("Metal", "OpenGL", "Vulkan"), or null before the device exists. */
	public static @Nullable String running() {
		try {
			return RenderSystem.getDevice().getDeviceInfo().backendName();
		} catch (RuntimeException notYet) {
			return null;
		}
	}

	public static boolean runningMetal() {
		return "Metal".equals(running());
	}

	/** The option's value as a renderer name: what that choice starts, given what's installed. */
	public static String label(PreferredGraphicsApi api) {
		return switch (api) {
			case DEFAULT -> metalBlocked() == null ? "Metal (mcopt)" : "Default (OpenGL)";
			case OPENGL -> "OpenGL";
			case VULKAN -> "Vulkan (MoltenVK)";
		};
	}

	/** What a backend name means on a Mac, for "Running now". */
	public static String describe(@Nullable String backend) {
		if (backend == null) return "starting";
		return switch (backend) {
			case "Metal" -> "Metal (mcopt)";
			case "Vulkan" -> "Vulkan (MoltenVK)";
			default -> backend;
		};
	}

	/** One line for the log at startup: the choice, what it starts, and why Metal is off when it is. */
	public static String summary(PreferredGraphicsApi api) {
		String reason = metalBlocked();
		return "graphics API option " + api.getSerializedName() + ": trying " + label(api)
			+ (api == PreferredGraphicsApi.DEFAULT && reason != null ? " (Metal is off for this launch: " + reason + ")" : "");
	}
}
