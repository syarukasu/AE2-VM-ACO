package com.ae2vm.addon.vm;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.compiler.PatternCompiler;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.function.Predicate;

/** #218: shared recipe demands, not a multiplied snapshot of one inventory trial. */
final class VmDemandProgram {
    private static final BigInteger ZERO = BigInteger.ZERO, ONE = BigInteger.ONE;
    final Map<IPatternDetails, Ratio> crafts = new LinkedHashMap<>();
    final Map<AEKey, Ratio> needs = new LinkedHashMap<>(), outputs = new LinkedHashMap<>();
    final Map<AEKey, Leaf> leaves = new LinkedHashMap<>();
    final Set<AEKey> intermediates = new LinkedHashSet<>(), dependencies = new HashSet<>();
    final Set<AEKey> fuzzy = new HashSet<>();
    final Set<AEKey> alternatives = new HashSet<>();
    final IPatternDetails pattern;
    int generation;
    final List<VmDemandProgram> children = new ArrayList<>();
    private final Set<AEKey> inputs = new HashSet<>(), secondary = new HashSet<>();
    BigInteger nodes = ZERO, period = ONE;
    VmByteCost bytes = VmByteCost.ZERO;

    VmDemandProgram(IPatternDetails pattern, int generation) { this.pattern = pattern; this.generation = generation; }
    record Leaf(BigInteger unit, boolean emit, boolean stockOnly) { }
    record Ratio(BigInteger numerator, BigInteger denominator) {
        Ratio {
            if (denominator.signum() <= 0) throw new IllegalArgumentException("nonpositive demand denominator");
            if (numerator.signum() == 0) denominator = ONE;
            else if (!denominator.equals(ONE)) {
                var gcd = numerator.gcd(denominator);
                numerator = numerator.divide(gcd); denominator = denominator.divide(gcd);
            }
        }
        static Ratio of(BigInteger value) { return new Ratio(value, ONE); }
        Ratio add(Ratio other) {
            if (numerator.signum() == 0) return other;
            if (other.numerator.signum() == 0) return this;
            if (denominator.equals(other.denominator)) return new Ratio(numerator.add(other.numerator), denominator);
            return new Ratio(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                    denominator.multiply(other.denominator));
        }
        Ratio multiply(Ratio other) {
            if (ONE.equals(numerator) && ONE.equals(denominator)) return other;
            if (ONE.equals(other.numerator) && ONE.equals(other.denominator)) return this;
            return new Ratio(numerator.multiply(other.numerator), denominator.multiply(other.denominator));
        }
        BigInteger scale(BigInteger times) {
            if (denominator.equals(ONE)) return numerator.equals(ONE) ? times
                    : times.equals(ONE) ? numerator : numerator.multiply(times);
            // #236: a reduced fraction is integral iff its multiplicity is divisible.
            var parts = times.divideAndRemainder(denominator);
            if (parts[1].signum() != 0) throw new IllegalStateException("non-integral aggregated demand");
            return numerator.equals(ONE) ? parts[0] : numerator.multiply(parts[0]);
        }
    }

    /** Only already-demanded immutable definitions are visible to this compiler. */
    static final class Compiler {
        private final Map<AEKey, List<IPatternDetails>> known;
        private final Function<AEKey, List<IPatternDetails>> resolver;
        private final Map<IPatternDetails, VmDemandProgram> programs = new IdentityHashMap<>();
        private final Map<IPatternDetails, Integer> unavailable = new IdentityHashMap<>();
        private final Set<IPatternDetails> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Runnable control;
        private boolean simulationSources;
        private boolean equivalentSources;
        Compiler equivalentSources() { equivalentSources = true; return this; }
        private Set<AEKey> stockBoundaries = Set.of();
        private final Map<IPatternDetails, Map<Set<AEKey>, StockedProgram>> stockedPrograms = new IdentityHashMap<>();
        private record StockedProgram(VmDemandProgram program, int generation) { }
        Compiler(Map<AEKey, List<IPatternDetails>> known) { this(known, null, () -> { }); }
        Compiler(Map<AEKey, List<IPatternDetails>> known, Runnable control) {
            this(known, null, control);
        }
        Compiler(Map<AEKey, List<IPatternDetails>> known, Runnable control, boolean simulationSources) {
            this(known, null, control);
            this.simulationSources = simulationSources;
        }
        Compiler(Map<AEKey, List<IPatternDetails>> known,
                Function<AEKey, List<IPatternDetails>> resolver, Runnable control) {
            this.known = known; this.resolver = resolver; this.control = control;
        }
        private void checkpoint() {
            VmDemandProgram.checkpoint();
            control.run();
        }

