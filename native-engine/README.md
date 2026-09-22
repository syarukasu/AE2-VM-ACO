# Native Forge VM Integration

This module owns the AE2 1.20.1 crafting-calculation entry. It reads patterns and
input semantics through AE2 on the server thread and runs immutable BigInteger
bytecode calculations on bounded, cancellable worker threads. It does not
depend on ACO graph/capture classes. VmAccounting is the optional exact-stock
and finished-wide-result contract.

The legacy upstream CraftingVM/bootstrap is not included in this native JAR.
This is new fork integration using exact-engine's ordered bytecode VM, not a
claim that every original upstream VM feature was retained unchanged.

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
