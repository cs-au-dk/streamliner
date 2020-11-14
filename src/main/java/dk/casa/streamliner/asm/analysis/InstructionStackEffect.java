package dk.casa.streamliner.asm.analysis;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Value;
import org.objectweb.asm.util.Textifier;

import static org.objectweb.asm.Opcodes.*;

/** Copied from Scala's implementation
 *  https://github.com/scala/scala/blob/2.13.x/src/compiler/scala/tools/nsc/backend/jvm/analysis/InstructionStackEffect.scala
 */
public class InstructionStackEffect {
	public static class ConsProd {
		public int consumed, produced;

		public ConsProd(int consumed, int produced) {
			this.consumed = consumed;
			this.produced = produced;
		}
	}

	private static ConsProd t(int consumed, int produced) {
		return new ConsProd(consumed, produced);
	}

	public static ConsProd invokeConsProd(AbstractInsnNode insn, boolean forClassFile) {
		String desc;
		if(insn instanceof MethodInsnNode) desc = ((MethodInsnNode) insn).desc;
		else if(insn instanceof InvokeDynamicInsnNode) desc = ((InvokeDynamicInsnNode) insn).desc;
		else throw new RuntimeException("Invalid instruction type.");

		boolean consumesReceiver = insn.getOpcode() != INVOKESTATIC && insn.getOpcode() != INVOKEDYNAMIC;
		if(forClassFile) {
			int sz = Type.getArgumentsAndReturnSizes(desc);
			int cons = (sz >> 2) - (consumesReceiver ? 0 : 1);
			return t(cons, sz & 0b11);
		} else {
			int cons = Type.getArgumentTypes(desc).length + (consumesReceiver ? 1 : 0);
			int prod = Type.getReturnType(desc) == Type.VOID_TYPE ? 0 : 1;
			return t(cons, prod);
		}
	}

	public static <V extends Value> ConsProd computeConsProd(AbstractInsnNode insn, Frame<V> frame) {
		boolean isSize2;
		switch (insn.getOpcode()) {
			case NOP:
				return t(0, 0);

			case ACONST_NULL:
			case ICONST_M1:
			case ICONST_0:
			case ICONST_1:
			case ICONST_2:
			case ICONST_3:
			case ICONST_4:
			case ICONST_5:
			case FCONST_0:
			case FCONST_1:
			case FCONST_2:
			case BIPUSH:
			case SIPUSH:
			case ILOAD:
			case FLOAD:
			case ALOAD:
			/* We assume always for ASM analysis */
			case LDC:
			case LCONST_0:
			case LCONST_1:
			case DCONST_0:
			case DCONST_1:
			case LLOAD:
			case DLOAD:
				return t(0, 1);

			case IALOAD:
			case FALOAD:
			case AALOAD:
			case BALOAD:
			case CALOAD:
			case SALOAD:
			/* We assume always for ASM analysis */
			case LALOAD:
			case DALOAD:
				return t(2, 1);

			case ISTORE:
			case FSTORE:
			case ASTORE:
				/* We assume always for ASM analysis */
			case LSTORE:
			case DSTORE:

			case POP:
				return t(1, 0);

			case IASTORE:
			case FASTORE:
			case AASTORE:
			case BASTORE:
			case CASTORE:
			case SASTORE:
				/* We assume always for ASM analysis */
			case LASTORE:
			case DASTORE:
				return t(3, 0);

			case POP2:
				isSize2 = frame.getStack(frame.getStackSize() - 1).getSize() == 2;
				return t(isSize2 ? 1 : 2, 0);

			case DUP:
				return t(1, 2);

			case DUP_X1:
				return t(2, 3);

			case DUP_X2:
				isSize2 = frame.getStack(frame.getStackSize() - 1).getSize() == 2;
				return isSize2 ? t(2, 3) : t(3, 4);

			case DUP2:
				isSize2 = frame.getStack(frame.getStackSize() - 1).getSize() == 2;
				return isSize2 ? t(1, 2) : t(2, 4);

			case DUP2_X1:
				isSize2 = frame.getStack(frame.getStackSize() - 1).getSize() == 2;
				return isSize2 ? t(2, 3) : t(3, 5);

			case SWAP: return t(2, 2);

			case IADD:
			case FADD:
			case ISUB:
			case FSUB:
			case IMUL:
			case FMUL:
			case IDIV:
			case FDIV:
			case IREM:
			case FREM:
				/* We assume always for ASM analysis */
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
				return t(2, 1);

			case INEG:
			case FNEG:
				/* We assume always for ASM analysis */
			case LNEG:
			case DNEG:
				return t(1, 1);

			case ISHL:
			case ISHR:
			case IUSHR:
			case IAND:
			case IOR:
			case IXOR:
				/* We assume always for ASM analysis */
			case LSHL:
			case LSHR:
			case LUSHR:
			case LAND:
			case LOR:
			case LXOR:
				return t(2, 1);

			case IINC:
				return t(0, 0);

			case I2F:
			case F2I:
			case I2B:
			case I2C:
			case I2S:
				/* We assume always for ASM analysis */
			case I2L:
			case I2D:
			case F2L:
			case F2D:

			case L2I:
			case L2F:
			case D2I:
			case D2F:

			case L2D:
			case D2L:
				return t(1, 1);

			case FCMPL:
			case FCMPG:
				/* We assume always for ASM analysis */
			case LCMP:
			case DCMPL:
			case DCMPG:
				return t(2, 1);

			case IFEQ:
			case IFNE:
			case IFGT:
			case IFGE:
			case IFLT:
			case IFLE:
				return t(1, 0);

			case IF_ICMPEQ:
			case IF_ICMPNE:
			case IF_ICMPLT:
			case IF_ICMPGE:
			case IF_ICMPGT:
			case IF_ICMPLE:
			case IF_ACMPEQ:
			case IF_ACMPNE:
				return t(2, 0);

			case GOTO:
				return t(0, 0);

			case TABLESWITCH:
			case LOOKUPSWITCH:
				return t(1, 0);

			case GETSTATIC:
				return t(0, 1);

			case PUTSTATIC:
				return t(1, 0);

			case GETFIELD:
				return t(1, 1);

			case PUTFIELD:
				return t(2, 0);

			case INVOKEINTERFACE:
			case INVOKEVIRTUAL:
			case INVOKESTATIC:
			case INVOKESPECIAL:
			case INVOKEDYNAMIC:
				return invokeConsProd(insn, false);

			case IRETURN:
			case FRETURN:
			case DRETURN:
			case LRETURN:
			case ARETURN:
				return t(1, 0);

			case RETURN:
				return t(0, 0);

			case NEW:
				return t(0, 1);

			case NEWARRAY:
			case ANEWARRAY:
			case ARRAYLENGTH:
				return t(1, 1);

			case ATHROW:
				return t(1, 0);

			case CHECKCAST:
			case INSTANCEOF:
				return t(1, 1);

			case MONITORENTER:
			case MONITOREXIT:
				return t(1, 0);

			case IFNULL:
			case IFNONNULL:
				return t(1, 0);

			default:
				// Add more by need
				throw new IllegalArgumentException("Opcode: " + Textifier.OPCODES[insn.getOpcode()] + " is not modelled!");
		}
	}
}
