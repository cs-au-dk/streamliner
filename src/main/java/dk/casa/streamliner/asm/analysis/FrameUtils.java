package dk.casa.streamliner.asm.analysis;

import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Value;

public class FrameUtils {
	public static <V extends Value> int stackTop(Frame<V> frame) {
		return frame.getLocals() + frame.getStackSize() - 1;
	}

	public static <V extends Value> V getStackTop(Frame<V> frame) {
		return getValue(frame, stackTop(frame));
	}

	public static <V extends Value> V getValue(Frame<V> frame, int index) {
		if(index < frame.getLocals()) return frame.getLocal(index);
		return frame.getStack(index - frame.getLocals());
	}

	public static <V extends Value> void setValue(Frame<V> frame, int index, V value) {
		if(index < frame.getLocals()) frame.setLocal(index, value);
		else frame.setStack(index - frame.getLocals(), value);
	}
}
