package dk.casa.streamliner.asm.transform;

import dk.casa.streamliner.asm.Utils;
import dk.casa.streamliner.asm.analysis.LivenessAnalysis;
import dk.casa.streamliner.asm.analysis.alias.MustEqualsAnalyzer;
import dk.casa.streamliner.asm.analysis.alias.MustEqualsFrame;
import dk.casa.streamliner.asm.analysis.nullness.NullnessInterpreter;
import dk.casa.streamliner.asm.analysis.nullness.NullnessValue;
import dk.casa.streamliner.asm.comments.CommentNode;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.objectweb.asm.util.Textifier;

import java.util.*;

import static org.objectweb.asm.Opcodes.*;

public class LocalVariableCleanup {
    private final String owner;
    private final MethodNode mn;

    public LocalVariableCleanup(String owner, MethodNode mn) {
        this.owner = owner;
        this.mn = mn;
    }

    private void replaceLabel(LabelNode oldLabel, LabelNode newLabel) {
        for(LocalVariableNode lvn : mn.localVariables) {
            if(lvn.start.equals(oldLabel)) lvn.start = newLabel;
            if(lvn.end.equals(oldLabel)) lvn.end = newLabel;
        }

        for(TryCatchBlockNode tcbn : mn.tryCatchBlocks) {
            if(tcbn.start.equals(oldLabel)) tcbn.start = newLabel;
            if(tcbn.end.equals(oldLabel)) tcbn.end = newLabel;
            if(tcbn.handler.equals(oldLabel)) tcbn.handler = newLabel;
        }

        for (AbstractInsnNode insn : mn.instructions) {
            if (insn instanceof LineNumberNode) {
                LineNumberNode ln = (LineNumberNode) insn;
                if (ln.start.equals(oldLabel))
                    ln.start = newLabel;
            } else if (insn instanceof JumpInsnNode) {
                JumpInsnNode jmp = (JumpInsnNode) insn;
                if (jmp.label.equals(oldLabel))
                    jmp.label = newLabel;
            } else {
                List<LabelNode> labels;
                if(insn instanceof TableSwitchInsnNode) {
                    TableSwitchInsnNode tinsn = (TableSwitchInsnNode) insn;
                    if(tinsn.dflt.equals(oldLabel))
                        tinsn.dflt = newLabel;
                    labels = tinsn.labels;
                } else if(insn instanceof LookupSwitchInsnNode) {
                    LookupSwitchInsnNode linsn = (LookupSwitchInsnNode) insn;;
                    if(linsn.dflt.equals(oldLabel))
                        linsn.dflt = newLabel;
                    labels = linsn.labels;
                } else continue;

                labels.replaceAll(label -> label.equals(oldLabel) ? newLabel : label);
            }
        }
    }

    private static int requiredSize(AbstractInsnNode insn) {
        switch(insn.getOpcode()) {
            case ALOAD:
            case ASTORE:
            case ILOAD:
            case ISTORE:
            case FLOAD:
            case FSTORE:
            case IINC:
                return 1;

            case LLOAD:
            case LSTORE:
            case DLOAD:
            case DSTORE:
                return 2;
        }

        throw new RuntimeException("Unexpected instruction: " + Textifier.OPCODES[insn.getOpcode()]);
    }

    private static InsnNode deadLoad(VarInsnNode insn) {
        int constCode;
        switch(insn.getOpcode()) {
            case ILOAD: constCode = ICONST_0; break;
            case ALOAD: constCode = ACONST_NULL; break;
            case FLOAD: constCode = FCONST_0; break;
            case LLOAD: constCode = LCONST_0; break;
            case DLOAD: constCode = DCONST_0; break;
            default: throw new RuntimeException("Unexpected opcode");
        }
        return new InsnNode(constCode);
    }

