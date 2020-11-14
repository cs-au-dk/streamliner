package dk.casa.streamliner.test.asm;

import dk.casa.streamliner.asm.ClassNodeCache;
import dk.casa.streamliner.asm.Utils;
import dk.casa.streamliner.asm.analysis.FlatElement;
import dk.casa.streamliner.asm.analysis.inter.*;
import dk.casa.streamliner.asm.analysis.inter.oracles.ExhaustiveOracle;
import dk.casa.streamliner.asm.analysis.inter.oracles.StreamLibraryOracle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.ACONST_NULL;

public class TestInterproc extends TestASM {
	@AfterEach
	public void cleanClassNodeCache() {
		// We need to clean the cache after each test since we transform methods
		// TODO: Instead we can make a parametrized test and only purge the cache for the transformed class
		ClassNodeCache.clear();
	}

	private static void throwNullPointer() {
		throw new NullPointerException();
	}

	@Test
	public void testThrowNullPointerException() {
		analyzeMethod(asmName, "throwNullPointer");
	}

	private static native void nativeMethod(Object o);

	private static void nullPointerIntoNative() {
		nativeMethod(null);
	}

	@Test
	public void testNullPointerIntoNative() {
		assertDoesNotThrow(() ->
				analyzeMethod(asmName, "nullPointerIntoNative"));
	}

	private static void escapeIntoArray(Object[] arr) {
		Object obj = new Object();
		arr[10] = obj;
	}

	@Test
	@Disabled("Escaping is not good right now")
	public void testEscapeIntoArray() throws AnalyzerException {
		MethodNode mn = getMethodNode(asmName, "escapeIntoArray");
		InterFrame[] frames = analyzeMethod(asmName, mn);
		InterFrame ret = Utils.getReturnFrame(mn, frames, new InterInterpreter(), InterFrame::new);
		Set<Integer> escaped = ret.getHeap().getEscaped();

		assertEquals(1, escaped.size());
		//assertTrue(escaped.contains(0));
		assertFalse(Utils.instructionStream(mn).anyMatch(insn -> insn.getOpcode() == ACONST_NULL));
	}

	private static int loadInteger(Integer[] arr) { return arr[10];	}

	@Test
	public void testLoadInteger() {
		assertDoesNotThrow(() -> analyzeMethod(asmName, "loadInteger"));
	}

	private static long[] returnArray() {
		return new long[12];
	}

	@Test
	public void testReturnArray() throws AnalyzerException {
		MethodNode mn = getMethodNode(asmName, "returnArray");
		InterFrame[] frames = analyzeMethod(asmName, mn);
		InterFrame ret = Utils.getReturnFrame(mn, frames, new InterInterpreter(), InterFrame::new);
		InterValue returnValue = ret.getStack(ret.getStackSize() - 1);

		assertTrue(returnValue.type.isPrecise());
		assertTrue(returnValue.type.maybePointer());
		assertEquals(returnValue.type.getType().getDescriptor(), "[J");

		assertEquals(PointerElement.uTOP, returnValue.pointer);
	}

	private static boolean arrayInstanceOf() {
		long[] longs = new long[10];
		return longs instanceof long[];
	}

	@Test
	public void testArrayInstanceOf() throws AnalyzerException {
		MethodNode mn = getMethodNode(asmName, "arrayInstanceOf");
		InterFrame[] frames = analyzeMethod(asmName, mn);
		InterFrame ret = Utils.getReturnFrame(mn, frames, new InterInterpreter(), InterFrame::new);
		FlatElement<Number> returnValue = ret.getStack(ret.getStackSize() - 1).constant;
		assertTrue(returnValue.isDefined());
		assertEquals(returnValue.value, 1);
	}

	private static int dontStackAllocate(boolean bool) {
		Integer a = 2, b = 3;
		Integer c = bool ? a : b;
		return c;
	}

	@Test
	public void testDontStackAllocate() {
		transformAndRunTest(asmName, "dontStackAllocate", ClassSetup.invStatic(false), new StreamLibraryOracle());
	}

	/* This program can reveal a bug in the inliner.
	   Virtual method dispatch reveals type information to the verifier
	   that is lost when inlining.
	   Here the toString call simply returns 'this' which the verifier
	   thinks is a CharSequence (and not a String!). Thus the verifier
	   throws an error when we try to return it as a String.
	   TODO: It should be possible to encounter this bug without indirect downcasting?
	 */
	private static String downcasting() {
		Object a = "abc";
		CharSequence asd = (CharSequence) a;
		return asd.toString();
	}

