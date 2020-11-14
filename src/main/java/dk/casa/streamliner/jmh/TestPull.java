package dk.casa.streamliner.jmh;

import dk.casa.streamliner.stream.IntPullStream;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.AverageTime)
@State(Scope.Thread)
@Fork(1)
public class TestPull extends TestBase {
	@Setup
	public void setUp() {
		super.setUp();
	}

	@Benchmark
	public int sum() {
		return IntPullStream.of(v).sum();
	}

	@Benchmark
	public int sumOfSquares() {
		return IntPullStream.of(v)
				.map(d -> d * d)
				.sum();
	}

	@Benchmark
	public int sumOfSquaresEven() {
		return IntPullStream.of(v)
				.filter(x -> x % 2 == 0)
				.map(x -> x * x)
				.sum();
	}

	@Benchmark
	public int cart() {
		return IntPullStream.of(v_outer)
				.flatMap(d ->
						IntPullStream.of(v_inner)
								.map(dP -> dP * d))
				.sum();
	}

	@Benchmark
	public int megamorphicMaps() {
		return IntPullStream.of(v)
				.map(d -> d * 1)
				.map(d -> d * 2)
				.map(d -> d * 3)
				.map(d -> d * 4)
				.map(d -> d * 5)
				.map(d -> d * 6)
				.map(d -> d * 7)
				.sum();
	}

	@Benchmark
	public int megamorphicFilters() {
		return IntPullStream.of(v)
				.filter(d -> d > 1)
				.filter(d -> d > 2)
				.filter(d -> d > 3)
				.filter(d -> d > 4)
				.filter(d -> d > 5)
				.filter(d -> d > 6)
				.filter(d -> d > 7)
				.sum();
	}

	@Benchmark
	public int flatMapTake() {
		return IntPullStream.of(v_outer)
				.flatMap(x ->
						IntPullStream.of(v_inner)
								.map(dP -> dP * x))
				.limit(20000000)
				.sum();
	}

	@Benchmark
	public int flatMapTakeRev() {
		return IntPullStream.of(v_inner)
				.flatMap(x ->
						IntPullStream.of(v_outer)
								.map(dP -> dP * x))
				.limit(20000000)
				.sum();
	}

	@Benchmark
	public boolean allMatch() {
		return IntPullStream.of(v).allMatch(x -> x < 10);
	}

	@Benchmark
	public long count() {
		return IntPullStream.of(v).count();
	}

	@Benchmark
	public long filterCount() {
		return IntPullStream.of(v)
				.filter(x -> x % 2 == 0)
				.count();
	}

	@Benchmark
	public long filterMapCount() {
		return IntPullStream.of(v)
				.filter(x -> x % 2 == 0)
				.map(x -> x * x)
				.count();
	}
}
