package dk.casa.streamliner.asm.analysis.inter.oracles;

import dk.casa.streamliner.asm.Utils;
import dk.casa.streamliner.asm.analysis.inter.Context;
import dk.casa.streamliner.asm.transform.LambdaPreprocessor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.Set;

public class StreamLibraryOracle extends ExhaustiveOracle {
    private static boolean isLibraryClass(String name) {
        return name.startsWith("dk/casa/streamliner/stream/")
                || name.startsWith("java/util/stream/");
    }

    public boolean shouldTrackAllocation(Context context, Type type) {
        String name = type.getInternalName();
        Set<Type> ancestors = Utils.getAncestors(type);
        if(isLibraryClass(name) || ancestors.contains(Type.getObjectType("java/util/Spliterator")))
            return true;

        Context parent = context.getParent();
        if(parent != null && ancestors.contains(Type.getObjectType("java/util/Iterator"))
            && parent.getOwner().equals("java/util/Spliterators$IteratorSpliterator"))
            return true;

        // TODO: 1) Inlining of untracked lambdas (where possible).
        if(name.contains("LambdaModel$")) {
            if (isLibraryClass(parent.getOwner()))
                return true;

            // Heuristic for tracking lambdas that return streams (for e.g. flatMap)
            InvokeDynamicInsnNode idyn = LambdaPreprocessor.models.get(name);
            Type instantiatedMethodtype = (Type) idyn.bsmArgs[2];
            Set<Type> returnTypes = Utils.getAncestors(instantiatedMethodtype.getReturnType());
            if(returnTypes.contains(Type.getObjectType("java/util/stream/BaseStream")) ||
                    returnTypes.contains(Type.getObjectType("dk/casa/streamliner/stream/IntStream")))
                return true;
        }

        return false;
    }

    public boolean shouldAnalyseCall(Context context, MethodInsnNode minsn) {
        Type returnType = Type.getReturnType(minsn.desc);
        Set<Type> ancestors = Utils.getAncestors(returnType);
        return ancestors.contains(Type.getObjectType("java/util/Spliterator"))
                || ancestors.stream().anyMatch(t -> t.getSort() == Type.OBJECT && t.getInternalName().startsWith("java/util/stream"))
                || minsn.name.equals("checkFromToBounds")                // TODO: Analyse for constant prop
                || minsn.owner.equals("java/util/stream/StreamOpFlag")   // TODO: Analyse for constant prop

                // IteratorSpliterator
                || minsn.name.equals("iterator")// && context.getOwner().equals("java/util/Spliterators$IteratorSpliterator"))

                // Track LambdaModel$<...>.create calls and calls to lambda targets that are invoked
                // from within the stream library. (I.e. lambdas passed to intermediate operations).
                || minsn.owner.contains("LambdaModel$")
                || (context.getOwner().contains("LambdaModel$")
                    && isLibraryClass(context.getParent().getOwner()))

                // These utility methods are easy to analyse and let us preserve some type precision (for e.g. Objects.requireNonNull)
                || minsn.owner.equals("java/util/Objects")

                || isLibraryClass(context.getOwner())
                || ancestors.stream().anyMatch(t -> t.getSort() == Type.OBJECT && t.getInternalName().startsWith("dk/casa/streamliner/stream/"));
    }
}
