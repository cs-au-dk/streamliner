package dk.casa.streamliner.other.testtransform;

public class TestInline {
	private int a = 10;

	private static int iff(boolean b) {
		return b ? 1 : 2;
	}

	public static void test() {
		iff(true);
		iff(false);
	}
}
