package dk.casa.streamliner.asm.analysis;

public class ProductElement<E1 extends Element<E1>, E2 extends Element<E2>> implements Element<ProductElement<E1, E2>> {
	private final E1 first;
	private final E2 second;

	public ProductElement(E1 first, E2 second) {
		this.first = first;
		this.second = second;
	}

	public E1 getFirst() {
		return first;
	}

	public E2 getSecond() {
		return second;
	}

	@Override
	public ProductElement<E1, E2> merge(ProductElement<E1, E2> other) {
		if(equals(other)) return this;

		return new ProductElement<>(first.merge(other.first), second.merge(other.second));
	}

	@Override
	public int hashCode() {
		return first.hashCode() + 301 * second.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		if(o == this) return true;
		if(!(o instanceof ProductElement)) return false;
		ProductElement po = (ProductElement) o;
		return first.equals(po.first) && second.equals(po.second);
	}

	@Override
	public String toString() {
		return String.format("(%s,%s)", first, second);
	}
}
