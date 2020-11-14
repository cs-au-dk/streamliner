package dk.casa.streamliner.asm.analysis.inter;

import dk.casa.streamliner.utils.Dotable;
import dk.casa.streamliner.asm.analysis.FlatElement;
import dk.casa.streamliner.asm.analysis.InstructionStackEffect;
import dk.casa.streamliner.asm.analysis.constant.ConstantEvaluator;
import dk.casa.streamliner.asm.analysis.pointer.AbstractObject;
import dk.casa.streamliner.asm.analysis.pointer.Heap;
import dk.casa.streamliner.asm.analysis.pointer.HeapFrame;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.objectweb.asm.Opcodes.*;

public class InterFrame extends HeapFrame<InterValue> implements Dotable {
	// Used for keeping track of dead branches
	private boolean wasBottom, isBottom;
	private int jumpResult = 2; // 0: don't jump, 1: jump, 2: unknown

	public InterFrame(int numLocals, int numStack) {
		super(numLocals, numStack);
	}

	public InterFrame(Frame<? extends InterValue> frame) { super(frame); }

	private static final Interpreter<InterValue> voidInterpreter = new VoidInterInterpreter();

	@Override
	public Frame<InterValue> init(Frame<? extends InterValue> frame) {
		InterFrame iframe = (InterFrame) frame;
		wasBottom = isBottom = iframe.isBottom;
		iframe.resetUnreachable();
		return super.init(frame);
	}

	public boolean isUnreachable() { return isBottom; }
	private void resetUnreachable() {
		isBottom = wasBottom;
	}

	private static int toJumpResult(boolean res) { return res ? 1 : 0; }

	@Override
	public void execute(AbstractInsnNode insn, Interpreter<InterValue> interpreter) throws AnalyzerException {
		InterInterpreter interp = (InterInterpreter) interpreter;
		interp.setHeap(cells);

		// Record jump result information
		jumpResult = 2;
		if(insn instanceof JumpInsnNode) {
			switch (insn.getOpcode()) {
				case GOTO:
					jumpResult = 1;
					break;

				case IFNULL:
				case IFNONNULL:
					InterValue value = getStack(getStackSize() - 1);
					if(value.pointer == PointerElement.NULL)
						jumpResult = toJumpResult(insn.getOpcode() == IFNULL);
					else if(value.pointer.isValid())
						jumpResult = toJumpResult(insn.getOpcode() == IFNONNULL);
					break;

				case IF_ACMPEQ:
				case IF_ACMPNE:
					InterValue value1 = getStack(getStackSize() - 2);
					InterValue value2 = getStack(getStackSize() - 1);
					if((!value1.isValid() && value1.pointer != PointerElement.NULL)
						|| (!value2.isValid() && value2.pointer != PointerElement.NULL)) break;
					boolean equal = value1.pointer.equals(value2.pointer);
					if(insn.getOpcode() == IF_ACMPEQ)
						jumpResult = toJumpResult(equal);
					else
						jumpResult = toJumpResult(!equal);
					break;

				default:
					// Try to use Constant propagation information
					int numArgs = InstructionStackEffect.computeConsProd(insn, this).consumed;
					List<FlatElement<Number>> args = IntStream.range(0, numArgs)
							.mapToObj(i -> getStack(getStackSize() - numArgs + i).constant)
							.collect(Collectors.toList());
					Optional<Boolean> res = ConstantEvaluator.branchOperation((JumpInsnNode) insn, args);
					res.ifPresent(jump -> jumpResult = toJumpResult(jump));
			}
		}

		// Skip execution in dead frames
		super.execute(insn, isBottom? voidInterpreter : interpreter);

		// perform abstract GC
		if(!isBottom && insn instanceof MethodInsnNode) {
			Context context = interp.getContext();
			int insnIndex = context.getMethod().instructions.indexOf(insn);
			Set<Integer> calls = InterproceduralTypePointerAnalysis.analysedCalls.get(context);
			if(context.getDepth() != 1 || calls == null || !calls.contains(insnIndex)) return;

			Set<InterValue> roots = new HashSet<>();
			InterproceduralTypePointerAnalysis.staticAllocations.entrySet().stream()
					.map(entry -> new InterValue(new TypeElement(true, Type.getObjectType(entry.getKey())), new PointerElement(entry.getValue())))
					.forEach(roots::add);

			for(int i = 0; i < getStackSize(); i++) roots.add(getStack(i));
			for(int i = 0; i < getLocals(); i++) {
				InterValue loc = getLocal(i);
				if(loc != null) roots.add(loc);
			}

			Set<Integer> reachable = interp.reachableSubgraph(roots);
			if(reachable.size() == cells.size()) return;

			System.err.println("Reclaiming " + (cells.size() - reachable.size()) + " abstract objects");
			cells.keySet().retainAll(reachable);
		}
	}

