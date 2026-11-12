/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "tensorrt_llm/common/cudaArchitectures.h"
#include "tensorrt_llm/common/stringUtils.h"

#include <cuda_runtime_api.h>
#include <dlfcn.h>
#include <string>

int main(int argc, char** argv)
{
    // This exported std::string API also checks the libstdc++ and inline namespace ABI.
    auto const values = tensorrt_llm::common::str2set("rnn,compatibility", ',');
    if (values.size() != 2 || values.count("rnn") != 1)
    {
        return 1;
    }
    if (argc < 3)
    {
        return 3;
    }
    void* library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (library == nullptr)
    {
        return 5;
    }
    using RuntimeVersionFunction = cudaError_t (*)(int*);
    auto runtimeVersionFunction = reinterpret_cast<RuntimeVersionFunction>(dlsym(library, "cudaRuntimeGetVersion"));
    int runtimeVersion = 0;
    if (runtimeVersionFunction == nullptr || runtimeVersionFunction(&runtimeVersion) != cudaSuccess
        || runtimeVersion / 1000 != CUDART_VERSION / 1000 || runtimeVersion < CUDART_VERSION)
    {
        dlclose(library);
        return 2;
    }
    dlclose(library);
    for (int i = 2; i < argc; ++i)
    {
        if (!tensorrt_llm::common::isCudaArchitectureBuilt(std::stoi(argv[i])))
        {
            return 4;
        }
    }
    return 0;
}
