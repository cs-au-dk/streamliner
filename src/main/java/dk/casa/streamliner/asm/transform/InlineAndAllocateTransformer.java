package dk.casa.streamliner.asm.transform;

import dk.casa.streamliner.NotImplementedException;
import dk.casa.streamliner.asm.ClassNodeCache;
import dk.casa.streamliner.asm.InlineMethod;
import dk.casa.streamliner.asm.Utils;
import dk.casa.streamliner.asm.analysis.FlatElement;
import dk.casa.streamliner.asm.analysis.InstructionStackEffect;
import dk.casa.streamliner.asm.analysis.constant.ConstantEvaluator;
import dk.casa.streamliner.asm.analysis.inter.*;
import dk.casa.streamliner.asm.analysis.inter.oracles.Oracle;
import dk.casa.streamliner.asm.analysis.pointer.AbstractObject;
import dk.casa.streamliner.asm.analysis.pointer.AbstractPointer;
import dk.casa.streamliner.asm.comments.CommentNode;
import org.apache.commons.math3.util.Pair;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.util.Textifier;

import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.objectweb.asm.Opcodes.*;

public class InlineAndAllocateTransformer {
	private final String owner;
	private final MethodNode method;
	private final Oracle oracle;
	private final Map<Integer, Map<String, Integer>> stackAllocateMap = new HashMap<>();

	private final LabelNode methodStart = new LabelNode(), methodEnd = new LabelNode();
	private final PreTransformAnalysis preAnalysis;

	private static String getNestHost(String clazz) {
		ClassNode cn = ClassNodeCache.get(clazz);
		return cn.nestHostClass == null? clazz : cn.nestHostClass;
	}
	// Could be provided as a constructor argument by the client to specialise behaviour.
	// Currently generates reflection for spliterator accesses to nest class fields
	private static final ContextFieldPredicate generateReflectiveAccess =
			(Context ctxt, FieldInsnNode finsn) -> {
				MethodNode mn = ctxt.getMethod();
				if((mn.access & ACC_STATIC) != 0) return false;
				if(mn.name.equals("spliterator")) return true;

				// Allow inlining of forEach in SortedOps sinks
				/*
				if(ctxt.getOwner().equals("java/util/ArrayList") && mn.name.equals("forEach")
				   && ctxt.getParent().getOwner().equals("java/util/stream/SortedOps$RefSortingSink"))
					return true;
				 */

				InterValue thisValue = ctxt.getArguments().get(0);

				// Field access happens inside spliterator method
				return Utils.getAncestors(thisValue.type.getType()).contains(Type.getObjectType("java/util/Spliterator"))
						// Accessed field is in the same nest as the spliterator
						&& getNestHost(finsn.owner).equals(getNestHost(ctxt.getOwner()));
			};

	/**
	 * @param owner Name of the class to transform.
	 * @param method Method to transform.
	 * @param oracle Analysis oracle.
	 * @param verifyTransformable If true, throws an exception if there are stream methods that cannot be transformed.
	 */
	public InlineAndAllocateTransformer(String owner, MethodNode method, Oracle oracle, boolean verifyTransformable) {
		this.owner = owner;
		this.method = method;
		this.oracle = oracle;

		preAnalysis = new PreTransformAnalysis(owner, generateReflectiveAccess, verifyTransformable);
	}

	public void transform() throws AnalyzerException {
		Context initialContext = InterproceduralTypePointerAnalysis.startAnalysis(owner, method, oracle);
		Map<Integer, Integer> idMap = IntStream.range(0, method.maxLocals).boxed()
				.collect(Collectors.toMap(Function.identity(), Function.identity()));

		// Fetch escaped set
		InterFrame[] frames = InterproceduralTypePointerAnalysis.calls.get(initialContext);
		InterFrame retFrame = Utils.getReturnFrame(method, frames, new InterInterpreter(), InterFrame::new);
		if(retFrame == null) throw new RuntimeException("No return instruction in method");

		Set<Integer> escapedSet = new HashSet<>(retFrame.getHeap().getEscaped());
		// TODO: Work-around since we don't model exceptional flow
		IntStream.range(0, method.instructions.size())
				.filter(i -> method.instructions.get(i).getOpcode() == ATHROW)
				.forEach(i -> escapedSet.addAll(frames[i].getHeap().getEscaped()));

		preAnalysis.run(initialContext, escapedSet);

		System.err.println("Locals before transform: " + method.maxLocals);
		recursiveTransform(initialContext, idMap, null, 0);

		method.instructions.insert(methodStart);
		method.instructions.add(methodEnd);

		InterproceduralTypePointerAnalysis.reset();
	}

