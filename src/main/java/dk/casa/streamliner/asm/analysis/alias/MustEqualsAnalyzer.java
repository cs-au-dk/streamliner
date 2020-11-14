package dk.casa.streamliner.asm.analysis.alias;

import dk.casa.streamliner.asm.CFG;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.*;

/** Analyzer that uses the MustEqualsFrame and computes an intraprocedural control flow graph */
public class MustEqualsAnalyzer<V extends Value> extends Analyzer<V> {
	private CFG cfg;

	public MustEqualsAnalyzer(Interpreter<V> interpreter) {
		super(interpreter);
	}

	@Override
	protected Frame<V> newFrame(int numLocals, int numStack) {
		return new MustEqualsFrame<>(numLocals, numStack);
	}

	@Override
	protected Frame<V> newFrame(Frame<? extends V> frame) {
		return new MustEqualsFrame<>(frame);
	}

	public MustEqualsFrame<V> getFrame(int index) {
		return (MustEqualsFrame<V>) getFrames()[index];
	}

	@Override
	protected void newControlFlowEdge(int insnIndex, int successorIndex) {
		cfg.addEdge(insnIndex, successorIndex);
		super.newControlFlowEdge(insnIndex, successorIndex);
	}

	@Override
	protected boolean newControlFlowExceptionEdge(int insnIndex, int successorIndex) {
		cfg.addEdge(insnIndex, successorIndex);
		return super.newControlFlowExceptionEdge(insnIndex, successorIndex);
	}

	@Override
	public Frame<V>[] analyze(String owner, MethodNode method) throws AnalyzerException {
		cfg = new CFG(method.instructions.size(), method);
		return super.analyze(owner, method);
	}

	public CFG getCFG() {
		return cfg;
	}
}
