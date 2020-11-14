package dk.casa.streamliner.asm.analysis.inter;

import dk.casa.streamliner.asm.*;
import dk.casa.streamliner.asm.analysis.FlatElement;
import dk.casa.streamliner.asm.analysis.inter.oracles.ExhaustiveOracle;
import dk.casa.streamliner.asm.analysis.inter.oracles.Oracle;
import dk.casa.streamliner.asm.analysis.inter.oracles.TypeQueryOracle;
import dk.casa.streamliner.asm.analysis.pointer.AbstractObject;
import dk.casa.streamliner.asm.analysis.pointer.Heap;
import dk.casa.streamliner.asm.transform.JavaPreprocess;
import dk.casa.streamliner.asm.transform.LambdaPreprocessor;
import org.apache.commons.math3.util.Pair;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.util.Textifier;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static dk.casa.streamliner.utils.Utils.isJava8;
import static org.objectweb.asm.Opcodes.*;

public class InterproceduralTypePointerAnalysis {
	public static final Map<Context, InterFrame[]> calls = new HashMap<>();
	public static final Map<Context, Set<Integer>> analysedCalls = new HashMap<>();
	// allocations is a map: Context -> Instruction index -> allocation index
	public static final Map<Context, Map<Integer, Integer>> allocations = new HashMap<>();
	public static final Map<String, Integer> staticAllocations = new HashMap<>();
	public static final Map<Integer, String> allocationTypes = new HashMap<>();

	private static Oracle oracle;
	private static int allocCounter;
	public static void reset() {
		calls.clear();
		analysedCalls.clear();
		allocCounter = 0;
		allocations.clear();
		staticAllocations.clear();
		allocationTypes.clear();
		oracle = null;
	}

	public static Context startAnalysis(String owner, MethodNode mn, Oracle oracle) throws AnalyzerException {
		reset();

		InterproceduralTypePointerAnalysis.oracle = oracle;

		// Prep heap and argument list
		Heap<InterValue> heap = getInitialStaticHeap(Arrays.asList(
				"java/util/stream/StreamOpFlag$Type",
				"java/util/stream/StreamOpFlag",
				"java/util/stream/MatchOps$MatchKind",
				"java/util/stream/Collectors",
				"java/util/stream/Collector$Characteristics"
		), // Java 8 does not have static initializers for FindSinks and WhileOps does not exist
			isJava8()? Collections.emptyList() :
				Arrays.asList(
					"java/util/stream/FindOps$FindSink$OfRef",
					"java/util/stream/FindOps$FindSink$OfInt",
					"java/util/stream/FindOps$FindSink$OfLong",
					"java/util/stream/FindOps$FindSink$OfDouble",
					"java/util/stream/WhileOps")
		);
		List<InterValue> args = new ArrayList<>();
		// Add 'this' argument if not static
		if((mn.access & ACC_STATIC) == 0) {
			Type thisTy = Type.getObjectType(owner);
			args.add(new InterValue(new TypeElement(false, thisTy), PointerElement.uTOP));
		}

		for (Type argTy : Type.getArgumentTypes(mn.desc)) {
			TypeElement type = new TypeElement(false, argTy);
			args.add(new InterValue(type, PointerElement.uTOP));
		}

		Set<Integer> origEscape = new HashSet<>(heap.getEscaped());

		//Context initialContext = new HeapContext(owner, mn, heap, args);
		Context initialContext = new StackContext(owner, mn, heap, args);
		InterFrame[] frames = analyzeRecursively(initialContext, oracle);
		AbstractInsnNode[] insns = mn.instructions.toArray();

		// Handle escaping return values
		Set<Integer> finalEscape = new HashSet<>();
		InterInterpreter interp = new InterInterpreter();
		for (int i = 0; i < frames.length; i++) {
			AbstractInsnNode insn = insns[i];
			int opcode = insn.getOpcode();

			if(opcode >= IRETURN && opcode <= RETURN || opcode == ATHROW) {
				InterFrame frame = frames[i];
				finalEscape.addAll(frame.getHeap().getEscaped());
				interp.setHeap(frame.getHeap());

				if(opcode != RETURN)
					interp.valueEscapes(frame.getStack(frame.getStackSize() - 1));

				frame.getHeap().getEscaped().removeAll(origEscape);
			}
		}

		finalEscape.removeAll(origEscape);
		if(!finalEscape.isEmpty())
			throw new RuntimeException("Tracked values escape");

		return initialContext;
	}

