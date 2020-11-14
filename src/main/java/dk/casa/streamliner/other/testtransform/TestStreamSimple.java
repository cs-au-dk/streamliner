package dk.casa.streamliner.other.testtransform;

import dk.casa.streamliner.stream.IntPullStream;
import dk.casa.streamliner.stream.IntPushStream;

import java.util.Arrays;
import java.util.stream.IntStream;

public class TestStreamSimple {
	public static int sumInts(int[] v) {
		return IntPushStream.of(v).sum();
	}

	private int[] v;
	public int sumIntsFromField() {
		return IntPushStream.of(v).sum();
	}

	public static int sumFilteredIntsPull(int[] v) {
		return IntPullStream.of(v).filter(x -> x % 2 == 0).sum();
	}

	public int sumFlatMapPull(int[] v) {
		int[] one = new int[]{1};
		return IntPullStream.of(v).flatMap(x -> IntPullStream.of(one)).sum();
	}

	public int limit() {
		return IntPushStream.of(v).limit(10).sum();
	}

	public static void main(String[] args) {
		int[] values = new int[]{1, 2, 3, 4, 5};
		System.out.println(flatMapWithJava(values));
	}

	private static int sumWithJava(int[] v) {
		return IntStream.of(v).sum();
	}

	// toArray creates two different sinks based on if the size of the stream is known
	// in advance. We cannot know this for sure, so the two sinks get mixed together in the dataflow analysis.
	// If we assume that the size is unknown then the stream uses a SpinedBuffer that we do not support.
	private static int[] toArray(int[] v) {
		return IntStream.of(v).toArray();
	}

	private static int sumSquaresWithJava(int[] v) {
		return IntStream.of(v).map(x -> x * x).sum();
	}

	private static int flatMapWithJava(int[] v) {
		return IntStream.of(v).flatMap(x -> IntStream.of(v).map(y -> y)).peek(System.out::println).limit(7).sum();
	}

	private static int takeWithJava(int[] v) {
		return IntStream.of(v).limit(2).sum();
	}

	private static boolean anyMatch(int[] v) {
		return IntStream.of(v).anyMatch(x -> x > 3);
	}

	private static void arraysSwitchStream(int[] v) {
		Arrays.stream(v).boxed().count();
		Arrays.stream(v).boxed().count();
	}

	private static int parallelJavaSum(int[] v) {
		return IntStream.of(v).parallel().sum();
	}
}
