package dk.casa.streamliner.test.asm;

import dk.casa.streamliner.utils.StreamUtils;
import dk.casa.streamliner.asm.ClassNodeCache;
import dk.casa.streamliner.asm.analysis.inter.Context;
import dk.casa.streamliner.asm.analysis.inter.InterValue;
import dk.casa.streamliner.asm.analysis.inter.oracles.ExhaustiveOracle;
import dk.casa.streamliner.asm.analysis.inter.oracles.Oracle;
import dk.casa.streamliner.asm.analysis.inter.oracles.StreamLibraryOracle;
import org.apache.commons.math3.util.Pair;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.aggregator.ArgumentsAccessor;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.params.provider.Arguments.arguments;

public class TransformAndRunTests extends TestASM {
	private static class WinterbeOracle extends StreamLibraryOracle {
		private final int testcase;

		public WinterbeOracle(int i) {
			testcase = i;
		}

		@Override
		public boolean shouldAnalyseCall(Context context, MethodInsnNode minsn) {
			return super.shouldAnalyseCall(context, minsn)
					|| (minsn.owner.startsWith("dk/casa/streamliner/other/winterbe/") && minsn.name.startsWith("test"));
		}

		@Override
		public Optional<Type> queryType(Context context, MethodInsnNode minsn, InterValue receiver) {
			switch (testcase) {
				case 5:
					if(minsn.owner.equals("java/util/List") && minsn.name.equals("stream"))
						return Optional.of("java/util/Arrays$ArrayList").map(Type::getObjectType);
					break;

				case 7:
					if(minsn.owner.equals("java/util/List") && minsn.name.equals("stream"))
						return Optional.of("java/util/ArrayList").map(Type::getObjectType);
					break;
			}

			return super.queryType(context, minsn, receiver);
		}
	}

