package dk.casa.streamliner.test.asm;

import dk.casa.streamliner.asm.Utils;
import dk.casa.streamliner.asm.analysis.LivenessAnalysis;
import dk.casa.streamliner.asm.analysis.alias.MustEqualsAnalyzer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

public class TestLivenessAnalysis {
	private LivenessAnalysis analysis;

	private HashSet<Integer>[] doAnalysis(MethodNode mn) throws AnalyzerException {
		MustEqualsAnalyzer<BasicValue> analyzer = new MustEqualsAnalyzer<>(new BasicInterpreter());
		Frame<BasicValue>[] frames = analyzer.analyze("java/lang/Object", mn);
		analysis = new LivenessAnalysis(analyzer.getCFG());
		return analysis.analyze(frames);
	}

	private void printResult(HashSet<Integer>[] res, MethodNode mn) {
		for (int i = 0; i < mn.instructions.size(); i++) {
			System.out.println(i + ": " + Utils.toString(mn.instructions.get(i)) + " Live in: " + res[i]);
		}
	}

	private MethodNode createMethod(int access, String desc, int maxStack) {
		MethodNode mn = new MethodNode(access, "", desc, null, null);
		mn.maxStack = maxStack;
		mn.tryCatchBlocks = Collections.emptyList();
		return mn;
	}

	@Test
	public void testIconstReturn() throws AnalyzerException {
		MethodNode mn = createMethod(ACC_STATIC, Type.getMethodDescriptor(Type.INT_TYPE), 1);
		Utils.addInstructions(mn.instructions, new InsnNode(ICONST_0), new InsnNode(IRETURN));
		HashSet<Integer>[] res = doAnalysis(mn);
		printResult(res, mn);

		assertEquals(res[1], Collections.singleton(0));
	}

	@Test
	public void testIAdd() throws AnalyzerException {
		MethodNode mn = createMethod(ACC_STATIC, Type.getMethodDescriptor(Type.INT_TYPE), 2);
		Utils.addInstructions(mn.instructions,
				new InsnNode(ICONST_1), new InsnNode(ICONST_2),
				new InsnNode(IADD), new InsnNode(IRETURN));
		HashSet<Integer>[] res = doAnalysis(mn);
		printResult(res, mn);

		assertEquals(res[2], new HashSet<>(Arrays.asList(0, 1)));
		assertEquals(res[3], Collections.singleton(0));
	}

	@Test
	public void testLoadStore() throws AnalyzerException {
		MethodNode mn = createMethod(ACC_STATIC, Type.getMethodDescriptor(Type.INT_TYPE), 1);
		mn.maxLocals = 1;
		Utils.addInstructions(mn.instructions, new IntInsnNode(BIPUSH, 8),
				new VarInsnNode(ISTORE, 0), new VarInsnNode(ILOAD, 0),
				new InsnNode(IRETURN));
		HashSet<Integer>[] res = doAnalysis(mn);
		printResult(res, mn);

		assertEquals(res[1], Collections.singleton(1));
		assertEquals(res[2], Collections.singleton(0));
		assertEquals(res[3], Collections.singleton(1));
	}

	@Test
	public void testIAddPop() throws AnalyzerException {
		MethodNode mn = createMethod(ACC_STATIC, Type.getMethodDescriptor(Type.VOID_TYPE), 2);
		Utils.addInstructions(mn.instructions,
				new InsnNode(ICONST_1), new InsnNode(ICONST_2),
				new InsnNode(IADD), new InsnNode(POP),
				new InsnNode(RETURN));
		HashSet<Integer>[] res = doAnalysis(mn);
		printResult(res, mn);

		assertEquals(res[2], new HashSet<>(), "Since the result of ADD is popped both stack values should be dead.");
	}

	@Test
	public void testLocalStaysLive() throws AnalyzerException {
		MethodNode mn = createMethod(ACC_STATIC, Type.getMethodDescriptor(Type.INT_TYPE), 2);
		mn.maxLocals = 1;
		Utils.addInstructions(mn.instructions,
				new InsnNode(ICONST_3), new VarInsnNode(ISTORE, 0),
				new InsnNode(ICONST_1), new InsnNode(ICONST_2),
				new InsnNode(IADD), new InsnNode(POP),
				new VarInsnNode(ILOAD, 0), new InsnNode(IRETURN));
		HashSet<Integer>[] res = doAnalysis(mn);
		printResult(res, mn);

		assertEquals(res[6], Collections.singleton(0), "Only the local is live");
		assertFalse(analysis.isDeadStore(1, 0));
	}

	@Test
	public void testStackStaysLive() throws AnalyzerException {
		MethodNode mn = createMethod(ACC_STATIC, Type.getMethodDescriptor(Type.INT_TYPE), 3);
		mn.maxLocals = 2;
		Utils.addInstructions(mn.instructions,
				new InsnNode(ICONST_3),
				new InsnNode(ICONST_2), new VarInsnNode(ISTORE, 0),
				new VarInsnNode(ILOAD, 0), new InsnNode(DUP), new InsnNode(POP2),
				new InsnNode(IRETURN));
		HashSet<Integer>[] res = doAnalysis(mn);
		printResult(res, mn);

		assertEquals(res[6], Collections.singleton(2), "Only the stack slot is live");
		assertTrue(analysis.isDeadStore(2, 0));
	}

	@Test
	public void testLoop() throws AnalyzerException {
		MethodNode mn = createMethod(ACC_STATIC, Type.getMethodDescriptor(Type.VOID_TYPE), 2);
		mn.maxLocals = 1;
		LabelNode loop = new LabelNode();
		Utils.addInstructions(mn.instructions,
				new InsnNode(ICONST_0), new VarInsnNode(ISTORE, 0),
				loop,
				new VarInsnNode(ILOAD, 0), new InsnNode(ICONST_1),
				new InsnNode(IADD), new InsnNode(DUP), new VarInsnNode(ISTORE, 0),
				new JumpInsnNode(IFGT, loop),
				new InsnNode(RETURN));

		HashSet<Integer>[] res = doAnalysis(mn);
		printResult(res, mn);
	}
}
