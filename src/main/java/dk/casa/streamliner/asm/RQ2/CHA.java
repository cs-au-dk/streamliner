package dk.casa.streamliner.asm.RQ2;

import org.objectweb.asm.tree.ClassNode;

import java.util.*;

import static org.objectweb.asm.Opcodes.ACC_ABSTRACT;

// Compute transitive closure of (subspace of) Class Hierarchy
public class CHA {
    private final Map<String, Set<String>> subclasses = new HashMap<>();
    private final Map<String, ClassNode> cache = new HashMap<>();

    public CHA(Collection<ClassNode> classes) {
        for(ClassNode cn: classes) cache.put(cn.name, cn);
        for(ClassNode cn: classes)
            if((cn.access & ACC_ABSTRACT) == 0)
                upwards(cn.name, cn.name);
        cache.clear();
    }

    private void upwards(String name, String start) {
        if(name == null) return;

        ClassNode cn = cache.get(name);
        if(cn == null) return;

        if(subclasses.computeIfAbsent(name, k -> new HashSet<>()).add(start)) {
            upwards(cn.superName, start);

            for (String itf : cn.interfaces)
                upwards(itf, start);
        }
    }

    public Set<String> getSubclasses(String name) {
        return subclasses.get(name);
    }
}
