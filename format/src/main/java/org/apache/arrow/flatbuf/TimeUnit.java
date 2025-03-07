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
public final class TimeUnit {
  private TimeUnit() { }
  public static final short SECOND = 0;
  public static final short MILLISECOND = 1;
  public static final short MICROSECOND = 2;
  public static final short NANOSECOND = 3;

  public static final String[] names = { "SECOND", "MILLISECOND", "MICROSECOND", "NANOSECOND", };

  public static String name(int e) { return names[e]; }
}
