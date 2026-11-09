package mcopt.metal;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.util.Collection;
import java.util.List;
import org.lwjgl.sdl.SDLMetal;

/**
 * A CAMetalLayer on the SDL window. The drawable is fetched at blit time rather than frame start: holding it for
 * the whole frame only adds latency and can stall on a drawable the display hasn't released yet.
 */
final class MetalSurface implements GpuSurfaceBackend {
	/** With vsync off, present only the last frame that makes each refresh (see mc_pace); -Dmcopt.metal.pace=false presents every frame. */
	private static final boolean PACE = Boolean.parseBoolean(System.getProperty("mcopt.metal.pace", "true"));
	/** How long before a refresh a finished frame has to reach the compositor to be shown on it. */
	private static final double PACE_MARGIN_S = 0.002;
	/**
	 * With vsync off, the drawable is taken, drawn and presented off the render thread and off the frame's command buffer
	 * (see mc_present_split); -Dmcopt.metal.splitPresent=false keeps both on them. The opt-in cadence probe (GpuTimes) needs
	 * the drawable here, so it keeps the old path.
	 */
	private static final boolean SPLIT = Boolean.parseBoolean(System.getProperty("mcopt.metal.splitPresent", "true"));
	private boolean paced, split;
	private final long ctx;
	private final MetalEncoder encoder;
	private final long view;
	private final long layer;

	MetalSurface(long ctx, MetalEncoder encoder, long window) {
		this.ctx = ctx;
		this.encoder = encoder;
		this.view = SDLMetal.SDL_Metal_CreateView(window);
		this.layer = SDLMetal.SDL_Metal_GetLayer(this.view);
	}

	@Override
	public void configure(GpuSurface.Configuration config) {
		boolean vsync = config.presentMode() == GpuSurface.PresentMode.FIFO;
		// Frame generation schedules every present on the display's refresh grid, which needs vsync (FrameGen).
		Native.layerConfigure(this.ctx, this.layer, config.width(), config.height(), vsync || FrameGen.ENABLED ? 1 : 0);
		this.paced = !vsync && PACE && !FrameGen.ENABLED;
		this.split = !vsync && SPLIT && !FrameGen.ENABLED && !MetalEncoder.GPU_TIMES;
	}

	@Override
	public boolean isSuboptimal() {
		return false;
	}

	@Override
	public void acquireNextTexture() {
	}

	@Override
	public void blitFromTexture(CommandEncoderBackend commandEncoder, GpuTextureView textureView) {
		// mcopt.rec hook: the opt-in recorder (-Dmcopt.rec, mcopt.metal.rec) takes the finished frame, GUI included. Rec.ON is a constant false without it.
		if (mcopt.metal.rec.Rec.ON) mcopt.metal.rec.Rec.frame(this.encoder, textureView);
		FrameGen frameGen = FrameGen.ENABLED ? FrameGen.get(this.encoder) : null;
		if (frameGen != null) {
			frameGen.frame(textureView, this.layer);
			return;
		}
		if (this.paced && !Native.pace(PACE_MARGIN_S)) return;
		if (this.split) {
			// both staging slots still waiting on the compositor: skip this frame's present rather than wait
			if (Native.presentReady(this.ctx)) this.encoder.presentSplit(this.layer, textureView);
			return;
		}
		MetalEvents.Operation event = MetalEvents.begin("nextDrawable", -1, 0);
		long drawable, start = System.nanoTime();
		try {
			drawable = Native.layerNext(this.layer);
		} finally {
			MetalEvents.end(event);
			this.encoder.drawableNanos += System.nanoTime() - start;
		}
		if (drawable == 0) return; // no drawable (window hidden): skip the frame's present
		this.encoder.presentTexture(drawable, textureView);
		this.encoder.afterGpuFinishes(() -> Native.release(drawable));
	}

	@Override
	public void present() {
		// The present was scheduled on the frame's command buffer in blitFromTexture and happens when it's committed.
		// Frame generation shows the frame once its GPU work is done; here the game waits for its next frame's turn (FrameGen).
		FrameGen frameGen = FrameGen.ENABLED ? FrameGen.get(this.encoder) : null;
		if (frameGen != null) frameGen.afterSubmit();
	}

	@Override
	public Collection<GpuSurface.PresentMode> supportedPresentModes() {
		return List.of(GpuSurface.PresentMode.IMMEDIATE, GpuSurface.PresentMode.FIFO);
	}

	@Override
	public void close() {
		FrameGen frameGen = FrameGen.ENABLED ? FrameGen.get(this.encoder) : null;
		if (frameGen != null) frameGen.close();
		Native.presentDrain(); // queued split presents still use the layer
		SDLMetal.SDL_Metal_DestroyView(this.view);
	}
}
