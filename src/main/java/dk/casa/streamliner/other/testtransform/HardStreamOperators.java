package dk.casa.streamliner.other.testtransform;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class HardStreamOperators {
	public static int intSizedDistinct(int[] arr) {
		return IntStream.of(arr).distinct().sum();
	}

	public static int intUnsizedDistinct(int[] arr) {
		return IntStream.of(arr).filter(x -> x % 2 == 0).distinct().sum();
	}

	// Sized streams and toArray is not a good combo for some reason
	@ExpectFail
	public static int[] intSizedToArray(int[] arr) {
		return IntStream.of(arr).toArray();
	}

	public static int[] intUnsizedToArray(int[] arr) {
		return IntStream.of(arr).filter(x -> x % 2 == 0).toArray();
	}

	public static int intSizedSorted(int[] arr) {
		return IntStream.of(arr).sorted().sum();
	}

	public static int intUnsizedSorted(int[] arr) {
		return IntStream.of(arr).filter(x -> x % 2 == 0).sorted().sum();
	}

	public static int refSizedDistinct(Integer[] arr) {
		return Stream.of(arr).distinct().reduce(0, Integer::sum);
	}

	public static int refUnsizedDistinct(Integer[] arr) {
		return Stream.of(arr).filter(x -> x % 2 == 0).distinct().reduce(0, Integer::sum);
	}

	public static int refSizedSorted(Integer[] arr) {
		return Stream.of(arr).sorted().reduce(0, Integer::sum);
	}

	public static List<String> refSizedSortedComparator(String[] strings) {
		return Stream.of(strings).sorted(Comparator.comparing(String::toLowerCase)).collect(Collectors.toList());
	}

	// Transformation is blocked by inability to inline ArrayList.forEach (could be solved with reflection/copying)
	@ExpectFail
	public static int refUnsizedSorted(Integer[] arr) {
		return Stream.of(arr).filter(x -> x % 2 == 0).sorted().reduce(0, Integer::sum);
	}

	public static int intConcat(int[] arr1, int[] arr2) {
		return IntStream.concat(IntStream.of(arr1), IntStream.of(arr2)).sum();
	}

	public static int refConcat(Integer[] arr1, Integer[] arr2) {
		return Stream.concat(Stream.of(arr1), Stream.of(arr2)).reduce(0, Integer::sum);
	}

	// When one of the concatees are unsized the analysis is not precise enough to allow optimization
	@ExpectFail
	public static int intConcatLUnsized(int[] arr1, int[] arr2) {
		return IntStream.concat(IntStream.of(arr1).filter(x -> x % 2 == 0), IntStream.of(arr2)).sum();
	}

	@ExpectFail
	public static int intConcatRUnsized(int[] arr1, int[] arr2) {
		return IntStream.concat(IntStream.of(arr1), IntStream.of(arr2).filter(x -> x % 2 == 0)).sum();
	}

	/* Not supported in Java 8
	public static int intTakeWhile(int[] arr) {
		return IntStream.of(arr).takeWhile(x -> x % 2 == 0).sum();
	}

	public static int refTakeWhile(Integer[] arr) {
		return Stream.of(arr).takeWhile(x -> x % 2 == 0).reduce(0, Integer::sum);
	}

	public static int intDropWhile(int[] arr) {
		return IntStream.of(arr).dropWhile(x -> x % 2 == 0).sum();
	}

	public static int refDropWhile(Integer[] arr) {
		return Stream.of(arr).dropWhile(x -> x % 2 == 0).reduce(0, Integer::sum);
	}
	 */
}
