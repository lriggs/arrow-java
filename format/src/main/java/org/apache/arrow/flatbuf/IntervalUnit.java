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
package org.apache.arrow.flatbuf;

@SuppressWarnings("unused")
public final class IntervalUnit {
  private IntervalUnit() { }
  public static final short YEAR_MONTH = 0;
  public static final short DAY_TIME = 1;
  public static final short MONTH_DAY_NANO = 2;

  public static final String[] names = { "YEAR_MONTH", "DAY_TIME", "MONTH_DAY_NANO", };

  public static String name(int e) { return names[e]; }
}
