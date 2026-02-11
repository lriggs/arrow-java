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
package org.apache.arrow.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Thread-safety tests for AtomicFieldUpdater optimizations.
 *
 * <p>This test verifies that the AtomicFieldUpdater pattern used in BufferLedger, Accountant, and
 * other classes maintains thread-safety under high contention.
 */
public class TestAtomicFieldUpdaterThreadSafety {

  private RootAllocator allocator;

  @BeforeEach
  public void setup() {
    allocator = new RootAllocator(Long.MAX_VALUE);
  }

  @AfterEach
  public void tearDown() {
    allocator.close();
  }

  /**
   * Test concurrent buffer allocation and deallocation.
   *
   * <p>This test creates multiple threads that concurrently allocate and deallocate buffers,
   * stressing the BufferLedger reference counting implementation using AtomicIntegerFieldUpdater.
   */
  @Test
  public void testConcurrentBufferAllocation() throws InterruptedException {
    final int numThreads = 16;
    final int operationsPerThread = 1000;
    final int bufferSize = 1024;

    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(numThreads);
    AtomicInteger errors = new AtomicInteger(0);

    for (int i = 0; i < numThreads; i++) {
      executor.submit(
          () -> {
            try {
              startLatch.await(); // Wait for all threads to be ready

              for (int j = 0; j < operationsPerThread; j++) {
                ArrowBuf buf = allocator.buffer(bufferSize);
                // Perform some operations
                buf.writerIndex(bufferSize);
                buf.readerIndex(0);
                buf.close();
              }
            } catch (Exception e) {
              e.printStackTrace();
              errors.incrementAndGet();
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown(); // Start all threads
    assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Test timed out");
    executor.shutdown();
    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Executor did not terminate");

    assertEquals(0, errors.get(), "Errors occurred during concurrent buffer allocation");
    assertEquals(0, allocator.getAllocatedMemory(), "Memory leak detected");
  }

  /**
   * Test concurrent buffer retain and release operations.
   *
   * <p>This test stresses the reference counting mechanism by having multiple threads concurrently
   * retain and release the same buffer.
   */
  @Test
  public void testConcurrentBufferRetainRelease() throws InterruptedException {
    final int numThreads = 16;
    final int operationsPerThread = 1000;
    final int bufferSize = 1024;

    ArrowBuf sharedBuffer = allocator.buffer(bufferSize);

    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(numThreads);
    AtomicInteger errors = new AtomicInteger(0);

    for (int i = 0; i < numThreads; i++) {
      executor.submit(
          () -> {
            try {
              startLatch.await();

              for (int j = 0; j < operationsPerThread; j++) {
                sharedBuffer.getReferenceManager().retain();
                // Simulate some work
                Thread.yield();
                sharedBuffer.getReferenceManager().release();
              }
            } catch (Exception e) {
              e.printStackTrace();
              errors.incrementAndGet();
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown();
    assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Test timed out");
    executor.shutdown();
    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Executor did not terminate");

    assertEquals(0, errors.get(), "Errors occurred during concurrent retain/release");
    assertEquals(
        1, sharedBuffer.getReferenceManager().getRefCount(), "Reference count should be 1");

    sharedBuffer.close();
    assertEquals(0, allocator.getAllocatedMemory(), "Memory leak detected");
  }
}
