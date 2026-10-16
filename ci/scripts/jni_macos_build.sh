#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# This script is like java_jni_build.sh, but is meant for release artifacts
# and hardcodes assumptions about the environment it is being run in.

set -euo pipefail

# shellcheck source=ci/scripts/util_log.sh
. "$(dirname "${0}")/util_log.sh"

github_actions_group_begin "Prepare arguments"
source_dir="$(cd "${1}" && pwd)"
arrow_dir="$(cd "${2}" && pwd)"
build_dir="${3}"
normalized_arch="$(arch)"
case "${normalized_arch}" in
arm64)
  normalized_arch=aarch_64
  ;;
i386)
  normalized_arch=x86_64
  ;;
esac
# The directory where the final binaries will be stored when scripts finish
dist_dir="${4}"
github_actions_group_end

github_actions_group_begin "Clear output directories and leftovers"
rm -rf "${build_dir}"
rm -rf "${dist_dir}"

mkdir -p "${build_dir}"
build_dir="$(cd "${build_dir}" && pwd)"
github_actions_group_end

: "${ARROW_USE_CCACHE:=ON}"
: "${CMAKE_BUILD_TYPE:=release}"
: "${CMAKE_UNITY_BUILD:=OFF}"
if [ "${ARROW_USE_CCACHE}" == "ON" ]; then
  github_actions_group_begin "ccache statistics before build"
  ccache -sv 2>/dev/null || ccache -s
  github_actions_group_end
fi

github_actions_group_begin "Building Arrow C++ libraries"
install_dir="${build_dir}/cpp-install"

export ARROW_BUILD_TESTS=OFF

: "${ARROW_ACERO:=ON}"
export ARROW_ACERO
export ARROW_DATASET=ON
export ARROW_GANDIVA=ON
export ARROW_ORC=ON
export ARROW_PARQUET=ON
: "${ARROW_S3:=ON}"
export ARROW_S3

export AWS_EC2_METADATA_DISABLED=TRUE

# Determine vcpkg triplet based on architecture
vcpkg_arch="$(arch)"
case "${vcpkg_arch}" in
arm64)
  vcpkg_triplet="arm64-osx"
  ;;
i386 | x86_64)
  vcpkg_triplet="x64-osx"
  ;;
*)
  vcpkg_triplet="arm64-osx"
  ;;
esac

# Set LLVM_DIR to point to vcpkg-installed LLVM if VCPKG_ROOT_LOCAL is set
llvm_dir_arg=""
gandiva_cxx_flags=""
osx_sysroot_arg=""
re2_source_arg="-Dre2_SOURCE=BUNDLED"
absl_source_arg="-Dabsl_SOURCE=BUNDLED"
protobuf_args=()
if [ -n "${VCPKG_ROOT_LOCAL:-}" ]; then
  vcpkg_installed="${VCPKG_ROOT_LOCAL}/installed/${vcpkg_triplet}"
  llvm_cmake_dir="${vcpkg_installed}/share/llvm"
  if [ -d "${llvm_cmake_dir}" ]; then
    # Use LLVM_ROOT, not LLVM_DIR: Arrow's FindLLVMAlt tries each supported LLVM
    # version newest-first, and the first mismatch resets LLVM_DIR to NOTFOUND.
    llvm_dir_arg="-DLLVM_ROOT=${vcpkg_installed}"

    # vcpkg's clang needs to know where to find system headers
    # Arrow's GandivaAddBitcode.cmake uses CMAKE_OSX_SYSROOT to set SDKROOT env var
    sdk_path="$(xcrun --show-sdk-path)"
    if [ -d "${sdk_path}" ]; then
      osx_sysroot_arg="-DCMAKE_OSX_SYSROOT=${sdk_path}"
    fi

    # Also pass the C++ standard library include path via ARROW_GANDIVA_PC_CXX_FLAGS
    xcode_path="$(xcode-select -p)"
    cxx_include_path="${xcode_path}/Toolchains/XcodeDefault.xctoolchain/usr/include/c++/v1"
    if [ -d "${cxx_include_path}" ]; then
      gandiva_cxx_flags="-DARROW_GANDIVA_PC_CXX_FLAGS=-stdlib=libc++;-isystem;${cxx_include_path}"
    fi

    # Use vcpkg's RE2 since it's installed as a dependency of LLVM
    # This ensures ABI compatibility - vcpkg's RE2 uses std::string_view API
    # which matches what vcpkg's LLVM and Abseil expect
    re2_cmake_dir="${vcpkg_installed}/share/re2"
    if [ -d "${re2_cmake_dir}" ]; then
      re2_source_arg="-Dre2_ROOT=${vcpkg_installed}"
    fi

    # Use vcpkg's static Abseil and Protobuf, as the Linux build does. vcpkg's RE2
    # is built against this Abseil, and Arrow 26 does not export the Abseil
    # dependency of a bundled Protobuf to static consumers such as the JNI libs.
    # protobuf_ROOT is for Arrow's config-mode lookup, Protobuf_ROOT for
    # FindProtobuf in the JNI build.
    if [ -d "${vcpkg_installed}/share/absl" ]; then
      absl_source_arg="-Dabsl_ROOT=${vcpkg_installed}"
    fi
    if [ -d "${vcpkg_installed}/share/protobuf" ]; then
      protobuf_args=("-Dprotobuf_ROOT=${vcpkg_installed}" "-DProtobuf_ROOT=${vcpkg_installed}")
    fi
  fi
