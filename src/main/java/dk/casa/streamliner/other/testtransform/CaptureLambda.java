package dk.casa.streamliner.other.testtransform;

import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

public class CaptureLambda {
	public static int test() {
		int x = 7;
		IntUnaryOperator plus10s = y -> x + y;
		int plus10 = plus10s.applyAsInt(10);
		return plus10;
	}

	public IntUnaryOperator adder(int x) {
		return y -> x + y;
	}

	int x = 0;
	public void captureField() {
		IntUnaryOperator oper = y -> { x += 2; return x + y; };
		oper.applyAsInt(20);
	}

	public static void main(String[] args) {
		System.out.println(test());
	}


	// Another experiment
	public static class A {
		public static Supplier<Integer> getLambda() {
			return A::getInt;
		}

		private static int getInt() { return 42; }
	}
}
