package com.ae2vm.addon.vm;

import appeng.api.config.FuzzyMode;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import com.ae2vm.addon.compiler.PatternCompiler;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * #208: ordered execution of compiled VM blocks. A captured one-craft effect is
 * not a recipe: stock alternatives, returns and failed branch trials are stateful.
 * This executor keeps those operations in bytecode order, inside the VM fork.
 */
final class VmInstructionExecution {
    private static final BigInteger ZERO = BigInteger.ZERO, ONE = BigInteger.ONE;
    private static final Object EMPTY_STOCK_KEYS = new Object();
    private final Function<AEKey, List<IPatternDetails>> resolver;
    private final Predicate<AEKey> emitters;
    private final Map<AEKey, BigInteger> initial;
    private final boolean completeStock;
    private final KeyCounter initialKeys = new KeyCounter();
    private final Node root;
    private final Map<AEKey, List<IPatternDetails>> knownProducers = new HashMap<>();
    private final VmDemandProgram.Compiler demandCompiler;
    private final Map<IPatternDetails, ProcessMetadata> processMetadata = new IdentityHashMap<>();
    private final Map<VmDemandProgram, CapacityShape> capacityShapes = new IdentityHashMap<>();
    private boolean multiple;
    private boolean simulate;
    private long instructions;
    private long work;
    private long stateCopies;
    private long branchTrials, branchEntries;
    private long aggregatedBlocks;
    private final boolean sparseBranches, optimizedGuards;
    private long stockKeyScans, minimumSnapshots, replays;
    private BigInteger skipped = ZERO;
    private final java.util.function.LongConsumer progress;
    private final Runnable control;
    private final List<Trace> traces = new ArrayList<>();

    VmInstructionExecution(CraftingBytecode request, VmSimulationState inventory,
            Function<AEKey, List<IPatternDetails>> resolver, Predicate<AEKey> emitters,
            java.util.function.LongConsumer progress) {
        this(request, inventory, resolver, emitters, progress, true, true);
    }
    VmInstructionExecution(CraftingBytecode request, VmSimulationState inventory,
            Function<AEKey, List<IPatternDetails>> resolver, Predicate<AEKey> emitters,
            java.util.function.LongConsumer progress, boolean sparseBranches, boolean optimizedGuards) {
        this(request, inventory, resolver, emitters, progress, sparseBranches, optimizedGuards, () -> { });
    }
    VmInstructionExecution(CraftingBytecode request, VmSimulationState inventory,
            Function<AEKey, List<IPatternDetails>> resolver, Predicate<AEKey> emitters,
            java.util.function.LongConsumer progress, boolean sparseBranches, boolean optimizedGuards, Runnable control) {
        this.control = control;
        demandCompiler = new VmDemandProgram.Compiler(knownProducers, resolver, this::checkpoint);
        this.sparseBranches = sparseBranches;
        this.optimizedGuards = optimizedGuards;
        this.resolver = resolver;
        this.emitters = emitters;
        this.progress = progress;
        initial = new LinkedHashMap<>(inventory.initialStock());
        completeStock = inventory.complete();
        initial.put(request.getOutput(), ZERO);
        initial.keySet().forEach(key -> initialKeys.add(key, 1));
        root = new Node(request.getOutput(), ONE, null, null);
    }
    long instructions() { return instructions; }
    long work() { return work; }
    long stateCopies() { return stateCopies; }
    long branchTrials() { return branchTrials; }
    long branchEntries() { return branchEntries; }
    long aggregatedBlocks() { return aggregatedBlocks; }
    long stockKeyScans() { return stockKeyScans; }
    long minimumSnapshots() { return minimumSnapshots; }
    long replays() { return replays; }
    BigInteger skippedIterations() { return skipped; }

    VmCraftingPlan execute(BigInteger amount, boolean craftLess) {
        if (optimizedGuards) {
            var direct = craftLess ? directCraftLessPlan(amount) : directPlan(amount);
            if (direct != null) return direct;
        }
        var result = attempt(amount, false);
        if (result != null) return result;
        if (craftLess) {
            BigInteger success = ZERO;
            for (var step = ONE.shiftLeft(amount.bitLength() - 1); step.signum() > 0; step = step.shiftRight(1)) {
                var candidate = success.add(step);
                if (candidate.compareTo(amount) >= 0) continue;
                var trial = attempt(candidate, false);
                if (trial != null) { success = candidate; result = trial; }
            }
            if (result != null) return result;
        }
        return Objects.requireNonNull(attempt(amount, true));
    }

