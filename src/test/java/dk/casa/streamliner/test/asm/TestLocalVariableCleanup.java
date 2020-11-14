package dk.casa.streamliner.test.asm;

import dk.casa.streamliner.asm.Utils;
import dk.casa.streamliner.asm.transform.LocalVariableCleanup;
import dk.casa.streamliner.asm.transform.SlidingWindowOptimizer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.objectweb.asm.Opcodes.*;

public class TestLocalVariableCleanup extends TestASM {

	private MethodNode transformAndCheck(String name) {
		MethodNode mn = getMethodNode(asmName, name);
		try {
			new LocalVariableCleanup(asmName, mn).run();
			SlidingWindowOptimizer.run(mn);
		} catch (AnalyzerException e) {
			throw new RuntimeException(e);
		}

		checkMethod(asmName, mn);
		return mn;
	}

	private static long simple() {
		long a = 10L;
		long b = a;
		return b += 20L;
	}

	@Test
	public void testSimple() {
		MethodNode mn = transformAndCheck("simple");
		Utils.printMethod(mn);
	}

	private static long bothVars() {
		long a = 10L;
		long b = a + 20L;
		return b;
	}

	@Test
	public void testBothVars() {
		MethodNode mn = transformAndCheck("bothVars");
		Utils.printMethod(mn);
	}

	private static long bothVarsWithRedundant() {
		long a = 10L;
		long b = a + 20L;
		long c = a;
		return b + c;
	}

	@Test
	public void testRedundant() {
		MethodNode mn = transformAndCheck("bothVarsWithRedundant");
		Utils.printMethod(mn);
	}

	private static int deadStore() {
		int a = 10;
		int b = a + 20;
		return 0;
	}

	@Test
	public void testDeadStore() {
		MethodNode mn = transformAndCheck("deadStore");
		Utils.printMethod(mn);
		assertEquals(0, mn.maxLocals);
	}

	private int unusedThis(int a) {
		return a;
	}

	@Test
	public void testUnusedThis() {
		transformAndCheck("unusedThis");
	}

	private int tableSwitch(int a) {
		int b = 0;
		switch(a) {
			case 1:
				b = 2;
				break;
			case 3:
				b = 4;
				break;
			default:
				b = 10;
		}
		return b;
	}

	@Test
	public void testTableSwitch() {
		MethodNode mn = transformAndCheck("tableSwitch");
		Utils.printMethod(mn);
	}

	@Test
	public void testDeadIinc() throws AnalyzerException {
		MethodNode mn = new MethodNode(ACC_PRIVATE, "deadIinc", Type.getMethodDescriptor(Type.VOID_TYPE), null, null);
		LabelNode lbl = new LabelNode();
		Utils.addInstructions(mn.instructions,
				new JumpInsnNode(GOTO, lbl),
				new IincInsnNode(0, 1),
				lbl,
				new InsnNode(RETURN));

		mn.maxLocals = 1;

		new LocalVariableCleanup(asmName, mn).run();
		SlidingWindowOptimizer.run(mn);
		checkMethod(asmName, mn);

		assertEquals(1, mn.instructions.size());
	}

	private boolean floatCmp(float f) {
		return f < .5f;
	}

	@Test
	public void testFloatCmp() {
		transformAndCheck("floatCmp");
	}

	private int sync() {
		synchronized(this) {
			return 1;
		}
	}

	@Test
	public void testSync() {
		MethodNode mn = transformAndCheck("sync");
		Utils.printMethod(mn);
	}

	private static int deadIinc2() {
		int i = 10;
		i++;
		i = 5;
		return i;
	}

	@Test
	public void testDeadIinc2() {
		MethodNode mn = transformAndCheck("deadIinc2");
		Utils.printMethod(mn);
		assertFalse(Utils.instructionStream(mn).anyMatch(insn -> insn instanceof IincInsnNode));
	}
}
