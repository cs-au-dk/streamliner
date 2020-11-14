package dk.casa.streamliner.asm.analysis.pointer;

import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;

public abstract class HeapFrame<V extends AbstractPointer & Value> extends Frame<V> {
	protected final Heap<V> cells;

	public HeapFrame(int numLocals, int numStack) {
		super(numLocals, numStack);
		this.cells = new Heap<>();
	}

	public HeapFrame(Frame<? extends V> frame) {
		this(frame.getLocals(), frame.getMaxStackSize());
		init(frame);
	}

	// TODO: Make read-only?
	public Heap<V> getHeap() {
		return cells;
	}

	@Override
	public Frame<V> init(Frame<? extends V> frame) {
		super.init(frame);

		@SuppressWarnings("unchecked")
		HeapFrame<V> mframe = (HeapFrame<V>) frame;
		mframe.cells.copyTo(cells);

		return this;
	}

	protected abstract boolean mergeHeap(Heap<V> otherHeap, Interpreter<V> interpreter) throws AnalyzerException;

	@Override
	public boolean merge(Frame<? extends V> frame, Interpreter<V> interpreter) throws AnalyzerException {
		boolean valuesChanged = super.merge(frame, interpreter);

		@SuppressWarnings("unchecked")
		HeapFrame<V> mframe = (HeapFrame<V>) frame;
		boolean heapChanged = mergeHeap(mframe.cells, interpreter);

		return valuesChanged || heapChanged;
	}

	@Override
	public String toString() {
		String s = super.toString();
		return s + "\nHeap: " + cells;
	}

}
