package dk.casa.streamliner.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public final class ClassNodeCache {
    private final static ClassLoader classLoader = ClassNodeCache.class.getClassLoader();
    private final static HashMap<String, ClassNode> cache = new HashMap<>();

    /* Quick and dirty way to implement a hierarchy between caches.
       Allows us to keep some classes between analysis runs but trash the rest.
       Does not keep a class if it is present in multiple levels!
    */
    private final static Deque<Set<String>> stack = new ArrayDeque<>();
    static { stack.add(new HashSet<>()); }

    public static ClassNode get(String name) {
        return cache.computeIfAbsent(name, k -> {
            ClassNode cn = new ClassNode();

            try(InputStream is = classLoader.getResourceAsStream(name + ".class")) {
                if(is == null) throw new IOException("Class not found");
                ClassReader cr = new ClassReader(Objects.requireNonNull(is));
                cr.accept(cn, ClassReader.EXPAND_FRAMES);
            } catch (IOException e) {
                throw new RuntimeException(name, e);
            }

            stack.getFirst().add(name);
            return cn;
        });
    }

    public static ClassNode tryGet(String name) throws ClassNotFoundException {
        try {
            return get(name);
        } catch(RuntimeException exc) {
            Throwable cause = exc.getCause();
            if(!(cause instanceof IOException)) throw exc;
            throw new ClassNotFoundException(name, cause);
        }
    }

    /** Returns true if there was no mapping for name prior to the call */
    public static boolean put(String name, ClassNode cn) {
    	boolean added = cache.put(name, cn) == null;
    	if(added) stack.getFirst().add(name);
        return added;
    }

    public static void putBack(String name, ClassNode cn) {
        boolean added = cache.put(name, cn) == null;
        if(added) stack.getLast().add(name);
    }

	public static void clear() {
        cache.clear();
	}

	public static void remove(String owner) {
	    cache.remove(owner);
    }

    public static void push() {
        stack.addFirst(new HashSet<>());
    }

    public static void pop() {
        Set<String> s = stack.removeFirst();
        System.err.println("Reclaiming " + s.size() + " classes");
        s.forEach(ClassNodeCache::remove);
    }
}
