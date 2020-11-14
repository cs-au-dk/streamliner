package dk.casa.streamliner.stream;

import java.util.Collection;
import java.util.Iterator;
import java.util.Optional;
import java.util.function.*;
import java.util.stream.Collector;

/**
 * Pull streams backed by iterators
 * @param <V> Type of element in stream
 */
public abstract class PullStream<V> implements Stream<V> {
	protected abstract V get();
	protected abstract boolean hasNext();

	public static <T> PullStream<T> of(Collection<T> collection) {
		// Just a wrapper for an iterator?
		return new PullStream<T>() {
			private Iterator<T> it = collection.iterator();

			@Override
			protected T get() {
				return it.next();
			}

			@Override
			protected boolean hasNext() {
				return it.hasNext();
			}
		};
	}

	@Override
	public <U> Stream<U> map(Function<? super V, ? extends U> f) {
		return new PullStream<U>() {
			@Override
			protected U get() {
				return f.apply(PullStream.this.get());
			}

			@Override
			protected boolean hasNext() {
				return PullStream.this.hasNext();
			}
		};
	}

	@Override
	public Stream<V> filter(Predicate<? super V> p) {
		return new PullStream<V>() {
			private V val = null;

			// Iterate through source until we find an element that matches the predicate
			private void fix() {
				while(val == null && PullStream.this.hasNext()) {
					V v = PullStream.this.get();
					if(p.test(v))
						val = v;
				}
			}

			@Override
			protected V get() {
				fix();
				V v = val;
				val = null;
				return v;
			}

			@Override
			protected boolean hasNext() {
				fix();
				return val != null;
			}
		};
	}

	@Override
	public <U> Stream<U> flatMap(Function<? super V, ? extends Stream<? extends U>> f) {
		return new PullStream<U>() {
			private PullStream<U> curStream = null;

			@SuppressWarnings("unchecked")
			private void fix() {
				while(PullStream.this.hasNext() && (curStream == null || !curStream.hasNext()))
					curStream = (PullStream<U>) f.apply(PullStream.this.get());
			}

			@Override
			protected U get() {
				fix();
				return curStream.get();
			}

			@Override
			protected boolean hasNext() {
				fix();
				return curStream != null && curStream.hasNext();
			}
		};
	}

	@Override
	public PullStream<V> peek(Consumer<? super V> f) {
		return new PullStream<V>() {

			@Override
			protected V get() {
				V el = PullStream.this.get();
				f.accept(el);
				return el;
			}

			@Override
			protected boolean hasNext() {
				return PullStream.this.hasNext();
			}
		};
	}

	@Override
	public PullStream<V> limit(long maxSize) {
		return new PullStream<V>() {
			private long remaining = maxSize;

			@Override
			protected V get() {
				remaining--;
				return PullStream.this.get();
			}

			@Override
			protected boolean hasNext() {
				return remaining > 0 && PullStream.this.hasNext();
			}
		};
	}

	@Override
	public void forEach(Consumer<? super V> c) {
		while(hasNext()) c.accept(get());
	}

	@Override
	public <U> U reduce(U initial, BiFunction<U, ? super V, U> r) {
		while(hasNext()) initial = r.apply(initial, get());
		return initial;
	}

	@Override
	public <R, A> R collect(Collector<? super V, A, R> collector) {
		BiConsumer<A, ? super V> acc = collector.accumulator();
		A accumulator = collector.supplier().get();
		while(hasNext()) acc.accept(accumulator, get());
		return collector.finisher().apply(accumulator);
	}

	@Override
	public Optional<V> findFirst() {
		if(hasNext()) return Optional.of(get());
		else return Optional.empty();
	}
}
