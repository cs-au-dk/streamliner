package dk.casa.streamliner.asm;

import dk.casa.streamliner.utils.Dotable;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Arrays;
import java.util.HashSet;
import java.util.function.IntFunction;

public class CFG implements Dotable {
	public final HashSet<Integer>[] pred;
	public final HashSet<Integer>[] succ;
	public final AbstractInsnNode[] insns;
	private final MethodNode mn;

	@SuppressWarnings("unchecked")
	public CFG(int size, MethodNode mn) {
		pred = new HashSet[size];
		succ = new HashSet[size];
		IntFunction<HashSet<Integer>> mkHashSet = i -> new HashSet<>();
		Arrays.setAll(pred, mkHashSet);
		Arrays.setAll(succ, mkHashSet);

		this.mn = mn;
		insns = mn.instructions.toArray();
	}

	public void addEdge(int a, int b) {
		succ[a].add(b);
		pred[b].add(a);
	}

	@Override
	public String toDot(String label) {
		StringBuilder builder = new StringBuilder();
		builder.append("digraph cfg {\n");

		builder.append(String.format("label=\"%s\";\n\n", label));

		for(int i = 0; i < succ.length; i++) {
			builder.append(i).append(String.format(" [label=\"%s\n%s\"]\n", i, Utils.toString(insns[i]).replace("\"", "\\\"")));
			for(int j : pred[i]) builder.append(i).append(" -> ").append(j).append(" [style=dashed]\n");
			for(int j : succ[i]) builder.append(i).append(" -> ").append(j).append("\n");
		}

		builder.append("}\n");

		return builder.toString();
	}

	public MethodNode getMethod() {
		return mn;
	}
}
