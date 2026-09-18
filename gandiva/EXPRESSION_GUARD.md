# Expression Guard — change summary

## Problem

A Dremio query plan containing an `UNPIVOT` produced an `IS NOT NULL(CASE WHEN key='lit_0' THEN v0 WHEN key='lit_1' THEN v1 … WHEN key='lit_392' THEN v392 ELSE NULL END)` expression with 393 arms. When the plan was passed to Gandiva, the executor thread died with a native `SIGSEGV` (exit 139). The customer hs_err showed:

- `Filter.evaluate` → `JniWrapper.evaluateFilter` (i.e. runtime, not compile-time)
- `RSP` ~1 MB **below** the executor thread's 1028 KB stack base
- `SEGV_ACCERR` on the guard page exactly at the stack base
- Instructions at the faulting PC: a function *prologue* spilling registers into `[rsp+0x6e??]`/`[rsp+0x6f??]`/… slots

i.e. one Gandiva-emitted function tried to reserve a stack frame larger than the entire executor thread's stack, and the prologue walked off the bottom on first call.

## Root cause

Dremio's planner has a rewrite rule that turns the compact `IS NOT NULL(CASE …)` form into:

```
OR(
  AND(c0, isnotnull(v0)),
  AND(NOT(c0), c1, isnotnull(v1)),
  AND(NOT(c0), NOT(c1), c2, isnotnull(v2)),
  …
  AND(NOT(c0)..NOT(c_{N-2}), c_{N-1}, isnotnull(v_{N-1}))
)
```

For N=393 that materializes **77,421 `equal` comparisons** in one ~4.5 MB expression. Gandiva compiles the whole thing into a single LLVM function; the optimizer can't deduplicate the redundant `not(equal)` subterms across AND arms, so thousands of SSA values get spill slots in the function's stack frame. The frame size exceeds available thread stack and the prologue faults.

The crash class has **two reachable failure modes**:

