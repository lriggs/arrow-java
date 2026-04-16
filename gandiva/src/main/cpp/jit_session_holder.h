// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#pragma once

#include <memory>
#include <mutex>
#include <unordered_map>
#include <utility>

#include <gandiva/jit_session.h>

namespace gandiva {

class JITSessionHolder {
 public:
  static int64_t MapInsert(std::shared_ptr<JITSession> session) {
    g_mtx_.lock();

    int64_t result = session_id_++;
    session_map_.insert(
        std::pair<int64_t, std::shared_ptr<JITSession>>(result, session));

    g_mtx_.unlock();
    return result;
  }

  static void MapErase(int64_t session_id) {
    g_mtx_.lock();
    session_map_.erase(session_id);
    g_mtx_.unlock();
  }

  static std::shared_ptr<JITSession> MapLookup(int64_t session_id) {
    std::shared_ptr<JITSession> result = nullptr;

    try {
      result = session_map_.at(session_id);
    } catch (const std::out_of_range&) {
    }

    return result;
  }

 private:
  // map of JITSession objects created so far
  static std::unordered_map<int64_t, std::shared_ptr<JITSession>> session_map_;

  static std::mutex g_mtx_;

  // atomic counter for session ids
  static int64_t session_id_;
};

}  // namespace gandiva
