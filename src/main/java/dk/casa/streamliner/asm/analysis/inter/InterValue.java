package dk.casa.streamliner.asm.analysis.inter;

import dk.casa.streamliner.asm.analysis.Element;
import dk.casa.streamliner.asm.analysis.FlatElement;
import dk.casa.streamliner.asm.analysis.pointer.AbstractPointer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Value;

public class InterValue implements Value, AbstractPointer, Element<InterValue> {
	public final TypeElement type;
	public final PointerElement pointer;
	public final FlatElement<Number> constant;

	public InterValue(TypeElement type, PointerElement pointer, FlatElement<Number> constant) {
		this.type = type;
		this.pointer = pointer;
		this.constant = constant;
	}

	public InterValue(TypeElement type, PointerElement pointer) {
		this(type, pointer, FlatElement.getTop());
	}

	@Override
	public boolean isValid() {
		return pointer.isValid();
	}

	@Override
	public int pointsTo() throws AnalyzerException {
		return pointer.pointsTo();
	}

	@Override
	public String toString() {
		return String.format("(%s,%s,%s)", type, pointer, constant);
	}

	@Override
	public int getSize() {
		if(type.getType() == TypeElement.TOP) return 1;
		return type.getType().getSize();
	}

	@Override
	public InterValue merge(InterValue other) {
		if(equals(other)) return this;

		PointerElement npointer = pointer.merge(other.pointer);
		FlatElement<Number> nconstant = constant.merge(other.constant);
		TypeElement ntype = type.merge(other.type);

		// Do not use type of NULL for merges
		if(pointer == PointerElement.NULL) ntype = other.type;
		else if(other.pointer == PointerElement.NULL) ntype = type;

		return new InterValue(ntype, npointer, nconstant);
	}

	@Override
	public boolean equals(Object o) {
		if(this == o) return true;
		else if(!(o instanceof InterValue)) return false;
		InterValue oi = (InterValue) o;
		return type.equals(oi.type) && pointer.equals(oi.pointer) && constant.equals(oi.constant);
	}

	@Override
	public int hashCode() {
		return type.hashCode() + 31 * pointer.hashCode() + 257 * constant.hashCode();
	}
}
