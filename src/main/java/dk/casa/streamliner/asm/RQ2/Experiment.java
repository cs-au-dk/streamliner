package dk.casa.streamliner.asm.RQ2;

import dk.casa.streamliner.asm.ClassNodeCache;
import dk.casa.streamliner.asm.Decompile;
import dk.casa.streamliner.asm.Utils;
import dk.casa.streamliner.asm.analysis.MethodIdentifier;
import dk.casa.streamliner.asm.analysis.inter.Context;
import dk.casa.streamliner.asm.analysis.inter.InterValue;
import dk.casa.streamliner.asm.analysis.inter.oracles.SPARKOracle;
import dk.casa.streamliner.asm.analysis.inter.oracles.MockTypeOracle;
import dk.casa.streamliner.asm.analysis.inter.oracles.StreamLibraryOracle;
import dk.casa.streamliner.asm.analysis.inter.oracles.TypeQueryOracle;
import dk.casa.streamliner.asm.analysis.inter.oracles.WALAOracle;
import dk.casa.streamliner.asm.transform.InlineAndAllocateTransformer;
import dk.casa.streamliner.asm.transform.LambdaPreprocessor;
import dk.casa.streamliner.asm.transform.LocalVariableCleanup;
import dk.casa.streamliner.asm.transform.SlidingWindowOptimizer;
import dk.casa.streamliner.utils.Counter;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.input.MessageDigestCalculatingInputStream;
import org.apache.commons.math3.util.Pair;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.util.CheckMethodAdapter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.objectweb.asm.Opcodes.*;

public class Experiment {
	private static boolean isClassNotFound(Exception exc) {
		Throwable cause = exc;
		while(cause.getCause() != null) cause = cause.getCause();
		return cause instanceof IOException && cause.getMessage().equals("Class not found");
	}

	private static final Type streamT = Type.getObjectType("java/util/stream/BaseStream");

	private static boolean isStreamType(Type type) {
		try {
			return Utils.getAncestors(type).contains(streamT);
		} catch(RuntimeException exc) {
			if(!isClassNotFound(exc)) throw exc;
			return false;
		}
	}

	private static boolean isStreamConstructor(AbstractInsnNode insn) {
		if(!(insn instanceof MethodInsnNode)) return false;
		MethodInsnNode minsn = (MethodInsnNode) insn;
		Type returnType = Type.getReturnType(minsn.desc);
		if(!isStreamType(returnType) || !returnType.getInternalName().startsWith("java/util/stream")) return false;
		if(Stream.of(Type.getArgumentTypes(minsn.desc)).anyMatch(Experiment::isStreamType)) return false;
		if(minsn.getOpcode() == INVOKESTATIC) return true;
		// If we call a method on a stream to get a new stream it's not a constructor
		return !isStreamType(Type.getObjectType(minsn.owner));
	}

	private static boolean isStreamConsumer(AbstractInsnNode insn) {
		if(!(insn instanceof MethodInsnNode)) return false;
		MethodInsnNode minsn = (MethodInsnNode) insn;
		Type objType = Type.getObjectType(minsn.owner);
		return minsn.getOpcode() != INVOKESTATIC
				&& isStreamType(objType)
				&& objType.getInternalName().startsWith("java/util/stream")
				&& !isStreamType(Type.getReturnType(minsn.desc))
				&& !Arrays.asList("<init>", "iterator").contains(minsn.name);
	}

	private static int countPipelines(MethodNode mn) {
		return (int)Long.min(Utils.instructionStream(mn).filter(Experiment::isStreamConstructor).count(),
							 Utils.instructionStream(mn).filter(Experiment::isStreamConsumer).count());
	}

	private static boolean isParallel(AbstractInsnNode insn) {
		if(!(insn instanceof MethodInsnNode)) return false;
		MethodInsnNode minsn = (MethodInsnNode) insn;
		return (minsn.name.equals("parallel") && isStreamType(Type.getObjectType(minsn.owner)))
				|| (minsn.name.equals("parallelStream") && isStreamType(Type.getReturnType(minsn.desc)));
	}

	private static int clearAccess(int access) {
		return access & ~(ACC_PUBLIC | ACC_PRIVATE | ACC_PROTECTED);
	}

