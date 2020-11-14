package dk.casa.streamliner.test.asm;

import dk.casa.streamliner.asm.analysis.inter.PointerElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestPointerElement {
	@MethodSource
	@ParameterizedTest
	public void testBasicLatticeOrder(PointerElement ptr) {
		assertTrue(ptr.leq(PointerElement.TOP), String.format("%s should be less than Top", ptr));
		assertEquals(ptr, ptr.merge(ptr));
	}

	public static Stream<PointerElement> testBasicLatticeOrder() {
		return Stream.of(PointerElement.uTOP, PointerElement.NULL, PointerElement.TOP, PointerElement.iTOP, new PointerElement(1));
	}

	@Test
	public void testMergeNull() {
		assertEquals(PointerElement.NULL, PointerElement.NULL.merge(PointerElement.NULL));
		assertEquals(PointerElement.iTOP, PointerElement.NULL.merge(PointerElement.iTOP));
		assertEquals(PointerElement.iTOP, PointerElement.iTOP.merge(PointerElement.NULL));
		assertEquals(PointerElement.uTOP, PointerElement.NULL.merge(PointerElement.uTOP));
		assertEquals(PointerElement.uTOP, PointerElement.uTOP.merge(PointerElement.NULL));
		assertEquals(PointerElement.iTOP, new PointerElement(1).merge(PointerElement.NULL));
	}

	@Test
	public void testMergeTwoPrecise() {
		assertEquals(PointerElement.iTOP, new PointerElement(1).merge(new PointerElement(2)));
	}
}