fi

cmake \
  -S "${arrow_dir}/cpp" \
  -B "${build_dir}/cpp" \
  -DARROW_ACERO="${ARROW_ACERO}" \
  -DARROW_BUILD_SHARED=OFF \
  -DARROW_BUILD_TESTS="${ARROW_BUILD_TESTS}" \
  -DARROW_CSV="${ARROW_DATASET}" \
  -DARROW_DATASET="${ARROW_DATASET}" \
  -DARROW_SUBSTRAIT="${ARROW_DATASET}" \
  -DARROW_DEPENDENCY_USE_SHARED=OFF \
  -DARROW_GANDIVA="${ARROW_GANDIVA}" \
  -DARROW_GANDIVA_STATIC_LIBSTDCPP=ON \
  -DARROW_JSON="${ARROW_DATASET}" \
  -DARROW_ORC="${ARROW_ORC}" \
  -DARROW_PARQUET="${ARROW_PARQUET}" \
  -DARROW_S3="${ARROW_S3}" \
  -DARROW_USE_CCACHE="${ARROW_USE_CCACHE}" \
  -DAWSSDK_SOURCE=BUNDLED \
  -DORC_SOURCE=BUNDLED \
  -DCMAKE_BUILD_TYPE="${CMAKE_BUILD_TYPE}" \
  -DCMAKE_INSTALL_PREFIX="${install_dir}" \
  -DCMAKE_UNITY_BUILD="${CMAKE_UNITY_BUILD}" \
  -DGTest_SOURCE=BUNDLED \
  -Dsimdjson_SOURCE=BUNDLED \
  -Duriparser_SOURCE=BUNDLED \
  "${llvm_dir_arg}" \
  "${osx_sysroot_arg}" \
  "${gandiva_cxx_flags}" \
  -DPARQUET_BUILD_EXAMPLES=OFF \
  -DPARQUET_BUILD_EXECUTABLES=OFF \
  -DPARQUET_REQUIRE_ENCRYPTION=OFF \
  "${re2_source_arg}" \
  "${absl_source_arg}" \
  ${protobuf_args[@]+"${protobuf_args[@]}"} \
  -GNinja
cmake --build "${build_dir}/cpp" --target install
github_actions_group_end

if [ "${ARROW_RUN_TESTS:-}" == "ON" ]; then
  github_actions_group_begin "Running Arrow C++ libraries tests"
  # MinIO is required
  exclude_tests="arrow-s3fs-test"
  # unstable
  exclude_tests="${exclude_tests}|arrow-acero-asof-join-node-test"
  exclude_tests="${exclude_tests}|arrow-acero-hash-join-node-test"
  ctest \
    --exclude-regex "${exclude_tests}" \
    --label-regex unittest \
    --output-on-failure \
    --parallel "$(sysctl -n hw.ncpu)" \
    --test-dir "${build_dir}/cpp" \
    --timeout 300
  github_actions_group_end
