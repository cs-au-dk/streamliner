package dk.casa.streamliner.asm.analysis.unused;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Interpreter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;

import static org.objectweb.asm.Opcodes.ASM7;

public class PointsToInterpreter extends Interpreter<PointsToValue> {
	private final ToIntFunction<AbstractInsnNode> indexer;

	protected PointsToInterpreter(ToIntFunction<AbstractInsnNode> indexer) {
		super(ASM7);
		this.indexer = indexer;
	}

	@Override
	public PointsToValue newValue(Type type) {
		if(type == Type.VOID_TYPE) return null;
		return new PointsToValue(type == null ? 1 : type.getSize());
	}

	@Override
	public PointsToValue newOperation(AbstractInsnNode insn) {
		return new PointsToValue(1, indexer.applyAsInt(insn));
	}

	@Override
	public PointsToValue copyOperation(AbstractInsnNode insn, PointsToValue value) {
		return value;
	}

	@Override
	public PointsToValue unaryOperation(AbstractInsnNode insn, PointsToValue value)  {
		return null;
	}

	@Override
	public PointsToValue binaryOperation(AbstractInsnNode insn, PointsToValue value1, PointsToValue value2) throws AnalyzerException {
		return null;
	}

	@Override
	public PointsToValue ternaryOperation(AbstractInsnNode insn, PointsToValue value1, PointsToValue value2, PointsToValue value3) throws AnalyzerException {
		return null;
	}

	@Override
	public PointsToValue naryOperation(AbstractInsnNode insn, List<? extends PointsToValue> values) throws AnalyzerException {
		return null;
	}

	@Override
	public void returnOperation(AbstractInsnNode insn, PointsToValue value, PointsToValue expected) throws AnalyzerException {

	}

	@Override
	public PointsToValue merge(PointsToValue value1, PointsToValue value2) {
		Set<Integer> newSet = new HashSet<>(value1.set);
		newSet.addAll(value2.set);
		return new PointsToValue(Math.min(value1.getSize(), value2.getSize()));
	}
}