	@Test
	public void testDowncasting() throws AnalyzerException {
		MethodNode mn = getMethodNode(asmName, "downcasting");
		fullTransform(asmName, mn);
		Utils.printMethod(mn);
		checkMethod(asmName, mn);
	}

	private static void abstractGCWork(Object escape, boolean b) {
		if(b)
			// Maybe construct an object with some pointer fields that "escapes"
			((String[])escape)[0] = new String("asd");
		// Call native method to trigger overapproximation
		// Ideally we should be able to remove the unreachable object to avoid Top fields
		// However, the objects reachable from the initialization analysis are also affected so it doesn't help
		nativeMethod(escape);
	}

	@Test
	public void testAbstractGC() {
		analyzeMethod(asmName, "abstractGCWork");
	}

	private static void escapedChanged(Object[] os, boolean b) {
		for(int i = 0; i < 10; os[i] = new Object()) {}
	}

	@Test
	public void testEscapedChanged() {
		analyzeMethod(asmName, "escapedChanged");
	}

	@Test
	public void testInline() {
		analyzeMethod("dk/casa/streamliner/other/testtransform/TestInline", "test");
		InterproceduralTypePointerAnalysis.debug();
	}

	private static Map<Integer, Integer> getAllocationsIn(MethodNode mn) {
		return InterproceduralTypePointerAnalysis.allocations.entrySet().stream()
				.filter(e -> e.getKey().getMethod().equals(mn))
				.findAny().orElseThrow(NoSuchElementException::new).getValue();
	}

	@Test
	public void testPartialEscapeAnalysis() {
		String owner = "dk/casa/streamliner/other/testtransform/TestPartialEscapeAnalysis";
		MethodNode mn = getMethodNode(owner, "test");
		InterFrame[] frames = analyzeMethod(owner, mn);

		assertEquals(1, getAllocationsIn(mn).size());
		Utils.printMethodWithFrames(mn, frames);
	}

	@Test
	public void testPartialEscapeAnalysisArg() throws AnalyzerException {
		String owner = "dk/casa/streamliner/other/testtransform/TestPartialEscapeAnalysis";
		MethodNode mn = getMethodNode(owner, "testArg");

		InterFrame[] frames = analyzeMethod(owner, mn);

		assertEquals(1, getAllocationsIn(mn).size());
		InterFrame returnFrame = Utils.getReturnFrame(mn, frames, new InterInterpreter(), InterFrame::new);
		Set<Integer> escaped = returnFrame.getHeap().getEscaped();
		assertEquals(0, escaped.size());
	}

	@Test
	public void testPartialEscapeAnalysisArg2() {
		analyzeMethod("dk/casa/streamliner/other/testtransform/TestPartialEscapeAnalysis", "testArg2");
		InterproceduralTypePointerAnalysis.debug();
	}

	@Test
	@Disabled("Cannot assign to parameters")
	public void testPartialEscapeAnalysisArg3() throws AnalyzerException {
		String owner = "dk/casa/streamliner/other/testtransform/TestPartialEscapeAnalysis";
		MethodNode mn = getMethodNode(owner, "testArg3");

		InterFrame[] frames = analyzeMethod(owner, mn);

		assertEquals(1, InterproceduralTypePointerAnalysis.allocations.size());
		InterFrame returnFrame = Utils.getReturnFrame(mn, frames, new InterInterpreter(), InterFrame::new);
		Set<Integer> escaped = returnFrame.getHeap().getEscaped();
		assertEquals(1, escaped.size());
	}

	@Test
	@Disabled("Assigning to parameters is forbidden")
	public void testPartialEscapeToArg() throws AnalyzerException {
		String owner = "dk/casa/streamliner/other/testtransform/TestPartialEscapeAnalysis";
		MethodNode mn = getMethodNode(owner, "testEscapeToArg");
		InterFrame[] frames = analyzeMethod(owner, mn);

		InterFrame returnFrame = Utils.getReturnFrame(mn, frames, new InterInterpreter(), InterFrame::new);
		Set<Integer> escaped = returnFrame.getHeap().getEscaped();
		assertEquals(escaped.size(), 1);
		assertTrue(escaped.contains(0));
	}

