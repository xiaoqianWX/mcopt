package mcopt.metal.mixin.lod;

import mcopt.metal.lod.Lod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Far terrain: leaving a world or server (every way out ends here) saves what its chunks changed, before the game may quit. */
@Mixin(Minecraft.class)
abstract class LodDisconnectMixin {
	@Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
	private void mcopt$lodLeaving(Screen screen, boolean keepResourcePacks, boolean stopSound, CallbackInfo ci) {
		Lod.leftWorld();
	}
}
