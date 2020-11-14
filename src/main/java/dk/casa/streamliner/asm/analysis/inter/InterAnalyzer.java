package dk.casa.streamliner.asm.analysis.inter;

import dk.casa.streamliner.asm.analysis.inter.oracles.Oracle;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;

public class InterAnalyzer extends Analyzer<InterValue> {
	private final Oracle oracle;
	private final InterInterpreter interpreter;
	private Context context;

	public InterAnalyzer(InterInterpreter interpreter, Oracle oracle) {
		super(interpreter);
		this.interpreter = interpreter;
		this.oracle = oracle;
	}

	@Override
	protected Frame<InterValue> newFrame(int numLocals, int numStack) {
		// We assume that this method is only called for computeInitialFrame (Currently true in ASM 7.2)
		// Unfortunately we cannot override computeInitialFrame as it is private
		InterFrame frame = new InterFrame(numLocals, numStack);
		context.getHeap().copyTo(frame.getHeap());
		return frame;
	}

	@Override
	protected Frame<InterValue> newFrame(Frame<? extends InterValue> frame) {
		return new InterFrame(frame);
	}

	public InterFrame[] analyze(Context context) throws AnalyzerException {
		this.context = context;
		interpreter.initialiseForAnalysis(context, oracle);
		Frame<InterValue>[] result = super.analyze(context.getOwner(), context.getMethod());
		InterFrame[] castedResult = new InterFrame[result.length];
		System.arraycopy(result, 0, castedResult, 0, result.length);
		InterproceduralTypePointerAnalysis.calls.put(context, castedResult);
		return castedResult;
	}

	@Override
	public Frame<InterValue>[] analyze(String owner, MethodNode method) throws AnalyzerException {
		throw new AnalyzerException(null, "Call with context instead");
	}
}
