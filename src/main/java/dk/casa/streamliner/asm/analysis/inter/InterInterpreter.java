package dk.casa.streamliner.asm.analysis.inter;

import dk.casa.streamliner.asm.InlineMethod;
import dk.casa.streamliner.asm.Utils;
import dk.casa.streamliner.asm.analysis.FlatElement;
import dk.casa.streamliner.asm.analysis.constant.ConstantEvaluator;
import dk.casa.streamliner.asm.analysis.inter.oracles.Oracle;
import dk.casa.streamliner.asm.analysis.pointer.AbstractObject;
import dk.casa.streamliner.asm.analysis.pointer.Heap;
import org.apache.commons.math3.util.Pair;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.util.Textifier;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.objectweb.asm.Opcodes.*;

public class InterInterpreter extends Interpreter<InterValue> {
	private Context context;
	private Oracle oracle;

	private Heap<InterValue> heap;
	private final List<InterValue> paddedArguments = new ArrayList<>();

	// Used for type calculations
	protected static BasicInterpreter basicInterpreter = new BasicInterpreter();

	private InterValue topValue(Type type, FlatElement<Number> constant) {
		if(type == null) type = TypeElement.TOP;
		return new InterValue(new TypeElement(false, type), PointerElement.TOP, constant);
	}

	protected InterValue topValue(Type type) {
		return topValue(type, FlatElement.getTop());
	}

	protected BasicValue toBasicValue(InterValue value) {
		return basicInterpreter.newValue(value.type.getType());
	}

	protected static Type fromBasicValue(BasicValue value) {
		if(value == null) return TypeElement.TOP;
		return value.getType();
	}

	public InterInterpreter() {
		super(ASM7);
	}

	public Context getContext() { return context; }
	public void initialiseForAnalysis(Context context, Oracle oracle) {
		this.context = context;
		this.oracle = oracle;

		// Compute the list of locals corresponding to the arguments
		MethodNode mn = context.getMethod();
		List<Type> argTypes = new ArrayList<>(Arrays.asList(Type.getArgumentTypes(mn.desc)));
		if((mn.access & ACC_STATIC) == 0)
			argTypes.add(0, Type.getObjectType(context.getOwner()));

		paddedArguments.clear();
		for(int i = 0; i < argTypes.size(); i++) {
			paddedArguments.add(context.getArguments().get(i));

			if(argTypes.get(i).getSize() == 2)
				paddedArguments.add(topValue(TypeElement.TOP));
		}
	}

	public void setHeap(Heap<InterValue> heap) {
		this.heap = heap;
	}

	/* We cannot rely solely on names to uniquely identify fields in the heap due to shadowing */
	private static String getFieldName(String owner, String name, String desc) {
		Pair<String, FieldNode> res = Utils.resolveField(owner, name, desc).get();
		return res.getFirst() + "." + res.getSecond().name;
	}

	public static String getFieldName(FieldInsnNode finsn) {
		return getFieldName(finsn.owner, finsn.name, finsn.desc);
	}

	public static String getFieldName(String owner, FieldNode fn) {
		return getFieldName(owner, fn.name, fn.desc);
	}

	@Override
	public InterValue newValue(Type type) {
		if(type == Type.VOID_TYPE) return null;

		return topValue(type);
	}

	@Override
	public InterValue newParameterValue(boolean isInstanceMethod, int local, Type type) {
		return paddedArguments.get(local);
	}

	/** We never want to track fields of StringBuilders, Strings or exceptions */
	private static boolean interestingAllocation(TypeInsnNode insn) {
		switch(insn.desc) {
			case "java/lang/StringBuilder":
			case "java/lang/String":
				return false;
		}

		return !Utils.getAncestors(Type.getObjectType(insn.desc)).contains(Type.getObjectType("java/lang/Throwable"));
	}

