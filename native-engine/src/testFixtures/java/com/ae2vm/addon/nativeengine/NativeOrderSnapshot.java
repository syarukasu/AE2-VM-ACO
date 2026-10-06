package com.ae2vm.addon.nativeengine;

import appeng.api.config.FuzzyMode;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import com.syaru.ae2vm.exact.graph.RecipeGraph;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import net.minecraft.world.level.Level;

/** All live information is captured before the AE2 worker starts. Handles are adapter-only. */
public final class NativeOrderSnapshot {
    public interface CatalogSource { NativePatternCatalog catalog(); }

    private final RecipeGraph capturedGraph;
    private final NativePatternCatalog.Program program;
    private final int root;
    private final List<AEKey> keys;
    private final Map<Integer, BigInteger> stock;
    private final Set<Integer> emitters;
    private final Map<String, IPatternDetails> bindings;
    private final Object accountingContext;

    NativeOrderSnapshot(RecipeGraph graph, int root, List<AEKey> keys, Map<Integer, BigInteger> stock,
            Set<Integer> emitters, Map<String, IPatternDetails> bindings, Object accountingContext) {
        this(graph, null, root, keys, stock, emitters, bindings, accountingContext);
    }

    private NativeOrderSnapshot(RecipeGraph graph, NativePatternCatalog.Program program, int root,
            List<AEKey> keys, Map<Integer, BigInteger> stock, Set<Integer> emitters,
            Map<String, IPatternDetails> bindings, Object context) {
        this.capturedGraph = graph; this.program = program; this.root = root;
        this.keys = List.copyOf(keys); this.stock = Map.copyOf(stock); this.emitters = Set.copyOf(emitters);
        this.bindings = Map.copyOf(bindings); this.accountingContext = context;
    }

    RecipeGraph graph() { return program == null ? capturedGraph : program.graph(); }
    int root() { return root; }
    List<AEKey> keys() { return keys; }
    Map<Integer, BigInteger> stock() { return stock; }
    Set<Integer> emitters() { return emitters; }
    Map<String, IPatternDetails> bindings() { return bindings; }
    Object accountingContext() { return accountingContext; }

    NativeOrderSnapshot withAccountingContext(Object context) {
        return new NativeOrderSnapshot(capturedGraph,program,root,keys,stock,emitters,bindings,context);
    }

    static NativeOrderSnapshot capture(IGrid grid, Level level, ICraftingSimulationRequester requester,
            AEKey root, VmAccounting.Stock stock, NativeVmTiming timing, BooleanSupplier cancelled) {
        var node = requester.getGridNode();
        var producerService = node == null ? null : node.getGrid().getCraftingService();
        var emitterService = grid.getCraftingService();
        var catalog = producerService instanceof CatalogSource source ? source.catalog() : null;
        if (catalog != null) {
            var program = catalog.program(root, producerService, cancelled);
            if (program != null) {
                var quantities = new LinkedHashMap<Integer, BigInteger>();
                var emitted = new LinkedHashSet<Integer>();
                var demanded = Set.copyOf(program.demands);
                for (int id = 0; id < program.keys.size(); id++) {
                    if (cancelled.getAsBoolean()) throw new CancellationException("order capture cancelled");
                    var key = program.keys.get(id);
                    if (program.inputKeys.contains(key)) stock.amount(key);
                    var count = stock.amounts().getOrDefault(key, BigInteger.ZERO);
                    if (id == 0 && !stock.complete() && !stock.exactKeys().contains(key)) count = BigInteger.ZERO;
                    if (count.signum() > 0) quantities.put(id, count);
                    if (demanded.contains(key) && emitterService.canEmitFor(key)) emitted.add(id);
                }
                return new NativeOrderSnapshot(null, program, 0, program.keys, quantities, emitted, program.bindings, null);
            }
        }
        return new Capture(producerService, emitterService, level, stock, timing, cancelled, catalog).capture(root);
    }

    private static final class Capture {
        private final ICraftingService producers, emitters;
        private final Level level;
        private final VmAccounting.Stock stock;
        private final NativeVmTiming timing;
        private final BooleanSupplier cancelled;
        private final NativePatternCatalog catalog;
        private final Map<AEKey, Integer> ids = new LinkedHashMap<>();
        private final Set<AEKey> demanded = new LinkedHashSet<>();
        private final KeyCounter candidates = new KeyCounter();
        private boolean craftablesIndexed;
        private final ArrayDeque<AEKey> pendingKeys = new ArrayDeque<>();
        private final ArrayDeque<Candidate> pendingInputs = new ArrayDeque<>();
        private final Map<IPatternDetails, Pattern> patterns = new LinkedHashMap<>();
        private final List<Pattern> ordered = new ArrayList<>();
        private final Map<Object, List<Input>> inputFamilies = new HashMap<>();
        private final Set<Integer> emitted = new LinkedHashSet<>();
        private final Map<String, IPatternDetails> bindings = new LinkedHashMap<>();

