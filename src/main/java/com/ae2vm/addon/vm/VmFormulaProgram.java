package com.ae2vm.addon.vm;

import appeng.api.config.FuzzyMode;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import com.ae2vm.addon.compiler.PatternCompiler;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;

/** #236: guarded diagonal-affine planning, with exact branch and return-state boundaries. */
final class VmFormulaProgram {
    private static final BigInteger Z = BigInteger.ZERO, O = BigInteger.ONE;
    record Linear(BigInteger a, BigInteger b) {
        static final Linear ID = new Linear(O, Z), ZERO = new Linear(Z, Z);
        BigInteger at(BigInteger x) { return a.multiply(x).add(b); }
        Linear plus(BigInteger x) { return new Linear(a, b.add(x)); }
        Linear plus(Linear x) { return new Linear(a.add(x.a), b.add(x.b)); }
        Linear scale(BigInteger n) { return new Linear(a.multiply(n), b.multiply(n)); }
        Linear of(Linear x) { return new Linear(a.multiply(x.a), a.multiply(x.b).add(b)); }
    }
    record Bounds(BigInteger min, BigInteger max) {
        Bounds intersect(Bounds other) {
            var lo = min.max(other.min);
            var hi = max == null ? other.max : other.max == null ? max : max.min(other.max);
            if (hi != null && lo.compareTo(hi) > 0) throw new Unsupported();
            return new Bounds(lo, hi);
        }
        boolean contains(BigInteger x) { return x.compareTo(min) >= 0 && (max == null || x.compareTo(max) <= 0); }
    }
    /** All processes exposed at a visited input node, plus only their visited descendants. */
    static final class Call {
        final IPatternDetails pattern;
        boolean entered;
        final Map<Integer, List<Call>> children = new LinkedHashMap<>();
        Call(IPatternDetails pattern) { this.pattern = pattern; }
        Call copy() {
            var result = new Call(pattern);
            result.entered = entered;
            children.forEach((slot, calls) -> result.children.put(slot, calls.stream().map(Call::copy).toList()));
            return result;
        }
        void merge(Call other) {
            if (pattern != other.pattern) throw new Unsupported();
            entered |= other.entered;
            other.children.forEach((slot, calls) -> {
                var combined = new ArrayList<>(children.getOrDefault(slot, List.of()));
                for (var call : calls) {
                    var existing = combined.stream().filter(c -> c.pattern == call.pattern).findFirst();
                    if (existing.isPresent()) existing.get().merge(call); else combined.add(call.copy());
                }
                children.put(slot, combined);
            });
        }
    }
    final Map<AEKey, Linear> stock, missing, emitted;
    final Map<AEKey, Bounds> guards;
    final Map<AEKey, List<Linear>> lows;
    final Map<IPatternDetails, BigInteger> crafts;
    final VmByteCost bytes;
    final BigInteger output;
    final Call call;
    final Set<AEKey> domains;
    private final List<Builder.Lookup> lookups;

    private VmFormulaProgram(Builder b, BigInteger output, Call call) {
        stock = Map.copyOf(b.stock); missing = Map.copyOf(b.missing); emitted = Map.copyOf(b.emitted);
        guards = Map.copyOf(b.guards); crafts = Map.copyOf(b.crafts); bytes = b.bytes;
        var minima = new LinkedHashMap<AEKey, List<Linear>>();
        b.lows.forEach((key, values) -> minima.put(key, List.copyOf(values)));
        lows = Map.copyOf(minima); this.output = output; this.call = call.copy();
        lookups = b.lookups.stream().map(Builder.Lookup::copy).toList();
        domains = Set.copyOf(b.lookups.stream().map(lookup -> lookup.template).toList());
    }
    BigInteger repetitions(Function<AEKey, BigInteger> available, BigInteger limit) {
        for (var e : guards.entrySet()) {
            var x = available.apply(e.getKey());
            var bound = e.getValue();
            if (!bound.contains(x)) return Z;
            var move = stock.getOrDefault(e.getKey(), Linear.ID);
            if (move.a.signum() == 0) {
                if (!bound.contains(move.b)) limit = limit.min(O);
            } else if (move.b.signum() > 0 && bound.max != null) {
                limit = limit.min(bound.max.subtract(x).divide(move.b).add(O));
            } else if (move.b.signum() < 0) {
                limit = limit.min(x.subtract(bound.min).divide(move.b.negate()).add(O));
            }
        }
        return limit.max(Z);
    }
    Linear movement(AEKey key, BigInteger times) {
        var move = stock.getOrDefault(key, Linear.ID);
        return move.a.signum() == 0 ? move : new Linear(O, move.b.multiply(times));
    }
    boolean constantDelta(AEKey key) {
        return stock.getOrDefault(key, Linear.ID).a.equals(O)
                && missing.getOrDefault(key, Linear.ZERO).a.signum() == 0
                && emitted.getOrDefault(key, Linear.ZERO).a.signum() == 0
                && lows.getOrDefault(key, List.of()).stream().allMatch(low -> low.a.equals(O));
    }
    private int cacheWeight() {
        long weight = (long) stock.size() + guards.size() + missing.size() + emitted.size() + crafts.size();
        for (var values : lows.values()) weight += 1L + values.size();
        for (var lookup : lookups) weight += 1L + lookup.at.size() + lookup.known.size();
        var pending = new ArrayDeque<Call>(); pending.add(call);
        while (!pending.isEmpty() && weight <= 65536) {
            var next = pending.removeFirst();
            weight++;
            for (var children : next.children.values()) pending.addAll(children);
        }
        return (int) Math.min(65537, weight);
    }
    Linear total(AEKey key, Linear quantity, BigInteger times) {
        var move = stock.getOrDefault(key, Linear.ID);
        if (move.a.signum() == 0)
            return new Linear(quantity.a, quantity.b.multiply(times)
                    .add(quantity.a.multiply(move.b).multiply(times.subtract(O))));
        return quantity.scale(times).plus(quantity.a.multiply(move.b)
                .multiply(times.multiply(times.subtract(O)).divide(BigInteger.TWO)));
    }
    BigInteger minimum(AEKey key, BigInteger initial, BigInteger times) {
        var result = initial;
        var move = stock.getOrDefault(key, Linear.ID);
        var last = move.a.signum() == 0 ? move.b : initial.add(move.b.multiply(times.subtract(O)));
        for (var low : lows.getOrDefault(key, List.of())) {
            result = result.min(low.at(initial));
            if (times.compareTo(O) > 0) result = result.min(low.at(last));
        }
        return result;
    }

