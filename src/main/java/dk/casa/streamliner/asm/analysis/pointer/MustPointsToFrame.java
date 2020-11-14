package dk.casa.streamliner.asm.analysis.pointer;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;

import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.PUTFIELD;

public class MustPointsToFrame extends HeapFrame<MustPointsToValue> {

	public MustPointsToFrame(int numLocals, int numStack) {
		super(numLocals, numStack);
	}

	public MustPointsToFrame(Frame<? extends MustPointsToValue> frame) {
		super(frame);
	}

	@Override
	public void execute(AbstractInsnNode insn, Interpreter<MustPointsToValue> interpreter) throws AnalyzerException {
		// Handle reads and writes to cells
		int top = getStackSize() - 1;
		MustPointsToValue topValue = getStackSize() > 0 ? getStack(top) : null;
		MustPointsToValue res = null;

		// TODO: Instead of inlining constructors we can handle them here?

		FieldInsnNode finsn;
		int cell;
		switch(insn.getOpcode()) {
			case GETFIELD:
				finsn = (FieldInsnNode) insn;
				cell = topValue.pointsTo();
				res = cells.getField(cell, finsn.name, interpreter.newValue(null));
				break;

			case PUTFIELD:
				finsn = (FieldInsnNode) insn;
				cell = getStack(top - 1).pointsTo();
				cells.setField(cell, finsn.name, topValue);
				break;

				// TODO: We might also have to do some stuff for invokedynamic
		}

		super.execute(insn, interpreter);

		if(res != null)
			setStack(top, res);
	}

	@Override
	protected boolean mergeHeap(Heap<MustPointsToValue> otherHeap, Interpreter<MustPointsToValue> interpreter) throws AnalyzerException {
		boolean cellsChanged = false;
		Set<Integer> indices = cells.keySet();

		for(int i : indices) {
			if(!otherHeap.containsKey(i)) {
				cellsChanged = true;
				cells.getCell(i).toTop(interpreter);
			} else {
				for(Map.Entry<String, MustPointsToValue> entry : cells.getCell(i).entrySet()) {
					String field = entry.getKey();
					MustPointsToValue oldValue = entry.getValue();
					MustPointsToValue newValue = interpreter.merge(oldValue, otherHeap.getField(i, field, interpreter.newValue(null)));
					if(!oldValue.equals(newValue)) {
						cellsChanged = true;
						cells.setField(i, field, newValue);
					}
				}
			}
		}

		return cellsChanged;
	}
}
