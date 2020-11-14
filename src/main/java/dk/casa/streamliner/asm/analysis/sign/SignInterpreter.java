package dk.casa.streamliner.asm.analysis.sign;

import dk.casa.streamliner.asm.analysis.FlatElement;
import dk.casa.streamliner.asm.analysis.SizeHelper;
import dk.casa.streamliner.asm.analysis.constant.ConstantEvaluator;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Interpreter;

import java.util.List;

import static org.objectweb.asm.Opcodes.*;

public class SignInterpreter extends Interpreter<SignValue> {
	public SignInterpreter() {
		super(ASM7);
	}

	@Override
	public SignValue newValue(Type type) {
		if(type == Type.VOID_TYPE) return null;

		return new SignValue(type == null? 1 : type.getSize(), Sign.TOP);
	}

	@Override
	public SignValue newOperation(AbstractInsnNode insn) throws AnalyzerException {
		FlatElement<Number> cnst = ConstantEvaluator.newOperation(insn);
		if(cnst.isDefined()) {
			Number num = cnst.value;
			if(num instanceof Integer) {
				int x = num.intValue();
				if(x >= 0)
					return new SignValue(1, x == 0? Sign.ZERO : Sign.POS);
			}
		}

		return new SignValue(SizeHelper.newOperation(insn), Sign.TOP);
	}

	@Override
	public SignValue copyOperation(AbstractInsnNode insn, SignValue value) throws AnalyzerException {
		return value;
	}

	@Override
	public SignValue unaryOperation(AbstractInsnNode insn, SignValue value) throws AnalyzerException {
		if(insn.getOpcode() == ARRAYLENGTH)
			return new SignValue(1, Sign.POS);

		Sign sgn = value.value;
		if(sgn != Sign.TOP) {
			Sign newSgn = null;
			switch (insn.getOpcode()) {
				case IINC:
					newSgn = Sign.POS;
					break;

				case INEG:
					if(sgn == Sign.ZERO) newSgn = Sign.ZERO;
					break;
			}

			if(newSgn != null)
				return new SignValue(1, newSgn);
		}

		return new SignValue(SizeHelper.unaryOperation(insn), Sign.TOP);
	}

	@Override
	public SignValue binaryOperation(AbstractInsnNode insn, SignValue value1, SignValue value2) throws AnalyzerException {
		// TODO: If needed this can be made more precise
		Sign s1 = value1.value, s2 = value2.value;
		if(s1 != Sign.TOP && s2 != Sign.TOP && insn.getOpcode() == IADD) {
			// If they are not POS or TOP, they must both be ZERO
			Sign newSgn = s1 == Sign.POS || s2 == Sign.POS ? Sign.POS : Sign.ZERO;
			return new SignValue(1, newSgn);
		}

		return new SignValue(SizeHelper.binaryOperation(insn), Sign.TOP);
	}

	@Override
	public SignValue ternaryOperation(AbstractInsnNode insn, SignValue value1, SignValue value2, SignValue value3) throws AnalyzerException {
		return new SignValue(SizeHelper.ternaryOperation(insn), Sign.TOP);
	}

	@Override
	public SignValue naryOperation(AbstractInsnNode insn, List<? extends SignValue> values) throws AnalyzerException {
		return new SignValue(SizeHelper.naryOperation(insn), Sign.TOP);
	}

	@Override
	public void returnOperation(AbstractInsnNode insn, SignValue value, SignValue expected) throws AnalyzerException {

	}

	@Override
	public SignValue merge(SignValue value1, SignValue value2) {
		Sign s1 = value1.value, s2 = value2.value;
		if(s1 == Sign.ZERO) return value2;
		else if(s2 == Sign.ZERO) return value1;
		else if(s1 == Sign.POS && s1 == s2) return value1;
		return new SignValue(Math.min(value1.getSize(), value2.getSize()), Sign.TOP);
	}
}
