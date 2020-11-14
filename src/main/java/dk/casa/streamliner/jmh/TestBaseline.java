package dk.casa.streamliner.jmh;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.AverageTime)
@State(Scope.Thread)
@Fork(1)
public class TestBaseline extends TestBase {
	@Setup
	public void setUp() {
		super.setUp();
	}

	@Benchmark
	public int sum() {
		int acc = 0;
		for(int i = 0 ; i < v.length ; i++)
			acc += v[i];

		return acc;
	}

	@Benchmark
	public int sumOfSquares() {
		int acc = 0;
		for(int i = 0 ; i < v.length ; i++)
			acc += v[i] * v[i];

		return acc;
	}

	@Benchmark
	public int sumOfSquaresEven() {
		int acc = 0;
		for(int i = 0 ; i < v.length ; i++)
			if (v[i] % 2 == 0)
				acc += v[i] * v[i];

		return acc;
	}

	@Benchmark
	public int cart() {
		int cart = 0;
		for(int d = 0 ; d < v_outer.length ; d++)
			for(int dp = 0 ; dp < v_inner.length ; dp++)
				cart += v_outer[d] * v_inner[dp];

		return cart;
	}

	@Benchmark
	public int megamorphicMaps() {
		int acc = 0;
		for(int i = 0 ; i < v.length ; i++)
			acc += v[i] *1*2*3*4*5*6*7;

		return acc;
	}

	@Benchmark
	public int megamorphicFilters() {
		int acc = 0;
		for(int i = 0 ; i < v.length ; i++)
			if (v[i] > 1 && v[i] > 2 && v[i] > 3 && v[i] > 4 && v[i] > 5 && v[i] > 6 && v[i] > 7)
				acc += v[i];

		return acc;
	}

	@Benchmark
	public int flatMapTake() {
		int sum = 0;
		int n = 0;
		boolean flag = true;
		for(int d = 0 ; d < v_outer.length && flag ; d++) {
			for(int dp = 0 ; dp < v_inner.length && flag ; ){
				sum += v_outer[d] * v_inner[dp++];
				if (++n == 20000000)
					flag = false;
			}
		}

		return sum;
	}

	@Benchmark
	public boolean allMatch() {
		boolean flag = true;
		for(int i = 0; i < v.length; i++)
			if(v[i] >= 10) {
				flag = false;
				break;
			}
		return flag;
	}

	@Benchmark
	public int flatMapTakeRev() {
		int sum = 0;
		int n = 0;
		boolean flag = true;
		for(int d = 0 ; d < v_inner.length && flag ; d++) {
			for(int dp = 0 ; dp < v_outer.length && flag ; ){
				sum += v_inner[d] * v_outer[dp++];
				if (++n == 20000000)
					flag = false;
			}
		}

		return sum;
	}

	@Benchmark
	public long count() {
		long c;
		for(c = 0; c < v.length; ++c);
		return c;
	}

	@Benchmark
	public long filterCount() {
		long c = 0;
		for(int i = 0; i < v.length; ++i)
			if(v[i] % 2 == 0)
				++c;

		return c;
	}

	@Benchmark
	public long filterMapCount() {
		long c = 0;
		for(int i = 0; i < v.length; ++i)
			if((v[i] * v[i]) % 2 == 0)
				++c;

		return c;
	}
}
