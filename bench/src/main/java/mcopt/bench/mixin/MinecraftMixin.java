package mcopt.bench.mixin;

import mcopt.bench.Bench;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void mcoptBench$tick(CallbackInfo ci) {
		Bench.tick((Minecraft) (Object) this);
	}

	// Next to the game's own frame-time sample: reached once per presented frame, after present and the frame limiter
	// (renderFrame's tail also runs for frames it skips).
	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/DebugScreenOverlay;logFrameDuration(J)V"))
	private void mcoptBench$frame(boolean advanceGameTime, CallbackInfo ci) {
		Bench.frame((Minecraft) (Object) this);
	}
}
