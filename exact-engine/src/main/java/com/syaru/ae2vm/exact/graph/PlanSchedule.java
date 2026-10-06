package com.syaru.ae2vm.exact.graph;

import static java.math.BigInteger.ONE;
import static java.math.BigInteger.ZERO;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Prefix requirements and repeated blocks use exact arithmetic, not per-item replay. */
public final class PlanSchedule {
    public sealed interface Step permits Batch, Repeat {
        Summary summary();
        Map<String, BigInteger> crafts();
    }

    public record Summary(Map<Integer, BigInteger> seed, Map<Integer, BigInteger> delta) {
        public Summary { seed = Map.copyOf(seed); delta = Map.copyOf(delta); }
        public Summary then(Summary next) {
            var needs = new LinkedHashMap<>(seed);
            next.seed.forEach((key, count) -> needs.merge(key,
                    count.subtract(delta.getOrDefault(key, ZERO)).max(ZERO), BigInteger::max));
            var change = new LinkedHashMap<>(delta);
            next.delta.forEach((key, count) -> add(change, key, count));
            return new Summary(clean(needs), clean(change));
        }
        public Summary repeat(BigInteger times) {
            if (times.signum() < 0) throw new IllegalArgumentException("negative repetition");
            if (times.signum() == 0) return new Summary(Map.of(), Map.of());
            var needs = new LinkedHashMap<>(seed);
            var change = new LinkedHashMap<Integer, BigInteger>();
            delta.forEach((key, count) -> {
                change.put(key, count.multiply(times));
                if (count.signum() < 0) add(needs, key, count.negate().multiply(times.subtract(ONE)));
            });
            return new Summary(needs, change);
        }
    }

    public record Batch(String recipe, Map<Integer, BigInteger> inputs,
            Map<Integer, BigInteger> outputs, Map<Integer, BigInteger> returns,
            BigInteger times) implements Step {
        public Batch {
            RecipeGraph.positive(times);
            inputs = RecipeGraph.positiveMap(inputs); outputs = RecipeGraph.positiveMap(outputs);
            returns = RecipeGraph.positiveMap(returns);
        }
        public Summary summary() {
            var delta = new LinkedHashMap<>(outputs);
            returns.forEach((key, count) -> add(delta, key, count));
            inputs.forEach((key, count) -> add(delta, key, count.negate()));
            return new Summary(inputs, clean(delta)).repeat(times);
        }
        public Map<String, BigInteger> crafts() { return Map.of(recipe, times); }
        public Batch withTimes(BigInteger value) { return new Batch(recipe, inputs, outputs, returns, value); }
    }

    public record Repeat(List<Step> body, BigInteger times, Summary summary,
            Map<String, BigInteger> crafts) implements Step {
        public Repeat(List<Step> body, BigInteger times) {
            this(List.copyOf(body), times, summarize(body).repeat(times), totalCrafts(body, times));
        }
        public Repeat { body = List.copyOf(body); crafts = Map.copyOf(crafts); RecipeGraph.positive(times); }
    }

    public record Ordered(List<Step> steps, Summary summary) {
        public Ordered { steps = List.copyOf(steps); }
    }

    public static Ordered order(List<Batch> selected, Map<Integer, BigInteger> available) {
        try { return greedy(selected,available); }
        catch (UnresolvedOrderException blocked) { return search(selected,available); }
    }

    static Ordered inOrder(List<Batch> selected,Map<Integer,BigInteger> available) {
        var stock=new LinkedHashMap<>(available);
        for (var batch:selected) { checkpoint(); apply(batch.summary(),stock); }
        return new Ordered(new ArrayList<>(selected),summarize(selected));
    }

    private static Ordered greedy(List<Batch> selected, Map<Integer, BigInteger> available) {
        var stock = new LinkedHashMap<>(available);
        var pending = selected.stream().map(Batch::times).toArray(BigInteger[]::new);
        var steps = new ArrayList<Step>();
        var trail = new ArrayList<Integer>();
        var trailSteps = new ArrayList<Step>();
        while (true) {
            checkpoint();
            int candidate = -1;
            BigInteger repetitions = ZERO;
            boolean unfinished = false;
            for (int i = 0; i < selected.size(); i++) {
                if (pending[i].signum() == 0) continue;
                unfinished = true;
                var possible = limit(selected.get(i).withTimes(ONE).summary(), stock, pending[i]);
                if (possible.signum() > 0) { candidate = i; repetitions = possible; break; }
            }
            if (!unfinished) return new Ordered(steps, summarize(steps));
            if (candidate < 0) throw new UnresolvedOrderException(
                    "Balanced recipe counts have no enabled next block; not a proven craftable plan", stock);

            // #218: a repeated cycle is a prefix/delta calculation, never a long-sized replay loop.
            int previous = trail.lastIndexOf(candidate);
            if (previous >= 0) {
                var body = List.copyOf(trailSteps.subList(previous, trailSteps.size()));
                BigInteger rounds = null;
                var perRound = new LinkedHashMap<Integer, BigInteger>();
                for (int i = previous; i < trail.size(); i++)
                    perRound.merge(trail.get(i), ((Batch) trailSteps.get(i)).times(), BigInteger::add);
                for (var entry : perRound.entrySet()) {
                    var count = pending[entry.getKey()].divide(entry.getValue());
                    rounds = rounds == null ? count : rounds.min(count);
                }
                rounds = limit(summarize(body), stock, rounds);
                if (rounds.signum() > 0) {
                    var repeated = new Repeat(body, rounds);
                    steps.add(repeated);
                    apply(repeated.summary(), stock);
                    for (var entry : perRound.entrySet())
                        pending[entry.getKey()] = pending[entry.getKey()].subtract(entry.getValue().multiply(rounds));
                    trail.clear(); trailSteps.clear();
                    continue;
                }
                trail.clear(); trailSteps.clear();
            }
            var batch = selected.get(candidate).withTimes(repetitions);
            steps.add(batch); trail.add(candidate); trailSteps.add(batch);
            apply(batch.summary(), stock);
            pending[candidate] = pending[candidate].subtract(repetitions);
        }
    }

