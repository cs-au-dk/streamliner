package dk.casa.streamliner.stream;

import java.util.Collection;
import java.util.function.*;

/**
 * Push streams that lets the source drive the iteration
 */
public abstract class IntPushStream implements IntStream<IntPushStream> {
	// private
	@FunctionalInterface
	public interface IterConsumer {
		boolean accept(int x);
	}

	// protected
	abstract public void exec(IterConsumer c);

	public static IntPushStream of(Collection<Integer> collection) {
		return new IntPushStream() {
			@Override
			public void exec(IterConsumer c) {
				for (Integer integer : collection)
					if(c.accept(integer)) break;
			}
		};
	}

	public static IntPushStream of(int[] arr) {
		return new IntPushStream() {
			@Override
			public void exec(IterConsumer c) {
				int i = 0;
				while (i < arr.length && !c.accept(arr[i++]));
			}
		};
	}

	@Override
	public IntPushStream map(IntUnaryOperator f) {
		return new IntPushStream() {
			@Override
			public void exec(IterConsumer c) {
				//IntPushStream.this.iter(x -> c.accept(f.applyAsInt(x)));
				IntPushStream.this.exec(new IterConsumer() {
					@Override
					public boolean accept(int x) {
						return c.accept(f.applyAsInt(x));
					}
				});
			}
		};
	}

	@Override
	public IntPushStream filter(IntPredicate p) {
		return new IntPushStream() {
			@Override
			public void exec(IterConsumer c) {
				//IntPushStream.this.iter(x -> p.test(x) && c.accept(x));
				IntPushStream.this.exec(new IterConsumer() {
					@Override
					public boolean accept(int x) {
						return p.test(x) && c.accept(x);
					}
				});
			}
		};
	}

	@Override
	public IntPushStream flatMap(IntFunction<? extends IntPushStream> f) {
		return new IntPushStream() {
			@Override
			public void exec(IterConsumer c) {
				IntPushStream.this.exec(new IterConsumer() {
					// private
					public boolean stopped = false;

					@Override
					public boolean accept(int source) {
						IntPushStream s = f.apply(source);
						s.exec(new IterConsumer() {
							@Override
							public boolean accept(int x) {
								return stopped = c.accept(x);
							}
						});
						return stopped;
					}
				});
			}
		};
	}

	@Override
	public IntPushStream limit(long maxSize) {
		return new IntPushStream() {
			@Override
			public void exec(IterConsumer c) {
				IntPushStream.this.exec(new IterConsumer() {
					private long remaining = maxSize;

					@Override
					public boolean accept(int x) {
						if (--remaining >= 0) return c.accept(x);
						else return true;
					}
				});
			}
		};
	}

	@Override
	public void forEach(IntConsumer c) {
		exec(x -> {
			c.accept(x);
			return false;
		});
	}

	// private
	public static class Reducer implements IterConsumer {
		// private
		public int state;
		public IntBinaryOperator reducer;

		Reducer(int initial, IntBinaryOperator r) {
			state = initial;
			reducer = r;
		}

		@Override
		public boolean accept(int v) {
			state = reducer.applyAsInt(state, v);
			return false;
		}
	}

	@Override
	public int reduce(int initial, IntBinaryOperator r) {
		Reducer reducer = new Reducer(initial, r);
		exec(reducer);
		return reducer.state;
	}

	@Override
	public boolean allMatch(IntPredicate p) {
		class Matcher implements IterConsumer {
			boolean flag = true;

			@Override
			public boolean accept(int x) {
				if(!p.test(x)) flag = false;
				return !flag;
			}
		}

		Matcher m = new Matcher();
		exec(m);
		return m.flag;
	}
}
