package mcopt.bench.mixin;

import mcopt.bench.Bench;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
abstract class CameraMixin {
	// Before the camera reads the player: the pose for this frame, from the wall clock.
	@Inject(method = "update", at = @At("HEAD"))
	private void mcoptBench$pose(DeltaTracker deltaTracker, CallbackInfo ci) {
		Bench.pose();
	}
}
