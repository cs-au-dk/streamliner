package dk.casa.streamliner.utils;

import java.util.List;

public class ListUtils {
	public static <V> V getBack(List<V> l, int i) {
		return l.get(l.size() - 1 - i);
	}

	public static <V> V last(List<V> l) {
		return getBack(l, 0);
	}
}
