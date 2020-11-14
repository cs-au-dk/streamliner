package dk.casa.streamliner.test.asm;

import dk.casa.streamliner.asm.ClassNodeCache;
import dk.casa.streamliner.asm.Utils;
import dk.casa.streamliner.asm.analysis.inter.Context;
import dk.casa.streamliner.asm.analysis.inter.InterproceduralTypePointerAnalysis;
import dk.casa.streamliner.asm.analysis.inter.oracles.ExhaustiveOracle;
import dk.casa.streamliner.asm.transform.LambdaPreprocessor;
import dk.casa.streamliner.other.testtransform.CaptureLambda;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;

import java.awt.*;
import java.io.File;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class TestLambdaPreprocessor extends TestASM {
	private static final String captureLambdaOwner = "dk/casa/streamliner/other/testtransform/CaptureLambda";

	@Test
	public void testCaptureLambda() {
		analyzeMethod(captureLambdaOwner, "test");
		InterproceduralTypePointerAnalysis.debug();
	}

	@Test
	public void testCaptureLambdaAdder() {
		analyzeMethod(captureLambdaOwner, "adder");
		InterproceduralTypePointerAnalysis.debug();
	}

	@Test
	void testCaptureLambdaField() {
		MethodNode mn = getMethodNode(captureLambdaOwner, "captureField");
		analyzeMethod(captureLambdaOwner, mn);
		Utils.printMethod(mn);
		InterproceduralTypePointerAnalysis.debug();
	}

	private void intConversion() {
		Consumer<Integer> f = (Integer i) -> {};
		IntConsumer fi = f::accept;
		fi.accept(2);
	}

	private void intConversionRev() {
		IntConsumer f = (int i) -> {};
		Consumer<Integer> fo = f::accept;
		fo.accept(Integer.valueOf(2));
	}

	@Test
	void testPreprocessIntConversion() {
		MethodNode mn = getMethodNode(asmName, "intConversion");
		Utils.printMethod(mn);
	}

	@Test
	void testPreprocessIntConversionRev() {
		getMethodNode(asmName, "intConversionRev");
	}

	private void returnIntConversion() {
		Integer a = 2, b = 3;
		BinaryOperator<Integer> f = Integer::sum;
		Integer c = f.apply(a, b);
	}

	@Test
	void testPreprocessReturnIntConversion() {
		getMethodNode(asmName, "returnIntConversion");
	}

	private void longTest() {
		long a = 2L, b = 30L;
		LongBinaryOperator oper = Long::sum;
		long c = oper.applyAsLong(a, b);
	}

	@Test
	void testPreprocessLongArguments() {
		getMethodNode(asmName, "longTest");
	}

	private void captureLongTest() {
		long a = 2L;
		int b = 3;
		LongSupplier sup = () -> a + b;
		long c = sup.getAsLong();
	}

	@Test
	void testPreprocessCaptureLong() {
		getMethodNode(asmName, "captureLongTest");
	}

	private void returnLongConversion() {
		Long a = 2L, b = 3L;
		BinaryOperator<Long> f = Long::sum;
		Long c = f.apply(a, b);
	}

	@Test
	void testPreprocessReturnLongConversion() {
		getMethodNode(asmName, "returnLongConversion");
	}

	private static boolean and(boolean a, boolean b) {
		return a && b;
	}

	private void booleanConversion(boolean c) {
		Boolean a = true;
		BinaryOperator<Boolean> f = TestLambdaPreprocessor::and;
		boolean res = f.apply(a, c);
	}

	@Test
	void testPreprocessBooleanConversion() {
		getMethodNode(asmName, "booleanConversion");
	}

	private static void checkcastTest(Point arg) {
		Consumer<Point> cons = (Point i) ->
				System.out.println(i.x);
		cons.accept(arg);
	}

	@Disabled("fullTransform no longer aggressively inlines lambdas")
	@Test
	void testCheckcast() throws AnalyzerException {
		MethodNode mn = getMethodNode(asmName, "checkcastTest");
		fullTransform(asmName, mn);
		checkMethod(asmName, mn);

		assertTrue(Stream.of(mn.instructions.toArray()).anyMatch(insn -> insn.getOpcode() == Opcodes.CHECKCAST && ((TypeInsnNode) insn).desc.equals("java/awt/Point")),
				() -> { Utils.printMethod(mn); return ""; });
	}

	private static File createObject(String s) {
		Function<String, File> creator = File::new;
		File f = creator.apply(s);
		return null;
	}

	@Test
	void testCreateObject() throws AnalyzerException {
		MethodNode mn = getMethodNode(asmName, "createObject");
		analyzeMethod(asmName, mn);
	}

	private static void printSomething() {
		Runnable r = () -> System.out.println("ASD");
		r.run();
	}

	@Test
	void runPrintSomethingAfterPreprocess() {
		MethodNode mn = getMethodNode(asmName, "printSomething");
		//new LambdaPreprocessor(mn).postprocess();
		Class<?> cls = getClassWithReplacedMethod(asmName, mn);
		assertDoesNotThrow(() -> executeMethod(cls, mn, null));
	}

	/* This test sets up a scenario where the instantiatedMethodType of `op` is important
		as it mandates otherwise missing casts to java/lang/Integer */
	private static <V> void func(V base, List<V> l, BinaryOperator<V> func) {
		func.apply(base, l.get(0));
	}

	private static void spooky(Integer[] l) {
		List<Integer> lv = Arrays.asList(l);
		BinaryOperator<Integer> op = Integer::sum;
		func(0, lv, op);
	}

	@Test
	void testSpooky() throws AnalyzerException {
		MethodNode mn = getMethodNode(asmName, "spooky");
		fullTransform(asmName, mn);
		checkMethod(asmName, mn);
		Utils.printMethod(mn);
	}

	private static void postProcess() {
		IntUnaryOperator op = x -> x + 1;
	}

	@Test
	void testPostProcessingWhenCreateIsDuplicated() {
		assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
			MethodNode mn = getMethodNode(asmName, "postProcess");
			MethodInsnNode create = (MethodInsnNode) mn.instructions.get(2);
			assertEquals("create", create.name);

			// Duplicate instruction
			mn.instructions.insertBefore(create, create.clone(null));
			mn.instructions.insertBefore(create, new InsnNode(Opcodes.POP));

			new LambdaPreprocessor(mn).postprocess();

			assertEquals(2, Utils.instructionStream(mn).filter(insn -> insn instanceof InvokeDynamicInsnNode).count());

			checkMethod(asmName, mn);
		});
	}

	private static Predicate<Integer> altMetafactory() {
		return (Predicate<Integer> & Serializable) x -> x % 2 == 0;
	}

	@Test
	void testAltMetafactory() {
		MethodNode mn = getMethodNode(asmName, "altMetafactory");
		assertFalse(Utils.instructionStream(mn).anyMatch(insn -> insn instanceof InvokeDynamicInsnNode), "Lambda not transformed");

		MethodInsnNode insn = (MethodInsnNode) Utils.instructionStream(mn).filter(in -> in instanceof MethodInsnNode).findFirst().orElseThrow(NoSuchElementException::new);
		ClassNode lambda = ClassNodeCache.get(insn.owner);
		assertTrue(lambda.interfaces.contains("java/io/Serializable"));
	}

	private static int privateLambda() {
		return CaptureLambda.A.getLambda().get();
	}

	@Test
	void testPrivateLambda() throws AnalyzerException, InvocationTargetException, IllegalAccessException {
		MethodNode mn = getMethodNode(asmName, "privateLambda");
		fullTransform(asmName, mn, new ExhaustiveOracle() {
			public boolean shouldTrackAllocation(Context context, Type type) {
			    // Make sure the lambda is not stack allocated
				return false;
			}
		});

		assertTrue(Utils.instructionStream(mn).anyMatch(insn -> insn instanceof InvokeDynamicInsnNode));

		Class<?> cls = getClassWithReplacedMethod(asmName, mn);
		InvocationTargetException exc = assertThrows(InvocationTargetException.class, () -> executeMethod(cls, mn, null));
		Throwable cause = exc.getTargetException();
		assertTrue(cause instanceof IllegalAccessError);
		assertTrue(cause.getMessage().contains("CaptureLambda$A.getInt()"));
	}
}