    private VmCraftingPlan directCraftLessPlan(BigInteger amount) {
        if (!completeStock || root.emit) return null;
        var choices = root.processes();
        if (choices.size() != 1) return null;
        var process = choices.get(0);
        var program = demandCompiler.getExpanded(process.pattern,
                key -> initial.getOrDefault(key, ZERO).signum() > 0);
        if (program == null) return null;
        if (!program.period.equals(ONE)) {
            // #218: fractional shared paths have trial-dependent conceptual node charges.
            var seen = Collections.newSetFromMap(new IdentityHashMap<VmDemandProgram, Boolean>());
            var pending = new ArrayDeque<VmDemandProgram>();
            pending.add(program);
            while (!pending.isEmpty()) {
                checkpoint();
                var current = pending.removeFirst();
                if (!seen.add(current)) return null;
                for (var child : current.children) if (child != null) pending.addLast(child);
            }
        }
        // #218: raw-demand bounds cannot replace available intermediate stock or its producers.
        for (var key : program.intermediates)
            if (initial.getOrDefault(key, ZERO).signum() > 0) return null;
        var output = process.output(root.key);
        var requestedTimes = PatternCompiler.ceilDiv(amount, output);
        var upperTimes = requestedTimes;
        for (var entry : program.needs.entrySet()) {
            checkpoint();
            var leaf = program.leaves.get(entry.getKey());
            if (leaf.emit()) continue;
            var coefficient = entry.getValue();
            if (coefficient.numerator().signum() <= 0) continue;
            var available = initial.getOrDefault(entry.getKey(), ZERO);
            var fundedTimes = available.multiply(coefficient.denominator()).divide(coefficient.numerator());
            if (leaf.stockOnly() && fundedTimes.compareTo(requestedTimes) < 0
                    && !knownProducers.computeIfAbsent(entry.getKey(), resolver).isEmpty()) return null;
            upperTimes = upperTimes.min(fundedTimes);
        }
        if (!program.period.equals(ONE)) {
            // Failed full-size trials also establish AE2's conceptual capacity tree.
            var requested = attempt(amount, false);
            if (requested != null) return requested;
        }
        // Exact whole periods form a funded lower bound; fractional requirements only an upper bound.
        var lowerTimes = upperTimes.divide(program.period).multiply(program.period);
        var upper = amount.min(upperTimes.multiply(output));
        var lower = amount.min(lowerTimes.multiply(output));
        if (upper.signum() == 0) {
            return requestedTimes.remainder(program.period).signum() == 0
                    ? materializeDirect(amount, process, program, requestedTimes, true) : attempt(amount, true);
        }
        if (lower.equals(upper)) return materializeDirect(upper, process, program, lowerTimes, false);
        var result = attempt(upper, false);
        if (result != null) return result;
        var high = upper.subtract(ONE);
        result = lower.signum() > 0 ? attempt(lower, false) : null;
        while (lower.compareTo(high) < 0) {
            checkpoint();
            var candidate = lower.add(high).add(ONE).shiftRight(1);
            var trial = attempt(candidate, false);
            if (trial == null) high = candidate.subtract(ONE);
            else { lower = candidate; result = trial; }
        }
        return result != null ? result : attempt(amount, true);
    }

    private VmCraftingPlan directPlan(BigInteger amount) {
        if (!completeStock || root.emit) return null;
        var choices = root.processes();
        if (choices.isEmpty()) return null;
        Process first = null;
        VmDemandProgram firstProgram = null;
        BigInteger firstTimes = null;
        for (var process : choices) {
            var program = demandCompiler.getExpanded(process.pattern,
                    key -> initial.getOrDefault(key, ZERO).signum() > 0);
            if (program == null) return null;
            if (choices.size() > 1 && !program.period.equals(ONE)) return null;
            var times = PatternCompiler.ceilDiv(amount, process.output(root.key));
            if (times.remainder(program.period).signum() != 0) return null;
            for (var key : program.intermediates)
                if (initial.getOrDefault(key, ZERO).signum() != 0) return null;
            boolean resolved = false;
            for (var entry : program.needs.entrySet()) {
                var key = entry.getKey();
                var leaf = program.leaves.get(key);
                if (leaf.emit() || !leaf.stockOnly()
                        || initial.getOrDefault(key, ZERO).compareTo(entry.getValue().scale(times)) >= 0) continue;
                if (!knownProducers.computeIfAbsent(key, resolver).isEmpty()) return null;
                resolved = true;
            }
            if (resolved) {
                program = demandCompiler.getExpanded(process.pattern,
                        key -> initial.getOrDefault(key, ZERO).signum() > 0);
                if (program == null || times.remainder(program.period).signum() != 0) return null;
            }
            for (var key : program.intermediates)
                if (initial.getOrDefault(key, ZERO).signum() != 0) return null;
            boolean shortage = false, impossible = false;
            for (var entry : program.needs.entrySet()) {
                var key = entry.getKey();
                var leaf = program.leaves.get(key);
                var available = initial.getOrDefault(key, ZERO);
                if (leaf.stockOnly() && available.compareTo(entry.getValue().scale(times)) < 0) return null;
                if (leaf.emit() || available.compareTo(entry.getValue().scale(times)) >= 0) continue;
                shortage = true;
                if (program.period.equals(ONE) && available.multiply(entry.getValue().denominator())
                        .compareTo(entry.getValue().numerator()) < 0) impossible = true;
            }
            if (first == null) { first = process; firstProgram = program; firstTimes = times; }
            if (!shortage) return materializeDirect(amount, process, program, times, false);
            if (choices.size() == 1) return materializeDirect(amount, process, program, times, true);
            if (!impossible) return null;
        }
        return materializeDirect(amount, first, firstProgram, firstTimes, true);
    }

    private VmCraftingPlan materializeDirect(BigInteger amount, Process process,
            VmDemandProgram program, BigInteger times, boolean simulation) {
        simulate = simulation;
        var state = new State();
        work++;
        state.charge(root.key, amount);
        applyAggregate(process, program, times, state);
        if (state.extract(root.key, amount).compareTo(amount) != 0)
            throw new IllegalStateException("VM demand program did not produce its requested output");
        state.bytes = state.bytes.add(new VmByteCost(root.nodeCount(null).multiply(BigInteger.valueOf(8)), ONE));
        return new VmCraftingPlan(new VmStack(root.key, amount), state.bytes.ceiling(), simulation,
                multiple, new VmKeyCounter(state.used), new VmKeyCounter(state.emitted),
                new VmKeyCounter(state.missing), state.patterns);
    }

    private VmCraftingPlan attempt(BigInteger amount, boolean simulation) {
        simulate = simulation;
        var state = new State();
        try { request(root, amount, state, null); }
        catch (Missing absent) { return null; }
        state.bytes = state.bytes.add(new VmByteCost(root.nodeCount(null).multiply(BigInteger.valueOf(8)), ONE));
        return new VmCraftingPlan(new VmStack(root.key, amount), state.bytes.ceiling(), simulation,
                multiple, new VmKeyCounter(state.used), new VmKeyCounter(state.emitted),
                new VmKeyCounter(state.missing), state.patterns);
    }

