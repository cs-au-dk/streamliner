package dk.casa.streamliner.asm.analysis.inter.oracles;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.classLoader.ShrikeCTMethod;
import com.ibm.wala.demandpa.alg.DemandRefinementPointsTo;
import com.ibm.wala.demandpa.alg.refinepolicy.NeverRefineCGPolicy;
import com.ibm.wala.demandpa.alg.refinepolicy.NeverRefineFieldsPolicy;
import com.ibm.wala.demandpa.alg.refinepolicy.RefinementPolicyFactory;
import com.ibm.wala.demandpa.alg.refinepolicy.SinglePassRefinementPolicy;
import com.ibm.wala.demandpa.alg.statemachine.DummyStateMachine;
import com.ibm.wala.demandpa.alg.statemachine.StateMachineFactory;
import com.ibm.wala.demandpa.flowgraph.IFlowLabel;
import com.ibm.wala.demandpa.util.MemoryAccessMap;
import com.ibm.wala.demandpa.util.SimpleMemoryAccessMap;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.cha.CHACallGraph;
import com.ibm.wala.ipa.callgraph.impl.DefaultEntrypoint;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.HeapModel;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.types.Descriptor;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.Selector;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.collections.Pair;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.util.debug.UnimplementedError;
import com.ibm.wala.util.perf.Stopwatch;
import com.ibm.wala.util.strings.Atom;
import dk.casa.streamliner.asm.analysis.MethodIdentifier;
import dk.casa.streamliner.asm.analysis.inter.Context;
import dk.casa.streamliner.asm.analysis.inter.InterValue;
import dk.casa.streamliner.utils.Counter;
import dk.casa.streamliner.asm.Utils;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

import static com.ibm.wala.ipa.callgraph.impl.Everywhere.EVERYWHERE;

public class WALAOracle implements TypeQueryOracle {
	private boolean initialised = false;
	private boolean initialisedSuccessfully = false;

	private final List<File> libraries;
	private final List<String> directories;
	private final List<MethodIdentifier> entrypoints;

	public WALAOracle(Collection<File> libraries, Collection<String> directories, Collection<MethodIdentifier> entrypoints) {
		this.libraries = new ArrayList<>(libraries);
		this.directories = new ArrayList<>(directories);
		this.entrypoints = new ArrayList<>(entrypoints);
	}

	public final Counter<String> queryStats = new Counter<>(Arrays.asList(
			"total", "initSuccess", "initFail",
			"SUCCESS", "BUDGETEXCEEDED", "NOMOREREFINE",
			"AssertionError", "NullPointerException", "IllegalArgumentException", "CGNodeNull",
			"singleton", "multiple", "zero"
	));

	private AnalysisScope scope;
	private CHACallGraph chaCG;
	private HeapModel heapModel;
	private DemandRefinementPointsTo drpt;

	private IClass lookupClass(String name) {
		IClass cls = chaCG.getClassHierarchy().lookupClass(TypeReference.findOrCreate(scope.getApplicationLoader(), "L" + name));
		if(cls == null)
			throw new NullPointerException();
		return cls;
	}

	private void init() {
		initialised = true;
		System.out.println("Initialising WALA callgraph and memory access map");

		try {
			File scopeFile = File.createTempFile("streamliner_scope", ".txt");
			scopeFile.deleteOnExit();

			try(PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(scopeFile)))) {
				// Java standard library
				writer.println("Primordial,Java,stdlib,none");
				writer.println("Primordial,Java,jarFile,primordial.jar.model");

				// Dependencies
				for(File jarFile : libraries)
					writer.format("Application,Java,jarFile,%s\n", jarFile.getPath());

				// Class file directories
				for(String classDirectory : directories)
					writer.format("Application,Java,binaryDir,%s\n", classDirectory);
			} finally {
				libraries.clear();
				directories.clear();
			}

			scope = AnalysisScopeReader.readJavaScope(scopeFile.getPath(), null, getClass().getClassLoader());

			// We need a baseline call graph.  Here we use a CHACallGraph based on a ClassHierarchy.
			ClassHierarchy cha = ClassHierarchyFactory.make(scope);
			chaCG = new dk.casa.streamliner.utils.Utils.CHACallGraph(cha);

			Collection<Entrypoint> entrypoints = this.entrypoints.stream()
					.map(mthd -> {
						IClass cls = lookupClass(mthd.owner);
						return cls.getMethod(new Selector(Atom.findOrCreateUnicodeAtom(mthd.name), Descriptor.findOrCreateUTF8(mthd.desc)));
					}).filter(Objects::nonNull).map(mth -> new DefaultEntrypoint(mth, cha))
					.collect(Collectors.toList());
			this.entrypoints.clear();

			chaCG.init(entrypoints);
			AnalysisOptions options = new AnalysisOptions();
			IAnalysisCacheView cache = new AnalysisCacheImpl();
			// We also need a heap model to create InstanceKeys for allocation sites, etc.
			// Here we use a 0-1 CFA builder, which will give a heap abstraction similar to
			// context-insensitive Andersen's analysis
			heapModel = Util.makeZeroOneCFABuilder(Language.JAVA, options, cache, cha, scope);
			// The MemoryAccessMap helps the demand analysis find matching field reads and writes
			MemoryAccessMap mam = new SimpleMemoryAccessMap(chaCG, heapModel, false);
			// The StateMachineFactory helps in tracking additional states like calling contexts.
			// For context-insensitive analysis we use a DummyStateMachine.Factory
			StateMachineFactory<IFlowLabel> stateMachineFactory = new DummyStateMachine.Factory<>();
			drpt = DemandRefinementPointsTo.makeWithDefaultFlowGraph(
					chaCG, heapModel, mam, cha, options, stateMachineFactory);
			// The RefinementPolicyFactory determines how the analysis refines match edges (see PLDI'06
			// paper).  Here we use a policy that does not perform refinement and just uses a fixed budget
			// for a single pass
			RefinementPolicyFactory refinementPolicyFactory =
					new SinglePassRefinementPolicy.Factory(
							new NeverRefineFieldsPolicy(), new NeverRefineCGPolicy(), 1000);
			drpt.setRefinementPolicyFactory(refinementPolicyFactory);

