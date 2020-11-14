package dk.casa.streamliner.asm.comments;


import org.objectweb.asm.MethodVisitor;

public interface CommentVisitor {
	void visitComment(String comment);

	default void visitComment(MethodVisitor mv, String comment) {
		if(mv instanceof CommentVisitor)
			((CommentVisitor) mv).visitComment(comment);
	}
}
