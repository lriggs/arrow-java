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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.Lists;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.gandiva.exceptions.GandivaException;
import org.apache.arrow.gandiva.expression.Condition;
import org.apache.arrow.gandiva.expression.TreeBuilder;
import org.apache.arrow.gandiva.expression.TreeNode;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.ipc.message.ArrowFieldNode;
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Faithful reproduction of the customer's failing Gandiva Filter.
 *
 * <p>Built from the post-planner Filter expression text the customer's Dremio emitted (saved at
 * {@code gandiva/filter.txt} during the incident). The expression has the shape
 *
 * <pre>
 *   OR(
 *     AND(=(key, lit_0), IS NOT NULL(v_0)),
 *     AND(=(key, lit_1), &lt;&gt;(key, lit_0), IS NOT NULL(v_1)),
 *     AND(=(key, lit_2), &lt;&gt;(key, lit_0), &lt;&gt;(key, lit_1), IS NOT NULL(v_2)),
 *     ...
 *     AND(=(key, lit_{N-1}), &lt;&gt;(key, lit_0), ..., &lt;&gt;(key, lit_{N-2}), IS NOT NULL(v_{N-1}))
 *   )
 * </pre>
 *
 * <p>For N=393 (the customer's count) that's <b>393 AND arms, 77 028 {@code &lt;&gt;} comparisons,
 * 393 {@code IS NOT NULL} checks</b> over a schema of 1 key column + 393 nullable VARCHAR(65536)
 * value columns.
 *
 * <p>Two test methods:
 *
 * <ol>
 *   <li>{@link #customerFilterIsRejectedByGuard()} — runs unconditionally. Asserts that the
 *       arrow-java guard rejects the expression with a {@code node-count} message instead of
 *       letting it reach native code. This is the production behaviour.
 *   <li>{@link #customerFilterRunsAgainstGandivaIfGuardDisabled()} — opt-in via {@code
 *       -Dgandiva.repro.runUnguarded=1}. Raises the guard limits via system properties so the
 *       expression actually reaches {@code Filter.make} / {@code Filter.evaluate}. Will likely
 *       crash the forked JVM with SIGSEGV on builds where the runtime JIT-frame overflow
 *       reproduces; otherwise will either compile-and-evaluate cleanly (on newer LLVM that
 *       deduplicates the comparisons) or take a long time. <b>Run in isolation</b> because the
 *       JVM exit may take other tests with it.
 * </ol>
 */
public class CustomerUnpivotFilterReproTest extends BaseEvaluatorTest {

  private static final String LITERALS_RESOURCE = "/unpivot_393_literals.txt";

  /** Loads the 393 literal strings extracted from the customer's filter.txt. */
  private static List<String> loadLiterals() {
    List<String> out = new ArrayList<>(393);
    try (InputStream in =
            CustomerUnpivotFilterReproTest.class.getResourceAsStream(LITERALS_RESOURCE);
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        if (!line.isEmpty()) {
          out.add(line);
        }
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to load " + LITERALS_RESOURCE, e);
    }
    if (out.size() != 393) {
      throw new IllegalStateException("Expected 393 literals, got " + out.size());
    }
    return out;
  }

  /**
   * Builds the customer's filter expression with the first {@code numArms} literals.
   *
   * <p>Total node count grows as N + N*(N-1)/2 (positive eqs + cumulative neqs) + N (isnotnull) +
   * N (and) + 1 (or) ≈ N²/2 + 3N + 1. For N=393 ≈ 78 600 nodes.
   *
   * @return the bundle containing the constructed schema and the Condition wrapping the OR root.
   */
  private static FilterBundle buildCustomerFilter(int numArms) {
    ArrowType utf8 = new ArrowType.Utf8();
    ArrowType bool = new ArrowType.Bool();
    List<String> literals = loadLiterals();
    if (numArms > literals.size()) {
      throw new IllegalArgumentException(
          "numArms=" + numArms + " exceeds available literals " + literals.size());
    }

    // Schema: column 0 is the key, columns 1..numArms are the per-arm values.
    Field keyField = Field.nullable("key", utf8);
    TreeNode keyNode = TreeBuilder.makeField(keyField);
    List<Field> schemaFields = new ArrayList<>();
    schemaFields.add(keyField);
    List<TreeNode> valueNodes = new ArrayList<>(numArms);
    for (int i = 0; i < numArms; i++) {
      Field vi = Field.nullable("v" + i, utf8);
      schemaFields.add(vi);
      valueNodes.add(TreeBuilder.makeField(vi));
    }

    // Precompute the positive `=(key, lit_i)` nodes once each (they're reused as `<>(...)` siblings
    // — but Gandiva tree nodes are immutable values; reuse here doesn't actually share structure
    // in the resulting protobuf, since each AND arm is built fresh from literal strings).
    List<TreeNode> eqNodes = new ArrayList<>(numArms);
    for (int i = 0; i < numArms; i++) {
      eqNodes.add(
          TreeBuilder.makeFunction(
              "equal",
              Lists.newArrayList(keyNode, TreeBuilder.makeStringLiteral(literals.get(i))),
              bool));
    }

    // Build each AND arm: =(key, lit_i), <>(key, lit_0), ..., <>(key, lit_{i-1}), isnotnull(v_i)
    List<TreeNode> orArgs = new ArrayList<>(numArms);
    for (int i = 0; i < numArms; i++) {
      List<TreeNode> andArgs = new ArrayList<>(i + 2);
      andArgs.add(eqNodes.get(i));
      for (int j = 0; j < i; j++) {
        andArgs.add(
            TreeBuilder.makeFunction(
                "not_equal",
                Lists.newArrayList(
                    keyNode, TreeBuilder.makeStringLiteral(literals.get(j))),
                bool));
      }
      andArgs.add(
          TreeBuilder.makeFunction("isnotnull", Lists.newArrayList(valueNodes.get(i)), bool));
      orArgs.add(TreeBuilder.makeAnd(andArgs));
    }
    TreeNode root = TreeBuilder.makeOr(orArgs);

    return new FilterBundle(new Schema(schemaFields), TreeBuilder.makeCondition(root));
  }

  /**
   * With the guard at its default settings, the customer's 393-arm filter must be rejected with a
   * GandivaException naming the node-count limit, before any native code runs.
   */
  @Test
  public void customerFilterIsRejectedByGuard() {
    FilterBundle bundle = buildCustomerFilter(393);
    GandivaException ex =
        assertThrows(
            GandivaException.class, () -> Filter.make(bundle.schema, bundle.condition));
    assertTrue(
        ex.getMessage().contains("node-count") || ex.getMessage().contains("depth"),
        ex.getMessage());
  }

  /**
   * Opt-in runtime reproduction. Raises guard limits via the test JVM's system properties
   * (caller is responsible for setting both — see test class Javadoc), then attempts to compile
   * AND evaluate the customer's filter.
   *
   * <p>To run: <pre>
   *   mvn -Parrow-jni -pl gandiva test \
   *     -Dtest='CustomerUnpivotFilterReproTest#customerFilterRunsAgainstGandivaIfGuardDisabled' \
   *     -Dsurefire.add-opens.argLine="--add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED \
   *         -Xss1m -Dgandiva.repro.runUnguarded=1 \
   *         -Dorg.apache.arrow.gandiva.expr.max_depth=1000000 \
   *         -Dorg.apache.arrow.gandiva.expr.max_nodes=10000000"
   * </pre>
   *
   * <p>Expected outcomes (none are "test pass" — this method is an investigation tool):
   *
   * <ul>
   *   <li>JVM SIGSEGV (exit 139) during {@code Filter.make} — the compile-time recursion cliff,
   *       which already had a regression test.
   *   <li>JVM SIGSEGV (exit 139) during {@code Filter.evaluate} — the runtime JIT-frame
   *       overflow the customer hit. This is the failure mode we previously couldn't reproduce
   *       locally; success here closes the epistemic gap on Fix #2 in EXPRESSION_GUARD.md.
   *   <li>Test completes — newer LLVM CSE'd the redundant comparisons, or the frame fit on the
   *       configured stack. Try a smaller {@code -Xss} (e.g. {@code -Xss256k}) to push the cliff
   *       down.
   * </ul>
   */
  @Test
  @EnabledIfSystemProperty(named = "gandiva.repro.runUnguarded", matches = ".+")
  public void customerFilterRunsAgainstGandivaIfGuardDisabled() throws GandivaException {
    int n = Integer.parseInt(System.getProperty("gandiva.repro.n", "393"));
    boolean optimize = Boolean.parseBoolean(System.getProperty("gandiva.repro.optimize", "true"));
    FilterBundle bundle = buildCustomerFilter(n);

    System.out.println("[probe] before Filter.make n=" + n + " optimize=" + optimize);
    System.out.flush();
    ConfigurationBuilder.ConfigOptions configOptions =
        new ConfigurationBuilder.ConfigOptions().withOptimize(optimize);
    Filter filter = Filter.make(bundle.schema, bundle.condition, configOptions);
    System.out.println("[probe] after Filter.make n=" + n);
    System.out.flush();

    int numRows = 1;
    int numCols = n + 1;
    ArrowFieldNode fieldNode = new ArrowFieldNode(numRows, 0);
    List<ArrowFieldNode> fieldNodes = new ArrayList<>(numCols);
    List<ArrowBuf> buffers = new ArrayList<>(numCols * 3);
    for (int i = 0; i < numCols; i++) {
      fieldNodes.add(fieldNode);
      ArrowBuf validity = allocator.buffer(1);
      validity.writeByte((byte) 0xFF);
      ArrowBuf offsets = allocator.buffer(8);
      offsets.writeInt(0);
      offsets.writeInt(0);
      ArrowBuf data = allocator.buffer(0);
      buffers.add(validity);
      buffers.add(offsets);
      buffers.add(data);
    }
    ArrowRecordBatch batch = new ArrowRecordBatch(numRows, fieldNodes, buffers);
    ArrowBuf selectionBuffer = allocator.buffer(numRows * 2L);
    SelectionVectorInt16 selectionVector = new SelectionVectorInt16(selectionBuffer);
    try {
      System.out.println("[probe] before Filter.evaluate n=" + n);
      System.out.flush();
      filter.evaluate(batch, selectionVector);
      System.out.println("[probe] after Filter.evaluate n=" + n);
      System.out.flush();
    } finally {
      selectionBuffer.close();
      releaseRecordBatch(batch);
      filter.close();
    }
  }

  private static final class FilterBundle {
    final Schema schema;
    final Condition condition;

    FilterBundle(Schema schema, Condition condition) {
      this.schema = schema;
      this.condition = condition;
    }
  }
}
