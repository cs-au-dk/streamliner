package dk.casa.streamliner.asm.analysis.inter.oracles;

import dk.casa.streamliner.asm.analysis.inter.Context;
import dk.casa.streamliner.asm.analysis.inter.InterValue;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.Optional;

public class CombinedOracle implements Oracle {
	private final AnalyseCallOracle analyseCallOracle;
	private final TrackAllocationsOracle trackAllocationsOracle;
	private final TypeQueryOracle typeQueryOracle;

	public CombinedOracle(AnalyseCallOracle analyseCallOracle, TrackAllocationsOracle trackAllocationsOracle, TypeQueryOracle typeQueryOracle) {
		this.analyseCallOracle = analyseCallOracle;
		this.trackAllocationsOracle = trackAllocationsOracle;
		this.typeQueryOracle = typeQueryOracle;
	}

	@Override
	public boolean shouldAnalyseCall(Context context, MethodInsnNode minsn) {
		return analyseCallOracle.shouldAnalyseCall(context, minsn);
	}

	@Override
	public boolean shouldTrackAllocation(Context context, Type type) {
		return trackAllocationsOracle.shouldTrackAllocation(context, type);
	}

	@Override
	public Optional<Type> queryType(Context context, MethodInsnNode minsn, InterValue receiver) {
		return typeQueryOracle.queryType(context, minsn, receiver);
	}
}
