# AE2-VM-ACO

ACO-specific LGPL-3.0 derivative of [TaoLe-si/AE2-VM](https://github.com/TaoLe-si/AE2-VM), based on upstream commit `f9e083732ee654a99a1679bcdee7f864eb90be94`. Original authors retain their copyrights and credits; see LICENSE and exact-engine/NOTICE.txt.

This branch contains only the VM source used by the Forge ACO beta. It is not a standalone replacement for upstream AE2-VM. Do not install another copy of the bundled VM modules beside ACO.

- `exact-engine`: pure BigInteger bytecode library, without a Minecraft mod bootstrap.
- `native-engine`: native AE2 integration, compiled and reobfuscated by ACO's build.
- `src/main/java`: shared compiler and VM arithmetic used by native-engine.

Java 17 is required. Build the pure library with `gradlew -p exact-engine jar sourcesJar`. To build the installable mod, use the matching ACO source branch and pass `-PacoExactVmDir=/path/to/this/checkout/exact-engine`.

AE2 owns its calculation worker pool. Request initialization captures stock, candidate order and auditable input rules on the server thread. Frozen arithmetic runs on AE2 workers; unsupported live predicates use AE2-managed exclusive read intervals. Physical execution, power, reservation and refunds remain owned by AE2 or the external CPU.

This beta includes optional bounded profiling, explicitly armed detached replay, and exact rational arithmetic fast paths. Future-version dependency admission is not a binary-compatibility guarantee. Live wide crafting, refund/restart recovery and industrial TPS acceptance are not established by this source release.
