package com.tinkerpop.gremlin.process.computer.traversal;

import com.tinkerpop.gremlin.process.PathTraverser;
import com.tinkerpop.gremlin.process.SimpleTraverser;
import com.tinkerpop.gremlin.process.Traversal;
import com.tinkerpop.gremlin.process.Traverser;
import com.tinkerpop.gremlin.process.computer.GraphComputer;
import com.tinkerpop.gremlin.process.computer.MapReduce;
import com.tinkerpop.gremlin.process.computer.Memory;
import com.tinkerpop.gremlin.process.computer.MessageType;
import com.tinkerpop.gremlin.process.computer.Messenger;
import com.tinkerpop.gremlin.process.computer.VertexProgram;
import com.tinkerpop.gremlin.process.computer.traversal.step.sideEffect.mapreduce.TraversalResultMapReduce;
import com.tinkerpop.gremlin.process.computer.util.AbstractBuilder;
import com.tinkerpop.gremlin.process.computer.util.LambdaType;
import com.tinkerpop.gremlin.process.graph.marker.MapReducer;
import com.tinkerpop.gremlin.process.graph.step.sideEffect.GraphStep;
import com.tinkerpop.gremlin.process.graph.step.sideEffect.SideEffectCapStep;
import com.tinkerpop.gremlin.process.util.EmptyStep;
import com.tinkerpop.gremlin.process.util.TraversalHelper;
import com.tinkerpop.gremlin.structure.Edge;
import com.tinkerpop.gremlin.structure.Graph;
import com.tinkerpop.gremlin.structure.Vertex;
import org.apache.commons.configuration.Configuration;
import org.javatuples.Pair;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;