1. **Compile-time native recursion** — a deeply nested AST (e.g. nested `IfNode` chain) causes `ExprDecomposer::Visit(IfNode&)` and `LLVMGenerator::Visitor::Visit(IfDex&)` to recurse once per level. Each frame holds several `shared_ptr`s and lambda captures (~3 KB). Around depth ~360 on a 1 MB stack the recursion blows the stack during `Filter.make` / `Projector.make`.
2. **Runtime giant JIT frame** — a wide flat tree (the customer's post-rewrite OR-of-ANDs) compiles successfully but produces a JIT'd function whose single stack frame is larger than the calling thread's stack budget. Crashes on first call to `Filter.evaluate` / `Projector.evaluate`.

Either mode kills the JVM in native code with no chance for Java to catch it.

## Solution

Reject pathological expression trees on the Java side, *before* the JNI call. The check is iterative (uses an explicit `ArrayDeque` work-stack) so the guard itself cannot stack-overflow on the same input it's trying to protect against.

### Limits

| | Default | System property override |
|---|---|---|
| Max AST depth | 100 | `org.apache.arrow.gandiva.expr.max_depth` |
| Max total node count | 10 000 | `org.apache.arrow.gandiva.expr.max_nodes` |

**Why these numbers:**

- **Depth 100** is well below the compile-time-recursion cliff and far above any legitimate hand-written CASE/IF depth (single digits to low tens).
- **Node count 10 000** covers the wide-flat shape. The customer's failing input (393-arm OR-of-ANDs → ~77 421 nodes) is rejected with ~8× margin while ordinary expressions are unaffected.

The defaults can be raised at deploy time without rebuilding.

## Changes

### New files

- **`gandiva/src/main/java/org/apache/arrow/gandiva/evaluator/ExpressionGuard.java`** — package-private iterative walker over `GandivaTypes.TreeNode` protobuf. Handles `IfNode`, `AndNode`, `OrNode`, `FunctionNode`, `InNode`; leaves (fields, literals) terminate naturally. Each step increments a counter and checks against the limits. The `ExpressionList` overload validates each expression independently because Gandiva's `LLVMGenerator::Add` compiles each Projector expression into its own LLVM function (`expr_<idx>_<mode>`), so the per-function spill-slot budget — which the node-count limit defends — applies per expression, not in aggregate.
- **`gandiva/src/test/java/org/apache/arrow/gandiva/evaluator/ExpressionGuardTest.java`** — 11 focused tests:
  - `filterRejectsExpressionAboveDepthLimit`, `projectorRejectsExpressionAboveDepthLimit`, `filterRejectsExpressionAboveNodeCountLimit`, `projectorRejectsExpressionAboveNodeCountLimit` — confirm the `Filter.make` / `Projector.make` wiring rejects oversized expressions.
  - `smallExpressionPassesGuard` — sanity that ordinary expressions are unaffected.
  - **Boundary**: `guardPassesAtDepthOneBelowLimit`, `guardPassesAtDepthExactlyAtLimit`, `guardRejectsAtDepthOneAboveLimit`, `guardPassesAtNodeCountExactlyAtLimit`, `guardRejectsAtNodeCountOneAboveLimit` — pin the off-by-one for both limits. These call `ExpressionGuard.check` directly on hand-built protobuf trees rather than going through `Filter.make`, because compiling a 10 000-arg `AND` natively takes ~9 minutes; the wiring is already covered by the four `*Rejects*` tests above.
  - `depthLimitHonoursSystemPropertyOverride` — verifies the `org.apache.arrow.gandiva.expr.max_depth` override accepts an otherwise-rejected tree, and restores the previous property value in `finally` so the test is hermetic.
- **`gandiva/src/test/java/org/apache/arrow/gandiva/evaluator/UnpivotCaseStackOverflowTest.java`** — regression tests for the original customer plan shape (393 nested `IfNode`s) as both a Projector and Filter:
  - `testUnpivotCaseStackOverflow` / `testUnpivotCaseStackOverflowFilter` — assert `GandivaException` with a depth-limit message instead of a JVM crash.
  - `testUnpivotCaseShallow` / `testUnpivotCaseShallowFilter` — shallow (depth-8) versions confirming legitimate CASE expressions still compile and evaluate.
- **`gandiva/src/test/java/org/apache/arrow/gandiva/evaluator/CustomerUnpivotFilterReproTest.java`** + **`gandiva/src/test/resources/unpivot_393_literals.txt`** — faithful reproduction of the customer's actual failing Filter expression, rebuilt in Java from the literal strings extracted from their planner output:
  - `customerFilterIsRejectedByGuard` — always runs; asserts the guard rejects the 393-arm OR-of-ANDs (77 421 nodes) cleanly with a `node-count` message.
  - `customerFilterRunsAgainstGandivaIfGuardDisabled` — opt-in via `-Dgandiva.repro.runUnguarded=1`; raises the guard limits and submits the expression to Gandiva. Used for evidence-gathering on the runtime crash mode; see *Evidence and gaps* below.

### Modified files

- **`gandiva/src/main/java/org/apache/arrow/gandiva/evaluator/Filter.java`** — calls `ExpressionGuard.check(conditionBuf)` in the synchronized `make(Schema, Condition, long)` overload, just after `condition.toProtobuf()` and before the JNI call. Every public `make()` overload funnels through this one, so the guard applies uniformly.
- **`gandiva/src/main/java/org/apache/arrow/gandiva/evaluator/Projector.java`** — same pattern, with `ExpressionGuard.check(exprList)` over the built `ExpressionList`.

## Tests

- **Full gandiva suite: 108 tests, 0 failures, 0 errors** (verified with `mvn -Parrow-jni -pl gandiva test`). The opt-in `customerFilterRunsAgainstGandivaIfGuardDisabled` probe is not counted; it runs only when `-Dgandiva.repro.runUnguarded=1` is set.
- **11 new guard tests, all green** — wiring (×4), sanity, boundary (×5), and system-property override.
- **4 UNPIVOT regression tests, all green** — the deep variants assert the guard exception, the shallow variants assert normal compilation.
- **1 customer-filter regression test, green** — asserts the guard rejects the faithful reproduction of the customer's 393-arm OR-of-ANDs.

## Evidence and gaps

Both crash modes named in *Root cause* are now reproducible on local hardware. Status:

| Crash mode | Locally reproducible? | Regression-tested? | Evidence |
|---|---|---|---|
| **Compile-time native recursion** (`Filter.make` / `Projector.make`, depth-360 cliff at `-Xss1m`) | **Yes** — multiple times | Yes — `testUnpivotCaseStackOverflow*` and depth-boundary tests in `ExpressionGuardTest` | Direct: forked surefire JVM dies with SIGSEGV exit 139 |
| **Runtime JIT-frame overflow** (`Filter.evaluate`, single function's prologue allocates frame larger than the executor thread's stack) | **Yes** — under `-Dgandiva.repro.optimize=false` | Available as opt-in (`-Dgandiva.repro.runUnguarded=1 -Dgandiva.repro.optimize=false`); not part of the default suite because it crashes the JVM | Local repro at N=393 matches the customer hs_err pattern exactly (see below) |

### Reproducing the runtime crash

The runtime crash is sensitive to LLVM's optimisation level. With `withOptimize(true)` (the arrow-java default), LLVM's CSE pass deduplicates the redundant `<>(key, lit_j)` comparisons across the 393 AND arms, the resulting JIT'd function has a small frame, and `Filter.evaluate` runs cleanly — but the optimiser itself runs for > 30 minutes on the cumulative-NOT IR.

With `withOptimize(false)`:
- `Filter.make` of the full 393-arm expression completes in **~13 seconds** (the optimiser passes are skipped).
- The resulting JIT'd function preserves all the redundant comparisons as distinct live SSA values.
- First call to `Filter.evaluate` crashes with `SIGSEGV` from a single function's prologue trying to reserve a stack frame ~1 MB in size on a 1 MB thread stack.

Diagnostic from this box's hs_err (`gandiva/hs_err_pid436342.log`), versus the customer's hs_err:

| | This box, `optimize=false` | Customer, Dremio Enterprise 26.1.6 |
|---|---|---|
| Crash type | `SIGSEGV` in C native code | `SIGSEGV` (`SEGV_ACCERR`) in C native code |
| Executor thread stack | `(1024K)` | `(1028K)` |
| `RSP` below stack base by | ~1.04 MB | ~1.03 MB |
| `free space=…k` wraparound | `18014398509480918k` | `18014398509480932k` |
| Last Java frame | `JniWrapper.evaluateFilter` | `JniWrapper.evaluateFilter` |
| Below that | `Filter.evaluate` → test | `Filter.evaluate` → `NativeFilter.filterBatch` → `SplitStageExecutor` → ... |

The stack-pointer-below-base + the "free space" wraparound + the function-prologue-walked-off-the-bottom pattern is identical. The customer's environment evidently has weaker CSE in its LLVM build (older LLVM in their Dremio package), so even with `withOptimize(true)` they ended up in the same situation we reach by explicitly disabling optimisation here.

### What we tried before finding the right knob

For the record, on Java 21 + recent libstdc++/LLVM, `-Xss` from 256 K up to 8 M, `optimize=true`, every shape we tried either succeeded or hit a different limit:

- Wide Projector with up to 4 000 output expressions: succeeded
- Deep `concat` chain: hit the protobuf parser's own depth limit at ~500
- Wide `OR(like(s, p_0), …, like(s, p_N))` up to N=2 000: succeeded
- The deep nested-`IfNode` shape: in every case, `Filter.make` / `Projector.make` blew the stack from compile-time recursion before `evaluate` could run
- The faithful customer filter (`customerFilterRunsAgainstGandivaIfGuardDisabled`) at N=100/150: compiled + evaluated cleanly; N=200/393: compile didn't complete in 10/30 min respectively (the >O(N²) compile cost from LLVM's optimiser)

