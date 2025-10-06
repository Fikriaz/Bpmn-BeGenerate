package com.example.bpmn_generator.service;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.*;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Consumer;

@Service
public class DfsService {

    /* ===================== Options ===================== */

    public static final class Options {
        public int maxVisitsPerNode = 3;
        public int maxPaths = 50_000;
        public long timeLimitMillis = 30_000;
        public boolean includeBoundary = true;
        public boolean includeMessageFlow = true;
        public boolean strictGatewaySemantics = true;
        /** Inclusive Join konservatif: drop join bila ada cabang TIDAK terpilih yang masih bisa mencapai join. */
        public boolean inclusiveConservativeJoin = true;

        /** Batasi jumlah subset Inclusive agar tidak meledak. */
        public int inclusiveMaxSubsets = 64;
        /** Batasi fan-out Event-Based Parallel. */
        public int eventParallelFanoutCap = 8;

        /** Rem tambahan untuk model branchy */
        public int maxDepth = 300;              // stop jika path terlalu panjang
        public int genericFanoutCap = 12;       // cap outgoing biasa / XOR
        public int boundaryFanoutCap = 4;       // cap eksplorasi boundary per host
        public int messageFanoutCap = 4;        // cap eksplorasi message flow per node
        public int revisitWindow = 25;          // larang revisit node yang muncul dalam W langkah terakhir

        public Options() {}

        public Options maxVisitsPerNode(int v){ this.maxVisitsPerNode = Math.max(1, v); return this; }
        public Options maxPaths(int v){ this.maxPaths = Math.max(1, v); return this; }
        public Options timeLimitMillis(long v){ this.timeLimitMillis = Math.max(1, v); return this; }
        public Options includeBoundary(boolean v){ this.includeBoundary = v; return this; }
        public Options includeMessageFlow(boolean v){ this.includeMessageFlow = v; return this; }
        public Options strictGatewaySemantics(boolean v){ this.strictGatewaySemantics = v; return this; }
        public Options inclusiveConservativeJoin(boolean v){ this.inclusiveConservativeJoin = v; return this; }
        public Options inclusiveMaxSubsets(int v){ this.inclusiveMaxSubsets = Math.max(1, v); return this; }
        public Options eventParallelFanoutCap(int v){ this.eventParallelFanoutCap = Math.max(1, v); return this; }

        // setters rem
        public Options maxDepth(int v){ this.maxDepth = Math.max(1, v); return this; }
        public Options genericFanoutCap(int v){ this.genericFanoutCap = Math.max(1, v); return this; }
        public Options boundaryFanoutCap(int v){ this.boundaryFanoutCap = Math.max(1, v); return this; }
        public Options messageFanoutCap(int v){ this.messageFanoutCap = Math.max(1, v); return this; }
        public Options revisitWindow(int v){ this.revisitWindow = Math.max(1, v); return this; }
    }

    /* ===================== Structures for annotated paths ===================== */

    public static final class Group {
        public String type;        // "PARALLEL" | "INCLUSIVE"
        public String forkId;
        public String joinId;
        public int startIndex;     // index di nodes (posisi fork di path gabungan)
        public int endIndex;       // index di nodes (posisi join di path gabungan)
        public List<List<String>> branches = new ArrayList<>(); // list node-id per cabang (tanpa forkId, berakhir di joinId)

        public Group() {}
        public Group(String type, String forkId, String joinId, int startIndex, int endIndex, List<List<String>> branches) {
            this.type = type;
            this.forkId = forkId;
            this.joinId = joinId;
            this.startIndex = startIndex;
            this.endIndex = endIndex;
            if (branches != null) this.branches = branches;
        }
    }

    public static final class AnnotatedPath {
        public List<String> nodes;
        public List<Group> groups;
        public AnnotatedPath(List<String> nodes, List<Group> groups) {
            this.nodes = nodes;
            this.groups = groups;
        }
    }

    /* ===================== Public API ===================== */

    public List<List<String>> findAllPaths(BpmnModelInstance model) {
        return findAllPaths(model, new Options());
    }

    public List<List<String>> findAllPaths(BpmnModelInstance model, Options opt) {
        List<List<String>> out = new ArrayList<>(256);
        streamAllPaths(model, opt, out::add);
        return out;
    }