    static final class Compiler {
        private final Function<AEKey, List<IPatternDetails>> resolver;
        private final Runnable checkpoint;
        private final VmInputFacts inputFacts;
        private final Map<IPatternDetails, VmPatternOutputs> outputs = new IdentityHashMap<>();
        private record FixedRoute(VmDemandProgram program, VmMissingProgram proof) { }
        private final Map<IPatternDetails, FixedRoute> fixedPrograms = new IdentityHashMap<>();
        private final Map<IPatternDetails, List<VmMissingProgram.FailureCut>> failureInputs = new IdentityHashMap<>();
        private int failureInputWeight;
        private record Interval(AEKey target, List<AEKey> ancestors, boolean simulate,
                Set<AEKey> keys, VmFormulaProgram program) { }
        private final Map<IPatternDetails, List<Interval>> intervals = new IdentityHashMap<>();
        private final Map<IPatternDetails, List<Interval>> regions = new IdentityHashMap<>();
        private int intervalCount, intervalWeight;
        private int regionCount, regionWeight;
        private int work;
        private int compilationDepth;
        String lastDecline = "none";
        long suffixPowers;
        long directDemands;
        Compiler(Function<AEKey, List<IPatternDetails>> resolver, Runnable checkpoint) {
            this(resolver, checkpoint, new VmInputFacts());
        }
        Compiler(Function<AEKey, List<IPatternDetails>> resolver, Runnable checkpoint, VmInputFacts inputFacts) {
            this.resolver = resolver; this.checkpoint = checkpoint; this.inputFacts = inputFacts;
        }
        private void step() {
            checkpoint.run();
            if (++work > 32768) throw new Unsupported("work-limit");
        }
        private VmPatternOutputs outputs(IPatternDetails pattern) {
            return outputs.computeIfAbsent(pattern, VmPatternOutputs::inspect);
        }
        private List<VmMissingProgram.FailureCut> failureInputs(IPatternDetails pattern) {
            var existing = failureInputs.get(pattern);
            if (existing != null) return existing;
            var result = VmMissingProgram.failureCuts(pattern, resolver, this::step);
            if (failureInputs.size() < 256 && result.size() <= 8192 - failureInputWeight) {
                failureInputs.put(pattern, result);
                failureInputWeight += result.size();
            }
            return result;
        }
        VmFormulaProgram compile(IPatternDetails pattern, AEKey target, Map<AEKey, BigInteger> initial,
                List<AEKey> ancestors, boolean simulate, BigInteger demand) {
            if (compilationDepth++ == 0) { work = 0; lastDecline = "phase-limit"; }
            try { return compileRegion(pattern, target, initial, ancestors, simulate, demand); }
            finally { compilationDepth--; }
        }
        private VmFormulaProgram compileRegion(IPatternDetails pattern, AEKey target, Map<AEKey, BigInteger> initial,
                List<AEKey> ancestors, boolean simulate, BigInteger demand) {
            if (ancestors.size() > 64) return null;
            for (var input : pattern.getInputs()) if (!(input instanceof PatternCompiler.DetachedInput)) return null;
            var b = new Builder(this, initial, simulate);
            var call = new Call(pattern);
            var output = outputs(pattern).amount(target);
            if (output.signum() <= 0) return null;
            var fixed = fixedRoute(pattern, target, ancestors);
            var certificate = fixed == null ? null : fixed.proof.certify(k -> initial.getOrDefault(k, Z), this::step);
            if (certificate != null) {
                try {
                    // #236: retain ceil division and surplus, rather than finding a common repetition period.
                    var high = PatternCompiler.ceilDiv(demand, output);
                    if (!simulate) {
                        high = fundedUpper(fixed.program, high, initial);
                        if (high.signum() == 0) {
                            // #236: no speculative ledger or phase search is needed for a certified zero supply.
                            lastDecline = "unfunded-bound";
                            return null;
                        }
                    }
                    var direct = directDemand(pattern, target, initial, ancestors, simulate,
                            demand.min(high.multiply(output)), certificate);
                    if (direct == null && !simulate) {
                        var low = Z;
                        while (high.subtract(low).compareTo(O) > 0) {
                            var middle = low.add(high).shiftRight(1);
                            var candidate = directDemand(pattern, target, initial, ancestors, false, middle.multiply(output), certificate);
                            if (candidate == null) high = middle;
                            else { low = middle; direct = candidate; }
                        }
                    }
                    if (direct != null) {
                        directDemands++;
                        lastDecline = "direct-demand";
                        return direct;
                    }
                } catch (Unsupported unavailable) {
                    // A partially funded route still needs the ordered source boundary below.
                }
            }
            var total = Z;
            VmFormulaProgram prefix = null;
            Tail tail = null;
            var depleted = new HashSet<AEKey>();
            try {
                // Power each certified interval, then compose across its recipe/tool boundary.
                for (int phase = 0; phase < 256; phase++) {
                    if (total.add(output).compareTo(demand) > 0) break;
                    var now = new VmBranchMap<>(initial, true);
                    b.stock.forEach((key, value) -> now.put(key, b.at(key, value)));
                    // #236: a transient replenishment must not pin every later loop to its initial stock.
                    if (phase > 0 && tail == null)
                        tail = new Tail(new Builder(this, now, simulate), new Call(pattern));
                    var interval = interval(pattern, target, now, ancestors, simulate, output, demand.subtract(total));
                    var times = interval.repetitions(k -> now.getOrDefault(k, Z), demand.subtract(total).divide(interval.output));
                    if (times.signum() <= 0) throw new Unsupported();
                    b.append(interval, times);
                    call.merge(interval.call);
                    total = total.add(interval.output.multiply(times));
                    if (tail != null) tail.append(interval, times);
                    var current = new VmBranchMap<>(initial, true);
                    b.stock.forEach((key, value) -> current.put(key, b.at(key, value)));
                    if (tail != null && tail.phases >= 2 && tail.output.signum() > 0) {
                        tail.builder.finishDomains();
                        var loop = new VmFormulaProgram(tail.builder, tail.output, tail.call);
                        var repeats = loop.repetitions(k -> current.getOrDefault(k, Z), demand.subtract(total).divide(loop.output));
                        if (repeats.signum() > 0) {
                            suffixPowers++;
                            rememberRegion(pattern, target, ancestors, simulate, current, loop);
                            b.append(loop, repeats);
                            call.merge(loop.call);
                            total = total.add(loop.output.multiply(repeats));
                            tail = null;
                        }
                    }
                    // #236: rebase at observed stock depletion, not arbitrary overlapping window boundaries.
                    for (var key : interval.stock.keySet()) {
                        if (now.getOrDefault(key, Z).signum() > 0 && current.getOrDefault(key, Z).signum() == 0
                                && depleted.add(key)) tail = null;
                    }
                    b.finishDomains();
                    var program = new VmFormulaProgram(b, total, call);
                    var limit = demand.divide(total);
                    if (program.repetitions(k -> initial.getOrDefault(k, Z), limit).compareTo(O) > 0) {
                        rememberRegion(pattern, target, ancestors, simulate, initial, program);
                        lastDecline = "repeatable";
                        return program;
                    }
                    if (total.compareTo(output) > 0) prefix = program;
                }
            } catch (Unsupported | Missing unavailable) {
                lastDecline = unavailable instanceof Unsupported unsupported ? unsupported.reason : "missing";
                return prefix;
            }
            lastDecline = total.add(output).compareTo(demand) > 0 ? "complete-prefix" : "phase-limit";
            return prefix;
        }

