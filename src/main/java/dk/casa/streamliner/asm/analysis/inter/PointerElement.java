package dk.casa.streamliner.asm.analysis.inter;

import dk.casa.streamliner.asm.analysis.Element;
import dk.casa.streamliner.asm.analysis.pointer.AbstractPointer;
import org.objectweb.asm.tree.analysis.AnalyzerException;

public class PointerElement implements Element<PointerElement>, AbstractPointer {
	public static final PointerElement NULL = new PointerElement(-1);
	public static final PointerElement uTOP = new PointerElement(-2); // Uninteresting
	public static final PointerElement iTOP = new PointerElement(-3); // Interesting Top
	public static final PointerElement TOP = new PointerElement(-4);

	public final int value;

	public PointerElement(int value) {
		this.value = value;
	}

	public boolean isValid() {
		return value >= 0;
	}

	public int pointsTo() throws AnalyzerException {
		if(!isValid())
			throw new AnalyzerException(null, "Value in invalid state for pointsTo lookup");
		return value;
	}

	public boolean maybeInteresting() { return this != uTOP && this != NULL; }

	@Override
	public PointerElement merge(PointerElement other) {
		if(value == other.value) return this;
		if(this == TOP || other == TOP) return TOP;
		// Not equal and not TOP
		if(this == uTOP) return other == NULL ? uTOP : TOP;
		else if(other == uTOP) return this == NULL ? uTOP : TOP;
		return iTOP;
	}

	@Override
	public boolean equals(Object o) {
		if(this == o) return true;
		if(!(o instanceof PointerElement)) return false;
		return value == ((PointerElement) o).value;
	}

	@Override
	public int hashCode() {
		return value;
	}

	@Override
	public String toString() {
		if(this == NULL) return "N";
		else if(this == iTOP) return "⊤ᵢ";
		else if(this == uTOP) return "U";
		else if(this == TOP) return "⊤";
		else return "[" + value + "]";
	}

}