	private static int freshCounter = 0;
	private static ClassNode freshArrayList() {
		ClassNode splitCopy = new ClassNode();
		ClassNodeCache.get("java/util/ArrayList$ArrayListSpliterator").accept(splitCopy);
		splitCopy.name = String.format("ArrayListCopy%d$Spliterator", freshCounter);
		splitCopy.access |= ACC_FINAL;

		ClassNode copy = new ClassNode();
		ClassNodeCache.get("java/util/ArrayList").accept(copy);
		copy.name = String.format("ArrayListCopy%d", freshCounter++); // Discard java.util package name
		copy.access |= ACC_FINAL;  // Make class final

		// Make all ArrayList fields public
		for(FieldNode fn : copy.fields) fn.access = clearAccess(fn.access) | ACC_PUBLIC;

		// The modCount field lives in AbstractList - to avoid copying AbstractList as well
		// we create an accessor method for it
		MethodNode modCount = new MethodNode(ACC_PUBLIC, "getModCount", Type.getMethodDescriptor(Type.INT_TYPE), null, null);
		Utils.addInstructions(modCount.instructions,
				new VarInsnNode(ALOAD, 0),
				new FieldInsnNode(GETFIELD, "java/util/AbstractList", "modCount", "I"),
				new InsnNode(IRETURN));
		copy.methods.add(modCount);

		// Change spliterator method to return splitCopy
		for(MethodNode mn : copy.methods) if(mn.name.equals("spliterator")) {
			mn.instructions.forEach(insn -> {
				if(insn.getOpcode() == NEW) ((TypeInsnNode) insn).desc = splitCopy.name;
				else if(insn instanceof MethodInsnNode) {
					MethodInsnNode minsn = (MethodInsnNode) insn;
					if(minsn.name.equals("<init>")) minsn.owner = splitCopy.name;
				}
			});
		}

		// Change field instruction in spliterator methods to use the fresh ArrayList
		for(MethodNode mn : splitCopy.methods) {
			for(AbstractInsnNode insn : mn.instructions) if(insn instanceof FieldInsnNode) {
				FieldInsnNode finsn = (FieldInsnNode) insn;
				if(finsn.name.equals("modCount"))
					mn.instructions.set(insn, new MethodInsnNode(INVOKEVIRTUAL, copy.name, modCount.name, modCount.desc, false));
				else if(finsn.owner.equals("java/util/ArrayList"))
					finsn.owner = copy.name;
				else if(finsn.owner.equals("java/util/ArrayList$ArrayListSpliterator"))
					finsn.owner = splitCopy.name;
			}
		}

		ClassNodeCache.put(splitCopy.name, splitCopy);
		ClassNodeCache.put(copy.name, copy);
		return copy;
	}

	/** Replace non-static stream constructors with stream constructors for
	 *  a final copy of ArrayList.
	 */
	 public static void preprocessStreamConstructors(MethodNode mn) {
		ClassNode copy = null;
		ListIterator<AbstractInsnNode> it = mn.instructions.iterator();
		while(it.hasNext()) {
			AbstractInsnNode insn = it.next();
			if(insn.getOpcode() == CHECKCAST && ((TypeInsnNode) insn).desc.contains("ArrayListCopy")) return; // Already preprocessed
			if(!(insn instanceof MethodInsnNode)) continue;
			MethodInsnNode minsn = (MethodInsnNode) insn;
			if(!isStreamConstructor(minsn)
					|| minsn.getOpcode() == INVOKESTATIC
					|| !Utils.getAncestors(Type.getObjectType(minsn.owner)).contains(Type.getObjectType("java/util/Collection"))) continue;

			if(copy == null) copy = freshArrayList();
			mn.instructions.insertBefore(minsn, new TypeInsnNode(CHECKCAST, copy.name));
			MethodInsnNode newInsn = (MethodInsnNode) minsn.clone(new HashMap<>());
			newInsn.owner = copy.name;
			it.set(newInsn);
		}
	}

	private static String hashFile(File file) {
	 	try {
		    MessageDigest md = MessageDigest.getInstance("SHA-256");
		    try (FileInputStream is = new FileInputStream(file);
		         MessageDigestCalculatingInputStream dis = new MessageDigestCalculatingInputStream(is, md)) {
				dis.consume();
		    } catch (IOException e) {
			    throw new RuntimeException(e);
		    }
		    return new BigInteger(1, md.digest()).toString(16);
	    } catch (NoSuchAlgorithmException e) {
		    throw new RuntimeException(e);
	    }
	}

	private static Collection<File> getLibraries(Path repo) {
		return FileUtils.listFiles(repo.toFile(), FileFilterUtils.asFileFilter(file -> {
			String path = file.getPath();
			return path.endsWith(".jar") && (path.contains("gcache/caches/modules-2/") || path.contains("libs/"));
		}), FileFilterUtils.trueFileFilter());
	}

