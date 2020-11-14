package dk.casa.streamliner.asm.analysis.inter;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;

public class PrecisionLossException extends AnalyzerException {
	public PrecisionLossException(AbstractInsnNode insn, String message) {
		super(insn, "Critical loss of precision: " + message);
	}
}