    /** Compact locals to readSet */
    private void remapLocals(Set<Integer> readSet, Map<Integer, Integer> requiredSizes) {
        Map<Integer, Integer> newRequiredSizes = new HashMap<>();

        // Remapping
        List<LocalVariableNode> newLocals = new ArrayList<>();
        Map<Integer, Integer> remap = new HashMap<>();
        int argumentSizes = (Type.getArgumentsAndReturnSizes(mn.desc) >> 2) - ((mn.access & ACC_STATIC) == 0 ? 0 : 1);
        int localsSize = argumentSizes;
        for(int i : readSet) {
            int newLocal = i;
            if(i >= argumentSizes) {
                newLocal = localsSize;
                localsSize += requiredSizes.get(i);
            }

            remap.put(i, newLocal);
            newRequiredSizes.put(newLocal, requiredSizes.get(i));

            Optional<LocalVariableNode> oldVariable = mn.localVariables.stream()
                    .filter(v -> v.index == i).findAny();
            if(oldVariable.isPresent()) {
                LocalVariableNode lvn = oldVariable.get();
                newLocals.add(new LocalVariableNode(lvn.name, lvn.desc, lvn.signature, lvn.start, lvn.end, newLocal));
            }
        }

        System.err.format("Reduced number of locals from %s to %s\n", mn.maxLocals, localsSize);
        mn.localVariables = newLocals;
        mn.maxLocals = localsSize;

        ListIterator<AbstractInsnNode> it = mn.instructions.iterator();
        while(it.hasNext()) {
            AbstractInsnNode insn = it.next();
            if(insn instanceof VarInsnNode) {
                VarInsnNode vinsn = (VarInsnNode) insn;
                if(!readSet.contains(vinsn.var)) {
                    int opcode = vinsn.getOpcode();
                    if(Utils.isLoad(opcode))
                        it.set(deadLoad(vinsn));
                    else
                        it.set(new InsnNode((opcode == DSTORE || opcode == LSTORE) ? POP2 : POP));
                } else
                    vinsn.var = remap.get(vinsn.var);
            } else if(insn instanceof IincInsnNode) {
                IincInsnNode iinsn = (IincInsnNode) insn;
                if(!readSet.contains(iinsn.var))
                    it.set(new InsnNode(NOP)); // Removing the instruction would mess with the CFG
                else
                    iinsn.var = remap.get(iinsn.var);
            }
        }

        requiredSizes.clear();
        requiredSizes.putAll(newRequiredSizes);
    }

