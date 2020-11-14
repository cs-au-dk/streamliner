package dk.casa.streamliner.asm.comments;


import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.util.TraceMethodVisitor;

import java.io.PrintWriter;
import java.util.List;

import static org.objectweb.asm.Opcodes.ASM7;

public class TraceMethodWithCommentVisitor extends MethodVisitor implements CommentVisitor {
	private final CommentTextifier ctext = new CommentTextifier();

	public TraceMethodWithCommentVisitor(MethodVisitor mv) {
		super(ASM7, null);
		this.mv = new TraceMethodVisitor(mv, ctext);
	}

	public TraceMethodWithCommentVisitor() {
		this(null);
	}

	@Override
	public void visitComment(String comment) {
		ctext.visitComment(comment);
	}

	public void print(PrintWriter printWriter) {
		ctext.print(printWriter);
		printWriter.flush();
	}

	public List<Object> getText() {
		return ctext.getText();
	}
}
