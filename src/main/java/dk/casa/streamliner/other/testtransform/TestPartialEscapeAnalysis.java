package dk.casa.streamliner.other.testtransform;

class A {
	public int a = 10;
	public int getA() { return a; }
}

class B {
	public A obj;
	public B(A obj) { this.obj = obj; }
	public void func(int x) {}
}

class C {
	public B bobj;
}

public class TestPartialEscapeAnalysis {
	private static int test() {
		A obj = new A();
		int a = obj.getA();
		return a + 10;
	}

	private static int testArg(A o) {
		new A();
		return o.getA();
	}

	private static int testArg2(A o) {
		A a = new A();
		int b = a.getA();
		return o.a;
	}

	private static void testArg3(B b) {
		// Since the newly allocated object is reachable from b it should escape
		b.obj = new A();
		b.func(10);
	}

	public static void main(String[] args) {
		System.out.println(test());
	}

	private static void testEscapeToArg(B b) {
		b.obj = new A();
	}

	private static void testImpreciseEscapeToArg(B b) {
		b.obj = (b.obj == null ? new A() : new A());
	}

	private static B testEscapeToReturnValue() {
		return new B(new A());
	}

	private static B someEscapeSomeDont() {
		B b = new B(null); // 0
		if(b.obj == null) {
			b.obj = new A(); // 1
			return b;
		} else {
			b.obj = new A(); // unreachable
		}

		int r20 = b.obj.getA() + 10;
		b.obj = new A(); // unreachable

		return b;
	}

	private static void putGetOnEscape(C c) {
		A a = new A();   // 1
		A a2 = new A();  // 2
		B b = new B(a);  // 3
		c.bobj = b;
		B b2 = c.bobj;
		b2.obj = a2;
		// The best we can hope for is that 3.obj is 2
		// It is sound that 3.obj is Top
		// Unsound if 3.obj is 1
	}

	private static void fieldDefaultValue() {
		C c = new C();
		if(c.bobj == null)
			c.bobj = null;
	}
}