        Capture(ICraftingService producers, ICraftingService emitters, Level level, VmAccounting.Stock stock,
                NativeVmTiming timing, BooleanSupplier cancelled, NativePatternCatalog catalog) {
            this.producers=producers; this.emitters=emitters; this.level=level; this.stock=stock;
            this.timing=timing; this.cancelled=cancelled;
            this.catalog = catalog;
            // KeyCounter is only a fuzzy-key index. Never place real quantities into its long counters.
            stock.amounts().keySet().forEach(key -> candidates.set(key, 1));
        }

        NativeOrderSnapshot capture(AEKey root) {
            int rootId = key(root,true);
            while (!pendingKeys.isEmpty() || !pendingInputs.isEmpty()) {
                checkpoint();
                if (!pendingKeys.isEmpty()) {
                    var key = pendingKeys.remove();
                    if (emitters != null && emitters.canEmitFor(key)) emitted.add(ids.get(key));
                    if (producers != null) for (var pattern : List.copyOf(producers.getCraftingFor(key))) pattern(pattern);
                } else candidate(pendingInputs.remove());
            }
            var recipes = new ArrayList<RecipeGraph.Recipe>();
            for (var pattern : ordered) {
                var slots = new ArrayList<RecipeGraph.Slot>();
                for (var input : pattern.inputs) {
                    if (input.options.isEmpty()) throw new IllegalArgumentException("No valid captured input: " + pattern.id);
                    slots.add(new RecipeGraph.Slot(input.multiplier, List.copyOf(input.options)));
                }
                recipes.add(new RecipeGraph.Recipe(pattern.id, slots, pattern.outputs));
            }
            var quantities = new LinkedHashMap<Integer, BigInteger>();
            ids.forEach((key, index) -> {
                // The root is not taken from stock unless an actual recipe input uses it.
                var amount = stock.amounts().getOrDefault(key, BigInteger.ZERO);
                if (index == rootId && !stock.complete() && !stock.exactKeys().contains(key)) amount = BigInteger.ZERO;
                if (amount.signum() > 0) quantities.put(index, amount);
            });
            return new NativeOrderSnapshot(new RecipeGraph(ids.size(), recipes), rootId, List.copyOf(ids.keySet()),
                    quantities, emitted, bindings, null);
        }

        private int key(AEKey key,boolean demand) {
            if (demand && demanded.add(key)) pendingKeys.add(key);
            var id = ids.get(key);
            if (id != null) return id;
            id = ids.size(); ids.put(key,id);
            boolean fresh = candidates.get(key) == 0;
            candidates.set(key,1);
            if (fresh) for (var input : inputFamilies.getOrDefault(key.getPrimaryKey(),List.of()))
                input.enqueue(key);
            return id;
        }

        private void pattern(IPatternDetails source) {
            if (patterns.containsKey(source)) return;
            var result = new Pattern("graph-" + ordered.size());
            patterns.put(source,result); ordered.add(result); bindings.put(result.id,source);
            timing.patternType(source.getClass().getName());
            var fixed = catalog == null ? null : catalog.definition(source);
            if (fixed != null) {
                for (var output : fixed.outputs()) result.outputs.merge(key(output.key(), false), output.amount(), BigInteger::add);
                for (var slot : fixed.inputs()) {
                    stock.amount(slot.key());
                    var input = new Input(null, new GenericStack[0], slot.multiplier(), true);
                    input.options.add(new RecipeGraph.Option(key(slot.key(), true), slot.amount(), -1));
                    result.inputs.add(input);
                }
                return;
            }
            var coefficients = NativePatternCatalog.coefficients(source);
            var semantics = coefficients.pattern();
            var outputs = semantics.getOutputs().clone();
            if (outputs.length == 0) throw new IllegalArgumentException("pattern without outputs");
            for (var out : outputs) {
                if (out == null || out.amount() <= 0) throw new IllegalArgumentException("nonpositive pattern output");
                result.outputs.merge(key(out.what(),false), BigInteger.valueOf(out.amount()).multiply(coefficients.scale()),BigInteger::add);
            }
            for (var slot : semantics.getInputs().clone()) {
                checkpoint();
                boolean nativeExact = semantics instanceof appeng.crafting.pattern.AEProcessingPattern
                        && slot.getClass().getName().equals("appeng.crafting.pattern.AEProcessingPattern$Input")
                        || semantics.getClass() == appeng.crafting.pattern.AECraftingPattern.class
                        && !((appeng.crafting.pattern.AECraftingPattern)semantics).canSubstitute()
                        && slot.getClass().getName().equals("appeng.crafting.pattern.AECraftingPattern$Input");
                var input = new Input(slot, slot.getPossibleInputs().clone(),
                        BigInteger.valueOf(slot.getMultiplier()).multiply(coefficients.scale()),nativeExact);
                if (input.templates.length == 0 || input.multiplier.signum() <= 0)
                    throw new IllegalArgumentException("invalid pattern slot");
                result.inputs.add(input);
                for (var template : input.templates) {
                    if (template == null || template.amount() <= 0) throw new IllegalArgumentException("invalid template");
                    var family = inputFamilies.computeIfAbsent(template.what().getPrimaryKey(),ignored -> new ArrayList<>());
                    if (!family.contains(input)) family.add(input);
                    key(template.what(),false);
                }
                if (!input.exactNative && input.templates.length > 1) indexCraftables();
                for (var template : input.templates) {
                    input.enqueue(template.what());
                    if (!input.exactNative) for (var candidate : candidates.findFuzzy(template.what(),FuzzyMode.IGNORE_ALL)) input.enqueue(candidate.getKey());
                }
                // Native AE2 permits a fuzzy craftable even with only one encoded template.
                if (producers != null && !input.exactNative) for (var template : input.templates) {
                    if (template.amount() != input.templates[0].amount()) continue;
                    var fuzzy = producers.getFuzzyCraftable(template.what(), candidate -> input.valid(candidate));
                    if (fuzzy != null) { key(fuzzy,false); input.enqueue(fuzzy); }
                }
            }
        }

