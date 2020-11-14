package dk.casa.streamliner.asm;

import dk.casa.streamliner.asm.analysis.inter.Context;
import dk.casa.streamliner.asm.analysis.inter.InterValue;
import dk.casa.streamliner.asm.analysis.inter.oracles.Oracle;
import dk.casa.streamliner.asm.analysis.inter.oracles.StreamLibraryOracle;
import dk.casa.streamliner.asm.transform.InlineAndAllocateTransformer;
import dk.casa.streamliner.asm.transform.LambdaPreprocessor;
import dk.casa.streamliner.asm.transform.LocalVariableCleanup;
import dk.casa.streamliner.asm.transform.SlidingWindowOptimizer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.util.CheckMethodAdapter;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.objectweb.asm.Opcodes.V1_8;

/** This class contains some skeleton code and directions on how to
 *  apply the analysis and transformation on class files found in the wild.
 */
public class OptimizeSkeleton {

	public static void main(String[] args) throws IOException {
		/* The first step of the process is to load the class file.
		 * The ClassNodeCache singleton object contains class files as
		 * ClassNode instances. They can be retrieved by name with
		 * ClassNodeCache.get(name).
		 * As hinted by the name of the class it caches classes for
		 * further retrieval.
		 *
		 * By default it can load classes that are on the Java classpath.
		 * Classes not on the classpath must be put into the cache manually
		 * as shown below.
		 */

		/* How to load a class by path and put it in the cache:
		ClassNode loaded = Utils.loadClassFile(new File("path/to/Example.class"));
		ClassNodeCache.put(loaded.name, loaded);
		 */

		ClassNode cn = ClassNodeCache.get("Example");

		/* If the class references other classes that are not on the classpath
		 * (classes in the same project or external dependencies), they must
		 * also be put into the cache before the analysis starts:
		for(ClassNode cls : Utils.loadJarFile(new File("path/to/dependency.jar")))
			ClassNodeCache.put(cls.name, cls);
		 *
		 * Missing classes will result in RuntimeExceptions in the analysis.
		 */

		/* We then select the method from the class that we want to optimise: */
		MethodNode mn = Utils.getMethod(cn, method -> method.name.equals("toOptimize")).orElseThrow(NoSuchElementException::new);

		/* We can print the method before optimization. */
		System.out.println(Decompile.run(mn));

		/* The following two lines of code transform INVOKEDYNAMIC instructions for lambdas
		 * into equivalent instructions that are easier to analyse and transform.
		 */
		LambdaPreprocessor lambdaPreprocessor = new LambdaPreprocessor(mn);
		lambdaPreprocessor.preprocess();

		try {
			/* We construct the oracle that the analysis will use. The oracle answers type
			 * queries from the interprocedural analysis (see section 4 / phase 1 in the paper).
			 * Queries contain the call string (context), call instruction (minsn) and abstract
			 * value for the receiver.
			 * The return value should be the type of the receiver of the call, if it can be determined.
			 *
			 * In the paper we discuss multiple options for how to answer these queries.
			 * The RQ2 experiments use some heuristics and an implementation of a Class Hierarchy Analysis,
			 * see dk.casa.streamliner.asm.RQ2.{CHA,Experiment}
			 */
			Oracle oracle = new StreamLibraryOracle() {
				@Override
				public Optional<Type> queryType(Context context, MethodInsnNode minsn, InterValue receiver) {
					/* If we know that the method only creates streams from ArrayLists, we can use the following implementation: */
					if(minsn.name.equals("stream") &&
							Utils.getAncestors(Type.getObjectType(minsn.owner)).contains(Type.getObjectType("java/util/Collection")))
						return Optional.of(Type.getObjectType("java/util/ArrayList"));

					return Optional.empty();
				}
			};

			/* This class performs the interprocedural analysis followed by the interprocedural
			 * transformations: inlining and stack allocation.
			 * This corresponds to phases 2 and 3 in the paper.
			 */
			new InlineAndAllocateTransformer(cn.name, mn, oracle, true).transform();

			/* Perform some clean-up of the resulting bytecode. Corresponds to phase 4 in the paper */
			new LocalVariableCleanup(cn.name, mn).run();
			SlidingWindowOptimizer.run(mn);

			lambdaPreprocessor.postprocess();

		} catch(AnalyzerException exc) {
			exc.printStackTrace();
			System.exit(1);
		}

		/* We now have the transformed method. We can let ASM perform a small validation of the
		 * optimized code. If it fails there is (most likely) a bug in the implementation somewhere.
		 */
		CheckMethodAdapter cma = new CheckMethodAdapter(mn.access, mn.name, mn.desc, null, new HashMap<>());
		cma.version = V1_8;
		mn.accept(cma); // Performs the validation

		/* We can also print it */
		System.out.println(Decompile.run(mn));

		/* We can now write the transformed class file back to the file system. */
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		cn.accept(cw);

		try(FileOutputStream fos = new FileOutputStream("path/to/Example.class")) {
			fos.write(cw.toByteArray());
		}
	}
}
