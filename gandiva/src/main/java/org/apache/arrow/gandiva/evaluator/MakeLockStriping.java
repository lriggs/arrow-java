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

import java.util.Arrays;

/**
 * Fixed pool of lock objects used to stripe the native {@code make()} calls in {@link Projector}
 * and {@link Filter} by an approximation of Gandiva's native expression-cache key (schema bytes,
 * expression/condition bytes, selection vector mode, configuration id).
 *
 * <p>Two concurrent {@code make()} calls for the identical cache key hash to the same stripe and
 * serialize -- preserving the invariant that avoided the duplicate-LLVM-symbol crash from GH-601 --
 * while unrelated concurrent compilations, which make up the large majority of concurrent traffic,
 * proceed in parallel instead of contending on one process-wide lock.
 */
final class MakeLockStriping {
  private static final int STRIPE_COUNT = 64;

  private final Object[] locks;

  MakeLockStriping() {
    Object[] stripes = new Object[STRIPE_COUNT];
    for (int i = 0; i < stripes.length; i++) {
      stripes[i] = new Object();
    }
    this.locks = stripes;
  }

  /** Returns the lock stripe for the cache key formed by the given parts. */
  Object forKey(Object... keyParts) {
    int hash = Arrays.deepHashCode(keyParts);
    return locks[(hash & Integer.MAX_VALUE) % locks.length];
  }
}
