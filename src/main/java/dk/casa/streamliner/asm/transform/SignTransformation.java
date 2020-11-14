package dk.casa.streamliner.asm.transform;

import dk.casa.streamliner.asm.analysis.FrameUtils;
import dk.casa.streamliner.asm.analysis.InstructionStackEffect;
import dk.casa.streamliner.asm.analysis.sign.Sign;
import dk.casa.streamliner.asm.analysis.sign.SignInterpreter;
import dk.casa.streamliner.asm.analysis.sign.SignValue;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;

public class SignTransformation {
	private final String owner;
	private final MethodNode mn;

	public SignTransformation(String owner, MethodNode mn) {
		this.owner = owner;
		this.mn = mn;
	}

	public void run() throws AnalyzerException {
		Analyzer<SignValue> analyzer = new Analyzer<>(new SignInterpreter());
		int originalLength = mn.instructions.size();
		Frame<SignValue>[] frames = analyzer.analyze(owner, mn);
		AbstractInsnNode[] insns = mn.instructions.toArray();
		boolean modified = false;
		for (int i = 0; i < insns.length; i++) {
			Frame<SignValue> frame = frames[i];
			if(frame == null || !(insns[i] instanceof JumpInsnNode)) continue;
			JumpInsnNode jinsn = (JumpInsnNode) insns[i];

			boolean doJump;
			int opc = insns[i].getOpcode();
			if(opc == Opcodes.IF_ICMPLE || opc == Opcodes.IF_ICMPGE) {
				Sign v1 = frame.getStack(frame.getStackSize()-2).value, v2 = frame.getStack(frame.getStackSize() - 1).value;
				if(v1 == Sign.TOP || v2 == Sign.TOP || (v1 == Sign.POS && v2 == Sign.POS)) continue;

				// At least one is zero
				boolean le = opc == Opcodes.IF_ICMPLE;
				//    0 <= +                        + >= 0
				if((v1 == Sign.ZERO && le) || (v2 == Sign.ZERO && !le))
					doJump = true;
				else continue;
			} else if(opc == Opcodes.IFLT || opc == Opcodes.IFGE) {
				Sign s = FrameUtils.getStackTop(frame).value;
				if (s == Sign.TOP) continue;
				doJump = opc == Opcodes.IFGE;
			} else if(opc == Opcodes.IFEQ || opc == Opcodes.IFNE) {
				Sign s = FrameUtils.getStackTop(frame).value;
				if(s != Sign.ZERO) continue;
				doJump = opc == Opcodes.IFEQ;
			} else
				continue;

			int consumed = InstructionStackEffect.computeConsProd(jinsn, frame).consumed;
			if(consumed != 1 && consumed != 2) throw new RuntimeException("Unexpected");
			mn.instructions.insertBefore(jinsn, new InsnNode(consumed == 2? Opcodes.POP2 : Opcodes.POP));
			if(doJump)
				mn.instructions.set(jinsn, new JumpInsnNode(Opcodes.GOTO, jinsn.label));
			else
				mn.instructions.remove(jinsn);

			modified = true;
		}

		if(modified) {
			// Run analysis for reachability and remove dead instructions
			frames = analyzer.analyze(owner, mn);
			insns = mn.instructions.toArray();
			for (int i = 0; i < insns.length; i++) {
				if (frames[i] == null && insns[i].getOpcode() >= 0)
					mn.instructions.remove(insns[i]);
			}
		}

		System.err.format("SignTransformation removed %s instructions.\n", originalLength - mn.instructions.size());
	}
}
