package dk.casa.streamliner.asm.transform;

import dk.casa.streamliner.asm.ClassNodeCache;
import dk.casa.streamliner.asm.Utils;
import org.apache.commons.math3.util.Pair;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static java.lang.invoke.LambdaMetafactory.*;
import static org.objectweb.asm.Opcodes.*;

public class LambdaPreprocessor {
	private static int freshCounter = 0;
	private final MethodNode method;

	// Used to replace non-inlined lambdas with their original InvokeDynamic instruction
	// TODO: WeakHashMap with strings as keys is not ideal
	public static final Map<String, InvokeDynamicInsnNode> models = new WeakHashMap<>();

	public static final boolean storeClasses = false;
	public static final List<Pair<String, byte[]>> classes = new ArrayList<>();

	public LambdaPreprocessor(MethodNode mn) {
		this.method = mn;
	}

	public void preprocess() {
		Stream.of(method.instructions.toArray())
				.filter(LambdaPreprocessor::isLambdaInvoke)
				.forEachOrdered(insn -> handleInvokeDynamic((InvokeDynamicInsnNode) insn));
	}

	/** Replaces LambdaModel.create calls with their original invokedynamic */
	public void postprocess() {
		for(AbstractInsnNode insn : method.instructions) {
			if (!(insn instanceof MethodInsnNode)) continue;
			MethodInsnNode minsn = (MethodInsnNode) insn;
			if (minsn.name.equals("create") && models.containsKey(minsn.owner) && !minsn.itf)
				method.instructions.set(minsn, models.get(minsn.owner).clone(null));
		}
	}

	private static boolean isLambdaInvoke(AbstractInsnNode insn) {
		if(!(insn instanceof InvokeDynamicInsnNode)) return false;
		Handle bmHandle = ((InvokeDynamicInsnNode) insn).bsm;
		return bmHandle.getOwner().equals("java/lang/invoke/LambdaMetafactory")
				&& bmHandle.getName().toLowerCase().endsWith("metafactory");
	}

