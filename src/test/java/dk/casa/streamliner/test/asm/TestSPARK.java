package dk.casa.streamliner.test.asm;

import dk.casa.streamliner.asm.analysis.MethodIdentifier;
import dk.casa.streamliner.asm.analysis.inter.Context;
import dk.casa.streamliner.asm.analysis.inter.InterValue;
import dk.casa.streamliner.asm.analysis.inter.oracles.SPARKOracle;
import dk.casa.streamliner.other.testtransform.CollectionStreams;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import soot.*;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.Stmt;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSPARK extends TestASM {

	public static void entrypoint() {
		List<String> strings = new ArrayList<>();
		interestingMethod(strings);
	}

	private static void interestingMethod(List<String> strings) {
		strings.stream();
	}

	private static SootClass initSoot(String mainClass) {
		SPARKOracle.initSoot("out/classes:out/test-classes", "");
		SootClass c = Scene.v().forceResolve(mainClass, SootClass.BODIES);
		c.setApplicationClass();
		return c;
	}

	@Test
	public void testSPARK() {
		String mainClass = getClass().getName();
		SootClass c = initSoot(mainClass);
		SootMethod m = c.getMethodByName("entrypoint");
		Scene.v().setEntryPoints(Collections.singletonList(m));

		analyze(c.getMethodByName("interestingMethod"));
	}

	public static void entrypoint2() {
		List<String> strings = new ArrayList<>();
		Supplier<Stream<String>> supplier = () -> strings.stream().filter(x -> x.startsWith("asd"));
		supplier.get().anyMatch(s -> true);
	}

	@Test
	public void testSPARKLambda() {
		String mainClass = getClass().getName();
		SootClass c = initSoot(mainClass);
		Scene.v().setEntryPoints(Collections.singletonList(c.getMethodByName("entrypoint2")));

		analyze(c.getMethodByName("lambda$entrypoint2$1"));
	}

	private void analyze(SootMethod m) {
		PackManager.v().getPack("cg").apply();

		Local query = getQuery(m);
		System.out.println("Solving query: " + query);
		PointsToSet pts = Scene.v().getPointsToAnalysis().reachingObjects(query);

		System.out.println("All allocation sites of the query variable are:");
		System.out.println(pts);

		System.out.println("Possible types for the query variable:");
		Set<soot.Type> possibleTypes = pts.possibleTypes();
		System.out.println(possibleTypes);
		assertEquals(1, possibleTypes.size());
		assertEquals(RefType.v("java.util.ArrayList"), possibleTypes.iterator().next());
	}

	private Local getQuery(SootMethod method) {
		if (!method.hasActiveBody()) {
			return null;
		}
		for (Unit u : method.getActiveBody().getUnits()) {
			if (!(u instanceof Stmt)) {
				continue;
			}
			Stmt s = (Stmt) u;
			if (!s.containsInvokeExpr()) {
				continue;
			}
			if (s.toString().contains("stream")) {
				Value base = ((InstanceInvokeExpr) s.getInvokeExpr()).getBase();
				assertTrue(base instanceof Local);
				return (Local) base;
			}
		}

		Assertions.fail("Couldn't fint a call to stream in " + method + "?");
		return null;
	}

	private static class MockSPARKOracle extends SPARKOracle {
		public int calls = 0;
		public Optional<Type> lastResult;

		public MockSPARKOracle(String classPath, List<MethodIdentifier> entryPoints) {
			super(classPath, entryPoints);
		}

		@Override
		public Optional<Type> queryType(Context context, MethodInsnNode minsn, InterValue receiver) {
			calls++;
			return lastResult = super.queryType(context, minsn, receiver);
		}
	}

	@Test
	public void testOracle() {
		MethodNode mn = getMethodNode(asmName, "interestingMethod");
		MethodIdentifier entry = new MethodIdentifier(asmName, "entrypoint", Type.getMethodDescriptor(Type.VOID_TYPE));

		MockSPARKOracle oracle = new MockSPARKOracle("out/classes:out/test-classes", Collections.singletonList(entry));
		analyzeMethod(asmName, mn, oracle);
		assertEquals(1, oracle.calls);
		assertEquals(Optional.of(Type.getObjectType("java/util/ArrayList")), oracle.lastResult);
	}

	public void crossFileQueryEntrypoint() {
		List<Integer> list = new ArrayList<>();
		CollectionStreams.unknownCollectionNoTerminal(list);
	}

	@Test
	public void testOracleCrossFile() {
		String owner = "dk/casa/streamliner/other/testtransform/CollectionStreams";
		MethodNode mn = getMethodNode(owner, "unknownCollectionNoTerminal");

		MethodIdentifier entry = new MethodIdentifier(asmName, "crossFileQueryEntrypoint", Type.getMethodDescriptor(Type.VOID_TYPE));
		MockSPARKOracle oracle = new MockSPARKOracle("out/classes:out/test-classes", Collections.singletonList(entry));

		analyzeMethod(owner, mn, oracle);

		assertEquals(1, oracle.calls);
		assertEquals(Optional.of(Type.getObjectType("java/util/ArrayList")), oracle.lastResult);
	}
}
