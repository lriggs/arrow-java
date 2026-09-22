/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.arrow.gandiva.evaluator;

import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.arrow.gandiva.expression.ExpressionTree;
import org.apache.arrow.gandiva.expression.TreeBuilder;
import org.apache.arrow.gandiva.expression.TreeNode;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Measures the throughput of concurrent {@link Projector#make} calls, to quantify what removing the
 * process-wide lock on {@code make()} actually buys (GH-601).
 *
 * <p>{@code Projector.make()} used to be declared {@code static synchronized}, so every LLVM module
 * build in the JVM serialized on one monitor regardless of whether the calls were related. That
 * monitor was {@code Projector.class}, which means the old behaviour can be reproduced exactly by
 * wrapping the call in {@code synchronized (Projector.class)}. This benchmark runs both arms in a
 * single JVM over identical pre-generated inputs, so the comparison does not depend on two builds
 * or two machine states.
 *
 * <p>Two workloads, because a cache hit is not free:
 *
 * <ul>
 *   <li><b>cold</b> — every call uses a distinct expression, so each one is a real LLVM compile.
 *       This is the case the original JFR profile was showing: unrelated concurrent queries queuing
 *       behind one lock.
 *   <li><b>warm</b> — every call shares one expression, so all but the first hit Gandiva's native
 *       object cache. A hit still constructs a fresh TargetMachine, LLJIT, LLVMContext and Module,
 *       installs ~150 absolute symbols, decomposes the expression tree and relocates the cached
 *       object, so it is milliseconds of real work that the old lock also serialized.
 * </ul>
 *
 * <p>Do not expect linear scaling from the unlocked arm. Two process-wide native locks remain on
 * this path — the expression cache's own mutex, taken on every lookup and insert, and the JNI
 * module-id map's mutex, taken on every make and every close. Where the curve flattens is where
 * those start to dominate.
 *
 * <p>Disabled by default; this is a benchmark, not a test. Run it with:
 *
 * <pre>
 * mvn -Parrow-jni -pl gandiva -Dtest=MakeConcurrencyBenchmark -DfailIfNoTests=false \
 *     -Djunit.jupiter.conditions.deactivate='*' \
 *     -Darrow.cpp.build.dir=/path/to/dir/containing/libgandiva_jni.so test
 * </pre>
 *
 * <p>Keep {@code COLD_MAKES * THREAD_COUNTS.length} well under Gandiva's native cache capacity
 * (default 5000, overridable only via the {@code GANDIVA_CACHE_SIZE} environment variable, which is
 * read once per process) so that LRU eviction does not perturb the cold numbers.
 */
@Disabled("perf benchmark; run explicitly, see class javadoc")
public class MakeConcurrencyBenchmark extends BaseEvaluatorTest {

  private static final int[] THREAD_COUNTS = {1, 2, 4, 8, 16};

  /** Distinct compiles per measured cold run. Each is tens of ms, so keep this modest. */
  private static final int COLD_MAKES = 256;

  /** Cache-hit makes per measured warm run. Each is ~ms, so this can be larger. */
  private static final int WARM_MAKES = 2048;

  /** Distinct expressions burned by warmup passes, reserved outside the measured key ranges. */
  private static final int WARMUP_MAKES = 64;

  /** Monotonic source of distinct cache keys, so no two runs in this JVM collide. */
  private static final AtomicLong KEY_SEQUENCE = new AtomicLong();

  /** Whether the timed call emulates the old {@code static synchronized} declaration. */
  private enum Arm {
    LOCKED("legacy (static synchronized)"),
    UNLOCKED("current (no lock)");

    private final String label;

    Arm(String label) {
      this.label = label;
    }
  }

  /**
   * Builds one expression whose native cache key is unique to {@code key}.
   *
   * <p>Varying an integer literal is the cheapest way to get a distinct key: Gandiva's cache key is
   * derived from the printed form of the expression, and changing the literal leaves the generated
   * IR the same size, so per-compile cost stays flat across the sweep. Note this deliberately
   * avoids any {@code like()} function — Gandiva mixes the thread id into the cache key for those,
   * which would make the hit/miss ratio depend on thread count.
   */
  private List<ExpressionTree> distinctExpr(long key) {
    Field x = Field.nullable("x", int32);
    TreeNode sum =
        TreeBuilder.makeFunction(
            "add",
            Lists.newArrayList(TreeBuilder.makeField(x), TreeBuilder.makeLiteral((int) key)),
            int32);
    return Lists.newArrayList(TreeBuilder.makeExpression(sum, Field.nullable("out", int32)));
  }

  private Schema schema() {
    return new Schema(Lists.newArrayList(Field.nullable("x", int32)));
  }

  /**
   * Runs {@code numMakes} Projector.make() calls across {@code numThreads} threads and returns the
   * wall-clock nanos for the whole batch.
   *
   * @param keys the cache key to use for each call; pre-generated so expression construction is not
   *     inside the timed region. Repeat a value to force a cache hit.
   */
  private long timeMakes(Arm arm, int numThreads, List<Long> keys) throws Exception {
    final Schema schema = schema();

    // Build every expression up front: only make() should be inside the timed region.
    List<List<ExpressionTree>> exprs = new ArrayList<>(keys.size());
    for (Long key : keys) {
      exprs.add(distinctExpr(key));
    }

    ExecutorService pool = Executors.newFixedThreadPool(numThreads);
    List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
    // All threads meet here, so the measured window is contention and not pool ramp-up.
    CyclicBarrier gate = new CyclicBarrier(numThreads + 1);

    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < numThreads; t++) {
        final int threadIndex = t;
        futures.add(
            pool.submit(
                () -> {
                  try {
                    gate.await(60, TimeUnit.SECONDS);
                    // Stripe the work so every thread does an equal share.
                    for (int i = threadIndex; i < exprs.size(); i += numThreads) {
                      makeOnce(arm, schema, exprs.get(i));
                    }
                  } catch (Throwable e) {
                    failures.add(e);
                  }
                }));
      }

      gate.await(60, TimeUnit.SECONDS);
      long start = System.nanoTime();
      for (Future<?> future : futures) {
        future.get(10, TimeUnit.MINUTES);
      }
      long elapsed = System.nanoTime() - start;

      if (!failures.isEmpty()) {
        AssertionError error = new AssertionError("make() failed during benchmark");
        error.initCause(failures.get(0));
        throw error;
      }
      return elapsed;
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * One make/close pair. The LOCKED arm holds the {@code Projector.class} monitor across the call,
   * which is exactly what {@code public static synchronized Projector make(...)} did. Verified that
   * this mechanism works the same as the original sycnhronized method call.
   */
  private void makeOnce(Arm arm, Schema schema, List<ExpressionTree> exprs) throws Exception {
    Projector projector = null;
    try {
      if (arm == Arm.LOCKED) {
        synchronized (Projector.class) {
          projector = Projector.make(schema, exprs);
        }
      } else {
        projector = Projector.make(schema, exprs);
      }
    } finally {
      // Projector is not AutoCloseable and has no Cleaner: a dropped reference leaks a whole LLJIT,
      // which at these iteration counts will exhaust memory.
      if (projector != null) {
        projector.close();
      }
    }
  }

  /** Distinct key per call: every make() is a real LLVM compile. */
  private List<Long> coldKeys(int count) {
    List<Long> keys = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      keys.add(KEY_SEQUENCE.incrementAndGet());
    }
    return keys;
  }

  /** One shared key: all but the first make() hits the native object cache. */
  private List<Long> warmKeys(int count) {
    long key = KEY_SEQUENCE.incrementAndGet();
    List<Long> keys = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      keys.add(key);
    }
    return keys;
  }

  private void warmup() throws Exception {
    // Absorbs JVM JIT, the one-time JniLoader native load, and LLVM's one-time target init.
    timeMakes(Arm.UNLOCKED, 4, coldKeys(WARMUP_MAKES));
  }

  @Test
  public void benchmarkConcurrentMake() throws Exception {
    warmup();

    StringBuilder table = new StringBuilder();
    table.append("\n### Projector.make() throughput (makes/sec, higher is better)\n\n");
    table.append("cold = distinct expression per call (real LLVM compile)\n");
    table.append("warm = one shared expression (native object-cache hits)\n\n");
    table.append("| threads | cold legacy | cold current | cold speedup ");
    table.append("| warm legacy | warm current | warm speedup |\n");
    table.append("|--------:|------------:|-------------:|-------------:");
    table.append("|------------:|-------------:|-------------:|\n");

    for (int numThreads : THREAD_COUNTS) {
      double coldLocked = rate(COLD_MAKES, timeMakes(Arm.LOCKED, numThreads, coldKeys(COLD_MAKES)));
      double coldFree = rate(COLD_MAKES, timeMakes(Arm.UNLOCKED, numThreads, coldKeys(COLD_MAKES)));
      double warmLocked = rate(WARM_MAKES, timeMakes(Arm.LOCKED, numThreads, warmKeys(WARM_MAKES)));
      double warmFree = rate(WARM_MAKES, timeMakes(Arm.UNLOCKED, numThreads, warmKeys(WARM_MAKES)));

      table.append(
          String.format(
              "| %7d | %11.1f | %12.1f | %11.2fx | %11.1f | %12.1f | %11.2fx |%n",
              numThreads,
              coldLocked,
              coldFree,
              coldFree / coldLocked,
              warmLocked,
              warmFree,
              warmFree / warmLocked));
    }

    table.append("\nArms: legacy = ").append(Arm.LOCKED.label);
    table.append(", current = ").append(Arm.UNLOCKED.label).append('\n');
    table.append("Cores available: ").append(Runtime.getRuntime().availableProcessors());
    table.append(" (speedup is bounded by this, not by thread count)\n");

    System.out.println(table);
  }

  private static double rate(int numMakes, long elapsedNanos) {
    return numMakes * 1_000_000_000.0 / elapsedNanos;
  }
}
