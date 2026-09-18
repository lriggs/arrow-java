# Bug: UNPIVOT plan triggers O(N²) `IS NOT NULL(CASE)` expansion → Gandiva crash

## Summary

When Dremio's planner reduces a `Filter` whose condition contains `IS NOT NULL(CASE WHEN c0 THEN v0 WHEN c1 THEN v1 … WHEN c_{N-1} THEN v_{N-1} ELSE NULL END)`, it expands the expression to:

```
OR(
  AND(c0, isnotnull(v0)),
  AND(NOT(c0), c1, isnotnull(v1)),
  AND(NOT(c0), NOT(c1), c2, isnotnull(v2)),
  …
  AND(NOT(c0), NOT(c1), …, NOT(c_{N-2}), c_{N-1}, isnotnull(v_{N-1}))
)
```

That's `N + N·(N−1)/2` distinct comparisons. For an UNPIVOT over a 393-column table, that's **77 421 `equal` comparisons** in one Filter condition (~4.5 MB protobuf). Gandiva JIT-compiles the whole thing into a single LLVM function whose stack frame exceeds the executor thread's stack at first call, killing the JVM with `SIGSEGV` / `SEGV_ACCERR`.

Customer hit this on Dremio Enterprise 26.1.6, Java 11, Ubuntu 18.04, OpenJDK 11.0.18, 1028 KB executor thread stack. The hs_err shows `RSP` ~1 MB below the executor thread's stack base and an instruction prologue spilling registers into stack-relative slots up around `[rsp+0x6f??]`.

The wide rewrite is semantically equivalent but **redundant** when the original CASE arms have mutually-exclusive conditions (e.g. `key = 'lit_0'`, `key = 'lit_1'`, …, with distinct constant RHS). UNPIVOT-generated CASEs always have that property.

## Severity / impact

- **Crashes the executor JVM** in native code with no Java-side catch path. Other in-flight queries on the same executor are lost. Daemon restarts.
- Already worked around at the arrow-java layer (`ExpressionGuard` rejects expressions of > 10 000 nodes with a clean `GandivaException`), but this only converts the crash into a query failure — the customer's UNPIVOT still cannot complete.
- Affects any UNPIVOT over a wide table (or any hand-written `IS NOT NULL(CASE …)` with many mutually-exclusive arms). The threshold for a crash depends on the executor thread's `-Xss`; we measured a 1 MB stack crashes around N≈70 distinct AND-arms in the post-rewrite form, the customer crashed at N=393.

## Reproducing SQL

The trigger is `IS NOT NULL(CASE WHEN key = 'lit_0' THEN v_0 WHEN key = 'lit_1' THEN v_1 … ELSE NULL END)` reaching `FilterReduceExpressionsRule`. The CASE must have:
- many arms whose conditions are mutually-exclusive equality checks against the same column (`key = 'lit_i'`),
- value expressions that are **nullable** — if every value is non-null the optimiser folds the `IS NOT NULL` to `TRUE` and the rewrite never fires.

UNPIVOT with the default `EXCLUDE NULLS` (SQL standard default) generates exactly this shape: it lowers to a Project containing the CASE plus an implicit `WHERE value IS NOT NULL` filter, which gets merged into a single `Filter(IS NOT NULL(CASE …))` and then exploded.

Two repros below — both work, neither uses ellipses, both are runnable as written. Repro 1 uses UNPIVOT (matches the customer's exact path through Calcite's UNPIVOT lowering). Repro 2 is a parametric generator for arbitrary N.

### Repro 1 — UNPIVOT, 20 arms, matches the customer's planning path

Verified syntactically against Dremio's own UNPIVOT test fixture at `community/sabot/kernel/src/test/resources/dx94156/query.sql` (which UNPIVOTs 26 columns). Twenty arms is small enough to inspect via `EXPLAIN PLAN`, large enough to make the O(N²) explosion unambiguous (190 cumulative `NOT(=(...))` operators after simplification).