	@Override
	public InterValue newOperation(AbstractInsnNode insn) throws AnalyzerException {
		FlatElement<Number> constant = ConstantEvaluator.newOperation(insn);

		switch(insn.getOpcode()) {
			case ACONST_NULL:
				return new InterValue(new TypeElement(false, Type.getObjectType("java/lang/Object")), PointerElement.NULL);

			case NEW:
				TypeInsnNode tinsn = (TypeInsnNode) insn;
				Type objectType = Type.getObjectType(tinsn.desc);
				int allocIndex = InterproceduralTypePointerAnalysis.putAllocation(context, insn, objectType.getClassName());
				if(!interestingAllocation(tinsn) || !oracle.shouldTrackAllocation(context, objectType))
					return new InterValue(new TypeElement(true, objectType), PointerElement.uTOP);

				AbstractObject<InterValue> obj = heap.allocate(allocIndex, tinsn.desc);

				// Allocate default abstract values for instance (non-static) fields
				for(Pair<String, FieldNode> pair : obj.getFields()) {
					FieldNode fn = pair.getSecond();
					Type type = Type.getType(fn.desc);
					constant = FlatElement.getTop();
					PointerElement ptr = PointerElement.TOP;
					if(fn.value != null)
						throw new RuntimeException("TODO");

					switch(type.getSort()) {
						case Type.BOOLEAN:
						case Type.BYTE:
						case Type.CHAR:
						case Type.SHORT:
						case Type.INT:
							type = Type.INT_TYPE; // Prevent merging e.g. boolean and int to TOP
							constant = new FlatElement<>(0);
							break;

						case Type.LONG: constant = new FlatElement<>(0L); break;
						case Type.FLOAT: constant = new FlatElement<>(0.f); break;
						case Type.DOUBLE: constant = new FlatElement<>(0.); break;

						case Type.OBJECT:
						case Type.ARRAY:
							ptr = PointerElement.NULL;
							break;

						default: throw new RuntimeException("Unexpected field sort: " + type.getSort());
					}

					obj.setField(getFieldName(pair.getFirst(), fn), new InterValue(new TypeElement(false, type), ptr, constant));
				}

				return new InterValue(new TypeElement(true, objectType), new PointerElement(allocIndex));

			case GETSTATIC:
				FieldInsnNode finsn = (FieldInsnNode) insn;
				Type ftype = Type.getType(finsn.desc);

				Integer sallocIndex = InterproceduralTypePointerAnalysis.staticAllocations.get(finsn.owner);
				if(sallocIndex != null)
					return heap.getField(sallocIndex, getFieldName(finsn), topValue(Type.getType(finsn.desc)));

				return new InterValue(new TypeElement(false, ftype), PointerElement.uTOP);

			case LDC: // For strings and such
				LdcInsnNode ldcinsn = (LdcInsnNode) insn;
				Object cst = ldcinsn.cst;
				Type ctype;
				if(cst instanceof Integer)
					ctype = Type.INT_TYPE;
				else if(cst instanceof Long)
					ctype = Type.LONG_TYPE;
				else if(cst instanceof Float)
					ctype = Type.FLOAT_TYPE;
				else if(cst instanceof Double)
					ctype = Type.DOUBLE_TYPE;
				else if(cst instanceof String)
					ctype = Type.getObjectType("java/lang/String");
				else if(cst instanceof Type) {
					int sort = ((Type) cst).getSort();
					if(sort == Type.OBJECT || sort == Type.ARRAY)
						ctype = Type.getObjectType("java/lang/Class");
					else throw new AnalyzerException(insn, "Unhandled LDC type sort: " + cst);
				} else throw new AnalyzerException(insn, "Unknown LDC type: " + cst.getClass());

				// We want primitives to have the Top value since that is what we generate on operations on them
				TypeElement typeEl = new TypeElement(true, ctype);
				//PointerElement ptr = typeEl.maybePointer() && trackAllocations ? PointerElement.iTOP : PointerElement.uTOP;
				return new InterValue(typeEl, PointerElement.uTOP, constant);

			default:
				BasicValue basicValue = basicInterpreter.newOperation(insn);
				return topValue(fromBasicValue(basicValue), constant);
		}
	}

	@Override
	public InterValue copyOperation(AbstractInsnNode insn, InterValue value) throws AnalyzerException {
		return value;
	}

