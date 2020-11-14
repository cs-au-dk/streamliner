package dk.casa.streamliner.asm.analysis;

import org.objectweb.asm.tree.analysis.Value;

public class GenericValue<V> implements Value {
	public final V value;
	private final int size;

	public GenericValue(int size, V value) {
		this.value = value;
		this.size = size;
	}

	@Override
	public int getSize() {
		return size;
	}

	@Override
	public boolean equals(Object o) {
		if(!(o instanceof GenericValue)) return false;
		GenericValue go = (GenericValue) o;
		return size == go.size && value.equals(go.value);
	}

	@Override
	public int hashCode() {
		return size + value.hashCode();
	}

	@Override
	public String toString() {
		return value.toString();
	}
}
