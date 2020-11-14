package dk.casa.streamliner.stream;

import java.util.Collection;
import java.util.Optional;
import java.util.function.*;
import java.util.stream.Collector;

/**
 * Push streams that lets the source drive the iteration
 * @param <V> Type of element in stream
 */
public abstract class PushStream<V> implements Stream<V> {
	@FunctionalInterface
	private interface IterConsumer<V> {
		boolean accept(V x);
	}

	abstract protected void iter(IterConsumer<? super V> c);

	public static <T> PushStream<T> of(Collection<T> collection) {
		return new PushStream<T>() {
			@Override
			protected void iter(IterConsumer<? super T> c) {
				for(T element : collection)
					if(c.accept(element)) break;
			}
		};
	}

	@Override
	public <U> PushStream<U> map(Function<? super V, ? extends U> f) {
		return new PushStream<U>() {
			@Override
			protected void iter(IterConsumer<? super U> c) {
				PushStream.this.iter(x -> c.accept(f.apply(x)));
			}
		};
	}

	@Override
	public PushStream<V> filter(Predicate<? super V> p) {
		return new PushStream<V>() {
			@Override
			protected void iter(IterConsumer<? super V> c) {
				PushStream.this.iter(x -> p.test(x) && c.accept(x));
			}
		};
	}

	@Override
	public <U> PushStream<U> flatMap(Function<? super V, ? extends Stream<? extends U>> f) {
		return new PushStream<U>() {
			@Override
			protected void iter(IterConsumer<? super U> c) {
				PushStream.this.iter(new IterConsumer<V>() {
					private boolean stopped = false;

					@Override
					public boolean accept(V source) {
						@SuppressWarnings("unchecked")
						PushStream<U> s = (PushStream<U>) f.apply(source);
						s.iter(x -> stopped = c.accept(x));
						return stopped;
					}
				});
			}
		};
	}

	@Override
	public PushStream<V> peek(Consumer<? super V> f) {
		return new PushStream<V>() {
			@Override
			protected void iter(IterConsumer<? super V> c) {
				PushStream.this.iter(x -> { f.accept(x); return c.accept(x); });
			}
		};
	}

	@Override
	public PushStream<V> limit(long maxSize) {
		return new PushStream<V>() {
			@Override
			public void iter(IterConsumer<? super V> c) {
				PushStream.this.iter(new IterConsumer<V>() {
					private long remaining = maxSize;

					@Override
					public boolean accept(V x) {
						if (--remaining >= 0) return c.accept(x);
						else return true;
					}
				});
			}
		};
	}

	@Override
	public void forEach(Consumer<? super V> c) {
		iter(x -> { c.accept(x); return false; });
	}

	private class Reducer<U> implements IterConsumer<V> {
		private U state;
		private BiFunction<U, ? super V, U> reducer;

		Reducer(U initial, BiFunction<U, ? super V, U> r) {
			state = initial;
			reducer = r;
		}

		@Override
		public boolean accept(V v) {
			state = reducer.apply(state, v);
			return false;
		}
	}

	@Override
	public <U> U reduce(U initial, BiFunction<U, ? super V, U> r) {
		Reducer<U> reducer = new Reducer<>(initial, r);
		iter(reducer);
		return reducer.state;
	}

	@Override
	public <R, A> R collect(Collector<? super V, A, R> collector) {
		A accumulator = collector.supplier().get();
		BiConsumer<A, ? super V> acc = collector.accumulator();
		iter(el -> { acc.accept(accumulator, el); return false; });
		return collector.finisher().apply(accumulator);
	}

	private static class Cell<V> { V element; }

	@Override
	public Optional<V> findFirst() {
		Cell<V> cell = new Cell<>();
		iter(x -> { cell.element = x; return true; });
		return Optional.ofNullable(cell.element);
	}
}
