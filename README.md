# AE2-VM-ACO: ACO 2.0.0 Sources

Source fork used by [AE2 Crafting Optimizer](https://github.com/syarukasu/ae2-crafting-optimizer).
Do not install it separately: the required runtime modules are bundled in ACO.

This branch contains the shared BigInteger arithmetic core, Forge native adapter
and exact-count library. It adds prepared whole-order allocation, shared-material
supply proofs, bounded order-local caches, finite-tool returns and diagnostics.
Unknown callbacks retain the guarded path. No item-specific planning exception.

Actual Forge GUI shortage planning for the circuit completed in 5.634 seconds
at `2^63-1` and 7.319 seconds at `10^1024-1`. These are not manufacturing,
fully-funded huge-order, NeoForge runtime or universal latency guarantees.

Build the exact library with Java 17 from `exact-engine` using
`../gradlew jar sourcesJar`. ACO compiles the native adapter for its platform.
NeoForge retains the clean refs.8 host at commit
`5b57a00e283d355a47ee5276ba528b2dcf7d07db` and adapts this shared core.

Tests, local reports, worlds, dependency JARs and generated outputs are excluded.
Derived from [TaoLe-si/AE2-VM](https://github.com/TaoLe-si/AE2-VM); the original
license and third-party attribution are retained in LICENSE and module notices.
