package dk.casa.streamliner.asm.analysis;

public interface Element<V> {
	V merge(V other);

	default boolean leq(V other) {
		return merge(other).equals(other);
	}
}
