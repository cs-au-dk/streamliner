package dk.casa.streamliner.test;

import dk.casa.streamliner.stream.IntPullStream;
import dk.casa.streamliner.stream.IntPushStream;
import dk.casa.streamliner.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Alias our Stream provider type
@FunctionalInterface
interface IntStreamProvider<StreamT extends IntStream> extends Function<int[], StreamT> {}


public class TestIntStreams {
	private static int[] ints = new int[]{1, 2, 3, 4, 5};

	// Shortcut for named lambdas
	static <StreamT extends IntStream> IntStreamProvider<StreamT> namedLambda(Function<int[], StreamT> provider, String name) {
		return new IntStreamProvider<StreamT>() {
			@Override
			public StreamT apply(int[] ints) {
				return provider.apply(ints);
			}

			@Override
			public String toString() {
				return name;
			}
		};
	}

	// For ParameterizedTests
	static java.util.stream.Stream<IntStreamProvider> streamProvider() {
		return java.util.stream.Stream.of(
				namedLambda(IntPushStream::of, "IntPushStream"),
				namedLambda(IntPullStream::of, "IntPullStream")
		);
	}

	@ParameterizedTest
	@MethodSource("streamProvider")
	<StreamT extends IntStream> void testSum(IntStreamProvider<StreamT> supplier) {
		assertEquals(
				supplier.apply(ints).sum(),
				15
		);
	}

	@ParameterizedTest
	@MethodSource("streamProvider")
	<StreamT extends IntStream> void testMapSum(IntStreamProvider<StreamT> supplier) {
		assertEquals(
				supplier.apply(ints).map(x -> x * 2).sum(),
				30
		);
	}

	@ParameterizedTest
	@MethodSource("streamProvider")
	<StreamT extends IntStream> void testEvenSquares(IntStreamProvider<StreamT> supplier) {
		assertEquals(
				supplier.apply(ints).map(x -> x * x).filter(x -> x % 2 == 0).sum(),
				20
		);
	}

	@ParameterizedTest
	@MethodSource("streamProvider")
	<StreamT extends IntStream> void testEmptyFlatMap(IntStreamProvider<StreamT> supplier) {
		assertEquals(
				supplier.apply(ints).flatMap(x -> supplier.apply(new int[]{})).count(),
				0
		);
	}

	@ParameterizedTest
	@MethodSource("streamProvider")
	<StreamT extends IntStream> void testNonEmptyFlatMap(IntStreamProvider<StreamT> supplier) {
		assertEquals(
				supplier.apply(ints).flatMap(x -> supplier.apply(ints).filter(y -> y % x == 0)).sum(),
				33
		);
	}

	@ParameterizedTest
	@MethodSource("streamProvider")
	<StreamT extends IntStream> void testLimit(IntStreamProvider<StreamT> supplier) {
		assertEquals(
				supplier.apply(ints).filter(x -> x > 2).limit(2).sum(),
				7
		);
	}

	@ParameterizedTest
	@MethodSource("streamProvider")
	<StreamT extends IntStream> void testFlatMapLimit(IntStreamProvider<StreamT> supplier) {
		assertEquals(
				supplier.apply(ints).limit(2).flatMap(x -> supplier.apply(ints)).count(),
				10
		);
	}
}