	private static List<ClassNode> addLibraries(Collection<File> jarFiles) {
	 	Set<String> seen = new HashSet<>();
	 	List<ClassNode> addedClasses = new ArrayList<>();
		for(File file : jarFiles) if(seen.add(hashFile(file))) {
			try {
				for (ClassNode cn : Utils.loadJarFile(file))
					if(ClassNodeCache.put(cn.name, cn))
						addedClasses.add(cn);

			} catch(IOException e) {
				throw new RuntimeException(e);
			}
		}
	 	System.out.format("Added %d classes from %d jar files.\n", addedClasses.size(), jarFiles.size());
	 	return addedClasses;
	}

	@FunctionalInterface
	private interface OracleFactory {
	 	TypeQueryOracle create(Collection<File> jarFiles, Collection<String> classPathFolders,
	                           Collection<MethodIdentifier> entrypoints, Collection<ClassNode> projectClasses);
	}

	static class Result extends Counter<String> {
		public static final List<String> keys = Arrays.asList("methodsWithPipelines", "methodsOptimised",
				"parallelSkip", "pipelines", "pipelinesOptimised", "missingClasses",
				"failFlatMap", "failToArray", "failConcat", "failPhase0", "failResolveCall", "failEscape",
				"failLongStream", "failInfiniteRecursion", "failBranching", "failSorted",
				"failOverapproximate", "projects", "progress");

		public Result() { super(keys); }
	}

	/** An Oracle that answers type queries with CHA.
	 *  Delegates to provided TypeQueryOracle if CHA is insufficient.
	 */
	private static class RQ2Oracle extends StreamLibraryOracle {
		private final TypeQueryOracle delegate;
	 	private final CHA cha;

		public RQ2Oracle(TypeQueryOracle delegate, CHA cha) {
			this.delegate = delegate;
		    this.cha = cha;
		}

		@Override
		public Optional<Type> queryType(Context context, MethodInsnNode minsn, InterValue receiver) {
			Type ownerT = Type.getObjectType(minsn.owner);
			Type receiverT = receiver.type.getType();
			if(receiverT.equals(ownerT) || Utils.getAncestors(receiverT).contains(ownerT))
				ownerT = receiverT;

			Set<String> subclasses = cha.getSubclasses(ownerT.getInternalName());
			if(subclasses != null && subclasses.size() == 1)
				return Optional.of(subclasses.iterator().next()).map(Type::getObjectType);

			return delegate != null? delegate.queryType(context, minsn, receiver): Optional.empty();
		}
	}

	private static final Counter<String> resolveStat = new Counter<>(),
										 queryStats = new Counter<>();

