package dk.casa.streamliner.asm.analysis.inter.oracles;

import dk.casa.streamliner.asm.analysis.inter.Context;
import dk.casa.streamliner.asm.analysis.inter.InterValue;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.Optional;

@FunctionalInterface
public interface TypeQueryOracle {
	/** Query for the type of the receiver of a call */
	Optional<Type> queryType(Context context, MethodInsnNode minsn, InterValue receiver);
}