	static InterFrame[] analyzeRecursively(Context c, Oracle oracle) throws AnalyzerException {
		if(c.getDepth() > 100) throw new RuntimeException("Infinite recursion?");

		InterInterpreter interpreter = new InterInterpreter();
		InterAnalyzer analyzer = new InterAnalyzer(interpreter, oracle);
		return analyzer.analyze(c);
	}

	static int putAllocation(Context context, AbstractInsnNode insn, String name) {
		int instructionIndex = context.getMethod().instructions.indexOf(insn);
		return allocations.computeIfAbsent(context, c -> new HashMap<>()).computeIfAbsent(instructionIndex, i -> {
			allocationTypes.put(allocCounter, name);
			return allocCounter++;
		});
	}

	public static int getAllocationIndex(Context context, int instructionIndex) {
		return allocations.get(context).get(instructionIndex);
	}

	public static void debug() {
		System.out.println("Calls: " + calls.size());

		calls.entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().getOwner())).forEachOrdered(e -> {
			Context c = e.getKey();
			System.out.println(c);
			try {
				System.out.println(Utils.getReturnFrame(c.getMethod(), e.getValue(), new InterInterpreter(), InterFrame::new));
			} catch (AnalyzerException ex) {
				throw new RuntimeException(ex);
			}
			//System.out.println("\nMethod analysis:");
			//Utils.printMethodWithFrames(c.method, e.getValue());
			System.out.println();
		});
	}

	public static InlineMethod resolveCall(MethodInsnNode mn, List<InterValue> values, Heap<InterValue> heap, Context context) throws AnalyzerException {
		return resolveCall(mn, values, heap, context, oracle);
	}

	public static InlineMethod resolveCall(MethodInsnNode mn, List<InterValue> values, Heap<InterValue> heap, Context context, TypeQueryOracle oracle) throws AnalyzerException {
		if(mn.owner.startsWith("[")) {
			// We are calling the clone method on an array
			if(!mn.name.equals("clone") || !Type.getType(mn.desc).equals(Type.getMethodType(Type.getObjectType("java/lang/Object"))))
				throw new RuntimeException("What?");

			return new InlineMethod(new MethodNode(ACC_PUBLIC | ACC_NATIVE, mn.name, mn.desc, null, null),
					mn.owner);
		}

		switch(mn.getOpcode()) {
			case INVOKEINTERFACE:
			case INVOKEVIRTUAL:
				InterValue thisValue = values.get(0);
				TypeElement t = thisValue.type;
				String fromClass = t.getType().getSort() == Type.ARRAY ? "java/lang/Object" : t.getType().getInternalName();

				if(!t.isPrecise()) {
					// Try to promote to precise type if type is final
					// TODO: Is there a better place to do this? Maybe in the TypeElement constructor?
					ClassNode cls = ClassNodeCache.get(t.getType().getInternalName());
					if ((cls.access & ACC_FINAL) != 0) {} // Class is final so we can trust the type
					else {
						cls = ClassNodeCache.get(mn.owner);
						Optional<MethodNode> mthd = Utils.getMethod(cls, mn.name, mn.desc);
						if((cls.access & ACC_FINAL) != 0 // Class is final
								|| (mthd.isPresent() && (mthd.get().access & ACC_FINAL) != 0)) // Method is final

							fromClass = mn.owner;
						else {
							fromClass = oracle.queryType(context, mn, thisValue).map(Type::getInternalName)
									.orElseThrow(() -> new PrecisionLossException(mn, "Runtime type of 'this' is unknown"));

						}
					}

					values.set(0, new InterValue(new TypeElement(true, Type.getObjectType(fromClass)),
												 thisValue.pointer, thisValue.constant));
				}

				InlineMethod resolvedMethod = Utils.resolveMethodForInlining(fromClass, mn.name, mn.desc).get();

				String name = resolvedMethod.mth.name;
				// We have a special model for java.util.stream.AbstractPipeline.wrapSink and copyIntoWithCancel
				if(resolvedMethod.owner.equals("java/util/stream/AbstractPipeline")) {
					if(name.equals("wrapSink")) {
						int depth = getPipelineDepth(thisValue, heap);
						resolvedMethod = new InlineMethod(JavaPreprocess.getWrapSinkModel(depth), resolvedMethod.owner);
					} else if(name.equals("copyIntoWithCancel")) {
						int depth = getPipelineDepth(thisValue, heap);
						resolvedMethod = new InlineMethod(JavaPreprocess.getCopyIntoWithCancelModel(depth), resolvedMethod.owner);
					}
				}

				return resolvedMethod;

			case INVOKESTATIC:
			case INVOKESPECIAL:
				return Utils.resolveMethodForInlining(mn.owner, mn.name, mn.desc).get();

			default:
				throw new RuntimeException("Resolve of " + Textifier.OPCODES[mn.getOpcode()] + " not supported.");
		}
	}

	private static final InterValue topValue = new InterValue(new TypeElement(false, TypeElement.TOP), PointerElement.TOP);
	private static int getPipelineDepth(InterValue root, Heap<InterValue> heap) throws AnalyzerException {
		// TODO: Handle when root is invalid
		int node = root.pointsTo();
		FlatElement<Number> constant = heap.getField(node, "java/util/stream/AbstractPipeline.depth", topValue).constant;
		if(constant.isDefined()) return constant.value.intValue();
		else
			System.err.println("Depth field not precise, trying to compute pipeline depth instead!");

		int depth = 0;
		while(true) {
			InterValue previous = heap.getField(node, "previousStage", topValue);
			if(previous.pointer == PointerElement.NULL) break;

			node = previous.pointsTo();
			depth++;
		}
		return depth;
	}

	private static Heap<InterValue> getInitialStaticHeap(Collection<String> dynamicClasses, Collection<String> staticClasses) {
		Heap<InterValue> res = new Heap<>();
		IdentityHashMap<Object, Integer> seen = new IdentityHashMap<>();

		int firstAlloc = allocCounter;

		Function<String, AbstractObject<InterValue>> initClass = (String clsName) -> {
			int allocIndex = allocCounter++;
			staticAllocations.put(clsName, allocIndex);
			AbstractObject<InterValue> staticObj = new AbstractObject<>(clsName, true);
			res.allocate(allocIndex, staticObj);
			return staticObj;
		};

		try {
			for (String clsName : dynamicClasses) {
				AbstractObject<InterValue> staticObj = initClass.apply(clsName);
				String javaName = clsName.replace('/', '.');
				Class<?> cls = Class.forName(javaName);
				for(Field field : cls.getDeclaredFields()) {
					int modifiers = field.getModifiers();
					if(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers)) {
						field.setAccessible(true);
						Object value = field.get(null);
						staticObj.setField(clsName + "." + field.getName(), traceHeap(field.getType(), value, res, seen));
					}
				}
			}

			Oracle oracle = new ExhaustiveOracle();
			for (String clsName : staticClasses) {
				AbstractObject<InterValue> staticObj = initClass.apply(clsName);
				ClassNode cn = ClassNodeCache.get(clsName);
				MethodNode clinit = Utils.getMethod(cn, "<clinit>", Type.getMethodDescriptor(Type.VOID_TYPE))
										.orElseThrow(() -> new RuntimeException("Missing static initializer method"));
				new LambdaPreprocessor(clinit).preprocess();

				Context context = new StackContext(clsName, clinit, res, Collections.emptyList());
				InterInterpreter interpreter = new InterInterpreter();
				InterFrame returnFrame = Utils.getReturnFrame(clinit, analyzeRecursively(context, oracle), interpreter, InterFrame::new);
				returnFrame.getHeap().copyTo(res);

			}

			res.addEscape(IntStream.range(firstAlloc, allocCounter).boxed().collect(Collectors.toList()));

			// Sanitize final fields (TODO: Measure impact on RQ2)
			// TODO: Use uTOP?
			InterInterpreter interpreter = new InterInterpreter();
			res.entrySet().stream().map(Map.Entry::getValue)
					.forEach(obj -> obj.getFields().forEach(fe -> {
						FieldNode fn = fe.getSecond();
						if(!Modifier.isFinal(fn.access))
							obj.setField(fe.getFirst() + "." + fn.name, interpreter.topValue(Type.getType(fn.desc)));
					}));

			/*
			// Get rid of objects that are not directly reachable from the static objects
			res.entrySet().stream().map(Map.Entry::getValue)
					.flatMap(obj -> obj.entrySet().stream().map(Map.Entry::getValue))
					.filter(InterValue::isValid)
					.map(v -> v.pointer.maybeInteresting());
			 */

			return res;
		} catch(ClassNotFoundException | IllegalAccessException | AnalyzerException exc) {
			throw new RuntimeException(exc);
		}
	}

	private static InterValue traceHeap(Class<?> type, Object value, Heap<InterValue> heap, IdentityHashMap<Object, Integer> seen) throws IllegalAccessException, ClassNotFoundException {
		if(value == null)
			return new InterValue(new TypeElement(false, Type.getType(type)), PointerElement.NULL);

		TypeElement typeElement = new TypeElement(true, Type.getType(value.getClass()));
		if(type.isPrimitive()) {
			FlatElement<Number> constant;
			if(type == int.class) constant = new FlatElement<>((int) value);
			else if(type == byte.class) constant = new FlatElement<>((int)(byte) value);
			else if(type == long.class) constant = new FlatElement<>((long) value);
			else if(type == boolean.class) constant = new FlatElement<>((boolean) value ? 1 : 0);
			else if(type == float.class) constant = new FlatElement<>((float) value);
			else if(type == double.class) constant = new FlatElement<>((double) value);
			else throw new RuntimeException("Unexpected primitive type: " + type);

			return new InterValue(new TypeElement(true, Type.getType(type)), PointerElement.TOP, constant);
		} else if(type.isArray())
			return new InterValue(typeElement, PointerElement.uTOP);
		else if(type == String.class)
			return new InterValue(typeElement, PointerElement.uTOP);

		int allocIndex;
		if(seen.containsKey(value)) allocIndex = seen.get(value);
		else {
			Class<?> actualType = value.getClass();
			String clsName = actualType.getName().replace('.', '/');

			allocIndex = allocCounter++;
			seen.put(value, allocIndex);
			AbstractObject<InterValue> obj = heap.allocate(allocIndex, clsName);

			while(actualType != null) {
				for (Pair<String, FieldNode> pair : obj.getFields()) {
					String fieldCls = pair.getFirst();
					FieldNode fn = pair.getSecond();
					Class<?> fieldOwner = Class.forName(fieldCls.replace("/", "."));
					try {
						Field field = fieldOwner.getDeclaredField(fn.name);
						field.setAccessible(true);
						obj.setField(fieldCls + "." + fn.name, traceHeap(field.getType(), field.get(fieldOwner.cast(value)), heap, seen));
					} catch (NoSuchFieldException nsf) {
						obj.setField(fieldCls + "." + fn.name, new InterValue(new TypeElement(false, TypeElement.TOP), PointerElement.uTOP));
					}
				}

				actualType = actualType.getSuperclass();
			}
		}

		return new InterValue(typeElement, new PointerElement(allocIndex));
	}
}