	@Override
	public InterValue unaryOperation(AbstractInsnNode insn, InterValue value) throws AnalyzerException {
		FieldInsnNode finsn;
		switch(insn.getOpcode()) {
			case GETFIELD:
				finsn = (FieldInsnNode) insn;
				String fieldName = getFieldName(finsn);
				Type ftype = Type.getType(finsn.desc);

				if(value.pointer == PointerElement.NULL)
					throw new RuntimeException("TODO");
				else if(PointerElement.uTOP.leq(value.pointer))
					return new InterValue(new TypeElement(false, ftype), value.pointer);
				else if(!value.isValid()) {
					InterValue rv = null;
					for (int allocIndex : reachableFromValue(value)) {
						AbstractObject<InterValue> obj = heap.getCell(allocIndex);
						if(obj.hasField(fieldName)) {
							if(rv == null) rv = obj.getField(fieldName);
							else rv = rv.merge(obj.getField(fieldName));
						}
					}
					return rv;
				}

				return heap.getField(value.pointsTo(), fieldName, topValue(ftype));

			case PUTSTATIC:
				finsn = (FieldInsnNode) insn;
				valueEscapes(value);

				Integer sallocIndex = InterproceduralTypePointerAnalysis.staticAllocations.get(finsn.owner);
				if(sallocIndex != null)
					heap.setField(sallocIndex, getFieldName(finsn), value);

				return null;

			case CHECKCAST:
				Type to = Type.getObjectType(((TypeInsnNode) insn).desc);
				// Specialize type if possible
				if(!Utils.getAncestors(value.type.getType()).contains(to))
					return new InterValue(new TypeElement(false, to), value.pointer, value.constant);
				return value;

			case INSTANCEOF:
				if(value.pointer == PointerElement.NULL)
					return topValue(Type.INT_TYPE, new FlatElement<>(0));

				TypeElement type = value.type;
				if(!type.maybePointer() || type.getType() == TypeElement.TOP) break;

				/* Imprecise type => return true/top
				   Precise type => return true/false */
				Set<Type> ancestors = Utils.getAncestors(type.getType());
				if(ancestors.contains(Type.getObjectType(((TypeInsnNode) insn).desc)))
					return topValue(Type.INT_TYPE, new FlatElement<>(1));
				else if(type.isPrecise())
					return topValue(Type.INT_TYPE, new FlatElement<>(0));

				break;

			case ANEWARRAY:
			case NEWARRAY:
				Type elementType;
				if(insn.getOpcode() == NEWARRAY) {
					int newArrayType = ((IntInsnNode) insn).operand;
					switch (newArrayType) {
						case T_BOOLEAN: elementType = Type.BOOLEAN_TYPE; break;
						case T_BYTE: elementType = Type.BYTE_TYPE; break;
						case T_CHAR: elementType = Type.CHAR_TYPE; break;
						case T_SHORT: elementType = Type.SHORT_TYPE; break;
						case T_INT: elementType = Type.INT_TYPE; break;
						case T_LONG: elementType = Type.LONG_TYPE; break;
						case T_FLOAT: elementType = Type.FLOAT_TYPE; break;
						case T_DOUBLE: elementType = Type.DOUBLE_TYPE; break;
						default: throw new RuntimeException("Unknown NEWARRAY type: " + Textifier.TYPES[newArrayType]);
					}
				} else
					elementType = Type.getObjectType(((TypeInsnNode) insn).desc);

				Type arrayType = Type.getType("[" + elementType.getDescriptor());
				return new InterValue(new TypeElement(true, arrayType), PointerElement.uTOP);
		}

		BasicValue basicValue = basicInterpreter.unaryOperation(insn, toBasicValue(value));
		return topValue(fromBasicValue(basicValue), ConstantEvaluator.unaryOperation(insn, value.constant));
	}

	@Override
	public InterValue binaryOperation(AbstractInsnNode insn, InterValue value1, InterValue value2) throws AnalyzerException {
		PointerElement resPtr = PointerElement.TOP;

		switch(insn.getOpcode()) {
			case PUTFIELD: {
				FieldInsnNode finsn = (FieldInsnNode) insn;
				PointerElement ptr = value1.pointer;
				String fieldName = getFieldName(finsn);

				if(ptr == PointerElement.NULL)
					throw new RuntimeException("TODO");
				else if(ptr == PointerElement.iTOP) {
					for (int allocIndex : heap.keySet()) {
						AbstractObject<InterValue> obj = heap.getCell(allocIndex);
						if(obj.hasField(fieldName))
							obj.setField(fieldName, merge(obj.getField(fieldName), value2));
					}

					// valueEscapes(value2);
				} else if(PointerElement.uTOP.leq(ptr)) {
					if(value2.type.maybePointer() && value2.pointer.maybeInteresting())
						throw new RuntimeException("We lost?");
				} else
					heap.setField(ptr.pointsTo(), fieldName, value2);

				return null; // unused
			}

			case IALOAD:
			case LALOAD:
			case FALOAD:
			case DALOAD:
			case AALOAD:
			case BALOAD:
			case CALOAD:
			case SALOAD:
				if(insn.getOpcode() == AALOAD) {
					// Array loads are always imprecise
					resPtr = PointerElement.uTOP; // Not unsound, as the analysis aborts if tracked locations are put into arrays
					// Try to get a more precise type than java/lang/Object
					Type arrType = value1.type.getType();
					if(arrType.getSort() == Type.ARRAY)
						return new InterValue(new TypeElement(false, arrType.getElementType()), resPtr, FlatElement.getTop());
				}

			default:
				BasicValue basicValue = basicInterpreter.binaryOperation(insn, toBasicValue(value1), toBasicValue(value2));

				return new InterValue(new TypeElement(false, fromBasicValue(basicValue)), resPtr,
									  ConstantEvaluator.binaryOperation(insn, value1.constant, value2.constant));
		}
	}

