package dk.casa.streamliner.other.examples;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

// Source: https://github.com/oracle/graal/blob/master/substratevm/src/com.oracle.svm.driver/src/com/oracle/svm/driver/NativeImage.java

public class GraalVMConsolidateArgs {
	protected static String consolidateSingleValueArg(Collection<String> args, String argPrefix) {
		BiFunction<String, String, String> takeLast = (a, b) -> b;
		return consolidateArgs(args, argPrefix, Function.identity(), Function.identity(), () -> null, takeLast);
	}

	protected static boolean replaceArg(Collection<String> args, String argPrefix, String argSuffix) {
		boolean elementsRemoved = args.removeIf(arg -> arg.startsWith(argPrefix));
		args.add(argPrefix);
		return elementsRemoved;
	}

	private static <T> T consolidateArgs(Collection<String> args, String argPrefix,
	                                     Function<String, T> fromSuffix, Function<T, String> toSuffix,
	                                     Supplier<T> init, BiFunction<T, T, T> combiner) {
		T consolidatedValue = null;
		boolean needsConsolidate = false;
		for (String arg : args) {
			if (arg.startsWith(argPrefix)) {
				if (consolidatedValue == null) {
					consolidatedValue = init.get();
				} else {
					needsConsolidate = true;
				}
				consolidatedValue = combiner.apply(consolidatedValue, fromSuffix.apply(arg.substring(argPrefix.length())));
			}
		}
		if (consolidatedValue != null && needsConsolidate) {
			replaceArg(args, argPrefix, toSuffix.apply(consolidatedValue));
		}
		return consolidatedValue;
	}

	// TODO: Can be be extended with more interesting calls to consolidateArgs from the source class

	public static void main(String[] args) {
		ArrayList<String> ls = new ArrayList<>(Arrays.asList(args));
		String s = consolidateSingleValueArg(ls, "asd");
		System.out.println(s);
	}
}