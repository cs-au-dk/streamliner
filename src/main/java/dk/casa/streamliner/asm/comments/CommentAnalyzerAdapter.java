package dk.casa.streamliner.asm.comments;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.AnalyzerAdapter;

import static org.objectweb.asm.Opcodes.ASM7;

public class CommentAnalyzerAdapter extends AnalyzerAdapter implements CommentVisitor {
	public CommentAnalyzerAdapter(String owner, int access, String name, String descriptor, MethodVisitor methodVisitor) {
		this(ASM7, owner, access, name, descriptor, methodVisitor);
	}

	protected CommentAnalyzerAdapter(int api, String owner, int access, String name, String descriptor, MethodVisitor methodVisitor) {
		super(api, owner, access, name, descriptor, methodVisitor);
	}

	@Override
	public void visitComment(String comment) {
		visitComment(mv, comment);
	}
}