    private final class Node {
        final AEKey key;
        final BigInteger unit;
        final IPatternDetails.IInput input;
        final Node ancestor;
        final boolean emit;
        List<Process> processes;

        Node(AEKey encoded, BigInteger unit, IPatternDetails.IInput input, Node ancestor) {
            this.input = input;
            this.ancestor = ancestor;
            this.unit = unit;
            key = input instanceof PatternCompiler.DetachedInput detached ? detached.craftedKey() : encoded;
            emit = input instanceof PatternCompiler.DetachedInput detached ? detached.emittable() : emitters.test(encoded);
        }

        BigInteger nodeCount(CapacityShape inherited) {
            var count = ONE;
            boolean inheritedFound = false;
            if (processes != null) for (var process : processes) {
                var shape = inherited != null && inherited.pattern == process.pattern ? inherited : null;
                inheritedFound |= shape != null;
                count = count.add(process.nodeCount(shape));
            }
            if (inherited != null && !inheritedFound) count = count.add(inherited.nodes);
            return count;
        }

        List<Process> processes() {
            if (processes != null) return processes;
            processes = new ArrayList<>();
            var definitions = knownProducers.computeIfAbsent(key, resolver);
            for (var pattern : definitions) {
                if (ancestor != null && ancestor.conflicts(pattern)) continue;
                processes.add(new Process(pattern, this));
            }
            multiple |= processes.size() > 1;
            return processes;
        }

        boolean conflicts(IPatternDetails pattern) {
            for (Node cursor = this; cursor != null; cursor = cursor.ancestor) {
                for (var output : pattern.getOutputs()) if (cursor.key.equals(output.what())) return true;
                for (var slot : pattern.getInputs())
                    if (cursor.key.equals(slot.getPossibleInputs()[0].what())) return true;
            }
            return false;
        }
    }

    private final class Process {
        final IPatternDetails pattern;
        List<Node> inputs;
        final Node parent;
        ProcessMetadata metadata;
        CapacityShape aggregatedShape;
        Process(IPatternDetails pattern, Node parent) {
            this.pattern = pattern;
            this.parent = parent;
        }
        List<Node> inputs() {
            if (inputs == null) {
                inputs = new ArrayList<>();
                for (var slot : pattern.getInputs()) {
                    var primary = slot.getPossibleInputs()[0];
                    inputs.add(new Node(primary.what(), BigInteger.valueOf(primary.amount()), slot, parent));
                }
            }
            return inputs;
        }
        ProcessMetadata metadata() {
            if (metadata == null) metadata = processMetadata.computeIfAbsent(pattern,
                    VmInstructionExecution.this::inspectProcess);
            return metadata;
        }
        BigInteger nodeCount(CapacityShape inherited) {
            var shape = CapacityShape.union(inherited, aggregatedShape);
            if (inputs == null) {
                var count = BigInteger.valueOf(pattern instanceof PatternCompiler.DetachedPattern detached
                        ? detached.inputCount() : pattern.getInputs().length);
                if (shape != null) for (var child : shape.children) if (child != null) count = count.add(child.nodes);
                return count;
            }
            var count = ZERO;
            for (int i = 0; i < inputs.size(); i++)
                count = count.add(inputs.get(i).nodeCount(shape == null ? null : shape.children.get(i)));
            return count;
        }
        BigInteger output(AEKey key) {
            BigInteger amount = ZERO;
            for (var output : pattern.getOutputs()) if (output.what().equals(key))
                amount = amount.add(PatternCompiler.outputAmount(pattern, output));
            if (amount.signum() <= 0) throw new IllegalStateException("VM producer does not produce requested key");
            return amount;
        }
    }

    private CapacityShape capacityShape(VmDemandProgram program) {
        if (program == null) return null;
        var cached = capacityShapes.get(program);
        if (cached != null) return cached;
        var children = new ArrayList<CapacityShape>();
        for (var child : program.children) children.add(capacityShape(child));
        var result = new CapacityShape(program.pattern, children);
        capacityShapes.put(program, result);
        return result;
    }

    /** #218: visited capacity nodes survive later stock-boundary summaries. No quantity state is retained. */
    private static final class CapacityShape {
        final IPatternDetails pattern;
        final List<CapacityShape> children;
        final BigInteger nodes;
        CapacityShape(IPatternDetails pattern, List<CapacityShape> children) {
            this.pattern = pattern;
            this.children = Collections.unmodifiableList(children);
            var count = BigInteger.valueOf(children.size());
            for (var child : children) if (child != null) count = count.add(child.nodes);
            nodes = count;
        }
        private record Pair(CapacityShape first, CapacityShape second) { }
        static CapacityShape union(CapacityShape first, CapacityShape second) {
            if (first == null) return second;
            if (second == null || first == second) return first;
            return union(first, second, new HashMap<>());
        }
        private static CapacityShape union(CapacityShape first, CapacityShape second, Map<Pair, CapacityShape> memo) {
            if (first == null) return second;
            if (second == null || first == second) return first;
            var pair = new Pair(first, second);
            var cached = memo.get(pair);
            if (cached != null) return cached;
            if (first.pattern != second.pattern || first.children.size() != second.children.size())
                throw new IllegalStateException("incompatible VM capacity shapes");
            var children = new ArrayList<CapacityShape>();
            boolean sameFirst = true, sameSecond = true;
            for (int i = 0; i < first.children.size(); i++) {
                var child = union(first.children.get(i), second.children.get(i), memo);
                children.add(child);
                sameFirst &= child == first.children.get(i);
                sameSecond &= child == second.children.get(i);
            }
            var result = sameFirst ? first : sameSecond ? second : new CapacityShape(first.pattern, children);
            memo.put(pair, result);
            return result;
        }
    }