        private static final class Tail {
            final Builder builder;
            final Call call;
            BigInteger output = Z;
            int phases;
            Tail(Builder builder, Call call) { this.builder = builder; this.call = call; }
            void append(VmFormulaProgram interval, BigInteger times) {
                builder.append(interval, times);
                call.merge(interval.call);
                output = output.add(interval.output.multiply(times));
                phases++;
            }
        }

        private void rememberRegion(IPatternDetails pattern, AEKey target, List<AEKey> ancestors, boolean simulate,
                Map<AEKey, BigInteger> now, VmFormulaProgram program) {
            if (program.output.compareTo(outputs(pattern).amount(target)) <= 0) return;
            int weight = program.cacheWeight();
            if (regionCount >= 64 || weight > 65536 - regionWeight) return;
            var entries = regions.computeIfAbsent(pattern, ignored -> new ArrayList<>());
            for (var entry : entries) if (entry.target.equals(target) && entry.ancestors.equals(ancestors)
                    && entry.simulate == simulate && entry.program.output.equals(program.output)
                    && entry.program.guards.equals(program.guards)) return;
            entries.add(new Interval(target, List.copyOf(ancestors), simulate,
                    program.domains.isEmpty() ? Set.of() : Set.copyOf(now.keySet()), program));
            regionCount++; regionWeight += weight;
        }

