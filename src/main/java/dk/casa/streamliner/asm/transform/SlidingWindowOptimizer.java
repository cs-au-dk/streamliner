package dk.casa.streamliner.asm.transform;


import dk.casa.streamliner.asm.analysis.InstructionStackEffect;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.Textifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.objectweb.asm.Opcodes.*;

// Simple implementation of a sliding window optimizer
// TODO: Allow making optimizations over labels that are not jump targets (debug info)
public class SlidingWindowOptimizer {
	@FunctionalInterface
	private interface Pattern {
		int apply(MethodNode mn, AbstractInsnNode[] insns, int[] preds, int startIndex);
	}

	private static boolean isClass1Producer(AbstractInsnNode insn) {
		switch(insn.getOpcode()) {
			case ACONST_NULL:
			case ILOAD:
			case FLOAD:
			case ALOAD:
			case ICONST_M1:
			case ICONST_0:
			case ICONST_1:
			case ICONST_2:
			case ICONST_3:
			case ICONST_4:
			case ICONST_5:
			case BIPUSH:
			case SIPUSH:
				return true;

			case LDC:
				Object cst = ((LdcInsnNode) insn).cst;
				return !(cst instanceof Long) && !(cst instanceof Double);

			case GETSTATIC:
				return Type.getType(((FieldInsnNode) insn).desc).getSize() == 1;
		}

		return false;
	}

	private static boolean isClass1Consumer(AbstractInsnNode insn) {
		switch (insn.getOpcode()) {
			case ISTORE:
			case FSTORE:
			case ASTORE:
			case POP:
				return true;

		}

		return false;
	}

	private static boolean isClass1ConsumerProducer(AbstractInsnNode insn) {
		switch (insn.getOpcode()) {
			case INSTANCEOF:
			case CHECKCAST:

			case ANEWARRAY:
			case NEWARRAY:
			case ARRAYLENGTH:
				return true;

			case GETFIELD:
				return Type.getType(((FieldInsnNode) insn).desc).getSize() == 1;

			case INVOKESTATIC:
				// We have to be careful to only include pure functions here
				// since we might remove the call instruction later
				MethodInsnNode minsn = (MethodInsnNode) insn;
				return minsn.owner.equals("java/lang/Integer") && minsn.name.equals("valueOf")
						&& minsn.desc.equals("(I)Ljava/lang/Integer;");
		}

		return false;
	}

	private static boolean isDoubleClass1ConsumerProducer(AbstractInsnNode insn) {
		switch (insn.getOpcode()) {
			case IALOAD:
			case BALOAD:
			case SALOAD:
			case CALOAD:
			case FALOAD:
			case AALOAD:

			case IADD:
			case ISUB:
			case IMUL:
			case IDIV:
			case IOR:
			case IXOR:
			case IAND:
			case ISHR:
			case IUSHR:
			case ISHL:
				return true;

			case INVOKEVIRTUAL:
				MethodInsnNode minsn = (MethodInsnNode) insn;
				return minsn.owner.equals("java/util/stream/StreamOpFlag") &&
						minsn.name.equals("isKnown") && minsn.desc.equals("(I)Z");
		}

		return false;
	}

	private static boolean isClass2Producer(AbstractInsnNode insn) {
		switch (insn.getOpcode()) {
			case LCONST_0:
			case LCONST_1:
			case DCONST_0:
			case DCONST_1:

			case LLOAD:
			case DLOAD:
				return true;

			case LDC:
				Object cst = ((LdcInsnNode) insn).cst;
				return (cst instanceof Long) || (cst instanceof Double);

			case GETSTATIC:
				return Type.getType(((FieldInsnNode) insn).desc).getSize() == 2;
		}

		return false;
	}

	private static boolean isClass2Consumer(AbstractInsnNode insn) {
		switch(insn.getOpcode()) {
			case LSTORE:
			case DSTORE:
				return true;
		}

		return false;
	}

	private static boolean isClass1ConsumerClass2Producer(AbstractInsnNode insn) {
		switch(insn.getOpcode()) {
			case I2L:
			case I2D:
			case F2L:
			case F2D:
				return true;
		}

		return false;
	}

	private static boolean isClass2ConsumerProducer(AbstractInsnNode insn) {
		switch (insn.getOpcode()) {
			case L2D:
			case D2L:
				return true;
		}

		return false;
	}

