package com.ae2vm.addon.vm;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.Map;

/** #236: opt-in bounded diagnostics; no pattern callbacks or planner decisions. */
final class VmBranchProfile {
    static final int TRIAL = 0, REJECTED = 1, BOUNDARY = 2, APPLIED = 3;
    private final Map<IPatternDetails, Entry> entries = new IdentityHashMap<>();
    private long trials;
    private long quotaFailures, probes, probeWork;
    private record Rejection(AEKey key, String reason) { }
    private final Map<Rejection, Long> rejections = new HashMap<>();
    private final Map<AEKey, Long> requests = new HashMap<>();

    void request(AEKey key) {
        if (requests.size() < 4096 || requests.containsKey(key)) requests.merge(key, 1L, Long::sum);
    }

    void quotaFailed() { quotaFailures++; }
    void probed(long work) { probes++; probeWork += work; }

    private static final class Entry {
        final AEKey key;
        final long[] counts = new long[4];
        Entry(AEKey key) { this.key = key; }
    }

    void record(IPatternDetails pattern, AEKey key, int event) {
        if (event == TRIAL) trials++;
        var entry = entries.get(pattern);
        if (entry == null) {
            if (entries.size() >= 4096) return;
            entry = new Entry(key);
            entries.put(pattern, entry);
        }
        entry.counts[event]++;
    }

    void report() {
        if (trials < Long.getLong("ae2vm.branches.minimumTrials", 100000L)) return;
        var logger = System.getLogger("ACO.VmBranchProfile");
        logger.log(System.Logger.Level.INFO, "ACO_VM_QUOTA failures=" + quotaFailures
                + " isolatedProbes=" + probes + " inclusiveProbeWork=" + probeWork);
        requests.entrySet().stream().sorted(Map.Entry.<AEKey, Long>comparingByValue().reversed())
                .limit(12).forEach(e -> logger.log(System.Logger.Level.INFO,
                        "ACO_VM_REQUEST key=" + e.getKey() + " count=" + e.getValue()));
        entries.values().stream().sorted(Comparator.comparingLong((Entry e) -> e.counts[TRIAL]).reversed())
                .limit(12).forEach(e -> logger.log(System.Logger.Level.INFO,
                        "ACO_VM_BRANCH key=" + e.key + " trials=" + e.counts[TRIAL]
                                + " rejected=" + e.counts[REJECTED] + " boundary=" + e.counts[BOUNDARY]
                                + " applied=" + e.counts[APPLIED]));
        rejections.entrySet().stream().sorted(Map.Entry.<Rejection, Long>comparingByValue().reversed())
                .limit(12).forEach(e -> logger.log(System.Logger.Level.INFO,
                        "ACO_VM_REPLAY key=" + e.getKey().key() + " reason=" + e.getKey().reason()
                                + " count=" + e.getValue()));
        rejections.entrySet().stream().filter(e -> e.getKey().reason().startsWith("finite-")
                        || e.getKey().reason().startsWith("projection-"))
                .sorted(Map.Entry.<Rejection, Long>comparingByValue().reversed()).limit(12)
                .forEach(e -> logger.log(System.Logger.Level.INFO,
                        "ACO_VM_FINITE key=" + e.getKey().key() + " reason=" + e.getKey().reason()
                                + " count=" + e.getValue()));
    }

    void rejected(AEKey key, String reason) {
        var rejection = new Rejection(key, reason);
        if (rejections.size() < 4096 || rejections.containsKey(rejection))
            rejections.merge(rejection, 1L, Long::sum);
    }
}