    private static final class ProcessMetadata {
        private final boolean single, containers;
        private CraftingBytecode code;

        ProcessMetadata(boolean single, boolean containers) {
            this.single = single;
            this.containers = containers;
        }

        CraftingBytecode code(IPatternDetails pattern) {
            // #218: retain compiled blocks without compiling unused alternatives.
            if (code == null) {
                PatternCompiler.compileIfAbsent(pattern);
                code = Objects.requireNonNull(PatternCompiler.getCompiled(pattern));
            }
            return code;
        }
    }

    private ProcessMetadata inspectProcess(IPatternDetails pattern) {
        boolean limited = false, returned = false;
        for (var slot : pattern.getInputs()) {
            var templates = slot.getPossibleInputs();
            returned |= slot.getRemainingKey(templates[0].what()) != null;
            if (slot instanceof PatternCompiler.DetachedInput detached) {
                returned |= detached.mayReturnItems();
                for (var output : pattern.getOutputs()) limited |= detached.mayConsumeOutput(output.what());
            } else {
                for (int i = 1; i < templates.length; i++) {
                    try { returned |= slot.getRemainingKey(templates[i].what()) != null; }
                    catch (CancellationException cancelled) { throw cancelled; }
                    catch (RuntimeException unused) { returned = true; }
                }
            }
            for (var candidate : slot.getPossibleInputs())
                for (var output : pattern.getOutputs()) if (output.what().equals(candidate.what())) limited = true;
        }
        return new ProcessMetadata(limited || returned, returned);
    }

    private void request(Node node, BigInteger count, State state, Map<AEKey, BigInteger> returns) {
        checkpoint();
        work++;
        if ((work & 1023) == 0) progress.accept(work);
        state.charge(node.key, node.unit.multiply(count));
        var remaining = count;
        if (node.input == null) {
            remaining = remaining.subtract(state.extract(node.key, remaining));
        } else if (node.input instanceof PatternCompiler.DetachedInput detached && detached.exactKey() != null) {
            // #218: native processing inputs are exact, not a fuzzy NBT-family search.
            var key = detached.exactKey();
            var wanted = remaining.multiply(node.unit);
            var taken = state.read(key, wanted).min(wanted).divide(node.unit);
            if (taken.signum() > 0) {
                state.extract(key, taken.multiply(node.unit));
                remaining = remaining.subtract(taken);
            }
        } else {
            // Use AE2's key index for ordering only; quantities are never stored as long.
            var templates = node.input.getPossibleInputs();
            int nextTemplate = 0;
            Iterator<? extends Map.Entry<AEKey, ?>> candidates = Collections.emptyIterator();
            var unit = ONE;
            while (remaining.signum() > 0 && (candidates.hasNext() || nextTemplate < templates.length)) {
                // #218: batch across template boundaries, retaining each candidate's own unit and priority.
                var batch = new ArrayList<Candidate>(128);
                for (int scanned = 0; scanned < 128; scanned++) {
                    while (!candidates.hasNext() && nextTemplate < templates.length) {
                        checkpoint();
                        var template = templates[nextTemplate++];
                        unit = BigInteger.valueOf(template.amount());
                        candidates = state.fuzzy(template.what()).iterator();
                    }
                    if (!candidates.hasNext()) break;
                    checkpoint();
                    var key = candidates.next().getKey();
                    // #218: lookahead must not add guards for candidates the ordered cursor never reaches.
                    batch.add(new Candidate(key, unit));
                    if (batch.size() == 1 && node.input instanceof PatternCompiler.DetachedInput detached
                            && detached.isCandidatePrepared(key)) break;
                }
                if (batch.isEmpty()) continue;
                for (int index = 0; index < batch.size(); index++) {
                    if (remaining.signum() == 0) break;
                    var candidate = batch.get(index);
                    var key = candidate.key();
                    if (optimizedGuards && state.amount(key).compareTo(candidate.unit()) < 0) {
                        // Keep the guard when this skip is actually needed: later returns can replenish it.
                        state.read(key, candidate.unit(), true);
                        continue;
                    }
                    if (node.input instanceof PatternCompiler.DetachedInput detached && !detached.isCandidatePrepared(key))
                        detached.prepareCandidates(batch.subList(index, batch.size()).stream()
                                .filter(next -> !optimizedGuards || state.amount(next.key()).compareTo(next.unit()) >= 0)
                                .map(Candidate::key).toList());
                    if (!node.input.isValid(key, null)) continue;
                    var wanted = remaining.multiply(candidate.unit());
                    var available = state.read(key, wanted);
                    var taken = available.min(wanted).divide(candidate.unit());
                    if (taken.signum() == 0) continue;
                    state.extract(key, taken.multiply(candidate.unit()));
                    remaining = remaining.subtract(taken);
                    addReturn(node, key, taken, returns);
                }
            }
        }
        if (remaining.signum() == 0) return;
        addReturn(node, node.key, remaining, returns);
        var demand = remaining.multiply(node.unit);
        if (node.emit) { merge(state.emitted, node.key, demand); return; }
        var choices = node.processes();
        if (simulate && choices.size() > 1) {
            // #218: an unrelated shortage must not make an unfunded first route hide a funded alternative.
            simulate = false;
            try { demand = craft(node, demand, state, choices); }
            finally { simulate = true; }
            if (demand.signum() > 0) demand = craft(node, demand, state, choices.subList(0, 1));
        } else {
            demand = craft(node, demand, state, choices);
        }
        if (demand.signum() == 0) return;
        if (!simulate) throw new Missing();
        merge(state.missing, node.key, demand);
    }

