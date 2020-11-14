package dk.casa.streamliner.asm;

import org.objectweb.asm.tree.MethodNode;

public class InlineMethod {
    public MethodNode mth;
    public String owner;

    public InlineMethod(MethodNode mth, String owner) {
        // Necessary when inlining the same function multiple times
        this.mth = new MethodNode(mth.access, mth.name, mth.desc, mth.signature, mth.exceptions.toArray(new String[0]));
        mth.accept(this.mth);

        this.owner = owner;
    }
}