	private static Result process(Path repo, OracleFactory oracleFactory) {
	 	Result result = new Result();
		System.out.println("\n" + repo);
		if(!repo.resolve(".built").toFile().exists() || !repo.resolve(".libs").toFile().exists()) {
			System.out.println("Skipping since .built or .libs is missing");
			return result;
		}

		Collection<File> classFiles = FileUtils.listFiles(repo.toFile(), FileFilterUtils.suffixFileFilter(".class"),
				FileFilterUtils.asFileFilter(name -> !name.getPath().contains("gcache/caches")));
		if(classFiles.isEmpty()) {
			System.out.println("Skipping since there are no ClassFiles");
			return result;
		}

		result.inc("projects", 1);
		ClassNodeCache.clear();

		Collection<File> jarFiles = getLibraries(repo);
		List<ClassNode> projectClasses = addLibraries(jarFiles);

		List<ClassNode> classes = new ArrayList<>();
		List<MethodIdentifier> entryPoints = new ArrayList<>();
		Set<String> classPathFolders = new HashSet<>();

		// TODO: This loop generates entrypoints for methods in classes with the same name.
		//  The list of class files should be filtered to select the representative class for each name.
		for(File file : classFiles) {
			try {
				ClassNode cn = Utils.loadClassFile(file);
				ClassNodeCache.put(cn.name, cn);
				classes.add(cn);

				// Find the directory that should be added to the classpath to find this class
				File parent = file;
				for(int i = 0; i < cn.name.split("/").length; i++)
					parent = parent.getParentFile();

				classPathFolders.add(parent.getPath());

				// Find main methods or methods annotated with Test
				cn.methods.stream()
						.filter(mn ->
								(mn.visibleAnnotations != null && mn.visibleAnnotations.stream()
										.anyMatch(an -> {
											String name = Type.getType(an.desc).getInternalName();
											return name.endsWith("Test") || name.endsWith("TestTemplate");
										}))
								|| (mn.name.equals("main") && mn.desc.equals("([Ljava/lang/String;)V")))
						.map(mn -> new MethodIdentifier(cn.name, mn.name, mn.desc)).forEach(entryPoints::add);
			} catch (IOException exc) { exc.printStackTrace(); }
		}

		System.out.println("Loaded " + classes.size() + " class files");
		System.out.println("Found " + entryPoints.size() + " entrypoints");

		projectClasses.addAll(classes);
		TypeQueryOracle delegateOracle = oracleFactory.create(jarFiles, classPathFolders, entryPoints, projectClasses);
		RQ2Oracle oracle = new RQ2Oracle(delegateOracle, new CHA(projectClasses));
		projectClasses.clear();

		List<Pair<ClassNode, MethodNode>> methodsWithPipelines = classes.stream()
				.flatMap(cn -> cn.methods.stream().map(mn -> new Pair<>(cn, mn)))
				.filter(pr -> {
					MethodNode mn = pr.getSecond();
					return Utils.instructionStream(mn).anyMatch(Experiment::isStreamConstructor)
							&& Utils.instructionStream(mn).anyMatch(Experiment::isStreamConsumer);
				})
				.collect(Collectors.toList());

		result.put("methodsWithPipelines", methodsWithPipelines.size());
		System.out.println("" + methodsWithPipelines.size() + " methods with pipelines.");
		methodsWithPipelines.forEach(pr -> {
			ClassNode cn = pr.getFirst();
			String owner = cn.name;

			// Copy to prevent modified methods in old cache from retaining objects
			MethodNode orig = pr.getSecond();
			MethodNode mn = new MethodNode(orig.access, orig.name, orig.desc, orig.signature, orig.exceptions.toArray(new String[0]));
			orig.accept(mn);

			int parallelCount = (int)Utils.instructionStream(mn).filter(Experiment::isParallel).count();
			if(parallelCount > 0) {
				result.inc("parallelSkip", parallelCount);
				return;
			}

			int pipelines = countPipelines(mn);
			result.inc("pipelines", pipelines);

			System.out.format("Analyse %s.%s\n", owner, mn.name);
			String beforeOptimisation = Decompile.run(mn);
			//System.out.println(beforeOptimisation);
			try {
				ClassNodeCache.push();  // Start a new cache

				//preprocessStreamConstructors(mn);
				new LambdaPreprocessor(mn).preprocess();
				new InlineAndAllocateTransformer(owner, mn, oracle, false).transform();
				new LocalVariableCleanup(owner, mn).run();
				SlidingWindowOptimizer.run(mn);
				new LambdaPreprocessor(mn).postprocess();

				CheckMethodAdapter cma = new CheckMethodAdapter(mn.access, mn.name, mn.desc, null, new HashMap<>());
				cma.version = V1_8;
				mn.accept(cma);

				result.inc("methodsOptimised", 1);

				int opt = pipelines - Integer.min(countPipelines(mn), pipelines);
				result.inc("pipelinesOptimised", opt);

				if(opt < pipelines) {
					int concat = (int)Utils.instructionStream(orig)
							.filter(insn -> insn instanceof MethodInsnNode && ((MethodInsnNode) insn).name.equals("concat"))
							.count();

					result.inc("failConcat", Integer.min(pipelines - opt, concat));

					int sorted = (int)Utils.instructionStream(orig)
							.filter(insn -> insn instanceof MethodInsnNode && ((MethodInsnNode) insn).name.equals("sorted"))
							.count();

					result.inc("failSorted", Integer.min(pipelines - opt - concat, sorted));
				}

				//String afterOptimisation = Decompile.run(mn);
				//System.out.println("Optimised: " + beforeOptimisation.equals(afterOptimisation));
				//System.out.println(afterOptimisation);

			} catch(Exception exc) {
				System.err.println("Analysis failed!");
				String message = exc.getMessage();
				if(isClassNotFound(exc)) {
					result.inc("missingClasses", pipelines);
					System.err.println(message);
				} else {
					if(message.contains("Value in invalid state for pointsTo lookup")) result.inc("failBranching", pipelines);
					else if(message.contains("values escape") || message.contains("We lost?")) result.inc("failEscape", pipelines);
					else if(message.contains("Unable to resolve call") || message.contains("Overapproximation of call with reachable cells")) {
					    if(!(exc instanceof AnalyzerException)) throw new IllegalArgumentException();

						AnalyzerException aexc = (AnalyzerException) exc;
						if (isStreamConstructor(aexc.node)) result.inc("failPhase0", pipelines);
						else if(!(aexc.node instanceof MethodInsnNode))
							throw new IllegalArgumentException();
						else {
							MethodInsnNode minsn = (MethodInsnNode) aexc.node;
							Set<Type> ancestors = Utils.getAncestors(minsn.owner);
							if(ancestors.contains(Type.getObjectType("java/util/stream/Sink")) && beforeOptimisation.contains("toArray"))
								result.inc("failToArray", pipelines);
							else if(beforeOptimisation.contains("concat"))
								result.inc("failConcat", pipelines);
							else if(ancestors.contains(Type.getObjectType("java/util/Spliterator")))
								result.inc(beforeOptimisation.contains("flatMap")? "failFlatMap" : "failBranching", pipelines);
							else if(minsn.owner.equals("java/util/stream/Stream") && minsn.name.equals("close") && beforeOptimisation.contains("flatMap"))
								result.inc("failFlatMap", pipelines);
							else {
								result.inc(message.contains("Overapproximation")? "failOverapproximate" : "failResolveCall", pipelines);
								resolveStat.add(minsn.owner + "." + minsn.name);
							}
						}
					} else if(message.contains("Infinite recursion?"))
						result.inc(beforeOptimisation.contains("LongStream.range")? "failLongStream" : "failInfiniteRecursion", pipelines);
					else
						exc.printStackTrace();
				}
			} finally {
				ClassNodeCache.pop();
			}
		});

		Counter<String> stats = null;
		if(delegateOracle instanceof WALAOracle) stats = ((WALAOracle) delegateOracle).queryStats;
		else if(delegateOracle instanceof SPARKOracle) stats = ((SPARKOracle) delegateOracle).queryResults;

		if(stats != null) {
			System.out.println("Query stats: " + stats);
			queryStats.add(stats);
		}

		return result;
	}