        private VmFormulaProgram interval(IPatternDetails pattern, AEKey target, Map<AEKey, BigInteger> now,
                List<AEKey> ancestors, boolean simulate, BigInteger output, BigInteger demand) {
            VmFormulaProgram largest = null;
            for (var cached : regions.getOrDefault(pattern, List.of())) {
                step();
                var program = cached.program;
                if (cached.simulate != simulate || !cached.target.equals(target) || !cached.ancestors.equals(ancestors)
                        || program.output.compareTo(demand) > 0 || largest != null && program.output.compareTo(largest.output) <= 0
                        || !program.domains.isEmpty() && !cached.keys.equals(now.keySet())) continue;
                if (program.repetitions(k -> now.getOrDefault(k, Z), O).signum() > 0) largest = program;
            }
            if (largest != null) return largest;
            for (var cached : intervals.getOrDefault(pattern, List.of())) {
                step();
                if (cached.simulate != simulate || !cached.target.equals(target) || !cached.ancestors.equals(ancestors)) continue;
                var program = cached.program;
                // #236: stock guards alone cannot certify newly appearing fuzzy candidates.
                if (!program.domains.isEmpty() && !cached.keys.equals(now.keySet())) continue;
                if (program.repetitions(k -> now.getOrDefault(k, Z), O).signum() > 0) return program;
            }
            var local = new Builder(this, now, simulate);
            var localCall = new Call(pattern);
            local.process(pattern, O, target, ancestors, localCall, 0);
            local.takeFixed(target, output);
            local.finishDomains();
            var result = new VmFormulaProgram(local, output, localCall);
            int weight = result.cacheWeight();
            if (intervalCount < 64 && intervalWeight + weight <= 32768) {
                intervals.computeIfAbsent(pattern, ignored -> new ArrayList<>()).add(
                        new Interval(target, List.copyOf(ancestors), simulate,
                                result.domains.isEmpty() ? Set.of() : Set.copyOf(now.keySet()), result));
                intervalCount++; intervalWeight += weight;
            }
            return result;
        }

        private VmFormulaProgram directDemand(IPatternDetails pattern, AEKey target, Map<AEKey, BigInteger> initial,
                List<AEKey> ancestors, boolean simulate, BigInteger demand, Map<AEKey, BigInteger> certificate) {
            step();
            var builder = new Builder(this, initial, simulate);
            // #236: the graph is reusable; the current source-selection certificate is not.
            certificate.forEach((key, bound) -> builder.guard(key, Linear.ID, Z, bound));
            var call = new Call(pattern);
            try {
                builder.process(pattern, PatternCompiler.ceilDiv(demand, outputs(pattern).amount(target)), target, ancestors, call, 0);
                builder.takeFixed(target, demand);
                return new VmFormulaProgram(builder, demand, call);
            } catch (Missing unavailable) { return null; }
        }

        private BigInteger fundedUpper(VmDemandProgram program, BigInteger limit, Map<AEKey, BigInteger> stock) {
            step();
            var result = limit;
            var inputs = program.pattern.getInputs();
            for (int i = 0; i < inputs.length; i++) {
                var input = (PatternCompiler.DetachedInput) inputs[i];
                if (input.emittable()) continue;
                var key = input.exactKey();
                var count = PatternCompiler.inputMultiplier(input);
                var available = stock.getOrDefault(key, Z);
                var child = program.children.get(i);
                if (child != null) {
                    var yield = outputs(child.pattern).amount(key);
                    var required = count.multiply(limit).subtract(available).max(Z);
                    available = available.add(yield.multiply(fundedUpper(child, PatternCompiler.ceilDiv(required, yield), stock)));
                }
                result = result.min(available.divide(count));
            }
            return result;
        }

        private FixedRoute fixedRoute(IPatternDetails pattern, AEKey target, List<AEKey> ancestors) {
            var route = fixedPrograms.get(pattern);
            if (!fixedPrograms.containsKey(pattern)) {
                var proof = VmMissingProgram.compile(pattern, target, List.of(), resolver, this::step);
                if (proof != null) {
                    var compiler = new VmDemandProgram.Compiler(new HashMap<>(), proof::firstSource, this::step);
                    var coefficients = compiler.getExpanded(pattern, key -> false);
                    if (coefficients != null) route = new FixedRoute(coefficients, proof);
                }
                if (fixedPrograms.size() < 256) fixedPrograms.put(pattern, route);
            }
            if (route == null || !Collections.disjoint(route.program.dependencies, ancestors)) return null;
            for (var leaf : route.program.leaves.values()) if (!leaf.unit().equals(O) || leaf.stockOnly()) return null;
            return route;
        }

    }

