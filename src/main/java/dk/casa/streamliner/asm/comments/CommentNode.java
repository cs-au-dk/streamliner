package dk.casa.streamliner.asm.comments;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LabelNode;

import java.util.Map;

public class CommentNode extends AbstractInsnNode {
	private final String comment;

	public CommentNode(String comment) {
		super(-1);
		this.comment = comment;
	}

	@Override
	public int getType() {
		return LINE;
	}

	@Override
	public void accept(MethodVisitor methodVisitor) {
		if(methodVisitor instanceof CommentVisitor)
			((CommentVisitor) methodVisitor).visitComment(comment);
	}

	@Override
	public AbstractInsnNode clone(Map<LabelNode, LabelNode> map) {
		return new CommentNode(comment).cloneAnnotations(this);
	}

	@Override
	public String toString() {
		return "Comment: " + comment;
	}
}