			queryStats.add("initSuccess");
			initialisedSuccessfully = true;
			System.out.println("Successfully initialised WALA");
		} catch(ClassHierarchyException | IOException | CancelException | UnimplementedError | NullPointerException exc) {
			queryStats.add("initFail");
			System.err.println("Failed to initialise WALA oracle!");
			exc.printStackTrace();
		}
	}

	@Override
	public Optional<Type> queryType(Context context, MethodInsnNode minsn, InterValue receiver) {
		if(context.getOwner().contains("LambdaModel$"))
			return Optional.empty();

		if(!initialised) init();
		if(!initialisedSuccessfully) return Optional.empty();

		MethodNode mn = context.getMethod();

		queryStats.add("total");
		System.out.format("Starting WALA query in %s.%s for receiver of call to %s.%s%s\n",
				context.getOwner(), mn.name, minsn.owner, minsn.name, minsn.desc);

		IClass cls = lookupClass(context.getOwner());
		IMethod method = cls.getMethod(new Selector(Atom.findOrCreateUnicodeAtom(mn.name), Descriptor.findOrCreateUTF8(mn.desc)));

		CGNode node = chaCG.getNode(method, EVERYWHERE);
		if(node == null) { // ???
			queryStats.add("CGNodeNull");
			System.err.println("CGNodeNull?");
			return Optional.empty();
		}

		// This piece of code finds calls in the method that match the given name and descriptor
		IR ir = node.getIR();
		List<SSAAbstractInvokeInstruction> matchingCalls = new ArrayList<>();
		ir.iterateCallSites().forEachRemaining(csr -> {
			SSAAbstractInvokeInstruction invoke = ir.getCalls(csr)[0];
			MethodReference target = invoke.getDeclaredTarget();
			if(target.getName().toString().equals(minsn.name) && target.getDescriptor().toUnicodeString().equals(minsn.desc))
				matchingCalls.add(invoke);
		});

		if(matchingCalls.size() == 0)
			throw new NoSuchElementException();
		else if(matchingCalls.size() > 1) {
			// There can of course be multiple calls to the same method.
			// In this case we try to narrow the set of candidates by utilising embedded line number information.
			System.err.println("Multiple matching calls...");

			if(!(method instanceof ShrikeCTMethod)) {
				System.err.println("Method is not a ShrikeCTMethod?");
				return Optional.empty();
			}

			Optional<Integer> lineNumber = Utils.getLineNumber(minsn);
			if(!lineNumber.isPresent()) return Optional.empty();

			matchingCalls.removeIf(invoke -> {
				try {
					return method.getLineNumber(((ShrikeCTMethod) method).getBytecodeIndex(invoke.iIndex())) != lineNumber.get();
				} catch (InvalidClassFileException e) { return true; }
			});

			if(matchingCalls.size() == 0) {
				System.err.println("Line number search yielded 0 results");
				return Optional.empty();
			} else if(matchingCalls.size() > 1) {
				System.err.println("Line number search yielded too many matching calls");
				return Optional.empty();
			}
		}

		// Now we have the matching call site, so we can query for the type of the receiver of the call.
		SSAAbstractInvokeInstruction invoke = matchingCalls.get(0);

		PointerKey pk = heapModel.getPointerKeyForLocal(node, invoke.getUse(0));
		Pair<DemandRefinementPointsTo.PointsToResult, Collection<InstanceKey>> res;
		Stopwatch stopwatch = new Stopwatch();
		try {
		    stopwatch.start();
			res = drpt.getPointsTo(pk, k -> true);
			stopwatch.stop();
		} catch(AssertionError exc) {
			System.err.println(exc);
			queryStats.add("AssertionError");
			return Optional.empty();
		} catch(NullPointerException exc) {
			System.err.println(exc);
			queryStats.add("NullPointerException");
			return Optional.empty();
		} catch(IllegalArgumentException exc) {
			System.err.println(exc);
			queryStats.add("IllegalArgumentException");
			return Optional.empty();
		}

		queryStats.add(res.fst.toString());
		System.out.format("Points to query finished with %s in %.2fs\n", res.fst, (float)stopwatch.getElapsedMillis()/1000.f);

		if(res.fst != DemandRefinementPointsTo.PointsToResult.SUCCESS)
			return Optional.empty();

		// We transform the returned list of allocation sites into a list of concrete types
		Set<String> concreteTypes = res.snd.stream().map(InstanceKey::getConcreteType)
				.map(IClass::getName).map(type -> type.getPackage() + "/" + type.getClassName())
				.collect(Collectors.toSet());

		if(concreteTypes.size() == 0) { // ???
		    queryStats.add("zero");
		    return Optional.empty();
		} else if(concreteTypes.size() > 1) {
			queryStats.add("multiple");
			System.err.format("WALA query found too many concrete types (%d): %s\n", concreteTypes.size(),
					concreteTypes.stream().limit(10).collect(Collectors.toList()));
			return Optional.empty();
		}

		// Full success: only one possible concrete type!
		queryStats.add("singleton");
		return Optional.of(concreteTypes.iterator().next()).map(Type::getObjectType);
	}
}
