package dk.casa.streamliner.asm.transform;

import dk.casa.streamliner.utils.Dotable;
import dk.casa.streamliner.asm.ClassNodeCache;
import dk.casa.streamliner.asm.InlineMethod;
import dk.casa.streamliner.asm.Utils;
import dk.casa.streamliner.asm.analysis.inter.*;
import dk.casa.streamliner.asm.analysis.pointer.AbstractPointer;
import org.apache.commons.math3.util.Pair;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;

import java.util.*;
import java.util.function.Predicate;

import static org.objectweb.asm.Opcodes.*;

public class PreTransformAnalysis implements Dotable {

	private final String owner;
	private final ContextFieldPredicate generateReflectiveAccess;
	private final boolean verifyTransformable;

	private final Set<Node> roots = new HashSet<>();
	/** An edge represents an inverse constraint between two nodes A and B.
	 * If A depends on B then we create an edge from B to A such that if
	 * B is reachable (not satisfied) then so is A. */
	private final Map<Node, Set<Node>> edges = new HashMap<>();

	public PreTransformAnalysis(String owner, ContextFieldPredicate generateReflectiveAccess, boolean verifyTransformable) {
		this.owner = owner;
		this.generateReflectiveAccess = generateReflectiveAccess;
		this.verifyTransformable = verifyTransformable;
	}

	public void run(Context initialContext, Collection<Integer> escapedSet) throws AnalyzerException {
		escapedSet.stream().map(AllocNode::new).forEach(roots::add);

		stackAllocPreAnalysis(initialContext);
		// TODO: If we accidentally added initialContext to the set of roots
		//  (e.g. due to imprecision of unsafeFieldAccess) remove it again.
		roots.remove(new InlineNode(initialContext));

		// Find fixpoint
		// Roots works as visited set
		ArrayList<Node> Q = new ArrayList<>(roots);
		while(!Q.isEmpty()) {
			Node i = Q.remove(Q.size() - 1);
			i.visit();
			if(edges.containsKey(i)) {
				for(Node j : edges.get(i))
					if(roots.add(j)) {
						j.prev = i;
						Q.add(j);
					}
			}
		}

		// Signal error if inlining of stream library methods is prevented.
		Optional<Context> res = roots.stream().filter(node -> node instanceof InlineNode)
				.map(node -> ((InlineNode) node).context)
				.filter(context -> ((context.getOwner().startsWith("java/util/stream/") || context.getOwner().startsWith("dk/casa/streamliner/stream/"))
									 && context.getDepth() == 2)
						|| context.getMethod().name.equals("spliterator"))
				.findAny();
		if (res.isPresent()) {
			Context ctxt = res.get();
			//showDot("asd");
			if(verifyTransformable)
				throw new AnalyzerException(null, String.format("Inlining of %s.%s is prevented", ctxt.getOwner(), ctxt.getMethod().name));
		}
	}

	public boolean canStackAllocate(int index) {
		return !roots.contains(new AllocNode(index));
	}

	public boolean canInline(Context context) {
		return !roots.contains(new InlineNode(context));
	}

	@Override
	public String toDot(String label) {
		StringBuilder builder = new StringBuilder();
		builder.append("digraph constraints {\n");

		builder.append(String.format("label=\"%s\";\n\n", label));

		for(Node node : roots) {
			int i = node.hashCode();
			builder.append(i).append(String.format(" [label=\"%s\"%s]\n",
					node.toString().replace("\"", "\\\""),
					node.prev == null ? ", shape=rectangle" : ""));
			if(node.prev != null)
				builder.append(node.prev.hashCode()).append(" -> ").append(i).append("\n");
			/*
			for(Node j : edges.getOrDefault(node, Collections.emptySet()))
				if(j.prev != node)
					builder.append(i).append(" -> ").append(j.hashCode()).append(" [style=dashed]\n");
			 */
		}

		builder.append("}\n");

		return builder.toString();
	}

