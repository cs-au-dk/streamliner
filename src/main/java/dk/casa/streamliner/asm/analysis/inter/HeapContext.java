package dk.casa.streamliner.asm.analysis.inter;

import dk.casa.streamliner.asm.analysis.pointer.Heap;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

public class HeapContext implements Context {
	private final String owner;
	private final MethodNode method;
	private final Heap<InterValue> heap;
	private final List<? extends InterValue> arguments;
	private final Context parent;
	private final int depth;

	protected HeapContext(String owner, MethodNode method, Heap<InterValue> heap, List<? extends InterValue> arguments, Context parent, int depth) {
		this.owner = owner;
		this.method = method;
		this.heap = heap;
		this.arguments = arguments;
		this.parent = parent;
		this.depth = depth;
	}

	public HeapContext(String owner, MethodNode method, Heap<InterValue> heap, List<? extends InterValue> arguments) {
		this(owner, method, heap, arguments, null, 0);
	}


	@Override
	public Heap<InterValue> getHeap() {
		return heap;
	}

	@Override
	public String getOwner() {
		return owner;
	}

	@Override
	public MethodNode getMethod() {
		return method;
	}

	@Override
	public List<? extends InterValue> getArguments() {
		return arguments;
	}

	@Override
	public Context getParent() {
		return parent;
	}

	@Override
	public int getDepth() {
		return depth;
	}

	@Override
	public Context newContext(String owner, MethodNode method, Integer callInsn, Heap<InterValue> heap, List<? extends InterValue> arguments) {
		return new HeapContext(owner, method, heap, arguments, this, depth + 1);
	}

	@Override
	public int hashCode() {
		return owner.hashCode() +
				3 * (method.name.hashCode() + method.desc.hashCode()) +
				7 * heap.hashCode() +
				19 * arguments.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		if(!(o instanceof HeapContext)) return false;
		HeapContext oc = (HeapContext) o;
		return owner.equals(oc.owner) &&
				(method.name.equals(oc.method.name) &&
						method.desc.equals(oc.method.desc)) &&
				heap.equals(oc.heap) &&
				arguments.equals(oc.arguments);
	}

	@Override
	public String toString() {
		return String.format("%s.%s %s %s", owner, method.name, heap, arguments);
	}
}
