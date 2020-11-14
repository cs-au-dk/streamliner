package dk.casa.streamliner.test.asm;

import dk.casa.streamliner.asm.ClassNodeCache;
import dk.casa.streamliner.asm.Decompile;
import dk.casa.streamliner.asm.Utils;
import dk.casa.streamliner.asm.analysis.FrameUtils;
import dk.casa.streamliner.asm.analysis.inter.InterFrame;
import dk.casa.streamliner.asm.analysis.inter.InterInterpreter;
import dk.casa.streamliner.asm.analysis.inter.InterValue;
import dk.casa.streamliner.asm.analysis.inter.PointerElement;
import dk.casa.streamliner.asm.analysis.inter.oracles.StreamLibraryOracle;
import dk.casa.streamliner.other.testtransform.HardStreamOperators;
import dk.casa.streamliner.stream.PushStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class TestInterprocOnGeneralStreams extends TestASM {
	@AfterEach
	public void cleanClassNodeCache() {
		// We need to clean the cache after each test since we transform methods
		// TODO: Instead we can make a parametrized test and only purge the cache for the transformed class
		ClassNodeCache.clear();
	}

	private static int _sumTest(Integer[] arr) {
		return PushStream.of(Arrays.asList(arr)).reduce(0, Integer::sum);
	}

	@Test
	public void testSumGeneral() throws AnalyzerException {
		MethodNode mn = getMethodNode(asmName, "_sumTest");

		fullTransform(asmName, mn);

		checkMethod(asmName, mn);
		Utils.printMethod(mn);
	}

	@Test
	public void testEmployeeTest() throws AnalyzerException {
		String owner = "dk/casa/streamliner/other/adaptedcases/EmployeeTest";
		MethodNode mn = getMethodNode(owner, "whenIncrementSalaryUsingPeek_thenApplyNewSalary");

		fullTransform(owner, mn);

		checkMethod(owner, mn);
		Utils.printMethod(mn);
	}

	private static boolean findFirstExists(Integer[] arr) {
		return Stream.of(arr).findFirst().isPresent();
	}

	@Test
	public void testFindFirst() throws AnalyzerException {
		transformAndRunTest(asmName, "findFirstExists", ClassSetup.invStatic((Object) new Integer[]{1}), new StreamLibraryOracle());
	}

	private static Optional<Integer> findFirstReturnValue(Integer[] arr) {
		return Stream.of(arr).findFirst();
	}

	@Test
	public void testFindFirstReturnValue() throws AnalyzerException {
		MethodNode mn = getMethodNode(asmName, "findFirstReturnValue");

		InterFrame[] frames = analyzeMethod(asmName, mn, new StreamLibraryOracle());
		InterFrame rf = Utils.getReturnFrame(mn, frames, new InterInterpreter(), InterFrame::new);
		InterValue returnValue = FrameUtils.getValue(rf, FrameUtils.stackTop(rf));

		assertEquals(PointerElement.uTOP, returnValue.pointer, "findFirst Optional should be untracked");
	}

	private static void ifPresent(int[] arr) {
		IntStream.of(arr).max().ifPresent(System.out::println);
	}

	@Test
	public void testIfPresent() throws AnalyzerException {
		transformAndRunTest(asmName, "ifPresent", ClassSetup.invStatic((Object) new int[]{1}), new StreamLibraryOracle());
	}

	@Test
	public void testArrayStream() throws AnalyzerException {
		String owner = "dk/casa/streamliner/other/testtransform/TestStreamSimple";
		MethodNode mn = getMethodNode(owner, "arraysSwitchStream");

		fullTransform(owner, mn);
		Utils.printMethod(mn);
		checkMethod(owner, mn);

		System.out.println(Decompile.run(mn));
	}

	@Test
	public void testEscapingCollectionStream() throws AnalyzerException {
		String owner = "dk/casa/streamliner/other/testtransform/CollectionStreams";
		MethodNode mn = getMethodNode(owner, "escapingCollection");

		fullTransform(owner, mn);

		System.out.println(Decompile.run(mn));
	}

	@ParameterizedTest
	@MethodSource
	public void testHardStreamOperators(String name) {
		String owner = "dk/casa/streamliner/other/testtransform/HardStreamOperators";
		MethodNode mn = getMethodNode(owner, name);

		Executable process = () -> {
			fullTransform(owner, mn);
			checkMethod(owner, mn);
		};

		if(mn.invisibleAnnotations != null && mn.invisibleAnnotations.stream().anyMatch(an -> an.desc.contains("ExpectFail")))
			assertThrows(AnalyzerException.class, process);
		else
			assertDoesNotThrow(process);

		System.out.println(Decompile.run(mn));
	}

	private static Stream<String> testHardStreamOperators() {
		Class<?> cls = HardStreamOperators.class;
		return Stream.of(cls.getDeclaredMethods()).filter(method -> !method.isSynthetic()).map(Method::getName).sorted();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"java/util/ArrayList", "java/util/LinkedList",
			"java/util/HashSet", "java/util/TreeSet",
			"java/util/ImmutableCollections$List12",
			"java/util/ImmutableCollections$ListN" })
	public void testVariousCollectionTransforms(String type) throws AnalyzerException {
		String owner = "dk/casa/streamliner/other/testtransform/CollectionStreams";
		MethodNode mn = getMethodNode(owner, "unknownCollection");

		Executable process = () -> fullTransform(owner, mn, new CollectionTypeOracle(type));
		if(type.equals("java/util/TreeSet"))
			assertThrows(AnalyzerException.class, process);
		else
			assertDoesNotThrow(process);

		System.out.println(Decompile.run(mn));
	}

	@Test
	@Disabled("We currently cannot handle the sorted operator")
	public void testLambdasInActionStreamBasic() throws AnalyzerException {
		String owner = "lambdasinaction/StreamBasic";
		MethodNode mn = getMethodNode(owner, "getLowCaloricDishesNamesInJava8");

		fullTransform(owner, mn, new CollectionTypeOracle("java/util/ArrayList"));

		System.out.println(Decompile.run(mn));
	}

	@Test
	public void testParallelStream() throws AnalyzerException {
		String owner = "dk/casa/streamliner/other/testtransform/TestStreamSimple";
		MethodNode mn = getMethodNode(owner, "parallelJavaSum");

		assertThrows(AnalyzerException.class, () -> {
			fullTransform(owner, mn);
			Utils.printMethod(mn);
			checkMethod(owner, mn);

			System.out.println(Decompile.run(mn));
		});
	}
}