        private void indexCraftables() {
            if (craftablesIndexed || producers == null) return;
            craftablesIndexed = true;
            for (var key : producers.getCraftables(candidate -> true)) {
                checkpoint(); candidates.set(key,1);
                for (var input : inputFamilies.getOrDefault(key.getPrimaryKey(),List.of())) input.enqueue(key);
            }
        }

        private void candidate(Candidate candidate) {
            var input = candidate.input;
            var key = candidate.key;
            if (!input.valid(key)) return;
            stock.amount(key); // An unknown saturated quantity is not silently accepted as long.
            int id = key(key,true);
            AEKey returned = input.returns.get(key);
            if (!input.returns.containsKey(key)) {
                returned = input.source.getRemainingKey(key);
                input.returns.put(key,returned);
                timing.remainderRead(input.source.getClass().getName(),key.getId().toString(),returned==null);
            }
            if (returned != null && !ids.containsKey(returned)
                    && key.getPrimaryKey().equals(returned.getPrimaryKey()) && input.valid(returned)) {
                boolean finiteDamage = false;
                if (key instanceof appeng.api.stacks.AEItemKey before && returned instanceof appeng.api.stacks.AEItemKey after) {
                    var oldStack=before.toStack(); var newStack=after.toStack();
                    int maximum=oldStack.getMaxDamage();
                    finiteDamage=maximum>0 && newStack.getMaxDamage()==maximum
                            && newStack.getDamageValue()>oldStack.getDamageValue() && newStack.getDamageValue()<=maximum;
                }
                if (!finiteDamage) throw new UnsupportedOperationException(
                        "Remainder changes a reusable key without a finite captured domain: " + key.getId());
            }
            int returnId = returned == null ? -1 : key(returned,false);
            input.options.add(new RecipeGraph.Option(id, candidate.amount, returnId));
        }

        private void checkpoint() {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new CancellationException("order capture cancelled");
        }

        private static final class Pattern {
            final String id;
            final List<Input> inputs = new ArrayList<>();
            final Map<Integer,BigInteger> outputs = new LinkedHashMap<>();
            Pattern(String id) { this.id=id; }
        }
        private final class Input {
            final IPatternDetails.IInput source;
            final GenericStack[] templates;
            final BigInteger multiplier;
            final boolean exactNative;
            final Set<RecipeGraph.Option> options = new LinkedHashSet<>();
            final Set<Candidate> queued = new LinkedHashSet<>();
            final Map<AEKey,Boolean> validity = new HashMap<>();
            final Map<AEKey,AEKey> returns = new HashMap<>();
            Input(IPatternDetails.IInput source,GenericStack[] templates,BigInteger multiplier,boolean exactNative) {
                this.source=source; this.templates=templates; this.multiplier=multiplier; this.exactNative=exactNative;
            }
            boolean valid(AEKey key) { checkpoint(); return validity.computeIfAbsent(key,k -> source.isValid(k,level)); }
            void enqueue(AEKey key) {
                if (exactNative && !templates[0].what().equals(key)) return;
                for (var template : templates) if (template.what().getPrimaryKey().equals(key.getPrimaryKey())) {
                    var value = new Candidate(this,key,BigInteger.valueOf(template.amount()));
                    if (queued.add(value)) pendingInputs.add(value);
                }
            }
        }
        private record Candidate(Input input,AEKey key,BigInteger amount) { }
    }
}
