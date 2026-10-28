package com.ae2vm.addon.vm;

import java.util.LinkedHashMap;
import java.util.Map;

/** #236: bounded pure subtrees; callers must validate catalog and observations before reuse. */
public final class VmRouteCache {
    private final Map<VmInstructionExecution.RouteKey, VmInstructionExecution.Route> entries;
    private int members;

    public VmRouteCache() { entries = new LinkedHashMap<>(); }

    VmInstructionExecution.Route get(VmInstructionExecution.RouteKey key) { return entries.get(key); }

    void put(VmInstructionExecution.RouteKey key, VmInstructionExecution.Route route) {
        if (entries.containsKey(key) || entries.size() >= 256 || members + route.weight() > 65536) return;
        entries.put(key, route);
        members += route.weight();
    }

    public int size() { return entries.size(); }
    public int retainedMembers() { return members; }
}
