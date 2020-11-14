package dk.casa.streamliner.asm;

import dk.casa.streamliner.asm.analysis.inter.Context;
import dk.casa.streamliner.asm.analysis.inter.oracles.StreamLibraryOracle;
import dk.casa.streamliner.asm.transform.InlineAndAllocateTransformer;
import dk.casa.streamliner.asm.transform.LambdaPreprocessor;
import dk.casa.streamliner.asm.transform.LocalVariableCleanup;
import dk.casa.streamliner.asm.transform.SlidingWindowOptimizer;
import org.apache.commons.io.FileUtils;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static org.objectweb.asm.Opcodes.ASM7;

class InlineClassTransformer extends ClassVisitor {
    private String owner;

    private final Set<String> methodsToOptimise;

    public InlineClassTransformer(ClassVisitor cv, Set<String> methodsToOptimise) {
        super(ASM7, cv);
        this.methodsToOptimise = methodsToOptimise;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        owner = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    private static class LambdaTrackerOracle extends StreamLibraryOracle {
	    @Override
	    public boolean shouldTrackAllocation(Context context, Type type) {
	    	if(type.getInternalName().contains("LambdaModel$")) return true;
		    return super.shouldTrackAllocation(context, type);
	    }
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
        if(methodsToOptimise.contains(name)) {
            return new MethodNode(ASM7, access, name, descriptor, signature, exceptions) {
                @Override
                public void visitEnd() {
	                try {
		                System.out.println("Original size of " + name + ": " + instructions.size());
		                LambdaPreprocessor preprocessor = new LambdaPreprocessor(this);
		                preprocessor.preprocess();
		                new InlineAndAllocateTransformer(owner, this, new LambdaTrackerOracle(), true).transform();
		                new LocalVariableCleanup(owner, this).run();
		                preprocessor.postprocess();
		                SlidingWindowOptimizer.run(this);

		                //System.out.println(owner + "." + name + Decompile.run(this));
	                } catch(AnalyzerException e) {
		                throw new RuntimeException(e);
	                }

	                accept(methodVisitor);
                }
            };
        } else
            return methodVisitor;
    }
}

public class TransformASM {
	private static final ClassLoader classLoader = TransformASM.class.getClassLoader();

    public static void main(String[] args) throws IOException {
        FileUtils.copyDirectory(new File("out/classes"), new File("out/asm"));

        Set<String> jmhOptimise = new HashSet<>(Arrays.asList(
                "sum", "sumOfSquares", "sumOfSquaresEven",
                "count", "filterCount", "filterMapCount",
                "megamorphicMaps", "megamorphicFilters",
		        "allMatch"));

        Set<String> jmhOptimiseWCart = new HashSet<>(jmhOptimise);
        jmhOptimiseWCart.add("cart");

        transform("dk.casa.streamliner.jmh.TestPushOpt", jmhOptimiseWCart);
        transform("dk.casa.streamliner.jmh.TestPullOpt", jmhOptimise);
        transform("dk.casa.streamliner.jmh.TestStreamOpt", jmhOptimiseWCart);
    }

    public static void transform(String cls, Set<String> methodsToOptimise) throws IOException {
        String internalName = cls.replace(".", "/");
        try(InputStream is = classLoader.getResourceAsStream(internalName + ".class");
            FileOutputStream fos = new FileOutputStream((String.format("out/asm/%s.class", internalName)))) {

            ClassReader cr = new ClassReader(Objects.requireNonNull(is));

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
            TraceClassVisitor tcv = new TraceClassVisitor(cw, new PrintWriter(System.out));
            CheckClassAdapter cca = new CheckClassAdapter(tcv);
            InlineClassTransformer cp = new InlineClassTransformer(cca, methodsToOptimise);

            cr.accept(cp, ClassReader.EXPAND_FRAMES);

            fos.write(cw.toByteArray());
        }
    }
}
