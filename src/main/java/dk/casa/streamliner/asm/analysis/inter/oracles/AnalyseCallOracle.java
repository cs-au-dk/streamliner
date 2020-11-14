package dk.casa.streamliner.asm.analysis.inter.oracles;

import dk.casa.streamliner.asm.analysis.inter.Context;
import org.objectweb.asm.tree.MethodInsnNode;

@FunctionalInterface
public interface AnalyseCallOracle {
	boolean shouldAnalyseCall(Context context, MethodInsnNode minsn);
}
