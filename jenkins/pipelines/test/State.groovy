/*
 * Copyright (c) 2026, NVIDIA CORPORATION. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

class GlobalState {
    static def uploadResultStageNames = []
    static def stageAttemptEstimateMs = [:]
    static def stageAttemptEstimateDetails = [:]
    static def maintenanceSkips = []

    // HOST_NODE_NAME to starting port section map
    // This map maintains the next available starting port for each host node
    // to avoid port conflicts when running parallel tests on the same node.
    // Key: HOST_NODE_NAME (e.g., "node-01.cluster.local")
    // Value: Next available starting port number for that node
    static def hostNodePortMap = [:]

    // Port allocation configuration
    static final int BASE_PORT = 10000           // Base starting port
    static final int PORT_SECTION_SIZE = 1000    // Number of ports per section/stage
    static final int MAX_PORT = 32000            // Maximum port number to avoid system ports
}

return GlobalState
