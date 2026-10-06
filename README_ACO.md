# ACO VM fork snapshot

This is a modified LGPL-3.0 AE2-VM fork used by ACO. Upstream attribution and
license notices are retained. The active Forge adapter is `native-engine`;
the selected compiler and VM files remain under `src/main/java`.

## Build

Build the installable mod from the matching
[ACO source](https://github.com/syarukasu/ae2-crafting-optimizer), supplying
`-PacoExactVmDir=/absolute/path/to/AE2-VM-ACO/exact-engine` to ACO's Gradle wrapper.
ACO's snapshot CI pins this repository to an exact commit. It compiles selected
VM sources against the platform's AE2 API and packages two nested modules:
`aco-exact-vm` 0.1.6 and Forge `aco-vm-native` 0.3.12-vm-refs.8.

Do not use the historical upstream root Gradle build or deployment tasks for ACO.
Do not install the upstream standalone VM alongside the ACO bundled VM.
The graph/SMT code is a comparison fixture, not a shipped fallback calculator.

## Current scope

- Exact BigInteger VM quantity arithmetic and order-local stock/input observations.
- Shared demand compilation, returned-input accounting and funded producer trials.
- Requester CRAFT_LESS quantity bounds for eligible fixed graphs; complex cases
  retain ordered VM search. No new requester retries or provider speed limits.
- AE2 owns normal job submission, CPU execution and physical transfers. The
  arithmetic result does not generate items or prove physical completion.

Tests for the platform adapter and independent quantity/capacity oracles are in
ACO's source tree. The exact library also has its own `exact-engine` tests.
Runtime jars exclude graph fixtures and SMTInterpol. Matching runtime source
archives are produced by ACO's `vmBundleSources` task.

This remains an Alpha snapshot. Production false-shortage revalidation, general
wide execution, cancellation/refunds, restart recovery, concurrent orders and
the final industrial sub-10-second target are not accepted as complete.