    public void run() throws AnalyzerException {
        // Remove all comments
        Utils.removeInstructionsIf(mn, insn -> insn instanceof CommentNode);

        /*  We use a linear scan analysis to find variables that are never loaded.
            This can significantly reduce the number of local variables which greatly
            affects the performance of the MustEquals analysis. */
        Set<Integer> readSet = new HashSet<>();
        Map<Integer, Integer> requiredSizes = new HashMap<>();
        for(AbstractInsnNode insn : mn.instructions) {
            int var;
            if(insn instanceof VarInsnNode) var = ((VarInsnNode) insn).var;
            else if(insn instanceof IincInsnNode) var = ((IincInsnNode) insn).var;
            else continue;

            requiredSizes.merge(var, requiredSize(insn), Math::max);
            if(Utils.isLoad(insn.getOpcode())) readSet.add(var);
        }

        remapLocals(readSet, requiredSizes);
        /* We run the SlidingWindow optimizer for good measure. The remapping introduces some POP instructions that should be possible to remove. */
        SlidingWindowOptimizer.run(mn);

        if(mn.instructions.size() <= 20000) {
            // Clean up duplicated local variables with a must-equals analysis
            MustEqualsAnalyzer<NullnessValue> a = new MustEqualsAnalyzer<>(new NullnessInterpreter());
            a.analyze(owner, mn);
            List<AbstractInsnNode> insns = Arrays.asList(mn.instructions.toArray());

            LivenessAnalysis livenessAnalyzer = new LivenessAnalysis(a.getCFG());
            livenessAnalyzer.analyze(a.getFrames());

            // Transform reads to representative reads and compute readSet
            readSet.clear();
            for (int i = 0; i < insns.size(); i++) {
                AbstractInsnNode insn = insns.get(i);
                MustEqualsFrame<NullnessValue> f = a.getFrame(i);
                if (f == null) continue;

                if (insn instanceof VarInsnNode) {
                    VarInsnNode v = (VarInsnNode) insn;
                    if (Utils.isLoad(v.getOpcode())) {
                        NullnessValue loc = f.getLocal(v.var);

                        AbstractInsnNode newInsn;
                        if (loc.isNull())
                            newInsn = new InsnNode(ACONST_NULL);
                        else if (livenessAnalyzer.isDeadLoad(i, f))
                            newInsn = deadLoad(v);
                        else {
                            // Try to find an alias we have already read from
                            int repr = v.var;
                            for (int alias : f.aliasesOf(v.var))
                                if (readSet.contains(alias)) {
                                    repr = alias;
                                    break;
                                }

                            newInsn = new VarInsnNode(v.getOpcode(), repr);
                            readSet.add(repr);
                        }

                        mn.instructions.set(insn, newInsn);
                    }
                } else if (insn instanceof IincInsnNode)
                    readSet.add(((IincInsnNode) insn).var);
            }

            // System.err.println(String.format("Readset: %s (%s < %s)", readSet, readSet.size(), mn.maxLocals));
            remapLocals(readSet, requiredSizes);

            // TODO: Currently the analysis is only used for reachability so we could just replace it with any other analysis
            Analyzer<BasicValue> as = new Analyzer<>(new BasicInterpreter());
            as.analyze(owner, mn);
            Frame<?>[] sourceFrames = as.getFrames();

            livenessAnalyzer.analyze(sourceFrames); // Reanalyze with new locals

            insns = Arrays.asList(mn.instructions.toArray());
            for (int i = 0; i < insns.size(); i++) {
                AbstractInsnNode insn = insns.get(i);
                if (insn == null)
                    continue;
                else if (insn instanceof FrameNode) // Remove frames as they do not match our locals anymore - they must be recomputed
                    mn.instructions.remove(insn);
                else if (sourceFrames[i] == null) {
                    if (insn.getOpcode() >= 0)
                        mn.instructions.remove(insn); // unreachable
                    continue;
                }

                if (insn instanceof VarInsnNode) {
                    VarInsnNode v = (VarInsnNode) insn;
                    int opcode = v.getOpcode();

                    if (Utils.isLoad(opcode)) {
                        if (livenessAnalyzer.isDeadLoad(i, sourceFrames[i]))
                            mn.instructions.set(v, deadLoad(v));
                    } else if (opcode != RET && livenessAnalyzer.isDeadStore(i, v.var)) {
                        int popCode = (opcode == LSTORE || opcode == DSTORE) ? POP2 : POP;
                        mn.instructions.set(insn, new InsnNode(popCode));
                    }
                } else if (insn instanceof IincInsnNode) {
                    if(livenessAnalyzer.isDeadStore(i, ((IincInsnNode) insn).var))
                        mn.instructions.remove(insn);
                }
            }

            // TODO: With liveness information we can also eliminate redundant store-load pairs if the variable is not live after the load

        new SignTransformation(owner, mn).run();

        // Clean up labels
        // TODO: This can be done in one pass if you are clever
        boolean changed = true;
        while (changed) {
            changed = false;

                ListIterator<AbstractInsnNode> it = mn.instructions.iterator();
                while (it.hasNext()) {
                    AbstractInsnNode insn = it.next();

                    if (insn instanceof LabelNode) {
                        LabelNode l1 = (LabelNode) insn;
                        while (it.hasNext()) {
                            insn = it.next();
                            if (insn instanceof LabelNode) {
                                changed = true;
                                it.remove();
                                replaceLabel((LabelNode) insn, l1);
                            } else {
                                it.previous();
                                break;
                            }
                        }
                    } else if (insn instanceof LineNumberNode) {
                        LineNumberNode l1 = (LineNumberNode) insn;
                        LabelNode lab = l1.start;
                        while (it.hasNext()) {
                            insn = it.next();
                            if (insn instanceof LabelNode) {
                                changed = true;
                                it.remove();
                                replaceLabel((LabelNode) insn, lab);
                            } else if (insn instanceof LineNumberNode) {
                                changed = true;
                                it.remove();
                            } else {
                                it.previous();
                                break;
                            }
                        }
                    } else if (insn.getOpcode() == GOTO) {
                        // Remove GOTOs that jump to the next
                        // TODO: Replace with implementation that can handle series of GOTOs
                        JumpInsnNode jinsn = (JumpInsnNode) insn;
                        AbstractInsnNode next = jinsn.getNext();
                        while (next != null && next.getOpcode() < 0) {
                            if (jinsn.label.equals(next)) {
                                changed = true;
                                it.remove();
                                break;
                            }

                            next = next.getNext();
                        }
                    }
                }
            }

            // Remove all labels that are not targeted by jumps or localvariables
            Set<LabelNode> targets = new HashSet<>();
            for(LocalVariableNode lvn : mn.localVariables) {
                targets.add(lvn.start);
                targets.add(lvn.end);
            }

            for(TryCatchBlockNode tcbn : mn.tryCatchBlocks) {
                targets.add(tcbn.start);
                targets.add(tcbn.end);
                targets.add(tcbn.handler);
            }

            LabelNode currentLabel = null;
            for(AbstractInsnNode insn : mn.instructions) {
                if(insn instanceof JumpInsnNode)
                    targets.add(((JumpInsnNode) insn).label);
                else if(insn instanceof FrameNode) {
                    if (currentLabel != null) targets.add(currentLabel);
                } else if(insn instanceof LabelNode)
                    currentLabel = (LabelNode) insn;
                else if(insn instanceof TableSwitchInsnNode) {
                    TableSwitchInsnNode tinsn = (TableSwitchInsnNode) insn;
                    if (tinsn.dflt != null) targets.add(tinsn.dflt);
                    targets.addAll(tinsn.labels);
                } else if(insn instanceof LookupSwitchInsnNode) {
                    LookupSwitchInsnNode linsn = (LookupSwitchInsnNode) insn;
                    if(linsn.dflt != null) targets.add(linsn.dflt);
                    targets.addAll(linsn.labels);
                }
            }

            Utils.removeInstructionsIf(mn, insn -> (insn instanceof LabelNode && !targets.contains(insn))
                    || (insn instanceof LineNumberNode && !targets.contains(((LineNumberNode) insn).start)));

        } else
            System.err.println("Skipping additional cleanup as the method is too big.");
    }
}
