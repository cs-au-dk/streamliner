package dk.casa.streamliner.asm.analysis.nullness;

import dk.casa.streamliner.asm.analysis.SizeHelper;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Interpreter;

import java.util.List;

import static org.objectweb.asm.Opcodes.*;

public class NullnessInterpreter extends Interpreter<NullnessValue> {
    /** A lot of the code (for size computation) is copied from SourceInterpreter */
    public NullnessInterpreter() {
        super(ASM7);
    }

    @Override
    public NullnessValue newValue(Type type) {
        if(type == Type.VOID_TYPE) return null;

        return new NullnessValue(type == null ? 1 : type.getSize());
    }

    @Override
    public NullnessValue newOperation(AbstractInsnNode insn) {
        int size = SizeHelper.newOperation(insn);
        return new NullnessValue(size, insn.getOpcode() == ACONST_NULL);
    }

    @Override
    public NullnessValue copyOperation(AbstractInsnNode insn, NullnessValue value) {
        return value;
    }

    @Override
    public NullnessValue unaryOperation(AbstractInsnNode insn, NullnessValue value) {
        int size = SizeHelper.unaryOperation(insn);

        // Preserve information for CHECKCAST
        return new NullnessValue(size, value.isNull() && insn.getOpcode() == CHECKCAST);
    }

    @Override
    public NullnessValue binaryOperation(AbstractInsnNode insn, NullnessValue value1, NullnessValue value2) {
        return new NullnessValue(SizeHelper.binaryOperation(insn));
    }

    @Override
    public NullnessValue ternaryOperation(AbstractInsnNode insn, NullnessValue value1, NullnessValue value2, NullnessValue value3) {
        return new NullnessValue(SizeHelper.ternaryOperation(insn));
    }

    @Override
    public NullnessValue naryOperation(AbstractInsnNode insn, List<? extends NullnessValue> values) {
        return new NullnessValue(SizeHelper.naryOperation(insn));
    }

    @Override
    public void returnOperation(AbstractInsnNode insn, NullnessValue value, NullnessValue expected) {

    }

    @Override
    public NullnessValue merge(NullnessValue value1, NullnessValue value2) {
        if(value1.equals(value2)) return value1;

        return new NullnessValue(Math.min(value1.getSize(), value2.getSize()));

    }
}
