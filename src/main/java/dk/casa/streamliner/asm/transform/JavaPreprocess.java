package dk.casa.streamliner.asm.transform;

import dk.casa.streamliner.asm.ClassNodeCache;
import dk.casa.streamliner.asm.Utils;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.CheckMethodAdapter;

import java.util.*;

import static dk.casa.streamliner.utils.Utils.isJava8;
import static org.objectweb.asm.Opcodes.*;

public class JavaPreprocess {
	private static final Set<MethodNode> preprocessed = Collections.newSetFromMap(new WeakHashMap<>());
	private static final Type objectType = Type.getObjectType("java/lang/Object");

	public static void preprocess(String owner, MethodNode mn) {
		if(preprocessed.contains(mn)) return;

		AbstractInsnNode[] insns = mn.instructions.toArray();
		String combinedName = owner + "." + mn.name;
		switch (combinedName) {
			case "java/util/stream/IntPipeline$7$1.accept":
			case "java/util/stream/ReferencePipeline$7$1.accept":
				// Throwable addSuppressed is troublesome - we have to find a solution for this
				// We loop through the instructions to support both openjdk and oracle
				for(AbstractInsnNode insn : insns) {
					if(!(insn instanceof MethodInsnNode)) continue;
					MethodInsnNode minsn = (MethodInsnNode) insn;
					if(minsn.owner.equals("java/lang/Throwable") && minsn.name.equals("addSuppressed"))
						mn.instructions.set(insn, new InsnNode(POP2)); // Pop both arguments
				}
				break;

			case "java/lang/Long.valueOf":
			case "java/lang/Integer.valueOf":
				if(mn.desc.equals(Type.getMethodDescriptor(Type.getObjectType("java/lang/Integer"), Type.INT_TYPE))) {
					// Assume that we miss the 'small ints' cache
					// This is *almost* sound except that pointer equality checks between integers can fail for small integers
					// (but it is bad practice to rely on this anyway)
					mn.instructions.insertBefore(insns[2], new JumpInsnNode(GOTO, (LabelNode) insns[16]));
				} else if(mn.desc.equals(Type.getMethodDescriptor(Type.getObjectType("java/lang/Long"), Type.LONG_TYPE))) {
					mn.instructions.insertBefore(insns[2], new JumpInsnNode(GOTO, (LabelNode) insns[isJava8()? 19 : 23]));
				}
				break;

			case "java/util/List.of":
				if(mn.desc.equals("([Ljava/lang/Object;)Ljava/util/List;")) {
					// Always assume ListN
					mn.instructions.insertBefore(insns[4], new InsnNode(POP));
					mn.instructions.set(insns[4], new JumpInsnNode(GOTO, ((TableSwitchInsnNode) insns[4]).dflt));
				}
				break;

			case "java/util/stream/Collector.of":
				// Always assume default characteristics (unsoundly)
				JumpInsnNode jump = (JumpInsnNode) insns[33];
				mn.instructions.insertBefore(jump, new InsnNode(POP));
				mn.instructions.set(jump, new JumpInsnNode(GOTO, jump.label));
				break;

			case "java/util/RegularEnumSet.contains":
				// We need better handling of Class objects to handle this method precisely.
				// This modification unsoundly assumes that the enum type is correct
				((JumpInsnNode) insns[3]).label = (LabelNode) insns[29];
				break;

			case "java/util/stream/IntStream.range":
			case "java/util/stream/IntStream.rangeClosed":
				// Assume that the non-empty branch is taken (unsound but has equivalent semantics)
                mn.instructions.insertBefore(insns[2], new JumpInsnNode(GOTO, (LabelNode) insns[9]));
				break;
		}

		// It is annoying when java/lang/Object.getClass is used as a null-check since it is a native method
		// We transform it into an Objects.requireNonNull instead
		for(AbstractInsnNode insn : mn.instructions) {
			if(insn instanceof MethodInsnNode) {
				MethodInsnNode minsn = (MethodInsnNode) insn;
				if(minsn.getOpcode() == INVOKEVIRTUAL
				    && minsn.owner.equals("java/lang/Object")
					&& minsn.name.equals("getClass")
					&& minsn.desc.equals(Type.getMethodDescriptor(Type.getObjectType("java/lang/Class")))) {

					AbstractInsnNode next = minsn.getNext();
					if(next != null && next.getOpcode() == POP) {
						MethodInsnNode newCall = new MethodInsnNode(INVOKESTATIC, "java/util/Objects", "requireNonNull",
								Type.getMethodDescriptor(objectType, objectType), false);
						mn.instructions.set(insn, newCall);
					}
				}
			} else if(insn.getOpcode() == GETSTATIC && ((FieldInsnNode) insn).name.endsWith("$assertionsDisabled")) {
				// Assume assertions are always disabled
				mn.instructions.set(insn, new InsnNode(ICONST_1));
			}
		}

		preprocessed.add(mn);
	}


