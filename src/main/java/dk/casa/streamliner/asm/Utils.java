package dk.casa.streamliner.asm;

import dk.casa.streamliner.asm.comments.TraceMethodWithCommentVisitor;
import dk.casa.streamliner.asm.transform.JavaPreprocess;
import dk.casa.streamliner.asm.transform.LambdaPreprocessor;
import org.apache.commons.math3.util.Pair;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;
import org.objectweb.asm.util.Printer;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

import java.io.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.objectweb.asm.Opcodes.*;

public final class Utils {
	public static void printMethod(MethodNode mv) {
		TraceMethodWithCommentVisitor mvn = new TraceMethodWithCommentVisitor();
		mv.accept(mvn);
		List<Object> lines = mvn.getText();
		System.out.println(mv.name + " : " + mv.desc);
		int digs = (int)Math.ceil(Math.log10(lines.size()));
		for (int i = 0; i < lines.size(); i++)
			System.out.print(String.format("%1$" + digs + "s", i).replace(' ', '0') + ":" + lines.get(i));
	}

	public static String toString(AbstractInsnNode insn) {
		Printer p = new Textifier();
		MethodVisitor mv = new TraceMethodVisitor(p);
		insn.accept(mv);
		return ((String) p.getText().get(0)).trim();
	}

	public static boolean isLoad(int opcode) {
		switch(opcode) {
			case ILOAD:
			case FLOAD:
			case DLOAD:
			case LLOAD:
			case ALOAD:
				return true;
		}

		return false;
	}

	public static boolean isConstructor(MethodNode mn) {
		return mn.name.equals("<init>");
	}

	public static void printMethodWithFrames(MethodNode mv, Frame<? extends Value>[] frames) {
		AbstractInsnNode[] insns = mv.instructions.toArray();
		for(int i = 0; i < insns.length && insns[i] != null; i++) {
			int type = insns[i].getType();
			if(type == AbstractInsnNode.FRAME || type == AbstractInsnNode.LABEL || type == AbstractInsnNode.LINE) continue;
			Frame<? extends Value> fm = frames[i];
			if(fm != null)
				System.out.println(fm);

			System.out.println(Utils.toString(insns[i]) + "\n");
		}
	}

	public static ClassNode loadClassFile(File file) throws IOException {
		try(FileInputStream is = new FileInputStream(file)) {
			return loadClassFile(is);
		}
	}

	public static ClassNode loadClassFile(InputStream inputStream) throws IOException {
		ClassNode cn = new ClassNode();
		ClassReader cr = new ClassReader(inputStream);
		cr.accept(cn, ClassReader.EXPAND_FRAMES);
		return cn;
	}

	public static List<ClassNode> loadJarFile(File file) throws IOException {
		List<ClassNode> classes = new ArrayList<>();
		try(JarFile jarFile = new JarFile(file, true)) {
			Enumeration<JarEntry> entries = jarFile.entries();
			while(entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				if(!entry.getName().endsWith(".class")) continue;
				try(InputStream is = jarFile.getInputStream(entry)) {
					classes.add(loadClassFile(is));
				}
			}
		}

		return classes;
	}

	public static Optional<Integer> getLineNumber(AbstractInsnNode insn) {
		for (AbstractInsnNode prev = insn; ; prev = prev.getPrevious()) {
			if (prev == null || prev instanceof LabelNode) {
				System.err.println("Unable to find LineNumberNode before LabelNode");
				return Optional.empty();
			} else if (prev instanceof LineNumberNode)
				return Optional.of(((LineNumberNode) prev).line);
		}
	}

	public static void removeInstructionsIf(MethodNode node, Predicate<AbstractInsnNode> p) {
		ListIterator<AbstractInsnNode> it = node.instructions.iterator();
		while(it.hasNext()) if(p.test(it.next())) it.remove();
	}

	public static void addInstructions(InsnList l, AbstractInsnNode... insns) {
		for(AbstractInsnNode insn : insns) l.add(insn);
	}

	public static Stream<AbstractInsnNode> instructionStream(MethodNode mn) {
		return StreamSupport.stream(Spliterators.spliterator(mn.instructions.iterator(), mn.instructions.size(), Spliterator.SIZED), false);
	}

	// TODO: Add hasAncestor method
	// TODO: Replace usages with this method
	public static Set<Type> getAncestors(String className) {
		return getAncestors(Type.getObjectType(className));
	}

	public static Set<Type> getAncestors(Type type) {
		Set<Type> res = new HashSet<>();
		getAncestors(type, res);
		return res;
	}

	private static void getAncestors(Type type, Set<Type> res) {
		if(!res.add(type)) return;

		switch(type.getSort()) {
			case Type.OBJECT:
				ClassNode cn = ClassNodeCache.get(type.getInternalName());

				if(cn.superName != null) getAncestors(Type.getObjectType(cn.superName), res);
				for(String superName : cn.interfaces) getAncestors(Type.getObjectType(superName), res);
				break;

			case Type.ARRAY:
				Stream.of("java/io/Serializable", "java/lang/Cloneable", "java/lang/Object")
						.map(Type::getObjectType).forEach(t -> getAncestors(t, res));

				getAncestors(type.getElementType()).forEach(t ->
						getAncestors(Type.getType("[" + t.getDescriptor()), res));
				break;
		}
	}