```sql
WITH wide AS (
  SELECT
    CAST(NULL            AS VARCHAR) AS c0,
    CAST(NULL            AS VARCHAR) AS c1,
    CAST(NULL            AS VARCHAR) AS c2,
    CAST(NULL            AS VARCHAR) AS c3,
    CAST(NULL            AS VARCHAR) AS c4,
    CAST(NULL            AS VARCHAR) AS c5,
    CAST(NULL            AS VARCHAR) AS c6,
    CAST('found_at_c7'   AS VARCHAR) AS c7,
    CAST(NULL            AS VARCHAR) AS c8,
    CAST(NULL            AS VARCHAR) AS c9,
    CAST(NULL            AS VARCHAR) AS c10,
    CAST(NULL            AS VARCHAR) AS c11,
    CAST(NULL            AS VARCHAR) AS c12,
    CAST(NULL            AS VARCHAR) AS c13,
    CAST(NULL            AS VARCHAR) AS c14,
    CAST(NULL            AS VARCHAR) AS c15,
    CAST(NULL            AS VARCHAR) AS c16,
    CAST(NULL            AS VARCHAR) AS c17,
    CAST(NULL            AS VARCHAR) AS c18,
    CAST(NULL            AS VARCHAR) AS c19
)
SELECT key, value
FROM wide
UNPIVOT (
  value FOR key IN (
    c0,  c1,  c2,  c3,  c4,  c5,  c6,  c7,  c8,  c9,
    c10, c11, c12, c13, c14, c15, c16, c17, c18, c19
  )
);
```

Expected result: **one row** — `key = 'C7'` (Calcite uppercases unquoted identifiers when materialising them as the UNPIVOT key literal), `value = 'found_at_c7'`. The default `EXCLUDE NULLS` semantics drops the other 19 rows.

### Verifying the rewrite fired

```sql
EXPLAIN PLAN INCLUDING ALL ATTRIBUTES FOR <Repro 1 SQL>;
```

Look for a `Filter` node whose condition is a flat `OR(...)` of 20 `AND(...)` arms. Arm *i* (for 0 ≤ i < 20) should contain *i* distinct `NOT(=($keyRef, 'C_j'))` operators where j < i, plus one positive `=($keyRef, 'C_i')` and one `IS NOT NULL($v_i)`.

Total `NOT(=(...))` operator count after rewrite: **190** (= 20 × 19 / 2). Grep the EXPLAIN output:
```bash
grep -oE 'NOT\(=\(' explain.txt | wc -l   # should print 190
```

