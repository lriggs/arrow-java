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
import org.apache.arrow.gandiva.ipc.GandivaTypes;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

/**
 * Focused tests for the {@link ExpressionGuard} depth and node-count limits as wired into {@link
 * Filter#make(Schema, Condition)} and {@link Projector#make(Schema, java.util.List)}. Each test
 * builds the smallest expression that violates one limit, calls the relevant make() variant, and
 * asserts the right kind of {@link GandivaException} is raised before reaching the JNI layer.
 */
public class ExpressionGuardTest extends BaseEvaluatorTest {

  /**
   * Builds a chain of nested {@link TreeBuilder#makeIf} nodes that is {@code numIfs} deep. With
   * {@code numIfs > DEFAULT_MAX_DEPTH} the guard must reject this with a depth message.
   */
  private static TreeNode nestedIfChain(int numIfs, Field cond, Field then, Field els) {
    TreeNode condNode = TreeBuilder.makeField(cond);
    TreeNode thenNode = TreeBuilder.makeField(then);
    TreeNode current = TreeBuilder.makeField(els);
    ArrowType retType = els.getType();
    for (int i = 0; i < numIfs; i++) {
      current = TreeBuilder.makeIf(condNode, thenNode, current, retType);
    }
    return current;
  }

  /**
   * Builds an OR of {@code numArgs} {@code isnotnull(field_i)} comparisons — a flat tree that
   * pushes the node count up without adding any depth.
   */
  private static TreeNode wideOr(List<Field> fields) {
    ArrowType bool = new ArrowType.Bool();
    List<TreeNode> args = new ArrayList<>(fields.size());
    for (Field f : fields) {
      args.add(
          TreeBuilder.makeFunction(
              "isnotnull", Lists.newArrayList(TreeBuilder.makeField(f)), bool));
    }
    return TreeBuilder.makeOr(args);
  }

  // ---------- depth limit ----------

  @Test
  public void filterRejectsExpressionAboveDepthLimit() {
    Field cond = Field.nullable("c", new ArrowType.Bool());
    Field then = Field.nullable("t", new ArrowType.Bool());
    Field els = Field.nullable("e", new ArrowType.Bool());
    Schema schema = new Schema(Lists.newArrayList(cond, then, els));

    TreeNode root =
        nestedIfChain(ExpressionGuard.DEFAULT_MAX_DEPTH + 50, cond, then, els);
    Condition condition = TreeBuilder.makeCondition(root);

    GandivaException ex =
        assertThrows(GandivaException.class, () -> Filter.make(schema, condition));
    assertTrue(ex.getMessage().contains("depth"), ex.getMessage());
  }

  @Test
  public void projectorRejectsExpressionAboveDepthLimit() {
    Field cond = Field.nullable("c", new ArrowType.Bool());
    Field then = Field.nullable("t", new ArrowType.Bool());
    Field els = Field.nullable("e", new ArrowType.Bool());
    Schema schema = new Schema(Lists.newArrayList(cond, then, els));

    TreeNode root =
        nestedIfChain(ExpressionGuard.DEFAULT_MAX_DEPTH + 50, cond, then, els);
    ExpressionTree expr =
        TreeBuilder.makeExpression(root, Field.nullable("res", new ArrowType.Bool()));

    GandivaException ex =
        assertThrows(
            GandivaException.class, () -> Projector.make(schema, Lists.newArrayList(expr)));
    assertTrue(ex.getMessage().contains("depth"), ex.getMessage());
  }

  // ---------- node-count limit ----------

  @Test
  public void filterRejectsExpressionAboveNodeCountLimit() {
    List<Field> fields = new ArrayList<>();
    // numFields chosen so total nodes (1 OR + N functions + N fields) is comfortably past limit.
    int numFields = ExpressionGuard.DEFAULT_MAX_NODES;
    for (int i = 0; i < numFields; i++) {
      fields.add(Field.nullable("f" + i, new ArrowType.Bool()));
    }
    Schema schema = new Schema(fields);
    Condition condition = TreeBuilder.makeCondition(wideOr(fields));

    GandivaException ex =
        assertThrows(GandivaException.class, () -> Filter.make(schema, condition));
    assertTrue(ex.getMessage().contains("node-count"), ex.getMessage());
  }

  @Test
  public void projectorRejectsExpressionAboveNodeCountLimit() {
    List<Field> fields = new ArrayList<>();
    int numFields = ExpressionGuard.DEFAULT_MAX_NODES;
    for (int i = 0; i < numFields; i++) {
      fields.add(Field.nullable("f" + i, new ArrowType.Bool()));
    }
    Schema schema = new Schema(fields);
    ExpressionTree expr =
        TreeBuilder.makeExpression(
            wideOr(fields), Field.nullable("res", new ArrowType.Bool()));

    GandivaException ex =
        assertThrows(
            GandivaException.class, () -> Projector.make(schema, Lists.newArrayList(expr)));
    assertTrue(ex.getMessage().contains("node-count"), ex.getMessage());
  }