	public static Stream<Arguments> transformTestProvider() {
		String[] microbenchmarks = new String[] {
				"sum", "sumOfSquares", "sumOfSquaresEven",
				"megamorphicMaps", "megamorphicFilters",
				"count", "filterCount", "filterMapCount",
				"cart", "flatMapTake", "flatMapTakeRev",
				"allMatch"
		};

		ClassSetup microbenchmarkCs = (Class<?> cls) -> {
			// Reduce test size
			for(String fieldName : Arrays.asList("N", "N_outer")) {
				Field field = cls.getSuperclass().getDeclaredField(fieldName);
				field.setAccessible(true);
				field.set(null, 1000);
			}

			// Construct object and invoke setup function to fill arrays
			Object instance = cls.getDeclaredConstructor().newInstance();
			executeMethod(cls, "setUp", Type.getMethodDescriptor(Type.VOID_TYPE), instance);
			return new Pair<>(instance, new Object[]{});
		};

		ClassSetup initializeV = (Class<?> cls) -> {
			Object instance = cls.getDeclaredConstructor().newInstance();
			Field field = cls.getDeclaredField("v");
			field.setAccessible(true);
			field.set(instance, new int[] {1, 25, 5, 599, 12, 689, 1, 2, 2, 0, 99, -3, 20});
			return new Pair<>(instance, new Object[]{});
		};

		ClassSetup callStaticWithArray = ClassSetup.invStatic((Object) new int[] {1, 2, 3, 4, 5, 6});
		ClassSetup callStaticWithIntegerArray = ClassSetup.invStatic((Object) new Integer[] {1, 2, 3, 4, 5, 6});
		ClassSetup callStaticWithEmptyArgs = ClassSetup.invStatic((Object) new String[]{});

		return StreamUtils.concat(
				// Some smaller tests
				Stream.of(
						arguments("dk/casa/streamliner/other/testtransform/TestPartialEscapeAnalysis", "test", ClassSetup.invStatic(), new ExhaustiveOracle()),

						arguments("dk/casa/streamliner/other/testtransform/TestStreamSimple", "sumInts", callStaticWithArray),
						arguments("dk/casa/streamliner/other/testtransform/TestStreamSimple", "sumIntsFromField", initializeV),
						arguments("dk/casa/streamliner/other/testtransform/TestStreamSimple", "toArray", callStaticWithArray),
						arguments("dk/casa/streamliner/other/testtransform/TestStreamSimple", "sumWithJava", callStaticWithArray),
						arguments("dk/casa/streamliner/other/testtransform/TestStreamSimple", "takeWithJava", callStaticWithArray),
						arguments("dk/casa/streamliner/other/testtransform/TestStreamSimple", "sumFilteredIntsPull", callStaticWithArray),
						arguments("dk/casa/streamliner/other/testtransform/TestStreamSimple", "limit", initializeV),
						arguments("dk/casa/streamliner/other/testtransform/TestStreamSimple", "anyMatch", callStaticWithArray),

						arguments("dk/casa/streamliner/stream/PushSumOfSquaresEven", "test", callStaticWithArray),
						arguments("dk/casa/streamliner/stream/PushSumOfSquaresEven", "sumArrayFlatMap", callStaticWithArray),

						arguments("dk/casa/streamliner/test/asm/TestInterprocOnGeneralStreams", "_sumTest", callStaticWithIntegerArray)
				),
				// Cases
				Stream.of(
						arguments("dk/casa/streamliner/other/examples/SumRange", "main", ClassSetup.invStatic((Object) new String[]{"100000"})),
						arguments("dk/casa/streamliner/other/examples/EmployeeTest", "whenIncrementSalaryUsingPeek_thenApplyNewSalary", ClassSetup.invStatic(),
								new CollectionTypeOracle("java/util/Arrays$ArrayList")),
						//arguments("employeetest/EmployeeTest", "main", callStaticWithEmptyArgs),
						arguments("dk/casa/streamliner/other/examples/FirefoxBinary", "main", callStaticWithEmptyArgs),
						arguments("dk/casa/streamliner/other/examples/GraalVMConsolidateArgs", "main", ClassSetup.invStatic((Object) new String[]{"asdarg"}))
				),
				// Collections
				Stream.of(
						arguments("dk/casa/streamliner/other/testtransform/CollectionStreams", "arrayList", ClassSetup.invStatic()),
						arguments("dk/casa/streamliner/other/testtransform/CollectionStreams", "arraysAsList", ClassSetup.invStatic(),
								new CollectionTypeOracle("java/util/Arrays$ArrayList")),
						arguments("dk/casa/streamliner/other/testtransform/CollectionStreams", "linkedList", ClassSetup.invStatic()),
						arguments("dk/casa/streamliner/other/testtransform/CollectionStreams", "hashSet", ClassSetup.invStatic()),
						arguments("dk/casa/streamliner/other/testtransform/CollectionStreams", "arrayToList", ClassSetup.invStatic((Object) new String[] { "abc", "def", "aaa" })),
						arguments("dk/casa/streamliner/other/testtransform/CollectionStreams", "customCollection", ClassSetup.invStatic(4))
				),
				// Winterbe Java 8 Tutorial
				IntStream.rangeClosed(1, 13).mapToObj(i -> arguments("dk/casa/streamliner/other/winterbe/Streams" + i, "main",
																	 ClassSetup.invStatic((Object) new String[]{}), new WinterbeOracle(i))),
				IntStream.rangeClosed(1, 2).mapToObj(i -> arguments("dk/casa/streamliner/other/winterbe/Optional" + i, "main", ClassSetup.invStatic((Object) new String[]{}))),
				// Microbenchmarks
				Stream.of("Stream", "Pull", "Push").flatMap(kind ->
					Stream.of(microbenchmarks).map(test -> arguments("dk/casa/streamliner/jmh/Test" + kind, test, microbenchmarkCs))));
	}

	@ParameterizedTest
	@MethodSource("transformTestProvider")
	public void testTransformAndRun(String owner, String name, ClassSetup setup, ArgumentsAccessor accessor) {
		try {
			transformAndRunTest(owner, name, setup,
					accessor.size() <= 3? new StreamLibraryOracle() : accessor.get(3, Oracle.class));
		} finally {
			ClassNodeCache.clear();
		}
	}
}
