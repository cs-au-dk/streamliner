package dk.casa.streamliner.asm.analysis.pointer;

import org.objectweb.asm.tree.analysis.AnalyzerException;

public interface AbstractPointer {
	boolean isValid();
	int pointsTo() throws AnalyzerException;
}
