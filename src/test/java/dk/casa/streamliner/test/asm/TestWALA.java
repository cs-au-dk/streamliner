package dk.casa.streamliner.test.asm;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.demandpa.alg.DemandRefinementPointsTo;
import com.ibm.wala.demandpa.alg.refinepolicy.NeverRefineCGPolicy;
import com.ibm.wala.demandpa.alg.refinepolicy.NeverRefineFieldsPolicy;
import com.ibm.wala.demandpa.alg.refinepolicy.RefinementPolicyFactory;
import com.ibm.wala.demandpa.alg.refinepolicy.SinglePassRefinementPolicy;
import com.ibm.wala.demandpa.alg.statemachine.DummyStateMachine;
import com.ibm.wala.demandpa.alg.statemachine.StateMachineFactory;
import com.ibm.wala.demandpa.flowgraph.IFlowLabel;
import com.ibm.wala.demandpa.util.MemoryAccessMap;
import com.ibm.wala.demandpa.util.SimpleMemoryAccessMap;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.cha.CHACallGraph;
import com.ibm.wala.ipa.callgraph.impl.DefaultEntrypoint;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.HeapModel;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.types.Selector;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.collections.Pair;
import com.ibm.wala.util.config.AnalysisScopeReader;
import dk.casa.streamliner.utils.Utils;
import dk.casa.streamliner.asm.analysis.MethodIdentifier;
import dk.casa.streamliner.asm.analysis.inter.Context;
import dk.casa.streamliner.asm.analysis.inter.InterValue;
import dk.casa.streamliner.asm.analysis.inter.oracles.StreamLibraryOracle;
import dk.casa.streamliner.asm.analysis.inter.oracles.WALAOracle;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.StreamSupport;

import static com.ibm.wala.ipa.callgraph.impl.Everywhere.EVERYWHERE;
import static org.junit.jupiter.api.Assertions.*;

public class TestWALA extends TestASM {

    public static void entrypoint() {
        List<String> stringList = new ArrayList<>();
        interestingMethod(stringList);
    }

    public static void interestingMethod(List<String> list) {
        list.stream().filter(x -> x.startsWith("asd")).count();
    }

    @Test
    public void testBasic() throws ClassHierarchyException, CancelException, IOException {
        String classpath = "out/classes:out/test-classes";
        AnalysisScope scope = AnalysisScopeReader.makeJavaBinaryAnalysisScope(classpath, null);
        // We need a baseline call graph.  Here we use a CHACallGraph based on a ClassHierarchy.
        ClassHierarchy cha = ClassHierarchyFactory.make(scope);
        CHACallGraph chaCG = new Utils.CHACallGraph(cha);

        IClass cls = cha.lookupClass(TypeReference.findOrCreate(scope.getApplicationLoader(), "L" + asmName));
        IMethod method = cls.getMethod(Selector.make("entrypoint()V"));

        chaCG.init(Collections.singletonList(new DefaultEntrypoint(method, cha)));
        AnalysisOptions options = new AnalysisOptions();
        IAnalysisCacheView cache = new AnalysisCacheImpl();
        // We also need a heap model to create InstanceKeys for allocation sites, etc.
        // Here we use a 0-1 CFA builder, which will give a heap abstraction similar to
        // context-insensitive Andersen's analysis
        HeapModel heapModel = Util.makeZeroOneCFABuilder(Language.JAVA, options, cache, cha, scope);
        // The MemoryAccessMap helps the demand analysis find matching field reads and writes
        MemoryAccessMap mam = new SimpleMemoryAccessMap(chaCG, heapModel, false);
        // The StateMachineFactory helps in tracking additional states like calling contexts.
        // For context-insensitive analysis we use a DummyStateMachine.Factory
        StateMachineFactory<IFlowLabel> stateMachineFactory = new DummyStateMachine.Factory<>();
        DemandRefinementPointsTo drpt =
                DemandRefinementPointsTo.makeWithDefaultFlowGraph(
                        chaCG, heapModel, mam, cha, options, stateMachineFactory);
        // The RefinementPolicyFactory determines how the analysis refines match edges (see PLDI'06
        // paper).  Here we use a policy that does not perform refinement and just uses a fixed budget
        // for a single pass
        RefinementPolicyFactory refinementPolicyFactory =
                new SinglePassRefinementPolicy.Factory(
                        new NeverRefineFieldsPolicy(), new NeverRefineCGPolicy(), 1000);
        drpt.setRefinementPolicyFactory(refinementPolicyFactory);

        IMethod analysisMethod = cls.getMethod(Selector.make("interestingMethod(Ljava/util/List;)V"));
        CGNode node = chaCG.getNode(analysisMethod, EVERYWHERE);

        IR ir = node.getIR();
        assertNotNull(ir);
        CallSiteReference streamCall = StreamSupport.stream(Spliterators.spliteratorUnknownSize(ir.iterateCallSites(), 0), false)
                .filter(csr -> csr.getDeclaredTarget().getName().toString().equals("stream"))
                .findFirst().orElseThrow(NoSuchElementException::new);

        System.out.println(streamCall);
        SSAAbstractInvokeInstruction[] calls = ir.getCalls(streamCall);
        assertEquals(1, calls.length);

        SSAAbstractInvokeInstruction call = calls[0];
        PointerKey pk = heapModel.getPointerKeyForLocal(node, call.getUse(0));
        Pair<DemandRefinementPointsTo.PointsToResult, Collection<InstanceKey>> pointsTo =
                drpt.getPointsTo(pk, k -> true);

        assertEquals(DemandRefinementPointsTo.PointsToResult.SUCCESS, pointsTo.fst);
        assertEquals(1, pointsTo.snd.size());

        InstanceKey ik = pointsTo.snd.iterator().next();

        assertEquals(cha.lookupClass(TypeReference.findOrCreateClass(scope.getPrimordialLoader(), "java/util", "ArrayList")),
                ik.getConcreteType());
    }

    private static class TestOracle extends StreamLibraryOracle {
        public boolean called = false;
        public Optional<Type> res;
        private final WALAOracle tOracle;

        public TestOracle(Collection<File> libraries, Collection<String> directories, Collection<MethodIdentifier> entrypoints) {
            tOracle = new WALAOracle(libraries, directories, entrypoints);
        }

        @Override
        public Optional<Type> queryType(Context context, MethodInsnNode minsn, InterValue receiver) {
            called = true;
            return res = tOracle.queryType(context, minsn, receiver);
        }
    }

    @Test
    public void testWALAOracle() {
        TestOracle oracle = new TestOracle(Collections.emptyList(), Collections.singletonList("out/test-classes"),
                Collections.singletonList(new MethodIdentifier(asmName, "entrypoint", "()V")));

        MethodNode mn = getMethodNode(asmName, "interestingMethod");
        analyzeMethod(asmName, mn, oracle);

        assertTrue(oracle.called);
        assertEquals(Optional.of(Type.getObjectType("java/util/ArrayList")), oracle.res);

        System.out.println(oracle.tOracle.queryStats);
    }
}