    private BigInteger craft(Node node, BigInteger demand, State state, List<Process> choices) {
        boolean alternatives = node.processes().size() > 1;
        for (var process : choices) {
            var metadata = process.metadata();
            var output = process.output(node.key);
            while (optimizedGuards && alternatives && !metadata.single && demand.signum() > 0) {
                var program = demandCompiler.get(process.pattern);
                if (program == null) break;
                program = demandCompiler.stocked(program, key -> state.amount(key).signum() > 0);
                if (program == null) break;
                var times = PatternCompiler.ceilDiv(demand, output);
                var batch = fundedAggregatePrefix(process, program, times, state);
                if (batch.signum() == 0) break;
                boolean transaction = alternatives;
                var trial = !transaction ? state : sparseBranches ? new State(state) : state.copy();
                if (transaction) branchTrials++;
                applyAggregate(process, program, batch, trial);
                var taken = trial.extract(node.key, demand);
                if (taken.signum() == 0) throw new IllegalStateException("VM produced no requested output");
                if (transaction) {
                    branchEntries += sparseBranches ? trial.changedEntries() : trial.entries();
                    if (sparseBranches) trial.commitTo(state);
                    else state.adopt(trial);
                }
                demand = demand.subtract(taken);
                if (demand.signum() == 0) return ZERO;
                // A depleted stocked boundary can still be crafted by this same producer.
            }
            // #217: bulk-only producers need no local replay snapshot. Enclosing traces still observe all reads.
            if (!alternatives && !metadata.single) {
                while (demand.signum() > 0) {
                    checkpoint();
                    run(process, PatternCompiler.ceilDiv(demand, output), state);
                    var taken = state.extract(node.key, demand);
                    if (taken.signum() == 0) throw new IllegalStateException("VM produced no requested output");
                    demand = demand.subtract(taken);
                }
                return ZERO;
            }
            // A one-shot process cannot replay; enclosing traces still observe reads and failed trials.
            var window = !optimizedGuards || demand.compareTo(output) > 0 ? new Trace(state) : null;
            if (window != null) traces.add(window);
            var windowOutput = ZERO;
            int iterations = 0, windowSize = 64;
            try {
            while (demand.signum() > 0) {
                checkpoint();
                boolean transaction = alternatives;
                if (transaction) branchTrials++;
                var target = !transaction ? state : sparseBranches ? new State(state) : state.copy();
                var times = transaction || metadata.single ? ONE : PatternCompiler.ceilDiv(demand, output);
                BigInteger taken;
                try {
                    var program = optimizedGuards && transaction && !metadata.single ? demandCompiler.get(process.pattern) : null;
                    if (program != null) program = demandCompiler.stocked(program, key -> target.amount(key).signum() > 0);
                    var bulk = program == null ? ZERO : fundedAggregatePrefix(process, program,
                            PatternCompiler.ceilDiv(demand, output), target);
                    if (bulk.signum() > 0) applyAggregate(process, program, bulk, target);
                    else run(process, times, target);
                    taken = target.extract(node.key, demand);
                    if (taken.signum() == 0) throw new IllegalStateException("VM produced no requested output");
                } catch (Missing failure) {
                    if (!transaction) throw failure;
                    break;
                } finally {
                    if (transaction) branchEntries += sparseBranches ? target.changedEntries() : state.entries();
                }
                if (transaction) {
                    if (sparseBranches) target.commitTo(state);
                    else state.adopt(target);
                }
                demand = demand.subtract(taken);
                if (window == null) continue;
                windowOutput = windowOutput.add(taken);
                // A multi-instruction period also covers changing tool damage and returned variants.
                var repeats = ZERO;
                boolean sameKeys;
                if (optimizedGuards) sameKeys = window.before.sameStockKeys(state);
                else {
                    stockKeyScans++;
                    sameKeys = window.before.stock.keySet().equals(state.stock.keySet());
                    window.captureMinimum(state);
                }
                traces.remove(traces.size() - 1);
                try {
                    if (sameKeys && demand.compareTo(windowOutput) >= 0) {
                        repeats = window.safeRepeats(state, demand.divide(windowOutput));
                        if (repeats.signum() > 0) {
                            if (optimizedGuards) window.captureMinimum(state);
                            window.replay(state, repeats);
                        }
                    }
                } finally { traces.add(window); }
                demand = demand.subtract(windowOutput.multiply(repeats));
                if (demand.signum() > 0 && (repeats.signum() > 0 || !sameKeys
                        || ++iterations >= windowSize)) {
                    window.finish(state); traces.remove(traces.size() - 1);
                    window = new Trace(state); traces.add(window);
                    windowOutput = ZERO; iterations = 0;
                    // #218: grow only an exhausted search, not successful replay or a changed key domain.
                    windowSize = repeats.signum() > 0 || !sameKeys ? 64 : Math.min(1_048_576, windowSize * 2);
                }
            }
            } finally {
                if (window != null) { window.finish(state); traces.remove(traces.size() - 1); }
            }
            if (demand.signum() == 0) return ZERO;
        }
        return demand;
    }

    private void addReturn(Node node, AEKey consumed, BigInteger count, Map<AEKey, BigInteger> returns) {
        if (returns == null || node.input == null) return;
        var key = node.input.getRemainingKey(consumed);
        if (key != null) merge(returns, key, count);
    }

