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

import com.google.flatbuffers.BaseVector;
import com.google.flatbuffers.BooleanVector;
import com.google.flatbuffers.ByteVector;
import com.google.flatbuffers.Constants;
import com.google.flatbuffers.DoubleVector;
import com.google.flatbuffers.FlatBufferBuilder;
import com.google.flatbuffers.FloatVector;
import com.google.flatbuffers.IntVector;
import com.google.flatbuffers.LongVector;
import com.google.flatbuffers.ShortVector;
import com.google.flatbuffers.StringVector;
import com.google.flatbuffers.Struct;
import com.google.flatbuffers.Table;
import com.google.flatbuffers.UnionVector;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Timestamp is a 64-bit signed integer representing an elapsed time since a
 * fixed epoch, stored in either of four units: seconds, milliseconds,
 * microseconds or nanoseconds, and is optionally annotated with a timezone.
 *
 * Timestamp values do not include any leap seconds (in other words, all
 * days are considered 86400 seconds long).
 *
 * Timestamps with a non-empty timezone
 * ------------------------------------
 *
 * If a Timestamp column has a non-empty timezone value, its epoch is
 * 1970-01-01 00:00:00 (January 1st 1970, midnight) in the *UTC* timezone
 * (the Unix epoch), regardless of the Timestamp's own timezone.
 *
 * Therefore, timestamp values with a non-empty timezone correspond to
 * physical points in time together with some additional information about
 * how the data was obtained and/or how to display it (the timezone).
 *
 *   For example, the timestamp value 0 with the timezone string "Europe/Paris"
 *   corresponds to "January 1st 1970, 00h00" in the UTC timezone, but the
 *   application may prefer to display it as "January 1st 1970, 01h00" in
 *   the Europe/Paris timezone (which is the same physical point in time).
 *
 * One consequence is that timestamp values with a non-empty timezone
 * can be compared and ordered directly, since they all share the same
 * well-known point of reference (the Unix epoch).
 *
 * Timestamps with an unset / empty timezone
 * -----------------------------------------
 *
 * If a Timestamp column has no timezone value, its epoch is
 * 1970-01-01 00:00:00 (January 1st 1970, midnight) in an *unknown* timezone.
 *
 * Therefore, timestamp values without a timezone cannot be meaningfully
 * interpreted as physical points in time, but only as calendar / clock
 * indications ("wall clock time") in an unspecified timezone.
 *
 *   For example, the timestamp value 0 with an empty timezone string
 *   corresponds to "January 1st 1970, 00h00" in an unknown timezone: there
 *   is not enough information to interpret it as a well-defined physical
 *   point in time.
 *
 * One consequence is that timestamp values without a timezone cannot
 * be reliably compared or ordered, since they may have different points of
 * reference.  In particular, it is *not* possible to interpret an unset
 * or empty timezone as the same as "UTC".
 *
 * Conversion between timezones
 * ----------------------------
 *
 * If a Timestamp column has a non-empty timezone, changing the timezone
 * to a different non-empty value is a metadata-only operation:
 * the timestamp values need not change as their point of reference remains
 * the same (the Unix epoch).
 *
 * However, if a Timestamp column has no timezone value, changing it to a
 * non-empty value requires to think about the desired semantics.
 * One possibility is to assume that the original timestamp values are
 * relative to the epoch of the timezone being set; timestamp values should
 * then adjusted to the Unix epoch (for example, changing the timezone from
 * empty to "Europe/Paris" would require converting the timestamp values
 * from "Europe/Paris" to "UTC", which seems counter-intuitive but is
 * nevertheless correct).
 *
 * Guidelines for encoding data from external libraries
 * ----------------------------------------------------
 *
 * Date & time libraries often have multiple different data types for temporal
 * data. In order to ease interoperability between different implementations the
 * Arrow project has some recommendations for encoding these types into a Timestamp
 * column.
 *
 * An "instant" represents a physical point in time that has no relevant timezone
 * (for example, astronomical data). To encode an instant, use a Timestamp with
 * the timezone string set to "UTC", and make sure the Timestamp values
 * are relative to the UTC epoch (January 1st 1970, midnight).
 *
 * A "zoned date-time" represents a physical point in time annotated with an
 * informative timezone (for example, the timezone in which the data was
 * recorded).  To encode a zoned date-time, use a Timestamp with the timezone
 * string set to the name of the timezone, and make sure the Timestamp values
 * are relative to the UTC epoch (January 1st 1970, midnight).
 *
 *  (There is some ambiguity between an instant and a zoned date-time with the
 *   UTC timezone.  Both of these are stored the same in Arrow.  Typically,
 *   this distinction does not matter.  If it does, then an application should
 *   use custom metadata or an extension type to distinguish between the two cases.)
 *
 * An "offset date-time" represents a physical point in time combined with an
 * explicit offset from UTC.  To encode an offset date-time, use a Timestamp
 * with the timezone string set to the numeric timezone offset string
 * (e.g. "+03:00"), and make sure the Timestamp values are relative to
 * the UTC epoch (January 1st 1970, midnight).
 *
 * A "naive date-time" (also called "local date-time" in some libraries)
 * represents a wall clock time combined with a calendar date, but with
 * no indication of how to map this information to a physical point in time.
 * Naive date-times must be handled with care because of this missing
 * information, and also because daylight saving time (DST) may make
 * some values ambiguous or nonexistent. A naive date-time may be
 * stored as a struct with Date and Time fields. However, it may also be
 * encoded into a Timestamp column with an empty timezone. The timestamp
 * values should be computed "as if" the timezone of the date-time values
 * was UTC; for example, the naive date-time "January 1st 1970, 00h00" would
 * be encoded as timestamp value 0.
 */
