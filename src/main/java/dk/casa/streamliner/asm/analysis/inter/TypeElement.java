package dk.casa.streamliner.asm.analysis.inter;

import dk.casa.streamliner.asm.analysis.Element;
import org.objectweb.asm.Type;

public class TypeElement implements Element<TypeElement> {
	public static final Type TOP = Type.VOID_TYPE;

	private final boolean precise;
	private final Type type;

	public TypeElement(boolean precise, Type type) {
		this.precise = precise;
		this.type = type;
	}

	public boolean isPrecise() {
		return precise;
	}

	public Type getType() {
		return type;
	}

	@Override
	public TypeElement merge(TypeElement other) {
		if(equals(other)) return this;

		Type newType;
		if(isSubtype(type, other.type)) newType = other.type;
		else if(isSubtype(other.type, type)) newType = type;
		else if(isReference(type) && isReference(other.type)) newType = Type.getObjectType("java/lang/Object");
		else newType = TOP;

		return new TypeElement(false, newType);
	}

	private static boolean isReference(Type type) {
		return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY;
	}

	private static boolean isSubtype(Type t1, Type t2) {
		if(isReference(t1) && isReference(t2)) {
			// TODO: Use Utils.getAncestors
			if(t1.getSort() == Type.ARRAY && t2.getSort() == Type.ARRAY)
				return isSubtype(t1.getElementType(), t2.getElementType());
			else if(t2.equals(Type.getObjectType("java/lang/Object")))
				return true;
			return t1.equals(t2);
		} else {
			// We implicitly allow widening conversion
			if(t2 == Type.INT_TYPE) switch (t1.getSort()) {
				case Type.INT:
				case Type.BOOLEAN:
				case Type.BYTE:
				case Type.SHORT:
				case Type.CHAR:
					return true;
			}

			return t1.equals(t2);
		}
	}

	public boolean maybePointer() {
		return isReference(type) || type == TOP;
	}

	@Override
	public String toString() {
		return (precise ? "!" : "") + type.getDescriptor();
	}

	@Override
	public boolean equals(Object o) {
		if(o == this) return true;
		if(!(o instanceof TypeElement)) return false;
		TypeElement to = (TypeElement) o;
		// Primitive values are always implicitly precise
		return (precise == to.precise || !maybePointer()) && type.equals(to.type);
	}

	@Override
	public int hashCode() {
		return (precise ? 1 : 3) * type.hashCode();
	}
}