	/** Get the fields of this class and superclasses
	 *  TODO: Could also include fields from interfaces?
	 * */
	public static Set<Pair<String, FieldNode>> getFields(String className, Predicate<FieldNode> filter) {
		Set<Pair<String, FieldNode>> result = new HashSet<>();
		while(className != null) {
			ClassNode cn = ClassNodeCache.get(className);
			cn.fields.stream().filter(filter)
					// The classLoader field cannot be accessed by reflection.
					// It generally shouldn't have an effect to ignore this field, but other workarounds
					// exist if tracking the abstract value of this field is desired.
					.filter(fn -> !fn.name.equals("classLoader"))
					.map(fn -> new Pair<>(cn.name, fn)).forEach(result::add);
			className = cn.superName;
		}
		return result;
	}

	public static Optional<MethodNode> getMethod(String className, String name, String descriptor) {
		return getMethod(ClassNodeCache.get(className), name, descriptor);
	}

	public static Optional<MethodNode> getMethod(ClassNode cls, String name, String descriptor) {
		return getMethod(cls, m -> m.name.equals(name) && m.desc.equals(descriptor));
	}

	public static Optional<MethodNode> getMethod(ClassNode cls, Predicate<MethodNode> predicate) {
		return cls.methods.stream().filter(predicate).findAny();
	}

	/** Recursively find a method by traversing the inheritance tree */
	public static Optional<Pair<String, MethodNode>> findMethod(String fromClass, Predicate<MethodNode> predicate) {
		ClassNode cls = ClassNodeCache.get(fromClass);
		Optional<Pair<String, MethodNode>> res = getMethod(cls, predicate).map(mn -> new Pair<>(fromClass, mn));
		if(res.isPresent()) return res;

		if(cls.superName != null) {
			res = findMethod(cls.superName, predicate);
			if(res.isPresent()) return res;
		}

		for(String superName : cls.interfaces) {
			res = findMethod(superName, predicate);
			if(res.isPresent()) return res;
		}

		return Optional.empty();
	}

	public static Optional<Pair<String, MethodNode>> findMethod(String fromClass, String name, String descriptor) {
		return findMethod(fromClass, mn -> mn.name.equals(name) && mn.desc.equals(descriptor));
	}

	/** Recursively find the implementation of a method by traversing the inheritance tree */
	public static Optional<InlineMethod> resolveMethodForInlining(String fromClass, String name, String descriptor) {
		return findMethod(fromClass, mn -> mn.name.equals(name) && mn.desc.equals(descriptor) && (mn.access & ACC_ABSTRACT) == 0).map(pr -> {
			MethodNode mn = pr.getSecond();
			JavaPreprocess.preprocess(pr.getFirst(), mn);
			new LambdaPreprocessor(mn).preprocess();
			return new InlineMethod(mn, pr.getFirst());
		});
	}

	/** Roughly follows this: https://docs.oracle.com/javase/specs/jvms/se11/html/jvms-5.html#jvms-5.4.3.2 */
	public static Optional<Pair<String, FieldNode>> resolveField(String owner, String name, String descriptor) {
		ClassNode cls = ClassNodeCache.get(owner);
		Optional<Pair<String, FieldNode>> res =
				cls.fields.stream().filter(fn -> fn.name.equals(name) && fn.desc.equals(descriptor)).findAny()
				.map(fn -> new Pair<>(owner, fn));
		if(res.isPresent()) return res;

		for (String interfaceName : cls.interfaces) {
			res = resolveField(interfaceName, name, descriptor);
			if(res.isPresent()) return res;
		}

		if(cls.superName != null)
			return resolveField(cls.superName, name, descriptor);

		return Optional.empty();
	}

	/** Merge the frames at all return instructions in the method */
	public static <V extends Value, F extends Frame<V>>
	F getReturnFrame(MethodNode mn, F[] frames, Interpreter<V> interpreter,
	                 Function<F, F> frameSupplier) throws AnalyzerException {
		F returnFrame = null;
		for(int i = 0; i < mn.instructions.size(); i++) {
			AbstractInsnNode insn = mn.instructions.get(i);
			switch (insn.getOpcode()) {
				case IRETURN:
				case DRETURN:
				case FRETURN:
				case LRETURN:
				case ARETURN:
				case RETURN:
					F f = frames[i];
					if(f == null) continue; // unreachable code

					if(returnFrame == null) returnFrame = frameSupplier.apply(f);
					else returnFrame.merge(f, interpreter);
					break;

				// TODO: Handle throws?
			}
		}

		if(returnFrame == null)
			System.err.println("Unable to find any return instruction");
			//throw new RuntimeException("Unable to find any return instruction?");
		return returnFrame;
	}

	public static <V extends Value> List<V> getArgumentValues(MethodInsnNode minsn, Frame<V> frame) {
		int numArgs = Type.getArgumentTypes(minsn.desc).length;

		if(minsn.getOpcode() != INVOKESTATIC)
			numArgs++; // This

		int stackSize = frame.getStackSize();
		if(numArgs > stackSize)
			throw new RuntimeException("More arguments than values on the stack!");

		List<V> values = new ArrayList<>();
		for(int i = stackSize - numArgs; i < stackSize; i++)
			values.add(frame.getStack(i));

		return values;
	}
}
