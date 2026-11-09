package mcopt.metal.mixin.cpu;

import java.util.List;
import mcopt.metal.cpu.TextBatch;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dmcopt.cpu.textBatch=true: the world's opaque text group (sign text, drawn in the solid phase) is built by TextBatch, a render type at a time. */
@Mixin(TextFeatureRenderer.class)
abstract class TextBatchMixin extends RenderTypeFeatureRenderer<TextFeatureRenderer.Submit> {
	@Inject(method = "buildGroup", at = @At("HEAD"), cancellable = true)
	private void mcopt$outlinesFirst(FeatureFrameContext context, List<TextFeatureRenderer.Submit> submits, CallbackInfo ci) {
		if (!TextBatch.wanted(submits)) return;
		TextBatch.build(context.font(), submits, renderType -> this.getVertexBuilder(renderType));
		ci.cancel();
	}
}