    /** Execute original stack arithmetic/output opcodes plus slot-aware input/return instructions. */
    private void run(Process process, BigInteger times, State state) {
        var metadata = process.metadata();
        if (optimizedGuards && !metadata.single) {
            var program = demandCompiler.get(process.pattern);
            if (program != null && aggregate(process, program, times, state)) return;
        }
        var code = metadata.code(process.pattern);
        var buffer = ByteBuffer.wrap(code.getCode());
        var stack = new ArrayDeque<BigInteger>();
        stack.push(times);
        var returns = metadata.containers ? new LinkedHashMap<AEKey, BigInteger>() : null;
        while (buffer.hasRemaining()) {
            checkpoint(); instructions++;
            switch (Opcode.fromCode(buffer.get())) {
                case PUSH_LONG -> stack.push(BigInteger.valueOf(buffer.getLong()));
                case PUSH_BIG_INTEGER -> stack.push(code.getQuantity(Short.toUnsignedInt(buffer.getShort())));
                case DUP -> stack.push(stack.element());
                case POP -> stack.pop();
                case MUL -> stack.push(stack.pop().multiply(stack.pop()));
                case ADD -> stack.push(stack.pop().add(stack.pop()));
                case SUB -> { var right = stack.pop(); stack.push(stack.pop().subtract(right)); }
                case DIV_ROUNDUP -> { var right = stack.pop(); stack.push(PatternCompiler.ceilDiv(stack.pop(), right)); }
                case REQUEST_INPUT -> {
                    var input = process.inputs().get(Short.toUnsignedInt(buffer.getShort()));
                    var raw = stack.pop().divideAndRemainder(input.unit);
                    if (raw[1].signum() != 0) throw new IllegalStateException("non-integral VM input template");
                    request(input, raw[0], state, returns);
                }
                case RETURN_CONTAINERS -> {
                    if (returns != null) returns.forEach((key, amount) -> { state.insert(key, amount); state.charge(key, amount); });
                }
                case INSERT_OUTPUT -> state.insert(code.getConstantPool()[Short.toUnsignedInt(buffer.getShort())], stack.pop());
                case RECORD_PATTERN -> {
                    var pattern = code.getPatternPool()[Short.toUnsignedInt(buffer.getShort())];
                    var count = stack.pop();
                    merge(state.patterns, pattern, count);
                    state.bytes = state.bytes.add(new VmByteCost(count, ONE));
                }
                case RETURN, HALT -> { return; }
                default -> throw new IllegalStateException("Unexpected opcode in ordered VM pattern");
            }
        }
        throw new IllegalStateException("Unterminated VM program");
    }

    /** #218: combine shared demands once. No inventory snapshot or one-craft trial is multiplied. */
    private boolean aggregate(Process process, VmDemandProgram program, BigInteger times, State state) {
        program = demandCompiler.stocked(program, key -> state.amount(key).signum() > 0);
        if (program == null) return false;
        if (!canAggregate(process, program, times, state)) return false;
        applyAggregate(process, program, times, state);
        return true;
    }

    private BigInteger fundedAggregatePrefix(Process process, VmDemandProgram program, BigInteger upper, State state) {
        // #218: fractional per-craft calls visit different AE2 nodes and cannot share capacity accounting.
        if (!program.period.equals(ONE)) return ZERO;
        if (canAggregate(process, program, upper, state)) return upper;
        var limit = upper;
        for (var entry : program.needs.entrySet()) {
            if (program.leaves.get(entry.getKey()).emit()) continue;
            var amount = entry.getValue();
            if (amount.numerator().signum() > 0) limit = limit.min(state.amount(entry.getKey())
                    .multiply(amount.denominator()).divide(amount.numerator()));
        }
        limit = limit.divide(program.period).multiply(program.period);
        return limit.signum() > 0 && canAggregate(process, program, limit, state) ? limit : ZERO;
    }

    private boolean canAggregate(Process process, VmDemandProgram program, BigInteger times, State state) {
        if (times.remainder(program.period).signum() != 0) return false;
        for (var ancestor = process.parent.ancestor; ancestor != null; ancestor = ancestor.ancestor)
            if (program.dependencies.contains(ancestor.key)) return false;
        for (var key : program.intermediates) if (state.amount(key).signum() != 0) return false;
        for (var entry : program.needs.entrySet()) {
            var leaf = program.leaves.get(entry.getKey());
            if ((leaf.stockOnly() || !simulate && !leaf.emit())
                    && state.amount(entry.getKey()).compareTo(entry.getValue().scale(times)) < 0) return false;
        }
        return true;
    }

    private void applyAggregate(Process process, VmDemandProgram program, BigInteger times, State state) {
        checkpoint();
        aggregatedBlocks++;
        // Preserve only actual dependencies for an enclosing state-dependent repetition.
        for (var key : program.intermediates) state.read(key, ONE);
        for (var entry : program.needs.entrySet()) {
            checkpoint();
            var key = entry.getKey();
            var leaf = program.leaves.get(key);
            var demand = entry.getValue().scale(times);
            var taken = state.read(key, demand).min(demand).divide(leaf.unit()).multiply(leaf.unit());
            state.extract(key, taken);
            var remaining = demand.subtract(taken);
            if (remaining.signum() > 0) merge(leaf.emit() ? state.emitted : state.missing, key, remaining);
        }
        program.outputs.forEach((key, amount) -> state.insert(key, amount.scale(times)));
        program.crafts.forEach((pattern, amount) -> merge(state.patterns, pattern, amount.scale(times)));
        state.bytes = state.bytes.add(program.bytes.multiply(times));
        process.aggregatedShape = CapacityShape.union(process.aggregatedShape, capacityShape(program));
    }