Switching that final probe to `optimize=false` is what closed the gap.

### Bottom line

- The `max_depth=100` half of the guard is sized against a **measured cliff** that we can reproduce on demand.
- The `max_nodes=10000` half of the guard is sized against a **measured cliff** that we can also now reproduce on demand — the cliff is real, the customer's failure was not an exotic environmental quirk, and the guard prevents both compile-time and runtime variants from killing the JVM in native code.

The runtime-mode repro is opt-in (`-Dgandiva.repro.runUnguarded=1 -Dgandiva.repro.optimize=false -Dgandiva.repro.n=393`) because it intentionally crashes the test JVM and would derail other tests in the same fork. It is, however, the missing piece of evidence the previous version of this doc said was outstanding.

## What this change does NOT do

- It does not eliminate the underlying native vulnerabilities. The AST visitors in Gandiva (`ExprDecomposer::Visit`, `LLVMGenerator::Visitor::Visit`) are still recursive; LLVM's own passes still walk def-use chains recursively; the JIT'd function can still produce a huge frame given the right input shape. The guard only ensures that *Java-submitted* expressions can't reach those vulnerabilities through the public API.
- It does not make the customer's UNPIVOT query succeed. With the guard in place, the same plan now produces a clear `GandivaException` instead of a JVM crash. The real fix needs to happen upstream — either skip the planner rewrite for large N or replace the rewrite with one that doesn't materialize O(N²) redundant comparisons.

