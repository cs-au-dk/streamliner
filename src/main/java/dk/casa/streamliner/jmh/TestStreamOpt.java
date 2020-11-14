package dk.casa.streamliner.jmh;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.AverageTime)
@State(Scope.Thread)
@Fork(1)
public class TestStreamOpt extends TestBase {
	@Setup
	public void setUp() {
		super.setUp();
	}
	
	@Benchmark
	public int sum() {
		return IntStream.of(v).sum();
	}

	@Benchmark
	public int sumOfSquares() {
		return IntStream.of(v)
				.map(d -> d * d)
				.sum();
	}

	@Benchmark
	public int sumOfSquaresEven() {
		return IntStream.of(v)
				.filter(x -> x % 2 == 0)
				.map(x -> x * x)
				.sum();
	}

	@Benchmark
	public int cart() {
		return IntStream.of(v_outer)
				.flatMap(d ->
						IntStream.of(v_inner)
								.map(dP -> dP * d))
				.sum();
	}

	@Benchmark
	public int megamorphicMaps() {
		return IntStream.of(v)
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
		return IntStream.of(v)
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
		return IntStream.of(v_outer)
				.flatMap(x ->
						IntStream.of(v_inner)
								.map(dP -> dP * x))
				.limit(20000000)
				.sum();
	}

	@Benchmark
	public int flatMapTakeRev() {
		return IntStream.of(v_inner)
				.flatMap(x ->
						IntStream.of(v_outer)
								.map(dP -> dP * x))
				.limit(20000000)
				.sum();
	}

	@Benchmark
	public boolean allMatch() {
		return IntStream.of(v).allMatch(x -> x < 10);
	}

	@Benchmark
	public long count() {
		return IntStream.of(v).count();
	}

	@Benchmark
	public long filterCount() {
		return IntStream.of(v)
				.filter(x -> x % 2 == 0)
				.count();
	}

	@Benchmark
	public long filterMapCount() {
		return IntStream.of(v)
				.filter(x -> x % 2 == 0)
				.map(x -> x * x)
				.count();
	}

	public static void main(String[] args) {
		TestStreamOpt obj = new TestStreamOpt();
		obj.setUp();
		for (int i = 0; i < 1000; i++) {
			obj.allMatch();
		}
	}
}
