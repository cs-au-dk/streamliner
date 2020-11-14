package dk.casa.streamliner.test.asm;

import dk.casa.streamliner.asm.Utils;
import dk.casa.streamliner.asm.analysis.inter.Context;
import dk.casa.streamliner.asm.analysis.inter.InterValue;
import dk.casa.streamliner.asm.analysis.inter.oracles.StreamLibraryOracle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.Optional;

public class CollectionTypeOracle extends StreamLibraryOracle {
    private final String type;

    public CollectionTypeOracle(String type) {
        this.type = type;
    }

    @Override
    public Optional<Type> queryType(Context context, MethodInsnNode minsn, InterValue receiver) {
        if(minsn.name.equals("stream")
            && Utils.getAncestors(Type.getObjectType(minsn.owner)).contains(Type.getObjectType("java/util/Collection")))
            return Optional.of(type).map(Type::getObjectType);

        return Optional.empty();
    }
}
