package dk.casa.streamliner.asm.analysis.pointer;

import dk.casa.streamliner.asm.analysis.FlatElement;
import dk.casa.streamliner.asm.analysis.SizeHelper;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Interpreter;

import java.util.List;
import java.util.Map;

import static org.objectweb.asm.Opcodes.*;

public class MustPointsToInterpreter extends Interpreter<MustPointsToValue> {

	private Map<AbstractInsnNode, Integer> indexer;

	public MustPointsToInterpreter() {
		super(ASM7);
	}

	public void init(Map<AbstractInsnNode, Integer> indexer) {
		this.indexer = indexer;
	}

	@Override
	public MustPointsToValue newValue(Type type) {
		if(type == Type.VOID_TYPE) return null;

		return new MustPointsToValue(type == null ? 1 : type.getSize(), FlatElement.getTop());
	}

	@Override
	public MustPointsToValue newParameterValue(boolean isInstanceMethod, int local, Type type) {
		return new MustPointsToValue(type.getSize(), new FlatElement<>(-local - 1));
	}

	@Override
	public MustPointsToValue newOperation(AbstractInsnNode insn) {
		if(insn.getOpcode() == NEW)
			return new MustPointsToValue(1, new FlatElement<>(indexer.get(insn)));

		return new MustPointsToValue(SizeHelper.newOperation(insn), FlatElement.getTop());
	}

	@Override
	public MustPointsToValue copyOperation(AbstractInsnNode insn, MustPointsToValue value) {
		return value;
	}

	@Override
	public MustPointsToValue unaryOperation(AbstractInsnNode insn, MustPointsToValue value) {
		if(insn.getOpcode() == CHECKCAST) return value;
		return new MustPointsToValue(SizeHelper.unaryOperation(insn), FlatElement.getTop());
	}

	@Override
	public MustPointsToValue binaryOperation(AbstractInsnNode insn, MustPointsToValue value1, MustPointsToValue value2) throws AnalyzerException {
		return new MustPointsToValue(SizeHelper.binaryOperation(insn), FlatElement.getTop());
	}

	@Override
	public MustPointsToValue ternaryOperation(AbstractInsnNode insn, MustPointsToValue value1, MustPointsToValue value2, MustPointsToValue value3) throws AnalyzerException {
		return new MustPointsToValue(SizeHelper.ternaryOperation(insn), FlatElement.getTop());
	}

	@Override
	public MustPointsToValue naryOperation(AbstractInsnNode insn, List<? extends MustPointsToValue> values) throws AnalyzerException {
		if(insn.getOpcode() == INVOKEDYNAMIC)
			return new MustPointsToValue(1, new FlatElement<>(indexer.get(insn)));

		return new MustPointsToValue(SizeHelper.naryOperation(insn), FlatElement.getTop());
	}

	@Override
	public void returnOperation(AbstractInsnNode insn, MustPointsToValue value, MustPointsToValue expected) throws AnalyzerException {

	}

	@Override
	public MustPointsToValue merge(MustPointsToValue value1, MustPointsToValue value2) {
		return new MustPointsToValue(Math.min(value1.getSize(), value2.getSize()),
									 value1.value.merge(value2.value));
	}
}
