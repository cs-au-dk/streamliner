package dk.casa.streamliner.asm.analysis.inter;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;

import java.util.List;
import java.util.stream.Collectors;

/** Delegates all calls to a BasicInterpreter */
public class VoidInterInterpreter extends InterInterpreter {
	private InterValue from(BasicValue value) {
		return topValue(fromBasicValue(value));
	}

	@Override
	public InterValue newOperation(AbstractInsnNode insn) throws AnalyzerException {
		return from(basicInterpreter.newOperation(insn));
	}

	@Override
	public InterValue unaryOperation(AbstractInsnNode insn, InterValue value) throws AnalyzerException {
		return from(basicInterpreter.unaryOperation(insn, toBasicValue(value)));
	}

	@Override
	public InterValue binaryOperation(AbstractInsnNode insn, InterValue value1, InterValue value2) throws AnalyzerException {
		return from(basicInterpreter.binaryOperation(insn, toBasicValue(value1), toBasicValue(value2)));
	}

	@Override
	public InterValue ternaryOperation(AbstractInsnNode insn, InterValue value1, InterValue value2, InterValue value3) throws AnalyzerException {
		return from(basicInterpreter.ternaryOperation(insn, toBasicValue(value1), toBasicValue(value2), toBasicValue(value3)));
	}

	@Override
	public InterValue naryOperation(AbstractInsnNode insn, List<? extends InterValue> values) throws AnalyzerException {
		return from(basicInterpreter.naryOperation(insn, values.stream().map(this::toBasicValue).collect(Collectors.toList())));
	}
}