        VmDemandProgram stocked(VmDemandProgram original, Predicate<AEKey> supplied) {
            var boundaries = new HashSet<AEKey>();
            for (var key : original.intermediates) if (supplied.test(key)) boundaries.add(key);
            if (boundaries.isEmpty()) return original;
            var variants = stockedPrograms.computeIfAbsent(original.pattern,
                    ignored -> new LinkedHashMap<>(16, 0.75f, true));
            var cached = variants.get(boundaries);
            if (cached != null && cached.generation == known.size())
                return cached.program;
            // #218: stop at actual stock, not at a permanently uncraftable leaf.
            var compiler = new Compiler(known, null, control);
            compiler.simulationSources = simulationSources;
            compiler.stockBoundaries = Set.copyOf(boundaries);
            var result = compiler.get(original.pattern);
            variants.put(compiler.stockBoundaries, new StockedProgram(result, known.size()));
            // Bound retained summaries, never quantities or the amount of planning work.
            if (variants.size() > 32) variants.remove(variants.keySet().iterator().next());
            return result;
        }

        VmDemandProgram getExpanded(IPatternDetails pattern, Predicate<AEKey> stocked) {
            try { return expandChecked(pattern, stocked); }
            catch (CancellationException cancelled) { throw cancelled; }
            catch (RuntimeException unavailable) { return null; }
        }

        private VmDemandProgram expandChecked(IPatternDetails pattern, Predicate<AEKey> stocked) {
            if (resolver == null) return get(pattern);
            var path = Collections.newSetFromMap(new IdentityHashMap<IPatternDetails, Boolean>());
            var complete = Collections.newSetFromMap(new IdentityHashMap<IPatternDetails, Boolean>());
            if (!expandUnique(pattern, stocked, path, complete)) return null;
            return get(pattern);
        }

        private boolean expandUnique(IPatternDetails pattern, Predicate<AEKey> stocked,
                Set<IPatternDetails> path,
                Set<IPatternDetails> complete) {
            checkpoint();
            if (complete.contains(pattern)) return true;
            if (pattern.getOutputs().length != 1) return false;
            if (!path.add(pattern)) return false;
            try {
                for (var slot : pattern.getInputs()) {
                    checkpoint();
                    if (!(slot instanceof PatternCompiler.DetachedInput exact)) return false;
                    var templates = slot.getPossibleInputs();
                    if (templates.length != 1 || !exact.isCandidatePrepared(templates[0].what())) return false;
                    var key = exact.exactKey();
                    if (key == null || !key.equals(templates[0].what()) || !key.equals(exact.craftedKey()))
                        return false;
                    if (exact.emittable()) continue;
                    // A stocked leaf needs no producer lookup. Compilation records it as
                    // stock-only; the caller resolves it if its demand exceeds that stock.
                    if (stocked.test(key)) continue;
                    var choices = known.computeIfAbsent(key, resolver);
                    if (choices.size() > 1) return false;
                    if (choices.size() == 1) {
                        var producer = choices.get(0);
                        if (!producer.getPrimaryOutput().what().equals(key)
                                || !expandUnique(producer, stocked, path, complete)) return false;
                    }
                }
                complete.add(pattern);
                return true;
            } finally { path.remove(pattern); }
        }

        VmDemandProgram get(IPatternDetails pattern) {
            // #236: compilation is speculative; ordered demand still exposes invalid metadata.
            try { return getChecked(pattern); }
            catch (CancellationException cancelled) { throw cancelled; }
            catch (RuntimeException invalid) {
                unavailable.put(pattern, known.size());
                return null;
            }
        }

