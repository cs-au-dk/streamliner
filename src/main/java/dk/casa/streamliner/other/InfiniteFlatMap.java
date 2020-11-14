package dk.casa.streamliner.other;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Optional;
import java.util.stream.LongStream;
import java.util.stream.Stream;

public class InfiniteFlatMap {

	public static void main(String[] args) {
		testShortCircuit();
	}

	private static void testShortCircuit() {
		Optional<Integer> opt = Stream.of(1,2,3).filter(x -> x % 2 == 0).findFirst();
	}

	private static void testNoFlatMap() {
		ArrayList<Integer> arr = new ArrayList<>(Arrays.asList(1, 2, 3, 4, 5));

		arr.stream().map(i -> i - 2).anyMatch(x -> x > 0);
	}

	private static void testFirst() {
		Stream<Long> s = Stream.iterate(0L, i -> i + 2);

		boolean l = Stream.of(10L)
				.flatMap(x -> s.map(i -> i-2))
				.anyMatch(x -> x > 0);
		System.out.println(l);
	}

	private static void testInf() {
		Stream<Long> s = Stream.iterate(0L, i -> i + 2);

		Iterator<Long> it = Stream.of(10L)
				.flatMap(x -> s)
				.iterator();

		it.hasNext();
	}

	private static void testInfNoFlatMap() {
		// No problem
		Iterator<Long> it = Stream.iterate(0L, i -> i + 2).map(x -> x * 2).iterator();

		it.hasNext();
	}

	private static void testPeekFlatMap() {
		LongStream s = LongStream.range(0, 10);

		Iterator<Long> it = s
				.peek(System.out::println)
				.flatMap(x -> LongStream.iterate(0L, i -> i + 2))
				.iterator();

		it.hasNext();
	}

}