	public static void main(String[] args) throws IOException {
		Path rq2dir = Paths.get("RQ2/repos");

		OracleFactory oracleFactory;
		if(args.length == 0 || args[0].equals("mock")) {
			System.out.println("WARNING: Using unsound mock type query oracle for optimisations!");
			oracleFactory = (a, b, c, d) -> new MockTypeOracle();
		} else if(args[0].equals("wala"))
			oracleFactory = (jarFiles, classPathFolders, entryPoints, projectClasses) -> {
				// WALA does not support Java 14 yet, so we cannot use it if any of the classes are compiled with Java 14
				int maxVersion = projectClasses.stream().mapToInt(cn -> cn.version & 0xFFFF).max().orElse(0);
				if(maxVersion >= 58) {
					System.err.println("Disabling WALAOracle since maxversion is: " + maxVersion);
					return null;
				} else
					return new WALAOracle(jarFiles, classPathFolders, entryPoints);
			};
		else if(args[0].equals("spark"))
			oracleFactory = (jarFiles, classPathFolders, entryPoints, projectClasses) -> {
				String classPath = Stream.concat(jarFiles.stream().map(File::getPath), classPathFolders.stream())
						.collect(Collectors.joining(":"));
				return new SPARKOracle(classPath, entryPoints, args.length > 1? args[1] : "");
			};
		else
			throw new IllegalArgumentException("Unknown oracle type: " + args[0]);


		Result res;
		try(Stream<Path> files = Files.list(rq2dir)) {
			List<Path> filesl = files.collect(Collectors.toList());
			res = filesl.stream().map(path -> process(path, oracleFactory)).peek(System.out::println).reduce(new Result(), (acc, r) -> {
				acc.add(r);
				acc.inc("progress", 1);
				System.out.format("Progress: %d/%d\n", acc.get("progress"), filesl.size());
				return acc;
			});
		}

		System.out.println("Final result: " + res);
		System.out.println(queryStats);
		System.out.println("\n\n\n");

		int tallied = 0;
		BiFunction<String, Integer, Integer> pprint = (String name, Integer cnt) -> { System.out.format("%-45s%s\n", name, cnt); return cnt; };
		System.out.format("%-45s%s\n", "Verdict", "Count");

		tallied += pprint.apply("Successful optimization", res.get("pipelinesOptimised"));
		tallied += pprint.apply("Branching or inadequate type information",
				res.get("failBranching") + res.get("failResolveCall") + res.get("failOverapproximate"));
		tallied += pprint.apply("Pre-analysis deficiency", res.get("failPhase0"));
		tallied += pprint.apply("Use of advanced stream operators", res.get("failToArray") + res.get("failConcat") +
				res.get("failSorted") + res.get("failLongStream") + res.get("failFlatMap"));
		tallied += pprint.apply("Escaping pipeline object", res.get("failEscape"));
		tallied += pprint.apply("Infinite recursion", res.get("failInfiniteRecursion"));

		pprint.apply("Other", res.get("pipelines") - tallied);

		pprint.apply("Total", res.get("pipelines"));
	}
}
