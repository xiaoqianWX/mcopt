package mcopt.metal.cpu;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.ARGB;

/**
 * -Dmcopt.cpu.textBatch=true: the world's opaque text (sign text above all) in one draw per glyph page, not one per change of page.
 *
 * In 26.x opaque text with no background (all sign text: dye colours are opaque) is drawn in the solid phase, as one group a
 * frame. The group may reorder its draws (it isn't strictly ordered), and is meant to keep one draw per render type, but it finds
 * the earlier draw by comparing PreparedRenderTypes, records that hold a fresh ScissorState (no equals) each time, so it never
 * does: each change of render type starts a new draw. A glyph's render type is its atlas page and mode (outline: text, front:
 * text_polygon_offset), so a glowing line costs its outline (the text 8 times, a pixel around) and its text, and within each
 * every change of page: Latin, digits, symbols and Chinese are on different pages, and Chinese characters are spread over many
 * (they're added in the order first seen). A wall of Chinese glowing signs is tens of draws a line; of plain ASCII ones, two.
 *
 * Here (TextBatchMixin builds such a group instead of TextFeatureRenderer.buildGroup) the group's glyphs are collected first,
 * then written a render type at a time, each type's glyphs in the order the game writes them: one draw per page and mode on
 * screen. The outline pages go first, then the front pages, so every line's outline is still drawn before its text, as the game
 * draws it (where a glyph's outline meets the next glyph's text at the same depth, what's drawn last shows). The picture is the
 * same: every glyph of this text is drawn with depth testing and writing, its texels opaque or discarded, so the order of glyphs
 * that don't overlap doesn't show, and those that do (a line's own) keep the game's order.
 */
public final class TextBatch {
	private static TextBatch spare;

	/** One render type's glyphs, and for each the index of the submit (pose, light) it belongs to. */
	private static final class Bucket {
		RenderType type;
		/** Written in the second pass: text over outlines (the polygon-offset mode, as signs submit it). */
		boolean front;
		TextRenderable[] glyphs = new TextRenderable[256];
		int[] owner = new int[256];
		int size;

		void add(TextRenderable r, int submit) {
			if (this.size == this.glyphs.length) {
				this.glyphs = Arrays.copyOf(this.glyphs, this.size * 2);
				this.owner = Arrays.copyOf(this.owner, this.size * 2);
			}
			this.glyphs[this.size] = r;
			this.owner[this.size++] = submit;
		}
	}

	private final ArrayList<Bucket> buckets = new ArrayList<>();
	private int used;
	private Bucket last;
	private int submit;
	private Font.DisplayMode mode = Font.DisplayMode.NORMAL;
	private final Font.GlyphVisitor collect = new Font.GlyphVisitor() {
		@Override
		public void acceptRenderable(TextRenderable r) {
			TextBatch.this.bucket(r.renderType(TextBatch.this.mode)).add(r, TextBatch.this.submit);
		}
	};

	private TextBatch() {
	}

	/**
	 * Whether the group is the world's opaque text, the text SubmitNodeCollection sends to the solid phase (canRenderAsSolid:
	 * opaque colour, no background) and that can't be in a strictly ordered phase (only see-through text is).
	 */
	public static boolean wanted(List<TextFeatureRenderer.Submit> submits) {
		if (submits.size() < 2) return false;
		for (TextFeatureRenderer.Submit s : submits) {
			if (s.displayMode() == Font.DisplayMode.SEE_THROUGH || !(s.content() instanceof TextFeatureRenderer.Content.Text t)) return false;
			if (ARGB.alpha(t.color()) != 255 || ARGB.alpha(t.backgroundColor()) != 0) return false;
		}
		return true;
	}

	/** Builds the group as TextFeatureRenderer.buildGroup does, a render type at a time. */
	public static void build(Font font, List<TextFeatureRenderer.Submit> submits, Function<RenderType, VertexConsumer> builders) {
		TextBatch b = spare != null ? spare : new TextBatch();
		spare = null;
		try {
			for (int i = 0, n = submits.size(); i < n; i++) b.collect(font, i, (TextFeatureRenderer.Content.Text) submits.get(i).content(), submits.get(i).displayMode());
			b.write(submits, builders);
		} finally {
			b.clear();
			spare = b;
		}
	}

	/** One submit's glyphs, as TextFeatureRenderer.renderText visits them. */
	private void collect(Font font, int submit, TextFeatureRenderer.Content.Text t, Font.DisplayMode displayMode) {
		this.submit = submit;
		if (t.outlineColor() == 0) {
			this.mode = displayMode;
			font.prepareText(t.string(), t.x(), t.y(), t.color(), t.dropShadow(), false, t.backgroundColor()).visit(this.collect);
		} else {
			Font.PreparedText outline = font.prepare8xTextOutline(t.string(), t.x(), t.y(), t.outlineColor());
			Font.PreparedText text = font.prepareText(t.string(), t.x(), t.y(), t.color(), false, false, 0);
			this.mode = Font.DisplayMode.NORMAL;
			outline.visit(this.collect);
			this.mode = Font.DisplayMode.POLYGON_OFFSET;
			text.visit(this.collect);
		}
	}

	private Bucket bucket(RenderType type) {
		if (this.last != null && this.last.type == type) return this.last;
		for (int i = 0; i < this.used; i++) {
			Bucket b = this.buckets.get(i);
			if (b.type == type || b.type.equals(type)) return this.last = b;
		}
		if (this.used == this.buckets.size()) this.buckets.add(new Bucket());
		Bucket b = this.buckets.get(this.used++);
		b.type = type;
		b.front = this.mode == Font.DisplayMode.POLYGON_OFFSET;
		return this.last = b;
	}

	/** Outline pages, then front pages: one draw each. */
	private void write(List<TextFeatureRenderer.Submit> submits, Function<RenderType, VertexConsumer> builders) {
		for (int pass = 0; pass < 2; pass++) {
			for (int i = 0; i < this.used; i++) {
				Bucket b = this.buckets.get(i);
				if (b.front == (pass == 0)) continue;
				this.write(b, submits, builders.apply(b.type));
			}
		}
	}

	private void write(Bucket b, List<TextFeatureRenderer.Submit> submits, VertexConsumer out) {
		for (int k = 0; k < b.size; k++) {
			TextFeatureRenderer.Submit s = submits.get(b.owner[k]);
			b.glyphs[k].render(s.pose(), out, s.lightCoords(), false);
		}
	}

	private void clear() {
		for (int i = 0; i < this.used; i++) {
			Bucket b = this.buckets.get(i);
			Arrays.fill(b.glyphs, 0, b.size, null);
			b.size = 0;
			b.type = null;
		}
		this.used = 0;
		this.last = null;
	}
}
