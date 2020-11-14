package dk.casa.streamliner.asm;

import dk.casa.streamliner.asm.analysis.inter.CallGraph;
import dk.casa.streamliner.asm.analysis.inter.Context;
import dk.casa.streamliner.asm.analysis.inter.InterproceduralTypePointerAnalysis;
import dk.casa.streamliner.asm.analysis.inter.oracles.StreamLibraryOracle;
import dk.casa.streamliner.asm.transform.LambdaPreprocessor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class MakeCallgraph {
    public static void main(String[] args) {
        if(args.length < 2) {
            System.out.println("Usage: java MakeCallgraph <class> <method>");
            System.exit(1);
        }

        String owner = args[0],
                method = args[1];

        ClassNode cn = ClassNodeCache.get(owner);
        MethodNode mn = Utils.getMethod(cn, m -> m.name.equals(method))
                .orElseThrow(() -> new RuntimeException("Method '" + method + "' does not exist."));

        try {
            LambdaPreprocessor preprocessor = new LambdaPreprocessor(mn);
            preprocessor.preprocess();

            Context initialContext = InterproceduralTypePointerAnalysis.startAnalysis(owner, mn, new StreamLibraryOracle());

            CallGraph callGraph = new CallGraph(initialContext);

            Files.write(Paths.get(String.format("%s_cg.dot", method)),
                    callGraph.toDot("callgraph").getBytes(StandardCharsets.UTF_8));

        } catch (AnalyzerException | IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
