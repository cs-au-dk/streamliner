package dk.casa.streamliner.asm.analysis.inter;

import dk.casa.streamliner.asm.analysis.pointer.Heap;
import org.objectweb.asm.tree.MethodNode;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

public class StackContext implements Context {
	private static class StackElement {
		private final Integer callIndex;
		private final String owner;
		private final MethodNode method;

		StackElement(Integer callIndex, String owner, MethodNode method) {
			this.callIndex = callIndex;
			this.owner = owner;
			this.method = method;
		}

		@Override
		public int hashCode() {
			return (callIndex == null ? 0 : callIndex) + 3 * owner.hashCode()
					+ 17 * (method.name.hashCode() + method.desc.hashCode());
		}

		@Override
		public boolean equals(Object o) {
			if(o == this) return true;
			if(!(o instanceof StackElement)) return false;
			StackElement so = (StackElement) o;
			return (Objects.equals(callIndex, so.callIndex))
					&& owner.equals(so.owner)
					&& method.name.equals(so.method.name)
					&& method.desc.equals(so.method.desc);
		}

		@Override
		public String toString() {
			return String.format("%d %s.%s", callIndex, owner, method.name);
		}
	}

	private final Heap<InterValue> heap;
	private final List<? extends InterValue> arguments;
	private final LinkedList<StackElement> callStack;
	private final Context parent;

	private StackContext(Heap<InterValue> heap, List<? extends InterValue> arguments, LinkedList<StackElement> callStack, Context parent) {
		this.heap = heap;
		this.arguments = arguments;
		this.callStack = callStack;
		this.parent = parent;
	}

	public StackContext(String owner, MethodNode mn, Heap<InterValue> heap, List<? extends InterValue> arguments) {
		this(heap, arguments, new LinkedList<>(Collections.singleton(new StackElement(null, owner, mn))), null);
	}

	@Override
	public Heap<InterValue> getHeap() {
		return heap;
	}

	@Override
	public String getOwner() {
		return Objects.requireNonNull(callStack.peek()).owner;
	}

	@Override
	public MethodNode getMethod() {
		return Objects.requireNonNull(callStack.peek()).method;
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
		return callStack.size();
	}

	@Override
	public Context newContext(String owner, MethodNode method, Integer insnIndex, Heap<InterValue> heap, List<? extends InterValue> arguments) {
		LinkedList<StackElement> newStack = new LinkedList<>(callStack);
		newStack.addFirst(new StackElement(insnIndex, owner, method));
		return new StackContext(heap, arguments, newStack, this);
	}

	@Override
	public int hashCode() {
		return callStack.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		if(this == o) return true;
		if(!(o instanceof StackContext)) return false;
		return callStack.equals(((StackContext) o).callStack);
	}

	@Override
	public String toString() {
		return String.format("[%d] %s %s", callStack.size(), getOwner(), getMethod().name);
	}
}