  // ---------- sanity: small expressions still compile ----------

  @Test
  public void smallExpressionPassesGuard() throws GandivaException {
    Field a = Field.nullable("a", new ArrowType.Bool());
    Schema schema = new Schema(Lists.newArrayList(a));
    Condition condition =
        TreeBuilder.makeCondition(
            TreeBuilder.makeFunction(
                "isnotnull",
                Lists.newArrayList(TreeBuilder.makeField(a)),
                new ArrowType.Bool()));
    Filter filter = Filter.make(schema, condition);
    filter.close();
  }

  // ---------- boundary tests ----------
  // The guard fires on `depth > maxDepth` and `nodes > maxNodes`, so the exact-limit value
  // must pass and limit+1 must fail. These tests pin that boundary by calling the guard
  // directly on a hand-built protobuf tree — going through Filter.make would also compile the
  // expression natively, which for the node-count boundary (10 000-arg AND) takes ~9 minutes.
  // The Filter.make/Projector.make wiring is covered by the other tests in this class.

  /** Builds a protobuf TreeNode that is exactly {@code depth} deep. */
  private static GandivaTypes.TreeNode protoChainOfDepth(int depth) throws GandivaException {
    Field f = Field.nullable("a", new ArrowType.Bool());
    TreeNode current = TreeBuilder.makeField(f);
    for (int i = 1; i < depth; i++) {
      current =
          TreeBuilder.makeFunction("not", Lists.newArrayList(current), new ArrowType.Bool());
    }
    return current.toProtobuf();
  }

  /** Builds a protobuf TreeNode with exactly {@code totalNodes} nodes (flat AND of fields). */
  private static GandivaTypes.TreeNode protoFlatAndOfSize(int totalNodes) throws GandivaException {
    int numArgs = totalNodes - 1; // 1 AndNode + numArgs field nodes
    List<TreeNode> args = new ArrayList<>(numArgs);
    for (int i = 0; i < numArgs; i++) {
      args.add(TreeBuilder.makeField(Field.nullable("f" + i, new ArrowType.Bool())));
    }
    return TreeBuilder.makeAnd(args).toProtobuf();
  }

  @Test
  public void guardPassesAtDepthOneBelowLimit() throws GandivaException {
    ExpressionGuard.check(protoChainOfDepth(ExpressionGuard.DEFAULT_MAX_DEPTH - 1));
  }

  @Test
  public void guardPassesAtDepthExactlyAtLimit() throws GandivaException {
    ExpressionGuard.check(protoChainOfDepth(ExpressionGuard.DEFAULT_MAX_DEPTH));
  }

  @Test
  public void guardRejectsAtDepthOneAboveLimit() throws GandivaException {
    GandivaTypes.TreeNode root = protoChainOfDepth(ExpressionGuard.DEFAULT_MAX_DEPTH + 1);
    GandivaException ex =
        assertThrows(GandivaException.class, () -> ExpressionGuard.check(root));
    assertTrue(ex.getMessage().contains("depth"), ex.getMessage());
  }

  @Test
  public void guardPassesAtNodeCountExactlyAtLimit() throws GandivaException {
    ExpressionGuard.check(protoFlatAndOfSize(ExpressionGuard.DEFAULT_MAX_NODES));
  }

  @Test
  public void guardRejectsAtNodeCountOneAboveLimit() throws GandivaException {
    GandivaTypes.TreeNode root = protoFlatAndOfSize(ExpressionGuard.DEFAULT_MAX_NODES + 1);
    GandivaException ex =
        assertThrows(GandivaException.class, () -> ExpressionGuard.check(root));
    assertTrue(ex.getMessage().contains("node-count"), ex.getMessage());
  }

  // ---------- system-property override ----------

  /**
   * Raising the depth limit via {@link ExpressionGuard#MAX_DEPTH_PROPERTY} must let through a
   * tree that would otherwise be rejected. Property is restored in finally so subsequent tests
   * in the same JVM fork see the original behaviour.
   */
  @Test
  public void depthLimitHonoursSystemPropertyOverride() throws GandivaException {
    GandivaTypes.TreeNode root = protoChainOfDepth(ExpressionGuard.DEFAULT_MAX_DEPTH + 1);
    // Without override: rejected.
    assertThrows(GandivaException.class, () -> ExpressionGuard.check(root));

    String previous = System.getProperty(ExpressionGuard.MAX_DEPTH_PROPERTY);
    try {
      System.setProperty(
          ExpressionGuard.MAX_DEPTH_PROPERTY,
          Integer.toString(ExpressionGuard.DEFAULT_MAX_DEPTH + 10));
      // With override: accepted.
      ExpressionGuard.check(root);
    } finally {
      if (previous == null) {
        System.clearProperty(ExpressionGuard.MAX_DEPTH_PROPERTY);
      } else {
        System.setProperty(ExpressionGuard.MAX_DEPTH_PROPERTY, previous);
      }
    }
  }
}
