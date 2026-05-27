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
import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.gandiva.exceptions.GandivaException;
import org.apache.arrow.gandiva.expression.Condition;
import org.apache.arrow.gandiva.expression.ExpressionTree;
import org.apache.arrow.gandiva.expression.TreeBuilder;
import org.apache.arrow.gandiva.expression.TreeNode;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the UNPIVOT-style nested-CASE expression that originally crashed the JVM
 * in native Gandiva code. The expression shape is
 *
 * <pre>
 *   CAST(CASE WHEN key = 'lit_0'   THEN v0
 *             WHEN key = 'lit_1'   THEN v1
 *             ...
 *             WHEN key = 'lit_392' THEN v392
 *             ELSE NULL
 *        END AS VARCHAR(65536))
 * </pre>
 *
 * <p>Calcite/Gandiva lower this into nested {@code if/else} nodes, one per WHEN branch. Without a
 * Java-side guard the resulting 393-deep AST blows the native stack during {@code Projector.make}
 * / {@code Filter.make}. With {@link ExpressionGuard} in place these deep variants must be
 * rejected cleanly with a {@link GandivaException} naming the depth limit; shallow variants must
 * still compile and evaluate normally.
 */
public class UnpivotCaseStackOverflowTest extends BaseEvaluatorTest {

  private static ProjectionBundle buildNestedCaseProjection(int numBranches) {
    ArrowType utf8 = new ArrowType.Utf8();

    Field keyField = Field.nullable("key", utf8);
    TreeNode keyNode = TreeBuilder.makeField(keyField);

    List<Field> schemaFields = new ArrayList<>();
    schemaFields.add(keyField);

    List<TreeNode> valueNodes = new ArrayList<>(numBranches);
    List<String> literals = new ArrayList<>(numBranches);
    for (int i = 0; i < numBranches; i++) {
      Field vi = Field.nullable("v" + i, utf8);
      schemaFields.add(vi);
      valueNodes.add(TreeBuilder.makeField(vi));
      literals.add("peg-krones.ns=3;s=V:0/3/" + i + ".value");
    }

    TreeNode current = TreeBuilder.makeNull(utf8);
    for (int i = numBranches - 1; i >= 0; i--) {
      TreeNode cond =
          TreeBuilder.makeFunction(
              "equal",
              Lists.newArrayList(keyNode, TreeBuilder.makeStringLiteral(literals.get(i))),
              new ArrowType.Bool());
      current = TreeBuilder.makeIf(cond, valueNodes.get(i), current, utf8);
    }

    TreeNode casted =
        TreeBuilder.makeFunction(
            "castVARCHAR",
            Lists.newArrayList(current, TreeBuilder.makeLiteral(65536L)),
            utf8);

    ExpressionTree expr = TreeBuilder.makeExpression(casted, Field.nullable("value", utf8));
    return new ProjectionBundle(new Schema(schemaFields), expr);
  }

  private static FilterBundle buildNestedCaseFilter(int numBranches) {
    ProjectionBundle proj = buildNestedCaseProjection(numBranches);
    // The projector tree is utf8-typed; wrap its root in isnotnull(...) so the whole expression
    // becomes a Bool condition usable by Filter. The if-chain depth — not the result type — is
    // what blows the native stack.
    TreeNode root =
        TreeBuilder.makeFunction(
            "isnotnull",
            Lists.newArrayList(rebuildNestedCaseRoot(numBranches)),
            new ArrowType.Bool());
    return new FilterBundle(proj.schema, TreeBuilder.makeCondition(root));
  }

  /** Identical to the body of buildNestedCaseProjection but exposes the raw root TreeNode. */
  private static TreeNode rebuildNestedCaseRoot(int numBranches) {
    ArrowType utf8 = new ArrowType.Utf8();
    Field keyField = Field.nullable("key", utf8);
    TreeNode keyNode = TreeBuilder.makeField(keyField);

    List<TreeNode> valueNodes = new ArrayList<>(numBranches);
    for (int i = 0; i < numBranches; i++) {
      valueNodes.add(TreeBuilder.makeField(Field.nullable("v" + i, utf8)));
    }

    TreeNode current = TreeBuilder.makeNull(utf8);
    for (int i = numBranches - 1; i >= 0; i--) {
      TreeNode cond =
          TreeBuilder.makeFunction(
              "equal",
              Lists.newArrayList(
                  keyNode,
                  TreeBuilder.makeStringLiteral(
                      "peg-krones.ns=3;s=V:0/3/" + i + ".value")),
              new ArrowType.Bool());
      current = TreeBuilder.makeIf(cond, valueNodes.get(i), current, utf8);
    }
    return TreeBuilder.makeFunction(
        "castVARCHAR", Lists.newArrayList(current, TreeBuilder.makeLiteral(65536L)), utf8);
  }

  /**
   * Reproduces the failing plan exactly: 393 WHEN branches (the count in the Dremio plan that
   * blew up LLVM). Without the guard this crashes the JVM with a native SIGSEGV during
   * {@code Projector.make}; with the guard wired in it must be rejected with a
   * {@link GandivaException} naming the depth limit.
   */
  @Test
  public void testUnpivotCaseStackOverflow() {
    ProjectionBundle bundle = buildNestedCaseProjection(393);
    GandivaException ex =
        assertThrows(
            GandivaException.class,
            () -> Projector.make(bundle.schema, Lists.newArrayList(bundle.expression)));
    assertTrue(ex.getMessage().contains("depth"), ex.getMessage());
  }

  /** A shallow version of the same shape compiles fine — sanity that ordinary CASEs still work. */
  @Test
  public void testUnpivotCaseShallow() throws GandivaException {
    ProjectionBundle bundle = buildNestedCaseProjection(8);
    Projector eval = Projector.make(bundle.schema, Lists.newArrayList(bundle.expression));
    eval.close();
  }

  /** Filter variant of the same shape: rejected by the guard for the same reason. */
  @Test
  public void testUnpivotCaseStackOverflowFilter() {
    FilterBundle bundle = buildNestedCaseFilter(393);
    GandivaException ex =
        assertThrows(
            GandivaException.class, () -> Filter.make(bundle.schema, bundle.condition));
    assertTrue(ex.getMessage().contains("depth"), ex.getMessage());
  }

  /** Shallow filter sanity check matching the projector shallow test. */
  @Test
  public void testUnpivotCaseShallowFilter() throws GandivaException {
    FilterBundle bundle = buildNestedCaseFilter(8);
    Filter filter = Filter.make(bundle.schema, bundle.condition);
    filter.close();
  }

  private static final class FilterBundle {
    final Schema schema;
    final Condition condition;

    FilterBundle(Schema schema, Condition condition) {
      this.schema = schema;
      this.condition = condition;
    }
  }

  private static final class ProjectionBundle {
    final Schema schema;
    final ExpressionTree expression;

    ProjectionBundle(Schema schema, ExpressionTree expression) {
      this.schema = schema;
      this.expression = expression;
    }
  }
}
