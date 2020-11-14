package dk.casa.streamliner.asm;

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.ClassFileSource;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;
import org.benf.cfr.reader.state.ClassFileSourceImpl;
import org.benf.cfr.reader.util.getopt.OptionsImpl;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.util.*;

import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.V13;

public class Decompile {
	private static class DataSource implements ClassFileSource {
		private final byte[] bytes;
		private final String wClass;
		private final ClassFileSource def = new ClassFileSourceImpl(
				OptionsImpl.getFactory().create(Collections.singletonMap("extraclasspath", "out/production/streamliner")));

		DataSource(ClassNode cn) {
			wClass = cn.name + ".class";

			ClassWriter cw = new ClassWriter(0);
			cn.accept(cw);
			bytes = cw.toByteArray();
		}

		@Override
		public void informAnalysisRelativePathDetail(String usePath, String classFilePath) {
			def.informAnalysisRelativePathDetail(usePath, classFilePath);
		}

		@Override
		public Collection<String> addJar(String jarPath) {
			return def.addJar(jarPath);
		}

		@Override
		public String getPossiblyRenamedPath(String path) {
			if(path.startsWith("dk/casa/streamliner"))
				return "out/production/streamliner/" + path;
			return def.getPossiblyRenamedPath(path);
		}

		@Override
		public Pair<byte[], String> getClassFileContent(String path) throws IOException {
			if(!path.equals(wClass)) return def.getClassFileContent(path);
			return Pair.make(bytes, wClass);
		}
	}

	private static class StringSinkFactory implements OutputSinkFactory {
		private final StringBuilder sb;

		private StringSinkFactory(StringBuilder sb) {
			this.sb = sb;
		}

		@Override
		public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
			return Arrays.asList(SinkClass.EXCEPTION_MESSAGE, SinkClass.STRING);
		}

		@Override
		public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
			switch(sinkType) {
				case JAVA:
					return sb::append;

				case EXCEPTION:
					return (T exc) -> ((SinkReturns.ExceptionMessage) exc).getThrownException().printStackTrace();

				default:
					return ignore -> {};
			}
		}
	}

	public static String run(ClassNode cn) {
		DataSource source = new DataSource(cn);
		StringBuilder sb = new StringBuilder();
		CfrDriver driver = new CfrDriver.Builder()
				.withClassFileSource(source)
				.withOutputSink(new StringSinkFactory(sb))
				.build();
		driver.analyse(Collections.singletonList(cn.name));
		return sb.toString();
	}


	public static String run(MethodNode mn) {
		ClassNode cn = new ClassNode();
		cn.version = V13;
		cn.name = "Dummy";
		cn.superName = "java/lang/Object";
		cn.access = ACC_PUBLIC;
		cn.methods = Collections.singletonList(mn);
		String res = run(cn);
		if(res.isEmpty()) return res;
		int idx = res.indexOf('{');
		return res.substring(idx + 1, res.length() - 2);
	}
}
