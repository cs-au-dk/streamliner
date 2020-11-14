package dk.casa.streamliner.asm.analysis.inter.oracles;

import com.google.common.base.Stopwatch;
import dk.casa.streamliner.asm.Utils;
import dk.casa.streamliner.asm.analysis.MethodIdentifier;
import dk.casa.streamliner.asm.analysis.inter.Context;
import dk.casa.streamliner.asm.analysis.inter.InterValue;
import dk.casa.streamliner.utils.Counter;
import org.apache.log4j.BasicConfigurator;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import soot.*;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.Stmt;
import soot.options.Options;
import soot.tagkit.LineNumberTag;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SPARKOracle extends StreamLibraryOracle {
	private String classPath;
	private Collection<MethodIdentifier> entryPoints;
	private final String extraSPARKOptions;

	public Counter<String> queryResults = new Counter<>();

	private boolean initialized = false;
	private boolean initializeSuccess = false;

	public SPARKOracle(String classPath, Collection<MethodIdentifier> entryPoints, String extraSPARKOptions) {
		this.classPath = classPath;
		this.entryPoints = entryPoints;
		this.extraSPARKOptions = extraSPARKOptions;
	}

	public SPARKOracle(String classPath, Collection<MethodIdentifier> entryPoints) {
		this(classPath, entryPoints, "");
	}

	/** Initialization takes significant time, so we delay until it is actually needed */
	private static boolean loggingInitialised = false;
	public static void initSoot(String classPath, String SPARKOptions) {
		if(!loggingInitialised) {
			loggingInitialised = true;
			BasicConfigurator.configure();
		}

		G.reset();
		G.v().resetSpark();

		Options.v().set_app(true);
		Options.v().set_whole_program(true);
		Options.v().set_src_prec(Options.src_prec_only_class);
		Options.v().set_output_format(Options.output_format_none);
		Options.v().set_no_bodies_for_excluded(true);
		Options.v().set_allow_phantom_refs(true);
		//Options.v().set_full_resolver(true);

		//Options.v().set_verbose(true);

		// TODO: keep offset only works with coffi front-end, which is too old.
		//  Might get fixed in https://github.com/soot-oss/soot/issues/787
		//  We hack around it with line numbers instead
		//Options.v().set_keep_offset(true);
		Options.v().set_keep_line_number(true);

		Options.v().set_include(Arrays.asList("java.lang.*", "java.util.*", "java.io.*", "java.nio.*", "sun.misc.*", "java.net.*", "javax.servlet.*", "javax.crypto.*"));
		Options.v().setPhaseOption("jb", "use-original-names:true");

		Options.v().setPhaseOption("cg.spark", "on,verbose:true,on-fly-cg:false,types-for-sites:true");
		Options.v().setPhaseOption("cg.spark", SPARKOptions);

		if(classPath.length() == 0) throw new RuntimeException("Empty classpath!");
		//Options.v().set_soot_classpath("VIRTUAL_FS_FOR_JDK:" + classPath);
		Options.v().set_soot_classpath(classPath);
		Options.v().set_prepend_classpath(true);

		//Options.v().set_dynamic_package(Collections.singletonList("dk.casa.streamliner"));
		//Options.v().set_process_dir(Arrays.asList("out/classes", "out/test-classes"));

		// Fix bug with SPARK loading android.os.Handler
		Scene.v().addBasicClass("android.os.Handler");

		Scene.v().loadNecessaryClasses();
	}

	private void init() {
		initialized = true;
		initSoot(classPath, extraSPARKOptions);
		Scene.v().setEntryPoints(entryPoints.stream().map(et ->
				Scene.v().forceResolve(et.owner.replace("/", "."), SootClass.BODIES)
						.getMethod(getSignature(et.name, et.desc))
		).collect(Collectors.toList()));

		// Generate CallGraph
		try {
			Stopwatch stopwatch = Stopwatch.createStarted();
			PackManager.v().getPack("cg").apply();
			System.err.println("Computed call-graph in " + stopwatch.elapsed(TimeUnit.MILLISECONDS) + "ms");
			queryResults.add("initSuccess");
			initializeSuccess = true;
		} catch (RuntimeException exc) {
			System.err.println("Error occurred while computing call-graph");
			exc.printStackTrace();
			queryResults.add("initFail");
		}

		classPath = null; entryPoints = null;
	}

	private static String getSignature(String name, String desc) {
		return Type.getReturnType(desc).getClassName() + " " + name +
				Stream.of(Type.getArgumentTypes(desc)).map(Type::getClassName).collect(Collectors.joining(",", "(", ")"));
	}

	@Override
	public Optional<Type> queryType(Context context, MethodInsnNode minsn, InterValue receiver) {
		String owner = context.getOwner();
		if(owner.contains("LambdaModel$"))
			return Optional.empty(); // TODO: Bridge gap between our lambda models and Soot's

		if(!initialized) init();
		if(!initializeSuccess) return Optional.empty();

		MethodNode mn = context.getMethod();
		queryResults.add("total");

		SootClass c = Scene.v().getSootClass(owner.replace("/", "."));
		if(c.isPhantom()) {
			queryResults.add("unreachable");
			System.err.format("Class %s is unreachable.\n", owner);
			return Optional.empty();
		}

		SootMethod sm = c.getMethod(getSignature(mn.name, mn.desc));
		if(!Scene.v().getReachableMethods().contains(sm)) {
			queryResults.add("unreachable");
			System.err.format("Method %s.%s is unreachable.\n", owner, mn.name);
			return Optional.empty();
		}

		String searchSig = getSignature(minsn.name, minsn.desc);
		List<Stmt> matches = sm.getActiveBody().getUnits().stream()
				.map(u -> (Stmt) u)
				.filter(u -> u.containsInvokeExpr() &&
						u.getInvokeExpr().getMethod().getSubSignature().equals(searchSig))
				.collect(Collectors.toList());

		if (matches.size() == 0)
			throw new RuntimeException("Unable to find call instruction with matching signature");
		else if (matches.size() > 1) {
			// There can of course be multiple calls to the same method.
			// In this case we try to narrow the set of candidates by utilising embedded line number information.
			Optional<Integer> lineNumber = Utils.getLineNumber(minsn);
			if(!lineNumber.isPresent()) return Optional.empty();

			matches.removeIf(u -> !u.hasTag("LineNumberTag") ||
					((LineNumberTag) u.getTag("LineNumberTag")).getLineNumber() != lineNumber.get());

			if(matches.size() == 0) {
				System.err.println("Line number search yielded 0 results");
				return Optional.empty();
			} else if(matches.size() > 1) {
				System.err.println("Line number search yielded too many matching calls");
				return Optional.empty();
			}
		}

		Stmt stmt = matches.get(0);
		InstanceInvokeExpr expr = (InstanceInvokeExpr) stmt.getInvokeExpr();

		if(!(expr.getBase() instanceof Local))
			throw new RuntimeException("???");

		PointsToSet pts = Scene.v().getPointsToAnalysis().reachingObjects((Local)expr.getBase());
		Set<soot.Type> possibleTypes = pts.possibleTypes();
		soot.Type sType = null;
		if (possibleTypes.size() == 0) {
			System.err.println("SPARK failed to identify any possible types?");
			queryResults.add("zero");
		} else if (possibleTypes.size() == 1) {
			sType = possibleTypes.iterator().next();
			System.out.format("SPARK found type of receiver of call %s.%s to be %s\n", minsn.owner, minsn.name, sType);
			queryResults.add("singleton");
		} else {
			System.err.format("SPARK identified too many possible types (%d) %s\n", possibleTypes.size(),
					possibleTypes.stream().limit(10).collect(Collectors.toList()));
			queryResults.add("multiple");
		}

		if (sType != null && !(sType instanceof RefType)) throw new RuntimeException("Should not happen");
		return Optional.ofNullable(sType).map(s -> Type.getObjectType(((RefType) s).getClassName().replace(".", "/")));
	}
}
