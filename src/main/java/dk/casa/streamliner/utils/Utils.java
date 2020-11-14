package dk.casa.streamliner.utils;

import com.ibm.wala.ipa.cha.IClassHierarchy;

import java.util.concurrent.Callable;

public class Utils {
	public static boolean isJava8() {
		return System.getProperty("java.version").startsWith("1.8");
	}

	public static <T> boolean throwsExc(Callable<T> func) {
		try {
			func.call();
			return false;
		} catch(Exception exc) {
			return true;
		}
	}

	/** Override toString method because it blows up the debugger... */
	public static class CHACallGraph extends com.ibm.wala.ipa.callgraph.cha.CHACallGraph {
		public CHACallGraph(IClassHierarchy cha) {
			super(cha);
		}

		public CHACallGraph(IClassHierarchy cha, boolean applicationOnly) {
			super(cha, applicationOnly);
		}

		@Override
		public String toString() {
			return "CHACallGraph with: " + getNumberOfNodes() + " nodes.";
		}
	}
}
