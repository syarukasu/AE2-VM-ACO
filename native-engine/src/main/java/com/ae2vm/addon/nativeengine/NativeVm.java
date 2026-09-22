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
import com.syaru.ae2vm.exact.ExactBranchVM;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

/** Native VM request owner. No dependency on ACO's graph/planner/capture classes. */
public final class NativeVm {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Map<IGrid, Long> EPOCHS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicLong RECIPES = new AtomicLong();
    private static final AtomicLong ORDERS = new AtomicLong();
    private static final Map<FutureTask<?>, MinecraftServer> ACTIVE = new ConcurrentHashMap<>();
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), task -> {
                var thread = new Thread(task, "AE2 VM Calculator");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static volatile VmAccounting accounting;
    private NativeVm() { }

    public static void installAccounting(VmAccounting extension) {
        if (accounting != null && accounting != extension) throw new IllegalStateException("VM accounting already registered");
        accounting = Objects.requireNonNull(extension);
    }

    public static long epoch(IGrid grid) { return EPOCHS.getOrDefault(grid, 0L); }
    public static long recipeEpoch() { return RECIPES.get(); }
    public static void recipesChanged() { RECIPES.incrementAndGet(); }
    public static void patternsChanged(IGrid grid) { EPOCHS.merge(grid, 1L, Long::sum); }
    public static void cancelServer(MinecraftServer server) {
        ACTIVE.forEach((future, owner) -> { if (owner == server) future.cancel(true); });
        WORKERS.purge();
    }

    public static Future<ICraftingPlan> calculate(IGrid grid, Level level,
            ICraftingSimulationRequester requester, AEKey output, BigInteger requested, CalculationStrategy strategy) {
        Objects.requireNonNull(grid); Objects.requireNonNull(requester); Objects.requireNonNull(strategy);
        Objects.requireNonNull(output); Objects.requireNonNull(requested);
        var server = Objects.requireNonNull(level.getServer());
        if (requested.signum() <= 0) throw new IllegalArgumentException("positive VM order required");
        long order = ORDERS.incrementAndGet();
        FutureTask<ICraftingPlan> task = new FutureTask<>(() -> compute(order, grid, level, output, requested, strategy)) {
            @Override protected void done() { ACTIVE.remove(this); }
        };
        ACTIVE.put(task, server);
        try { WORKERS.execute(task); }
        catch (RuntimeException failure) { ACTIVE.remove(task); task.cancel(false); throw failure; }
        return task;
    }

    private static ICraftingPlan compute(long order, IGrid grid, Level level, AEKey output,
            BigInteger request, CalculationStrategy strategy) {
        long started = System.nanoTime();
        LOG.info("AE2-VM event=started order={} output={} requested={} owner=vm-native", order, output.getId(), request);
        try {
            for (int attempt = 0; ; attempt++) {
                try {
                    VmAccounting extension = accounting;
                    var capture = new NativeVmCapture(grid, level, extension);
                    BigInteger maximum = extension == null ? BigInteger.TEN.pow(16_384).subtract(BigInteger.ONE)
                            : extension.maximumCount();
                    long[] nextLog = {started + TimeUnit.SECONDS.toNanos(5)};
                    var result = new ExactBranchVM<>(output, capture::candidates, capture::emittable,
                            capture::amount, key -> key.getType().getAmountPerByte(), work -> {
                                if (Thread.currentThread().isInterrupted()) throw new CancellationException("VM order cancelled");
                                if ((work & 1023) == 0 && System.nanoTime() >= nextLog[0]) {
                                    LOG.info("AE2-VM event=running order={} work={} elapsedMs={}", order, work,
                                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                                    nextLog[0] = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                                }
                            }, maximum, capture).plan(request, strategy == CalculationStrategy.CRAFT_LESS);
                    var bindings = capture.bindings();
                    BigInteger bytes = bytes(result);
                    boolean wide = result.requested().bitLength() > 63
                            || wide(result.crafts()) || wide(result.used()) || wide(result.missing()) || wide(result.emitted());
                    // Even a long execution count can overflow AE2's output multiplication.
                    wide |= capture.widePendingOutputs(result.crafts());
                    var exact = new NativeVmResult(result, bindings, bytes, wide);
                    capture.validate();
                    LOG.info("AE2-VM event=quantities_ready order={} patterns={} usedKeys={} missingKeys={} wide={} elapsedMs={}",
                            order, result.crafts().size(), result.used().size(), result.missing().size(), exact.wide(),
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                    ICraftingPlan plan = capture.callServer(() -> {
                        if (exact.wide()) {
                            if (extension == null) throw new IllegalStateException("wide VM result requires an exact accounting owner");
                            return extension.wideResult(grid, level, exact);
                        }
                        return ordinaryPlan(exact);
                    });
                    LOG.info("AE2-VM event=quantity_calculated route=vm-native order={} output={} requested={} "
                                    + "instructions={} work={} skipped={} simulation={} elapsedMs={}",
                            order, output.getId(), result.requested(), result.instructions(), result.work(),
                            result.skippedIterations(), plan.simulation(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                    return plan;
                } catch (Changed changed) {
                    if (attempt >= 2) throw changed;
                    LOG.info("AE2-VM event=recapture order={} reason={}", order, changed.getMessage());
                }
            }
        } catch (CancellationException cancelled) {
            LOG.info("AE2-VM event=cancelled order={}", order);
            throw cancelled;
        } catch (RuntimeException failure) {
            LOG.error("AE2-VM event=failed order={} output={} requested={}", order, output.getId(), request, failure);
            throw failure;
        }
    }

    private static boolean wide(Map<?, BigInteger> values) { return values.values().stream().anyMatch(v -> v.bitLength() > 63); }

    private static ICraftingPlan ordinaryPlan(NativeVmResult exact) {
        var result = exact.amounts();
        Map<IPatternDetails, Long> times = new LinkedHashMap<>();
        result.crafts().forEach((id, count) -> times.put(exact.bindings().get(id), count.longValueExact()));
        return new CraftingPlan(new GenericStack(result.root(), result.requested().longValueExact()),
                (long) Math.ceil(result.legacyBytes()), !result.missing().isEmpty(), result.multiplePaths(),
                counter(result.used()), counter(result.emitted()), counter(result.missing()), Map.copyOf(times));
    }

    private static KeyCounter counter(Map<AEKey, BigInteger> values) {
        var result = new KeyCounter();
        values.forEach((key, count) -> result.add(key, count.longValueExact()));
        return result;
    }

    private static BigInteger bytes(ExactBranchVM.Result<AEKey> result) {
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

    static final class Changed extends RuntimeException {
        Changed(String detail) { super(detail); }
    }
}