	private abstract static class Node {
		protected Node prev = null;
		public abstract void visit();
		protected void debug(String s, Object... objects) {
			if(false)
				System.err.format(s, objects);
		}
	}
	private static class AllocNode extends Node {
		private final int index;

		private AllocNode(int index) {
			this.index = index;
		}

		@Override
		public int hashCode() {
			return index;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof AllocNode && ((AllocNode) obj).index == index;
		}

		@Override
		public void visit() {
			debug("Preventing stack-allocation of %d\n", index);
		}

		@Override
		public String toString() {
			return String.format("Site %d\n(new %s)", index, InterproceduralTypePointerAnalysis.allocationTypes.getOrDefault(index, "unknown"));
		}
	}

	private static class InlineNode extends Node {
		private final Context context;

		private InlineNode(Context context) {
			this.context = context;
		}

		@Override
		public int hashCode() {
			return context.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof InlineNode && context.equals(((InlineNode) obj).context);
		}

		@Override
		public void visit() {
			debug("Preventing inlining of %s\n", context);
		}

		@Override
		public String toString() {
			return context.toString();
		}
	}

	private static class BanNode extends Node {
		private final TypeElement type;

		private BanNode(TypeElement type) {
			this.type = type;
		}

		@Override
		public int hashCode() {
			return type.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof BanNode && type.equals(((BanNode) obj).type);
		}

		@Override
		public void visit() {
			System.err.format("Banning stack allocation of type: %s\n", type);
			if(type.getType().equals(Type.getObjectType("java/lang/Object")) && !type.isPrecise())
				throw new RuntimeException("Banning Object type makes everything useless");
		}

		@Override
		public String toString() {
			return "Ban: " + type;
		}
	}

	private boolean addRoot(Node x) { // For easier debugging
		return roots.add(x);
	}
	private boolean addEdge(Node a, Node b) {
		return edges.computeIfAbsent(a, i -> new HashSet<>()).add(b);
	}
	private void addEdge(Optional<Node> a, Node b) {
		if(a.isPresent()) addEdge(a.get(), b);
		else addRoot(b);
	}
	private void addEdge(Node a, Optional<Node> b) {
		b.ifPresent(x -> addEdge(a, x));
	}

	private BanNode banStackAllocation(TypeElement type) {
		if(type.getType() == Type.VOID_TYPE)
			throw new RuntimeException("Cannot ban void type");
		BanNode node = new BanNode(type);
		if(!edges.containsKey(node)) {
			Predicate<Type> prevent = type.isPrecise() ? (typ -> typ.equals(type.getType())) :
					(typ -> Utils.getAncestors(typ).contains(type.getType()));
			for (Map.Entry<Integer, String> entry : InterproceduralTypePointerAnalysis.allocationTypes.entrySet()) {
				Type allocType = Type.getObjectType(entry.getValue().replace(".", "/"));
				if (prevent.test(allocType))
					addEdge(node, new AllocNode(entry.getKey()));
			}
		}
		return node;
	}

	/** Returns false iff. it is safe to inline this instruction into owner
	 * Ideally we should be able to take liveness information into account.
	 * TODO: Should be made more precise and take stuff like inner classes into account
	 * */
	private boolean unsafeFieldAccess(FieldInsnNode finsn, AbstractPointer ptr, InterFrame frame) throws AnalyzerException {
		if(finsn.owner.equals(owner)) return false;
		FieldNode fn = Utils.getFields(finsn.owner, f -> f.name.equals(finsn.name)).iterator().next().getSecond();
		if((fn.access & ACC_PUBLIC) == ACC_PUBLIC) {
			ClassNode cn = ClassNodeCache.get(finsn.owner);
			if((cn.access & ACC_PUBLIC) == ACC_PUBLIC)
				return false;
		}

		if(ptr != null && (finsn.getOpcode() == GETFIELD || finsn.getOpcode() == GETSTATIC)) {
			// Check if field is constant
			if(ptr.isValid() && frame.getHeap().getCell(ptr.pointsTo()).getField(InterInterpreter.getFieldName(finsn)).constant.isDefined())
				return false;

			// TODO: In this case the GETFIELD instruction is dead
			return (!finsn.owner.equals("java/util/stream/FindOps$FindOp") ||
					(!finsn.name.equals("sinkSupplier") && !finsn.name.equals("emptyValue"))) &&
					(!finsn.owner.contains("Unmodifiable") || !ptr.isValid() ||  // Collector characteristics
							InterproceduralTypePointerAnalysis.allocationTypes.containsKey(ptr.pointsTo()));
		}
		return true;
	}

