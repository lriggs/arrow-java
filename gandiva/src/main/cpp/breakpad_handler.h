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

#ifndef GANDIVA_JNI_BREAKPAD_HANDLER_H
#define GANDIVA_JNI_BREAKPAD_HANDLER_H

#include <cstdlib>
#include <string>

#ifdef GANDIVA_ENABLE_BREAKPAD

#if defined(__linux__)
#include "client/linux/handler/exception_handler.h"
#elif defined(__APPLE__)
#include "client/mac/handler/exception_handler.h"
#elif defined(_WIN32)
#include "client/windows/handler/exception_handler.h"
#endif

namespace gandiva {

class BreakpadHandler {
 public:
  static BreakpadHandler& Instance() {
    static BreakpadHandler instance;
    return instance;
  }

  bool Initialize();
  void Shutdown();

 private:
  BreakpadHandler() : handler_(nullptr) {}
  ~BreakpadHandler() { Shutdown(); }

  BreakpadHandler(const BreakpadHandler&) = delete;
  BreakpadHandler& operator=(const BreakpadHandler&) = delete;

  static std::string GetMinidumpPath();

#if defined(__linux__)
  static bool DumpCallback(const google_breakpad::MinidumpDescriptor& descriptor,
                           void* context, bool succeeded);
#elif defined(__APPLE__)
  static bool DumpCallback(const char* dump_dir, const char* minidump_id,
                           void* context, bool succeeded);
#elif defined(_WIN32)
  static bool DumpCallback(const wchar_t* dump_path, const wchar_t* minidump_id,
                           void* context, EXCEPTION_POINTERS* exinfo,
                           MDRawAssertionInfo* assertion, bool succeeded);
#endif

  google_breakpad::ExceptionHandler* handler_;
};

}  // namespace gandiva

#else  // !GANDIVA_ENABLE_BREAKPAD

namespace gandiva {

// Stub implementation when Breakpad is disabled
class BreakpadHandler {
 public:
  static BreakpadHandler& Instance() {
    static BreakpadHandler instance;
    return instance;
  }
  bool Initialize() { return true; }
  void Shutdown() {}
};

}  // namespace gandiva

#endif  // GANDIVA_ENABLE_BREAKPAD

#endif  // GANDIVA_JNI_BREAKPAD_HANDLER_H