	// TODO: Refactor common parts of methods?
	// Map from pipeline depth to generated method
	private static final Map<Integer, MethodNode> wrapSinkModels = new HashMap<>();

	public static MethodNode getWrapSinkModel(int depth) {
		if(wrapSinkModels.containsKey(depth))
			return wrapSinkModels.get(depth);

		String abstractPipelineName = "java/util/stream/AbstractPipeline";
		Type abstractPipeline = Type.getObjectType(abstractPipelineName);
		Type sink = Type.getObjectType("java/util/stream/Sink");
		Type object = Type.getObjectType("java/lang/Object");

		MethodNode originalMethod = ClassNodeCache.get(abstractPipelineName)
				.methods.stream().filter(mn -> mn.name.equals("wrapSink")).findAny().get();

		LabelNode startLabel = new LabelNode(), endLabel = new LabelNode();
		MethodNode mn = new MethodNode(originalMethod.access, "wrapSink", originalMethod.desc,
									   originalMethod.signature, originalMethod.exceptions.toArray(new String[0]));
		mn.localVariables.addAll(Arrays.asList(
				new LocalVariableNode("this", abstractPipeline.getDescriptor(), null, startLabel, endLabel, 0),
				new LocalVariableNode("sink", sink.getDescriptor(), null, startLabel, endLabel, 1),
				new LocalVariableNode("stage", abstractPipeline.getDescriptor(), null, startLabel, endLabel, 2)
		));

		Utils.addInstructions(mn.instructions,
				startLabel,
				new VarInsnNode(ALOAD, 1),
				new MethodInsnNode(INVOKESTATIC, "java/util/Objects", "requireNonNull", Type.getMethodDescriptor(object, object), false),
				new InsnNode(POP),
				new VarInsnNode(ALOAD, 0),
				new VarInsnNode(ASTORE, 2));

		// Manual loop unrolling
		for (int i = 0; i < depth; i++) {
			Utils.addInstructions(mn.instructions,
					new VarInsnNode(ALOAD, 2),
					new VarInsnNode(ALOAD, 2),
					new FieldInsnNode(GETFIELD, abstractPipelineName, "previousStage", abstractPipeline.getDescriptor()),
					new FieldInsnNode(GETFIELD, abstractPipelineName, "combinedFlags", Type.INT_TYPE.getDescriptor()),
					new VarInsnNode(ALOAD, 1),
					new MethodInsnNode(INVOKEVIRTUAL, abstractPipelineName, "opWrapSink", Type.getMethodDescriptor(sink, Type.INT_TYPE, sink), false),
					new VarInsnNode(ASTORE, 1),
					new VarInsnNode(ALOAD, 2),
					new FieldInsnNode(GETFIELD, abstractPipelineName, "previousStage", abstractPipeline.getDescriptor()),
					new VarInsnNode(ASTORE, 2));
		}

		Utils.addInstructions(mn.instructions,
				endLabel,
				new VarInsnNode(ALOAD, 1),
				new InsnNode(ARETURN));

		mn.maxLocals = 3;
		mn.maxStack = 3;

		if(false) {
			System.out.println("Generated model for opWrapSink of depth: " + depth);
			Utils.printMethod(mn);
		}

		mn.accept(new CheckMethodAdapter(mn.access, mn.name, mn.desc, null, new HashMap<>()));



		wrapSinkModels.put(depth, mn);
		return mn;
	}

	private static final Map<Integer, MethodNode> copyIntoWithCancelModels = new HashMap<>();

