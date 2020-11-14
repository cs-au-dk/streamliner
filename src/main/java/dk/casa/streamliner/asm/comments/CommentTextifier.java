package dk.casa.streamliner.asm.comments;


import org.objectweb.asm.util.Textifier;

import static org.objectweb.asm.Opcodes.ASM7;

public class CommentTextifier extends Textifier {
	public CommentTextifier() {
		super(ASM7);
	}

	public void visitComment(String comment) {
		stringBuilder.setLength(0);
		stringBuilder.append(tab).append("//").append(comment).append('\n');
		text.add(stringBuilder.toString());
	}
}
