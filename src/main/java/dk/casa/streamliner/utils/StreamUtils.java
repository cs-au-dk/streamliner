package dk.casa.streamliner.utils;

import java.util.function.Function;
import java.util.stream.Stream;

public class StreamUtils {
	@SafeVarargs
	public static <V> Stream<V> concat(Stream<V>... args) {
		return Stream.of(args).flatMap(Function.identity());
	}
}