	private void handleInvokeDynamic(InvokeDynamicInsnNode insn) {
		String name = insn.name;
		String descriptor = insn.desc;
		Object[] bmArguments = insn.bsmArgs;

		if(false)
			System.out.println(Utils.toString(insn));

		// Descriptor refers to the constructor of the object we want to create
		Type constructorType = Type.getType(descriptor);
		Type interfaceType = constructorType.getReturnType();
		Type interfaceMethodType = (Type) bmArguments[0];
		Handle target = (Handle) bmArguments[1];
		Type instantiatedMethodType = (Type) bmArguments[2];
		int flags = bmArguments.length > 3? (int)bmArguments[3] : 0;
		String targetOwner = target.getOwner();

		String modelOwner = targetOwner + "$";
		if(modelOwner.startsWith("java"))
			modelOwner = "";

		// We generate a new class that models what InvokeDynamic returns at link-time and replace the call
		ClassNode cn = new ClassNode();
		cn.access = ACC_PUBLIC | ACC_SYNTHETIC | ACC_SUPER;
		cn.outerClass = targetOwner;
		cn.nestHostClass = targetOwner;
		cn.name = String.format("%sLambdaModel$%s", modelOwner, freshCounter++);
		cn.superName = "java/lang/Object";
		cn.interfaces.add(interfaceType.getInternalName());

		ClassNode targetClass = ClassNodeCache.get(targetOwner);
		cn.version = targetClass.version;

		if((flags & FLAG_SERIALIZABLE) != 0) // TODO: Quite hacky, as we do not actually support serialization
			cn.interfaces.add("java/io/Serializable");

		if((flags & FLAG_MARKERS) != 0) throw new RuntimeException("Not implemented");
		if((flags & FLAG_BRIDGES) != 0) {
			int bridges = (int)bmArguments[4];
			if(bridges != 0)
				throw new RuntimeException("Not implemented");
		}

		/* We add the model as a nest member of the target such that it can access the target method */
		if(storeClasses) {
			targetClass.innerClasses.add(new InnerClassNode(cn.name, targetOwner, cn.name.substring(cn.name.lastIndexOf("/") + 1), cn.access));
			if (targetClass.nestMembers == null) targetClass.nestMembers = new ArrayList<>();
			targetClass.nestMembers.add(cn.name);
		}

		String modelTypeDescriptor = Type.getObjectType(cn.name).getDescriptor();

		// The argument types of the target might not match the field- and argumentTypes for the lambda interface
		List<Type> targetArgumentTypes = new ArrayList<>(Arrays.asList(Type.getArgumentTypes(target.getDesc())));
		Type[] instantiatedArgumentTypes = instantiatedMethodType.getArgumentTypes();
		if(target.getTag() != H_INVOKESTATIC)
			targetArgumentTypes.add(0, Type.getObjectType(targetOwner));

		Type[] fieldTypes = constructorType.getArgumentTypes();
		MethodNode constructor = new MethodNode(ACC_PUBLIC, "<init>", Type.getMethodDescriptor(Type.VOID_TYPE, fieldTypes), null, null);
		LabelNode constructorStart = new LabelNode(), constructorEnd = new LabelNode(),
				interfacemStart = new LabelNode(), interfacemEnd = new LabelNode();

		constructor.localVariables.add(new LocalVariableNode("this", modelTypeDescriptor, null, constructorStart, constructorEnd, 0));
		constructor.maxLocals = 1;
		Utils.addInstructions(constructor.instructions,
				constructorStart,
				new VarInsnNode(ALOAD, 0),
				new MethodInsnNode(INVOKESPECIAL, "java/lang/Object", "<init>", Type.getMethodDescriptor(Type.VOID_TYPE)));

		MethodNode interfacem = new MethodNode(ACC_PUBLIC, name, interfaceMethodType.getDescriptor(), null, null); // TODO: Exceptions
		interfacem.instructions.add(interfacemStart);
		interfacem.localVariables.add(new LocalVariableNode("this", modelTypeDescriptor, null, interfacemStart, interfacemEnd, 0));
		interfacem.maxLocals = 1;

		int targetArgStart = fieldTypes.length;
		if(target.getTag() == H_NEWINVOKESPECIAL) { // special handling for Object::new lambdas
			// Add NEW instruction before loading constructor arguments
			Utils.addInstructions(interfacem.instructions,
					new TypeInsnNode(NEW, targetOwner),
					new InsnNode(DUP));
			interfacem.maxStack += 2;
			targetArgStart += 1;
		}

		int maxFieldSize = 0;
		for(int i = 0, local = 1; i < fieldTypes.length; i++) {
			Type ft = fieldTypes[i];
			FieldNode fn = new FieldNode(ACC_PRIVATE | ACC_FINAL, String.format("field$%s", freshCounter++), ft.getDescriptor(), null, null);
			cn.fields.add(fn);

			Utils.addInstructions(constructor.instructions,
					new VarInsnNode(ALOAD, 0),
					new VarInsnNode(ft.getOpcode(ILOAD), local),
					new FieldInsnNode(PUTFIELD, cn.name, fn.name, fn.desc));

			constructor.localVariables.add(new LocalVariableNode(fn.name, fn.desc, null, constructorStart, constructorEnd, local));
			constructor.maxLocals += ft.getSize();

			Utils.addInstructions(interfacem.instructions,
					new VarInsnNode(ALOAD, 0),
					new FieldInsnNode(GETFIELD, cn.name, fn.name, fn.desc));

			interfacem.maxStack += ft.getSize();
			maxFieldSize = Math.max(maxFieldSize, ft.getSize());
			local += ft.getSize();
		}

		Utils.addInstructions(constructor.instructions,
				new InsnNode(RETURN),
				constructorEnd);
		constructor.maxStack = 1 + maxFieldSize;

		cn.methods.add(constructor);

		Type[] argumentTypes = interfaceMethodType.getArgumentTypes();
		for(int i = 0, local = 1; i < argumentTypes.length; i++) {
			Type at = argumentTypes[i];
			interfacem.instructions.add(new VarInsnNode(at.getOpcode(ILOAD), local));

			local += at.getSize();
			interfacem.maxLocals += at.getSize();
			interfacem.maxStack += convertType(interfacem.instructions, at, targetArgumentTypes.get(targetArgStart + i),
												instantiatedArgumentTypes[i]);
		}

		int invokeOpcode;
		switch (target.getTag()) {
			case H_NEWINVOKESPECIAL: /* for constructors */
			case H_INVOKESPECIAL:
				invokeOpcode = INVOKESPECIAL;
				break;
			case H_INVOKESTATIC:
				invokeOpcode = INVOKESTATIC;
				break;
			case H_INVOKEVIRTUAL:
				invokeOpcode = INVOKEVIRTUAL;
				break;
			case H_INVOKEINTERFACE:
				invokeOpcode = INVOKEINTERFACE;
				break;
			default:
				throw new RuntimeException("Unsupported handle kind: " + Textifier.HANDLE_TAG[target.getTag()]);
		}

		// Add the call to the target method
		interfacem.instructions.add(new MethodInsnNode(invokeOpcode, targetOwner, target.getName(), target.getDesc()));

		// We might have to convert the result to the expected type before returning (but constructor lambdas are special)
		if(target.getTag() != H_NEWINVOKESPECIAL)
			interfacem.maxStack = Math.max(interfacem.maxStack,
					convertType(interfacem.instructions, Type.getReturnType(target.getDesc()), interfaceMethodType.getReturnType(), instantiatedMethodType.getReturnType()));

		Utils.addInstructions(interfacem.instructions,
				new InsnNode(interfaceMethodType.getReturnType().getOpcode(IRETURN)),
				interfacemEnd);

		cn.methods.add(interfacem);

		MethodNode staticConstructor = new MethodNode(ACC_PUBLIC | ACC_STATIC, "create", descriptor, null, null);
		staticConstructor.maxStack = 2;
		Utils.addInstructions(staticConstructor.instructions,
				new TypeInsnNode(NEW, cn.name),
				new InsnNode(DUP));

		for(int i = 0, local = 0; i < fieldTypes.length; i++) {
			Type ft = fieldTypes[i];
			staticConstructor.instructions.add(new VarInsnNode(ft.getOpcode(ILOAD), local));
			staticConstructor.maxLocals += ft.getSize();
			staticConstructor.maxStack += ft.getSize();
			local += ft.getSize();
		}

		Utils.addInstructions(staticConstructor.instructions,
				new MethodInsnNode(INVOKESPECIAL, cn.name, constructor.name, constructor.desc, false),
				new InsnNode(ARETURN));

		cn.methods.add(staticConstructor);

		MethodNode mn = Utils.getMethod(targetOwner, target.getName(), target.getDesc()).get();
		//System.out.println(String.format("Generated %s for invokeDynamic (%s, %s.%s) (target size: %d)",
		//								 cn.name, targetOwner, target.getName(), target.getDesc(), mn.instructions.size()));
		if(false) {
			System.out.println(Utils.toString(insn));
			cn.accept(new TraceClassVisitor(new PrintWriter(System.out)));
		}

		cn.accept(new CheckClassAdapter(null));

		if(storeClasses) {
			ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			cn.accept(cw);
			byte[] b = cw.toByteArray();
			classes.add(new Pair<>(cn.name, b));

			// TODO: This is quite hacky, but it is needed for Class.forName calls later
			File of = new File("out/production/streamliner/" + cn.name.replace(".", "/") + ".class");
			try(FileOutputStream fos = new FileOutputStream(of)) {
				fos.write(b);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}


		method.instructions.set(insn, new MethodInsnNode(INVOKESTATIC, cn.name, staticConstructor.name, staticConstructor.desc, false));
		models.put(cn.name, insn);
		ClassNodeCache.putBack(cn.name, cn);
	}

	/** Returns the maximum stack size required to hold from or to */
	private int convertType(InsnList insns, Type from, Type to, Type functional) {
		if(from.equals(to) && from.equals(functional)) return from.getSize();

		int fromSort = from.getSort(), toSort = to.getSort();
		IntFunction<Boolean> isRef = sort -> sort == Type.OBJECT || sort == Type.ARRAY;

		if(to == Type.VOID_TYPE) {
			insns.add(new InsnNode((from == Type.LONG_TYPE || from == Type.DOUBLE_TYPE ? POP2 : POP)));
			return from.getSize();
		}

		if(!isRef.apply(fromSort)) {
			switch(fromSort) {
				case Type.INT:
					switch(toSort) {
						case Type.OBJECT:
							insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Integer", "valueOf",
									Type.getMethodDescriptor(Type.getObjectType("java/lang/Integer"), Type.INT_TYPE), false));
							return 1;

						case Type.LONG:
							insns.add(new InsnNode(I2L));
							return 2;

						case Type.DOUBLE:
							insns.add(new InsnNode(I2D));
							return 2;

						case Type.FLOAT:
							insns.add(new InsnNode(I2F));
							return 1;
					}
					break;

				case Type.LONG:
					switch (toSort) {
						case Type.OBJECT:
							insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Long", "valueOf",
									Type.getMethodDescriptor(Type.getObjectType("java/lang/Long"), Type.LONG_TYPE), false));
							return 2;

						case Type.DOUBLE:
							insns.add(new InsnNode(L2D));
							return 2;

						case Type.INT:
							insns.add(new InsnNode(L2I));
							return 2;

						case Type.FLOAT:
							insns.add(new InsnNode(L2F));
							return 2;
					}
					break;