	@Override
	public InterValue ternaryOperation(AbstractInsnNode insn, InterValue value1, InterValue value2, InterValue value3) throws AnalyzerException {
		if(insn.getOpcode() == AASTORE) {
			valueEscapes(value3);
			return null;
		}

		BasicValue basicValue = basicInterpreter.ternaryOperation(insn, toBasicValue(value1), toBasicValue(value2), toBasicValue(value3));
		return topValue(fromBasicValue(basicValue));
	}

	private InterValue overapproximateCall(MethodInsnNode minsn, List<? extends InterValue> values, Collection<String> modifiedFields) throws AnalyzerException {
		// Instead of throwing an error we can soundly remove all information from the heap that is
		//  reachable from the arguments. If one of the arguments has a top-value it doesn't help much.
		//  We can make the pointer a combined may- and must analysis to make the over approximation smaller.
		//  Another insight one can use is that top-values cannot (directly) refer to objects allocated after itself.
		//  This seems complicated to implement, as we get a sort of hierarchy on the top-values.
		Set<Integer> reachable = reachableSubgraph(values);

		// We cannot stack-allocate objects that reach methods we do not inline
		heap.addEscape(reachable);

		if(modifiedFields == null) {
			// Since we overapproximate the call - anything can happen to the fields
			if(!reachable.isEmpty())
				throw new PrecisionLossException(minsn, "Overapproximation of call with reachable cells");
		} else if(!modifiedFields.isEmpty()) {
			for (int allocIndex : reachable) {
				AbstractObject<InterValue> obj = heap.getCell(allocIndex);
				for (String field : modifiedFields)
					if (obj.hasField(field)) obj.setField(field, merge(obj.getField(field), newValue(null)));
			}
		}

		boolean isGetClass = "java/lang/Object.getClass".equals(minsn.owner + "." + minsn.name);
		BasicValue basicValue = basicInterpreter.naryOperation(minsn, values.stream().map(this::toBasicValue).collect(Collectors.toList()));
		// Since we add all the reachable objects to the escaped set, we can return PointerElement.ESCAPED here
		TypeElement typeEl = new TypeElement(false, fromBasicValue(basicValue));
		return new InterValue(typeEl, reachable.isEmpty() || isGetClass ? PointerElement.uTOP : PointerElement.TOP);
	}

	private static final Map<String, List<String>> purity = new HashMap<>();
	static {
		purity.put("java/lang/Object.getClass", Collections.emptyList());
		purity.put("java/lang/Class.getName", Collections.emptyList());
		purity.put("java/lang/Class.initClassName", Collections.singletonList("java/lang/Class.name"));
		purity.put("java/lang/Class.getSuperclass", Collections.emptyList());

		purity.put("java/lang/System.identityHashCode", Collections.emptyList());

		purity.put("sun/misc/JavaLangAccess.getEnumConstantsShared", Collections.emptyList());
		purity.put("jdk/internal/access/JavaLangAccess.getEnumConstantsShared", Collections.emptyList());
		purity.put("jdk/internal/access/JavaLangAccess.fastUUID", Collections.emptyList());
		purity.put("jdk/internal/misc/JavaLangAccess.fastUUID", Collections.emptyList());
	}

