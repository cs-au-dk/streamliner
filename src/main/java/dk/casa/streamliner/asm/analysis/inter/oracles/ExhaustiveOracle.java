package dk.casa.streamliner.asm.analysis.inter.oracles;

import dk.casa.streamliner.asm.analysis.inter.Context;
import dk.casa.streamliner.asm.analysis.inter.InterValue;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.Optional;

public class ExhaustiveOracle implements Oracle {
    @Override
    public boolean shouldAnalyseCall(Context context, MethodInsnNode minsn) {
        return true;
    }

    @Override
    public boolean shouldTrackAllocation(Context context, Type type) {
        return true;
    }

    @Override
    public Optional<Type> queryType(Context context, MethodInsnNode minsn, InterValue receiver) {
        return Optional.empty();
    }
}
