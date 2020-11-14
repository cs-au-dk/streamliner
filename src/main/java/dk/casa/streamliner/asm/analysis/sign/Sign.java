package dk.casa.streamliner.asm.analysis.sign;

public enum Sign {
	// POS includes ZERO
	ZERO, POS, TOP;

	@Override
	public String toString() {
		if(this == ZERO) return "0";
		else if(this == POS) return "+";
		else return "?";
	}
}
