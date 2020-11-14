package dk.casa.streamliner.asm.analysis;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import static org.objectweb.asm.Opcodes.*;

public class SizeHelper {
	public static int newOperation(AbstractInsnNode insn) {
		switch (insn.getOpcode()) {
			case LCONST_0:
			case LCONST_1:
			case DCONST_0:
			case DCONST_1:
				return 2;
			case LDC:
				Object value = ((LdcInsnNode) insn).cst;
				return value instanceof Long || value instanceof Double ? 2 : 1;
			case GETSTATIC:
				return Type.getType(((FieldInsnNode) insn).desc).getSize();
			default:
				return 1;
		}
	}

	public static int unaryOperation(AbstractInsnNode insn) {
		switch (insn.getOpcode()) {
			case LNEG:
			case DNEG:
			case I2L:
			case I2D:
			case L2D:
			case F2L:
			case F2D:
			case D2L:
				return 2;
			case GETFIELD:
				return Type.getType(((FieldInsnNode) insn).desc).getSize();
			default:
				return 1;
		}
	}

	public static int binaryOperation(AbstractInsnNode insn) {
		switch (insn.getOpcode()) {
			case LALOAD:
			case DALOAD:
			case LADD:
			case DADD:
			case LSUB:
			case DSUB:
			case LMUL:
			case DMUL:
			case LDIV:
			case DDIV:
			case LREM:
			case DREM:
			case LSHL:
			case LSHR:
			case LUSHR:
			case LAND:
			case LOR:
			case LXOR:
				return 2;
			default:
				return 1;
		}
	}

	public static int ternaryOperation(AbstractInsnNode insn) {
		return 1;
	}

	public static int naryOperation(AbstractInsnNode insn) {
		switch (insn.getOpcode()) {
			case MULTIANEWARRAY:
				return 1;
			case INVOKEDYNAMIC:
				return Type.getReturnType(((InvokeDynamicInsnNode) insn).desc).getSize();
			default:
				return Type.getReturnType(((MethodInsnNode) insn).desc).getSize();
		}
	}
}
