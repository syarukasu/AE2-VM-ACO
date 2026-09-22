# ACO Exact VM 0.1.2

Detached LGPL-3.0 library derived from the pinned AE2-VM source listed in
NOTICE.txt. Build this module directly, not the upstream mod root:

```powershell
.\gradlew.bat -p exact-engine test build --no-daemon
```

Java 17. The module has no Minecraft mod bootstrap, interception, mutable world,
AE2 KeyCounter or ICraftingPlan dependency. ACO embeds its game-library JAR.

Input/output recipe coefficients, requested amounts, inventories, demands,
execution counts and missing/used results are BigInteger. Indices and opcodes
remain int. The caller supplies a maximum accepted quantity; exceeding it fails
explicitly instead of clamping. The long convenience constructors preserve source
compatibility and widen exactly. Opcode checkpoints support caller cancellation.

ExactBytecode supports fixed single-output acyclic graphs. ExactBranchBytecode
and ExactBranchVM add experimental ordered candidate trials, co-products,
remainders and proven read-interval repetitions with BigInteger coefficients.
The sibling native-engine module connects the branch interpreter to AE2's
calculation entry and owns pattern acquisition. The redundant ACO graph-to-VM
adapter has been removed (Issue #208). Native integration tests are maintained
in the ACO Forge build; standalone library tests are not gameplay evidence.

ACO provides exact stock and materializes the completed result for its public
BigInteger API. Physical preparation belongs to the consuming execution API.
CPU and physical execution ownership must remain with AE2/add-ons.
VM result arrays are per invocation; immutable bytecode is reusable across orders.
Tests cover empty/partial/sufficient stock, long boundaries, independent wide
coefficients, cancellation, invalid graphs and concurrent bytecode reuse.

Artifacts include the LGPL license, upstream provenance and sources JAR. Publish
the corresponding fork source together with any distributed ACO release.