	private static boolean isDoubleClass2ConsumerProducer(AbstractInsnNode insn) {
		switch (insn.getOpcode()) {
			case LADD:
			case LSUB:
			case LMUL:
			case LDIV:
			case LREM:
			case LOR:
			case LXOR:
			case LAND:

			case DADD:
			case DSUB:
			case DMUL:
			case DDIV:
			case DREM:
				return true;
		}

		return false;
	}

	private static int oppositeBranch(JumpInsnNode insn) {
		switch (insn.getOpcode()) {
			case IF_ACMPEQ: return IF_ACMPNE;
			case IF_ACMPNE: return IF_ACMPEQ;

			case IF_ICMPEQ: return IF_ICMPNE;
			case IF_ICMPNE: return IF_ICMPEQ;

			case IF_ICMPGE: return IF_ICMPLT;
			case IF_ICMPLT: return IF_ICMPGE;

			case IF_ICMPLE: return IF_ICMPGT;
			case IF_ICMPGT: return IF_ICMPLE;

			case IFEQ: return IFNE;
			case IFNE: return IFEQ;

			case IFGE: return IFLT;
			case IFLT: return IFGE;

			case IFLE: return IFGT;
			case IFGT: return IFLE;

			case IFNULL: return IFNONNULL;
			case IFNONNULL: return IFNULL;

			default: throw new RuntimeException("No opposite branch for " + Textifier.OPCODES[insn.getOpcode()]);
		}
	}

	/** Find the next instruction that is executed after insn.
	 	Jumps over labels and line numbers and through GOTOs. */
	private static AbstractInsnNode findNextInstruction(AbstractInsnNode insn) {
		AbstractInsnNode next = insn.getNext();
		if(next.getOpcode() < 0) return findNextInstruction(next);
		else if(next.getOpcode() == GOTO) return findNextInstruction(((JumpInsnNode) next).label);
		return next;
	}