## Suggested follow-ups (outside this change)

- **Planner-side:** identify the Calcite/Dremio rule that turns `IS NOT NULL(CASE …)` into OR-of-ANDs and guard it on arm count, or replace it with a lowering that shares prefix tests rather than duplicating them. (Now traced: commit `c69df2c962b` / DX-103987 on HEAD includes a `stripNullabilityOnlyCasts` mitigation that collapses the customer's shape on the current branch — see `DREMIO_UNPIVOT_PLANNER_BUG.md`.)
- **Dremio splitter — close the filter complexity-check bypass.** `SplitStageExecutor.setupFilter()` calls `NativeFilter.build()` directly, skipping the `PROJECTION_COMPLEXITY_GANDIVA_LIMIT` check that `setupSplit()` (project path) already runs. Detail in the next section.
- **Gandiva C++:** rewrite `ExprDecomposer::Visit(IfNode&)` and `LLVMGenerator::Visitor::Visit(IfDex&)` iteratively, and special-case long if-chains in `LLVMGenerator` to a single basic-block compare-cascade-with-φ instead of cascading `BuildIfElse` calls. Detail two sections below.

## The Dremio splitter "Gap 2" fix in detail

Investigation of the customer's failure path turned up a structural gap in Dremio's expression splitter: **the per-split complexity check that gates the project path is bypassed on the filter path**. This is referred to as "Gap 2" in the [splitter probe plan](/home/logan.riggs/.claude/plans/can-you-answer-these-cuddly-gizmo.md). Closing it doesn't remove the need for the arrow-java guard, but it provides a Dremio-domain error for the largest oversized expressions and a backup layer if arrow-java is missing/bypassed.

### What's there today

[`SplitStageExecutor.setupSplit()`](dremio/oss/sabot/kernel/src/main/java/com/dremio/exec/expr/SplitStageExecutor.java#L154-L245) — the **project** path. Lines 171–218 are the per-split complexity check:

```java
double work = split.getWork();
boolean workCheckEnabled = context.getOptions().getOption(PROJECTION_COMPLEXITY_ENABLE_LIMIT);

if (gandivaCodeGen) {
  long workGandivaError = context.getOptions().getOption(PROJECTION_COMPLEXITY_GANDIVA_LIMIT);
  if (workCheckEnabled && work > workGandivaError) {
    logger.error(...);
    throw new ExpressionValidationException(
        "Projection complexity/work estimate above limit for Gandiva compilation, aborting.");
  }
  ...
}
// (parallel Java check below for non-Gandiva splits)
```

[`SplitStageExecutor.setupFilter()`](dremio/oss/sabot/kernel/src/main/java/com/dremio/exec/expr/SplitStageExecutor.java#L313-L372) — the **filter** path. Lines 343–354:

```java
if (finalSplit.getExecutionEngine() == SupportedEngines.Engine.GANDIVA) {
  gandivaCodeGenWatch.start();
  nativeFilter = NativeFilter.build(
      finalSplit.getNamedExpression().getExpr(),
      ...
  );          // ← goes straight to native compilation
  ...
  return;
}
```

No `setupSplit()` call, no complexity check, no early bailout. The filter's final split is handed to `NativeFilter.build()` as-is, regardless of how big it is.

### The change

Extract the existing check from `setupSplit` into a helper, call it from both code paths:

```java
/**
 * Per-split complexity guard. Throws ExpressionValidationException when the split's work
 * estimate exceeds the configured Gandiva or Java limit, depending on the engine the split is
 * routed to. Used by both setupSplit (project path) and setupFilter (filter path) so the two
 * paths apply the same ceiling.
 */
private void checkSplitComplexity(ExpressionSplit split, boolean toGandiva)
    throws ExpressionValidationException {
  if (!context.getOptions().getOption(ExecConstants.PROJECTION_COMPLEXITY_ENABLE_LIMIT)) {
    return;
  }
  double work = split.getWork();
  long warnLimit = context.getOptions().getOption(
      toGandiva
          ? ExecConstants.PROJECTION_COMPLEXITY_GANDIVA_WARN_THRESHOLD
          : ExecConstants.PROJECTION_COMPLEXITY_JAVA_WARN_THRESHOLD);
  long errorLimit = context.getOptions().getOption(
      toGandiva
          ? ExecConstants.PROJECTION_COMPLEXITY_GANDIVA_LIMIT
          : ExecConstants.PROJECTION_COMPLEXITY_JAVA_LIMIT);
  String engine = toGandiva ? "Gandiva" : "Java";
  if (work > errorLimit) {
    logger.error(
        "Expression complexity/work estimate above limit for {} compilation, aborting. Work: {}, Limit: {}",
        engine, work, errorLimit);
    throw new ExpressionValidationException(
        "Expression complexity/work estimate above limit for " + engine + " compilation, aborting.");
  } else if (work > warnLimit) {
    logger.warn(
        "Expression complexity/work estimate is high for {} compilation. Work: {}, Warn Threshold: {}",
        engine, work, warnLimit);
  }
}
```

Then in `setupSplit` (line ~171), replace the inline check with `checkSplitComplexity(split, gandivaCodeGen);`. And in `setupFilter` (after line 341 where `finalSplit` is determined, before the engine branch at line 343), add:

```java
checkSplitComplexity(
    finalSplit,
    finalSplit.getExecutionEngine() == SupportedEngines.Engine.GANDIVA);
```

That's it. One extracted helper (~25 lines), one call site refactored in `setupSplit`, one new call site in `setupFilter`.

### What it changes for the customer's failure path

Before:
```
SQL → planner → ExpressionSplitter → SplitStageExecutor.setupFilter()
                                     → NativeFilter.build()
                                     → Gandiva JIT
                                     → JVM SIGSEGV at evaluate()
```

After (on 26.1.6 without the HEAD strip fix):
```
SQL → planner → ExpressionSplitter → SplitStageExecutor.setupFilter()
                                     → checkSplitComplexity()   ← filter goes through the gate
                                     → throws ExpressionValidationException
                                     → query fails with clear error, JVM survives
```

### How Gap 2 and the arrow-java guard relate

Gap 2 closes the *bypass*, but the gate it sends filters through is much looser than the arrow-java guard. The arrow-java guard is still doing most of the work.

| Layer | Where it lives | Limit | What it sees | Coverage |
|---|---|---|---|---|
| **Dremio `PROJECTION_COMPLEXITY_GANDIVA_LIMIT`** | `setupSplit` (and `setupFilter` after Gap 2 fix) | 150 000 work units | Per-split work estimate | Catches only the **largest** expressions |
| **arrow-java `max_nodes`** | `ExpressionGuard` in `Filter.make` / `Projector.make` | 10 000 nodes | Protobuf node count | Catches everything ~7× more conservatively |

Translating to comparable units (with a leaves-counted work estimator, roughly 1 work unit ≈ 0.75 nodes for the customer's shape):

- **Dremio rejects** at work > 150 000 ≈ **nodes > ~110 000–200 000**
- **arrow-java rejects** at **nodes > 10 000**

The gap is huge. Anything between 10 000 nodes and ~110 000 nodes flies past Dremio's check and lands at arrow-java's check.

### Concrete walkthrough — three example shapes with Gap 2 fix applied

**Shape A — customer's exact filter (~78 K nodes / ~155 K work old estimator / ~310 K work new estimator):**
- `setupFilter` calls `checkSplitComplexity` → work > 150 000 → throws `ExpressionValidationException`
- Never reaches `NativeFilter.build`, never reaches arrow-java guard
- ✅ Dremio catches it

**Shape B — same shape scaled to N=120 (~14 K nodes / ~14 K work old / ~30 K work new):**
- `setupFilter` calls `checkSplitComplexity` → work = 30 000 < 150 000 → passes
- Reaches `NativeFilter.build` → calls `Filter.make` in arrow-java
- arrow-java `ExpressionGuard` runs → 14 000 nodes > 10 000 → throws `GandivaException`
- ✅ arrow-java catches it. Dremio missed it.

**Shape C — small N=20 (~860 nodes / ~420 work):**
- Dremio passes
- arrow-java passes
- Gandiva compiles + evaluates normally
- ✅ Correct — small expression, no issue

So Gap 2 only changes the outcome for the *biggest* expressions (where work > 150 000). Anything moderately oversized still falls to arrow-java.

### Why bother with Gap 2 if arrow-java already covers it

1. **Error UX.** When Dremio's check fires, the user sees `ExpressionValidationException: Expression complexity/work estimate above limit for Gandiva compilation, aborting.` — a Dremio-domain error with a recognised category. When arrow-java's guard fires, they see `GandivaException: Gandiva expression exceeds node-count limit: > 10000 nodes (override with -Dorg.apache.arrow.gandiva.expr.max_nodes=N)` — opaque arrow-java internals leaking out. The Dremio-side error is what support is going to grep for.
2. **Defence in depth.** A deployment that pins to an arrow-java jar without the guard has zero protection on the filter path today. Closing Gap 2 means the executor JVM survives even on un-patched arrow-java. The two layers serve different threat models.
3. **Splitter feedback loop.** Once `setupFilter` actually rejects oversized expressions, you have telemetry showing how often it fires. Today filters always pass through silently — there's no signal that anything dangerous is happening.

### Caveats worth flagging

1. **No tests today** assert that filters trigger the limit. Any change here needs a new unit test in `SplitStageExecutorTest` (or whichever fixture covers filter setup) that submits an oversized filter expression and asserts the exception.
2. **The error message wording changes.** Today's message says "Projection complexity…"; the helper's wording in the snippet above says "Expression complexity…". If anyone is grepping logs for the old text, that's a breakage. Easiest fix: keep two literal strings ("Projection complexity…" for projects, "Filter complexity…" for filters) rather than the generic "Expression complexity…".
3. **Customer error UX shift.** Today an oversized expression in a filter produces a JVM crash. With this fix it produces a `UserException` wrapping `ExpressionValidationException`. That's a strict improvement, but if customers are tolerating the existing project-path error and only complaining about the JVM crashes, they'll start seeing the same error on filters that used to "work" (by crashing).

### If you want Dremio to do all the work and remove the arrow-java fallback

Lower `PROJECTION_COMPLEXITY_GANDIVA_LIMIT` from 150 000 to something like 15 000–20 000 (matching the arrow-java guard's node budget). Then Dremio rejects at roughly the same point arrow-java would, and arrow-java becomes the redundancy.

This is a behavioural change — some queries that work today would start failing — so it's a policy call separate from the structural fix in Gap 2.

**Summary:** Gap 2 stops the JVM-crash failure mode on the filter path but doesn't displace arrow-java. arrow-java remains the load-bearing guard at default settings; Gap 2 lets Dremio produce nicer errors on the *largest* expressions and provides a backup if arrow-java's guard is bypassed or absent.

## The two Gandiva C++ fixes in detail

The guard is a backstop. The actual fixes that would let us raise or remove the limits live in arrow-cpp/gandiva. Two of them:

### Fix #1 — Make Gandiva's AST walkers iterative

#### The problem

When the Java side calls `Filter.make(schema, condition)`, control jumps into native C++. Gandiva has to take the expression tree (`IfNode → IfNode → IfNode → …`) and turn it into LLVM machine code. Today it does that with two passes:

- `ExprDecomposer::Visit(IfNode&)` ([expr_decomposer.cc:126](https://github.com/apache/arrow/blob/main/cpp/src/gandiva/expr_decomposer.cc)) — walks the AST once to break expressions into "value + validity" pairs
- `LLVMGenerator::Visitor::Visit(IfDex&)` ([llvm_generator.cc:886](https://github.com/apache/arrow/blob/main/cpp/src/gandiva/llvm_generator.cc)) — walks the decomposed result a second time to emit LLVM IR

Both walks **recurse** — a function that handles one level calls itself to handle the children. Look at the decomposer:

```cpp
Status ExprDecomposer::Visit(const IfNode& node) {
  status = node.condition()->Accept(*this);   // ← recurse on cond
  status = node.then_node()->Accept(*this);   // ← recurse on then
  status = node.else_node()->Accept(*this);   // ← recurse on else
  result_ = std::make_shared<ValueValidityPair>(...);
  return Status::OK();
}
```

The `else` branch of one `IfNode` is *another `IfNode`* in a long CASE chain. So that third recursive call goes one level deeper, then its `else` is another `IfNode`, then *its* `else`, … for 393 arms you get 393 nested C++ function calls stacked on top of each other.

#### Why it crashes

Every function call needs a **stack frame** — a chunk of the thread's native stack that holds the function's local variables, the return address, and the captured `shared_ptr`s. We measured each `Visit(IfNode&)` frame at roughly 3 KB. Multiply by 360 and you've used 1 MB, which is the default `-Xss` on Linux x86_64.

When the function tries to push frame #361, the stack pointer walks off the bottom of the thread's allocated stack into a "guard page." The kernel detects this with a `SIGSEGV`, the JVM has no chance to convert it to a `StackOverflowError`, the process dies.

This is **compile-time only** — it happens during `Filter.make`/`Projector.make`, before any data is touched. The arrow-java guard with `max_depth=100` prevents it by refusing to send anything past depth 100 into native code.

#### What the fix changes

Replace the C++ recursion with an explicit "work stack" stored on the heap. Same algorithm, but the calls don't pile up:

```cpp
Status ExprDecomposer::Decompose(const Node& root, ValueValidityPair* out) {
  std::deque<Frame> work;             // explicit stack on the HEAP
  work.push_back({root, PHASE_VISIT_COND});
  while (!work.empty()) {
    Frame f = work.back();
    work.pop_back();
    switch (f.phase) {
      case PHASE_VISIT_COND:  ... ;
      case PHASE_VISIT_THEN:  ... ;
      case PHASE_VISIT_ELSE:  work.push_back({f.node.else_node(), ...}); break;
      case PHASE_FINISH:      ... build value_dex and validity_dex ... ;
    }
  }
}
```

The heap can hold millions of work entries because the heap is huge (gigabytes). The thread's native stack is only ~1 MB. Moving the work off the stack means depth is no longer constrained by `-Xss`.

#### Why this matters even with the arrow-java guard

The guard caps depth at 100. That's conservative — most real queries are shallow. But it means *every* legitimate query with > 100 nested ifs gets rejected too. Fixing the recursion lets us raise the limit (or remove it for this specific cliff entirely) so a future "let's CASE-out 500 possible status values" query just works.

---

### Fix #2 — Bound the size of the JIT-emitted function's stack frame

#### The problem

Now suppose `Filter.make` *did* succeed (small enough tree, or Fix #1 landed). Gandiva has handed LLVM a description of the work, LLVM has compiled it into machine code, and Dremio is ready to run the filter on a batch of rows.

The compiled function has its own stack frame. That frame's size was fixed at compile time. When the function is called, its first instruction is:

```
sub rsp, FRAME_SIZE      ; reserve FRAME_SIZE bytes on the stack
```

If `FRAME_SIZE` is larger than the calling thread's available stack, that `sub rsp` instruction walks the stack pointer off the bottom — exactly like Fix #1's recursion crash, but from a *single function* allocating its locals all at once, not from many functions piling up.

The customer's hs_err shows this precisely: `RSP` ended up ~1 MB below the stack base, and the very first instructions at the faulting PC were the prologue storing registers into `[rsp+0x6e??]`, `[rsp+0x6f??]` slots (offsets up around 28 KB into a frame that turned out to be ~1 MB).

#### Why the frame got that big

For each varchar comparison in the expression, LLVM needs to track three values: the pointer to the string, its length, and a validity bit. These are called **SSA values** — each one is a distinct register-or-memory slot that holds an intermediate result.

LLVM tries to keep values in CPU registers (fast), but x86-64 has only 16 general-purpose registers. When more values need to be "live" simultaneously than fit in registers, the extra ones get **spilled** to the stack — written into a slot in the function's frame.

A 393-arm CASE has hundreds of varchar comparisons whose results need to be combined to produce the final answer. They're all live at the same time during the combining. LLVM allocates a spill slot for each one. The frame size grows linearly with the number of simultaneously-live values.

In the customer's plan, the `BuildIfElse` helper ([llvm_generator.cc:1188](https://github.com/apache/arrow/blob/main/cpp/src/gandiva/llvm_generator.cc)) emits **three** basic blocks per `IfDex`: a "then" block, an "else" block, and a "merge" block joined by a `PHINode`. A 393-arm CASE produces ~1 200 basic blocks chained through 393-deep φ-node use-def edges. Each varchar intermediate is live across many of those blocks — LLVM's register allocator can't prove it dead, so it spills.

This is **runtime** — it happens at the first `Filter.evaluate` call, no matter how clean `make()` was. The arrow-java guard with `max_nodes=10000` is sized to keep total node count below the threshold where frame size becomes dangerous, but it's a heuristic — actual frame size depends on which nodes are varchars, how LLVM scheduled them, etc.

#### What the fix changes

Two flavours, depending on how invasive you want to go:

**Targeted (covers the customer shape):** Detect long if-chains in `LLVMGenerator` and lower them to a single basic block with a sequential compare-cascade plus short-circuit exit, not 393 nested `BuildIfElse` calls. Concretely:

```
;; today: 393 then/else/merge triples, 393 deep φ
;; proposed: one block, one φ at the end, sequential conditional moves
  cmp key, 'lit_0'
  jne  L1
  mov  result, v_0
  jmp  done
L1:
  cmp key, 'lit_1'
  jne  L2
  mov  result, v_1
  jmp  done
L2: ...
done:
  ...
```

Only **one** varchar pointer/length pair needs to be live at any moment — the current arm's. Frame stays small regardless of N.

**General (fixes the whole class):** Teach `LLVMGenerator` a "max frame size budget" knob. If LLVM tells us the frame would exceed the budget, emit the rest of the work as a tail call into a helper function (which gets its own fresh frame). Costs a function-call boundary per chunk, gains a frame-size guarantee.

#### Why this matters even with the arrow-java guard

The guard's `max_nodes=10000` is a proxy for "the frame won't be too big." That's empirically reasonable but not principled — a query with 5 000 nodes that happen to be wide varchar comparisons could still produce a big frame. Fix #2 makes the frame size a *contract* of the code generator instead of a probabilistic property of the input. The guard could then either be removed or raised significantly.

---

### How the two fixes compose

- **Fix #1** fixes *compile-time* native stack overflows. Lets the guard's depth cap (100) be raised or removed for the if-chain case.
- **Fix #2** fixes *runtime* JIT-frame overflows. Lets the guard's node-count cap (10 000) be raised or removed for the wide-expression case.

Together they let arrow-java drop both limits — Gandiva itself would guarantee bounded resource use, instead of arrow-java having to refuse to submit anything that might explode. The guard becomes a backstop rather than the primary safety mechanism.

That's the long-term destination. The arrow-java guard is the bridge.
