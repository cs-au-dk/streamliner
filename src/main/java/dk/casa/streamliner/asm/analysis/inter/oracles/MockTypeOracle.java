package dk.casa.streamliner.asm.analysis.inter.oracles;

import dk.casa.streamliner.asm.Utils;
import dk.casa.streamliner.asm.analysis.inter.Context;
import dk.casa.streamliner.asm.analysis.inter.InterValue;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.Optional;
import java.util.Set;

/**
 *  An Oracle that uses (unsound) heuristics to answer non-trivial queries for Collection.stream type queries.
 */
public class MockTypeOracle implements TypeQueryOracle {
	@Override
	public Optional<Type> queryType(Context context, MethodInsnNode minsn, InterValue receiver) {
		Type ownerT = Type.getObjectType(minsn.owner);
		Type receiverT = receiver.type.getType();
		if(receiverT.equals(ownerT) || Utils.getAncestors(receiverT).contains(ownerT))
			ownerT = receiverT;

		if(minsn.name.equals("stream")) {
			Set<Type> ancestors = Utils.getAncestors(ownerT);
			if(!ancestors.contains(Type.getObjectType("java/util/Collection")))
				return Optional.empty();

			String res;
			switch(ownerT.getInternalName()) {
				case "java/util/ArrayList":
				case "java/util/List":
				case "java/util/Collection":
					res = "java/util/ArrayList"; break;
				case "java/util/Set":
					res = "java/util/HashSet"; break;
				case "java/util/SortedSet":
					res = "java/util/TreeSet"; break;

				case "java/util/Deque":
				case "java/util/Queue": // EnumSet
					res = "java/util/ArrayDeque"; break;

				case "java/util/EnumSet":
					res = "java/util/RegularEnumSet"; break;

				case "java/util/LinkedList":
				case "java/util/Vector":
				case "java/util/concurrent/DelayQueue":
				case "java/util/concurrent/ConcurrentLinkedDeque":
				case "java/util/concurrent/ConcurrentSkipListSet": // "BlockingQueue"
					res = ownerT.getInternalName(); break;

				case "com/google/common/collect/ImmutableList":
					res = "com/google/common/collect/RegularImmutableList"; break;

				default:
					return Optional.empty();
			}

			return Optional.of(res).map(Type::getObjectType);
		}

		return Optional.empty();
	}
}
