package dk.casa.streamliner.other.testtransform;

class Base {
	int x = 10;
}

class Sub extends Base {
	int getInt() {
		return x;
	}
}

public class SubField {
	public static void main(String[] args) {
		int r = new Sub().getInt();
	}
}
