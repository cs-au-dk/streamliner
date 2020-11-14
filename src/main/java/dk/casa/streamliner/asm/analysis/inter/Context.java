package dk.casa.streamliner.asm.analysis.inter;

import dk.casa.streamliner.asm.analysis.pointer.Heap;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

public interface Context {
	Heap<InterValue> getHeap();

	String getOwner();
	MethodNode getMethod();

	List<? extends InterValue> getArguments();

	Context getParent();
	int getDepth();

	Context newContext(String owner, MethodNode method, Integer callInsn,
	                   Heap<InterValue> heap, List<? extends InterValue> args);
}