	private boolean isException(ClassNode c) {
		while(c.superName != null) {
			if(c.name.equals("java/lang/Throwable")) return true;
			c = ClassNodeCache.get(c.superName);
		}
		return false;
	}

	/** Instance fields have a default value which we have to preserve when stack allocating */
	private void setFieldDefault(InsnList insns, AbstractInsnNode insertBefore, Type fieldType,
	                             int localIndex, int currentStackHeight) {
		// We might need to allocate more space if one of the fields is a DOUBLE or LONG
		method.maxStack = Math.max(method.maxStack, currentStackHeight + fieldType.getSize());

		int opcode;
		switch(fieldType.getSort()) {
			case Type.BOOLEAN:
			case Type.BYTE:
			case Type.CHAR:
			case Type.SHORT:
			case Type.INT:
				opcode = ICONST_0;
				break;

			case Type.FLOAT:
				opcode = FCONST_0;
				break;

			case Type.DOUBLE:
				opcode = DCONST_0;
				break;

			case Type.LONG:
				opcode = LCONST_0;
				break;

			case Type.ARRAY:
			case Type.OBJECT:
				opcode = ACONST_NULL;
				break;

			default:
				throw new RuntimeException("Unsupported Type sort: " + fieldType.getSort());
		}

		insns.insertBefore(insertBefore, new InsnNode(opcode));
		insns.insertBefore(insertBefore, new VarInsnNode(fieldType.getOpcode(ISTORE), localIndex));
	}

