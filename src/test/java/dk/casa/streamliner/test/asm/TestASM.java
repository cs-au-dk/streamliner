package dk.casa.streamliner.test.asm;

import dk.casa.streamliner.asm.ClassNodeCache;
import dk.casa.streamliner.asm.Decompile;
import dk.casa.streamliner.asm.InlineMethod;
import dk.casa.streamliner.asm.Utils;
import dk.casa.streamliner.asm.analysis.inter.*;
import dk.casa.streamliner.asm.analysis.inter.oracles.ExhaustiveOracle;
import dk.casa.streamliner.asm.analysis.inter.oracles.Oracle;
import dk.casa.streamliner.asm.analysis.inter.oracles.StreamLibraryOracle;
import dk.casa.streamliner.asm.transform.InlineAndAllocateTransformer;
import dk.casa.streamliner.asm.transform.LambdaPreprocessor;
import dk.casa.streamliner.asm.transform.LocalVariableCleanup;
import dk.casa.streamliner.asm.transform.SlidingWindowOptimizer;
import org.apache.commons.math3.util.Pair;
import org.junit.jupiter.api.Assertions;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.SimpleVerifier;
import org.objectweb.asm.util.CheckMethodAdapter;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

public abstract class TestASM {
	protected final String asmName = getClass().getName().replace('.', '/');

	protected static MethodNode getMethodNode(String owner, String name) {
		ClassNode cls = ClassNodeCache.get(owner);
		List<MethodNode> methods = cls.methods.stream().filter(mn -> mn.name.equals(name)).collect(Collectors.toList());
		assertEquals(methods.size(), 1);
		MethodNode mn = methods.get(0);
		new LambdaPreprocessor(mn).preprocess();
		return new InlineMethod(mn, owner).mth; // copy method
	}

	protected InterFrame[] analyzeMethod(String owner, String name) {
		MethodNode toAnalyze = getMethodNode(owner, name);
		return analyzeMethod(owner, toAnalyze);
	}

	protected InterFrame[] analyzeMethod(String owner, MethodNode mn) {
		return analyzeMethod(owner, mn, new ExhaustiveOracle());
	}

	protected InterFrame[] analyzeMethod(String owner, MethodNode mn, Oracle oracle) {
		try {
			Context initialContext = InterproceduralTypePointerAnalysis.startAnalysis(owner, mn, oracle);

			for (Map.Entry<Context, InterFrame[]> e : InterproceduralTypePointerAnalysis.calls.entrySet()) {
				assertEquals(e.getKey().getMethod().instructions.size(), e.getValue().length);
			}

			return InterproceduralTypePointerAnalysis.calls.get(initialContext);
		} catch(AnalyzerException exc) { throw new RuntimeException(exc); }
	}

	protected static void checkMethod(String owner, MethodNode mn) {
		CheckMethodAdapter checker = new CheckMethodAdapter(mn.access, mn.name, mn.desc,
				new MethodNode(Opcodes.ASM7, mn.access, mn.name, mn.desc, null, mn.exceptions.toArray(new String[]{})) {
					@Override
					public void visitEnd() {
						try {
							SimpleVerifier verifier = new SimpleVerifier(null, null, false);
							Analyzer<BasicValue> analyzer = new Analyzer<>(verifier);
							analyzer.analyze(owner, this);
						} catch(AnalyzerException exc) {
							throw new RuntimeException(exc);
						}
					}
				}, new HashMap<>());
		checker.version = Opcodes.V1_8; // Need to set this to avoid errors
		mn.accept(checker);
	}

	protected static void fullTransform(String owner, MethodNode mn) throws AnalyzerException {
		fullTransform(owner, mn, new StreamLibraryOracle());
	}

	protected static void fullTransform(String owner, MethodNode mn, Oracle oracle) throws AnalyzerException {
		new InlineAndAllocateTransformer(owner, mn, oracle, true).transform();
		new LocalVariableCleanup(owner, mn).run();
		SlidingWindowOptimizer.run(mn);
		new LambdaPreprocessor(mn).postprocess();
	}

	protected InterValue getReturnValue(String owner, String name) throws AnalyzerException {
		MethodNode mn = getMethodNode(owner, name);
		InterFrame[] res = analyzeMethod(owner, mn);
		InterFrame returnFrame = Utils.getReturnFrame(mn, res, new InterInterpreter(), InterFrame::new);
		return returnFrame.pop();
	}

