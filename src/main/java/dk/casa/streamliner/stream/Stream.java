package dk.casa.streamliner.stream;

import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collector;

public interface Stream<V> {
	/* Functions that return new streams */
	<U> Stream<U> map(Function<? super V, ? extends U> f);
	Stream<V> filter(Predicate<? super V> p);
	<U> Stream<U> flatMap(Function<? super V, ? extends Stream<? extends U>> f);
	Stream<V> peek(Consumer<? super V> f);
	Stream<V> limit(long maxSize);

	/* Terminal operations */
	void forEach(Consumer<? super V> c);
	<U> U reduce(U initial, BiFunction<U, ? super V, U> r);

	<R, A> R collect(Collector<? super V, A, R> collector);

	Optional<V> findFirst();
	default Optional<V> findAny() { return findFirst(); }

	default int count() {
		return reduce(0, (x, y) -> x + 1);
	}
}