package com.ae2vm.addon.nativeengine;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import com.mojang.logging.LogUtils;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

/** One order stock snapshot, the BigInteger VM, and AE2's existing worker pool. */
public final class NativeVm {
    private static final Logger LOG = LogUtils.getLogger();
    private static final NativeVmDiagnostics DIAGNOSTICS = new NativeVmDiagnostics(LOG, System::nanoTime,
            Boolean.getBoolean("ae2vm.diagnostics.verbose"));
    private static final AtomicLong ORDERS = new AtomicLong();
    private static final Map<FutureTask<?>, MinecraftServer> ACTIVE = new ConcurrentHashMap<>();
    private static volatile VmAccounting accounting;
    private NativeVm() { }

    public record CalculationSample(long order, String output, BigInteger requested, long elapsedMillis, String status,
            String timing) {
        public CalculationSample(long order, String output, BigInteger requested, long elapsedMillis, String status) {
            this(order, output, requested, elapsedMillis, status, "");
        }
    }

    /** Bounded diagnostics only; never a cache of plans or world references. */
    public static java.util.List<CalculationSample> recentCalculations() { return DIAGNOSTICS.recent(); }

    public static void installAccounting(VmAccounting extension) {
        if (accounting != null && accounting != extension) throw new IllegalStateException("VM accounting already registered");
        accounting = Objects.requireNonNull(extension);
    }

    public static void cancelServer(MinecraftServer server) {
        ACTIVE.forEach((future, owner) -> { if (owner == server) future.cancel(true); });
    }