@SuppressWarnings("unused")
public final class Timestamp extends Table {
  public static void ValidateVersion() { Constants.FLATBUFFERS_25_2_10(); }
  public static Timestamp getRootAsTimestamp(ByteBuffer _bb) { return getRootAsTimestamp(_bb, new Timestamp()); }
  public static Timestamp getRootAsTimestamp(ByteBuffer _bb, Timestamp obj) { _bb.order(ByteOrder.LITTLE_ENDIAN); return (obj.__assign(_bb.getInt(_bb.position()) + _bb.position(), _bb)); }
  public void __init(int _i, ByteBuffer _bb) { __reset(_i, _bb); }
  public Timestamp __assign(int _i, ByteBuffer _bb) { __init(_i, _bb); return this; }

  public short unit() { int o = __offset(4); return o != 0 ? bb.getShort(o + bb_pos) : 0; }
  /**
   * The timezone is an optional string indicating the name of a timezone,
   * one of:
   *
   * * As used in the Olson timezone database (the "tz database" or
   *   "tzdata"), such as "America/New_York".
   * * An absolute timezone offset of the form "+XX:XX" or "-XX:XX",
   *   such as "+07:30".
   *
   * Whether a timezone string is present indicates different semantics about
   * the data (see above).
   */
  public String timezone() { int o = __offset(6); return o != 0 ? __string(o + bb_pos) : null; }
  public ByteBuffer timezoneAsByteBuffer() { return __vector_as_bytebuffer(6, 1); }
  public ByteBuffer timezoneInByteBuffer(ByteBuffer _bb) { return __vector_in_bytebuffer(_bb, 6, 1); }

  public static int createTimestamp(FlatBufferBuilder builder,
      short unit,
      int timezoneOffset) {
    builder.startTable(2);
    Timestamp.addTimezone(builder, timezoneOffset);
    Timestamp.addUnit(builder, unit);
    return Timestamp.endTimestamp(builder);
  }

  public static void startTimestamp(FlatBufferBuilder builder) { builder.startTable(2); }
  public static void addUnit(FlatBufferBuilder builder, short unit) { builder.addShort(0, unit, 0); }
  public static void addTimezone(FlatBufferBuilder builder, int timezoneOffset) { builder.addOffset(1, timezoneOffset, 0); }
  public static int endTimestamp(FlatBufferBuilder builder) {
    int o = builder.endTable();
    return o;
  }

  public static final class Vector extends BaseVector {
    public Vector __assign(int _vector, int _element_size, ByteBuffer _bb) { __reset(_vector, _element_size, _bb); return this; }

    public Timestamp get(int j) { return get(new Timestamp(), j); }
    public Timestamp get(Timestamp obj, int j) {  return obj.__assign(__indirect(__element(j), bb), bb); }
  }
}
