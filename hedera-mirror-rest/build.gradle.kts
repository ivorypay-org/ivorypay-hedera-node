/*
 * Copyright (C) 2022-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

description = "Hedera Mirror Node REST API"

plugins {
    id("docker-conventions")
    id("javascript-conventions")
}

// Works around an implicit task dependency due to an output file of monitor dockerBuild present in
// the input file list of rest dockerBuild due to it being in a sub-folder.
tasks.dockerBuild { dependsOn(":rest:monitoring:dockerBuild") }