    public void streamAllPaths(BpmnModelInstance model, Options opt, Consumer<List<String>> sink) {
        if (model == null) throw new IllegalArgumentException("modelInstance null");
        final long deadline = System.nanoTime() + opt.timeLimitMillis * 1_000_000L;

        Graph g = buildGraph(model, opt);

        Deque<String> path = new ArrayDeque<>(128);
        Set<String> visitedDepthKey = new HashSet<>(2048);
        Map<String, Integer> visitCount = new HashMap<>(1024);

        Counter counter = new Counter();
        for (StartEvent s : g.rootStarts) {
            if (overBudget(counter, deadline, opt)) break;
            dfsRoot(g, s.getId(), path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
        }
    }

    /** ====== NEW: annotated variants ====== */

    public List<AnnotatedPath> findAllPathsAnnotated(BpmnModelInstance model) {
        return findAllPathsAnnotated(model, new Options());
    }

    public List<AnnotatedPath> findAllPathsAnnotated(BpmnModelInstance model, Options opt) {
        List<AnnotatedPath> out = new ArrayList<>(256);
        streamAllPathsAnnotated(model, opt, out::add);
        return out;
    }

    public void streamAllPathsAnnotated(BpmnModelInstance model, Options opt, Consumer<AnnotatedPath> sink) {
        if (model == null) throw new IllegalArgumentException("modelInstance null");
        final long deadline = System.nanoTime() + opt.timeLimitMillis * 1_000_000L;

        Graph g = buildGraph(model, opt);

        Deque<String> path = new ArrayDeque<>(128);
        Set<String> visitedDepthKey = new HashSet<>(2048);
        Map<String, Integer> visitCount = new HashMap<>(1024);
        List<Group> groups = new ArrayList<>();

        Counter counter = new Counter();
        for (StartEvent s : g.rootStarts) {
            if (overBudget(counter, deadline, opt)) break;
            dfsRootAnn(g, s.getId(), path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
        }
    }

    public List<List<String>> findAllPathsWithActor(BpmnModelInstance model) {
        List<List<String>> raw = findAllPaths(model);
        Map<String, String> nodeToLane = mapNodeToLane(model);

        List<List<String>> out = new ArrayList<>(raw.size());
        for (List<String> p : raw) {
            List<String> labeled = new ArrayList<>(p.size());
            for (String id : p) {
                FlowNode n = (FlowNode) model.getModelElementById(id);
                if (n == null) continue;
                String lane = nodeToLane.get(id);
                String actor = determineActor(n, lane);
                String name = (n.getName() != null && !n.getName().isBlank())
                        ? n.getName() : n.getElementType().getTypeName();
                labeled.add(!isBlank(lane) ? "[" + lane + "] " + name : "[" + actor + "] " + name);
            }
            if (!labeled.isEmpty()) out.add(labeled);
        }
        return out;
    }

    /* ===================== DFS (root) — plain ===================== */

    private void dfsRoot(
            Graph g, String currentId,
            Deque<String> path, Set<String> visitedDepthKey, Map<String, Integer> visitCount,
            Consumer<List<String>> sink, Counter counter, long deadline, Options opt
    ) {
        if (currentId == null || overBudget(counter, deadline, opt)) return;
        if (!enterNode(currentId, path, visitedDepthKey, visitCount, opt)) return;

        FlowNode cur = g.nodeById.get(currentId);

        // EndEvent (root)
        if (cur instanceof EndEvent && !g.insideSubprocess.contains(currentId)) {
            sink.accept(new ArrayList<>(path));
            counter.paths++;
            leaveNode(currentId, path, visitedDepthKey, visitCount);
            return;
        }

        // Event Sub-Process: skip aman (jangan expand, teruskan alur keluar)
        if (cur instanceof SubProcess && g.eventSubProcesses.contains(cur.getId())) {
            goOutgoing(g, currentId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
            if (opt.includeMessageFlow) goMessage(g, currentId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
            leaveNode(currentId, path, visitedDepthKey, visitCount);
            return;
        }

        // SubProcess
        if (cur instanceof SubProcess) {
            List<String> subStarts = g.subprocessStarts.get(currentId);
            if (subStarts != null && !subStarts.isEmpty()) {
                for (String s : subStarts) {
                    if (overBudget(counter, deadline, opt)) break;
                    dfsInsideSP(g, s, currentId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
                }
            } else {
                goOutgoing(g, currentId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
                if (opt.includeMessageFlow) goMessage(g, currentId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
            }
        }
        // Inclusive Gateway handling
        else if (cur instanceof InclusiveGateway && opt.strictGatewaySemantics) {
            int outCount = g.outgoing.getOrDefault(currentId, Collections.emptyList()).size();
            int inCount = (cur.getIncoming() != null) ? cur.getIncoming().size() : 0;

            if (outCount > 1) {
                handleForkWithJoin(g, currentId, GatewayType.INCLUSIVE, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
            } else if (inCount > 1 && outCount == 1) {
                goOutgoing(g, currentId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
            } else {
                goOutgoing(g, currentId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
            }
        }
        // Parallel Gateway
        else if (cur instanceof ParallelGateway && opt.strictGatewaySemantics) {
            int outCount = g.outgoing.getOrDefault(currentId, Collections.emptyList()).size();
            int inCount = (cur.getIncoming() != null) ? cur.getIncoming().size() : 0;

            if (outCount > 1) {
                handleForkWithJoin(g, currentId, GatewayType.PARALLEL, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
            } else if (inCount > 1 && outCount <= 1) {
                goOutgoing(g, currentId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
            } else {
                goOutgoing(g, currentId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
            }
        }
        // Event-Based Gateway
        else if (isEventBasedGateway(cur)) {
            if (isEventBasedParallel(cur)) {
                // (1) Terapkan eventParallelFanoutCap via limited handle
                List<String> outs = new ArrayList<>(g.outgoing.getOrDefault(currentId, Collections.emptyList()));
                if (outs.size() > opt.eventParallelFanoutCap) {
                    outs = outs.subList(0, opt.eventParallelFanoutCap);
                }
                handleForkWithJoinLimited(
                        g, currentId, GatewayType.PARALLEL, outs,
                        path, visitedDepthKey, visitCount, sink, counter, deadline, opt
                );
            } else {
                // Exclusive Event
                List<String> outs = g.outgoing.getOrDefault(currentId, Collections.emptyList());
                int lim = Math.min(outs.size(), opt.genericFanoutCap);
                for (int i = 0; i < lim; i++) {
                    if (overBudget(counter, deadline, opt)) break;
                    dfsRoot(g, outs.get(i), path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
                }
            }
        }
        else if (isExclusiveFork(cur, g)) {
            // XOR
            List<String> outs = g.outgoing.getOrDefault(currentId, Collections.emptyList());
            int lim = Math.min(outs.size(), opt.genericFanoutCap);
            for (int i = 0; i < lim; i++) {
                if (overBudget(counter, deadline, opt)) break;
                dfsRoot(g, outs.get(i), path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
            }
        } else {
            // Node biasa
            goOutgoing(g, currentId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
            if (opt.includeBoundary) goBoundary(g, currentId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
            if (opt.includeMessageFlow) goMessage(g, currentId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
        }

        leaveNode(currentId, path, visitedDepthKey, visitCount);
    }

    /* ===================== DFS (root) — annotated ===================== */

    private void dfsRootAnn(
            Graph g, String currentId,
            Deque<String> path, Set<String> visitedDepthKey, Map<String, Integer> visitCount,
            List<Group> groups, Consumer<AnnotatedPath> sink, Counter counter, long deadline, Options opt
    ) {
        if (currentId == null || overBudget(counter, deadline, opt)) return;
        if (!enterNode(currentId, path, visitedDepthKey, visitCount, opt)) return;

        FlowNode cur = g.nodeById.get(currentId);

        if (cur instanceof EndEvent && !g.insideSubprocess.contains(currentId)) {
            sink.accept(new AnnotatedPath(new ArrayList<>(path), new ArrayList<>(groups)));
            counter.paths++;
            leaveNode(currentId, path, visitedDepthKey, visitCount);
            return;
        }

        if (cur instanceof SubProcess && g.eventSubProcesses.contains(cur.getId())) {
            goOutgoingAnn(g, currentId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
            if (opt.includeMessageFlow) goMessageAnn(g, currentId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
            leaveNode(currentId, path, visitedDepthKey, visitCount);
            return;
        }

        if (cur instanceof SubProcess) {
            List<String> subStarts = g.subprocessStarts.get(currentId);
            if (subStarts != null && !subStarts.isEmpty()) {
                for (String s : subStarts) {
                    if (overBudget(counter, deadline, opt)) break;
                    dfsInsideSPAnn(g, s, currentId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
                }
            } else {
                goOutgoingAnn(g, currentId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
                if (opt.includeMessageFlow) goMessageAnn(g, currentId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
            }
        }
        // Inclusive
        else if (cur instanceof InclusiveGateway && opt.strictGatewaySemantics) {
            int outCount = g.outgoing.getOrDefault(currentId, Collections.emptyList()).size();
            int inCount = (cur.getIncoming() != null) ? cur.getIncoming().size() : 0;

            if (outCount > 1) {
                handleForkWithJoinAnn(g, currentId, GatewayType.INCLUSIVE, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
            } else if (inCount > 1 && outCount == 1) {
                goOutgoingAnn(g, currentId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
            } else {
                goOutgoingAnn(g, currentId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
            }
        }
        // Parallel
        else if (cur instanceof ParallelGateway && opt.strictGatewaySemantics) {
            int outCount = g.outgoing.getOrDefault(currentId, Collections.emptyList()).size();
            int inCount = (cur.getIncoming() != null) ? cur.getIncoming().size() : 0;

            if (outCount > 1) {
                handleForkWithJoinAnn(g, currentId, GatewayType.PARALLEL, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
            } else if (inCount > 1 && outCount <= 1) {
                goOutgoingAnn(g, currentId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
            } else {
                goOutgoingAnn(g, currentId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
            }
        }
        // Event-Based
        else if (isEventBasedGateway(cur)) {
            if (isEventBasedParallel(cur)) {
                List<String> outs = new ArrayList<>(g.outgoing.getOrDefault(currentId, Collections.emptyList()));
                if (outs.size() > opt.eventParallelFanoutCap) {
                    outs = outs.subList(0, opt.eventParallelFanoutCap);
                }
                handleForkWithJoinLimitedAnn(
                        g, currentId, GatewayType.PARALLEL, outs,
                        path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt
                );
            } else {
                List<String> outs = g.outgoing.getOrDefault(currentId, Collections.emptyList());
                int lim = Math.min(outs.size(), opt.genericFanoutCap);
                for (int i = 0; i < lim; i++) {
                    if (overBudget(counter, deadline, opt)) break;
                    dfsRootAnn(g, outs.get(i), path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
                }
            }
        }
        else if (isExclusiveFork(cur, g)) {
            List<String> outs = g.outgoing.getOrDefault(currentId, Collections.emptyList());
            int lim = Math.min(outs.size(), opt.genericFanoutCap);
            for (int i = 0; i < lim; i++) {
                if (overBudget(counter, deadline, opt)) break;
                dfsRootAnn(g, outs.get(i), path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
            }
        } else {
            goOutgoingAnn(g, currentId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
            if (opt.includeBoundary) goBoundaryAnn(g, currentId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
            if (opt.includeMessageFlow) goMessageAnn(g, currentId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
        }

        leaveNode(currentId, path, visitedDepthKey, visitCount);
    }

    /* ===================== DFS (inside SubProcess) — plain ===================== */

    private void dfsInsideSP(
            Graph g, String currentId, String parentSpId,
            Deque<String> path, Set<String> visitedDepthKey, Map<String, Integer> visitCount,
            Consumer<List<String>> sink, Counter counter, long deadline, Options opt
    ) {
        if (currentId == null || overBudget(counter, deadline, opt)) return;
        if (!enterNode(currentId, path, visitedDepthKey, visitCount, opt)) return;

        FlowNode cur = g.nodeById.get(currentId);

        if (cur instanceof EndEvent && g.isParentOf(parentSpId, currentId)) {
            for (String tgt : g.outgoing.getOrDefault(parentSpId, Collections.emptyList())) {
                if (overBudget(counter, deadline, opt)) break;
                dfsRoot(g, tgt, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
            }
            if (opt.includeMessageFlow) {
                for (String tgt : g.messageTargets.getOrDefault(parentSpId, Collections.emptyList())) {
                    if (overBudget(counter, deadline, opt)) break;
                    dfsRoot(g, tgt, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
                }
            }
            leaveNode(currentId, path, visitedDepthKey, visitCount);
            return;
        }

        if (cur instanceof SubProcess && g.eventSubProcesses.contains(cur.getId())) {
            goOutgoingInside(g, currentId, parentSpId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
            if (opt.includeMessageFlow) goMessageCrossToRoot(g, currentId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
            leaveNode(currentId, path, visitedDepthKey, visitCount);
            return;
        }

        if (cur instanceof SubProcess) {
            List<String> subStarts = g.subprocessStarts.get(currentId);
            if (subStarts != null && !subStarts.isEmpty()) {
                for (String s : subStarts) {
                    if (overBudget(counter, deadline, opt)) break;
                    dfsInsideSP(g, s, currentId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
                }
            } else {
                goOutgoingInside(g, currentId, parentSpId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
                if (opt.includeBoundary) goBoundaryInside(g, currentId, parentSpId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
                if (opt.includeMessageFlow) goMessageCrossToRoot(g, currentId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
            }
        } else {
            if (cur instanceof InclusiveGateway && opt.strictGatewaySemantics) {
                int outCount = g.outgoing.getOrDefault(currentId, Collections.emptyList()).size();
                if (outCount > 1) {
                    handleForkWithJoin(g, currentId, GatewayType.INCLUSIVE, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
                } else {
                    goOutgoingInside(g, currentId, parentSpId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
                }
            } else if (cur instanceof ParallelGateway && opt.strictGatewaySemantics) {
                int outCount = g.outgoing.getOrDefault(currentId, Collections.emptyList()).size();
                if (outCount > 1) {
                    handleForkWithJoin(g, currentId, GatewayType.PARALLEL, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
                } else {
                    goOutgoingInside(g, currentId, parentSpId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
                }
            } else if (isEventBasedGateway(cur)) {
                if (isEventBasedParallel(cur)) {
                    List<String> outs = new ArrayList<>(g.outgoing.getOrDefault(currentId, Collections.emptyList()));
                    if (outs.size() > opt.eventParallelFanoutCap) {
                        outs = outs.subList(0, opt.eventParallelFanoutCap);
                    }
                    handleForkWithJoinLimited(
                            g, currentId, GatewayType.PARALLEL, outs,
                            path, visitedDepthKey, visitCount, sink, counter, deadline, opt
                    );
                } else {
                    List<String> outs = g.outgoing.getOrDefault(currentId, Collections.emptyList());
                    int lim = Math.min(outs.size(), opt.genericFanoutCap);
                    for (int i = 0; i < lim; i++) {
                        if (overBudget(counter, deadline, opt)) break;
                        dfsInsideSP(g, outs.get(i), parentSpId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
                    }
                }
            } else if (isExclusiveFork(cur, g)) {
                List<String> outs = g.outgoing.getOrDefault(currentId, Collections.emptyList());
                int lim = Math.min(outs.size(), opt.genericFanoutCap);
                for (int i = 0; i < lim; i++) {
                    if (overBudget(counter, deadline, opt)) break;
                    dfsInsideSP(g, outs.get(i), parentSpId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
                }
            } else {
                goOutgoingInside(g, currentId, parentSpId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
                if (opt.includeBoundary) goBoundaryInside(g, currentId, parentSpId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
                if (opt.includeMessageFlow) goMessageCrossToRoot(g, currentId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
            }
        }

        leaveNode(currentId, path, visitedDepthKey, visitCount);
    }

    /* ===================== DFS (inside SubProcess) — annotated ===================== */

    private void dfsInsideSPAnn(
            Graph g, String currentId, String parentSpId,
            Deque<String> path, Set<String> visitedDepthKey, Map<String, Integer> visitCount,
            List<Group> groups, Consumer<AnnotatedPath> sink, Counter counter, long deadline, Options opt
    ) {
        if (currentId == null || overBudget(counter, deadline, opt)) return;
        if (!enterNode(currentId, path, visitedDepthKey, visitCount, opt)) return;

        FlowNode cur = g.nodeById.get(currentId);

        if (cur instanceof EndEvent && g.isParentOf(parentSpId, currentId)) {
            for (String tgt : g.outgoing.getOrDefault(parentSpId, Collections.emptyList())) {
                if (overBudget(counter, deadline, opt)) break;
                dfsRootAnn(g, tgt, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
            }
            if (opt.includeMessageFlow) {
                for (String tgt : g.messageTargets.getOrDefault(parentSpId, Collections.emptyList())) {
                    if (overBudget(counter, deadline, opt)) break;
                    dfsRootAnn(g, tgt, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
                }
            }
            leaveNode(currentId, path, visitedDepthKey, visitCount);
            return;
        }

        if (cur instanceof SubProcess && g.eventSubProcesses.contains(cur.getId())) {
            goOutgoingInsideAnn(g, currentId, parentSpId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
            if (opt.includeMessageFlow) goMessageCrossToRootAnn(g, currentId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
            leaveNode(currentId, path, visitedDepthKey, visitCount);
            return;
        }

        if (cur instanceof SubProcess) {
            List<String> subStarts = g.subprocessStarts.get(currentId);
            if (subStarts != null && !subStarts.isEmpty()) {
                for (String s : subStarts) {
                    if (overBudget(counter, deadline, opt)) break;
                    dfsInsideSPAnn(g, s, currentId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
                }
            } else {
                goOutgoingInsideAnn(g, currentId, parentSpId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
                if (opt.includeBoundary) goBoundaryInsideAnn(g, currentId, parentSpId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
                if (opt.includeMessageFlow) goMessageCrossToRootAnn(g, currentId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
            }
        } else {
            if (cur instanceof InclusiveGateway && opt.strictGatewaySemantics) {
                int outCount = g.outgoing.getOrDefault(currentId, Collections.emptyList()).size();
                if (outCount > 1) {
                    handleForkWithJoinAnn(g, currentId, GatewayType.INCLUSIVE, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
                } else {
                    goOutgoingInsideAnn(g, currentId, parentSpId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
                }
            } else if (cur instanceof ParallelGateway && opt.strictGatewaySemantics) {
                int outCount = g.outgoing.getOrDefault(currentId, Collections.emptyList()).size();
                if (outCount > 1) {
                    handleForkWithJoinAnn(g, currentId, GatewayType.PARALLEL, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
                } else {
                    goOutgoingInsideAnn(g, currentId, parentSpId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
                }
            } else if (isEventBasedGateway(cur)) {
                if (isEventBasedParallel(cur)) {
                    List<String> outs = new ArrayList<>(g.outgoing.getOrDefault(currentId, Collections.emptyList()));
                    if (outs.size() > opt.eventParallelFanoutCap) {
                        outs = outs.subList(0, opt.eventParallelFanoutCap);
                    }
                    handleForkWithJoinLimitedAnn(
                            g, currentId, GatewayType.PARALLEL, outs,
                            path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt
                    );
                } else {
                    List<String> outs = g.outgoing.getOrDefault(currentId, Collections.emptyList());
                    int lim = Math.min(outs.size(), opt.genericFanoutCap);
                    for (int i = 0; i < lim; i++) {
                        if (overBudget(counter, deadline, opt)) break;
                        dfsInsideSPAnn(g, outs.get(i), parentSpId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
                    }
                }
            } else if (isExclusiveFork(cur, g)) {
                List<String> outs = g.outgoing.getOrDefault(currentId, Collections.emptyList());
                int lim = Math.min(outs.size(), opt.genericFanoutCap);
                for (int i = 0; i < lim; i++) {
                    if (overBudget(counter, deadline, opt)) break;
                    dfsInsideSPAnn(g, outs.get(i), parentSpId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
                }
            } else {
                goOutgoingInsideAnn(g, currentId, parentSpId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
                if (opt.includeBoundary) goBoundaryInsideAnn(g, currentId, parentSpId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
                if (opt.includeMessageFlow) goMessageCrossToRootAnn(g, currentId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
            }
        }

        leaveNode(currentId, path, visitedDepthKey, visitCount);
    }

    /* ===================== Fork → Join (AND / OR) — plain ===================== */

    private enum GatewayType { PARALLEL, INCLUSIVE }

    private void handleForkWithJoin(
            Graph g, String forkId, GatewayType type,
            Deque<String> path, Set<String> visitedDepthKey, Map<String, Integer> visitCount,
            Consumer<List<String>> sink, Counter counter, long deadline, Options opt
    ) {
        List<String> branchStarts = g.outgoing.getOrDefault(forkId, Collections.emptyList());
        handleForkWithJoinLimited(g, forkId, type, branchStarts, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
    }

    /** (2) Versi LIMITED: menerima subset branchStarts (dipakai untuk EBG parallel + cap) */
    private void handleForkWithJoinLimited(
            Graph g, String forkId, GatewayType type, List<String> branchStarts,
            Deque<String> path, Set<String> visitedDepthKey, Map<String, Integer> visitCount,
            Consumer<List<String>> sink, Counter counter, long deadline, Options opt
    ) {
        if (branchStarts == null || branchStarts.isEmpty()) return;

        // kumpulan cabang yang dieksekusi
        List<List<String>> selections = computeSelections(type, branchStarts, opt);

        // Precompute jarak (BFS) dari tiap cabang
        Map<String, Map<String,Integer>> distByStart = new HashMap<>();
        for (String s : branchStarts) distByStart.put(s, bfsDistances(g, s));

        for (List<String> selected : selections) {
            if (overBudget(counter, deadline, opt)) break;

            // Join kandidat (4) longgarkan incoming-size untuk subset 1 cabang
            List<String> joinCandidates = findJoinCandidates(g, forkId, type, selected, distByStart);

            if (type == GatewayType.INCLUSIVE && opt.inclusiveConservativeJoin) {
                List<String> notSelected = new ArrayList<>(branchStarts);
                notSelected.removeAll(selected);
                joinCandidates.removeIf(j -> canAnyReach(g, notSelected, j));
            }

            if (!joinCandidates.isEmpty()) {
                String joinId = pickBestJoin(joinCandidates, selected, distByStart);

                // shortest path tiap cabang → join
                List<List<String>> segs = new ArrayList<>(selected.size());
                for (String s : selected) {
                    List<String> sp = shortestPathTo(g, s, joinId);
                    if (sp == null || sp.isEmpty()) { segs.clear(); break; }
                    segs.add(sp);
                }
                if (!segs.isEmpty()) {
                    // gabungkan segmen
                    segs.sort(Comparator.comparing(a -> a.get(0)));

                    Deque<String> mergedPath = new ArrayDeque<>(path);
                    Set<String> newVisited = new HashSet<>(visitedDepthKey);
                    Map<String,Integer> newVisits = new HashMap<>(visitCount);

                    for (List<String> seg : segs) {
                        for (String nid : seg) {
                            if (nid.equals(forkId)) continue;

                            // PATCH: izinkan duplikasi terkontrol
                            String depthKey = nid + "#" + mergedPath.size();
                            if (newVisited.contains(depthKey)) continue;

                            int cnt = newVisits.getOrDefault(nid, 0);
                            if (cnt >= opt.maxVisitsPerNode) continue;

                            mergedPath.addLast(nid);
                            newVisited.add(depthKey);
                            newVisits.put(nid, cnt + 1);
                        }
                    }

                    // (3) Pastikan join hadir; fallback jika tidak
                    int idxJoin = indexOfDeque(mergedPath, joinId);
                    if (idxJoin < 0) {
                        for (String s : selected) {
                            if (overBudget(counter, deadline, opt)) break;
                            dfsRoot(g, s, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
                        }
                        return;
                    }

                    // Lanjut dari JOIN
                    for (String out : g.outgoing.getOrDefault(joinId, Collections.emptyList())) {
                        if (overBudget(counter, deadline, opt)) break;
                        dfsRoot(g, out, mergedPath, newVisited, newVisits, sink, counter, deadline, opt);
                    }
                    if (opt.includeBoundary) {
                        List<String> bes = g.boundary.getOrDefault(joinId, Collections.emptyList());
                        int blim = Math.min(bes.size(), opt.boundaryFanoutCap);
                        for (int i = 0; i < blim; i++) {
                            if (overBudget(counter, deadline, opt)) break;
                            dfsRoot(g, bes.get(i), mergedPath, newVisited, newVisits, sink, counter, deadline, opt);
                        }
                    }
                    if (opt.includeMessageFlow) {
                        List<String> tgts = g.messageTargets.getOrDefault(joinId, Collections.emptyList());
                        int mlim = Math.min(tgts.size(), opt.messageFanoutCap);
                        for (int i = 0; i < mlim; i++) {
                            if (overBudget(counter, deadline, opt)) break;
                            dfsRoot(g, tgts.get(i), mergedPath, newVisited, newVisits, sink, counter, deadline, opt);
                        }
                    }
                    return;
                }
            }

            // fallback: eksplor tiap cabang apa adanya
            for (String s : selected) {
                if (overBudget(counter, deadline, opt)) break;
                dfsRoot(g, s, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
            }
        }
    }

    /* ===================== Fork → Join (AND / OR) — annotated ===================== */

    private void handleForkWithJoinAnn(
            Graph g, String forkId, GatewayType type,
            Deque<String> path, Set<String> visitedDepthKey, Map<String, Integer> visitCount,
            List<Group> groups, Consumer<AnnotatedPath> sink, Counter counter, long deadline, Options opt
    ) {
        List<String> branchStarts = g.outgoing.getOrDefault(forkId, Collections.emptyList());
        handleForkWithJoinLimitedAnn(g, forkId, type, branchStarts, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
    }

    /** (2) Versi LIMITED — annotated */
    private void handleForkWithJoinLimitedAnn(
            Graph g, String forkId, GatewayType type, List<String> branchStarts,
            Deque<String> path, Set<String> visitedDepthKey, Map<String, Integer> visitCount,
            List<Group> groups, Consumer<AnnotatedPath> sink, Counter counter, long deadline, Options opt
    ) {
        if (branchStarts == null || branchStarts.isEmpty()) return;

        List<List<String>> selections = computeSelections(type, branchStarts, opt);

        Map<String, Map<String,Integer>> distByStart = new HashMap<>();
        for (String s : branchStarts) distByStart.put(s, bfsDistances(g, s));

        for (List<String> selected : selections) {
            if (overBudget(counter, deadline, opt)) break;

            List<String> joinCandidates = findJoinCandidates(g, forkId, type, selected, distByStart);
            if (type == GatewayType.INCLUSIVE && opt.inclusiveConservativeJoin) {
                List<String> notSelected = new ArrayList<>(branchStarts);
                notSelected.removeAll(selected);
                joinCandidates.removeIf(j -> canAnyReach(g, notSelected, j));
            }

            if (!joinCandidates.isEmpty()) {
                String joinId = pickBestJoin(joinCandidates, selected, distByStart);

                List<List<String>> segs = new ArrayList<>(selected.size());
                for (String s : selected) {
                    List<String> sp = shortestPathTo(g, s, joinId);
                    if (sp == null || sp.isEmpty()) { segs.clear(); break; }
                    segs.add(sp);
                }
                if (!segs.isEmpty()) {
                    segs.sort(Comparator.comparing(a -> a.get(0)));

                    Deque<String> mergedPath = new ArrayDeque<>(path);
                    Set<String> newVisited = new HashSet<>(visitedDepthKey);
                    Map<String,Integer> newVisits = new HashMap<>(visitCount);

                    int idxFork = indexOfDeque(mergedPath, forkId);

                    for (List<String> seg : segs) {
                        for (String nid : seg) {
                            if (nid.equals(forkId)) continue;

                            // PATCH: izinkan duplikasi terkontrol
                            String depthKey = nid + "#" + mergedPath.size();
                            if (newVisited.contains(depthKey)) continue;

                            int cnt = newVisits.getOrDefault(nid, 0);
                            if (cnt >= opt.maxVisitsPerNode) continue;

                            mergedPath.addLast(nid);
                            newVisited.add(depthKey);
                            newVisits.put(nid, cnt + 1);
                        }
                    }

                    int idxJoin = indexOfDeque(mergedPath, joinId);
                    if (idxJoin < 0) {
                        // (3) Fallback jika join tidak hadir
                        for (String s : selected) {
                            if (overBudget(counter, deadline, opt)) break;
                            dfsRootAnn(g, s, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
                        }
                        return;
                    }

                    // Siapkan branches
                    List<List<String>> branches = new ArrayList<>();
                    for (List<String> seg : segs) {
                        List<String> b = new ArrayList<>();
                        for (String nid : seg) {
                            if (nid.equals(forkId)) continue;
                            b.add(nid);
                        }
                        branches.add(b);
                    }
                    List<Group> newGroups = new ArrayList<>(groups);
                    newGroups.add(new Group(
                            (type == GatewayType.PARALLEL ? "PARALLEL" : "INCLUSIVE"),
                            forkId, joinId,
                            idxFork, idxJoin,
                            branches
                    ));

                    // Lanjut dari JOIN
                    for (String out : g.outgoing.getOrDefault(joinId, Collections.emptyList())) {
                        if (overBudget(counter, deadline, opt)) break;
                        dfsRootAnn(g, out, mergedPath, newVisited, newVisits, newGroups, sink, counter, deadline, opt);
                    }
                    if (opt.includeBoundary) {
                        List<String> bes = g.boundary.getOrDefault(joinId, Collections.emptyList());
                        int blim = Math.min(bes.size(), opt.boundaryFanoutCap);
                        for (int i = 0; i < blim; i++) {
                            if (overBudget(counter, deadline, opt)) break;
                            dfsRootAnn(g, bes.get(i), mergedPath, newVisited, newVisits, newGroups, sink, counter, deadline, opt);
                        }
                    }
                    if (opt.includeMessageFlow) {
                        List<String> tgts = g.messageTargets.getOrDefault(joinId, Collections.emptyList());
                        int mlim = Math.min(tgts.size(), opt.messageFanoutCap);
                        for (int i = 0; i < mlim; i++) {
                            if (overBudget(counter, deadline, opt)) break;
                            dfsRootAnn(g, tgts.get(i), mergedPath, newVisited, newVisits, newGroups, sink, counter, deadline, opt);
                        }
                    }
                    return;
                }
            }

            // fallback
            for (String s : selected) {
                if (overBudget(counter, deadline, opt)) break;
                dfsRootAnn(g, s, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
            }
        }
    }

    private List<List<String>> computeSelections(GatewayType type, List<String> branchStarts, Options opt) {
        List<List<String>> selections = new ArrayList<>();
        if (type == GatewayType.PARALLEL) {
            selections.add(new ArrayList<>(branchStarts)); // semua cabang
        } else {
            int n = branchStarts.size();
            if (n <= 0) return selections;

            // Urutkan subset dari yang paling sedikit cabang → paling banyak
            List<Integer> masks = new ArrayList<>();
            for (int mask = 1; mask < (1 << n); mask++) masks.add(mask);
            masks.sort(Comparator.comparingInt(Integer::bitCount));

            int emitted = 0;
            for (int mask : masks) {
                if (emitted >= opt.inclusiveMaxSubsets) break;
                List<String> sel = new ArrayList<>();
                for (int i = 0; i < n; i++) if ((mask & (1 << i)) != 0) sel.add(branchStarts.get(i));
                selections.add(sel);
                emitted++;
            }
            if (!branchStarts.isEmpty() && !selections.contains(branchStarts) && selections.size() >= opt.inclusiveMaxSubsets) {
                selections.add(new ArrayList<>(branchStarts));
            }
        }
        return selections;
    }

    private int indexOfDeque(Deque<String> dq, String id) {
        int i = 0;
        for (String s : dq) { if (Objects.equals(s, id)) return i; i++; }
        return -1;
    }

    private List<String> findJoinCandidates(
            Graph g, String forkId, GatewayType type,
            List<String> starts, Map<String, Map<String,Integer>> distByStart
    ) {
        Set<String> candidates = new HashSet<>();
        for (FlowNode n : g.nodeById.values()) {
            if (!(n instanceof Gateway) || n.getId().equals(forkId)) continue;
            if (n.getIncoming() == null) continue;

            // (4) Longgarkan syarat incoming-size bila subset hanya 1 cabang (untuk Inclusive)
            int inSize = n.getIncoming().size();
            boolean requireMultiIn = (type == GatewayType.PARALLEL) || (starts.size() > 1);
            if (requireMultiIn && inSize <= 1) continue;

            boolean typeMatch = (type == GatewayType.PARALLEL && n instanceof ParallelGateway)
                    || (type == GatewayType.INCLUSIVE && n instanceof InclusiveGateway);
            if (!typeMatch) continue;
            candidates.add(n.getId());
        }

        List<String> result = new ArrayList<>();
        for (String j : candidates) {
            boolean ok = true;
            for (String s : starts) {
                Map<String,Integer> d = distByStart.get(s);
                if (d == null || !d.containsKey(j)) { ok = false; break; }
                if (d.get(j) <= 0) { ok = false; break; }
            }
            if (ok) result.add(j);
        }
        return result;
    }

    private String pickBestJoin(
            List<String> joins, List<String> starts,
            Map<String, Map<String,Integer>> distByStart
    ) {
        String best = null; int bestScore = Integer.MAX_VALUE;
        for (String j : joins) {
            int sum = 0; boolean ok = true;
            for (String s : starts) {
                Integer d = distByStart.getOrDefault(s, Collections.emptyMap()).get(j);
                if (d == null) { ok = false; break; }
                sum += d;
            }
            if (ok && sum < bestScore) { bestScore = sum; best = j; }
        }
        return best;
    }

    private boolean canAnyReach(Graph g, List<String> starts, String targetId) {
        for (String s : starts) {
            if (isReachable(g, s, targetId, new HashSet<>())) return true;
        }
        return false;
    }

    private Map<String,Integer> bfsDistances(Graph g, String startId) {
        Map<String,Integer> dist = new HashMap<>();
        ArrayDeque<String> q = new ArrayDeque<>();
        dist.put(startId, 0); q.add(startId);
        while (!q.isEmpty()) {
            String u = q.poll();
            int du = dist.get(u);
            for (String v : g.outgoing.getOrDefault(u, Collections.emptyList())) {
                if (!dist.containsKey(v)) { dist.put(v, du + 1); q.add(v); }
            }
        }
        return dist;
    }

    private List<String> shortestPathTo(Graph g, String startId, String targetId) {
        Map<String,String> prev = new HashMap<>();
        ArrayDeque<String> q = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        q.add(startId); seen.add(startId);
        while (!q.isEmpty()) {
            String u = q.poll();
            if (u.equals(targetId)) break;
            for (String v : g.outgoing.getOrDefault(u, Collections.emptyList())) {
                if (seen.add(v)) { prev.put(v, u); q.add(v); }
            }
        }
        if (!startId.equals(targetId) && !prev.containsKey(targetId)) return null;
        ArrayDeque<String> stack = new ArrayDeque<>();
        String cur = targetId; stack.addFirst(cur);
        while (!cur.equals(startId)) {
            cur = prev.get(cur);
            if (cur == null) return null;
            stack.addFirst(cur);
        }
        return new ArrayList<>(stack);
    }

    private boolean isReachable(Graph g, String from, String to, Set<String> visited) {
        if (from.equals(to)) return true;
        if (visited.contains(from)) return false;
        visited.add(from);
        for (String next : g.outgoing.getOrDefault(from, Collections.emptyList())) {
            if (isReachable(g, next, to, visited)) return true;
        }
        return false;
    }

    /* ===================== Gateway Predicates ===================== */

    private boolean isExclusiveFork(FlowNode node, Graph g) {
        boolean xor = (node instanceof ExclusiveGateway) &&
                g.outgoing.getOrDefault(node.getId(), Collections.emptyList()).size() > 1;
        boolean evbExclusive = isEventBasedExclusive(node) &&
                g.outgoing.getOrDefault(node.getId(), Collections.emptyList()).size() > 1;
        return xor || evbExclusive;
    }

    private boolean isEventBasedGateway(FlowNode n) {
        return n instanceof EventBasedGateway;
    }

    // Lebih kokoh: coba enum via refleksi, fallback ke attribute string
    private boolean isEventBasedParallel(FlowNode n) {
        if (!(n instanceof EventBasedGateway)) return false;
        try {
            Object enumVal = n.getClass().getMethod("getEventGatewayType").invoke(n);
            if (enumVal != null) return enumVal.toString().equalsIgnoreCase("Parallel");
        } catch (Exception ignore) {}
        String t = ((EventBasedGateway) n).getAttributeValue("eventGatewayType");
        if (t == null || t.isBlank()) return false; // default Exclusive
        return t.equalsIgnoreCase("Parallel");
    }

    private boolean isEventBasedExclusive(FlowNode n) {
        if (!(n instanceof EventBasedGateway)) return false;
        try {
            Object enumVal = n.getClass().getMethod("getEventGatewayType").invoke(n);
            if (enumVal != null) return enumVal.toString().equalsIgnoreCase("Exclusive");
            return true; // null → default Exclusive
        } catch (Exception ignore) {}
        String t = ((EventBasedGateway) n).getAttributeValue("eventGatewayType");
        return (t == null || t.isBlank()) || t.equalsIgnoreCase("Exclusive");
    }

    /* ===================== Flow helpers — plain ===================== */

    private void goOutgoing(Graph g, String nodeId, Deque<String> path, Set<String> visitedDepthKey,
                            Map<String, Integer> visitCount, Consumer<List<String>> sink,
                            Counter counter, long deadline, Options opt) {
        List<String> outs = g.outgoing.getOrDefault(nodeId, Collections.emptyList());
        int limit = Math.min(outs.size(), opt.genericFanoutCap);
        for (int i = 0; i < limit; i++) {
            if (overBudget(counter, deadline, opt)) break;
            dfsRoot(g, outs.get(i), path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
        }
    }

    private void goBoundary(Graph g, String nodeId, Deque<String> path, Set<String> visitedDepthKey,
                            Map<String, Integer> visitCount, Consumer<List<String>> sink,
                            Counter counter, long deadline, Options opt) {
        List<String> bes = g.boundary.getOrDefault(nodeId, Collections.emptyList());
        int blim = Math.min(bes.size(), opt.boundaryFanoutCap);
        for (int i = 0; i < blim; i++) {
            if (overBudget(counter, deadline, opt)) break;
            dfsRoot(g, bes.get(i), path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
        }
    }

    private void goMessage(Graph g, String nodeId, Deque<String> path, Set<String> visitedDepthKey,
                           Map<String, Integer> visitCount, Consumer<List<String>> sink,
                           Counter counter, long deadline, Options opt) {
        List<String> tgts = g.messageTargets.getOrDefault(nodeId, Collections.emptyList());
        int mlim = Math.min(tgts.size(), opt.messageFanoutCap);
        for (int i = 0; i < mlim; i++) {
            if (overBudget(counter, deadline, opt)) break;
            dfsRoot(g, tgts.get(i), path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
        }
    }

    private void goOutgoingInside(Graph g, String nodeId, String parentSpId, Deque<String> path,
                                  Set<String> visitedDepthKey, Map<String, Integer> visitCount,
                                  Consumer<List<String>> sink, Counter counter, long deadline, Options opt) {
        List<String> outs = g.outgoing.getOrDefault(nodeId, Collections.emptyList());
        int limit = Math.min(outs.size(), opt.genericFanoutCap);
        for (int i = 0; i < limit; i++) {
            if (overBudget(counter, deadline, opt)) break;
            dfsInsideSP(g, outs.get(i), parentSpId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
        }
    }

    private void goBoundaryInside(Graph g, String nodeId, String parentSpId, Deque<String> path,
                                  Set<String> visitedDepthKey, Map<String, Integer> visitCount,
                                  Consumer<List<String>> sink, Counter counter, long deadline, Options opt) {
        List<String> bes = g.boundary.getOrDefault(nodeId, Collections.emptyList());
        int blim = Math.min(bes.size(), opt.boundaryFanoutCap);
        for (int i = 0; i < blim; i++) {
            if (overBudget(counter, deadline, opt)) break;
            dfsInsideSP(g, bes.get(i), parentSpId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
        }
    }

    private void goMessageCrossToRoot(Graph g, String nodeId, Deque<String> path, Set<String> visitedDepthKey,
                                      Map<String, Integer> visitCount, Consumer<List<String>> sink,
                                      Counter counter, long deadline, Options opt) {
        List<String> tgts = g.messageTargets.getOrDefault(nodeId, Collections.emptyList());
        int mlim = Math.min(tgts.size(), opt.messageFanoutCap);
        for (int i = 0; i < mlim; i++) {
            if (overBudget(counter, deadline, opt)) break;
            dfsRoot(g, tgts.get(i), path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
        }
    }

    /* ===================== Flow helpers — annotated ===================== */

    private void goOutgoingAnn(Graph g, String nodeId, Deque<String> path, Set<String> visitedDepthKey,
                               Map<String, Integer> visitCount, List<Group> groups,
                               Consumer<AnnotatedPath> sink, Counter counter, long deadline, Options opt) {
        List<String> outs = g.outgoing.getOrDefault(nodeId, Collections.emptyList());
        int limit = Math.min(outs.size(), opt.genericFanoutCap);
        for (int i = 0; i < limit; i++) {
            if (overBudget(counter, deadline, opt)) break;
            dfsRootAnn(g, outs.get(i), path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
        }
    }

    private void goBoundaryAnn(Graph g, String nodeId, Deque<String> path, Set<String> visitedDepthKey,
                               Map<String, Integer> visitCount, List<Group> groups,
                               Consumer<AnnotatedPath> sink, Counter counter, long deadline, Options opt) {
        List<String> bes = g.boundary.getOrDefault(nodeId, Collections.emptyList());
        int blim = Math.min(bes.size(), opt.boundaryFanoutCap);
        for (int i = 0; i < blim; i++) {
            if (overBudget(counter, deadline, opt)) break;
            dfsRootAnn(g, bes.get(i), path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
        }
    }

    private void goMessageAnn(Graph g, String nodeId, Deque<String> path, Set<String> visitedDepthKey,
                              Map<String, Integer> visitCount, List<Group> groups,
                              Consumer<AnnotatedPath> sink, Counter counter, long deadline, Options opt) {
        List<String> tgts = g.messageTargets.getOrDefault(nodeId, Collections.emptyList());
        int mlim = Math.min(tgts.size(), opt.messageFanoutCap);
        for (int i = 0; i < mlim; i++) {
            if (overBudget(counter, deadline, opt)) break;
            dfsRootAnn(g, tgts.get(i), path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
        }
    }

    private void goOutgoingInsideAnn(Graph g, String nodeId, String parentSpId, Deque<String> path,
                                     Set<String> visitedDepthKey, Map<String, Integer> visitCount,
                                     List<Group> groups, Consumer<AnnotatedPath> sink,
                                     Counter counter, long deadline, Options opt) {
        List<String> outs = g.outgoing.getOrDefault(nodeId, Collections.emptyList());
        int limit = Math.min(outs.size(), opt.genericFanoutCap);
        for (int i = 0; i < limit; i++) {
            if (overBudget(counter, deadline, opt)) break;
            dfsInsideSPAnn(g, outs.get(i), parentSpId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
        }
    }

    private void goBoundaryInsideAnn(Graph g, String nodeId, String parentSpId, Deque<String> path,
                                     Set<String> visitedDepthKey, Map<String, Integer> visitCount,
                                     List<Group> groups, Consumer<AnnotatedPath> sink,
                                     Counter counter, long deadline, Options opt) {
        List<String> bes = g.boundary.getOrDefault(nodeId, Collections.emptyList());
        int blim = Math.min(bes.size(), opt.boundaryFanoutCap);
        for (int i = 0; i < blim; i++) {
            if (overBudget(counter, deadline, opt)) break;
            dfsInsideSPAnn(g, bes.get(i), parentSpId, path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
        }
    }

    private void goMessageCrossToRootAnn(Graph g, String nodeId, Deque<String> path, Set<String> visitedDepthKey,
                                         Map<String, Integer> visitCount, List<Group> groups,
                                         Consumer<AnnotatedPath> sink, Counter counter, long deadline, Options opt) {
        List<String> tgts = g.messageTargets.getOrDefault(nodeId, Collections.emptyList());
        int mlim = Math.min(tgts.size(), opt.messageFanoutCap);
        for (int i = 0; i < mlim; i++) {
            if (overBudget(counter, deadline, opt)) break;
            dfsRootAnn(g, tgts.get(i), path, visitedDepthKey, visitCount, groups, sink, counter, deadline, opt);
        }
    }

    /* ===================== Enter/Leave & Budget ===================== */

    private boolean enterNode(String currentId, Deque<String> path, Set<String> visitedDepthKey,
                              Map<String, Integer> visitCount, Options opt) {
        if (path.size() >= opt.maxDepth) return false;

        if (opt.revisitWindow > 0) {
            int seen = 0;
            for (Iterator<String> it = path.descendingIterator(); it.hasNext() && seen < opt.revisitWindow; seen++) {
                if (Objects.equals(it.next(), currentId)) return false;
            }
        }

        int cnt = visitCount.getOrDefault(currentId, 0);
        if (cnt >= opt.maxVisitsPerNode) return false;

        String depthKey = currentId + "#" + path.size();
        if (visitedDepthKey.contains(depthKey)) return false;

        path.addLast(currentId);
        visitedDepthKey.add(depthKey);
        visitCount.put(currentId, cnt + 1);
        return true;
    }

    private void leaveNode(String currentId, Deque<String> path, Set<String> visitedDepthKey,
                           Map<String, Integer> visitCount) {
        String depthKey = currentId + "#" + (path.size() - 1);
        visitedDepthKey.remove(depthKey);
        path.removeLast();
        int cnt = visitCount.getOrDefault(currentId, 0);
        if (cnt <= 1) visitCount.remove(currentId); else visitCount.put(currentId, cnt - 1);
    }

    private static final class Counter { long paths = 0; }
    private boolean overBudget(Counter c, long deadline, Options opt) {
        if (c.paths >= opt.maxPaths) return true;
        return System.nanoTime() > deadline;
    }

    /* ===================== Graph build ===================== */

    private Graph buildGraph(BpmnModelInstance model, Options opt) {
        Graph g = new Graph();

        for (FlowNode n : model.getModelElementsByType(FlowNode.class)) {
            g.nodeById.put(n.getId(), n);
            if (isInsideSubProcess(n)) g.insideSubprocess.add(n.getId());
        }

        for (StartEvent s : model.getModelElementsByType(StartEvent.class)) {
            if (!g.insideSubprocess.contains(s.getId())) g.rootStarts.add(s);
        }
        if (g.rootStarts.isEmpty()) throw new IllegalStateException("Tidak ada StartEvent di level root.");

        for (FlowNode n : g.nodeById.values()) {
            List<String> outs = new ArrayList<>();
            for (SequenceFlow sf : n.getOutgoing()) {
                FlowNode tgt = sf.getTarget();
                if (tgt != null) outs.add(tgt.getId());
            }
            if (!outs.isEmpty()) g.outgoing.put(n.getId(), outs);

            if (n instanceof Gateway) g.allGateways.add((Gateway) n);
        }

        if (opt.includeBoundary) {
            for (BoundaryEvent be : model.getModelElementsByType(BoundaryEvent.class)) {
                if (be.getAttachedTo() == null) continue;
                String hostId = be.getAttachedTo().getId();
                g.boundary.computeIfAbsent(hostId, k -> new ArrayList<>()).add(be.getId());
            }
        }

        for (SubProcess sp : model.getModelElementsByType(SubProcess.class)) {
            if (isEventSubProcess(sp)) {
                g.eventSubProcesses.add(sp.getId());
                continue;
            }
            List<String> starts = new ArrayList<>();
            for (FlowElement e : sp.getFlowElements()) if (e instanceof StartEvent) starts.add(e.getId());
            if (!starts.isEmpty()) g.subprocessStarts.put(sp.getId(), starts);

            g.spChildren.computeIfAbsent(sp.getId(), k -> new HashSet<>());
            for (FlowElement e : sp.getFlowElements())
                if (e instanceof FlowNode) g.spChildren.get(sp.getId()).add(e.getId());
        }

        if (opt.includeMessageFlow) {
            for (MessageFlow mf : model.getModelElementsByType(MessageFlow.class)) {
                InteractionNode src = mf.getSource();
                InteractionNode tgt = mf.getTarget();
                if (src instanceof FlowNode && tgt instanceof FlowNode) {
                    String sId = ((FlowNode) src).getId();
                    String tId = ((FlowNode) tgt).getId();
                    g.messageTargets.computeIfAbsent(sId, k -> new ArrayList<>()).add(tId);
                }
            }
        }

        return g;
    }

    private boolean isInsideSubProcess(FlowNode node) {
        ModelElementInstance p = node.getParentElement();
        while (p != null) {
            if (p instanceof SubProcess) return true;
            p = p.getParentElement();
        }
        return false;
    }

    /* ===================== Lane / Actor ===================== */

    private Map<String, String> mapNodeToLane(BpmnModelInstance model) {
        Map<String, String> nodeToLane = new HashMap<>(512);
        Map<String, String> processToPool = new HashMap<>(32);
        Map<String, String> nodeToProcess = new HashMap<>(1024);

        for (Participant p : model.getModelElementsByType(Participant.class)) {
            Process proc = p.getProcess();
            if (proc != null) {
                String pool = safeTrim(p.getName());
                if (pool == null) pool = safeTrim(p.getId());
                if (pool != null) processToPool.put(proc.getId(), pool);
            }
        }

        for (Process proc : model.getModelElementsByType(Process.class)) {
            for (FlowElement e : proc.getFlowElements())
                if (e instanceof FlowNode) nodeToProcess.put(e.getId(), proc.getId());
        }

        for (Lane lane : model.getModelElementsByType(Lane.class)) {
            String ln = safeTrim(lane.getName());
            if (ln == null) continue;
            for (FlowNode n : lane.getFlowNodeRefs()) nodeToLane.put(n.getId(), ln);
        }
        for (Process proc : model.getModelElementsByType(Process.class)) {
            Collection<LaneSet> lsets = proc.getLaneSets();
            if (lsets == null) continue;
            for (LaneSet ls : lsets) for (Lane lane : ls.getLanes()) {
                String ln = safeTrim(lane.getName());
                if (ln == null) continue;
                for (FlowNode n : lane.getFlowNodeRefs()) nodeToLane.put(n.getId(), ln);
            }
        }

        for (SubProcess sp : model.getModelElementsByType(SubProcess.class)) {
            String ln = nodeToLane.get(sp.getId());
            if (isBlank(ln)) continue;
            assignLaneRecursive(sp, ln, nodeToLane);
        }

        Set<String> processesWithAnyLane = new HashSet<>();
        for (Map.Entry<String,String> e : nodeToProcess.entrySet())
            if (nodeToLane.containsKey(e.getKey())) processesWithAnyLane.add(e.getValue());

        for (Map.Entry<String,String> e : nodeToProcess.entrySet()) {
            String nodeId = e.getKey();
            String procId = e.getValue();
            if (!nodeToLane.containsKey(nodeId)) {
                String pool = processToPool.get(procId);
                if (pool != null && !processesWithAnyLane.contains(procId)) nodeToLane.put(nodeId, pool);
            }
        }

        for (Process proc : model.getModelElementsByType(Process.class)) {
            String procName = safeTrim(proc.getName());
            if (procName == null) continue;
            boolean hasAnyLane = false;
            for (FlowElement e : proc.getFlowElements()) {
                if (e instanceof FlowNode && nodeToLane.containsKey(e.getId())) { hasAnyLane = true; break; }
            }
            if (!hasAnyLane) {
                for (FlowElement e : proc.getFlowElements())
                    if (e instanceof FlowNode) nodeToLane.putIfAbsent(e.getId(), procName);
            }
        }

        Map<String, FlowNode> nodeById = new HashMap<>();
        for (FlowNode n : model.getModelElementsByType(FlowNode.class)) nodeById.put(n.getId(), n);
        for (FlowNode n : nodeById.values()) {
            if (nodeToLane.containsKey(n.getId())) continue;
            String ln = inferFromNeighbors(n, nodeToLane);
            if (!isBlank(ln)) nodeToLane.put(n.getId(), ln);
        }

        return nodeToLane;
    }

    private String determineActor(FlowNode node, String laneName) {
        if (node instanceof ServiceTask || node instanceof ScriptTask ||
                node instanceof BusinessRuleTask || node instanceof SendTask ||
                node instanceof ReceiveTask) return "System";
        if (node instanceof UserTask || node instanceof ManualTask)
            return (!isBlank(laneName)) ? laneName : "User";
        if (node instanceof Gateway || node instanceof BoundaryEvent ||
                node instanceof StartEvent || node instanceof EndEvent ||
                node instanceof IntermediateCatchEvent || node instanceof IntermediateThrowEvent)
            return "System";
        if (node instanceof SubProcess)
            return (!isBlank(laneName)) ? laneName : "System";
        return "System";
    }

    /* ===================== Graph Holder ===================== */

    private static final class Graph {
        final Map<String, FlowNode> nodeById = new HashMap<>(1024);
        final Set<String> insideSubprocess = new HashSet<>(512);
        final List<StartEvent> rootStarts = new ArrayList<>(16);

        final Map<String, List<String>> outgoing = new HashMap<>(1024);
        final Map<String, List<String>> boundary = new HashMap<>(256);
        final Map<String, List<String>> messageTargets = new HashMap<>(512);

        final Map<String, List<String>> subprocessStarts = new HashMap<>(128);
        final Map<String, Set<String>> spChildren = new HashMap<>(128);

        final List<Gateway> allGateways = new ArrayList<>(256);

        // Event Sub-Process (triggeredByEvent=true)
        final Set<String> eventSubProcesses = new HashSet<>(64);

        boolean isParentOf(String spId, String childNodeId) {
            Set<String> children = spChildren.get(spId);
            return children != null && children.contains(childNodeId);
        }
    }

    /* ===================== Debug ===================== */

    public void printTaskTypeAnalysis(BpmnModelInstance model) {
        Map<String, String> nodeToLane = mapNodeToLane(model);
        System.out.println("=== TASK TYPE ANALYSIS ===");
        for (FlowNode node : model.getModelElementsByType(FlowNode.class)) {
            String type = node.getElementType().getTypeName();
            String name = node.getName() != null ? node.getName() : "N/A";
            String lane = nodeToLane.get(node.getId());
            String actor = determineActor(node, lane);
            System.out.printf("ID=%s | Type=%s | Name=%s | Lane=%s | Actor=%s%n",
                    node.getId(), type, name, lane != null ? lane : "-", actor);
        }
    }

    public List<List<String>> pathsWithLane(BpmnModelInstance modelInstance) {
        return findAllPathsWithActor(modelInstance);
    }

    /* ===================== Small helpers ===================== */

    private static void assignLaneRecursive(SubProcess sp, String laneName, Map<String,String> nodeToLane) {
        for (FlowElement e : sp.getFlowElements()) {
            if (e instanceof FlowNode) {
                nodeToLane.putIfAbsent(e.getId(), laneName);
                if (e instanceof SubProcess) assignLaneRecursive((SubProcess) e, laneName, nodeToLane);
                if (e instanceof Transaction) assignLaneTransaction((Transaction) e, laneName, nodeToLane);
            }
        }
    }
    private static void assignLaneTransaction(Transaction tx, String laneName, Map<String,String> nodeToLane) {
        for (FlowElement e : tx.getFlowElements()) {
            if (e instanceof FlowNode) {
                nodeToLane.putIfAbsent(e.getId(), laneName);
                if (e instanceof SubProcess) assignLaneRecursive((SubProcess) e, laneName, nodeToLane);
            }
        }
    }
    private static String inferFromNeighbors(FlowNode node, Map<String,String> nodeToLane) {
        for (SequenceFlow sf : node.getIncoming()) {
            String ln = nodeToLane.get(sf.getSource().getId());
            if (!isBlank(ln)) return ln;
        }
        for (SequenceFlow sf : node.getOutgoing()) {
            String ln = nodeToLane.get(sf.getTarget().getId());
            if (!isBlank(ln)) return ln;
        }
        return null;
    }
    private static boolean isBlank(String s){ return s == null || s.trim().isEmpty(); }
    private static String safeTrim(String s){ return isBlank(s) ? null : s.trim(); }

    /** Deteksi Event Sub-Process (triggeredByEvent=true). */
    private boolean isEventSubProcess(SubProcess sp) {
        String trig = sp.getAttributeValue("triggeredByEvent");
        return "true".equalsIgnoreCase(trig);
    }
}
