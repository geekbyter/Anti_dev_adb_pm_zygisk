/*
 * Copyright 2026 Duck Apps Contributor
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

package com.eltavine.duckdetector.capability.packageinventory.data

import com.eltavine.duckdetector.core.native.DuckDetectorNativeLibrary

/**
 * Checks for installed packages by stat-ing their data directories through raw syscalls.
 *
 * An error other than ENOENT counts as present, so the answer does not depend on
 * PackageManager's package visibility filtering.
 */
public class PackageDataDirectoryProbe {

    /** The packages whose data directory exists, or null when the native check could not run. */
    public fun statPackages(packageNames: List<String>): Set<String>? {
        if (packageNames.isEmpty()) {
            return emptySet()
        }
        // Asking the shared handle first is also what triggers the one-time load. Without this the
        // JNI call below would raise UnsatisfiedLinkError.
        if (!DuckDetectorNativeLibrary.isLoaded) {
            return null
        }
        return runCatching {
            nativeStatPackages(packageNames.toTypedArray())
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        }.getOrNull()
    }

    private external fun nativeStatPackages(packageNames: Array<String>): String
}
