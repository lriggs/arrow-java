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

import org.apache.arrow.gandiva.exceptions.GandivaException;

/**
 * A long-lived JIT session that compiles Gandiva's base IR (precompiled bitcode, DecimalIR,
 * TimestampIR) exactly once. Projectors and Filters created against this session only compile their
 * per-query expression function, eliminating repeated base IR compilation overhead across many
 * queries.
 *
 * <p>Note: The default JIT session is managed automatically by {@link JniLoader} and used
 * transparently by all {@link Projector#make} and {@link Filter#make} calls. This class is an
 * advanced API for callers that need explicit session lifecycle control — for example, to reclaim
 * native memory accumulated from many distinct expression shapes by closing and re-creating the
 * session.
 *
 * <p>Callers must ensure that all {@link Projector} and {@link Filter} instances built against a
 * session are closed before closing the session itself.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * try (JITSession session = JITSession.make()) {
 *   for (Query q : queries) {
 *     Projector p = Projector.make(schema, exprs, session);
 *     // ... evaluate ...
 *     p.close();
 *   }
 * }
 * }</pre>
 */
public class JITSession implements AutoCloseable {

  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(JITSession.class);

  private final JniWrapper wrapper;
  private final long sessionId;
  private boolean closed;

  private JITSession(JniWrapper wrapper, long sessionId) {
    this.wrapper = wrapper;
    this.sessionId = sessionId;
    this.closed = false;
  }

  /**
   * Create a JITSession using the default configuration.
   *
   * @return a new JITSession
   */
  public static JITSession make() throws GandivaException {
    return make(JniLoader.getDefaultConfiguration());
  }

  /**
   * Create a JITSession using the specified ConfigOptions.
   *
   * @param configOptions configuration options for the session
   * @return a new JITSession
   */
  public static JITSession make(ConfigurationBuilder.ConfigOptions configOptions)
      throws GandivaException {
    return make(JniLoader.getConfiguration(configOptions));
  }

  /**
   * Create a JITSession from a raw configurationId handle.
   *
   * @param configurationId raw configuration handle obtained from ConfigurationBuilder
   * @return a new JITSession
   */
  public static JITSession make(long configurationId) throws GandivaException {
    JniWrapper wrapper = JniLoader.getInstance().getWrapper();
    long sessionId = wrapper.buildJITSession(configurationId);
    logger.debug("Created JITSession with id {}", sessionId);
    return new JITSession(wrapper, sessionId);
  }

  /** Returns the native session handle. Package-private — used by Projector and Filter. */
  long getSessionId() {
    return sessionId;
  }

  /**
   * Releases the native LLJIT session and all memory it holds. Projectors and Filters already built
   * against this session must be closed before calling this method.
   */
  @Override
  public void close() throws GandivaException {
    if (this.closed) {
      return;
    }
    wrapper.closeJITSession(this.sessionId);
    this.closed = true;
    logger.debug("Closed JITSession with id {}", sessionId);
  }
}
