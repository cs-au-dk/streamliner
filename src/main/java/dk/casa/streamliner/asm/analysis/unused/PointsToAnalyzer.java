package dk.casa.streamliner.asm.analysis.unused;

import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;

public class PointsToAnalyzer extends Analyzer<PointsToValue> {
	/**
	 * Constructs a new {@link Analyzer}.
	 *
	 * @param interpreter the interpreter to use to symbolically interpret the bytecode instructions.
	 */
	public PointsToAnalyzer(Interpreter<PointsToValue> interpreter) {
		super(interpreter);
	}

	@Override
	public Frame<PointsToValue>[] analyze(String owner, MethodNode method) throws AnalyzerException {
		return super.analyze(owner, method);
	}
}