				case Type.DOUBLE:
					switch (toSort) {
						case Type.OBJECT:
							insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Double", "valueOf",
									Type.getMethodDescriptor(Type.getObjectType("java/lang/Double"), Type.DOUBLE_TYPE), false));
							return 2;

						case Type.LONG:
							insns.add(new InsnNode(D2L));
							return 2;

						case Type.INT:
							insns.add(new InsnNode(D2I));
							return 2;

						case Type.FLOAT:
							insns.add(new InsnNode(D2F));
							return 2;
					}
					break;

				case Type.BOOLEAN:
					switch(toSort) {
						case Type.OBJECT:
							insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Boolean", "valueOf",
									Type.getMethodDescriptor(Type.getObjectType("java/lang/Boolean"), Type.BOOLEAN_TYPE), false));
							return 1;
					}
					break;
			}
		} else {
			if(isRef.apply(functional.getSort()) && !from.equals(functional)) {
				cast(insns, from, functional);
				from = functional;
			}

			if(isRef.apply(toSort)) {
				cast(insns, from, to);
				return 1;
			} else {
				switch(toSort) {
					case Type.INT:
						insns.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Integer", "intValue",
								Type.getMethodDescriptor(Type.INT_TYPE), false));
						return 1;

					case Type.LONG:
						insns.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Long", "longValue",
								Type.getMethodDescriptor(Type.LONG_TYPE), false));
						return 2;

					case Type.DOUBLE:
						insns.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Double", "doubleValue",
								Type.getMethodDescriptor(Type.DOUBLE_TYPE), false));
						return 2;

					case Type.BOOLEAN:
						insns.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue",
								Type.getMethodDescriptor(Type.BOOLEAN_TYPE), false));
						return 1;
				}
			}
		}

		throw new RuntimeException("Type conversion not implemented");
	}

	private void cast(InsnList insns, Type from, Type to) {
		if (!Utils.getAncestors(from).contains(to))
			insns.add(new TypeInsnNode(CHECKCAST, to.getInternalName()));
	}
}
