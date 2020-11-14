package dk.casa.streamliner.utils;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class Counter<T> extends HashMap<T, Integer> {
    private final Collection<T> domain;

    public Counter() {
        this.domain = null;
    }

    public Counter(Collection<T> domain) {
        this.domain = domain;
    }

    private void checkDomain(T key) {
        if(domain != null && !domain.contains(key))
            throw new IllegalArgumentException("Invalid key for counter: " + key);
    }

    public Integer get(Object key) {
        try {
            checkDomain((T) key);
            putIfAbsent((T) key, 0);
            return super.get(key);
        } catch(ClassCastException exc) { throw exc; }
    }

    public void inc(T key, int value) {
        checkDomain(key);
        put(key, get(key) + value);
    }

    public void add(T key) {
        inc(key, 1);
    }

    public void add(Counter<T> other) {
        for(Map.Entry<T, Integer> entry : other.entrySet())
            inc(entry.getKey(), entry.getValue());
    }

    @Override
    public Integer put(T key, Integer value) {
        checkDomain(key);
        return super.put(key, value);
    }
}
