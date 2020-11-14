package dk.casa.streamliner.asm.analysis.nullness;

import dk.casa.streamliner.asm.analysis.GenericValue;

public class NullnessValue extends GenericValue<Boolean> {
	public NullnessValue(int size, Boolean value) {
		super(size, value);
	}

	public NullnessValue(int size) {
		this(size, false);
	}

	public boolean isNull() {
		return value;
	}

	@Override
	public String toString() {
		return value ? "Null" : "NotNull";
	}
}

/*
public class NullnessValue implements Value {
	private final int size;
	public boolean isNull;

	public NullnessValue(int size) {
		this(size, false);
	}

	public NullnessValue(int size, boolean isNull) {
		this.size = size;
		this.isNull = isNull;
	}

	@Override
	public int getSize() {
		return size;
	}

	@Override
	public boolean equals(Object o) {
		if(!(o instanceof NullnessValue)) return false;
		NullnessValue no = (NullnessValue) o;
		return no.size == size && no.isNull == isNull;
	}

	@Override
	public int hashCode() {
		return size + (isNull ? 13 : 17);
	}

	@Override
	public String toString() {
		return isNull ? "Null" : "NotNull";
	}
}
*/