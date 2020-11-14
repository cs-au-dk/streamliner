package dk.casa.streamliner.stream;

import java.util.function.*;

public interface IntStream<StreamT extends IntStream> {
	/* Functions that return new streams */
	StreamT map(IntUnaryOperator f);
	StreamT filter(IntPredicate p);
	StreamT flatMap(IntFunction<? extends StreamT> f);
	StreamT limit(long maxSize);

	/* Terminal operations */
	void forEach(IntConsumer c);
	int reduce(int initial, IntBinaryOperator r);
	boolean allMatch(IntPredicate p);

	default int sum() {
		return reduce(0, Integer::sum);
	}

	default int count() {
		return reduce(0, (x, y) -> x + 1);
	}
}