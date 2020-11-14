package dk.casa.streamliner.asm.analysis.pointer;

import dk.casa.streamliner.asm.Utils;
import org.apache.commons.math3.util.Pair;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.objectweb.asm.Opcodes.ACC_STATIC;

public class AbstractObject<V extends AbstractPointer & Value> {
	private final String className;
	private final boolean isStatic;
	private final Map<String, V> fields;

	public AbstractObject(String className, boolean isStatic) {
		this.className = className;
		this.isStatic = isStatic;
		this.fields = new HashMap<>();
	}

	public AbstractObject(String className) {
		this(className, false);
	}

	public AbstractObject(AbstractObject<V> obj) {
		this.className = obj.className;
		this.isStatic = obj.isStatic;
		this.fields = new HashMap<>(obj.fields);
	}

	public String getName() {
		return className;
	}

	public boolean hasField(String fieldName) {
		return fields.containsKey(fieldName);
	}

	public V getField(String fieldName) {
		return fields.get(fieldName);
	}

	public void setField(String fieldName, V value) {
		fields.put(fieldName, value);
	}

	public Set<Map.Entry<String, V>> entrySet() {
		return Collections.unmodifiableSet(fields.entrySet());
	}

	public Set<Pair<String, FieldNode>> getFields() {
		return Utils.getFields(className, fn -> (fn.access & ACC_STATIC) == (isStatic ? ACC_STATIC : 0));
	}

	public final boolean toTop(Interpreter<V> interpreter) {
		return merge(interpreter, pair -> interpreter.newValue(Type.getType(pair.getSecond().desc)));
	}

	public final boolean merge(AbstractObject<V> cell, Interpreter<V> interpreter) {
		return merge(interpreter, pair -> cell.getField(pair.getFirst() + "." + pair.getSecond().name));
	}

	private boolean merge(Interpreter<V> interpreter, Function<Pair<String, FieldNode>, V> valueProvider) {
		boolean changed = false;
		for(Pair<String, FieldNode> pair : getFields()) {
			FieldNode fn = pair.getSecond();
			String fieldName = pair.getFirst() + "." + fn.name;
			V oldValue = fields.get(fieldName);
			V newValue = interpreter.merge(oldValue, valueProvider.apply(pair));
			if(!oldValue.equals(newValue)) {
				setField(fieldName, newValue);
				changed = true;
			}
		}
		return changed;
	}

	@Override
	public int hashCode() {
		return fields.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		return this == o ||
				(o instanceof AbstractObject && fields.equals(((AbstractObject) o).fields));
	}
}
