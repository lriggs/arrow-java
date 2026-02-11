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

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Benchmarks for memory footprint of Arrow memory objects.
 *
 * <p>This benchmark measures the heap memory overhead of creating many ArrowBuf instances. The
 * optimizations using AtomicFieldUpdater instead of AtomicLong/AtomicInteger objects should reduce
 * memory overhead significantly.
 *
 * <p>Expected savings per instance: - ArrowBuf: 8 bytes (id field removed) - BufferLedger: 28
 * bytes (20 from AtomicInteger + 8 from ledgerId) - Accountant: 48 bytes (3 × 16 bytes from
 * AtomicLong objects)
 *
 * <p>For 1M ArrowBuf instances, this should save approximately 8 MB of heap memory.
 */
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgs = {"-Xms2g", "-Xmx2g"})
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
public class MemoryFootprintBenchmarks {

  private static final int NUM_BUFFERS = 100_000;
  private static final int BUFFER_SIZE = 1024;

  private RootAllocator allocator;
  private ArrowBuf[] buffers;
  private MemoryMXBean memoryBean;

  @Setup(Level.Trial)
  public void setup() {
    memoryBean = ManagementFactory.getMemoryMXBean();
    allocator = new RootAllocator((long) NUM_BUFFERS * BUFFER_SIZE);
    buffers = new ArrowBuf[NUM_BUFFERS];
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    for (int i = 0; i < NUM_BUFFERS; i++) {
      if (buffers[i] != null) {
        buffers[i].close();
      }
    }
    allocator.close();
  }

  /**
   * Benchmark that measures heap memory usage when creating many ArrowBuf instances.
   *
   * <p>This benchmark creates 100,000 ArrowBuf instances and measures the heap memory used. With
   * the AtomicFieldUpdater optimizations, we expect to save approximately 800 KB of heap memory
   * (8 bytes × 100,000 instances) just from removing the id field in ArrowBuf.
   */
  @Benchmark
  @BenchmarkMode(Mode.SingleShotTime)
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  public long measureArrowBufMemoryFootprint() {
    // Force GC before measurement
    System.gc();
    System.gc();
    System.gc();

    MemoryUsage heapBefore = memoryBean.getHeapMemoryUsage();
    long usedBefore = heapBefore.getUsed();

    // Allocate buffers
    for (int i = 0; i < NUM_BUFFERS; i++) {
      buffers[i] = allocator.buffer(BUFFER_SIZE);
    }

    // Force GC to get accurate measurement
    System.gc();
    System.gc();
    System.gc();

    MemoryUsage heapAfter = memoryBean.getHeapMemoryUsage();
    long usedAfter = heapAfter.getUsed();

    long memoryUsed = usedAfter - usedBefore;

    // Print memory usage for analysis
    System.out.printf(
        "Created %d ArrowBuf instances. Heap memory used: %d bytes (%.2f MB)%n",
        NUM_BUFFERS, memoryUsed, memoryUsed / (1024.0 * 1024.0));
    System.out.printf("Average memory per ArrowBuf: %.2f bytes%n", (double) memoryUsed / NUM_BUFFERS);

    return memoryUsed;
  }

  /**
   * Benchmark that measures allocation and deallocation performance.
   *
   * <p>This complements the memory footprint benchmark by measuring the time it takes to allocate
   * and deallocate buffers.
   */
  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public void measureAllocationPerformance() {
    ArrowBuf[] localBuffers = new ArrowBuf[1000];

    for (int i = 0; i < 1000; i++) {
      localBuffers[i] = allocator.buffer(BUFFER_SIZE);
    }

    for (int i = 0; i < 1000; i++) {
      localBuffers[i].close();
    }
  }

  public static void main(String[] args) throws RunnerException {
    Options opt =
        new OptionsBuilder()
            .include(MemoryFootprintBenchmarks.class.getSimpleName())
            .forks(1)
            .build();

    new Runner(opt).run();
  }
}