	private static final Pattern[] patterns = {
			(mn, insns, preds, startIndex) -> {
				if(insns[startIndex].getOpcode() == NOP) {
					mn.instructions.remove(insns[startIndex]);
					return 1;
				} else
					return 0;
			},
			(mn, insns, preds, startIndex) -> {
				if(insns.length < startIndex + 2) return 0;
				AbstractInsnNode insn1 = insns[startIndex];
				AbstractInsnNode insn2 = insns[startIndex + 1];

				// ILOAD, POP | LLOAD, POP2 | DUP, POP
				if((isClass1Producer(insn1) && insn2.getOpcode() == POP)
					|| (isClass2Producer(insn1) && insn2.getOpcode() == POP2)
					|| (insn1.getOpcode() == DUP && insn2.getOpcode() == POP)
					|| (insn1.getOpcode() == DUP2 && insn2.getOpcode() == POP2)) {
					mn.instructions.remove(insn1);
					mn.instructions.remove(insn2);
					return 2;
				}

				return 0;
			},
			(mn, insns, preds, startIndex) -> {
				if(insns.length < startIndex + 2) return 0;

				// ARRAYLENGTH, POP -> POP
				int popCode = insns[startIndex + 1].getOpcode();
				if((popCode == POP && isClass1ConsumerProducer(insns[startIndex]))
					|| (popCode == POP2 && isClass2ConsumerProducer(insns[startIndex]))) {

					mn.instructions.remove(insns[startIndex]);
					return 2;
				}

				return 0;
			},
			(mn, insns, preds, startIndex) -> {
				if(insns.length < startIndex + 3) return 0;

				// BIPUSH/CHECKCAST, BIPUSH, POP2 -> BIPUSH/CHECKCAST, POP
				if((isClass1ConsumerProducer(insns[startIndex]) || isClass1Producer(insns[startIndex]))
					&& isClass1Producer(insns[startIndex + 1])
					&& insns[startIndex + 2].getOpcode() == POP2) {

					mn.instructions.remove(insns[startIndex + 2]);
					mn.instructions.set(insns[startIndex + 1], new InsnNode(POP));
					return 3;
				}

				return 0;
			},
			(mn, insns, preds, startIndex) -> {
				if(insns.length < startIndex + 2) return 0;

				// LCMP, POP -> POP2, POP2
				if(insns[startIndex].getOpcode() == LCMP
					&& insns[startIndex + 1].getOpcode() == POP) {

					mn.instructions.set(insns[startIndex], new InsnNode(POP2));
					mn.instructions.set(insns[startIndex + 1], new InsnNode(POP2));
					return 2;
				}

				return 0;
			},
			(mn, insns, preds, startIndex) -> {
				if(insns.length < startIndex + 2) return 0;

				// I2L, POP2 -> POP
				if(isClass1ConsumerClass2Producer(insns[startIndex])
					&& insns[startIndex + 1].getOpcode() == POP2) {

					mn.instructions.set(insns[startIndex], new InsnNode(POP));
					mn.instructions.remove(insns[startIndex + 1]);
					return 2;
				}

				return 0;
			},
			(mn, insns, preds, startIndex) -> {
				if(insns.length < startIndex + 2) return 0;

				// IAND, POP -> POP, POP
				int popCode = insns[startIndex + 1].getOpcode();
				if((popCode == POP && isDoubleClass1ConsumerProducer(insns[startIndex]))
					|| (popCode == POP2 && isDoubleClass2ConsumerProducer(insns[startIndex]))) {

					mn.instructions.set(insns[startIndex], new InsnNode(popCode));
					return 2;
				}

				return 0;
			},
			(mn, insns, preds, startIndex) -> {
				if(insns.length < startIndex + 2) return 0;

				// ILOAD, POP2 -> ILOAD, POP, POP
				if(insns[startIndex + 1].getOpcode() != POP2) return 0;
				AbstractInsnNode insn = insns[startIndex];
				if(isClass1Producer(insn) || isDoubleClass1ConsumerProducer(insn)
					|| isClass1ConsumerProducer(insn)) {

					mn.instructions.insertBefore(insns[startIndex + 1], new InsnNode(POP));
					mn.instructions.set(insns[startIndex + 1], new InsnNode(POP));
					return 2;
				}

				return 0;
			},
			(mn, insns, preds, startIndex) -> {
				if(insns.length < startIndex + 2) return 0;

				// LSHL, POP2 -> POP, POP2
				int op1 = insns[startIndex].getOpcode();
				if(insns[startIndex + 1].getOpcode() == POP2 &&
						(op1 == LSHL || op1 == LSHR || op1 == LUSHR)) {

					mn.instructions.set(insns[startIndex], new InsnNode(POP));
					return 2;
				}

				return 0;
			},
			(mn, insns, preds, startIndex) -> {
				if(insns.length < startIndex + 3) return 0;

				// NULL, ILOAD, DUP_X1 -> ILOAD, NULL, ILOAD
				if(isClass1Producer(insns[startIndex])
						&& isClass1Producer(insns[startIndex + 1])
						&& insns[startIndex + 2].getOpcode() == DUP_X1) {

					// Empty map is fine since we don't clone labels
					mn.instructions.insertBefore(insns[startIndex], insns[startIndex + 1].clone(new HashMap<>()));
					mn.instructions.remove(insns[startIndex + 2]);

					return 3;
				}

				return 0;
			},
			(mn, insns, preds, startIndex) -> {
				if(insns.length < startIndex + 2) return 0;

				// DUP_X1, POP -> SWAP
				if(insns[startIndex].getOpcode() == DUP_X1
					&& insns[startIndex + 1].getOpcode() == POP) {

					mn.instructions.set(insns[startIndex], new InsnNode(SWAP));
					mn.instructions.remove(insns[startIndex + 1]);
					return 2;
				}

				return 0;
			},
			(mn, insns, preds, startIndex) -> {
				if(insns.length < startIndex + 3) return 0;

				// ILOAD, ALOAD, SWAP -> ALOAD, ILOAD
				if(isClass1Producer(insns[startIndex])
						&& isClass1Producer(insns[startIndex + 1])
					    && insns[startIndex + 2].getOpcode() == SWAP) {

					mn.instructions.remove(insns[startIndex + 1]);
					mn.instructions.insertBefore(insns[startIndex], insns[startIndex + 1]);
					mn.instructions.remove(insns[startIndex + 2]);
					return 3;
				}

				return 0;
			},
			(mn, insns, preds, startIndex) -> {
				if(insns.length < startIndex + 4) return 0;

				// ILOAD i, ICONST_1, IADD, ISTORE i
				if(insns[startIndex].getOpcode() == ILOAD
				    && insns[startIndex+1].getOpcode() == ICONST_1
					&& insns[startIndex+2].getOpcode() == IADD
					&& insns[startIndex+3].getOpcode() == ISTORE) {

					VarInsnNode v1 = (VarInsnNode) insns[startIndex];
					VarInsnNode v2 = (VarInsnNode) insns[startIndex+3];
					if(v1.var == v2.var) {
						for (int i = startIndex; i < startIndex + 3; i++)
							mn.instructions.remove(insns[i]);
						mn.instructions.set(v2, new IincInsnNode(v2.var, 1));
						return 4;
					}
				}

				return 0;
			},
			(mn, insns, preds, startIndex) -> {
				AbstractInsnNode cur = insns[startIndex];
				if(cur.getOpcode() != POP) return 0;

				int requiredSize = 1;
				AbstractInsnNode prev = cur.getPrevious();
				List<AbstractInsnNode> swaps = new ArrayList<>();
				while(prev != null) {
					if(isClass1Consumer(prev))
						requiredSize++;
					else if(isClass1Producer(prev))
						requiredSize--;
					else if(isClass2Consumer(prev))
						requiredSize += 2;
					else if(isClass2Producer(prev))
						requiredSize -= 2;
					else if(isClass1ConsumerProducer(prev)) {
						requiredSize--;
						if(requiredSize != 0)
							requiredSize++;
					} else if(isDoubleClass1ConsumerProducer(prev)) {
						requiredSize--;
						if (requiredSize != 0)
							requiredSize += 2;
					} else if(isDoubleClass2ConsumerProducer(prev)) {
						requiredSize -= 2;
						if(requiredSize != 0)
							requiredSize += 4;
					} else if(prev.getOpcode() == DUP) {
						requiredSize -= 2;
						if (requiredSize != 0)
							requiredSize++;
					} else if(prev.getOpcode() == SWAP) {
						if(requiredSize <= 2){
							swaps.add(prev);
							if(requiredSize == 1)
								requiredSize = 2;
							else
								requiredSize = 1;
						}
					} else if(isClass1ConsumerClass2Producer(prev)) {
						requiredSize -= 2;
						if(requiredSize != 0)
							requiredSize++;
					} else if(prev.getOpcode() == IINC) {}
					else if(prev instanceof MethodInsnNode || prev instanceof InvokeDynamicInsnNode) {
						InstructionStackEffect.ConsProd cp = InstructionStackEffect.invokeConsProd(prev, true);
						requiredSize -= cp.produced;
						if(requiredSize == 0) return 0;  // If we pop the result of the method then don't do anything
						requiredSize += cp.consumed;
					} else
						return 0;

					if(requiredSize == 0) {
						mn.instructions.remove(cur);
						mn.instructions.insert(prev, cur);
						swaps.forEach(mn.instructions::remove);
						return 1;
					} else if(requiredSize < 0)
						return 0;

					prev = prev.getPrevious();
				}

				return 0;
			},
			(mn, insns, preds, startIndex) -> {
				if(insns.length < startIndex + 7) return 0;

				// TODO: Can be made more general if needed
				// CNDJMP L0, ICONST 1, GOTO L1, L0, ICONST 0, L1, IFNE L2 ->
				// CNDJMP L2
				if(!(insns[startIndex] instanceof JumpInsnNode)) return 0;
				JumpInsnNode cndjmp = (JumpInsnNode) insns[startIndex];
				LabelNode L0 = cndjmp.label;
				if(insns[startIndex+1].getOpcode() == ICONST_1
					&& insns[startIndex+2].getOpcode() == GOTO
					&& insns[startIndex+3].equals(L0)
					&& insns[startIndex+4].getOpcode() == ICONST_0
					&& insns[startIndex+6].getOpcode() == IFNE) {

					LabelNode L1 = ((JumpInsnNode) insns[startIndex+2]).label;
					if(!L1.equals(insns[startIndex+5])) return 0;

					// Make sure that other code does not jump to L0 or L1
					if(preds[startIndex+3] == 1 && preds[startIndex+5] == 2) {
						cndjmp.setOpcode(oppositeBranch(cndjmp));
						cndjmp.label = ((JumpInsnNode) insns[startIndex+6]).label;

						for (int i = startIndex + 1; i <= startIndex + 6; i++)
							mn.instructions.remove(insns[i]);

						return 7;
					}
				}

				return 0;
			},
			(mn, insns, preds, startIndex) -> {
				AbstractInsnNode curr = insns[startIndex];
				if(curr.getOpcode() != ICONST_1) return 0;

				// ICONST 1, ..., IFNE L0 -> GOTO L0
				AbstractInsnNode next = findNextInstruction(curr);
				if(next.getOpcode() != IFNE) return 0;

				mn.instructions.set(curr, new JumpInsnNode(GOTO, ((JumpInsnNode) next).label));
				return 1;
			}
			/*
			(mn, insns, startIndex) -> {
				if(insns.length < startIndex + 3) return 0;

				// NULL, ILOAD, DUP_X1 -> ILOAD, NULL, ILOAD
				if(isClass1Producer(insns[startIndex])
					&& isClass1Producer(insns[startIndex + 1])
					&& insns[startIndex + 2].getOpcode() == DUP_X1) {

					// Empty map is fine since we don't clone labels
					mn.instructions.insertBefore(insns[startIndex], insns[startIndex + 1].clone(new HashMap<>()));
					mn.instructions.remove(insns[startIndex + 2]);

					return 3;
				}

				return 0;
			}*/
	};

