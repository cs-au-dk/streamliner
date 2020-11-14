package dk.casa.streamliner.asm.analysis.inter.oracles;

import dk.casa.streamliner.asm.analysis.inter.Context;
import org.objectweb.asm.Type;

@FunctionalInterface
public interface TrackAllocationsOracle {
	boolean shouldTrackAllocation(Context context, Type type);
}
