package dk.casa.streamliner.asm.analysis;

import dk.casa.streamliner.asm.CFG;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

public class LivenessAnalysis {
	private final CFG cfg;
	private final MethodNode mn;

	private HashSet<Integer>[] result;

	public LivenessAnalysis(CFG cfg) {
		this.cfg = cfg;
		mn = cfg.getMethod();
	}

	// Methods for querying analysis results
	public HashSet<Integer> liveIn(AbstractInsnNode insn) {
		return liveIn(mn.instructions.indexOf(insn));
	}

	public HashSet<Integer> liveIn(int index) {
		return result[index];
	}

	public HashSet<Integer> liveOut(int index) {
		HashSet<Integer> res = new HashSet<>();
		for(int j : cfg.succ[index]) if(result[j] != null) res.addAll(result[j]);
		return res;
	}

	public boolean isDeadStore(int index, int local) {
		return !liveOut(index).contains(local);
	}

	public boolean isDeadLoad(int index, Frame<?> frame) {
		int produced = FrameUtils.stackTop(frame) + 1;
		return !liveOut(index).contains(produced);
	}

	// Prepares analysis result
	@SuppressWarnings("unchecked")
	public HashSet<Integer>[] analyze(Frame<?>[] frames) {
		int size = mn.instructions.size();
		AbstractInsnNode[] insns = mn.instructions.toArray();
		result = new HashSet[size];

		// Add exit nodes
		boolean[] queued = new boolean[size];
		ArrayDeque<Integer> Q = new ArrayDeque<>();
		for(int i = 0; i < size; i++)
			if(cfg.succ[i].isEmpty() && !cfg.pred[i].isEmpty()) {
				Q.add(i);
				queued[i] = true;
			}

		while(!Q.isEmpty()) {
			int insnIndex = Q.remove();
			queued[insnIndex] = false;
			AbstractInsnNode insn = insns[insnIndex];
			HashSet<Integer> liveOut = liveOut(insnIndex);

			// Skip labels, linenumbers and frame nodes
			if(insn.getOpcode() >= 0) {
				// The variables assigned in this instruction
				// For variable stores: variables, otherwise produced stack locations
				HashSet<Integer> kill = new HashSet<>();

				// The variables used in this expression
				// For variable loads: variables, otherwise popped stack locations
				HashSet<Integer> gen = new HashSet<>();

				Frame<?> frame = frames[insnIndex];
				int top = FrameUtils.stackTop(frame);

				InstructionStackEffect.ConsProd consProd = InstructionStackEffect.computeConsProd(insn, frame);
				int prodStart = top - consProd.consumed + 1;

				// All stack slots that are assigned in this instruction should be killed
				boolean anyProducedLive = false;
				for (int j = 0; j < consProd.produced; j++) {
					kill.add(prodStart + j);
					if(liveOut.contains(prodStart + j)) anyProducedLive = true;
				}

				switch (insn.getOpcode()) {
					case ALOAD:
					case ILOAD:
					case LLOAD:
					case FLOAD:
					case DLOAD:
						// Is the produced stack slot live?
						if (anyProducedLive) gen.add(((VarInsnNode) insn).var);
						break;

					case ASTORE:
					case ISTORE:
					case LSTORE:
					case FSTORE:
					case DSTORE:
						int local = ((VarInsnNode) insn).var;
						// Is the local live?
						if (liveOut.contains(local)) gen.add(top);
						kill.add(local);
						break;

					case POP:
					case POP2:
						// All popped values are dead
						for (int j = 0; j < consProd.consumed; j++) kill.add(top - j);
						break;

					default:
						// All popped values are live
						if(BLACKLIST.contains(insn.getOpcode()) || anyProducedLive)
							for (int j = 0; j < consProd.consumed; j++) gen.add(top - j);
						break;
				}

				// System.out.format("%d liveOut: %s kill: %s gen: %s\n", insnIndex, liveOut, kill, gen);
				liveOut.removeAll(kill);
				liveOut.addAll(gen);
			}

			if(!liveOut.equals(result[insnIndex])) {
				result[insnIndex] = liveOut;
				for(int j : cfg.pred[insnIndex]) {
					if(!queued[j]) {
						queued[j] = true;
						Q.add(j);
					}
				}
			}
		}

		return result;
	}

	// Instructions whose parameters are always live
	private static final Set<Integer> BLACKLIST = new HashSet<>(Arrays.asList(
			PUTFIELD,
			PUTSTATIC,

			LALOAD,  // Until we can optimize these away we keep them
			DALOAD,

			AASTORE,
			BASTORE,
			SASTORE,
			CASTORE,
			IASTORE,
			LASTORE,
			FASTORE,
			DASTORE,

			ARETURN,
			IRETURN,
			LRETURN,
			FRETURN,
			DRETURN,
			ATHROW,

			INVOKESTATIC,
			INVOKEVIRTUAL,
			INVOKEINTERFACE,
			INVOKESPECIAL,
			INVOKEDYNAMIC,

			IFNULL,
			IFNONNULL,

			IFEQ,
			IFNE,
			IFLT,
			IFLE,
			IFGT,
			IFGE,

			IF_ACMPEQ,
			IF_ACMPNE,

			IF_ICMPEQ,
			IF_ICMPNE,
			IF_ICMPLT,
			IF_ICMPLE,
			IF_ICMPGT,
			IF_ICMPGE,

			TABLESWITCH,
			LOOKUPSWITCH,

			MONITORENTER,
			MONITOREXIT
	));
}
