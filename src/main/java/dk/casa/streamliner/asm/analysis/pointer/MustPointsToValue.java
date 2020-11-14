package dk.casa.streamliner.asm.analysis.pointer;

import dk.casa.streamliner.asm.analysis.FlatElement;
import dk.casa.streamliner.asm.analysis.GenericValue;
import org.objectweb.asm.tree.analysis.AnalyzerException;

import java.util.Objects;

public class MustPointsToValue extends GenericValue<FlatElement<Integer>> implements AbstractPointer {
	public MustPointsToValue(int size, FlatElement<Integer> value) {
		super(size, value);
	}

	@Override
	public boolean isValid() {
		return value.isDefined();
	}

	public int pointsTo() throws AnalyzerException {
		if(!value.isDefined())
			throw new AnalyzerException(null, "Value in invalid state for pointsTo lookup!");
		return Objects.requireNonNull(value.value);
	}
}