	// Map from field name to local variable
	private final Map<String, Integer> reflectiveFields = new HashMap<>();
	private static final Type classType = Type.getObjectType("java/lang/Class");
	private static final Type stringType = Type.getObjectType("java/lang/String");
	private static final Type rFieldType = Type.getObjectType("java/lang/reflect/Field");
	private int getReflectiveField(FieldInsnNode finsn) {
		Pair<String, FieldNode> res = Utils.resolveField(finsn.owner, finsn.name, finsn.desc).orElseThrow(NoSuchElementException::new);
		String owner = res.getFirst();
		FieldNode field = res.getSecond();
		String name = owner + "." + field.name; // See InterInterpreter

		return reflectiveFields.computeIfAbsent(name, unused -> {
			int local = method.maxLocals++;
			method.maxStack = Math.max(method.maxStack, 3);

			method.localVariables.add(new LocalVariableNode(field.name + "_field", rFieldType.getDescriptor(), null, methodStart, methodEnd, local));

			// Set up Field for reflective access
			InsnList insns = new InsnList();
			Utils.addInstructions(insns,
					new LdcInsnNode(owner.replace("/", ".")),
					new MethodInsnNode(INVOKESTATIC, "java/lang/Class", "forName",
							Type.getMethodDescriptor(classType, stringType), false),
					new LdcInsnNode(field.name),
					new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField",
							Type.getMethodDescriptor(rFieldType, stringType), false),
					new InsnNode(DUP),
					new InsnNode(ICONST_1),
					new MethodInsnNode(INVOKEVIRTUAL, "java/lang/reflect/Field", "setAccessible",
							Type.getMethodDescriptor(Type.VOID_TYPE, Type.BOOLEAN_TYPE), false),
					new VarInsnNode(ASTORE, local));

			method.instructions.insert(insns);
			return local;
		});
	}


	private void recursiveTransform(Context context, Map<Integer, Integer> vars,
	                                LabelNode endLabel, int initialStackHeight) throws AnalyzerException {

		MethodNode mth = context.getMethod();
		InterFrame[] frames = InterproceduralTypePointerAnalysis.calls.get(context);
		InsnList instructions = mth.instructions;

		if(instructions.size() != frames.length)
			throw new RuntimeException("bug!");

		AbstractInsnNode[] insns = instructions.toArray();
		for(int i = 0; i < insns.length; i++) {
			AbstractInsnNode insn = insns[i];
			int opcode = insn.getOpcode();

			if(frames[i] == null || frames[i].isUnreachable()) {
				if(!(insn instanceof LabelNode)) // unreachable
					instructions.remove(insn);

				continue;
			}

			if(insn instanceof VarInsnNode) {
				VarInsnNode vinsn = (VarInsnNode) insn;
				InterValue value = frames[i].getLocal(vinsn.var);

				// We load constant null if the pointer is guaranteed to be null of if the pointed-to value is stack allocated
				if(opcode == ALOAD && (value.pointer == PointerElement.NULL || value.isValid() && stackAllocateMap.containsKey(value.pointsTo())))
					instructions.set(insn, new InsnNode(ACONST_NULL));
				else if(Utils.isLoad(opcode) && opcode != ALOAD && value.constant.isDefined())
					instructions.set(insn, loadConstant(opcode, value.constant.value));
				else // just remap
					vinsn.var = vars.get(vinsn.var);
			} else if(insn instanceof IincInsnNode) {
				IincInsnNode iinsn = (IincInsnNode) insn;
				iinsn.var = vars.get(iinsn.var);
			} else if(opcode >= IRETURN && opcode <= RETURN) {
				if (endLabel != null)
					instructions.set(insn, new JumpInsnNode(GOTO, endLabel));
			} else if(insn instanceof FrameNode) {
				// We have to recompute these later
				instructions.remove(insn);
			} else if(insn instanceof MethodInsnNode) {
				performInlining(context, (MethodInsnNode) insn, i, instructions, frames[i], initialStackHeight);
			} else if(opcode == NEW) { // Stack allocation stuff
				TypeInsnNode tinsn = (TypeInsnNode) insn;
				int allocIndex = InterproceduralTypePointerAnalysis.getAllocationIndex(context, i);
				if(!preAnalysis.canStackAllocate(allocIndex)) continue;

				ClassNode c = ClassNodeCache.get(tinsn.desc);
				if(isException(c)) continue;

				// Create new locals for the instance fields
				Map<String, Integer> fieldMap = new HashMap<>();
				for(Pair<String, FieldNode> pair : Utils.getFields(c.name, fn -> (fn.access & ACC_STATIC) == 0)) {
					FieldNode fn = pair.getSecond();
					int index = method.maxLocals;
					Type fieldType = Type.getType(fn.desc);
					method.maxLocals += fieldType.getSize();
					method.localVariables.add(new LocalVariableNode(fn.name, fn.desc, fn.signature, methodStart, methodEnd, index));
					setFieldDefault(instructions, insn, fieldType, index, initialStackHeight + frames[i].getStackSize());
					fieldMap.put(fn.name, index);
				}

				stackAllocateMap.put(allocIndex, fieldMap);
				instructions.set(insn, new InsnNode(ACONST_NULL));
			} else if(insn instanceof FieldInsnNode) {
				FieldInsnNode finsn = (FieldInsnNode) insn;

				boolean get = opcode == GETFIELD;
				if(get || opcode == PUTFIELD) {
					InterFrame frame = frames[i];
					AbstractPointer ptrValue = frame.getStack(frame.getStackSize() - (get ? 1 : 2));
					if (!ptrValue.isValid()) {
						if(generateReflectiveAccess.test(context, finsn)) {
							if(!get) throw new NotImplementedException("reflective PUTFIELD not implemented");

							int fieldLocal = getReflectiveField(finsn);
							instructions.insertBefore(finsn, new VarInsnNode(ALOAD, fieldLocal));
							instructions.insertBefore(finsn, new InsnNode(SWAP));

							Type fieldType = Type.getType(finsn.desc);

							int sort = fieldType.getSort();
							boolean isPrimitive = sort != Type.OBJECT && sort != Type.ARRAY;
							Type returnType = isPrimitive? fieldType : Type.getObjectType("java/lang/Object");
							String getterName;
							switch (sort) {
								case Type.INT: getterName = "Int"; break;

								case Type.OBJECT:
								case Type.ARRAY:
									getterName = "";
									break;

								default: throw new NotImplementedException("Reflective getter for " + fieldType + " not implemented");
							}

							instructions.insertBefore(finsn, new MethodInsnNode(INVOKEVIRTUAL, rFieldType.getInternalName(), "get" + getterName,
									Type.getMethodDescriptor(returnType, Type.getObjectType("java/lang/Object")), false));

							if(isPrimitive || sort == Type.OBJECT) instructions.remove(finsn);
							else // TODO: This is quite fragile. Ideally we would like to cast objects and arrays to their correct
								 //     type, but it causes IllegalAccessErrors since the classes are private.
								 //     This works as long as we access fields on the returned objects via. reflection and don't call any methods on them.
								instructions.set(finsn, new TypeInsnNode(CHECKCAST, "[Ljava/lang/Object;"/*fieldType.getInternalName()*/));
						}

						continue;
					}

					int allocIndex = ptrValue.pointsTo();
					AbstractObject<InterValue> cell = frame.getHeap().getCell(allocIndex);
					int varOpcode = Type.getType(finsn.desc).getOpcode(get ? ILOAD : ISTORE);

					// We can load a constant if it is known
					InterValue fieldValue = cell.getField(InterInterpreter.getFieldName(finsn));
					if(get && fieldValue.constant.isDefined()) {
						instructions.insertBefore(insn, new InsnNode(POP));
						instructions.set(insn, loadConstant(varOpcode, fieldValue.constant.value));
					} else if(get && finsn.owner.equals("java/util/stream/FindOps$FindOp") && finsn.name.equals("emptyValue")) {
						// Transform illegal accesses to FindOp.emptyValue into the corresponding static Optional constructor
						TypeElement typ = cell.getField(InterInterpreter.getFieldName(finsn)).type;
						String optionName = typ.getType().getInternalName();
						instructions.insertBefore(insn, new InsnNode(POP));
						instructions.set(insn, new MethodInsnNode(INVOKESTATIC, optionName, "empty", Type.getMethodDescriptor(Type.getObjectType(optionName)), false));
					} else if(stackAllocateMap.containsKey(allocIndex)) {
						Map<String, Integer> fieldMap = stackAllocateMap.get(allocIndex);
						int localIndex = fieldMap.get(finsn.name);

						if (get) {
							// Pop the this reference
							instructions.insertBefore(insn, new InsnNode(POP));
							instructions.set(insn, new VarInsnNode(varOpcode, localIndex));
						} else { // store (value is at top of the stack)
							instructions.insertBefore(insn, new VarInsnNode(varOpcode, localIndex));
							// Pop the this reference
							instructions.set(insn, new InsnNode(POP));
						}
					}
				} else if(opcode == GETSTATIC) {
					// TODO: This is a specialised workaround for problematic access modifiers for streams.
					if(finsn.owner.equals("java/util/stream/StreamOpFlag") && Type.getType(finsn.desc) == Type.INT_TYPE) {
						ClassNode cn = ClassNodeCache.get("java/util/stream/StreamOpFlag");
						cn.fields.stream().filter(fn -> fn.name.equals(finsn.name)).findAny().ifPresent(fn -> {
							// If the field is both static and final we try to load it with reflection
							int requiredMask = ACC_STATIC | ACC_FINAL;
							if((fn.access & requiredMask) == requiredMask) {
								try {
									Field field = Class.forName("java.util.stream.StreamOpFlag").getDeclaredField(fn.name);
									field.setAccessible(true);
									int value = field.getInt(null);
									instructions.set(finsn, loadConstant(ILOAD, value));
								} catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
									throw new RuntimeException(e);
								}
							}
						});
					}
				}
			} else if(insn instanceof JumpInsnNode)
				// Transform known branches
				evaluateBranch(instructions, (JumpInsnNode) insn, frames[i]);
		}
	}

	private static AbstractInsnNode loadConstant(int opcode, Number constant) {
		switch(opcode) {
			case ILOAD:
				int i = constant.intValue();
				switch(i) {
					case -1: return new InsnNode(ICONST_M1);
					case 0: return new InsnNode(ICONST_0);
					case 1: return new InsnNode(ICONST_1);
					case 2: return new InsnNode(ICONST_2);
					case 3: return new InsnNode(ICONST_3);
					case 4: return new InsnNode(ICONST_4);
					case 5: return new InsnNode(ICONST_5);
				}

				if(-(1 << 7) <= i && i < (1 << 7))
					return new IntInsnNode(BIPUSH, i);
				else if(-(1 << 15) <= i && i < (1 << 15))
					return new IntInsnNode(SIPUSH, i);
				else
					return new LdcInsnNode(i);

			case LLOAD: return new LdcInsnNode(constant.longValue());
			case FLOAD: return new LdcInsnNode(constant.floatValue());
			case DLOAD: return new LdcInsnNode(constant.doubleValue());
		}

		throw new RuntimeException("Unexpected LOAD opcode: " + Textifier.OPCODES[opcode]);
	}

	/** Tries to completely remove a branch instruction if the target is known */
	private void evaluateBranch(InsnList instructions, JumpInsnNode insn, InterFrame frame) {
		int opcode = insn.getOpcode();
		LabelNode target = null;

		int numArgs = InstructionStackEffect.computeConsProd(insn, frame).consumed;
		List<InterValue> args = IntStream.range(0, numArgs)
				.mapToObj(i -> frame.getStack(frame.getStackSize() - numArgs + i))
				.collect(Collectors.toList());

		switch(opcode) {
			case GOTO: return;

			case IFNONNULL:
			case IFNULL:
				if(args.get(0).pointer == PointerElement.NULL) {
					if(opcode == IFNULL) target = insn.label;
				} else if(!args.get(0).isValid()) return;
				else if(opcode == IFNONNULL) target = insn.label;
				break;

			case IF_ACMPEQ:
			case IF_ACMPNE:
				if(!args.get(0).isValid() || !args.get(1).isValid()) return;
				int ptr1 = args.get(0).pointer.value,
					ptr2 = args.get(1).pointer.value;

				if(opcode == IF_ACMPEQ) {
					if(ptr1 == ptr2) target = insn.label;
				} else if(ptr1 != ptr2) target = insn.label;
				break;

			default:
				// Try constant propagation information
				List<FlatElement<Number>> constants = args.stream().map(v -> v.constant).collect(Collectors.toList());
				Optional<Boolean> res = ConstantEvaluator.branchOperation(insn, constants);

				if(!res.isPresent()) { simplifyBranch(instructions, insn, constants); return; }
				if(res.get()) target = insn.label;
				break;
		}

		if(numArgs == 0 || numArgs > 2) throw new RuntimeException("Unexpected");
		instructions.insertBefore(insn, new InsnNode(numArgs == 1 ? POP : POP2));

		if(target != null)
			instructions.set(insn, new JumpInsnNode(GOTO, target));
		else
			instructions.remove(insn);
	}

	/** Tries to simplify a ICMP branch if one of the arguments is known to be zero */
	private static void simplifyBranch(InsnList instructions, JumpInsnNode insn, List<FlatElement<Number>> args) {
		if(insn.getOpcode() < IF_ICMPEQ || insn.getOpcode() > IF_ICMPLE) return;
		FlatElement<Number> v1 = args.get(0), v2 = args.get(1);

		// 0 ICMP_GE x is equivalent to x IFLE (if lhs is 0 the comparison operator direction must be flipped)
		boolean doFlip = v1.isDefined() && v1.value.intValue() == 0;
		if(!doFlip && !(v2.isDefined() && v2.value.intValue() == 0)) return;

		int opcode;
		switch(insn.getOpcode()) {
			case IF_ICMPEQ: opcode = IFEQ; break;
			case IF_ICMPNE: opcode = IFNE; break;
			case IF_ICMPGE: opcode = doFlip? IFLE : IFGE; break;
			case IF_ICMPGT: opcode = doFlip? IFLT : IFGT; break;
			case IF_ICMPLE: opcode = doFlip? IFGE : IFLE; break;
			case IF_ICMPLT: opcode = doFlip? IFGT : IFLT; break;
			default: throw new RuntimeException("Should not happen");
		}

		if(doFlip) instructions.insertBefore(insn, new InsnNode(SWAP)); // Get the zero on top of the stack
		instructions.insertBefore(insn, new InsnNode(POP));
		instructions.set(insn, new JumpInsnNode(opcode, insn.label));
	}

	private void performInlining(Context context, MethodInsnNode minsn, int insnIndex, InsnList instructions,
	                             InterFrame frame, int initialStackHeight) throws AnalyzerException {

		Set<Integer> calls = InterproceduralTypePointerAnalysis.analysedCalls.get(context);
		if(calls == null || !calls.contains(insnIndex)) return;

		List<InterValue> argumentValues = Utils.getArgumentValues(minsn, frame);
		InlineMethod itarget = InterproceduralTypePointerAnalysis.resolveCall(minsn, argumentValues, frame.getHeap(), context);

		MethodNode target = itarget.mth;
		Context callContext = context.newContext(itarget.owner, target, insnIndex, frame.getHeap(), argumentValues);

		if((target.access & ACC_NATIVE) != 0 || !preAnalysis.canInline(callContext))
			return; // bail on native functions

		List<Type> args = new ArrayList<>(Arrays.asList(Type.getArgumentTypes(target.desc)));
		if(minsn.getOpcode() != INVOKESTATIC) {
			InterValue thisValue = argumentValues.get(0);
			// Do not inline constructor if object is not stack-allocated
			if(Utils.isConstructor(target) && (!thisValue.isValid() || !stackAllocateMap.containsKey(thisValue.pointsTo())))
				return;

			if(minsn.getOpcode() == INVOKESPECIAL)
				args.add(0, Type.getObjectType(minsn.owner));
			else
				args.add(0, Type.getObjectType(itarget.owner));
		}

		int argsSize = args.stream().mapToInt(Type::getSize).sum();
		int stackSize = IntStream.range(0, frame.getStackSize())
						.map(x -> frame.getStack(x).getSize()).sum();

		int newStackHeight = initialStackHeight + stackSize - argsSize;
		method.maxStack = Math.max(method.maxStack, newStackHeight + target.maxStack);

		LabelNode endLabelNode = new LabelNode();

		// Create new locals for parameters and callee locals
		Map<Integer, Integer> remappedLocals = new HashMap<>();
		for (int i = 0; i < target.maxLocals; i++)
			remappedLocals.put(i, method.maxLocals + i);

		method.maxLocals += target.maxLocals;

		// Copy LocalVariableNodes
		for(LocalVariableNode lvn : target.localVariables)
			method.localVariables.add(new LocalVariableNode(lvn.name, lvn.desc, lvn.signature, methodStart, endLabelNode, remappedLocals.get(lvn.index)));

		instructions.insertBefore(minsn, new CommentNode("Storing arguments in locals..."));

		// Store parameters in new local variables
		for(int localIndex = argsSize, // Handle doubles and longs
			    j = args.size() - 1; j >= 0; j--) {
			Type arg = args.get(j);
			localIndex -= arg.getSize();

			// See TestInterproc.testDowncasting
			// TODO: Measure impact on code size
			// TODO: Let recursiveTransform return whether the CHECKCAST is needed
			//  (untransformed and non-reflected field access to this)
			if(j == 0 && minsn.getOpcode() != INVOKESTATIC)
				instructions.insertBefore(minsn, new TypeInsnNode(CHECKCAST, args.get(0).getInternalName()));

			instructions.insertBefore(minsn, new VarInsnNode(arg.getOpcode(ISTORE), remappedLocals.get(localIndex)));
		}

		instructions.insertBefore(minsn, new CommentNode(String.format("Start of inlined %s.%s", itarget.owner, target.name)));

		recursiveTransform(callContext, remappedLocals, endLabelNode, newStackHeight);

		instructions.insertBefore(minsn, target.instructions);
		instructions.insertBefore(minsn, endLabelNode);
		instructions.insertBefore(minsn, new CommentNode(String.format("End of inlined %s.%s", itarget.owner, target.name)));
		instructions.remove(minsn);
	}
}
