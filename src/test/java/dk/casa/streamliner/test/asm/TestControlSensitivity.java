package dk.casa.streamliner.test.asm;

import dk.casa.streamliner.asm.analysis.inter.InterValue;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.GOTO;

public class TestControlSensitivity extends TestASM {

	@Retention(RetentionPolicy.RUNTIME)
	@interface ReturnValueTest{};

	private InterValue getReturnValue(String name) throws AnalyzerException {
		return getReturnValue(asmName, name);
	}

	@Test
	public void constantsOnObjects() throws AnalyzerException {
		String owner = "dk/casa/streamliner/other/testtransform/TestPartialEscapeAnalysis";
		InterValue returnValue = getReturnValue(owner, "test");
		assertTrue(returnValue.constant.isDefined());
		assertEquals(20, returnValue.constant.value);
	}

	@ReturnValueTest
	private static int variousIntOperators() {
		int a = 4;
		int b = a * 41231;
		int c = b / 10;
		int d = 5;
		int e = d | 2;
		return c - e;
	}

	@ReturnValueTest
	private static int shiftOperators() {
		int a = 3;
		int b = 2;
		return 1 << a >> b;
	}

	@ReturnValueTest
	private static int controlSensitive() {
		int a = 33;
		int b = a - 1 + 1;
		return a == b ? 1 : 3;
	}

	@Test
	public void testTransformControlSensitivity() throws AnalyzerException {
		MethodNode mn = getMethodNode(asmName, "controlSensitive");

		fullTransform(asmName, mn);

		for(AbstractInsnNode insn : mn.instructions) {
			if(insn instanceof JumpInsnNode)
				assertEquals(insn.getOpcode(), GOTO, "Known branches should be transformed");
		}
	}

	@ReturnValueTest
	private static int multiReturnControlSensitive() {
		int a = 22;
		if(a - 1 == a + 1 - 2)
			return 2;
		else
			return 5;
	}

	@ReturnValueTest
	private static int nullControlSensitive() {
		Object obj = null;
		if(obj == null)
			return 2;
		return 5;
	}

	@ReturnValueTest
	private static int nnullControlSensitive() {
		Object obj = new Object();
		if(obj != null)
			return 2;
		return 5;
	}

	private enum Singleton { VALUE; }
	@ReturnValueTest
	@Disabled("Requires possibility for client to control pre-analysis")
	private static int enumControlSensitivity() {
		Singleton v = Singleton.VALUE;
		return v == Singleton.VALUE ? 2 : 5;
	}

	static private class A {}
	static private class B extends A {}
	@ReturnValueTest
	private static int nullInstanceOf() {
		A a = null;
		return a instanceof A ? 1 : 0;
	}

	@ReturnValueTest
	private static int simpleInstanceOf() {
		A a = new A();
		return a instanceof A ? 1 : 0;
	}

	@ReturnValueTest
	private static int superInstanceOf() {
		B b = new B();
		return b instanceof A ? 1 : 0;
	}

	// Returns a stream of all the methods in this class with the ReturnValueTest annotation
	public static Stream<Method> methodProvider() {
		return Stream.of(TestControlSensitivity.class.getDeclaredMethods())
				.filter(m -> m.isAnnotationPresent(ReturnValueTest.class))
				.filter(m -> !m.isAnnotationPresent(Disabled.class))
				.sorted(Comparator.comparing(Method::getName));
	}

	@ParameterizedTest
	@MethodSource("methodProvider")
	public void testReturnValue(Method method) throws AnalyzerException, InvocationTargetException, IllegalAccessException {
		InterValue returnValue = getReturnValue(method.getName());
		assertTrue(returnValue.constant.isDefined(), "Return value is not precise");

		Object res = method.invoke(this);
		assertTrue(res instanceof Integer);

		assertEquals(res, returnValue.constant.value);
	}
}
