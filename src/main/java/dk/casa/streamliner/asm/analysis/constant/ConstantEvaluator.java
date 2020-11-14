package dk.casa.streamliner.asm.analysis.constant;

import dk.casa.streamliner.asm.analysis.FlatElement;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.objectweb.asm.Opcodes.*;

public class ConstantEvaluator {
	private static FlatElement<Number> f(int i) {
		return new FlatElement<>(i);
	}

	private static FlatElement<Number> f(long i) {
		return new FlatElement<>(i);
	}

	private static FlatElement<Number> f(float i) {
		return new FlatElement<>(i);
	}

	private static FlatElement<Number> f(double i) {
		return new FlatElement<>(i);
	}

	public static FlatElement<Number> newOperation(AbstractInsnNode insn) {
		switch(insn.getOpcode()) {
			case ICONST_M1: return f(-1);
			case ICONST_0: return f(0);
			case ICONST_1: return f(1);
			case ICONST_2: return f(2);
			case ICONST_3: return f(3);
			case ICONST_4: return f(4);
			case ICONST_5: return f(5);

			case LCONST_0: return f(0L);
			case LCONST_1: return f(1L);

			case FCONST_0: return f(0.f);
			case FCONST_1: return f(1.f);
			case FCONST_2: return f(2.f);

			case DCONST_0: return f(0.);
			case DCONST_1: return f(1.);

			case BIPUSH:
			case SIPUSH:
				return f(((IntInsnNode) insn).operand);

			case LDC:
				Object constant = ((LdcInsnNode) insn).cst;
				if(constant instanceof Number)
					return new FlatElement<>((Number) constant);
				else break;
		}

		return FlatElement.getTop();
	}

	public static FlatElement<Number> unaryOperation(AbstractInsnNode insn, FlatElement<Number> value) {
		if(!value.isDefined()) return FlatElement.getTop();
		Number v = Objects.requireNonNull(value.value);
		switch (insn.getOpcode()) {
			case IINC: return f(v.intValue() + 1);
			case INEG: return f(-v.intValue());

			case D2I:
			case F2I:
			case L2I:
				return f(v.intValue());
			case I2L:
			case F2L:
			case D2L:
				return f(v.longValue());
			case I2F:
			case L2F:
			case D2F:
				return f(v.floatValue());
			case I2D:
			case L2D:
			case F2D:
				return f(v.doubleValue());

			// Can be extended to support more operations
		}

		return FlatElement.getTop();
	}

	public static FlatElement<Number> binaryOperation(AbstractInsnNode insn, FlatElement<Number> left, FlatElement<Number> right) {
		if(!left.isDefined() || !right.isDefined()) return FlatElement.getTop();
		Number lv = Objects.requireNonNull(left.value), rv = Objects.requireNonNull(right.value);

		switch (insn.getOpcode()) {
			case IADD: return f(lv.intValue() + rv.intValue());
			case ISUB: return f(lv.intValue() - rv.intValue());
			case IMUL: return f(lv.intValue() * rv.intValue());
			case IDIV: return f(lv.intValue() / rv.intValue());
			case IREM: return f(lv.intValue() % rv.intValue());
			case IAND: return f(lv.intValue() & rv.intValue());
			case IOR:  return f(lv.intValue() | rv.intValue());
			case IXOR: return f(lv.intValue() ^ rv.intValue());
			case ISHL: return f(lv.intValue() << rv.intValue());
			case ISHR: return f(lv.intValue() >> rv.intValue());
			case IUSHR: return f(lv.intValue() >>> rv.intValue());

			case LADD: return f(lv.longValue() + rv.longValue());
			case LSUB: return f(lv.longValue() - rv.longValue());
			case LMUL: return f(lv.longValue() * rv.longValue());
			case LDIV: return f(lv.longValue() / rv.longValue());
			case LREM: return f(lv.longValue() % rv.longValue());
			case LAND: return f(lv.longValue() & rv.longValue());
			case LOR: return f(lv.longValue() | rv.longValue());
			case LXOR: return f(lv.longValue() ^ rv.longValue());
			case LSHL: return f(lv.longValue() << rv.intValue());
			case LSHR: return f(lv.longValue() >> rv.intValue());
			case LUSHR: return f(lv.longValue() >>> rv.intValue());
			case LCMP: return f(Long.compare(lv.longValue(), rv.longValue()));
		}

		return FlatElement.getTop();
	}

	/** Returns None if the branch is indeterminate, otherwise Some bool where bool is true iff. the branch is taken */
	public static Optional<Boolean> branchOperation(JumpInsnNode insn, List<FlatElement<Number>> args) {
		switch(insn.getOpcode()) {
			case IFEQ:
			case IFNE:
			case IFLT:
			case IFLE:
			case IFGT:
			case IFGE:
				FlatElement<Number> cst = args.get(0);
				if(!cst.isDefined()) return Optional.empty();

				int intv = cst.value.intValue();
				switch(insn.getOpcode()) {
					case IFEQ: return Optional.of(intv == 0);
					case IFNE: return Optional.of(intv != 0);
					case IFLT: return Optional.of(intv < 0);
					case IFLE: return Optional.of(intv <= 0);
					case IFGT: return Optional.of(intv > 0);
					case IFGE: return Optional.of(intv >= 0);
					default: throw new RuntimeException("What?");
				}

			case IF_ICMPEQ:
			case IF_ICMPNE:
			case IF_ICMPLT:
			case IF_ICMPLE:
			case IF_ICMPGT:
			case IF_ICMPGE:
				FlatElement<Number> cst1 = args.get(0), cst2 = args.get(1);
				if(!cst1.isDefined() || !cst2.isDefined()) return Optional.empty();

				int intv1 = cst1.value.intValue(), intv2 = cst2.value.intValue();
				switch (insn.getOpcode()) {
					case IF_ICMPEQ: return Optional.of(intv1 == intv2);
					case IF_ICMPNE: return Optional.of(intv1 != intv2);
					case IF_ICMPLT: return Optional.of(intv1 < intv2);
					case IF_ICMPLE: return Optional.of(intv1 <= intv2);
					case IF_ICMPGT: return Optional.of(intv1 > intv2);
					case IF_ICMPGE: return Optional.of(intv1 >= intv2);
					default: throw new RuntimeException("What?");
				}

			default:
				return Optional.empty();
		}
	}
}
