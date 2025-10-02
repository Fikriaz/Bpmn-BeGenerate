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

    /* ===================== Opsi ===================== */

    public static final class Options {
        public int maxVisitsPerNode = 1;         // 1 = tanpa loop
        public int maxPaths = 50_000;            // hard cap jumlah path
        public long timeLimitMillis = 30_000;    // timeout (ms)
        public boolean includeBoundary = true;   // boundary events ikut
        public boolean includeMessageFlow = true;// message flow ikut

        public Options() {}
        public Options maxVisitsPerNode(int v){ this.maxVisitsPerNode = Math.max(1, v); return this; }
        public Options maxPaths(int v){ this.maxPaths = Math.max(1, v); return this; }
        public Options timeLimitMillis(long v){ this.timeLimitMillis = Math.max(1, v); return this; }
        public Options includeBoundary(boolean v){ this.includeBoundary = v; return this; }
        public Options includeMessageFlow(boolean v){ this.includeMessageFlow = v; return this; }
    }

    /* ===================== API ===================== */

    public List<List<String>> findAllPaths(BpmnModelInstance model) {
        return findAllPaths(model, new Options());
    }

    public List<List<String>> findAllPaths(BpmnModelInstance model, Options opt) {
        List<List<String>> out = new ArrayList<>(128);
        streamAllPaths(model, opt, out::add);
        return out;
    }

    public void streamAllPaths(BpmnModelInstance model, Options opt, Consumer<List<String>> sink) {
        if (model == null) throw new IllegalArgumentException("modelInstance null");
        final long deadline = System.nanoTime() + opt.timeLimitMillis * 1_000_000L;

        Graph g = buildGraph(model, opt);

        Deque<String> path = new ArrayDeque<>(64);
        Set<String> visitedDepthKey = new HashSet<>(512);
        Map<String, Integer> visitCount = new HashMap<>(256);

        Counter counter = new Counter();
        for (StartEvent s : g.rootStarts) {
            if (overBudget(counter, deadline, opt)) break;
            dfsRoot(g, s.getId(), path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
        }
    }

    /** Path berlabel: pakai [Lane] jika tersedia, kalau tidak fallback ke [System/User]. */
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

                if (!isBlank(lane)) labeled.add("[" + lane + "] " + name);
                else labeled.add("[" + actor + "] " + name);
            }
            if (!labeled.isEmpty()) out.add(labeled);
        }
        return out;
    }

    /* ===================== DFS CORE ===================== */

    private void dfsRoot(
            Graph g,
            String currentId,
            Deque<String> path,
            Set<String> visitedDepthKey,
            Map<String, Integer> visitCount,
            Consumer<List<String>> sink,
            Counter counter,
            long deadline,
            Options opt
    ) {
        if (currentId == null || overBudget(counter, deadline, opt)) return;
        if (!enterNode(currentId, path, visitedDepthKey, visitCount, opt)) return;

        FlowNode cur = g.nodeById.get(currentId);

        if (cur instanceof EndEvent && !g.insideSubprocess.contains(currentId)) {
            sink.accept(new ArrayList<>(path));
            counter.paths++;
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
                goOutgoing(g, currentId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
                if (opt.includeMessageFlow) goMessage(g, currentId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
            }
        } else {
            goOutgoing(g, currentId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
            if (opt.includeBoundary) goBoundary(g, currentId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
            if (opt.includeMessageFlow) goMessage(g, currentId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
        }

        leaveNode(currentId, path, visitedDepthKey, visitCount);
    }

    private void dfsInsideSP(
            Graph g,
            String currentId,
            String parentSpId,
            Deque<String> path,
            Set<String> visitedDepthKey,
            Map<String, Integer> visitCount,
            Consumer<List<String>> sink,
            Counter counter,
            long deadline,
            Options opt
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
            goOutgoingInside(g, currentId, parentSpId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
            if (opt.includeBoundary) goBoundaryInside(g, currentId, parentSpId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
            if (opt.includeMessageFlow) goMessageCrossToRoot(g, currentId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
        }

        leaveNode(currentId, path, visitedDepthKey, visitCount);
    }

    /* ===================== Branch helpers ===================== */

    private void goOutgoing(Graph g, String nodeId, Deque<String> path, Set<String> visitedDepthKey,
                            Map<String, Integer> visitCount, Consumer<List<String>> sink,
                            Counter counter, long deadline, Options opt) {
        for (String tgt : g.outgoing.getOrDefault(nodeId, Collections.emptyList())) {
            if (overBudget(counter, deadline, opt)) break;
            dfsRoot(g, tgt, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
        }
    }

    private void goBoundary(Graph g, String nodeId, Deque<String> path, Set<String> visitedDepthKey,
                            Map<String, Integer> visitCount, Consumer<List<String>> sink,
                            Counter counter, long deadline, Options opt) {
        for (String beId : g.boundary.getOrDefault(nodeId, Collections.emptyList())) {
            if (overBudget(counter, deadline, opt)) break;
            dfsRoot(g, beId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
        }
    }

    private void goMessage(Graph g, String nodeId, Deque<String> path, Set<String> visitedDepthKey,
                           Map<String, Integer> visitCount, Consumer<List<String>> sink,
                           Counter counter, long deadline, Options opt) {
        for (String tgt : g.messageTargets.getOrDefault(nodeId, Collections.emptyList())) {
            if (overBudget(counter, deadline, opt)) break;
            dfsRoot(g, tgt, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
        }
    }

    private void goOutgoingInside(Graph g, String nodeId, String parentSpId, Deque<String> path,
                                  Set<String> visitedDepthKey, Map<String, Integer> visitCount,
                                  Consumer<List<String>> sink, Counter counter,
                                  long deadline, Options opt) {
        for (String tgt : g.outgoing.getOrDefault(nodeId, Collections.emptyList())) {
            if (overBudget(counter, deadline, opt)) break;
            dfsInsideSP(g, tgt, parentSpId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
        }
    }

    private void goBoundaryInside(Graph g, String nodeId, String parentSpId, Deque<String> path,
                                  Set<String> visitedDepthKey, Map<String, Integer> visitCount,
                                  Consumer<List<String>> sink, Counter counter,
                                  long deadline, Options opt) {
        for (String beId : g.boundary.getOrDefault(nodeId, Collections.emptyList())) {
            if (overBudget(counter, deadline, opt)) break;
            dfsInsideSP(g, beId, parentSpId, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
        }
    }

    private void goMessageCrossToRoot(Graph g, String nodeId, Deque<String> path, Set<String> visitedDepthKey,
                                      Map<String, Integer> visitCount, Consumer<List<String>> sink,
                                      Counter counter, long deadline, Options opt) {
        for (String tgt : g.messageTargets.getOrDefault(nodeId, Collections.emptyList())) {
            if (overBudget(counter, deadline, opt)) break;
            dfsRoot(g, tgt, path, visitedDepthKey, visitCount, sink, counter, deadline, opt);
        }
    }

    /* ===================== Enter/Leave & Budget ===================== */

    private boolean enterNode(String currentId, Deque<String> path, Set<String> visitedDepthKey,
                              Map<String, Integer> visitCount, Options opt) {
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
        }

        if (opt.includeBoundary) {
            for (BoundaryEvent be : model.getModelElementsByType(BoundaryEvent.class)) {
                if (be.getAttachedTo() == null) continue;
                String hostId = be.getAttachedTo().getId();
                g.boundary.computeIfAbsent(hostId, k -> new ArrayList<>()).add(be.getId());
            }
        }

        for (SubProcess sp : model.getModelElementsByType(SubProcess.class)) {
            List<String> starts = new ArrayList<>();
            for (FlowElement e : sp.getFlowElements()) if (e instanceof StartEvent) starts.add(e.getId());
            if (!starts.isEmpty()) g.subprocessStarts.put(sp.getId(), starts);

            g.spChildren.computeIfAbsent(sp.getId(), k -> new HashSet<>());
            for (FlowElement e : sp.getFlowElements()) if (e instanceof FlowNode) g.spChildren.get(sp.getId()).add(e.getId());
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

    /* ===================== Lane/Actor ===================== */

    /** Mapping lane/pool fleksibel + warisan; tahan untuk model yang “beragam gaya”. */
    private Map<String, String> mapNodeToLane(BpmnModelInstance model) {
        Map<String, String> nodeToLane = new HashMap<>(512);
        Map<String, String> processToPool = new HashMap<>(32);
        Map<String, String> nodeToProcess = new HashMap<>(1024);

        // A) Participant (Pool) -> Process
        for (Participant p : model.getModelElementsByType(Participant.class)) {
            Process proc = p.getProcess(); // gunakan getProcess() pada Camunda
            if (proc != null) {
                String pool = safeTrim(p.getName());
                if (pool != null) processToPool.put(proc.getId(), pool);
            }
        }

        // B) Node -> Process
        for (Process proc : model.getModelElementsByType(Process.class)) {
            for (FlowElement e : proc.getFlowElements()) {
                if (e instanceof FlowNode) nodeToProcess.put(e.getId(), proc.getId());
            }
        }

        // C) Lane eksplisit (global & via LaneSet)
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

        // D) Wariskan dari SubProcess ke anak-anaknya (rekursif)
        for (SubProcess sp : model.getModelElementsByType(SubProcess.class)) {
            String ln = nodeToLane.get(sp.getId());
            if (isBlank(ln)) continue;
            assignLaneRecursive(sp, ln, nodeToLane);
        }

        // E) Participant fallback untuk process TANPA lane sama sekali
        Set<String> processesWithAnyLane = new HashSet<>();
        for (Map.Entry<String,String> e : nodeToProcess.entrySet()) {
            if (nodeToLane.containsKey(e.getKey())) processesWithAnyLane.add(e.getValue());
        }
        for (Map.Entry<String,String> e : nodeToProcess.entrySet()) {
            String nodeId = e.getKey();
            String procId = e.getValue();
            if (!nodeToLane.containsKey(nodeId)) {
                String pool = processToPool.get(procId);
                if (pool != null && !processesWithAnyLane.contains(procId)) {
                    nodeToLane.put(nodeId, pool);
                }
            }
        }

        // F) Process name fallback (jika tidak ada participant & tidak ada lane)
        for (Process proc : model.getModelElementsByType(Process.class)) {
            String procName = safeTrim(proc.getName());
            if (procName == null) continue;
            boolean hasAnyLane = false;
            for (FlowElement e : proc.getFlowElements()) {
                if (e instanceof FlowNode && nodeToLane.containsKey(e.getId())) { hasAnyLane = true; break; }
            }
            if (!hasAnyLane) {
                for (FlowElement e : proc.getFlowElements()) {
                    if (e instanceof FlowNode) nodeToLane.putIfAbsent(e.getId(), procName);
                }
            }
        }

        // G) Neighbor inference (ambil dari incoming/outgoing yang sudah terpetakan)
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

    /* ===================== Graph holder ===================== */

    private static final class Graph {
        final Map<String, FlowNode> nodeById = new HashMap<>(512);
        final Set<String> insideSubprocess = new HashSet<>(256);
        final List<StartEvent> rootStarts = new ArrayList<>(8);

        final Map<String, List<String>> outgoing = new HashMap<>(512);
        final Map<String, List<String>> boundary = new HashMap<>(128);
        final Map<String, List<String>> messageTargets = new HashMap<>(256);

        final Map<String, List<String>> subprocessStarts = new HashMap<>(64);
        final Map<String, Set<String>> spChildren = new HashMap<>(64);

        boolean isParentOf(String spId, String childNodeId) {
            Set<String> children = spChildren.get(spId);
            return children != null && children.contains(childNodeId);
        }
    }

    /* ===================== Debug opsional ===================== */

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

    public List<List<String>> PathsWithLane(BpmnModelInstance modelInstance) {
        return findAllPathsWithActor(modelInstance);
    }

    /* ===================== Helpers ===================== */

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
}