	public static MethodNode getCopyIntoWithCancelModel(int depth) {
		if(copyIntoWithCancelModels.containsKey(depth))
			return copyIntoWithCancelModels.get(depth);

		String abstractPipelineName = "java/util/stream/AbstractPipeline";
		Type abstractPipeline = Type.getObjectType(abstractPipelineName);
		Type spliterator = Type.getObjectType("java/util/Spliterator");
		Type sink = Type.getObjectType("java/util/stream/Sink");

		MethodNode originalMethod = ClassNodeCache.get(abstractPipelineName)
				.methods.stream().filter(mn -> mn.name.equals("copyIntoWithCancel")).findAny().get();

		LabelNode startLabel = new LabelNode(), endLabel = new LabelNode();
		MethodNode mn = new MethodNode(originalMethod.access, "copyIntoWithCancel", originalMethod.desc,
				originalMethod.signature, originalMethod.exceptions.toArray(new String[0]));
		mn.localVariables.addAll(Arrays.asList(
				new LocalVariableNode("this", abstractPipeline.getDescriptor(), null, startLabel, endLabel, 0),
				new LocalVariableNode("sink", sink.getDescriptor(), null, startLabel, endLabel, 1),
				new LocalVariableNode("spliterator", spliterator.getDescriptor(), null, startLabel, endLabel, 2),
				new LocalVariableNode("stage", abstractPipeline.getDescriptor(), null, startLabel, endLabel, 3),
				new LocalVariableNode("cancelled", Type.BOOLEAN_TYPE.getDescriptor(), null, startLabel, endLabel, 4)
		));

		Utils.addInstructions(mn.instructions,
				startLabel,
				new VarInsnNode(ALOAD, 0),
				new VarInsnNode(ASTORE, 3));

		// Manual loop unrolling
		mn.instructions.add(new VarInsnNode(ALOAD, 3));
		for (int i = 0; i < depth; i++)
			mn.instructions.add(new FieldInsnNode(GETFIELD, abstractPipelineName, "previousStage", abstractPipeline.getDescriptor()));
		mn.instructions.add(new VarInsnNode(ASTORE, 3));

		String forEachWithCancelDesc = Type.getMethodDescriptor(isJava8()? Type.VOID_TYPE : Type.BOOLEAN_TYPE, spliterator, sink);

		Utils.addInstructions(mn.instructions,
				new VarInsnNode(ALOAD, 1),
				new VarInsnNode(ALOAD, 2),
				new MethodInsnNode(INVOKEINTERFACE, spliterator.getInternalName(), "getExactSizeIfKnown",
						Type.getMethodDescriptor(Type.LONG_TYPE), true),
				new MethodInsnNode(INVOKEINTERFACE, sink.getInternalName(), "begin",
						Type.getMethodDescriptor(Type.VOID_TYPE, Type.LONG_TYPE), true),
				new VarInsnNode(ALOAD, 3),
				new VarInsnNode(ALOAD, 2),
				new VarInsnNode(ALOAD, 1),
				new MethodInsnNode(INVOKEVIRTUAL, abstractPipelineName, "forEachWithCancel", forEachWithCancelDesc));

		if(isJava8())
			Utils.addInstructions(mn.instructions,
					new VarInsnNode(ALOAD, 1),
					new MethodInsnNode(INVOKEINTERFACE, sink.getInternalName(), "end", "()V", true),
					endLabel,
					new InsnNode(RETURN));
		else
			Utils.addInstructions(mn.instructions,
					new VarInsnNode(ISTORE, 4),
					new VarInsnNode(ALOAD, 1),
					new MethodInsnNode(INVOKEINTERFACE, sink.getInternalName(), "end", "()V", true),
					endLabel,
					new VarInsnNode(ILOAD, 4),
					new InsnNode(IRETURN));

		mn.maxLocals = 5;
		mn.maxStack = 3;

		if(false) {
			System.out.println("Generated model for copyIntoWithCancel of depth: " + depth);
			Utils.printMethod(mn);
		}

		mn.accept(new CheckMethodAdapter(mn.access, mn.name, mn.desc, null, new HashMap<>()));

		copyIntoWithCancelModels.put(depth, mn);
		return mn;
	}
}
