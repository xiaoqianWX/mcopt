package mcopt.bench;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.ModOptions;
import net.caffeinemc.mods.sodium.client.config.structure.Option;
import net.caffeinemc.mods.sodium.client.config.structure.OptionGroup;
import net.caffeinemc.mods.sodium.client.config.structure.OptionPage;
import net.caffeinemc.mods.sodium.client.config.structure.Page;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.caffeinemc.mods.sodium.client.gui.options.control.ControlElement;
import net.caffeinemc.mods.sodium.client.gui.widgets.OptionListWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/**
 * Screenshots of Sodium's video settings with the mouse resting on given options (run.properties ui=ID,ID,...), so a
 * settings change can be looked at without anyone at the Mac: for each option, its page is opened, the cursor is held
 * over the option's row (MouseHandlerMixin) until the tooltip shows, and the frame is saved as LABEL-ui-N-ID.png. Then
 * the game quits. Instead of the benchmark: the world is never opened.
 */
final class UiShots {
	/** Where the cursor is held, in GUI-scaled coordinates; NaN: not held. Read by MouseHandlerMixin. */
	static volatile double mouseX = Double.NaN, mouseY = Double.NaN;

	private static final int OPEN_TICKS = 10, HOVER_TICKS = 30, TIMEOUT_TICKS = 200;
	private static List<String> targets;
	private static Screen parent;
	private static Path dir;
	private static String prefix;
	private static int index = -1, ticks;
	private static boolean hovering;
	private static volatile boolean shotDone;

	private UiShots() {
	}

	static void start(Minecraft mc, List<String> ids, Path results, String label) {
		targets = ids;
		parent = mc.gui.screen();
		dir = results;
		prefix = label;
		next(mc);
	}

	/** Client tick. True once every target has been shot (or given up on). */
	static boolean tick(Minecraft mc) {
		if (index >= targets.size()) return true;
		ticks++;
		if (!hovering) {
			if (ticks < OPEN_TICKS) return false;
			ControlElement control = find(mc, Identifier.parse(targets.get(index)));
			if (control == null) {
				if (ticks > TIMEOUT_TICKS) {
					Bench.log("ui: no control for " + targets.get(index) + " on screen; skipped");
					next(mc);
				}
				return false;
			}
			mouseX = control.getX() + Math.min(control.getWidth() / 2.0, 60);
			mouseY = control.getY() + control.getHeight() / 2.0;
			hovering = true;
			ticks = 0;
			return false;
		}
		if (ticks == HOVER_TICKS) {
			Path file = dir.resolve(String.format("%s-ui-%d-%s.png", prefix, index, targets.get(index).replaceAll("[^A-Za-z0-9._-]", "_")));
			shotDone = false;
			Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> Util.ioPool().execute(() -> {
				try (image) {
					image.writeToFile(file);
					Bench.log("ui: wrote " + file);
				} catch (Throwable t) {
					Bench.log("ui: " + file + ": " + t);
				}
				shotDone = true;
			}));
		}
		if (ticks > HOVER_TICKS && shotDone) next(mc);
		return false;
	}

	private static void next(Minecraft mc) {
		index++;
		ticks = 0;
		hovering = false;
		mouseX = mouseY = Double.NaN;
		if (index >= targets.size()) {
			mc.gui.setScreen(parent);
			return;
		}
		Identifier id = Identifier.parse(targets.get(index));
		OptionPage page = pageOf(id);
		if (page == null) {
			Bench.log("ui: no Sodium page has " + id + "; skipped");
			next(mc);
			return;
		}
		mc.gui.setScreen(VideoSettingsScreen.createScreen(parent, page));
	}

	/** The page holding the option with this id, after Sodium's replacements (Config.modOptions is private). */
	private static OptionPage pageOf(Identifier id) {
		try {
			Field f = ConfigManager.CONFIG.getClass().getDeclaredField("modOptions");
			f.setAccessible(true);
			Option wanted = ConfigManager.CONFIG.getOption(id);
			@SuppressWarnings("unchecked")
			List<ModOptions> mods = (List<ModOptions>) f.get(ConfigManager.CONFIG);
			for (ModOptions mod : mods) {
				for (Page page : mod.pages()) {
					if (!(page instanceof OptionPage options)) continue;
					for (OptionGroup group : options.groups()) if (group.options().contains(wanted)) return options;
				}
			}
		} catch (ReflectiveOperationException e) {
			Bench.log("ui: " + e);
		}
		return null;
	}

	private static ControlElement find(Minecraft mc, Identifier id) {
		Option wanted = ConfigManager.CONFIG.getOption(id);
		if (wanted == null || !(mc.gui.screen() instanceof VideoSettingsScreen screen)) return null;
		for (GuiEventListener child : screen.children()) {
			if (!(child instanceof OptionListWidget list)) continue;
			for (ControlElement control : list.getControls()) if (control.getOption() == wanted) return control;
		}
		return null;
	}
}