/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public class TraversalVertexProgram<M extends TraversalMessage> implements VertexProgram<M> {

    // TODO: if not an adjacent traversal, use Local message types
    // TODO: a dual messaging system
    // TODO: thread local for Traversal so you don't have to keep compiling it over and over again

    private static final String VOTE_TO_HALT = "gremlin.traversalVertexProgram.voteToHalt";
    public static final String TRAVERSER_TRACKER = Graph.Key.hide("gremlin.traverserTracker");
    private static final String TRAVERSAL_SUPPLIER_TYPE_KEY = "gremlin.traversalVertexProgram.traversalSupplierType";
    private static final String TRAVERSAL_SUPPLIER_KEY = "gremlin.traversalVertexProgram.traversalSupplier";

    private LambdaType lambdaType;
    private Pair<?, Supplier<Traversal>> traversalPair;
    private ThreadLocal<Traversal> traversal = new ThreadLocal<>();

    private boolean trackPaths = false;
    public List<MapReduce> mapReducers = new ArrayList<>();
    private Set<String> elementComputeKeys = new HashSet<String>() {{
        add(TRAVERSER_TRACKER);
    }};

    private TraversalVertexProgram() {
    }

    @Override
    public void loadState(final Configuration configuration) {
        this.lambdaType = LambdaType.getType(configuration, TRAVERSAL_SUPPLIER_TYPE_KEY);
        this.traversalPair = this.lambdaType.get(configuration, TRAVERSAL_SUPPLIER_KEY);

        final Traversal<?, ?> traversal = this.traversalPair.getValue1().get();
        this.trackPaths = TraversalHelper.trackPaths(traversal);
        traversal.getSteps().stream().filter(step -> step instanceof MapReducer).forEach(step -> {
            final MapReduce mapReduce = ((MapReducer) step).getMapReduce();
            this.mapReducers.add(mapReduce);
            this.elementComputeKeys.add(Graph.Key.hide(mapReduce.getSideEffectKey()));
        });

        if (!(TraversalHelper.getEnd(traversal) instanceof SideEffectCapStep))
            this.mapReducers.add(new TraversalResultMapReduce());

    }

    @Override
    public void storeState(final Configuration configuration) {
        configuration.setProperty(GraphComputer.VERTEX_PROGRAM, TraversalVertexProgram.class.getName());
        this.lambdaType.set(configuration, TRAVERSAL_SUPPLIER_TYPE_KEY, TRAVERSAL_SUPPLIER_KEY, this.traversalPair.getValue0());
    }

    public Traversal getTraversal() {
        try {
            Traversal traversal = this.traversal.get();
            if (null != traversal)
                return traversal;
            else {
                traversal = this.traversalPair.getValue1().get();
                this.traversal.set(traversal);
                return traversal;
            }

        } catch (final Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public void setup(final Memory memory) {
        memory.set(VOTE_TO_HALT, true);
    }

    @Override
    public void execute(final Vertex vertex, final Messenger<M> messenger, Memory memory) {
        if (memory.isInitialIteration()) {
            executeFirstIteration(vertex, messenger, memory);
        } else {
            executeOtherIterations(vertex, messenger, memory);
        }
    }

    private void executeFirstIteration(final Vertex vertex, final Messenger<M> messenger, final Memory memory) {
        final Traversal traversal = this.getTraversal();

        final GraphStep startStep = (GraphStep) traversal.getSteps().get(0);   // TODO: make this generic to Traversal
        final String future = startStep.getNextStep() instanceof EmptyStep ? Traverser.System.NO_FUTURE : startStep.getNextStep().getLabel();
        final AtomicBoolean voteToHalt = new AtomicBoolean(true);               // TODO: SIDE-EFFECTS IN TRAVERSAL IN OLAP!
        if (Vertex.class.isAssignableFrom(startStep.returnClass)) {
            final Traverser.System<Vertex> traverser = this.trackPaths ?
                    new PathTraverser<>(startStep.getLabel(), vertex, null) :
                    new SimpleTraverser<>(vertex, null);
            traverser.setFuture(future);
            messenger.sendMessage(MessageType.Global.of(vertex), TraversalMessage.of(traverser));
            voteToHalt.set(false);
        } else if (Edge.class.isAssignableFrom(startStep.returnClass)) {
            vertex.outE().forEach(e -> {
                final Traverser.System<Edge> traverser = this.trackPaths ?
                        new PathTraverser<>(startStep.getLabel(), e, null) :
                        new SimpleTraverser<>(e, null);
                traverser.setFuture(future);
                messenger.sendMessage(MessageType.Global.of(vertex), TraversalMessage.of(traverser));
                voteToHalt.set(false);
            });
        }
        if (this.trackPaths)
            vertex.property(TRAVERSER_TRACKER, new TraverserPathTracker());
        else
            vertex.property(TRAVERSER_TRACKER, new TraverserCountTracker());

        memory.and(VOTE_TO_HALT, voteToHalt.get());
    }

    private void executeOtherIterations(final Vertex vertex, final Messenger<M> messenger, final Memory memory) {
        final Traversal traversal = this.getTraversal();

        if (this.trackPaths) {
            memory.and(VOTE_TO_HALT, TraversalPathMessage.execute(vertex, messenger, traversal));
            vertex.<TraverserPathTracker>value(TRAVERSER_TRACKER).completeIteration();
        } else {
            memory.and(VOTE_TO_HALT, TraversalCounterMessage.execute(vertex, messenger, traversal));
            vertex.<TraverserCountTracker>value(TRAVERSER_TRACKER).completeIteration();
        }

    }

    @Override
    public boolean terminate(final Memory memory) {
        final boolean voteToHalt = memory.<Boolean>get(VOTE_TO_HALT);
        if (voteToHalt) {
            return true;
        } else {
            memory.set(VOTE_TO_HALT, true);
            return false;
        }
    }

    @Override
    public Set<String> getElementComputeKeys() {
        return this.elementComputeKeys;
    }

    @Override
    public List<MapReduce> getMapReducers() {
        return this.mapReducers;
    }

    @Override
    public Set<String> getMemoryComputeKeys() {
        final Set<String> keys = new HashSet<>();
        keys.add(VOTE_TO_HALT);
        return keys;
    }

    @Override
    public String toString() {
        final Traversal traversal = this.getTraversal();
        traversal.strategies().apply();
        return this.getClass().getSimpleName() + traversal.toString();
    }

    @Override
    public Features getFeatures() {
        return new Features() {
            @Override
            public boolean requiresGlobalMessageTypes() {
                return true;
            }

            @Override
            public boolean requiresVertexPropertyAddition() {
                return true;
            }
        };
    }

    //////////////

    public static Builder build() {
        return new Builder();
    }

    public static class Builder extends AbstractBuilder<Builder> {

        public Builder() {
            super(TraversalVertexProgram.class);
        }

        public Builder traversal(final String scriptEngine, final String traversalScript) {
            LambdaType.SCRIPT.set(this.configuration, TRAVERSAL_SUPPLIER_TYPE_KEY, TRAVERSAL_SUPPLIER_KEY, new String[]{scriptEngine, traversalScript});
            return this;
        }

        public Builder traversal(final Supplier<Traversal> traversal) {
            LambdaType.OBJECT.set(this.configuration, TRAVERSAL_SUPPLIER_TYPE_KEY, TRAVERSAL_SUPPLIER_KEY, traversal);
            return this;
        }

        public Builder traversal(final Class<Supplier<Traversal>> traversalClass) {
            LambdaType.CLASS.set(this.configuration, TRAVERSAL_SUPPLIER_TYPE_KEY, TRAVERSAL_SUPPLIER_KEY, traversalClass);
            return this;
        }

        // TODO Builder resolveElements(boolean) to be fed to ComputerResultStep
    }

}