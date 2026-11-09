package mcopt.bench.mixin;

import com.mojang.blaze3d.platform.Window;
import mcopt.bench.Bench;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	// The camera is scripted: never capture the cursor, so the Mac stays usable while a run goes on.
	@Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
	private void mcoptBench$noGrab(CallbackInfo ci) {
		ci.cancel();
	}

	// UI screenshots (UiShots): the cursor the screens see is held over the option being shot, wherever the real one is.
	@Inject(method = "getScaledXPos(Lcom/mojang/blaze3d/platform/Window;)D", at = @At("HEAD"), cancellable = true)
	private void mcoptBench$heldX(Window window, CallbackInfoReturnable<Double> cir) {
		double x = Bench.uiMouseX();
		if (!Double.isNaN(x)) cir.setReturnValue(x);
	}

	@Inject(method = "getScaledYPos(Lcom/mojang/blaze3d/platform/Window;)D", at = @At("HEAD"), cancellable = true)
	private void mcoptBench$heldY(Window window, CallbackInfoReturnable<Double> cir) {
		double y = Bench.uiMouseY();
		if (!Double.isNaN(y)) cir.setReturnValue(y);
	}
}