    private final class State {
        final Map<AEKey, BigInteger> stock, used, emitted, missing;
        final Map<IPatternDetails, BigInteger> patterns;
        final Map<Trace, Map<AEKey, BigInteger>> lows = new IdentityHashMap<>();
        final Map<Family, KeyCounter> keys;
        private Object stockKeyVersion = EMPTY_STOCK_KEYS;
        VmByteCost bytes = VmByteCost.ZERO;
        State() {
            stock = new VmJournalMap<>(); used = new VmJournalMap<>(); emitted = new VmJournalMap<>();
            missing = new VmJournalMap<>(); patterns = new VmJournalMap<>(); keys = new HashMap<>();
        }
        State(State parent) {
            stock = new VmJournalMap<>(new VmBranchMap<>(parent.stock));
            used = new VmJournalMap<>(new VmBranchMap<>(parent.used));
            emitted = new VmJournalMap<>(new VmBranchMap<>(parent.emitted));
            missing = new VmJournalMap<>(new VmBranchMap<>(parent.missing));
            patterns = new VmJournalMap<>(new VmBranchMap<>(parent.patterns)); keys = new VmBranchMap<>(parent.keys);
            parent.lows.forEach((trace, low) -> lows.put(trace, new VmBranchMap<>(low)));
            stockKeyVersion = parent.stockKeyVersion; bytes = parent.bytes;
        }
        State(State source, boolean checkpoint) {
            stock = ((VmJournalMap<AEKey, BigInteger>) source.stock).snapshot();
            emitted = ((VmJournalMap<AEKey, BigInteger>) source.emitted).snapshot();
            missing = ((VmJournalMap<AEKey, BigInteger>) source.missing).snapshot();
            patterns = ((VmJournalMap<IPatternDetails, BigInteger>) source.patterns).snapshot();
            used = Map.of(); keys = Map.of();
            stockKeyVersion = source.stockKeyVersion; bytes = source.bytes;
        }
        long changedEntries() {
            long count = 0;
            for (var map : List.of(stock, used, emitted, missing, patterns))
                count += ((VmJournalMap<?, ?>) map).changedEntries();
            count += ((VmBranchMap<?, ?>) keys).changedEntries();
            for (var map : lows.values()) count += ((VmBranchMap<?, ?>) map).changedEntries();
            return count;
        }
        void commitTo(State parent) {
            for (var map : List.of(stock, used, emitted, missing, patterns)) ((VmJournalMap<?, ?>) map).commit();
            ((VmBranchMap<?, ?>) keys).commit();
            // Child traces have finished; only inherited low-water maps survive here.
            for (var map : lows.values()) ((VmBranchMap<?, ?>) map).commit();
            parent.stockKeyVersion = stockKeyVersion; parent.bytes = bytes;
        }
        State copy() { stateCopies++; var copy = new State(); copy.adopt(this); return copy; }
        State replayCopy() {
            if (sparseBranches) return new State(this, true);
            // #218: replay reads these quantities only, never copied low-water maps, used counts or fuzzy indexes.
            stateCopies++;
            var copy = new State();
            replace(copy.stock, stock); replace(copy.emitted, emitted);
            replace(copy.missing, missing); replace(copy.patterns, patterns);
            copy.stockKeyVersion = stockKeyVersion; copy.bytes = bytes;
            return copy;
        }
        void closeCheckpoint() {
            for (var map : List.of(stock, emitted, missing, patterns))
                if (map instanceof VmJournalMap.Snapshot<?, ?> snapshot) snapshot.close();
        }
        long entries() {
            return (long) stock.size() + used.size() + emitted.size() + missing.size() + patterns.size()
                    + keys.size() + lows.values().stream().mapToLong(Map::size).sum();
        }
        void adopt(State other) {
            replace(stock, other.stock); replace(used, other.used); replace(emitted, other.emitted);
            replace(missing, other.missing); replace(patterns, other.patterns);
            stockKeyVersion = other.stockKeyVersion;
            lows.clear(); other.lows.forEach((trace, low) -> lows.put(trace, VmBranchMap.snapshot(low)));
            keys.clear(); VmBranchMap.copyInto(other.keys, keys); bytes = other.bytes;
        }
        BigInteger amount(AEKey key) {
            var value = stock.get(key);
            return value != null ? value : initial.getOrDefault(key, ZERO);
        }
        boolean sameStockKeys(State other) {
            // #217: identity proves membership equality, not quantity equality. Branches inherit, never increment, tokens.
            if (stockKeyVersion == other.stockKeyVersion) return true;
            if (stock.size() != other.stock.size()) return false;
            stockKeyScans++;
            if (!stock.keySet().equals(other.stock.keySet())) return false;
            stockKeyVersion = other.stockKeyVersion;
            return true;
        }
        private void putStock(AEKey key, BigInteger amount) {
            if (stock.put(key, amount) == null) stockKeyVersion = new Object();
        }
        Collection<? extends Map.Entry<AEKey, ?>> fuzzy(AEKey key) {
            return keys.getOrDefault(Family.of(key), initialKeys).findFuzzy(key, FuzzyMode.IGNORE_ALL);
        }
        void index(AEKey key) {
            var family = Family.of(key);
            var source = keys.getOrDefault(family, initialKeys);
            if (source.get(key) > 0) return;
            // Copy only this fuzzy family on insertion, not the network-wide key index.
            var copy = new KeyCounter();
            for (var entry : source.findFuzzy(key, FuzzyMode.IGNORE_ALL)) copy.add(entry.getKey(), 1);
            copy.add(key, 1); keys.put(family, copy);
        }
        BigInteger read(AEKey key, BigInteger requested) {
            return read(key, requested, false);
        }
        BigInteger read(AEKey key, BigInteger requested, boolean belowUnit) {
            var available = amount(key);
            if (stock.putIfAbsent(key, available) == null) stockKeyVersion = new Object();
            index(key);
            for (var trace : traces) trace.read(key, available, requested, belowUnit);
            return available;
        }
        BigInteger extract(AEKey key, BigInteger requested) {
            var taken = read(key, requested).min(requested);
            var left = amount(key).subtract(taken);
            putStock(key, left); index(key);
            lows.forEach((trace, low) -> low.merge(key, left.min(trace.before.amount(key)), BigInteger::min));
            var required = initial.getOrDefault(key, ZERO).subtract(left);
            if (required.signum() > 0) used.merge(key, required, BigInteger::max);
            return taken;
        }
        void insert(AEKey key, BigInteger quantity) { putStock(key, amount(key).add(quantity)); index(key); }
        void charge(AEKey key, BigInteger amount) {
            bytes = bytes.add(new VmByteCost(amount.multiply(BigInteger.valueOf(8)), BigInteger.valueOf(key.getType().getAmountPerByte())));
        }
    }

