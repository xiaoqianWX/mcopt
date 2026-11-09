package mcopt.bench;

import java.util.Collection;
import java.util.Map;

/** Just enough JSON for the result file: objects indented, number arrays on one line (they hold every frame time). */
final class Json {
	private Json() {
	}

	static String write(Object value) {
		StringBuilder sb = new StringBuilder(1 << 16);
		write(sb, value, 0);
		return sb.append('\n').toString();
	}

	/** Frame times in ms, two decimals. */
	record Ms(long[] ns, int count) {
	}

	private static void write(StringBuilder sb, Object v, int depth) {
		switch (v) {
			case null -> sb.append("null");
			case String s -> string(sb, s);
			case Boolean b -> sb.append(b);
			case Double d -> sb.append(Double.isFinite(d) ? String.valueOf(d) : "null");
			case Float f -> sb.append(Float.isFinite(f) ? String.valueOf(f) : "null");
			case Number n -> sb.append(n);
			case Ms ms -> {
				sb.append('[');
				for (int i = 0; i < ms.count; i++) {
					if (i > 0) sb.append(',');
					long q = Math.round(ms.ns[i] / 1e4); // hundredths of a ms
					sb.append(q / 100).append('.');
					long frac = q % 100;
					if (frac < 10) sb.append('0');
					sb.append(frac);
				}
				sb.append(']');
			}
			case Map<?, ?> m -> {
				if (m.isEmpty()) {
					sb.append("{}");
					return;
				}
				sb.append('{');
				boolean first = true;
				for (Map.Entry<?, ?> e : m.entrySet()) {
					sb.append(first ? "\n" : ",\n");
					first = false;
					indent(sb, depth + 1);
					string(sb, String.valueOf(e.getKey()));
					sb.append(": ");
					write(sb, e.getValue(), depth + 1);
				}
				sb.append('\n');
				indent(sb, depth);
				sb.append('}');
			}
			case Collection<?> c -> {
				boolean flat = c.stream().noneMatch(x -> x instanceof Map || x instanceof Collection);
				sb.append('[');
				boolean first = true;
				for (Object x : c) {
					if (flat) {
						if (!first) sb.append(", ");
					} else {
						sb.append(first ? "\n" : ",\n");
						indent(sb, depth + 1);
					}
					first = false;
					write(sb, x, depth + 1);
				}
				if (!flat && !c.isEmpty()) {
					sb.append('\n');
					indent(sb, depth);
				}
				sb.append(']');
			}
			default -> string(sb, v.toString());
		}
	}

	private static void indent(StringBuilder sb, int depth) {
		sb.append("\t".repeat(depth));
	}

	private static void string(StringBuilder sb, String s) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
					else sb.append(c);
				}
			}
		}
		sb.append('"');
	}
}