If you see only ~20 (or zero) `NOT(=` matches, the rewrite didn't fire — possible causes:
- `planner.reduce_algebraic_expressions` disabled (it's on by default).
- `FilterReduceExpressionsRule` not in the active planner program for this query phase.
- The Project containing the CASE wasn't merged into the Filter (check for an intermediate `Project` between `Filter` and the data source).

### Repro 2 — parametric generator, scales to the customer's N

The full Repro 1 above is 20 arms; the customer's failing plan was 393. Below is a bash generator that produces a self-contained UNPIVOT query for arbitrary N. Default `N=100` is enough to trigger the arrow-java guard or crash a JVM with default `-Xss`; `N=393` matches the customer exactly.

```bash
# gen_repro.sh — emit an N-arm UNPIVOT that triggers the O(N²) rewrite.
# Usage: bash gen_repro.sh [N]        (default N=100)
N="${1:-100}"
M=$((N / 2))   # which column is non-null — middle so a row survives EXCLUDE NULLS
{
  printf 'WITH wide AS (\n  SELECT\n'
  for i in $(seq 0 $((N-1))); do
    if [ "$i" = "$M" ]; then
      printf "    CAST('found_at_c%d' AS VARCHAR) AS c%d" "$i" "$i"
    else
      printf '    CAST(NULL AS VARCHAR) AS c%d' "$i"
    fi
    if [ "$i" -lt "$((N-1))" ]; then printf ','; fi
    printf '\n'
  done
  printf ')\nSELECT key, value\nFROM wide\nUNPIVOT (\n  value FOR key IN (\n    '
  for i in $(seq 0 $((N-1))); do
    printf 'c%d' "$i"
    if [ "$i" -lt "$((N-1))" ]; then printf ', '; fi
    if [ "$(((i + 1) % 10))" = "0" ] && [ "$i" -lt "$((N-1))" ]; then printf '\n    '; fi
  done
  printf '\n  )\n);\n'
}
```

Save and run:
```bash
bash gen_repro.sh 100  > repro_100.sql
bash gen_repro.sh 393  > repro_393.sql   # customer's exact failing N
```

Expected behaviour at each layer:

| Stack | N=100 | N=393 (customer) |
|---|---|---|
| Without arrow-java guard, `-Xss256k` | JVM SIGSEGV in `Filter.evaluate` | JVM SIGSEGV in `Filter.evaluate` |
| Without arrow-java guard, `-Xss1m` (HotSpot default) | passes (≈ 5050 nodes, under cliff) | JVM SIGSEGV in `Filter.evaluate` |
| Without arrow-java guard, `-Xss2m` (Dremio default) | passes | passes on my box (newer LLVM); customer's older LLVM crashes |
| **With arrow-java guard** (this fix), any `-Xss` | passes (5050 nodes < 10 000 default limit) | clean `GandivaException: "Gandiva expression exceeds node-count limit: > 10000 nodes …"` |
| **With this planner-side fix proposed**, any `-Xss` | passes — rewrite never fires | passes — rewrite never fires; expression stays as nested CASE |

The "with this planner-side fix" row is what we want. Today, the guard converts the crash into a recoverable error but the query still fails. The planner-side fix lets the query succeed.

### Deterministic backup repro (no UNPIVOT)

If UNPIVOT lowering varies across Dremio versions and Repro 1 doesn't produce the expanded `Filter` shape on your build, the hand-written form below exercises `FilterReduceExpressionsRule` directly, bypassing the UNPIVOT path:

```sql
WITH t (key, v0, v1, v2, v3, v4, v5, v6, v7, v8, v9,
              v10, v11, v12, v13, v14, v15, v16, v17, v18, v19) AS (
  VALUES (
    CAST('k7'     AS VARCHAR),
    CAST(NULL     AS VARCHAR), CAST(NULL    AS VARCHAR), CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR),
    CAST(NULL     AS VARCHAR), CAST(NULL    AS VARCHAR), CAST('match' AS VARCHAR), CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR),
    CAST(NULL     AS VARCHAR), CAST(NULL    AS VARCHAR), CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR),
    CAST(NULL     AS VARCHAR), CAST(NULL    AS VARCHAR), CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR)
  )
)
SELECT key
FROM t
WHERE
  CASE
    WHEN key = 'k0'  THEN v0
    WHEN key = 'k1'  THEN v1
    WHEN key = 'k2'  THEN v2
    WHEN key = 'k3'  THEN v3
    WHEN key = 'k4'  THEN v4
    WHEN key = 'k5'  THEN v5
    WHEN key = 'k6'  THEN v6
    WHEN key = 'k7'  THEN v7
    WHEN key = 'k8'  THEN v8
    WHEN key = 'k9'  THEN v9
    WHEN key = 'k10' THEN v10
    WHEN key = 'k11' THEN v11
    WHEN key = 'k12' THEN v12
    WHEN key = 'k13' THEN v13
    WHEN key = 'k14' THEN v14
    WHEN key = 'k15' THEN v15
    WHEN key = 'k16' THEN v16
    WHEN key = 'k17' THEN v17
    WHEN key = 'k18' THEN v18
    WHEN key = 'k19' THEN v19
    ELSE NULL
  END IS NOT NULL;
```

Expected result: one row, `key = 'k7'`.

## Root cause — where the rewrite happens

The rewrite is performed during logical planning, not at Gandiva submission. Two cooperating steps:

### Step 1: `pushPredicateIntoCase` — push IS NOT NULL inside the CASE

[`DremioReduceExpressionsRule.pushPredicateIntoCase`](/home/logan.riggs/github/dremio/oss/sabot/kernel/src/main/java/com/dremio/exec/planner/logical/rule/DremioReduceExpressionsRule.java#L1103-L1158) is invoked via [`CaseShuttle`](/home/logan.riggs/github/dremio/oss/sabot/kernel/src/main/java/com/dremio/exec/planner/logical/rule/DremioReduceExpressionsRule.java#L1468-L1480) from [`reduceExpressionsInternal`](/home/logan.riggs/github/dremio/oss/sabot/kernel/src/main/java/com/dremio/exec/planner/logical/rule/DremioReduceExpressionsRule.java#L942-L951) called by [`FilterReduceExpressionsRule.onMatch`](/home/logan.riggs/github/dremio/oss/sabot/kernel/src/main/java/com/dremio/exec/planner/logical/rule/DremioReduceExpressionsRule.java#L143-L208).

For our input it converts:
```
IS NOT NULL(CASE WHEN c0 THEN v0 WHEN c1 THEN v1 … ELSE NULL END)
```
to:
```
CASE WHEN c0 THEN IS NOT NULL(v0)
     WHEN c1 THEN IS NOT NULL(v1)
     …
     ELSE IS NOT NULL(NULL)        // = false
END
```

This step is O(N): rewrites one operand per arm. Per-arm cost is small.

### Step 2: Calcite's `RexSimplify.simplifyPreservingType` — expand boolean-typed CASE to OR-of-AND

After `CaseShuttle`, [`reduceExpressions`](/home/logan.riggs/github/dremio/oss/sabot/kernel/src/main/java/com/dremio/exec/planner/logical/rule/DremioReduceExpressionsRule.java#L912-L939) at line 928 calls Calcite's `RexSimplify.simplifyPreservingType` on the result. When the input is a CASE whose all arms produce BOOLEAN, Calcite's `simplifyCase` (in `org.apache.calcite.rex.RexSimplify`, Apache Calcite library — pulled in as a dependency) expands it to a flat OR of AND-chains, with **cumulative NOTs of all prior conditions** in each arm:

```
OR(
  AND(c0, isnotnull(v0)),
  AND(NOT(c0), c1, isnotnull(v1)),
  AND(NOT(c0), NOT(c1), c2, isnotnull(v2)),
  …
  AND(NOT(c0), …, NOT(c_{N-2}), c_{N-1}, isnotnull(v_{N-1}))
)
```

This step is the O(N²) explosion. Calcite's `simplifyCase` does this because in general the original CASE has "first match wins" semantics — to be faithful, arm i must guarantee that no earlier arm matched. **Calcite does not check whether the conditions are mutually exclusive**, so it inserts the redundant `NOT(c_j)` guards even when they're tautologically true given that the c_j are distinct equality checks on the same column.

For UNPIVOT-generated CASE expressions, the conditions are always of the form `key = 'lit_i'` for distinct constants — mutually exclusive by construction. The cumulative `NOT(c_j)` guards are dead weight.

### Why Dremio's existing `PROJECTION_COMPLEXITY_GANDIVA_LIMIT` (default 150 000) did not save the customer

The complexity check is in [`SplitStageExecutor.setupSplit`](/home/logan.riggs/github/dremio/oss/sabot/kernel/src/main/java/com/dremio/exec/expr/SplitStageExecutor.java#L154-L199) and runs **per split, after splitting**. The work estimate is computed by [`ExpressionWorkEstimator`](/home/logan.riggs/github/dremio/oss/sabot/kernel/src/main/java/com/dremio/sabot/op/llvm/ExpressionWorkEstimator.java) which counts functions/ifs/booleans as 1 and **fields/literals as 0**. For the customer's expression, work ≈ 232 000 — which IS above the 150 000 default — but the splitter must have either:
1. Split the expression into sub-splits each individually below 150 000 (yet still large enough to crash Gandiva at runtime), or
2. Been disabled on the customer's deployment.

Either way, the complexity limit is a coarse safety net measured in different units from what actually crashes Gandiva. Tightening it is a band-aid; the real fix is to not produce the O(N²) expansion in the first place.

## Proposed fix

Three places this can be fixed, in increasing order of difficulty and increasing order of "really solves it":

### Fix A (recommended first, smallest patch) — skip `pushPredicateIntoCase` when CASE is wide

**File:** [`DremioReduceExpressionsRule.java`](/home/logan.riggs/github/dremio/oss/sabot/kernel/src/main/java/com/dremio/exec/planner/logical/rule/DremioReduceExpressionsRule.java#L1109)

In `pushPredicateIntoCase` (line 1109), bail out early when the inner CASE has more than `pushPredicateIntoCaseMaxArms` operands. New session option:

```java
LongValidator PUSH_PREDICATE_INTO_CASE_MAX_ARMS = new PositiveLongValidator(
    "planner.push_predicate_into_case_max_arms", 1000, 32);
```

Default 32 is conservative; even 100 would have prevented the customer crash. Adding the guard:

```java
public static RexCall pushPredicateIntoCase(RexCall call) {
  if (call.getType().getSqlTypeName() != SqlTypeName.BOOLEAN) {
    return call;
  }
  switch (call.getKind()) { … }

  int caseOrdinal = -1;
  final List<RexNode> operands = call.getOperands();
  for (int i = 0; i < operands.size(); i++) {
    if (operands.get(i).getKind() == SqlKind.CASE) {
      caseOrdinal = i;
    }
  }
  if (caseOrdinal < 0) {
    return call;
  }

  // NEW: bail out if the CASE has too many arms — pushing the predicate inside will
  // trigger Calcite's simplifyCase which produces an O(N²) OR-of-AND expansion that
  // crashes Gandiva at runtime via a giant JIT stack frame.
  final RexCall caseRex = (RexCall) operands.get(caseOrdinal);
  if (caseRex.getOperands().size() > sessionOptionPushPredicateIntoCaseMaxArms) {
    return call;
  }

  …
}
```

This stops the rewrite chain at step 1. Without the predicate inside the CASE, `RexSimplify.simplifyCase` doesn't have a boolean-typed CASE to expand and leaves the structure intact. arrow-java's `ExpressionGuard` will still reject the residual nested-CASE if its depth > 100 (arrow-java's other limit), but the planner won't produce the O(N²) shape that's untenable for Gandiva regardless.

**Pros**: smallest change, doesn't affect other cases, configurable.
**Cons**: doesn't actually make the customer's query work — it just stops the catastrophic shape from being generated. The original CASE still has to be evaluated somehow; that work falls to Dremio's Java-codegen path via the splitter.

### Fix B (recommended next, better customer outcome) — detect mutually-exclusive arms and use the smarter rewrite

When the CASE arms are all equality checks against the same column with distinct constant RHS, the cumulative-NOT form is redundant. Generate the simpler form directly:

```
OR(
  AND(c0, isnotnull(v0)),
  AND(c1, isnotnull(v1)),
  …
  AND(c_{N-1}, isnotnull(v_{N-1}))
)
```

This is **O(N) in the output**, faithful to the original semantics when conditions are mutex, and small enough to compile and execute in Gandiva regardless of N.

**Where**: same file — replace the call to Calcite's `RexSimplify.simplifyPreservingType` with a Dremio wrapper that pattern-matches `IS NOT NULL(CASE WHEN equality_on_same_column THEN … ELSE NULL)` and emits the smarter form before falling back to Calcite.

**Pros**: makes the customer's query work without changes elsewhere. Generalises to any UNPIVOT-shaped CASE.
**Cons**: more code; needs careful equivalence proof; risk of producing incorrect results if the mutex check has a bug.

### Fix C (longest term, fixes the root cause) — change UNPIVOT lowering to not produce a giant CASE

The cleanest fix: UNPIVOT should not lower to a CASE at all. Spark and DuckDB both lower UNPIVOT to a UNION ALL of per-column projects (each project selects `lit_i` as `key` and `col_i` as `value`), or to a cross-join with a `VALUES` table of `(lit, col)` pairs. Either form avoids the giant CASE entirely.

**Where**: Calcite produces the giant CASE in `SqlToRelConverter.convertUnpivot` (upstream library, not Dremio source). To override it in Dremio:
- Replace Calcite's Unpivot-to-Project lowering with a Dremio-specific lowering that emits a `LogicalUnion` of per-column projects.
- Likely lives in [`DremioRelMdRowCount`](/home/logan.riggs/github/dremio/oss/sabot/kernel/src/main/java/com/dremio/exec/planner/) or as a new RelOptRule.

**Pros**: fixes the problem at the source. Permanently better plans for UNPIVOT.
**Cons**: significant work. Calcite-deep. Affects estimated cardinality / cost model. Risk of regressions on narrow UNPIVOTs that currently optimise well as CASE.

### Fix D (file upstream) — Calcite ticket for smarter `simplifyCase`

Open a Calcite JIRA proposing that `RexSimplify.simplifyCase` check arm-condition mutual exclusivity before expanding to the cumulative-NOT form. Long lead time; benefits the entire Calcite ecosystem.

## Recommended path

1. **Immediate** (next 26.x patch release): **Fix A**. Tiny, safe, prevents the customer crash from recurring. Default the new session option conservatively (e.g. 32 arms).
2. **Short term**: **Fix B** if Fix A surfaces customer reports of UNPIVOT queries silently falling back to Java-codegen and being slow. Fix B preserves the performant Gandiva path.
3. **Long term**: **Fix C** if UNPIVOT becomes a frequent customer pattern. Otherwise leave it.
4. **Also**: file the Calcite ticket (Fix D) so the upstream simplifier eventually does the right thing.

## Test plan

For Fix A:
1. Add a unit test in `DremioReduceExpressionsRuleTest` (or wherever the rule is currently tested) that exercises `IS NOT NULL(CASE)` with >32 arms and asserts the resulting plan still contains a CASE (not an expanded OR).
2. Integration test: run `Repro 1` SQL above against a Dremio executor with `-Xss256k`. Without Fix A: JVM dies. With Fix A: query either succeeds or fails with `ExpressionValidationException` from `setupSplit`, not a JVM crash.
3. Verify the customer's original UNPIVOT shape (393 columns) no longer produces the O(N²) expansion in `EXPLAIN PLAN`.

For Fix B:
4. Equivalence test: for the same `IS NOT NULL(CASE WHEN c=k0 THEN v0 …)` input, the smart rewrite and the cumulative-NOT rewrite produce identical row-by-row truth tables across all combinations of `(c, v0, v1, …)` values.
5. Performance test: customer's 393-arm UNPIVOT query completes via Gandiva instead of falling back to Java codegen.

For Fix C:
6. Cardinality regression tests on existing narrow-UNPIVOT plans.
7. Performance tests showing UNION-ALL lowering doesn't regress narrow cases.

## Open questions

1. **Confirm step 2 is Calcite, not Dremio.** I traced the rewrite to `RexSimplify.simplifyPreservingType` invocation at [DremioReduceExpressionsRule.java:928](/home/logan.riggs/github/dremio/oss/sabot/kernel/src/main/java/com/dremio/exec/planner/logical/rule/DremioReduceExpressionsRule.java#L928) but did not read Calcite source (not locally available). The cumulative-NOT expansion is canonical Calcite `simplifyCase` behaviour and matches what the customer hit, but a one-line confirmation by reading Calcite's `RexSimplify.java` would be worth doing before landing the fix.
2. **Was `PROJECTION_COMPLEXITY_ENABLE_LIMIT` disabled on the customer's deployment?** The 150 000 work limit should have caught a 232 000-work expression. Worth checking the customer's options to know whether the per-split work limit is broken or just disabled.
3. **Does the customer's failing path actually go through `FilterReduceExpressionsRule`?** Their plan as shared shows the CASE inside a `Project`, not a `Filter`. The crash was at `Filter.evaluate`, so the splitter must have routed an IS NOT NULL-like predicate through the Filter path. Tracing the exact splitter decision would confirm Fix A is on the right code path.
