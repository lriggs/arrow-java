/*
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

package org.apache.arrow.flight.auth;

import java.util.Iterator;

public interface ServerAuthHandler {

  public boolean isValid(byte[] token);

  public boolean authenticate(ServerAuthSender outgoing, Iterator<byte[]> incoming);

  public interface ServerAuthSender {

    public void send(byte[] payload);

    public void onError(String message, Throwable cause);

  }

  ServerAuthHandler NO_OP = new ServerAuthHandler() {

    @Override
    public boolean isValid(byte[] token) {
      return true;
    }

    @Override
    public boolean authenticate(ServerAuthSender outgoing, Iterator<byte[]> incoming) {
      return true;
    }
  };
}
