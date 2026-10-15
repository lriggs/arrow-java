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

# Stages the static Arrow C++ Parquet libraries, headers and pkg-config
# metadata of an Arrow C++ installation into
# <dist_dir>/arrow_parquet/<os>-<arch>, then links and runs a smoke test
# against the staged package.
#
# Usage: arrow_parquet_package.sh <arrow_dir> <arrow_install_dir> <dist_dir> [<pkgconfig_dir>]
#   arrow_dir          Arrow source checkout (LICENSE.txt, NOTICE.txt, git revision)
#   arrow_install_dir  Arrow C++ installation prefix
#   dist_dir           Directory that receives arrow_parquet/<os>-<arch>
#   pkgconfig_dir      Optional pkg-config directory of external dependencies (e.g. vcpkg)

set -euo pipefail

# shellcheck source=ci/scripts/util_log.sh
. "$(dirname "${0}")/util_log.sh"

github_actions_group_begin "Stage Arrow Parquet package"
arrow_dir=${1}
arrow_install_dir=${2}
dist_dir=${3}
dependency_pkgconfig_dir=${4:-}
cxx=${CXX:-c++}

case "$(uname)" in
Linux) os=linux ;;
Darwin) os=osx ;;
*)
  echo "Unsupported OS: $(uname)" >&2
  exit 1
  ;;
esac
arch=$(uname -m)
case "${arch}" in
arm64 | aarch64) arch=aarch_64 ;;
esac
package_dir="${dist_dir}/arrow_parquet/${os}-${arch}"
rm -rf "${package_dir}"
mkdir -p "${package_dir}/lib/pkgconfig" "${package_dir}/META-INF"
cp -R "${arrow_install_dir}/include" "${package_dir}/"
cp "${arrow_dir}/LICENSE.txt" "${package_dir}/META-INF/LICENSE"
cp "${arrow_dir}/NOTICE.txt" "${package_dir}/META-INF/NOTICE"
git -c safe.directory="${arrow_dir}" -C "${arrow_dir}" rev-parse HEAD >"${package_dir}/ARROW_CPP_REVISION"

parquet_metadata=$(find "${arrow_install_dir}" -type f -name parquet.pc -print -quit)
if [ -z "${parquet_metadata}" ]; then
  echo "Missing installed Parquet pkg-config metadata" >&2
  exit 1
fi
PKG_CONFIG_PATH="$(dirname "${parquet_metadata}"):${dependency_pkgconfig_dir:+${dependency_pkgconfig_dir}:}${PKG_CONFIG_PATH:-}"
export PKG_CONFIG_PATH
packages=(parquet arrow-compute)
# Without an external dependency installation, Arrow bundles these into
# libarrow_bundled_dependencies.a; probing would pick up unrelated host copies.
if [ -n "${dependency_pkgconfig_dir}" ]; then
  for dependency in re2 thrift liblz4 libzstd libutf8proc; do
    if pkg-config --exists "${dependency}"; then
      packages+=("${dependency}")
    fi
  done
fi
# Assign before splitting so that a pkg-config failure stops the script.
library_flags=$(pkg-config --static --libs-only-L "${packages[@]}")
read -ra library_dirs <<<"${library_flags}"
library_flags=$(pkg-config --static --libs "${packages[@]}")
read -ra libraries <<<"${library_flags}"
cpp_version=$(pkg-config --modversion parquet)

# Copy every static archive into the package and keep only system libraries
# as external link dependencies.
link_flags=()
for flag in "${libraries[@]}"; do
  case "${flag}" in
  -L*) continue ;;
  -lc | -lm | -ldl | -lpthread | -lrt | -lstdc++ | -lc++ | -lresolv)
    link_flags+=("${flag}")
    continue
    ;;
  -l*)
    archive=""
    for directory in "${library_dirs[@]}"; do
      candidate="${directory#-L}/lib${flag#-l}.a"
      if [ -f "${candidate}" ]; then
        archive="${candidate}"
        break
      fi
    done
    if [ -z "${archive}" ]; then
      archive=$("${cxx}" -print-file-name="lib${flag#-l}.a")
    fi
    if [ ! -f "${archive}" ]; then
      if [ "${os}" = osx ] && [[ "${flag}" = -lz || "${flag}" = -lcurl ]]; then
        link_flags+=("${flag}")
        continue
      fi
      echo "Missing static dependency: ${flag}" >&2
      exit 1
    fi
    cp "${archive}" "${package_dir}/lib/"
    ;;
  *.a)
    cp "${flag}" "${package_dir}/lib/"
    archive_name=$(basename "${flag}" .a)
    flag="-l${archive_name#lib}"
    ;;
  esac
  link_flags+=("${flag}")
done

cat >"${package_dir}/lib/pkgconfig/arrow-parquet.pc" <<EOF
prefix=\${pcfiledir}/../..
libdir=\${prefix}/lib
includedir=\${prefix}/include

Name: Arrow Parquet
Description: Static Arrow C++ Parquet libraries (C++20)
Version: ${cpp_version}
Libs: -L\${libdir} ${link_flags[*]}
Cflags: -I\${includedir} -DARROW_STATIC -DARROW_COMPUTE_STATIC -DPARQUET_STATIC
EOF
github_actions_group_end

github_actions_group_begin "Verify Arrow Parquet package"
smoke_dir=$(mktemp -d)
trap 'rm -rf "${smoke_dir}"' EXIT
cat >"${smoke_dir}/smoke.cc" <<'EOF'
#include <arrow/api.h>
#include <arrow/compute/initialize.h>
#include <arrow/io/api.h>
#include <parquet/arrow/reader.h>
#include <parquet/arrow/writer.h>

int main() {
  if (!arrow::compute::Initialize().ok()) return 1;
  arrow::Int64Builder builder;
  if (!builder.Append(42).ok()) return 1;
  auto values = builder.Finish().ValueOrDie();
  auto table = arrow::Table::Make(arrow::schema({arrow::field("value", arrow::int64())}), {values});
  for (auto codec : {parquet::Compression::SNAPPY, parquet::Compression::GZIP,
                     parquet::Compression::ZSTD}) {
    auto output = arrow::io::BufferOutputStream::Create().ValueOrDie();
    auto properties = parquet::WriterProperties::Builder().compression(codec)->build();
    if (!parquet::arrow::WriteTable(*table, arrow::default_memory_pool(), output, 1,
                                    properties).ok()) return 1;
    auto input = std::make_shared<arrow::io::BufferReader>(output->Finish().ValueOrDie());
    auto reader = parquet::arrow::OpenFile(input, arrow::default_memory_pool()).ValueOrDie();
    std::shared_ptr<arrow::Table> actual;
    if (!reader->ReadTable(&actual).ok() || !table->Equals(*actual)) return 1;
  }
}
EOF
# Resolve flags only from the staged package to prove it is self-contained.
smoke_flags=$(PKG_CONFIG_PATH='' PKG_CONFIG_LIBDIR="${package_dir}/lib/pkgconfig" \
  pkg-config --cflags --libs arrow-parquet)
read -ra smoke_options <<<"${smoke_flags}"
"${cxx}" -std=c++20 "${smoke_dir}/smoke.cc" "${smoke_options[@]}" -o "${smoke_dir}/smoke"
"${smoke_dir}/smoke"
github_actions_group_end
