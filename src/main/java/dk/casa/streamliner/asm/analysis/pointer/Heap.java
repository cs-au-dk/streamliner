package dk.casa.streamliner.asm.analysis.pointer;

import org.objectweb.asm.tree.analysis.Value;

import java.util.*;

public class Heap<V extends AbstractPointer & Value> {
	private final Map<Integer, AbstractObject<V>> cells;
	private final Set<Integer> escaped;

	public Heap() {
		cells = new HashMap<>();
		escaped = new HashSet<>();
	}

	public Heap(Heap<V> other) {
		this();
		other.copyTo(this);
	}

	@Override
	public String toString() {
		return cells.toString();
	}

	// Heap operations

	public AbstractObject<V> getCell(int i) {
		return cells.get(i);
	}

	public AbstractObject<V> allocate(int allocIndex, String name) {
		AbstractObject<V> obj = new AbstractObject<>(name);
		cells.put(allocIndex, obj);
		return obj;
	}

	public void allocate(int allocIndex, AbstractObject<V> obj) {
		cells.put(allocIndex, obj);
	}

	public V getField(int i, String fieldName, V defaultValue) {
		if(!containsKey(i)) return defaultValue;
		return getCell(i).getField(fieldName);
	}

	public void setField(int i, String fieldName, V value) {
		getCell(i).setField(fieldName, value);
	}

	public boolean addEscape(Collection<Integer> escaping) {
		return escaped.addAll(escaping);
	}

	public Set<Integer> getEscaped() {
		return escaped;
	}

	public void copyTo(Heap<V> newHeap) {
		newHeap.clear();
		for(Map.Entry<Integer, AbstractObject<V>> e : cells.entrySet())
			newHeap.cells.put(e.getKey(), new AbstractObject<V>(e.getValue()));

		newHeap.escaped.addAll(escaped);
	}

	// Other operations that go directly through cells

	private void clear() {
		cells.clear();
		escaped.clear();
	}

	public int size() { return cells.size(); }

	public Set<Integer> keySet() {
		return cells.keySet();
	}

	public Set<Map.Entry<Integer, AbstractObject<V>>> entrySet() {
		return Collections.unmodifiableSet(cells.entrySet());
	}

	public boolean containsKey(int i) {
		return cells.containsKey(i);
	}

	@Override
	public int hashCode() {
		return cells.hashCode() + 17 * escaped.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		if(!(o instanceof Heap)) return false;
		Heap oh = (Heap) o;
		return cells.equals(oh.cells) && escaped.equals(oh.escaped);
	}
}