        private VmDemandProgram getChecked(IPatternDetails pattern) {
            checkpoint();
            var cached = programs.get(pattern);
            if (cached != null) {
                if (cached.generation == known.size()
                        || cached.leaves.values().stream().noneMatch(Leaf::stockOnly)) return cached;
                if (!unresolvedLeafResolved(cached)) {
                    refreshGeneration(cached, known.size());
                    return cached;
                }
            }
            if (Objects.equals(unavailable.get(pattern), known.size()) || !visiting.add(pattern)) return null;
            VmDemandProgram result;
            try { result = compile(pattern); }
            finally { visiting.remove(pattern); }
            if (result == null) unavailable.put(pattern, known.size());
            else programs.put(pattern, result);
            return result;
        }

        private boolean unresolvedLeafResolved(VmDemandProgram program) {
            for (var entry : program.leaves.entrySet()) {
                if (!entry.getValue().stockOnly()) continue;
                if (known.containsKey(entry.getKey())) return true;
            }
            return false;
        }

        private void refreshGeneration(VmDemandProgram program, int generation) {
            if (program.generation == generation) return;
            program.generation = generation;
            for (var child : program.children) if (child != null) refreshGeneration(child, generation);
        }

        private VmDemandProgram compile(IPatternDetails pattern) {
            if (simulationSources && pattern.getOutputs().length != 1) return null;
            var result = new VmDemandProgram(pattern, known.size());
            result.crafts.put(pattern, Ratio.of(ONE));
            result.bytes = new VmByteCost(ONE, ONE);
            var layout = VmPatternOutputs.inspect(pattern);
            var primary = layout.primary();
            for (var output : layout.amounts().entrySet()) {
                merge(result.outputs, output.getKey(), Ratio.of(output.getValue()));
                result.dependencies.add(output.getKey());
                if (!output.getKey().equals(primary)) result.secondary.add(output.getKey());
            }
            for (var slot : pattern.getInputs()) {
                checkpoint();
                if (!(slot instanceof PatternCompiler.DetachedInput exact)) return null;
                if (simulationSources && exact.mayReturnItems()) return null;
                var templates = slot.getPossibleInputs();
                if (templates.length == 0 || !exact.isCandidatePrepared(templates[0].what())) return null;
                for (var template : templates)
                    if (!template.equals(templates[0])) return null;
                var key = exact.exactKey();
                if (key == null) {
                    key = exact.craftedKey();
                    if (key == null || exact.mayReturnItems() || !exact.isValid(key, null)
                            || exact.getRemainingKey(key) != null) return null;
                    result.fuzzy.add(key);
                }
                if (!key.equals(templates[0].what()) || !key.equals(exact.craftedKey())) return null;
                var unit = BigInteger.valueOf(templates[0].amount());
                var amount = unit.multiply(PatternCompiler.inputMultiplier(slot));
                result.nodes = result.nodes.add(ONE);
                result.inputs.add(key); result.dependencies.add(key);
                result.bytes = result.bytes.add(VmByteCost.forStack(amount, key.getType().getAmountPerByte()));
                var choices = known.get(key);
                if (exact.emittable() || stockBoundaries.contains(key) || choices == null || choices.isEmpty()) {
                    var leaf = new Leaf(unit, exact.emittable(), !exact.emittable()
                            && (choices == null || stockBoundaries.contains(key)));
                    var previous = result.leaves.putIfAbsent(key, leaf);
                    if (previous != null && !previous.equals(leaf)) return null;
                    merge(result.needs, key, Ratio.of(amount));
                    result.children.add(null);
                    continue;
                }
                if (choices.size() != 1) {
                    if (!simulationSources && (!equivalentSources || !equivalentBatches(choices))) return null;
                    result.alternatives.add(key);
                }
                var producer = choices.get(0);
                if (!producer.getPrimaryOutput().what().equals(key)) return null;
                var child = get(producer);
                if (child == null) return null;
                result.children.add(child);
                BigInteger output = ZERO;
                for (var stack : producer.getOutputs()) if (stack.what().equals(key)) output = output.add(PatternCompiler.outputAmount(producer, stack));
                if (output.signum() <= 0) return null;
                var ratio = new Ratio(amount, output);
                result.nodes = result.nodes.add(child.nodes);
                result.intermediates.add(key); result.intermediates.addAll(child.intermediates);
                result.dependencies.addAll(child.dependencies);
                result.fuzzy.addAll(child.fuzzy);
                result.alternatives.addAll(child.alternatives);
                result.inputs.addAll(child.inputs); result.secondary.addAll(child.secondary);
                for (var entry : child.leaves.entrySet()) {
                    var previous = result.leaves.putIfAbsent(entry.getKey(), entry.getValue());
                    if (previous != null && !previous.equals(entry.getValue())) return null;
                }
                combine(result.crafts, child.crafts, ratio);
                combine(result.needs, child.needs, ratio);
                combine(result.outputs, child.outputs, ratio);
                merge(result.outputs, key, Ratio.of(amount.negate()));
                result.bytes = result.bytes.add(new VmByteCost(child.bytes.numerator().multiply(ratio.numerator()),
                        child.bytes.denominator().multiply(ratio.denominator())));
                // Every conceptual sub-call must have whole batches, not just the sum of two fractional calls.
                result.period = lcm(result.period, ratio.denominator().multiply(child.period)
                        .divide(ratio.numerator().gcd(ratio.denominator().multiply(child.period))));
            }
            if (!Collections.disjoint(result.inputs, result.secondary)
                    || result.inputs.contains(primary) || result.secondary.contains(primary)) return null;
            for (var key : result.fuzzy) for (var dependency : result.dependencies)
                if (!key.equals(dependency) && key.getType().equals(dependency.getType())
                        && key.getPrimaryKey().equals(dependency.getPrimaryKey())) return null;
            result.outputs.entrySet().removeIf(entry -> entry.getValue().numerator().signum() == 0);
            if (result.outputs.values().stream().anyMatch(value -> value.numerator().signum() < 0)) return null;
            return result;
        }

