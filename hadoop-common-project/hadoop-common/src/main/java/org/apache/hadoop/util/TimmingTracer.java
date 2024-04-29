/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.util;


import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class TimmingTracer {

  private final Map<String, Long> memo = new LinkedHashMap<>();

  private final long startTime;

  public TimmingTracer() {
    this.startTime = System.currentTimeMillis();
  }

  public <T, E extends Exception> T throwingRun(String name, ThrowingSupplier<T, E> supplier)
      throws E {
    long start = System.currentTimeMillis();
    try {
      return supplier.get();
    } finally {
      memo.put(name, System.currentTimeMillis() - start);
    }
  }

  public <E extends Exception> void throwingRun(String name, ThrowingProcedure<E> procedure)
      throws E {
    long start = System.currentTimeMillis();
    try {
      procedure.invoke();
    } finally {
      memo.put(name, System.currentTimeMillis() - start);
    }
  }

  @Override
  public String toString() {
    memo.put("Total", System.currentTimeMillis() - startTime);
    return memo.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> entry.getKey() + "=" + entry.getValue())
        .collect(Collectors.joining(", ", "[", "]"));
  }

  public interface ThrowingSupplier<T, E extends Exception> {

    T get() throws E;

  }

  public interface ThrowingProcedure<E extends Exception> {

    void invoke() throws E;

  }

}
