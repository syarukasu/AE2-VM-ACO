# Native Forge VM Integration

This module replaces the AE2 1.20.1 calculation engine with the BigInteger VM.
AE2 retains its calculation thread pool and TickHandler registration. Pure
arithmetic runs continuously, without waiting for another tick. The simulateFor
hook grants exclusive intervals only when live world access is pending; it
returns immediately when the worker is only computing. This is not a second
executor or a zero-server-cost claim.

## Request Path

1. Create the job on the server thread and capture accessible exact stock once.
   Off-thread API callers enqueue creation once. Cancellation before creation is latched.
2. Schedule NativeVmCalculation on AE2's own CRAFTING_POOL.
3. NativeVmPlanningContext obtains live provider order and actual input
   predicates/returns inside exclusive intervals managed by NativeVmAccess.
   Consecutive callbacks reuse the current interval. At expiry, checkpoints
   release the server and continue arithmetic; only a subsequent live callback
   waits for another grant. Nested callbacks cannot release the outer callback.
   Compile demanded definitions once in the request-local scope.
4. Return an ordinary AE2 plan or the VmAccounting BigInteger result.
   The base calculation's placeholder long quantity is never used for arithmetic.
5. AE2/AQE still owns submission, live reservation, physical execution and refunds.

NativeVmCapture and NativeVmPatternCatalog are not shipped. There are no
ingredient server futures, full-grid prefetch, observation mirrors, whole-plan
adoption checks, recapture/backoff loops or second calculation worker pool.
Historical detached-capture regression fixtures exist only in the ACO test tree.
They are not production fallbacks or evidence of the current integration.

The quantity stack, scratch stock, execution counts, missing/used/emitted items,
returns and byte charges use BigInteger. Shared exact demands use compiled
programs; alternatives and stateful branches retain ordered VM instructions.
The existing exact arithmetic/replay guards preserve quantities, not a timeout
or long-clipping policy. No stock-dependent plan is shared between orders.
Stock provenance, interruption and real CPU submission checks remain.

## Build and Verification

Build from the sibling ACO worktree with Java 17:

    ../../aco-rc4-vm/forge-1201
    ./gradlew.bat test build runGameTestServer verifyExactVmBundle --no-daemon
    ./gradlew.bat test build runGameTestServer verifyExactVmBundle -Pae2Variant=uelm --no-daemon

The quantity oracle harness tests the current production context against AE2.
Forge GameTests exercise the mixed-in native lifecycle, direct callbacks while
the server waits, cancellation, concurrent orders, stock changes and wide plans.
The continuous-arithmetic probe performs 3402-bit arithmetic for longer than a
normal time slice while the test server deliberately does not advance ticks.
NativeVmAccessTest checks the monitor separately with a controlled clock.
The bundle audit rejects retired capture/catalog classes and verifies nested
JAR bytes, bootstraps, class ownership and licenses. vmNativeSourcesJar supplies
the corresponding sources. Do not use upstream root deployment tasks.

## Diagnostics and Limits

Default logging is bounded: activity summaries at most once per minute and
slow/running details at most once per 30 seconds; failures remain explicit.
Per-order DEBUG traces require -Dae2vm.diagnostics.verbose=true.
The last 64 outcomes are available through /aco vm recent [1..64].
scheduler=ae2-boundary and handoffs=0 identify this lifecycle; vmMs includes waits,
vmActiveMs=-1 avoids presenting waiting time as active work, and vmCpuMs is
measured on the worker using the JVM thread CPU timer. These are calculation
outcomes, not evidence of physical craft completion.
ae2WorldWaitMs records waiting for exclusive access; ae2ExclusiveMs records
the server's occupied intervals, with ae2Intervals and ae2Reads counting grants
and callbacks. A grant may include short arithmetic between live callbacks to
avoid a new tick wait per ingredient. A single opaque mod callback cannot be
preempted; the configured interval is checked again when that callback returns.

Pattern exploration follows AE2's current requester grid; already explored
nodes are not invalidated and restarted. Stock is a snapshot, not a reservation.
Ordinary results use longValueExact only after eligibility checks. Wide values
remain exact in the accounting API; the legacy long interface is only a facade.

Production wall-clock speed, the final creative-circuit <10-second target,
generic wide machine/cycle execution and restart recovery are not established
by the local calculation tests. Alternate-only container returns retain the
existing AE2-compatible limitation; this lifecycle change does not repair that
separate accounting case or claim complete industrial execution.
