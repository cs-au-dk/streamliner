package dk.casa.streamliner.stream;

import java.util.Collection;
import java.util.Iterator;
import java.util.function.*;

/**
 * Pull streams backed by iterators
 */
public abstract class IntPullStream implements IntStream<IntPullStream> {
	protected abstract int get();
	protected abstract boolean hasNext();

	public static IntPullStream of(Collection<Integer> collection) {
		// Just a wrapper for an iterator?
		return new IntPullStream() {
			private Iterator<Integer> it = collection.iterator();

			@Override
			protected int get() {
				return (int)it.next();
			}

			@Override
			protected boolean hasNext() {
				return it.hasNext();
			}
		};
	}

	public static IntPullStream of(int[] arr) {
		return new IntPullStream() {
			private int i = 0;

			@Override
			protected int get() {
				return arr[i++];
			}

			@Override
			protected boolean hasNext() {
				return i < arr.length;
			}
		};
	}

	@Override
	public IntPullStream map(IntUnaryOperator f) {
		return new IntPullStream() {
			@Override
			protected int get() {
				return f.applyAsInt(IntPullStream.this.get());
			}

			@Override
			protected boolean hasNext() {
				return IntPullStream.this.hasNext();
			}
		};
	}

	@Override
	public IntPullStream filter(IntPredicate p) {
		return new IntPullStream() {
			private boolean empty = true;
			private int val = 0;

			// Iterate through source until we find an element that matches the predicate
			private void fix() {
				while(empty && IntPullStream.this.hasNext()) {
					int v = IntPullStream.this.get();
					if(p.test(v)) {
						val = v;
						empty = false;
					}
				}
			}

			@Override
			protected int get() {
				empty = true;
				return val;
			}

			@Override
			protected boolean hasNext() {
				fix();
				return !empty;
			}
		};
	}

	@Override
	public IntPullStream flatMap(IntFunction<? extends IntPullStream> f) {
		return new IntPullStream() {
			private IntPullStream curStream = null;

			@Override
			protected int get() {
				return curStream.get();
			}

			@Override
			protected boolean hasNext() {
				while(IntPullStream.this.hasNext() && (curStream == null || !curStream.hasNext()))
					curStream = f.apply(IntPullStream.this.get());
				return curStream != null && curStream.hasNext();
			}
		};
	}

	@Override
	public IntPullStream limit(long maxSize) {
		return new IntPullStream() {
			private long remaining = maxSize;

			@Override
			protected int get() {
				remaining--;
				return IntPullStream.this.get();
			}

			@Override
			protected boolean hasNext() {
				return remaining > 0 && IntPullStream.this.hasNext();
			}
		};
	}

	@Override
	public void forEach(IntConsumer c) {
		while(hasNext()) c.accept(get());
	}

	@Override
	public int reduce(int initial, IntBinaryOperator r) {
		while(hasNext()) initial = r.applyAsInt(initial, get());
		return initial;
	}

	@Override
	public boolean allMatch(IntPredicate p) {
		while(hasNext()) if(!p.test(get())) return false;
		return true;
	}
}
