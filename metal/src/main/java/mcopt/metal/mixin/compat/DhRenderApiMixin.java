package mcopt.metal.mixin.compat;

import mcopt.metal.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Distant Horizons on Metal. DH asks which API the game draws with by backend name: "Vulkan" is Vulkan, anything else is
 * OpenGL, so on Metal it took the OpenGL branches of its Blaze3D renderer (the lightmap cast to GlTexture: a crash on the
 * first frame). Its Blaze3D renderer draws through the game's own GPU API, which is what mcopt implements on Metal, and its
 * only Vulkan branches are "not OpenGL" ones; so on Metal it gets VULKAN. Applied only when DH is loaded (CompatMixinPlugin).
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper", remap = false)
abstract class DhRenderApiMixin {
	@Unique private static Object mcopt$notOpenGl;

	@Inject(method = "getMcRenderingApi", at = @At("HEAD"), cancellable = true, remap = false)
	private void mcopt$metalIsNotOpenGl(CallbackInfoReturnable<Object> cir) {
		if (!Renderer.runningMetal()) return;
		if (mcopt$notOpenGl == null) mcopt$notOpenGl = mcopt$vulkan();
		cir.setReturnValue(mcopt$notOpenGl);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Unique
	private static Object mcopt$vulkan() {
		try {
			Class api = Class.forName("com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi", false, DhRenderApiMixin.class.getClassLoader());
			return Enum.valueOf(api, "VULKAN");
		} catch (ClassNotFoundException e) {
			throw new IllegalStateException("Distant Horizons changed: no EDhApiRenderingApi", e);
		}
	}
}
