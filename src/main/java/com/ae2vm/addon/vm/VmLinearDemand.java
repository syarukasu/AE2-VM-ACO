package com.ae2vm.addon.vm;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.ae2vm.addon.compiler.PatternCompiler;
import java.math.BigInteger;
import java.util.*;

/** #236: rounded single-input definitions; quantities and inventory are never cached. */
final class VmLinearDemand {
    record Source(IPatternDetails pattern, BigInteger consumed, BigInteger output, boolean fuzzy) { }
    record Step(AEKey key, AEKey input, List<Source> sources) { }
    final List<Step> steps;
    final Set<AEKey> dependencies;
    final Set<AEKey> fuzzy;
    record Effect(List<BigInteger> times, List<BigInteger> inputs, List<BigInteger> surplus,
            BigInteger missing, VmByteCost bytes) { }

    Effect emptyEffect(BigInteger demand, Runnable checkpoint) {
        var times = new ArrayList<BigInteger>();
        var inputs = new ArrayList<BigInteger>();
        var surplus = new ArrayList<BigInteger>();
        var bytes = VmByteCost.ZERO;
        for (var step : steps) {
            checkpoint.run();
            var source = step.sources.get(0);
            var crafts = PatternCompiler.ceilDiv(demand, source.output);
            var produced = source.output.equals(BigInteger.ONE) ? crafts : crafts.multiply(source.output);
            surplus.add(produced.subtract(demand)); times.add(crafts);
            demand = source.consumed.equals(BigInteger.ONE) ? crafts : crafts.multiply(source.consumed);
            inputs.add(demand);
            bytes = bytes.add(VmByteCost.forStack(demand, step.input.getType().getAmountPerByte()))
                    .add(new VmByteCost(crafts, BigInteger.ONE));
        }
        return new Effect(List.copyOf(times), List.copyOf(inputs), List.copyOf(surplus), demand, bytes);
    }
    private VmLinearDemand(List<Step> steps, Set<AEKey> dependencies, Set<AEKey> fuzzy) {
        this.steps = List.copyOf(steps); this.dependencies = Set.copyOf(dependencies);
        this.fuzzy = Set.copyOf(fuzzy);
    }

    static VmLinearDemand compile(AEKey root, Map<AEKey, List<IPatternDetails>> catalog, Runnable checkpoint) {
        try { return compileChecked(root, catalog, checkpoint); }
        catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
        catch (RuntimeException unsupported) { return null; }
    }

    private static VmLinearDemand compileChecked(AEKey root, Map<AEKey, List<IPatternDetails>> catalog, Runnable checkpoint) {
        var steps = new ArrayList<Step>();
        var keys = new HashSet<AEKey>();
        var fuzzy = new HashSet<AEKey>();
        var key = root;
        while (keys.add(key)) {
            checkpoint.run();
            var choices = catalog.get(key);
            if (choices == null) return null;
            if (choices.isEmpty()) {
                for (var template : fuzzy) for (var dependency : keys)
                    if (!template.equals(dependency) && template.getType().equals(dependency.getType())
                            && template.getPrimaryKey().equals(dependency.getPrimaryKey())) return null;
                return steps.isEmpty() ? null : new VmLinearDemand(steps, keys, fuzzy);
            }
            var first = inspect(key, choices.get(0));
            if (first == null) return null;
            var sources = new ArrayList<Source>();
            var firstSource = first.sources.get(0);
            for (var choice : choices) {
                var other = inspect(key, choice);
                if (other == null || !first.input.equals(other.input)) return null;
                var source = other.sources.get(0);
                if (!source.consumed.multiply(firstSource.output).equals(firstSource.consumed.multiply(source.output)))
                    return null;
                sources.add(source);
                if (source.fuzzy) fuzzy.add(first.input);
            }
            steps.add(new Step(key, first.input, List.copyOf(sources)));
            if (steps.size() >= 64) return null;
            key = first.input;
        }
        return null;
    }

    private static Step inspect(AEKey key, IPatternDetails pattern) {
        GenericStack[] outputs = pattern.getOutputs();
        var inputs = pattern.getInputs();
        if (outputs.length != 1 || !key.equals(outputs[0].what()) || inputs.length != 1
                || !(inputs[0] instanceof PatternCompiler.DetachedInput input)) return null;
        var templates = input.getPossibleInputs();
        var selected = input.exactKey() == null ? input.craftedKey() : input.exactKey();
        if (selected == null || !selected.equals(input.craftedKey()) || templates.length == 0
                || input.mayReturnItems() || input.emittable() || !input.isCandidatePrepared(selected)
                || !input.isValid(selected, null) || input.getRemainingKey(selected) != null) return null;
        for (var template : templates)
            if (template.amount() != 1 || !selected.equals(template.what())) return null;
        var amount = PatternCompiler.inputMultiplier(input);
        var output = PatternCompiler.outputAmount(pattern, outputs[0]);
        if (amount.signum() <= 0 || output.signum() <= 0) return null;
        return new Step(key, selected, List.of(new Source(pattern, amount, output, input.exactKey() == null)));
    }
}