	@Test
	@Disabled("Assigning to parameters is forbidden")
	public void testPartialImpreciseEscapeToArg() throws AnalyzerException {
		String owner = "dk/casa/streamliner/other/testtransform/TestPartialEscapeAnalysis";
		MethodNode mn = getMethodNode(owner, "testImpreciseEscapeToArg");
		analyzeMethod(owner, mn);
	}

	@Test
	public void testPartialEscapeToReturnValue() throws AnalyzerException {
		String owner = "dk/casa/streamliner/other/testtransform/TestPartialEscapeAnalysis";
		MethodNode mn = getMethodNode(owner, "testEscapeToReturnValue");
		InterFrame[] frames = analyzeMethod(owner, mn);

		InterFrame returnFrame = Utils.getReturnFrame(mn, frames, new InterInterpreter(), InterFrame::new);
		Set<Integer> escaped = returnFrame.getHeap().getEscaped();
		assertEquals(2, escaped.size());
		//assertTrue(escaped.contains(0));
		//assertTrue(escaped.contains(1));
	}

	@Test
	public void testPartialEscapeSomeEscapeSomeDont() throws AnalyzerException {
		String owner = "dk/casa/streamliner/other/testtransform/TestPartialEscapeAnalysis";
		MethodNode mn = getMethodNode(owner, "someEscapeSomeDont");

		InterFrame[] frames = analyzeMethod(owner, mn);

		InterFrame returnFrame = Utils.getReturnFrame(mn, frames, new InterInterpreter(), InterFrame::new);
		Set<Integer> escaped = returnFrame.getHeap().getEscaped();
		assertEquals(2, escaped.size(), "Control sensitivity might be bugged?" + escaped);
		//assertTrue(escaped.containsAll(Arrays.asList(0, 1)), escaped.toString());
	}

	private InterValue getLocalValue(MethodNode mn, InterFrame frame, String fieldName) {
		return frame.getLocal(mn.localVariables.stream()
				.filter(lvn -> lvn.name.equals(fieldName)).findAny().get().index);
	}

	@Test
	@Disabled("This test does not make sense when assigning to parameters is disallowed")
	public void testPartialEscapePutGetOnEscape() throws AnalyzerException {
		String owner = "dk/casa/streamliner/other/testtransform/TestPartialEscapeAnalysis";
		MethodNode mn = getMethodNode(owner, "putGetOnEscape");

		InterFrame[] frames = analyzeMethod(owner, mn);
		InterFrame returnFrame = Utils.getReturnFrame(mn, frames, new InterInterpreter(), InterFrame::new);
		assertEquals(3, returnFrame.getHeap().getEscaped().size());

		// b2 should be iTOP
		InterValue b2 = getLocalValue(mn, returnFrame, "b2");
		assertEquals(PointerElement.iTOP, b2.pointer);

		// b should point to 2
		InterValue b = getLocalValue(mn, returnFrame, "b");
		assertEquals(2, b.pointsTo());

		InterValue obj = returnFrame.getHeap().getField(b.pointsTo(), "obj", new InterValue(new TypeElement(false, TypeElement.TOP), PointerElement.TOP));
		// The best sound value we can hope for is 1, so 1 should be lower than the actual value
		PointerElement best = new PointerElement(1);
		assertTrue(best.leq(obj.pointer), String.format("Expected %s ⊑ %s", best, obj.pointer));
	}

	@Test
	public void testPushSumOfSquaresEven() throws AnalyzerException {
		String owner = "dk/casa/streamliner/stream/PushSumOfSquaresEven";
		MethodNode mn = getMethodNode(owner, "test");
		InterFrame[] frames = analyzeMethod(owner, mn);
		InterFrame res = Utils.getReturnFrame(mn, frames, new InterInterpreter(), InterFrame::new);

		InterproceduralTypePointerAnalysis.debug();
		//res.showDot("test");
	}

	@Test
	public void testSumArrayFlatMap() throws AnalyzerException {
		String owner = "dk/casa/streamliner/stream/PushSumOfSquaresEven";
		MethodNode mn = getMethodNode(owner, "sumArrayFlatMap");

		InterFrame[] frames = analyzeMethod(owner, mn);
		InterFrame rFrame = Utils.getReturnFrame(mn, frames, new InterInterpreter(), InterFrame::new);

		//rFrame.showDot("sumArrayFlatMap");
	}

