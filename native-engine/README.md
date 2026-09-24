# Native Forge VM Integration

This module owns the AE2 1.20.1 crafting-calculation entry. It reads patterns and
input semantics through AE2 on the server thread and runs immutable BigInteger
bytecode calculations on bounded, cancellable worker threads. It does not
depend on ACO graph/capture classes. VmAccounting is the optional exact-stock
and finished-wide-result contract.

The upstream compiler/stack-bytecode core is compiled into this native JAR.
The unsafe one-craft aggregation was replaced inside the VM fork with ordered
input instructions and guarded block replay. This is a semantic extension of
the VM, not a claim that upstream's original aggregation was correct for every
AE2 recipe. The copied rc.4 `ExactBranchVM` evaluator is removed. The upstream
mod bootstrap, blocklist, config and mixins are not included; this module owns
the controlled AE2 entry. ACO supplies stock/result accounting only.

The quantity stack, scratch inventory, operation counts, missing amounts,
returns and rational byte charges use BigInteger. A replay verifies every
inventory comparison over the skipped interval, preserves peak reservation,
and accounts for periodic damaged-tool states. Failed producer trials roll
back their quantities without discarding the observations used for replay
guards. Pattern bytecode is compiled once per captured request scope; no
stock-dependent plan is shared between requests.

## Build and Test

The controlled Forge build is in the sibling ACO rc.4 recovery worktree:

    ../../aco-rc4-vm/forge-1201

Use Java 17 there:

    ./gradlew.bat test runGameTestServer verifyExactVmBundle --no-daemon
    ./gradlew.bat test runGameTestServer -Pae2Variant=uelm --no-daemon

Override `acoExactVmDir` with the path to this fork's `exact-engine` directory
when using a different checkout layout. That module's sibling `native-engine`
is compiled independently of ACO's main sources. ACO compiles against its
output; the native module never compiles against ACO classes.

`bundleVm` embeds the reobfuscated native mod and the tested arithmetic library
with Forge Jar-in-Jar metadata. `verifyExactVmBundle` checks both nested
artifacts, bootstrap manifests, class boundaries and licenses. Do not invoke
the upstream root build/deployment tasks as a substitute.
`vmNativeSourcesJar` creates the corresponding compiler, interpreter, adapter
and resource source archive. Distribute it alongside a prerelease of the bundle.

## Diagnostics

Routine orders do not produce individual INFO or DEBUG messages by default.
Activity emits one INFO summary at most every 60 seconds: started/completed,
missing plans, cancellations, failures, recaptures, slow completions, and
mean/max completion milliseconds within that window. An idle VM has no timer
thread and produces no periodic messages.

Slow means at least five seconds. Running and slow-completion samples are
each globally limited to one per 30 seconds across orders. Failure exceptions
and stack traces remain ERROR and are not rate-limited. To investigate an
individual order, explicitly enable per-order DEBUG traces with the Java
argument `-Dae2vm.diagnostics.verbose=true` (and a logger accepting DEBUG).
Changing the logger to DEBUG alone does not enable per-order traces.

These diagnostics do not alter calculation, cancellation or exact accounting.

## Boundaries

- Original AE2 candidate order, fuzzy input observations, container returns and
  inventory snapshot semantics are compared with actual AE2 calculations.
- Stock movement alone does not invalidate a completed snapshot. Submission
  must reserve against current stock. Pattern/recipe changes are revalidated.
- Exact input coefficients, branch counts, used/missing/emitted quantities and
  requested quantities use BigInteger, without long clipping in the VM.
- The existing AE2 interface still has long fields. Ordinary results are
  narrowed with longValueExact only after eligibility checks. Wide results go
  to the accounting extension, which preserves their exact counts separately.
- Calculation completion is not proof of physical execution or recovery.

## Consumer Boundary

The ACO bridge publishes finished wide quantities independently of physical
preparation. A stocked processing result must not fail calculation because a
consumer only implements a crafting-table executor. Preparation and custody
remain consumer responsibilities; successful calculation/API transfer is not
proof that every external CPU supports wide processing, dynamic inputs or cycles.
The existing ACO receipt executor still has those restrictions. The bridge does
not substitute fake missing materials, synthesize output, or send projected
long counts to a native executor to bypass them.
