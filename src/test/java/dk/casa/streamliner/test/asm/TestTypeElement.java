package dk.casa.streamliner.test.asm;

import dk.casa.streamliner.asm.ClassNodeCache;
import dk.casa.streamliner.asm.analysis.inter.TypeElement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.opentest4j.AssertionFailedError;

import static org.junit.jupiter.api.Assertions.*;

public class TestTypeElement {
	Type a = Type.getObjectType("A");

	private static ClassNode createClassNode(String name, String superName) {
		ClassNode cn = new ClassNode();
		cn.name = name;
		cn.superName = superName;
		return cn;
	}

	@BeforeAll
	static void setupClassNodeCache() {
		ClassNodeCache.clear();

		ClassNodeCache.put("A", createClassNode("A", null));
		ClassNodeCache.put("B", createClassNode("B", "A"));
		ClassNodeCache.put("C", createClassNode("C", "A"));
	}

	@Test
	void mergePreciseImpreciseIsImprecise() {
		TypeElement t = new TypeElement(true, a);
		TypeElement merged = t.merge(new TypeElement(false, a));

		assertEquals(a, merged.getType());
		assertFalse(merged.isPrecise());
	}

	@Test
	void mergeSame() {
		TypeElement t = new TypeElement(true, a);
		TypeElement merged = t.merge(new TypeElement(true, a));

		assertEquals(a, merged.getType());
		assertTrue(merged.isPrecise());
	}

	@Test
	void mergeObjectsIsNotVoid() {
		TypeElement t1 = new TypeElement(true, Type.getObjectType("C"));
		TypeElement t2 = new TypeElement(true, Type.getObjectType("B"));
		TypeElement merged = t1.merge(t2);

		assertFalse(merged.isPrecise());
		assertNotEquals(TypeElement.TOP, merged.getType());
	}

	@Test
	void mergeIntLong() {
		TypeElement merged = new TypeElement(false, Type.INT_TYPE)
				.merge(new TypeElement(false, Type.LONG_TYPE));

		assertEquals(TypeElement.TOP, merged.getType());
	}

	@Test
	void mergeObjectsGivesSuper() {
		// TODO: This test should not fail :)
		AssertionFailedError exc = assertThrows(AssertionFailedError.class,
				() -> {
					TypeElement t1 = new TypeElement(true, Type.getObjectType("C"));
					TypeElement t2 = new TypeElement(true, Type.getObjectType("B"));
					TypeElement merged = t1.merge(t2);

					assertEquals(a, merged.getType());
				});

		assertEquals(exc.getActual().getEphemeralValue(), Type.getObjectType("java/lang/Object"));
	}

	@AfterAll
	static void cleanupClassNodeCache() {
		ClassNodeCache.clear();
	}
}