	@Override
	public void initJumpTarget(int opcode, LabelNode target) {
		super.initJumpTarget(opcode, target);

		/* We make sure to return the isBottom flag to the same as
		   wasBottom (to prevent leaving falsely a live node as unreachable).
		   This happens in the merge-function, which Analyzer calls
		   directly after initJumpTarget */
		if(wasBottom) return;
		if(target == null) // no jump
			isBottom = jumpResult == 1;
		else
			isBottom = jumpResult == 0;
	}

	@Override
	protected boolean mergeHeap(Heap<InterValue> otherHeap, Interpreter<InterValue> interpreter) throws AnalyzerException {
		boolean cellsChanged = false;
		boolean escapedChanged;

		// Missing cells can never be referred to after an ordinary frame merge.
		// The pointer value can only exist in one of the branches, and thus the resulting
		// pointer becomes top - removing all direct pointers to the missing cell.
		for(int index : cells.keySet()) {
			AbstractObject<InterValue> obj = cells.getCell(index),
			                           other = otherHeap.getCell(index);
			if(other != null) cellsChanged |= obj.merge(other, interpreter);
		}

		for(Map.Entry<Integer, AbstractObject<InterValue>> entry : otherHeap.entrySet()) {
			int index = entry.getKey();
			if(!cells.containsKey(index)) cells.allocate(index, new AbstractObject<>(entry.getValue()));
		}


		// Add all escaping cells from other heap
		escapedChanged = cells.getEscaped().addAll(otherHeap.getEscaped());
		/*
		// We only have to record the frame as changed if a cell that exists
		// in both frames becomes escaped in the other.
		for(int index : otherHeap.getEscaped()) {
			if(cells.getEscaped().add(index))
				escapedChanged |= cells.containsKey(index);
		}
		*/

		return cellsChanged || escapedChanged;
	}

	@Override
	public boolean merge(Frame<? extends InterValue> frame, Interpreter<InterValue> interpreter) throws AnalyzerException {
		InterFrame iframe = (InterFrame) frame;

		// We skip the merge if the merge frame is bottom
		if(iframe.isBottom) {
			iframe.resetUnreachable();
			return false;
		} else if(isBottom) {
			init(iframe);
			return true;
		}

		return super.merge(frame, interpreter);
	}

	public String toDot(String label) {
		StringBuilder builder = new StringBuilder();
		builder.append("digraph interframe {\n");

		builder.append(String.format("label=\"%s\";\n\n", label));

		for(int i : cells.keySet()) {
			builder.append(i).append(String.format(" [label=\"%s\n%s\"]\n", i, InterproceduralTypePointerAnalysis.allocationTypes.get(i)));
			for(Map.Entry<String, InterValue> e : cells.getCell(i).entrySet()) {
				InterValue v = e.getValue();
				String nodeName = String.format("\"%s_%s\"", i, e.getKey());
				builder.append(nodeName).append(String.format(" [label=\"%s\", shape=rectangle]\n", e.getValue()));
				builder.append(i).append(" -> ").append(nodeName).append(String.format(" [label=\"%s\"]\n", e.getKey()));

				try {
					int j = v.pointsTo();
					builder.append(nodeName).append(" -> ").append(j).append("\n");

				} catch(AnalyzerException exc) {}
			}
		}

		builder.append("}\n");

		return builder.toString();
	}
}
