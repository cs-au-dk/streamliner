package dk.casa.streamliner.asm.analysis;


public class FlatElement<V> implements Element<FlatElement<V>> {
	public final V value;
	public final boolean bot;
	public final boolean top;

	public FlatElement(V value) {
		this.value = value;
		this.bot = false;
		this.top = false;
	}

	private FlatElement(boolean bot, boolean top) {
		this.value = null;
		this.bot = bot;
		this.top = top;
	}

	public boolean isDefined() {
		return !(bot || top);
	}

	public static <V> FlatElement<V> getBot() {
		return new FlatElement<V>(true, false);
	}

	public static <V> FlatElement<V> getTop() {
		return new FlatElement<>(false, true);
	}

	public FlatElement<V> merge(FlatElement<V> other) {
		if(bot) return other;
		else if(other.bot || equals(other)) return this;
		else return getTop();
	}

	@Override
	public boolean equals(Object o) {
		if(!(o instanceof FlatElement)) return false;
		FlatElement fo = (FlatElement) o;
		if(bot) return fo.bot;
		else if(top) return fo.top;
		else if(fo.value == null) return false;
		else return value.equals(fo.value);
	}

	@Override
	public int hashCode() {
		return (bot ? 3 : 1) * (top ? 7 : 1) * (value != null ? value.hashCode() : 1);
	}

	@Override
	public String toString() {
		if(bot) return "⊥";
		else if(top) return "⊤";
		else return "[" + value.toString() + "]";
	}
}