	@Override
	public InterValue naryOperation(AbstractInsnNode insn, List<? extends InterValue> values) throws AnalyzerException {
		if(insn instanceof InvokeDynamicInsnNode) {
			// Simple model for StringConcatFactory invokedynamics
			InvokeDynamicInsnNode idyninsn = (InvokeDynamicInsnNode) insn;
			if(idyninsn.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory")
				&& idyninsn.name.startsWith("makeConcat")) {

				Type string = Type.getReturnType(idyninsn.desc);
				return new InterValue(new TypeElement(true, string), PointerElement.uTOP);
			}

			throw new RuntimeException("InvokeDynamic unsupported!");
		}

		Set<Integer> calls = InterproceduralTypePointerAnalysis.analysedCalls.computeIfAbsent(context, x -> new HashSet<>());
		MethodInsnNode minsn = (MethodInsnNode) insn;
		int insnIndex = context.getMethod().instructions.indexOf(minsn);
		calls.remove(insnIndex); // We can have analyzed a call earlier that we will not be able to now

		// We can be forced to analyse a method if a tracked value escapes into it
		// otherwise we ask the oracle if we should analyse the method.
		// TODO: Some methods are beneficial to analyze just for constant propagation information
		if(values.stream().map(this::reachableFromValue).allMatch(Set::isEmpty)
				&& !oracle.shouldAnalyseCall(context, minsn)) {
			Type returnType = Type.getReturnType(minsn.desc);
			return new InterValue(new TypeElement(false, returnType), PointerElement.uTOP);
		}

		InlineMethod im;
		List<InterValue> lvalues = new ArrayList<>(values);

		try {
			im = InterproceduralTypePointerAnalysis.resolveCall(minsn, lvalues, heap, context, oracle);
		} catch(PrecisionLossException exc) {
			Set<Type> ancestors = Utils.getAncestors(Type.getReturnType(minsn.desc));
			if(ancestors.contains(Type.getObjectType("java/util/stream/BaseStream"))
			    || ancestors.contains(Type.getObjectType("java/util/Spliterator")))
				throw new PrecisionLossException(minsn, "Unable to resolve call to pipeline method: " + minsn.owner + "." + minsn.name);
			return overapproximateCall(minsn, lvalues, purity.get(minsn.owner + "." + minsn.name));
		}

		if((im.mth.access & ACC_NATIVE) != 0) // handle native calls
			return overapproximateCall(minsn, lvalues, purity.get(im.owner + "." + im.mth.name));

		//System.out.println(String.join("", Collections.nCopies(context.getDepth(), " ")) + im.owner + "." + im.mth.name);
		Context callContext = context.newContext(im.owner, im.mth, insnIndex, new Heap<>(heap), lvalues);
		InterFrame[] frames = InterproceduralTypePointerAnalysis.analyzeRecursively(callContext, oracle);
		calls.add(insnIndex);

		InterFrame returnFrame = Utils.getReturnFrame(im.mth, frames, this, InterFrame::new);
		Type returnType = Type.getReturnType(minsn.desc);

		if(returnFrame == null) { // Can only return via. exception
			// TODO: Exception flow modelling needs to happen in InterFrame
			if(returnType != Type.VOID_TYPE) throw new RuntimeException("Expects value from exceptional frame");
		} else
			// Copy the result heap
			returnFrame.getHeap().copyTo(heap);

		if(returnType == Type.VOID_TYPE) return null;
		InterValue returnValue = returnFrame.getStack(returnFrame.getStackSize() - 1);
		return returnValue;
	}

	@Override
	public void returnOperation(AbstractInsnNode insn, InterValue value, InterValue expected) throws AnalyzerException {

	}

	@Override
	public InterValue merge(InterValue value1, InterValue value2) {
		return value1.merge(value2);
	}

	void valueEscapes(InterValue value) {
		valuesEscape(Collections.singleton(value));
	}

	private void valuesEscape(Collection<InterValue> values) {
		heap.addEscape(reachableSubgraph(values));
	}

	Set<Integer> reachableSubgraph(Collection<? extends InterValue> roots) {
		Set<Integer> reachable = roots.stream()
				.filter(root -> root.type.maybePointer())
				.flatMap(v -> reachableFromValue(v).stream())
				.collect(Collectors.toSet());

		Queue<Integer> Q = new ArrayDeque<>(reachable);
		while(!Q.isEmpty()) {
			int i = Q.remove();

			for (Map.Entry<String, InterValue> entry : heap.getCell(i).entrySet()) {
				InterValue value = entry.getValue();
				TypeElement type = value.type;
				if (!type.maybePointer()) continue;

				for(Integer nptr : reachableFromValue(value))
					if(reachable.add(nptr))
						Q.add(nptr);
			}
		}

		return reachable;
	}

	private Set<Integer> reachableFromValue(InterValue value) {
		PointerElement ptr = value.pointer;
		if(ptr == PointerElement.NULL || ptr == PointerElement.uTOP) return Collections.emptySet();
		else if(!ptr.isValid()) {
			TypeElement type = value.type;

			Predicate<String> matchpred = type.isPrecise() ? type.getType().getInternalName()::equals :
					s -> Utils.getAncestors(Type.getObjectType(s)).contains(type.getType());

			return heap.keySet().stream()
					.filter(i -> matchpred.test(heap.getCell(i).getName()))
					.collect(Collectors.toSet());
		} else try {
			return Collections.singleton(ptr.pointsTo());
		} catch(AnalyzerException e) { throw new RuntimeException("Cannot happen???", e); }
	}
}
