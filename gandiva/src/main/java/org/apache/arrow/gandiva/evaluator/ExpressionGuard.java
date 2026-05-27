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

import java.util.ArrayDeque;
import java.util.Deque;
import org.apache.arrow.gandiva.exceptions.GandivaException;
import org.apache.arrow.gandiva.ipc.GandivaTypes;

/**
 * Pre-flight check for Gandiva expression trees.
 *
 * <p>Gandiva compiles each expression into a single LLVM-emitted function. Two AST shapes can
 * crash the JVM in native code:
 *
 * <ul>
 *   <li>A very deep tree (e.g. a long {@code CASE WHEN} chain becoming a nested {@code IfNode}
 *       chain) — the native AST visitors recurse per level and exhaust the C++ stack during
 *       {@code Filter.make}/{@code Projector.make}.
 *   <li>A very wide tree with many nodes (e.g. a planner-expanded {@code OR(AND(...), AND(...),
 *       ...)} with O(N²) comparisons) — compilation succeeds but the JIT'd function reserves a
 *       stack frame too large for the executor thread's stack at first call to {@code evaluate}.
 * </ul>
 *
 * <p>This class walks the protobuf form of an expression iteratively (no Java-side recursion, so
 * the guard itself never overflows on pathological input) and rejects trees whose depth or
 * node-count exceed configured limits, converting what would be a JVM crash into a recoverable
 * {@link GandivaException}.
 *
 * <p>Limits can be overridden via the system properties {@value #MAX_DEPTH_PROPERTY} and {@value
 * #MAX_NODES_PROPERTY}; the defaults are sized to admit any plausible hand-written expression
 * while rejecting the planner-generated pathological shapes that have been observed to crash.
 */
final class ExpressionGuard {

  static final String MAX_DEPTH_PROPERTY = "org.apache.arrow.gandiva.expr.max_depth";
  static final String MAX_NODES_PROPERTY = "org.apache.arrow.gandiva.expr.max_nodes";

  static final int DEFAULT_MAX_DEPTH = 100;
  static final int DEFAULT_MAX_NODES = 10_000;

  private ExpressionGuard() {}

  static int maxDepth() {
    return Integer.getInteger(MAX_DEPTH_PROPERTY, DEFAULT_MAX_DEPTH);
  }

  static int maxNodes() {
    return Integer.getInteger(MAX_NODES_PROPERTY, DEFAULT_MAX_NODES);
  }

  /** Validates a Condition's root tree. */
  static void check(GandivaTypes.Condition condition) throws GandivaException {
    if (condition.hasRoot()) {
      check(condition.getRoot());
    }
  }

  /** Validates every expression root in an ExpressionList. */
  static void check(GandivaTypes.ExpressionList exprs) throws GandivaException {
    for (GandivaTypes.ExpressionRoot root : exprs.getExprsList()) {
      if (root.hasRoot()) {
        check(root.getRoot());
      }
    }
  }

  /** Walks a single tree iteratively, throwing if depth or node-count exceed the limits. */
  static void check(GandivaTypes.TreeNode root) throws GandivaException {
    final int maxDepth = maxDepth();
    final int maxNodes = maxNodes();

    // Pair each node with its depth on a work-stack. ArrayDeque is the JDK's recommended
    // non-recursive stack; per-entry cost is tiny so we can hold ~maxNodes entries without
    // approaching the heap budget.
    Deque<Frame> stack = new ArrayDeque<>();
    stack.push(new Frame(root, 1));

    int nodes = 0;
    int observedMaxDepth = 0;
    while (!stack.isEmpty()) {
      Frame frame = stack.pop();
      nodes++;
      if (nodes > maxNodes) {
        throw new GandivaException(
            "Gandiva expression exceeds node-count limit: > "
                + maxNodes
                + " nodes (override with -D"
                + MAX_NODES_PROPERTY
                + "=N)");
      }
      if (frame.depth > observedMaxDepth) {
        observedMaxDepth = frame.depth;
      }
      if (frame.depth > maxDepth) {
        throw new GandivaException(
            "Gandiva expression exceeds depth limit: depth "
                + frame.depth
                + " > "
                + maxDepth
                + " (override with -D"
                + MAX_DEPTH_PROPERTY
                + "=N)");
      }

      GandivaTypes.TreeNode node = frame.node;
      int childDepth = frame.depth + 1;

      if (node.hasIfNode()) {
        GandivaTypes.IfNode ifNode = node.getIfNode();
        if (ifNode.hasCond()) {
          stack.push(new Frame(ifNode.getCond(), childDepth));
        }
        if (ifNode.hasThenNode()) {
          stack.push(new Frame(ifNode.getThenNode(), childDepth));
        }
        if (ifNode.hasElseNode()) {
          stack.push(new Frame(ifNode.getElseNode(), childDepth));
        }
      }
      if (node.hasAndNode()) {
        for (GandivaTypes.TreeNode child : node.getAndNode().getArgsList()) {
          stack.push(new Frame(child, childDepth));
        }
      }
      if (node.hasOrNode()) {
        for (GandivaTypes.TreeNode child : node.getOrNode().getArgsList()) {
          stack.push(new Frame(child, childDepth));
        }
      }
      if (node.hasFnNode()) {
        for (GandivaTypes.TreeNode child : node.getFnNode().getInArgsList()) {
          stack.push(new Frame(child, childDepth));
        }
      }
      if (node.hasInNode() && node.getInNode().hasNode()) {
        stack.push(new Frame(node.getInNode().getNode(), childDepth));
      }
      // Leaf nodes (field, literals) have no children to enqueue.
    }
  }

  private static final class Frame {
    final GandivaTypes.TreeNode node;
    final int depth;

    Frame(GandivaTypes.TreeNode node, int depth) {
      this.node = node;
      this.depth = depth;
    }
  }
}