        // #236: quota-only compilation, not an execution/capacity summary for alternate providers.
        private boolean equivalentBatches(List<IPatternDetails> sources) {
            var first = sources.get(0);
            if (first.getOutputs().length != 1) return false;
            var output = first.getPrimaryOutput();
            var unit = PatternCompiler.outputAmount(first, output);
            if (unit.signum() <= 0) return false;
            for (var source : sources) {
                checkpoint();
                if (source.getOutputs().length != 1 || !source.getPrimaryOutput().what().equals(output.what())
                        || source.getInputs().length != first.getInputs().length) return false;
                var ratio = PatternCompiler.outputAmount(source, source.getPrimaryOutput()).divideAndRemainder(unit);
                if (ratio[1].signum() != 0 || ratio[0].signum() <= 0) return false;
                for (int i = 0; i < first.getInputs().length; i++) {
                    if (!(first.getInputs()[i] instanceof PatternCompiler.DetachedInput a)
                            || !(source.getInputs()[i] instanceof PatternCompiler.DetachedInput b)
                            || a.exactKey() == null || !a.exactKey().equals(b.exactKey())
                            || !a.exactKey().equals(a.craftedKey()) || !b.exactKey().equals(b.craftedKey())
                            || a.emittable() || b.emittable() || a.mayReturnItems() || b.mayReturnItems()) return false;
                    var aa = a.getPossibleInputs(); var bb = b.getPossibleInputs();
                    if (aa.length == 0 || bb.length == 0 || !aa[0].what().equals(a.exactKey())
                            || !bb[0].what().equals(b.exactKey()) || aa[0].amount() != bb[0].amount()
                            || !a.isCandidatePrepared(a.exactKey()) || !b.isCandidatePrepared(b.exactKey())
                            || a.getRemainingKey(a.exactKey()) != null || b.getRemainingKey(b.exactKey()) != null) return false;
                    for (var t : aa) if (!t.equals(aa[0])) return false;
                    for (var t : bb) if (!t.equals(bb[0])) return false;
                    if (!PatternCompiler.inputMultiplier(b).equals(PatternCompiler.inputMultiplier(a).multiply(ratio[0]))) return false;
                }
            }
            return true;
        }
    }

    private static <K> void combine(Map<K, Ratio> target, Map<K, Ratio> source, Ratio factor) {
        source.forEach((key, value) -> merge(target, key, value.multiply(factor)));
    }
    private static <K> void merge(Map<K, Ratio> target, K key, Ratio value) { target.merge(key, value, Ratio::add); }
    private static BigInteger lcm(BigInteger a, BigInteger b) { return a.divide(a.gcd(b)).multiply(b); }
    private static void checkpoint() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("VM demand compilation cancelled");
    }
}
