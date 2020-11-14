package dk.casa.streamliner.asm.analysis.inter;

import dk.casa.streamliner.utils.Dotable;
import dk.casa.streamliner.asm.InlineMethod;
import dk.casa.streamliner.asm.Utils;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CallGraph implements Dotable {
	private final Context initialContext;

	public CallGraph(Context initialContext) {
		this.initialContext = initialContext;
	}

	private final StringBuilder sb = new StringBuilder();
	private final Set<Context> visited = new HashSet<>();
	private int tarIdx = 0;

	private void f(Context context) {
		if(!visited.add(context)) return;

		String owner = context.getOwner();
		InterFrame[] frames = InterproceduralTypePointerAnalysis.calls.get(context);

		{
			List<String> attrs = new ArrayList<>();
			attrs.add(String.format("label=\"[%d] %s.%s\"", context.getDepth(),
					owner.substring(owner.lastIndexOf('/') + 1), context.getMethod().name));

			if (frames == null)
				attrs.add("shape=rectangle");

			if (owner.equals("java/lang/Object") && context.getMethod().name.equals("<init>"))
				attrs.add("fillcolor=green,style=filled");

			sb.append("\t").append(context.hashCode()).append("[").append(String.join(",", attrs)).append("]\n");
		}

		if(frames == null) return;

		InsnList insns = context.getMethod().instructions;
		for(AbstractInsnNode insn : insns) {
			if(!(insn instanceof MethodInsnNode)) continue;
			MethodInsnNode minsn = (MethodInsnNode) insn;
			int index = insns.indexOf(minsn);
			InterFrame frame = frames[index];
			if(frame == null || frame.isUnreachable()) continue;

			Context newContext = null;
			try {
				List<InterValue> argumentValues = Utils.getArgumentValues(minsn, frame);
				InlineMethod im = InterproceduralTypePointerAnalysis.resolveCall(minsn, argumentValues, frame.getHeap(), context);
				newContext = context.newContext(im.owner, im.mth, index, frame.getHeap(), argumentValues);
			} catch(AnalyzerException exc) {}

			String target;
			if(newContext != null) {
				target = String.valueOf(newContext.hashCode());
				f(newContext);
			} else {
				target = "_unknown_" + tarIdx++;
				sb.append("\t").append(target).append(" [label=\"").append(minsn.owner).append(".").append(minsn.name).append("\", shape=rectangle]\n");
			}

			sb.append("\t").append(context.hashCode()).append(" -> ").append(target).append(" [label=\"").append(index).append("\"]\n");
		}
	}

	@Override
	public String toDot(String label) {
		visited.clear();
		sb.setLength(0); // Clear stringbuilder
		tarIdx = 0;

		sb.append("digraph callgraph {\n\trankdir=LR\n");
		f(initialContext);
		sb.append("}");
		return sb.toString();
	}
}
