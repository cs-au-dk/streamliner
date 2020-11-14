package dk.casa.streamliner.other.testtransform;

public class InfiniteAnalysis {
	public static void test(int i) {
		if(i <= 0) return;

		A a = new A();
		test(i - 1);
	}
}
