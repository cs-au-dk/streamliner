package dk.casa.streamliner.asm.transform;

import dk.casa.streamliner.asm.analysis.inter.Context;
import org.objectweb.asm.tree.FieldInsnNode;

@FunctionalInterface
public interface ContextFieldPredicate {
	boolean test(Context context, FieldInsnNode finsn);
}
