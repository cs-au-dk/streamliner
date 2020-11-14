package dk.casa.streamliner.asm.analysis.unused;

import org.objectweb.asm.tree.analysis.Frame;

import java.util.HashMap;

public class PtrFrame extends Frame<PointsToValue> {
	private HashMap<Integer, HashMap<String, PointsToValue>> cells = new HashMap<>();

	public PtrFrame(int numLocals, int numStack) {
		super(numLocals, numStack);
	}

	public PtrFrame(Frame<PointsToValue> frame) {
		super(frame);
	}
}
