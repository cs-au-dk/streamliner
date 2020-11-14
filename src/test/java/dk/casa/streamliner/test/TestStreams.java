package dk.casa.streamliner.test;

import dk.casa.streamliner.stream.PullStream;
import dk.casa.streamliner.stream.PushStream;
import dk.casa.streamliner.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

// Alias our Stream provider type
@FunctionalInterface
interface IntegerStreamProvider extends Function<Collection<Integer>, Stream<Integer>> {}


public class TestStreams {
	private static final int N = 1000;
	private static List<Integer> ints = Arrays.asList(1, 2, 3, 4, 5);

	// Shortcut for named lambdas
	static IntegerStreamProvider namedLambda(Function<Collection<Integer>, Stream<Integer>> provider, String name) {
		return new IntegerStreamProvider() {
			@Override
			public Stream<Integer> apply(Collection<Integer> ints) {
				return provider.apply(ints);
			}

			@Override
			public String toString() {
				return name;
			}
		};
	}

	// For ParameterizedTests
	static java.util.stream.Stream<IntegerStreamProvider> streamProvider() {
		return java.util.stream.Stream.of(
				namedLambda(PushStream::of, "PushStream"),
				namedLambda(PullStream::of, "PullStream")
		);
	}

	@ParameterizedTest
	@MethodSource("streamProvider")
	void testSum(IntegerStreamProvider supplier) {
		assertEquals(
				supplier.apply(ints).reduce(0, Integer::sum),
				15
		);
	}

	@ParameterizedTest
	@MethodSource("streamProvider")
	void testMapSum(IntegerStreamProvider supplier) {
		assertEquals(
				supplier.apply(ints).map(x -> x * 2).reduce(0, Integer::sum),
				30
		);
	}

	@ParameterizedTest
	@MethodSource("streamProvider")
	void testEvenSquares(IntegerStreamProvider supplier) {
		assertEquals(
				supplier.apply(ints).map(x -> x * x).filter(x -> x % 2 == 0).reduce(0, Integer::sum),
				20
		);
	}

	@ParameterizedTest
	@MethodSource("streamProvider")
	void testEmptyFlatMap(IntegerStreamProvider supplier) {
		assertEquals(
				supplier.apply(ints).flatMap(x -> supplier.apply(Collections.emptyList())).count(),
				0
		);
	}

	@ParameterizedTest
	@MethodSource("streamProvider")
	void testNonEmptyFlatMap(IntegerStreamProvider supplier) {
		assertEquals(
				supplier.apply(ints).flatMap(x -> supplier.apply(ints).filter(y -> y % x == 0)).reduce(0, Integer::sum),
				33
		);
	}

	@ParameterizedTest
	@MethodSource("streamProvider")
	void testPeek(IntegerStreamProvider supplier) {
		List<Integer> intlist = new ArrayList<>();
		assertEquals(
				15,
				supplier.apply(ints).peek(intlist::add).reduce(0, Integer::sum)
		);
		assertEquals(5, intlist.size());
	}

	@ParameterizedTest
	@MethodSource("streamProvider")
	void testFindFirst(IntegerStreamProvider supplier) {
		Optional<Integer> first = supplier.apply(ints).findFirst();
		assertTrue(first.isPresent());
		assertEquals(1, first.get());
	}

	@ParameterizedTest
	@MethodSource("streamProvider")
	void testFindFirstEmpty(IntegerStreamProvider supplier) {
		assertFalse(supplier.apply(Collections.emptyList()).findFirst().isPresent());
	}

	@ParameterizedTest
	@MethodSource("streamProvider")
	void testCollect(IntegerStreamProvider supplier) {
		assertEquals(ints, supplier.apply(ints).collect(Collectors.toList()));
	}

	@ParameterizedTest
	@MethodSource("streamProvider")
	void testLimit(IntegerStreamProvider supplier) {
		assertEquals(
				7,
				supplier.apply(ints).filter(x -> x > 2).limit(2).reduce(0, Integer::sum)
		);
	}
}