	private void stackAllocPreAnalysis(Context context) throws AnalyzerException {
		InterFrame[] frames = InterproceduralTypePointerAnalysis.calls.get(context);

		AbstractInsnNode[] insns = context.getMethod().instructions.toArray();
		for(int i = 0; i < insns.length; i++) {
			AbstractInsnNode insn = insns[i];
			InterFrame frame = frames[i];
			if(frame == null || frame.isUnreachable()) continue;
			int opcode = insn.getOpcode();

			if (insn instanceof MethodInsnNode) {
				MethodInsnNode minsn = (MethodInsnNode) insn;
				List<InterValue> argumentValues = Utils.getArgumentValues(minsn, frame);
				Set<Integer> calls = InterproceduralTypePointerAnalysis.analysedCalls.get(context);
				boolean hasAnalysisResult = calls != null && calls.contains(i);

				// If this method is not accessible from owner, then this method must be inlined if we inline parent
				// Aka. if we cannot inline the new method then we cannot inline the parent
				// TODO: We can access protected methods of superclasses and package-private classes in the same package
                boolean mustInline = false;
				if(!minsn.owner.equals(owner)) {
					ClassNode declaredOwner = ClassNodeCache.get(minsn.owner);
					MethodNode mn = Utils.findMethod(minsn.owner,  minsn.name, minsn.desc)
							.map(Pair::getSecond)
							.orElseThrow(() -> new RuntimeException("Cannot find method: " + declaredOwner.name + "." + minsn.name));
					if((declaredOwner.access & ACC_PUBLIC) == 0 || (mn.access & ACC_PUBLIC) == 0)
					    mustInline = true;
				}


				// We depend on the recursive analysis to determine if arguments can be stack-allocated or not
				// If we have no analysis result, we have to assume we cannot
				if(!hasAnalysisResult) {
					// No way of inlining
					for(InterValue value : argumentValues) {
						if(value.type.maybePointer()) {
							if(value.isValid()) addRoot(new AllocNode(value.pointsTo()));
							else if(value.pointer == PointerElement.TOP) addRoot(banStackAllocation(value.type));
						}
					}

					if(mustInline) addRoot(new InlineNode(context));
				} else {
					InlineMethod itarget = InterproceduralTypePointerAnalysis.resolveCall(minsn, argumentValues, frame.getHeap(), context);
					Context newContext = context.newContext(itarget.owner, itarget.mth, i, frame.getHeap(), argumentValues);
					InlineNode newNode = new InlineNode(newContext);

					if(minsn.getOpcode() != INVOKESTATIC) {
						InterValue receiver = argumentValues.get(0);
						if (receiver.isValid()) // If method is not inlined - we cannot stack-allocate receiver (NPE)
							addEdge(newNode, new AllocNode(receiver.pointsTo()));
						else if(receiver.pointer == PointerElement.TOP)
							addEdge(newNode, banStackAllocation(receiver.type));

						// We can only inline constructors if receiver is stack-allocated
						if(Utils.isConstructor(newContext.getMethod()) && receiver.isValid())
							addEdge(new AllocNode(receiver.pointsTo()), newNode);
					}

					// Inlining relies on inlining of parent
					addEdge(new InlineNode(context), newNode);

					if(mustInline) addEdge(newNode, new InlineNode(context));

					stackAllocPreAnalysis(newContext);
				}
			} else if(insn instanceof FieldInsnNode) {
				FieldInsnNode finsn = (FieldInsnNode) insn;

				boolean get = opcode == GETFIELD;
				if(get || opcode == PUTFIELD) {
					InterValue ptrValue = frame.getStack(frame.getStackSize() - (get ? 1 : 2));

					Optional<Node> ptrNode = Optional.empty();
					if(ptrValue.pointer.maybeInteresting()) {
						if (!ptrValue.isValid()) {
							Node node = banStackAllocation(new TypeElement(false, Type.getObjectType(finsn.owner)));
							addRoot(node);
							ptrNode = Optional.of(node);
						} else ptrNode = Optional.of(new AllocNode(ptrValue.pointsTo()));
					}

					// We can only stack-allocate if this method is inlined
					addEdge(new InlineNode(context), ptrNode);

					// If we cannot access the field then we cannot inline unless the object is stack-allocated
					if(unsafeFieldAccess(finsn, ptrValue, frame) && !generateReflectiveAccess.test(context, finsn)) {
						// Test what happens if we hypothetically allow access to some fields in certain situations
						//if(!Arrays.asList("forEachRemaining", "getFence").contains(context.getMethod().name) || !finsn.owner.equals("java/util/ArrayList") ||
						//		!Arrays.asList("elementData", "modCount", "size").contains(finsn.name))
						addEdge(ptrNode, new InlineNode(context));
					}

					if(opcode == PUTFIELD) {
						InterValue value = frame.getStack(frame.getStackSize() - 1);
						if(value.type.maybePointer() && value.pointer.maybeInteresting()) {
							if (value.isValid())
								// We can only stack allocate the pointed-to value if ptrValue is stack-allocated
								addEdge(ptrNode, new AllocNode(value.pointsTo()));
							else if (value.pointer == PointerElement.TOP)
								// If ptrValue is not stack-allocated we cannot stack-allocate any object of this type
								// TODO: Pointer sets can reduce over-approximation
								addEdge(ptrNode, banStackAllocation(value.type));
						}
					}
				} else {
					// Static field -- We have some special cases
					if((finsn.owner.equals("java/util/ArrayList") && finsn.name.contains("EMPTY_ELEMENTDATA")) ||
							finsn.owner.equals("java/util/stream/StreamShape") ||
							finsn.owner.equals("java/util/stream/StreamOpFlag") ||
							finsn.owner.equals("java/util/stream/MatchOps$MatchKind") ||
							finsn.owner.startsWith("java/util/stream/FindOps$FindSink$Of") ||
							finsn.owner.equals("java/util/stream/Collectors"))
						continue;

					Integer idx = InterproceduralTypePointerAnalysis.staticAllocations.get(finsn.owner);
					InterValue value = idx == null ? null : new InterValue(new TypeElement(false, TypeElement.TOP), new PointerElement(idx));
					// Cannot inline method if field access is not safe
					if(unsafeFieldAccess(finsn, value, frame))
						addRoot(new InlineNode(context));
				}
			} else if(opcode == NEW) {
				int idx = InterproceduralTypePointerAnalysis.getAllocationIndex(context, i);
				AllocNode node = new AllocNode(idx);
				if(!frames[i+1].getHeap().containsKey(idx)) // untracked allocation
					roots.add(node);

				// If this method is not inlined, we cannot stack-allocate
				addEdge(new InlineNode(context), node);

				// If lambda is not stack-allocated, we don't want to inline the create call (we look for them in postprocessing)
				TypeInsnNode tinsn = (TypeInsnNode) insn;
				if (tinsn.desc.contains("LambdaModel$"))
					addEdge(node, new InlineNode(context));
			}
		}
	}

}