    private static final class Builder {
        final Compiler compiler;
        final Map<AEKey, BigInteger> initial;
        boolean simulate;
        final Map<AEKey, Linear> stock = new LinkedHashMap<>(), missing = new LinkedHashMap<>(), emitted = new LinkedHashMap<>();
        final Map<AEKey, Bounds> guards = new LinkedHashMap<>();
        final Map<AEKey, List<Linear>> lows = new LinkedHashMap<>();
        final Map<IPatternDetails, BigInteger> crafts = new LinkedHashMap<>();
        final List<Lookup> lookups = new ArrayList<>();
        private record Family(Object type, Object primary) {
            static Family of(AEKey key) { return new Family(key.getType(), key.getPrimaryKey()); }
        }
        final Map<Family, Set<AEKey>> families = new HashMap<>();
        boolean indexed;
        VmByteCost bytes = VmByteCost.ZERO;
        private static final class Lookup {
            final AEKey template;
            final Map<AEKey, Linear> at;
            Set<AEKey> known;
            Set<AEKey> checkedFamily;
            AEKey selected;
            BigInteger unit = O;
            Lookup(AEKey template, Map<AEKey, Linear> at, Collection<AEKey> known) {
                this.template = template; this.at = Map.copyOf(at); this.known = Set.copyOf(known);
            }
            Lookup copy() {
                var result = new Lookup(template, at, known); result.selected = selected; result.unit = unit;
                result.checkedFamily = checkedFamily; return result;
            }
        }
        Builder(Compiler compiler, Map<AEKey, BigInteger> initial, boolean simulate) {
            this.compiler = compiler; this.initial = initial; this.simulate = simulate;
        }
        Builder(Builder source) {
            this(source.compiler, source.initial, source.simulate);
            stock.putAll(source.stock); missing.putAll(source.missing); emitted.putAll(source.emitted);
            guards.putAll(source.guards); crafts.putAll(source.crafts); bytes = source.bytes;
            source.lows.forEach((key, values) -> lows.put(key, new ArrayList<>(values)));
            source.lookups.forEach(lookup -> lookups.add(lookup.copy()));
            copyIndex(source);
        }
        void adopt(Builder source) {
            stock.clear(); stock.putAll(source.stock); missing.clear(); missing.putAll(source.missing);
            emitted.clear(); emitted.putAll(source.emitted); guards.clear(); guards.putAll(source.guards);
            crafts.clear(); crafts.putAll(source.crafts); lows.clear(); lows.putAll(source.lows); bytes = source.bytes;
            lookups.clear(); lookups.addAll(source.lookups);
            copyIndex(source);
        }
        void append(VmFormulaProgram p, BigInteger times) {
            var before = new LinkedHashMap<>(stock);
            for (var entry : p.guards.entrySet()) {
                var key = entry.getKey(); var bound = entry.getValue();
                var move = p.stock.getOrDefault(key, Linear.ID);
                var drift = move.a.signum() == 0 ? Z : move.b.multiply(times.subtract(O));
                guard(key, value(key), bound.min.subtract(drift.min(Z)),
                        bound.max == null ? null : bound.max.subtract(drift.max(Z)));
                if (times.compareTo(O) > 0 && move.a.signum() == 0 && !bound.contains(move.b)) throw new Unsupported();
            }
            p.missing.forEach((key, quantity) -> missing.merge(key, p.total(key, quantity, times).of(value(key)), Linear::plus));
            p.emitted.forEach((key, quantity) -> emitted.merge(key, p.total(key, quantity, times).of(value(key)), Linear::plus));
            p.lows.forEach((key, values) -> {
                var start = value(key);
                var last = times.equals(O) ? start : p.movement(key, times.subtract(O)).of(start);
                for (var value : values) { low(key, value.of(start)); low(key, value.of(last)); }
            });
            for (var lookup : p.lookups) {
                var at = new LinkedHashMap<AEKey, Linear>();
                for (var key : family(lookup.template))
                    if (before.containsKey(key)) at.put(key, before.get(key));
                lookup.at.forEach((key, value) -> at.put(key, value.of(before.getOrDefault(key, Linear.ID))));
                var translated = new Lookup(lookup.template, at, lookup.known);
                translated.selected = lookup.selected;
                translated.unit = lookup.unit;
                lookups.add(translated);
            }
            p.stock.forEach((key, value) -> set(key, p.movement(key, times).of(before.getOrDefault(key, Linear.ID))));
            p.crafts.forEach((pattern, count) -> crafts.merge(pattern, count.multiply(times), BigInteger::add));
            bytes = bytes.add(p.bytes.multiply(times));
        }
        Linear value(AEKey key) { return stock.getOrDefault(key, Linear.ID); }
        BigInteger at(AEKey key, Linear value) { return value.at(initial.getOrDefault(key, Z)); }
        BigInteger amount(AEKey key) { return at(key, value(key)); }
        void guard(AEKey key, Linear value, BigInteger min, BigInteger max) {
            compiler.step();
            if (value.a.signum() == 0) {
                if (!new Bounds(min, max).contains(value.b)) throw new Unsupported();
                return;
            }
            Bounds bound;
            if (value.a.equals(O)) bound = new Bounds(min.subtract(value.b).max(Z), max == null ? null : max.subtract(value.b));
            else if (value.a.equals(O.negate())) bound = new Bounds(max == null ? Z : value.b.subtract(max).max(Z), value.b.subtract(min));
            else throw new Unsupported();
            guards.merge(key, bound, Bounds::intersect);
            if (!guards.get(key).contains(initial.getOrDefault(key, Z))) throw new Unsupported();
        }
        void low(AEKey key, Linear value) {
            var values = lows.computeIfAbsent(key, ignored -> new ArrayList<>());
            for (int i = 0; i < values.size(); i++) if (values.get(i).a.equals(value.a)) {
                if (value.b.compareTo(values.get(i).b) < 0) values.set(i, value);
                return;
            }
            values.add(value);
        }
        void set(AEKey key, Linear value) {
            if (stock.size() >= 8192 && !stock.containsKey(key)) throw new Unsupported();
            if (!(value.a.equals(O) || value.a.equals(Z)) || at(key, value).signum() < 0) throw new Unsupported();
            stock.put(key, value); if (indexed) index(key); low(key, value);
        }
        private void index(AEKey key) {
            var family = Family.of(key);
            var previous = families.getOrDefault(family, Set.of());
            if (previous.contains(key)) return;
            var updated = new LinkedHashSet<>(previous); updated.add(key);
            families.put(family, Collections.unmodifiableSet(updated));
        }
        private Set<AEKey> family(AEKey template) {
            if (!indexed) {
                // #236: fixed arithmetic must not enumerate unrelated inventory to build unused fuzzy indexes.
                var built = new HashMap<Family, Set<AEKey>>();
                for (var keys : List.of(initial.keySet(), stock.keySet())) for (var key : keys)
                    built.computeIfAbsent(Family.of(key), ignored -> new LinkedHashSet<>()).add(key);
                built.forEach((family, keys) -> families.put(family, Collections.unmodifiableSet(keys)));
                indexed = true;
            }
            return families.getOrDefault(Family.of(template), Set.of());
        }
        private void copyIndex(Builder source) {
            indexed = source.indexed;
            families.clear();
            // #236: all published family sets are immutable; a branch copies only the map.
            families.putAll(source.families);
        }
        void takeFixed(AEKey key, BigInteger amount) {
            guard(key, value(key), amount, null);
            set(key, value(key).plus(amount.negate()));
        }
        void add(AEKey key, BigInteger amount) { set(key, value(key).plus(amount)); }
        void process(IPatternDetails pattern, BigInteger times, AEKey target, List<AEKey> ancestors, Call call, int depth) {
            compiler.step();
            call.entered = true;
            if (depth > 64) throw new Unsupported();
            var path = new ArrayList<>(ancestors); path.add(target);
            var inputs = pattern.getInputs();
            var returns = new LinkedHashMap<AEKey, BigInteger>();
            for (int slot = 0; slot < inputs.length; slot++) {
                var input = inputs[slot];
                if (!(input instanceof PatternCompiler.DetachedInput detached)) throw new Unsupported();
                var templates = input.getPossibleInputs();
                if (templates.length == 0 || templates[0].amount() <= 0) throw new Unsupported();
                var unit = BigInteger.valueOf(templates[0].amount());
                var count = PatternCompiler.inputMultiplier(input).multiply(times);
                if (count.signum() <= 0) throw new Unsupported();
                var key = detached.craftedKey();
                bytes = bytes.add(VmByteCost.forStack(count.multiply(unit), key.getType().getAmountPerByte()));
                if (detached.exactKey() != null) {
                    if (templates.length != 1 || !key.equals(detached.exactKey()) || detached.mayReturnItems()) throw new Unsupported();
                    if (unit.equals(O)) request(key, count, detached.emittable(), path, call, slot, depth + 1);
                    else {
                        var taken = takeUnits(key, unit, count);
                        if (taken.compareTo(count) < 0) produce(key, new Linear(Z, count.subtract(taken).multiply(unit)),
                                detached.emittable(), path, call, slot, depth + 1);
                    }
                } else {
                    var remaining = count;
                    for (var template : templates) {
                        if (remaining.signum() == 0) break;
                        if (template.amount() <= 0) throw new Unsupported();
                        var candidateUnit = BigInteger.valueOf(template.amount());
                        while (remaining.signum() > 0) {
                            compiler.step();
                            var candidate = select(detached, template.what(), candidateUnit);
                            if (candidate == null) break;
                            var taken = takeUnits(candidate, candidateUnit, remaining);
                            returned(detached, candidate, taken, returns);
                            remaining = remaining.subtract(taken);
                        }
                    }
                    if (remaining.signum() > 0) {
                        returned(detached, key, remaining, returns);
                        // #236: native fuzzy requests do not extract a rejected encoded key again.
                        produce(key, new Linear(Z, remaining.multiply(unit)), detached.emittable(), path, call, slot, depth + 1);
                    }
                }
            }
            returns.forEach((key, count) -> {
                add(key, count); bytes = bytes.add(VmByteCost.forStack(count, key.getType().getAmountPerByte()));
            });
            compiler.outputs(pattern).amounts().forEach((key, count) -> add(key, count.multiply(times)));
            crafts.merge(pattern, times, BigInteger::add);
            bytes = bytes.add(new VmByteCost(times, O));
        }
        private BigInteger takeUnits(AEKey key, BigInteger unit, BigInteger count) {
            var taken = amount(key).divide(unit).min(count);
            var physical = taken.multiply(unit);
            guard(key, value(key), physical, taken.equals(count) ? null : physical.add(unit).subtract(O));
            set(key, value(key).plus(physical.negate()));
            return taken;
        }
        private void returned(PatternCompiler.DetachedInput input, AEKey key, BigInteger count, Map<AEKey, BigInteger> returns) {
            if (!compiler.inputFacts.ready(input, key)) throw new Unsupported();
            var remainder = compiler.inputFacts.returned(input, key);
            if (remainder != null) returns.merge(remainder, count, BigInteger::add);
        }
        private AEKey select(PatternCompiler.DetachedInput input, AEKey template, BigInteger unit) {
            var keys = new LinkedHashSet<>(family(template)); keys.add(template);
            if (keys.size() > 8192) throw new Unsupported();
            // #236: unrelated stock cannot affect a fuzzy family; avoid O(templates * all-stock) copies.
            var at = new LinkedHashMap<AEKey, Linear>();
            for (var key : keys) if (stock.containsKey(key)) at.put(key, stock.get(key));
            var lookup = new Lookup(template, at, keys);
            lookup.checkedFamily = family(template);
            lookup.unit = unit;
            lookups.add(lookup);
            var index = new KeyCounter();
            // Include zero states too; their guards protect ordering if a returned state becomes available.
            for (var key : keys) { compiler.step(); index.add(key, 1); }
            if (template.getFuzzySearchMaxValue() <= 0) {
                // #236: a singleton needs no hash-order assumption; competing NBT states remain ordered.
                AEKey sole = null;
                for (var entry : index.findFuzzy(template, FuzzyMode.IGNORE_ALL)) {
                    var key = entry.getKey();
                    if (amount(key).compareTo(unit) < 0) guard(key, value(key), Z, unit.subtract(O));
                    else {
                        if (sole != null) throw new Unsupported();
                        sole = key; guard(key, value(key), unit, null);
                    }
                }
                if (sole == null) return null;
                if (!compiler.inputFacts.ready(input, sole)) throw new Unsupported();
                if (!compiler.inputFacts.valid(input, sole)) return null;
                lookup.selected = sole;
                return sole;
            }
            for (var e : index.findFuzzy(template, FuzzyMode.IGNORE_ALL)) {
                compiler.step();
                var key = e.getKey();
                if (amount(key).compareTo(unit) < 0) { guard(key, value(key), Z, unit.subtract(O)); continue; }
                if (!compiler.inputFacts.ready(input, key)) throw new Unsupported();
                if (!compiler.inputFacts.valid(input, key)) continue;
                guard(key, value(key), unit, null);
                lookup.selected = key;
                return key;
            }
            return null;
        }
        void finishDomains() {
            for (var lookup : lookups) {
                var current = family(lookup.template);
                if (current == lookup.checkedFamily) continue;
                Set<AEKey> updated = null;
                for (var key : current) {
                    if (lookup.known.contains(key)) continue;
                    if (updated == null) updated = new HashSet<>(lookup.known);
                    updated.add(key);
                    if (lookup.selected != null && lookup.template.getFuzzySearchMaxValue() > 0) {
                        var order = new KeyCounter(); order.add(key, 1); order.add(lookup.selected, 1);
                        if (order.findFuzzy(lookup.template, FuzzyMode.IGNORE_ALL).iterator().next().getKey().equals(lookup.selected)) continue;
                    }
                    // Only earlier-priority future states can invalidate the selected candidate.
                    guard(key, lookup.at.getOrDefault(key, Linear.ID), Z, lookup.unit.subtract(O));
                }
                if (updated != null) lookup.known = Set.copyOf(updated);
                lookup.checkedFamily = current;
            }
        }
        private void request(AEKey key, BigInteger count, boolean emit, List<AEKey> ancestors,
                Call owner, int slot, int depth) {
            var available = amount(key);
            if (available.compareTo(count) >= 0) { takeFixed(key, count); return; }
            var start = value(key);
            guard(key, start, Z, count.subtract(O));
            var demand = new Linear(start.a.negate(), count.subtract(start.b));
            set(key, Linear.ZERO);
            produce(key, demand, emit, ancestors, owner, slot, depth);
        }
        private void produce(AEKey key, Linear demand, boolean emit, List<AEKey> ancestors,
                Call owner, int slot, int depth) {
            if (emit) { emitted.merge(key, demand, Linear::plus); return; }
            var definitions = compiler.resolver.apply(key).stream().filter(p -> !conflicts(p, ancestors)).toList();
            var identities = Collections.newSetFromMap(new IdentityHashMap<IPatternDetails, Boolean>());
            for (var pattern : definitions) if (!identities.add(pattern)) throw new Unsupported();
            var calls = new ArrayList<Call>();
            for (var p : definitions) calls.add(new Call(p));
            var prior = owner.children.get(slot);
            if (prior != null) for (var call : calls) prior.stream().filter(c -> c.pattern == call.pattern).findFirst().ifPresent(call::merge);
            owner.children.put(slot, calls);
            if (simulate && definitions.size() > 1) {
                simulate = false;
                try { demand = craft(key, demand, ancestors, calls, depth, true); }
                finally { simulate = true; }
                if (at(key, demand).signum() > 0) demand = craft(key, demand, ancestors, calls.subList(0, 1), depth, true);
            } else demand = craft(key, demand, ancestors, calls, depth, definitions.size() > 1);
            if (at(key, demand).signum() == 0) return;
            if (!simulate) throw new Missing();
            missing.merge(key, demand, Linear::plus);
        }
        private Linear craft(AEKey key, Linear demand, List<AEKey> ancestors, List<Call> calls, int depth, boolean alternatives) {
            for (var call : calls) {
                while (at(key, demand).signum() > 0) {
                    compiler.step();
                    var output = compiler.outputs(call.pattern).amount(key);
                    if (output.signum() <= 0) throw new Unsupported();
                    if ((alternatives || repeated(call.pattern)) && at(key, demand).compareTo(output.multiply(BigInteger.TWO)) >= 0) {
                        var now = new VmBranchMap<>(initial, true);
                        stock.forEach((k, value) -> now.put(k, at(k, value)));
                        var summary = compiler.compile(call.pattern, key, now, ancestors, simulate, at(key, demand));
                        if (summary != null) {
                            var repetitions = summary.repetitions(k -> now.getOrDefault(k, Z), at(key, demand).divide(summary.output));
                            var delivered = summary.output.multiply(repetitions);
                            if (delivered.compareTo(output) > 0) {
                                var wanted = at(key, demand);
                                guard(key, demand, wanted, wanted);
                                append(summary, repetitions);
                                call.merge(summary.call);
                                demand = demand.plus(delivered.negate());
                                continue;
                            }
                        }
                    }
                    // Bounded partial evaluation preserves per-craft alternative/return semantics.
                    var times = alternatives || repeated(call.pattern) ? O : PatternCompiler.ceilDiv(at(key, demand), output);
                    if (times.compareTo(O) > 0) guard(key, demand,
                            output.multiply(times.subtract(O)).add(O), output.multiply(times));
                    var trial = new Builder(this);
                    try { trial.process(call.pattern, times, key, ancestors, call, depth); }
                    catch (Missing absent) {
                        if (!failedPredicate(call, times, key, ancestors)) {
                            trial.guards.forEach((k, v) -> guards.merge(k, v, Bounds::intersect));
                            lookups.clear(); lookups.addAll(trial.lookups);
                        }
                        break;
                    }
                    adopt(trial);
                    var produced = output.multiply(times);
                    if (at(key, demand).compareTo(produced) >= 0) {
                        guard(key, demand, produced, null);
                        set(key, value(key).plus(produced.negate()));
                        demand = demand.plus(produced.negate());
                    } else {
                        guard(key, demand, O, produced.subtract(O));
                        set(key, value(key).plus(demand.scale(O.negate())));
                        demand = Linear.ZERO;
                    }
                }
                if (at(key, demand).signum() == 0) {
                    // #236: stopping at an exact fit is a branch, not just a lower-bound check.
                    guard(key, demand, Z, Z);
                    return demand;
                }
            }
            return demand;
        }
        private boolean failedPredicate(Call call, BigInteger times, AEKey target, List<AEKey> ancestors) {
            var candidates = new ArrayList<>(compiler.failureInputs(call.pattern));
            candidates.sort(Comparator.comparingInt(cut -> amount(cut.key()).signum()));
            for (var cut : candidates) {
                var required = cut.amount().multiply(times);
                if (amount(cut.key()).compareTo(required) >= 0) continue;
                if (!coveredPrefix(call, target, ancestors, cut.key())) continue;
                // #236: discarded trial reads are irrelevant only after its capacity branches are covered.
                guard(cut.key(), value(cut.key()), Z, required.subtract(O));
                return true;
            }
            return false;
        }
        private boolean coveredPrefix(Call call, AEKey target, List<AEKey> ancestors, AEKey stop) {
            compiler.step();
            // #236: exposed candidates already count their terminal input slots, even before entry.
            if (ancestors.size() > 64 || stop != null && !call.entered) return false;
            var path = new ArrayList<>(ancestors); path.add(target);
            var inputs = call.pattern.getInputs();
            for (int i = 0; i < inputs.length; i++) {
                var input = (PatternCompiler.DetachedInput) inputs[i];
                var key = input.craftedKey();
                if (key.equals(stop)) return true;
                if (input.emittable()) continue;
                for (var definition : compiler.resolver.apply(key)) {
                    if (conflicts(definition, path)) continue;
                    var visited = call.children.getOrDefault(i, List.of()).stream()
                            .filter(child -> child.pattern == definition).findFirst().orElse(null);
                    if (visited == null || !coveredPrefix(visited, key, path, null)) return false;
                }
            }
            return stop == null;
        }
        private static boolean repeated(IPatternDetails pattern) {
            for (var input : pattern.getInputs()) {
                if (!(input instanceof PatternCompiler.DetachedInput detached)) throw new Unsupported();
                if (detached.mayReturnItems()) return true;
                for (var output : pattern.getOutputs()) if (detached.mayConsumeOutput(output.what())) return true;
            }
            return false;
        }
        private static boolean conflicts(IPatternDetails pattern, List<AEKey> ancestors) {
            for (var key : ancestors) {
                for (var output : pattern.getOutputs()) if (key.equals(output.what())) return true;
                for (var input : pattern.getInputs()) if (key.equals(input.getPossibleInputs()[0].what())) return true;
            }
            return false;
        }
    }
    private static final class Unsupported extends RuntimeException {
        final String reason;
        Unsupported() { this("unsupported"); }
        Unsupported(String reason) { super(null, null, false, false); this.reason = reason; }
    }
    private static final class Missing extends RuntimeException { Missing() { super(null, null, false, false); } }
}
