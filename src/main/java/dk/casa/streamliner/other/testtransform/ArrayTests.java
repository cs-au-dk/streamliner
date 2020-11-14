package dk.casa.streamliner.other.testtransform;

import java.util.Arrays;

public class ArrayTests {
	private static void nativeCopyOf() {
		char[] arr = new char[10];
		arr = Arrays.copyOf(arr, 20);
	}
}
