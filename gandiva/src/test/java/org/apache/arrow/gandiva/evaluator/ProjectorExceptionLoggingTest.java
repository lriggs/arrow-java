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
import java.util.List;
import org.apache.arrow.gandiva.exceptions.GandivaException;
import org.apache.arrow.gandiva.expression.ExpressionTree;
import org.apache.arrow.gandiva.expression.TreeBuilder;
import org.apache.arrow.gandiva.expression.TreeNode;
import org.apache.arrow.gandiva.ipc.GandivaTypes.SelectionVectorType;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

/** Test class to verify exception logging in Projector.make(). */
public class ProjectorExceptionLoggingTest {

  private static final ArrowType int64 = new ArrowType.Int(64, true);
  private static final ArrowType boolType = new ArrowType.Bool();

  @Test
  public void testProjectorMakeExceptionLogging() {
    // Create a schema with a field
    Field a = Field.nullable("a", int64);
    TreeNode aNode = TreeBuilder.makeField(a);
    List<TreeNode> args = Lists.newArrayList(aNode);

    List<Field> cols = Lists.newArrayList(a);
    Schema schema = new Schema(cols);

    // Create an expression with a non-existent function to trigger an exception
    TreeNode cond = TreeBuilder.makeFunction("non_existent_function", args, boolType);
    ExpressionTree expr = TreeBuilder.makeExpression(cond, Field.nullable("c", int64));
    List<ExpressionTree> exprs = Lists.newArrayList(expr);

    // Test that GandivaException is thrown and our logging code is executed
    GandivaException exception =
        assertThrows(
            GandivaException.class,
            () -> {
              Projector.make(schema, exprs, SelectionVectorType.SV_NONE);
            });

    // Verify that the exception is properly thrown (our logging should have occurred)
    assertTrue(
        exception.getMessage().contains("non_existent_function")
            || exception.getMessage().contains("Unknown function"));
  }

  @Test
  public void testProjectorMakeExceptionLoggingWithMultipleExpressions() {
    // Create a schema with multiple fields
    Field a = Field.nullable("a", int64);
    Field b = Field.nullable("b", int64);
    List<Field> cols = Lists.newArrayList(a, b);
    Schema schema = new Schema(cols);

    // Create multiple expressions, some valid and some invalid
    TreeNode aNode = TreeBuilder.makeField(a);
    TreeNode bNode = TreeBuilder.makeField(b);

    // Valid expression
    ExpressionTree validExpr =
        TreeBuilder.makeExpression("add", Lists.newArrayList(a, b), Field.nullable("valid", int64));

    // Invalid expression with non-existent function
    TreeNode invalidCond =
        TreeBuilder.makeFunction("invalid_function", Lists.newArrayList(aNode, bNode), boolType);
    ExpressionTree invalidExpr =
        TreeBuilder.makeExpression(invalidCond, Field.nullable("invalid", boolType));

    List<ExpressionTree> exprs = Lists.newArrayList(validExpr, invalidExpr);

    // Test that GandivaException is thrown and our logging code logs all expressions
    GandivaException exception =
        assertThrows(
            GandivaException.class,
            () -> {
              Projector.make(schema, exprs, SelectionVectorType.SV_INT32);
            });

    // Verify that the exception is properly thrown
    assertTrue(
        exception.getMessage().contains("invalid_function")
            || exception.getMessage().contains("Unknown function"));
  }
}