	@Test
	public void testStreamSimpleSum() {
		analyzeMethod("dk/casa/streamliner/other/testtransform/TestStreamSimple", "sumInts");
		InterproceduralTypePointerAnalysis.debug();
	}

	@Test
	public void testStreamSimpleLimit() {
		analyzeMethod("dk/casa/streamliner/other/testtransform/TestStreamSimple", "limit");
		InterproceduralTypePointerAnalysis.debug();
	}

	@Test
	public void testArrayCopyOf() {
		String owner = "dk/casa/streamliner/other/testtransform/ArrayTests";
		MethodNode mn = getMethodNode(owner, "nativeCopyOf");
		Utils.printMethod(mn);
		analyzeMethod(owner, mn);
	}

	@Test
	public void testSum() {
		String owner = "dk/casa/streamliner/jmh/TestStream";
		MethodNode mn = getMethodNode(owner, "sum");
		analyzeMethod(owner, mn, new StreamLibraryOracle());
	}

	@Test
	public void testTransformationInline() throws AnalyzerException {
		String owner = "dk/casa/streamliner/other/testtransform/TestInline";
		MethodNode mn = getMethodNode(owner, "test");
		analyzeMethod(owner, mn);
		Utils.printMethod(mn);

		fullTransform(owner, mn, new ExhaustiveOracle());
		checkMethod(owner, mn);

		assertEquals(1, Utils.instructionStream(mn).filter(insn -> insn.getOpcode() >= 0).count(), () -> {
			Utils.printMethod(mn);
			return "";
		});
	}

	@Test
	public void testTransformationPartialEscapeSomeEscape() throws AnalyzerException {
		String owner = "dk/casa/streamliner/other/testtransform/TestPartialEscapeAnalysis";
		MethodNode mn = getMethodNode(owner, "someEscapeSomeDont");

		fullTransform(owner, mn);

		checkMethod(owner, mn);
		Utils.printMethod(mn);
	}
	
	@Test
	public void testTransformSubField() throws AnalyzerException {
		String owner = "dk/casa/streamliner/other/testtransform/SubField";
		MethodNode mn = getMethodNode(owner, "main");

		fullTransform(owner, mn);

		checkMethod(owner, mn);
		Utils.printMethod(mn);
	}

	@Test
	public void testTransformationFieldDefault() throws AnalyzerException {
		String owner = "dk/casa/streamliner/other/testtransform/TestPartialEscapeAnalysis";
		MethodNode mn = getMethodNode(owner, "fieldDefaultValue");

		fullTransform(owner, mn);

		checkMethod(owner, mn);
	}

	@Test
	public void testTranformCaptureLambdaField() throws AnalyzerException {
		String owner = "dk/casa/streamliner/other/testtransform/CaptureLambda";
		MethodNode mn = getMethodNode(owner, "captureField");

		fullTransform(owner, mn);

		checkMethod(owner, mn);
		Utils.printMethod(mn);
	}

	@Test
	public void testAnalyzePullFlatMap() throws AnalyzerException {
		String owner = "dk/casa/streamliner/other/testtransform/TestStreamSimple";
		MethodNode mn = getMethodNode(owner, "sumFlatMapPull");

		fullTransform(owner, mn);
		checkMethod(owner, mn);
		Utils.printMethod(mn);
	}

	private String getClassName() {
		return TransformAndRunTests.class.getName();
	}

	@Test
	public void testGetClassName() {
		analyzeMethod("dk/casa/streamliner/test/asm/TestInterproc", "getClassName");
	}

	// Cases

	@Test
	public void testJava8Tutorial() {
		String owner = "dk/casa/streamliner/other/examples/Java8TutorialLambda3";
		MethodNode mn = getMethodNode(owner, "main");

		assertDoesNotThrow(() -> analyzeMethod(owner, mn, new StreamLibraryOracle()));
	}

	@Test
	@Disabled("TODO: We need some way to handle this example")
	public void testInfiniteAnalysis() {
		analyzeMethod("dk/casa/streamliner/other/testtransform/InfiniteAnalysis", "test");
	}
}
