package dk.casa.streamliner.other.testtransform;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CollectionStreams {
	public static long arrayList() {
		ArrayList<String> strings = new ArrayList<>();
		strings.add("abc");
		strings.add("def");
		strings.add("angjs");
		strings.add("adddd");

		return strings.stream().filter(s -> s.contains("a")).count();
	}

	public static long arraysAsList() {
		List<String> strings = Arrays.asList(
				"abc",
				"def",
				"angjs",
				"adddd"
		);

		return strings.stream().filter(s -> s.contains("a")).count();
	}

	public static long linkedList() {
		List<String> strings = new LinkedList<>();
		strings.add("abc");
		strings.add("def");
		strings.add("angjs");
		strings.add("adddd");

		return strings.stream().filter(s -> s.contains("a")).count();
	}

	public static long hashSet() {
		Set<String> strings = new HashSet<>();
		strings.add("abc");
		strings.add("def");
		strings.add("angjs");
		strings.add("adddd");

		return strings.stream().filter(s -> s.contains("a")).count();
	}

	public static List<String> arrayToList(String[] arr) {
		return Stream.of(arr).collect(Collectors.toList());
	}

	public static Object escapeHatch;
	public static int escapingCollection(Integer[] arr) {
		List<Integer> list = new ArrayList<>();
		for(int i : arr) list.add(i);
		escapeHatch = list;

		return list.stream().mapToInt(Integer::intValue).sum();
	}

	public static int unknownCollection(Collection<Integer> ints) {
		return ints.stream().mapToInt(Integer::intValue).map(x -> x * x).filter(x -> x % 2 == 0).sum();
	}

	public static void unknownCollectionNoTerminal(Collection<Integer> ints) {
		ints.stream();
	}

	public static final class CustomCollection extends AbstractCollection<Integer> {
		private final Integer obj;

		public CustomCollection(Integer obj) {
			this.obj = Objects.requireNonNull(obj);
		}

		@Override
		public Iterator<Integer> iterator() {
			return new Iterator<Integer>() {
				private boolean state = true;

				@Override
				public boolean hasNext() {
					return state;
				}

				@Override
				public Integer next() {
					Integer v = state? obj : null;
					state = false;
					return v;
				}
			};
		}

		@Override
		public int size() {
			return 1;
		}
	}

	public static int customCollection(Integer v) {
		return new CustomCollection(v).stream().mapToInt(x -> x * x).filter(x -> x % 2 == 0).sum();
	}
}