    /** Per-block comparison domain; partial reads deliberately require exact equality. */
    private final class Trace {
        final State before;
        final Map<AEKey, BigInteger> lower = new HashMap<>(), upper = new HashMap<>();
        Map<AEKey, BigInteger> minimum;
        Trace(State state) {
            before = state.replayCopy();
            state.lows.put(this, sparseBranches ? new LinkedHashMap<>() : VmBranchMap.snapshot(state.stock));
        }
        void finish(State state) { minimum = state.lows.remove(this); before.closeCheckpoint(); }
        void captureMinimum(State state) {
            minimumSnapshots++;
            minimum = VmBranchMap.snapshot(state.lows.get(this));
        }
        void read(AEKey key, BigInteger available, BigInteger requested, boolean belowUnit) {
            var start = before.amount(key);
            var offset = available.subtract(start);
            if (belowUnit) {
                // #218: zero consumption is invariant throughout [0, unit), not at one exact quantity.
                lower.merge(key, offset.negate(), BigInteger::max);
                upper.merge(key, requested.subtract(ONE).subtract(offset), BigInteger::min);
            } else if (available.compareTo(requested) >= 0) {
                lower.merge(key, requested.subtract(offset), BigInteger::max);
            } else {
                lower.merge(key, start, BigInteger::max);
                upper.merge(key, start, BigInteger::min);
            }
        }
        BigInteger safeRepeats(State after, BigInteger limit) {
            // The caller already verified the key domain; numerical guards still apply to every observed input.
            for (var key : lower.keySet()) {
                var start = before.amount(key);
                var delta = after.amount(key).subtract(start);
                var min = lower.get(key);
                var max = upper.get(key);
                if (delta.signum() < 0) limit = limit.min(start.subtract(min).divide(delta.negate()));
                if (delta.signum() > 0 && max != null) limit = limit.min(max.subtract(start).divide(delta));
            }
            return limit.max(ZERO);
        }
        void replay(State state, BigInteger repeats) {
            replays++;
            skipped = skipped.add(repeats);
            // Extend enclosing guards across every skipped iteration, including failed trials.
            for (var parent : traces) for (var key : lower.keySet()) {
                var start = before.amount(key);
                var delta = state.amount(key).subtract(start);
                var offset = start.subtract(parent.before.amount(key));
                var min = lower.get(key).subtract(offset).subtract(delta.min(ZERO).multiply(repeats));
                parent.lower.merge(key, min, BigInteger::max);
                var max = upper.get(key);
                if (max != null) parent.upper.merge(key,
                        max.subtract(offset).subtract(delta.max(ZERO).multiply(repeats)), BigInteger::min);
            }
            Collection<AEKey> changed;
            if (before.stock instanceof VmJournalMap.Snapshot<AEKey, BigInteger> snapshot) {
                // #218: a committed catalyst can have zero net change but a nonzero reservation peak.
                var keys = new LinkedHashSet<>(snapshot.changedKeys());
                keys.addAll(minimum.keySet());
                changed = keys;
            } else changed = state.stock.keySet();
            for (var key : changed) {
                var delta = state.amount(key).subtract(before.amount(key));
                state.putStock(key, state.stock.get(key).add(delta.multiply(repeats)));
                var low = minimum.getOrDefault(key, before.amount(key));
                if (delta.signum() < 0) low = low.add(delta.multiply(repeats));
                var peak = initial.getOrDefault(key, ZERO).subtract(low);
                if (peak.signum() > 0) state.used.merge(key, peak, BigInteger::max);
                var actualLow = low;
                state.lows.values().forEach(values -> values.merge(key, actualLow, BigInteger::min));
            }
            scaleDelta(state.patterns, before.patterns, repeats);
            scaleDelta(state.emitted, before.emitted, repeats);
            scaleDelta(state.missing, before.missing, repeats);
            state.bytes = state.bytes.add(state.bytes.subtract(before.bytes).multiply(repeats));
        }
    }

    private static <K> void merge(Map<K, BigInteger> map, K key, BigInteger value) {
        if (value.signum() != 0) map.merge(key, value, BigInteger::add);
    }
    private static <K> void replace(Map<K, BigInteger> target, Map<K, BigInteger> source) {
        // VM ledgers only grow: a successful child contains every key of its parent.
        VmBranchMap.copyInto(source, target);
    }
    private static <K> void scaleDelta(Map<K, BigInteger> target, Map<K, BigInteger> before,
            BigInteger repeats) {
        if (before instanceof VmJournalMap.Snapshot<K, BigInteger> snapshot) {
            for (var key : snapshot.changedKeys())
                merge(target, key, target.get(key).subtract(before.getOrDefault(key, ZERO)).multiply(repeats));
        } else target.forEach((key, value) -> merge(target, key, value.subtract(before.getOrDefault(key, ZERO)).multiply(repeats)));
    }
    private void checkpoint() {
        control.run();
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("VM calculation cancelled");
    }
    private static final class Missing extends RuntimeException {
        Missing() { super(null, null, false, false); }
    }
    private record Family(Object type, Object primary) {
        static Family of(AEKey key) { return new Family(key.getType(), key.getPrimaryKey()); }
    }
    private record Candidate(AEKey key, BigInteger unit) { }
}
