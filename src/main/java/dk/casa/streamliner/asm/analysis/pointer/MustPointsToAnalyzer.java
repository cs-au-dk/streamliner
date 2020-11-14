package dk.casa.streamliner.asm.analysis.pointer;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.HashMap;
import java.util.Map;

public class MustPointsToAnalyzer extends Analyzer<MustPointsToValue> {
	private final MustPointsToInterpreter interpreter;

	public MustPointsToAnalyzer(MustPointsToInterpreter interpreter) {
		super(interpreter);
		this.interpreter = interpreter;
	}

	@Override
	public Frame<MustPointsToValue>[] analyze(String owner, MethodNode method) throws AnalyzerException {
		// Create a map from instructions to index
		Map<AbstractInsnNode, Integer> indexer = new HashMap<>();
		AbstractInsnNode[] insns = method.instructions.toArray();
		for(int i = 0; i < insns.length && insns[i] != null; i++) {
			int opcode = insns[i].getOpcode();
			if (opcode == NEW || opcode == INVOKEDYNAMIC)
				indexer.put(insns[i], i);
		}

		interpreter.init(indexer);
		return super.analyze(owner, method);
	}

	@Override
	protected Frame<MustPointsToValue> newFrame(int numLocals, int numStack) {
		return new MustPointsToFrame(numLocals, numStack);
	}

	@Override
	protected Frame<MustPointsToValue> newFrame(Frame<? extends MustPointsToValue> frame) {
		return new MustPointsToFrame(frame);
	}

	public MustPointsToFrame getFrame(int index) {
		return (MustPointsToFrame) getFrames()[index];
	}
}
