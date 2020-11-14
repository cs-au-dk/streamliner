package dk.casa.streamliner.stream;

public class PushSumOfSquaresEven {
	public static int test(int[] v) {
		return IntPushStream.of(v)
				//.filter(x -> x % 2 == 0)
				.filter(x -> x % 2 == 0)
				.map(x -> x * x)
				//.map(x -> x * x)
				.sum();
	}

	public static void main(String[] args) {
		int[] arr = new int[]{1,2,3,4,5,6};
		int res = sumArrayFlatMap(arr);
		System.out.println(res);
	}

	public static int sumArrayFlatMap(int[] v) {
		int[] inner = new int[]{-2, 1, 2};
		return IntPushStream.of(v)
				.filter(x -> x % 2 == 0)
				.flatMap(x -> IntPushStream.of(inner).map(y -> x * y))
				.sum();
	}
}
