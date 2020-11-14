package dk.casa.streamliner.asm.analysis.alias;

import dk.casa.streamliner.asm.analysis.FrameUtils;
import dk.casa.streamliner.asm.analysis.InstructionStackEffect;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;
import org.objectweb.asm.util.Textifier;

import java.util.*;

import static org.objectweb.asm.Opcodes.*;

/** This code is mostly copied from Scala's analysis implementation
 *  https://github.com/scala/scala/blob/2.13.x/src/compiler/scala/tools/nsc/backend/jvm/analysis/AliasingAnalyzer.scala
 * */
public class MustEqualsFrame<V extends Value> extends Frame<V> {
	private final MustEqualsSet[] aliases;

	public MustEqualsFrame(int numLocals, int numStack) {
		super(numLocals, numStack);
		aliases = new MustEqualsSet[numLocals + numStack];
	}

	public MustEqualsFrame(Frame<? extends V> frame) {
		this(frame.getLocals(), frame.getMaxStackSize());
		init(frame);
	}

	public MustEqualsSet aliasesOf(int entry) {
		if(aliases[entry] != null) return aliases[entry];

		MustEqualsSet init = new MustEqualsSet();
		init.add(entry);
		aliases[entry] = init;
		return init;
	}

	/** a = unknown */
	private void removeAlias(int assignee) {
		if(aliases[assignee] != null) {
			aliases[assignee].remove(assignee);
			aliases[assignee] = null;
		}
	}

	/** b = a */
	private void newAlias(int assignee, int source) {
		removeAlias(assignee);
		MustEqualsSet sourceAliases = aliasesOf(source);
		sourceAliases.add(assignee);
		aliases[assignee] = sourceAliases;
	}

	@Override
	public void execute(AbstractInsnNode insn, Interpreter<V> interpreter) throws AnalyzerException {
		InstructionStackEffect.ConsProd cp = InstructionStackEffect.computeConsProd(insn, this);
		int consumed = cp.consumed;
		int produced = cp.produced;

		super.execute(insn, interpreter);

		int top = FrameUtils.stackTop(this);

		// Actual alias bookkeeping
		boolean isSize2;
		switch(insn.getOpcode()) {
			case ILOAD:
			case LLOAD:
			case FLOAD:
			case DLOAD:
			case ALOAD:
				newAlias(top, ((VarInsnNode) insn).var);
				break;

			case DUP:
				newAlias(top, top - 1);
				break;

			case DUP_X1:
				newAlias(top, top - 1);
				newAlias(top - 1, top - 2);
				newAlias(top - 2, top);
				break;

			case DUP_X2:
				isSize2 = getStack(getStackSize() - 2).getSize() == 2;
				newAlias(top, top - 1);
				newAlias(top - 1, top - 2);
				if (isSize2) {
					// Size 2 values on the stack only take one slot in the `values` array
					newAlias(top - 2, top);
				} else {
					newAlias(top - 2, top - 3);
					newAlias(top - 3, top);
				}
				break;

			case DUP2:
				isSize2 = getStack(getStackSize() - 1).getSize() == 2;
				if(isSize2)
					newAlias(top, top - 1);
				else {
					newAlias(top - 1, top - 3);
					newAlias(top, top - 2);
				}
				break;

			case DUP2_X1:
				isSize2 = getStack(getStackSize() - 1).getSize() == 2;
				if (isSize2) {
					newAlias(top, top - 1);
					newAlias(top - 1, top - 2);
					newAlias(top - 2, top);
				} else {
					newAlias(top, top - 2);
					newAlias(top - 1, top - 3);
					newAlias(top - 2, top - 4);
					newAlias(top - 4, top);
					newAlias(top - 5, top - 1);
				}
				break;

			case DUP2_X2:
				throw new IllegalArgumentException("MustEqualsFrame.execute not implemented for: " + Textifier.OPCODES[insn.getOpcode()]);

			case SWAP:
				Runnable moveNextToTop = () -> {
					MustEqualsSet next = aliases[top - 1];
					aliases[top] = next;
					next.remove(top - 1);
					next.add(top);
				};

				if(aliases[top] != null) {
					MustEqualsSet topAliases = aliases[top];
					if(aliases[top - 1] != null) moveNextToTop.run();
					else aliases[top] = null;
					aliases[top - 1] = topAliases;
					topAliases.remove(top);
					topAliases.add(top - 1);
				} else if(aliases[top - 1] != null) {
					moveNextToTop.run();
					aliases[top - 1] = null;
				}
				break;

			case IINC:
				removeAlias(((IincInsnNode) insn).var);
				break;

			default:
				switch(insn.getOpcode()) {
					case ISTORE:
					case LSTORE:
					case FSTORE:
					case DSTORE:
					case ASTORE:
						int topBefore = top - produced + consumed;
						int local = ((VarInsnNode) insn).var;
						newAlias(local, topBefore);

						// Match super.execute
						if (getLocal(local).getSize() == 2)
							removeAlias(local + 1);

						if (local > 0) {
							V prevValue = getLocal(local - 1);
							if (prevValue != null && prevValue.getSize() == 2)
								removeAlias(local - 1);
						}
						break;
				}

				// Remove aliasing for consumed values
				int firstConsumed = top - produced + 1;
				for(int i = 0; i < consumed; i++)
					removeAlias(firstConsumed + i);
		}
	}

	@Override
	public void clearStack() {
		for(int i = getLocals(); i < getLocals() + getStackSize(); i++)
			removeAlias(i);

		super.clearStack();
	}

	@Override
	public boolean merge(Frame<? extends V> frame, Interpreter<V> interpreter) throws AnalyzerException {
		boolean valuesChanged = super.merge(frame, interpreter);
		boolean aliasesChanged = false;

		@SuppressWarnings("unchecked")
		MustEqualsFrame<? extends V> aliasingOther = (MustEqualsFrame<V>) frame;
		int numValues = getLocals() + getStackSize();
		boolean[] knownOk = new boolean[numValues];
		for(int i = 0; i < numValues; i++) {
			if(knownOk[i]) continue;

			MustEqualsSet thisAliases = aliases[i];
			MustEqualsSet otherAliases = aliasingOther.aliases[i];
			if(thisAliases != null) {
				if(otherAliases == null) {
					if(thisAliases.size() > 1) {
						aliasesChanged = true;
						removeAlias(i);
					}
				} else {
					MustEqualsSet newSet = null;
					Iterator<Integer> iterator = thisAliases.iterator();
					while(iterator.hasNext()) {
						int j = iterator.next();
						if(otherAliases.contains(j)) knownOk[j] = true;
						else {
							aliasesChanged = true;
							if(newSet == null) newSet = new MustEqualsSet();
							newSet.add(j);
							iterator.remove();
							aliases[j] = newSet;
						}
					}
				}
			}
		}

		return valuesChanged || aliasesChanged;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Frame<V> init(Frame<? extends V> frame) {
		super.init(frame);
		// Copy aliases
		System.arraycopy(((MustEqualsFrame<V>) frame).aliases, 0, aliases, 0, aliases.length);
		boolean[] done = new boolean[aliases.length];

		for(int i = 0; i < aliases.length; i++) {
			if(aliases[i] == null || done[i]) continue;

			MustEqualsSet set = aliases[i];
			if(set.size() == 1)
				aliases[i] = null;
			else {
				MustEqualsSet newSet = new MustEqualsSet(set.size());
				for(int j : set) {
					newSet.add(j);
					done[j] = true;
					aliases[j] = newSet;
				}
			}
		}

		return this;
	}
}
