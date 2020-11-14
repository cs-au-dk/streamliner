package dk.casa.streamliner.test.asm;

import dk.casa.streamliner.asm.Utils;
import dk.casa.streamliner.asm.transform.SlidingWindowOptimizer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSlidingWindowOptimizer extends TestASM implements Opcodes {

	private static MethodNode test(int maxStack, int maxLocals, int expectedSize, AbstractInsnNode... insns) {
		return test(maxStack, maxLocals, expectedSize, Type.getMethodDescriptor(Type.VOID_TYPE), insns);
	}

	private static MethodNode test(int maxStack, int maxLocals, int expectedSize, String desc, AbstractInsnNode... insns) {
		MethodNode mn = new MethodNode(ACC_PUBLIC | ACC_STATIC, "test", desc, null, null);
		mn.maxLocals = maxLocals;
		mn.maxStack = maxStack;

		Utils.addInstructions(mn.instructions, insns);
		mn.instructions.add(new InsnNode(RETURN));

		SlidingWindowOptimizer.run(mn);
		checkMethod("dummy", mn);

		assertEquals(expectedSize, mn.instructions.size(), () -> { Utils.printMethod(mn); return ""; });
		return mn;
	}

	@Test
	public void testSimple() {
		test(1, 0, 1,
				new InsnNode(ICONST_0),
				new InsnNode(POP));
	}

	@Test
	public void moveUp1() {
		test(3, 1, 5,
				new InsnNode(ACONST_NULL),
				new InsnNode(ICONST_0),
				new InsnNode(ICONST_1),
				new InsnNode(IADD),
				new VarInsnNode(ISTORE, 0),
				new InsnNode(POP));
	}

	@Test
	public void moveUp2() {
		test(2, 2, 4,
				Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType("[I")),
				new InsnNode(ACONST_NULL),
				new VarInsnNode(ALOAD, 0),
				new InsnNode(ARRAYLENGTH),
				new VarInsnNode(ISTORE, 1),
				new InsnNode(POP));
	}

	@Test
	public void doubleMove() {
		test(4, 1, 5,
				new InsnNode(ACONST_NULL),
				new InsnNode(ACONST_NULL),
				new InsnNode(ICONST_0),
				new InsnNode(ICONST_1),
				new InsnNode(IADD),
				new VarInsnNode(ISTORE, 0),
				new InsnNode(POP),
				new InsnNode(POP));
	}

	@Test
	public void hardDup() {
		test(2, 1, 3,
				new InsnNode(ICONST_0),
				new InsnNode(DUP),
				new VarInsnNode(ISTORE, 0),
				new InsnNode(POP));
	}

	@Test
	public void branchNull() {
		LabelNode thn = new LabelNode();
		test(2, 2, 8,
				new InsnNode(ACONST_NULL),
				new IntInsnNode(SIPUSH, 78),
				new JumpInsnNode(IFNE, thn),
				new InsnNode(POP),
				new InsnNode(RETURN),
				thn,
				new VarInsnNode(ASTORE, 1));
	}

	@Test
	public void popOverMethod() {
		test(2, 1, 4,
				new InsnNode(ACONST_NULL),
				new InsnNode(ICONST_4),
				new MethodInsnNode(INVOKESTATIC, "dummy", "dostuff", Type.getMethodDescriptor(Type.BOOLEAN_TYPE, Type.INT_TYPE), false),
				new InsnNode(POP),
				new InsnNode(POP));
	}

	@Test
	public void swap1() {
		MethodNode mn = test(2, 0, 2,
				new InsnNode(ICONST_0),
				new InsnNode(ICONST_1),
				new InsnNode(SWAP),
				new InsnNode(POP));

		assertEquals(ICONST_1, mn.instructions.getFirst().getOpcode());
	}

	@Test
	public void swap2() {
		MethodNode mn = test(2, 1, 3,
				new InsnNode(ICONST_0),
				new InsnNode(ICONST_1),
				new InsnNode(SWAP),
				new VarInsnNode(ISTORE, 0),
				new InsnNode(POP));

		// Equal to ICONST_0, ISTORE 0
		assertEquals(ICONST_0, mn.instructions.getFirst().getOpcode());
		assertEquals(ISTORE, mn.instructions.getFirst().getNext().getOpcode());
	}

	@Test
	public void swap3() {
		MethodNode mn = test(3, 2, 5,
				new InsnNode(ICONST_0),
				new InsnNode(ICONST_1),
				new InsnNode(ICONST_2),
				new InsnNode(SWAP),
				new VarInsnNode(ISTORE, 0),
				new VarInsnNode(ISTORE, 1),
				new InsnNode(POP));

		assertEquals(ICONST_2, mn.instructions.getFirst().getOpcode());
		assertEquals(ICONST_1, mn.instructions.getFirst().getNext().getOpcode());
	}
}
