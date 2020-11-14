package dk.casa.streamliner.asm.analysis;

import org.objectweb.asm.tree.MethodInsnNode;

public class MethodIdentifier {
	public final String owner, name, desc;

	public MethodIdentifier(String owner, String name, String desc) {
		this.owner = owner;
		this.name = name;
		this.desc = desc;
	}

	public static MethodIdentifier fromMethodInsnNode(MethodInsnNode minsn) {
		return new MethodIdentifier(minsn.owner, minsn.name, minsn.desc);
	}

	@Override
	public boolean equals(Object obj) {
		if(!(obj instanceof MethodIdentifier)) return false;
		MethodIdentifier mth = (MethodIdentifier) obj;
		return owner.equals(mth.owner) &&
				name.equals(mth.name) &&
				desc.equals(mth.desc);
	}

	@Override
	public int hashCode() {
		return owner.hashCode() + 7 * name.hashCode() + 17 * desc.hashCode();
	}
}