	private static class ByteClassLoader extends ClassLoader {
		protected ByteClassLoader() {
			super();

			for(Pair<String, byte[]> cls : LambdaPreprocessor.classes) {
				byte[] bytes = cls.getSecond();
				try {
					defineClass(cls.getFirst().replace("/", "."), bytes, 0, bytes.length);
				} catch(IllegalAccessError exc) {
					if(!exc.getMessage().contains("superinterface"))
						throw exc;
				}
			}
		}

		Class<?> loadClass(String name, byte[] bytes) {
			return defineClass(name, bytes, 0, bytes.length);
		}
	}

	protected static Class<?> getClassWithReplacedMethod(String owner, MethodNode mn) {
		String javaName = owner.replace('/', '.');
		ClassNode origCn = ClassNodeCache.get(owner);
		// Replace method with updated version (we copy the classnode first)
		ClassNode cn = new ClassNode();
		origCn.accept(cn);

		replace: {
			for (MethodNode mth : cn.methods) {
				if (mth.name.equals(mn.name) && mth.desc.equals(mn.desc)) {
					cn.methods.remove(mth);
					cn.methods.add(mn);
					break replace;
				}
			}

			Assertions.fail("Did not find method to replace!");
		}

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		cn.accept(cw);

		byte[] bytes = cw.toByteArray();

		return new ByteClassLoader().loadClass(javaName, bytes);
	}

	protected static Pair<Object, String> executeMethod(Class<?> cls, MethodNode mn, Object thisObject, Object... args) throws InvocationTargetException, IllegalAccessException {
		return executeMethod(cls, mn.name, mn.desc, thisObject, args);
	}

	protected static Pair<Object, String> executeMethod(Class<?> cls, String name, String desc, Object thisObject, Object... args) throws InvocationTargetException, IllegalAccessException {
		for (Method method : cls.getDeclaredMethods()) {
			if (method.getName().equals(name) && Type.getMethodDescriptor(method).equals(desc)) {
				method.setAccessible(true);
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				PrintStream out = System.out;
				try {
					System.setOut(new PrintStream(baos));
					return new Pair<>(method.invoke(thisObject, args), baos.toString("UTF-8"));
				} catch (UnsupportedEncodingException e) {
					throw new RuntimeException(e);
				} finally {
					System.setOut(out);
				}
			}
		}

		Assertions.fail("Did not find replaced method!");
		return null;
	}

	@FunctionalInterface
	protected interface ClassSetup {
		static ClassSetup invStatic(Object... args) {
			return new ClassSetup() {
				@Override
				public Pair<Object, Object[]> setup(Class<?> cls) {
					return new Pair<>(null, args);
				}

				@Override
				public String toString() {
					return Arrays.deepToString(args);
				}
			};
		}

		Pair<Object, Object[]> setup(Class<?> cls) throws ReflectiveOperationException;
	}

	protected static void transformAndRunTest(String owner, String methodName, ClassSetup setup, Oracle oracle) {
		MethodNode mn = getMethodNode(owner, methodName);

		assertDoesNotThrow(() -> {
			fullTransform(owner, mn, oracle);
			checkMethod(owner, mn);

			Utils.printMethod(mn);
			System.out.println(Decompile.run(mn));

			try {
				Class<?> origCls = Class.forName(owner.replace('/', '.'));
				Pair<Object, Object[]> args = setup.setup(origCls);
				Pair<Object, String> expected = executeMethod(origCls, mn, args.getFirst(), args.getSecond());

				Class<?> cls = getClassWithReplacedMethod(owner, mn);
				args = setup.setup(cls);
				Pair<Object, String> res = executeMethod(cls, mn, args.getFirst(), args.getSecond());

				System.out.println("" + expected.getFirst() + " == " + res.getFirst());
				assertEquals(expected.getFirst(), res.getFirst());
				assertEquals(expected.getSecond(), res.getSecond());
				if(expected.getSecond().length() != 0) System.out.format("Outputs match:\n%s", expected.getSecond());
			} catch(InvocationTargetException exc) {
				throw exc.getTargetException();
			}
		});
	}
}
