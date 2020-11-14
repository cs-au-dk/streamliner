package dk.casa.streamliner.jmh;

import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

@State(Scope.Thread)
public abstract class TestBase {

	public static int N = 100000000,
						 N_outer = 10000000,
						 N_inner = 10;

	public int[] v, v_outer, v_inner;

	public void setUp() {
		v  = fillArray(N);
		v_outer = fillArray(N_outer);
		v_inner = fillArray(N_inner);
	}

	protected int[] fillArray(int range) {
		int[] array = new int[range];
		for (int i = 0; i < range; i++) {
			array[i] = i % 10;
		}
		return array;
	}

	/** From Clash of the Lambdas */
	abstract public int sum();
	abstract public int sumOfSquares();
	abstract public int sumOfSquaresEven();
	abstract public int cart();
	/** From StreamAlg */
	abstract public int megamorphicMaps();
	abstract public int megamorphicFilters();
	abstract public int flatMapTake();
	/** Experiment */
	abstract public int flatMapTakeRev();
	abstract public boolean allMatch();
	/** From StreamAlg (pull-style / adapted) */
	abstract public long count();
	abstract public long filterCount();
	abstract public long filterMapCount();
}
