package dk.casa.streamliner.asm.analysis.unused;

import org.objectweb.asm.tree.analysis.Value;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class PointsToValue implements Value {
	private final int size;
	public final Set<Integer> set;

	public PointsToValue(int size) {
		this(size, Collections.emptySet());
	}

	public PointsToValue(int size, int ptr) {
		this(size, Collections.singleton(ptr));
	}

	public PointsToValue(int size, Collection<Integer> col) {
		this.size = size;
		set = new HashSet<>(col);
	}

	@Override
	public int getSize() {
		return size;
	}

	@Override
	public boolean equals(Object o) {
		if(!(o instanceof PointsToValue)) return false;
		PointsToValue po = (PointsToValue) o;
		return size == po.size && set.equals(po.set);
	}

	@Override
	public int hashCode() {
		return size * 3 + set.hashCode();
	}

	@Override
	public String toString() {
		return set.toString();
	}
}