    /** Exact fallback for an order conflict, not a second quantity calculator. */
    private static Ordered search(List<Batch> selected, Map<Integer, BigInteger> available) {
        var stock = new LinkedHashMap<>(available);
        var pending = selected.stream().map(Batch::times).toArray(BigInteger[]::new);
        var prefix = new ArrayList<Step>();
        var frames = new java.util.ArrayDeque<SearchFrame>();
        frames.push(new SearchFrame(moves(selected,pending,stock),null));
        while (!frames.isEmpty()) {
            checkpoint();
            var frame = frames.peek();
            if (frame.next == frame.moves.size()) {
                frames.pop();
                if (frame.entered != null) {
                    var move = frame.entered;
                    var batch = selected.get(move.recipe).withTimes(move.times);
                    batch.summary().delta.forEach((key,count) -> add(stock,key,count.negate()));
                    pending[move.recipe] = pending[move.recipe].add(move.times);
                    prefix.remove(prefix.size()-1);
                }
                continue;
            }
            var move = frame.moves.get(frame.next++);
            var batch = selected.get(move.recipe).withTimes(move.times);
            apply(batch.summary(),stock); pending[move.recipe] = pending[move.recipe].subtract(move.times);
            prefix.add(batch);
            var rest = new ArrayList<Batch>();
            for (int i=0;i<pending.length;i++) if (pending[i].signum()>0) rest.add(selected.get(i).withTimes(pending[i]));
            try {
                var tail = greedy(rest,stock);
                var result = new ArrayList<Step>(prefix); result.addAll(tail.steps);
                return new Ordered(result,summarize(result));
            } catch (UnresolvedOrderException blocked) {
                frames.push(new SearchFrame(moves(selected,pending,stock),move));
            }
        }
        throw new UnresolvedOrderException("No legal ordering for selected recipe/input counts",available);
    }

    private record Move(int recipe,BigInteger times) { }
    private static final class SearchFrame {
        final List<Move> moves;
        final Move entered;
        int next;
        SearchFrame(List<Move> moves,Move entered) { this.moves=moves; this.entered=entered; }
    }
    private static List<Move> moves(List<Batch> selected,BigInteger[] pending,Map<Integer,BigInteger> stock) {
        var result = new ArrayList<Move>();
        for (int i=0;i<selected.size();i++) if (pending[i].signum()>0) {
            var maximum = limit(selected.get(i).withTimes(ONE).summary(),stock,pending[i]);
            if (maximum.signum()>0) {
                result.add(new Move(i,maximum));
                if (!maximum.equals(ONE)) result.add(new Move(i,ONE));
            }
        }
        return result;
    }

    static BigInteger limit(Summary summary, Map<Integer, BigInteger> stock, BigInteger upper) {
        var limit = upper;
        for (var entry : summary.seed.entrySet()) {
            var available = stock.getOrDefault(entry.getKey(), ZERO);
            if (available.compareTo(entry.getValue()) < 0) return ZERO;
            var delta = summary.delta.getOrDefault(entry.getKey(), ZERO);
            if (delta.signum() < 0)
                limit = limit.min(available.subtract(entry.getValue()).divide(delta.negate()).add(ONE));
        }
        return limit;
    }

    public static Summary summarize(List<? extends Step> steps) {
        var seed = new LinkedHashMap<Integer,BigInteger>();
        var delta = new LinkedHashMap<Integer,BigInteger>();
        for (var step : steps) {
            checkpoint(); var next=step.summary();
            next.seed.forEach((key,count) -> seed.merge(key,
                    count.subtract(delta.getOrDefault(key,ZERO)).max(ZERO),BigInteger::max));
            next.delta.forEach((key,count) -> add(delta,key,count));
        }
        return new Summary(clean(seed),clean(delta));
    }
    private static Map<String, BigInteger> totalCrafts(List<Step> steps, BigInteger times) {
        var result = new LinkedHashMap<String, BigInteger>();
        for (var step : steps) step.crafts().forEach((key, count) -> result.merge(key, count.multiply(times), BigInteger::add));
        return result;
    }
    static void apply(Summary summary, Map<Integer, BigInteger> stock) {
        if (limit(summary, stock, ONE).signum() == 0) throw new IllegalStateException("unavailable schedule seed");
        summary.delta.forEach((key, count) -> add(stock, key, count));
    }
    static void add(Map<Integer, BigInteger> values, int key, BigInteger count) {
        if (count.signum() != 0) values.merge(key, count, BigInteger::add);
    }
    static Map<Integer, BigInteger> clean(Map<Integer, BigInteger> values) {
        values.values().removeIf(value -> value.signum() == 0);
        return values;
    }
    static void checkpoint() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("graph planning cancelled");
    }
    public static final class UnresolvedOrderException extends IllegalStateException {
        private final Map<Integer, BigInteger> stock;
        UnresolvedOrderException(String message, Map<Integer, BigInteger> stock) { super(message); this.stock = Map.copyOf(stock); }
        public Map<Integer, BigInteger> stock() { return stock; }
    }
}