	private static int[] computePredecessors(MethodNode mn, AbstractInsnNode[] insns) {
		int N = insns.length;
		int[] predecessors = new int[N];

		// TODO: LineNumberNodes should also be handled.
		//  Maybe we can just get rid of them?
		for (int i = 0; i < N; i++) {
			AbstractInsnNode insn = insns[i];
			int opcode = insn.getOpcode();
			if(opcode != GOTO && opcode != ATHROW && (opcode < IRETURN || opcode > RETURN) && i+1 < N)
				predecessors[i+1]++;

			if(insn instanceof JumpInsnNode)
				predecessors[mn.instructions.indexOf(((JumpInsnNode) insn).label)]++;
			else if(insn instanceof TableSwitchInsnNode) {
				TableSwitchInsnNode tinsn = (TableSwitchInsnNode) insn;
				predecessors[mn.instructions.indexOf(tinsn.dflt)]++;
				for(LabelNode lbl : tinsn.labels) predecessors[mn.instructions.indexOf(lbl)]++;
			} else if(insn instanceof LookupSwitchInsnNode) {
				LookupSwitchInsnNode linsn = (LookupSwitchInsnNode) insn;
				predecessors[mn.instructions.indexOf(linsn.dflt)]++;
				for(LabelNode lbl : linsn.labels) predecessors[mn.instructions.indexOf(lbl)]++;
			}
		}

		// Add some fake predecessors for labels that are referred to by local variables etc.
		for(LocalVariableNode lvn : mn.localVariables) {
			predecessors[mn.instructions.indexOf(lvn.start)]++;
			predecessors[mn.instructions.indexOf(lvn.end)]++;
		}

		for(TryCatchBlockNode tcbn : mn.tryCatchBlocks) {
			predecessors[mn.instructions.indexOf(tcbn.start)]++;
			predecessors[mn.instructions.indexOf(tcbn.end)]++;
			predecessors[mn.instructions.indexOf(tcbn.handler)]++;
		}

		return predecessors;
	}

	public static void run(MethodNode mn) {
		int originalSize = mn.instructions.size();

		for(boolean changed = true; changed; ) {
			changed = false;
			AbstractInsnNode[] insns = mn.instructions.toArray();
			int[] predecessors = computePredecessors(mn, insns);
			for(int i = 0; i < insns.length; ) {
				boolean anyApplied = false;
				for(Pattern pat : patterns) {
					int jump = pat.apply(mn, insns, predecessors, i);
					if(jump != 0) {
						i += jump;
						anyApplied = true;
						break;
					}
				}

				if(anyApplied) changed = true;
				else i++;
			}
		}

		System.out.println(String.format("Reduced %s size from %d to %d instructions",
				mn.name, originalSize, mn.instructions.size()));
	}
}
