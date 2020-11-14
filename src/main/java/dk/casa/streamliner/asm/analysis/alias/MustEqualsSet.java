package dk.casa.streamliner.asm.analysis.alias;

import java.util.HashSet;

// TODO: This can be optimized
public class MustEqualsSet extends HashSet<Integer> {
	public MustEqualsSet() {
		super();
	}

	public MustEqualsSet(int initialCapacity) {
		super(initialCapacity);
	}
}
