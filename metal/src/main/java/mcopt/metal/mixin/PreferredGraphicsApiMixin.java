package mcopt.metal.mixin;

import com.mojang.renderpearl.api.device.GpuBackend;
import mcopt.metal.MetalBackend;
import mcopt.metal.Renderer;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Graphics API option does what it says. Default is Metal (mcopt), with the game's OpenGL and Vulkan behind it as
 * fallbacks; OpenGL and Vulkan keep the game's own order. Metal steps aside when {@link Renderer#metalBlocked()} says so.
 * Default's caption names what it starts, so the option shows "Metal (mcopt)" rather than "Default".
 */
@Mixin(PreferredGraphicsApi.class)
abstract class PreferredGraphicsApiMixin {
	@Inject(method = "getBackendsToTry", at = @At("RETURN"), cancellable = true)
	private void mcopt$metalFirst(CallbackInfoReturnable<GpuBackend[]> cir) {
		PreferredGraphicsApi self = (PreferredGraphicsApi) (Object) this;
		System.out.println("[mcopt] " + Renderer.summary(self));
		if (self != PreferredGraphicsApi.DEFAULT || Renderer.metalBlocked() != null) return;
		GpuBackend[] fallbacks = cir.getReturnValue();
		GpuBackend[] backends = new GpuBackend[fallbacks.length + 1];
		backends[0] = new MetalBackend();
		System.arraycopy(fallbacks, 0, backends, 1, fallbacks.length);
		cir.setReturnValue(backends);
	}

	@Inject(method = "caption", at = @At("HEAD"), cancellable = true)
	private void mcopt$caption(CallbackInfoReturnable<Component> cir) {
		PreferredGraphicsApi self = (PreferredGraphicsApi) (Object) this;
		if (self == PreferredGraphicsApi.DEFAULT) cir.setReturnValue(Component.literal(Renderer.label(self)));
	}
}