    public static Future<ICraftingPlan> calculate(IGrid grid, Level level,
            ICraftingSimulationRequester requester, AEKey output, BigInteger requested, CalculationStrategy strategy) {
        Objects.requireNonNull(grid); Objects.requireNonNull(requester); Objects.requireNonNull(strategy);
        Objects.requireNonNull(output); Objects.requireNonNull(requested);
        var server = Objects.requireNonNull(level.getServer());
        if (requested.signum() <= 0) throw new IllegalArgumentException("positive VM order required");
        long order = ORDERS.incrementAndGet(), started = System.nanoTime();
        boolean benchmarkCapture = NativeVmBenchmark.claimCapture();
        var observation = NativeVmProfiler.start(order, () -> output.getId().toString(), requested);
        var timing = observation.timing();
        DIAGNOSTICS.started();
        var job = new AtomicReference<java.util.concurrent.Callable<ICraftingPlan>>();
        class OrderTask extends FutureTask<ICraftingPlan> {
            OrderTask() {
                super(() -> {
                    timing.workerStarted();
                    try { return job.get().call(); }
                    finally { timing.workerFinished(); }
                });
            }
            @Override protected void done() {
                ACTIVE.remove(this);
                if (!observation.enabled()) return;
                // #221: FutureTask is the single terminal owner, including pre-run cancellation.
                NativeVmProfiler.observe(() -> {
                    if (isCancelled()) {
                        observation.finish("cancelled", timing.cancellationReason());
                        return;
                    }
                    try {
                        get();
                        boolean missing = timing.materialMissing();
                        observation.finish(missing ? "material_missing" : "plan_ready",
                                missing ? "materials_unavailable" : "completed");
                    } catch (java.util.concurrent.ExecutionException failure) {
                        var cause = failure.getCause();
                        String reason = NativeVmProfiler.failureReason(cause);
                        if (reason.equals("exception")) {
                            String observed = timing.failureReason(cause);
                            reason = observed == null ? cause.getClass().getName() : observed;
                        }
                        observation.finish(NativeVmProfiler.failureOutcome(cause),
                                reason);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            void fail(Throwable failure) { setException(failure); }
        }
        var task = new OrderTask();
        ACTIVE.put(task, server);
        Runnable initialize = () -> {
            if (task.isCancelled()) return;
            timing.initializationStarted();
            try {
                if (server.isStopped()) throw new CancellationException("server stopped");
                var hook = (NativeVmHook) grid.getCraftingService();
                var extension = accounting;
                VmAccounting.Stock stock;
                try (var span = timing.phase(NativeVmTiming.Phase.STOCK)) {
                    stock = requestStock(grid, requester, extension);
                }
                if (task.isCancelled()) return;
                var access = new NativeVmAccess(System::nanoTime, task::isCancelled);
                var context = new NativeVmPlanningContext(grid, level, requester, stock, access, timing);
                Object orderContext;
                try (var span = timing.phase(NativeVmTiming.Phase.PREPARE)) {
                    context.prepare(output);
                    orderContext = extension == null ? null : extension.orderContext(grid,level);
                }
                if (task.isCancelled()) return;
                if (benchmarkCapture) {
                    NativeVmProfiler.observe(() -> {
                        try (var span = timing.phase(NativeVmTiming.Phase.DIAGNOSTIC_CAPTURE)) {
                            NativeVmBenchmark.attach(order,
                                    context.benchmarkSnapshot(output, requested, strategy == CalculationStrategy.CRAFT_LESS));
                        }
                    });
                }
                java.util.function.Function<NativeVmAccess, ICraftingPlan> compute = owner ->
                        compute(order, started, grid, level, output, requested,
                                strategy, extension, context, orderContext, timing, owner);
                boolean liveAccess = context.requiresLiveAccess();
                if (liveAccess) {
                    var calculation = new NativeVmCalculation(level, grid, output, strategy, access, compute);
                    job.set(calculation::run);
                } else {
                    // Frozen exact inputs need neither AE2's placeholder tree nor its live-read tick registration.
                    job.set(() -> { try { return compute.apply(access); } finally { access.finish(); } });
                }
                timing.workerSubmitted(liveAccess);
                hook.vm$execute(task);
            } catch (Throwable failure) {
                if (failure instanceof CancellationException) DIAGNOSTICS.cancelled();
                else DIAGNOSTICS.failed(order, output.getId(), requested,
                        failure instanceof RuntimeException runtime ? runtime : new IllegalStateException(failure));
                task.fail(failure);
            }
        };
        // Off-thread API callers queue creation once, never one task per ingredient.
        if (server.isSameThread()) {
            timing.initializationSubmitted();
            initialize.run();
        } else try {
            timing.initializationSubmitted();
            server.execute(initialize);
        }
        catch (RuntimeException failure) { task.fail(failure); }
        return task;
    }

    static VmAccounting.Stock requestStock(IGrid grid, ICraftingSimulationRequester requester, VmAccounting extension) {
        var source = requester.getActionSource();
        if (source == null) return new VmAccounting.Stock(Map.of(), true, java.util.Set.of());
        VmAccounting owner = extension != null ? extension : new VmAccounting() {
            public BigInteger maximumCount() { return BigInteger.valueOf(Long.MAX_VALUE); }
            public Stock exactStock(IGrid value) {
                Map<AEKey, BigInteger> amounts = new LinkedHashMap<>();
                for (var entry : value.getStorageService().getCachedInventory()) {
                    if (entry.getLongValue() < 0) throw new IllegalArgumentException("negative AE2 stock");
                    if (entry.getLongValue() > 0) amounts.put(entry.getKey(), BigInteger.valueOf(entry.getLongValue()));
                }
                return new Stock(amounts, true, amounts.keySet());
            }
            public ICraftingPlan wideResult(IGrid value, Level world, NativeVmResult result) {
                throw new IllegalStateException("wide result requires an exact accounting owner");
            }
        };
        var config = appeng.core.AEConfig.instance();
        return owner.requestStock(grid, source, config != null && config.isCraftingSimulatedExtraction());
    }

    static ICraftingPlan compute(long order, long started, IGrid grid, Level level,
            AEKey output, BigInteger request, CalculationStrategy strategy,
            VmAccounting extension, NativeVmPlanningContext context, Object orderContext,
            NativeVmTiming timing, NativeVmAccess access) {
        timing.ae2Scheduled(access);
        try {
            var result = context.plan(output, request, strategy == CalculationStrategy.CRAFT_LESS,
                    work -> DIAGNOSTICS.running(order, output.getId(), work, elapsed(started), timing::summary));
            BigInteger bytes = bytes(result);
            boolean wide = result.requested().bitLength() > 63
                    || wide(result.crafts()) || wide(result.used()) || wide(result.missing()) || wide(result.emitted())
                    || context.widePendingOutputs(result.crafts());
            var exact = new NativeVmResult(result, context.bindings(), bytes, wide);
            ICraftingPlan plan;
            try (var span = timing.phase(NativeVmTiming.Phase.FINALIZE)) {
                if (exact.wide()) {
                    if (extension == null) throw new IllegalStateException("wide VM result requires an exact accounting owner");
                    plan = extension.wideResult(grid, level, exact, orderContext);
                } else plan = ordinaryPlan(exact);
            }
            String profile = timing.summary();
            boolean simulation = plan.simulation();
            timing.planResult(simulation);
            DIAGNOSTICS.record(new CalculationSample(order, output.getId().toString(), request,
                    elapsed(started), simulation ? "missing" : "ready", profile));
            DIAGNOSTICS.completed(order, output.getId(), request, elapsed(started), plan.simulation(), profile);
            if (DIAGNOSTICS.verbose()) LOG.debug("AE2-VM event=quantity_calculated route=vm-native order={} "
                    + "output={} requested={} {}", order, output.getId(), request, profile);
            return plan;
        } catch (CancellationException cancelled) {
            DIAGNOSTICS.record(new CalculationSample(order, output.getId().toString(), request,
                    elapsed(started), "cancelled", timing.summary()));
            DIAGNOSTICS.cancelled();
            throw cancelled;
        } catch (RuntimeException failure) {
            DIAGNOSTICS.record(new CalculationSample(order, output.getId().toString(), request,
                    elapsed(started), "failed", timing.summary()));
            DIAGNOSTICS.failed(order, output.getId(), request, failure);
            throw failure;
        }
    }

    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }

    private static boolean wide(Map<?, BigInteger> values) { return values.values().stream().anyMatch(v -> v.bitLength() > 63); }

    private static ICraftingPlan ordinaryPlan(NativeVmResult exact) {
        var result = exact.amounts();
        Map<IPatternDetails, Long> times = new LinkedHashMap<>();
        result.crafts().forEach((id, count) -> times.put(exact.bindings().get(id), count.longValueExact()));
        return new CraftingPlan(new GenericStack(result.root(), result.requested().longValueExact()),
                exact.bytes().longValueExact(), !result.missing().isEmpty(), result.multiplePaths(),
                counter(result.used()), counter(result.emitted()), counter(result.missing()), Map.copyOf(times));
    }

    private static KeyCounter counter(Map<AEKey, BigInteger> values) {
        var result = new KeyCounter();
        values.forEach((key, count) -> result.add(key, count.longValueExact()));
        return result;
    }

    private static BigInteger bytes(VmQuantities result) {
        BigInteger whole = result.integerCharges(), numerator = BigInteger.ZERO, denominator = BigInteger.ONE;
        for (var entry : result.stackCharges().entrySet()) {
            BigInteger divisor = BigInteger.valueOf(entry.getKey().getType().getAmountPerByte());
            if (divisor.signum() <= 0) throw new IllegalArgumentException("invalid AE2 byte unit");
            var fraction = entry.getValue().multiply(BigInteger.valueOf(8)).divideAndRemainder(divisor);
            whole = whole.add(fraction[0]);
            numerator = numerator.multiply(divisor).add(fraction[1].multiply(denominator));
            denominator = denominator.multiply(divisor);
            var reduced = numerator.divideAndRemainder(denominator);
            whole = whole.add(reduced[0]);
            var gcd = reduced[1].gcd(denominator);
            numerator = reduced[1].divide(gcd); denominator = denominator.divide(gcd);
        }
        return numerator.signum() == 0 ? whole : whole.add(BigInteger.ONE);
    }

}
