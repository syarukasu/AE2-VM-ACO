# Native Forge VM Integration

ACO compiles and reobfuscates this module against AE2 for Forge 1.20.1 / Java 17. It is bundled inside the ACO distribution, not installed separately.

The server thread captures request stock, candidate order and auditable input rules before scheduling arithmetic on AE2's existing crafting worker pool. Frozen orders need no subsequent live-read interval or tick registration. Open dynamic input domains retain NativeVmCalculation and AE2-managed exclusive read intervals; pure arithmetic does not wait for another tick.

ACO accounting supplies exact stock and BigInteger result bridging. AE2 and external CPUs retain job submission, reservation, energy, physical execution, progress and refunds. No second planner executor or zero-server-cost guarantee is introduced.

Optional profiling is bounded. Detached replay is explicitly armed, read-only and separate from normal planning. Production GUI, industrial performance and wide physical completion require additional runtime acceptance.

Derived from TaoLe-si/AE2-VM. The original authors retain their copyrights; see the repository LICENSE and exact-engine/NOTICE.txt.