fi

# Pass paths to dependencies so the JNI build can find them
# Build up the JNI CMake args based on what's available
jni_cmake_args="${llvm_dir_arg}"

# Protobuf for the JNI build: the vcpkg one if the C++ build used it. Otherwise
# Arrow built it via FetchContent; use its build and source trees directly as
# upstream does (installing it via the protobuf_fc target fails for Protobuf 31,
# whose install script expects protoc-gen-upb, which is not built).
# Older Arrow versions used ExternalProject (protobuf_ep-install).
if [ ${#protobuf_args[@]} -gt 0 ]; then
  jni_cmake_args="${jni_cmake_args} ${protobuf_args[*]}"
elif [ -d "${build_dir}/cpp/_deps/protobuf-src" ]; then
  jni_cmake_args="${jni_cmake_args} -DProtobuf_ROOT=${build_dir}/cpp/_deps/protobuf-build"
  jni_cmake_args="${jni_cmake_args} -DProtobuf_SRC_ROOT_FOLDER=${build_dir}/cpp/_deps/protobuf-src"
elif [ -d "${build_dir}/cpp/protobuf_ep-install" ]; then
  jni_cmake_args="${jni_cmake_args} -DProtobuf_ROOT=${build_dir}/cpp/protobuf_ep-install"
fi

# RE2 path for the JNI build - prefer vcpkg's RE2 if we used it for the C++ build,
# otherwise fall back to bundled RE2 if available
if [ -n "${VCPKG_ROOT_LOCAL:-}" ]; then
  vcpkg_re2_dir="${VCPKG_ROOT_LOCAL}/installed/${vcpkg_triplet}"
  if [ -d "${vcpkg_re2_dir}/share/re2" ]; then
    jni_cmake_args="${jni_cmake_args} -Dre2_ROOT=${vcpkg_re2_dir}"
  fi
elif [ -d "${build_dir}/cpp/re2_ep-install" ]; then
  jni_cmake_args="${jni_cmake_args} -Dre2_ROOT=${build_dir}/cpp/re2_ep-install"
fi

# Point the JNI build at the same Abseil the C++ build used. With bundled
# Protobuf, its headers need the bundled Abseil headers (see upstream GH-1242).
if [ "${absl_source_arg}" != "-Dabsl_SOURCE=BUNDLED" ]; then
  jni_cmake_args="${jni_cmake_args} ${absl_source_arg}"
elif [ -d "${build_dir}/cpp/_deps/absl-src/absl" ]; then
  jni_cmake_args="${jni_cmake_args} -DARROW_JAVA_JNI_ABSL_INCLUDE_DIR=${build_dir}/cpp/_deps/absl-src"
fi

export JAVA_JNI_CMAKE_ARGS="${jni_cmake_args}"
"${source_dir}/ci/scripts/jni_build.sh" \
  "${source_dir}" \
  "${install_dir}" \
  "${build_dir}" \
  "${dist_dir}"

if [ "${ARROW_USE_CCACHE}" == "ON" ]; then
  github_actions_group_begin "ccache statistics after build"
  ccache -sv 2>/dev/null || ccache -s
  github_actions_group_end
fi

github_actions_group_begin "Checking shared dependencies for libraries"
pushd "${dist_dir}"
archery linking check-dependencies \
  --allow CoreFoundation \
  --allow Network \
  --allow Security \
  --allow libSystem \
  --allow libarrow_cdata_jni \
  --allow libarrow_dataset_jni \
  --allow libarrow_orc_jni \
  --allow libc++ \
  --allow libcurl \
  --allow libgandiva_jni \
  --allow libncurses \
  --allow libobjc \
  --allow libz \
  --allow libz3 \
  "arrow_cdata_jni/${normalized_arch}/libarrow_cdata_jni.dylib" \
  "arrow_dataset_jni/${normalized_arch}/libarrow_dataset_jni.dylib" \
  "arrow_orc_jni/${normalized_arch}/libarrow_orc_jni.dylib" \
  "gandiva_jni/${normalized_arch}/libgandiva_jni.dylib"
popd
github_actions_group_end
