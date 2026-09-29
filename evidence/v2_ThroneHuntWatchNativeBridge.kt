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

package com.eltavine.duckdetector.features.nativeroot.data.native

import com.eltavine.duckdetector.core.native.NativeCollectionOutcome
import com.eltavine.duckdetector.core.native.NativeCollectionStatus
import com.eltavine.duckdetector.core.native.NativePayloadCodec
import com.eltavine.duckdetector.core.native.NativePayloadContract
import com.eltavine.duckdetector.core.native.NativeSnapshotCollector

// The collector makes the bridge a pure evidence adapter: the platform library, the JNI method,
// and the payload parser are no longer conflated into a single Boolean.
// 采集器把本桥接收敛成纯证据适配层，不再把平台库、JNI 方法和 payload 解析折叠成同一个布尔值。
open class ThroneHuntWatchNativeBridge(
    private val collector: NativeSnapshotCollector = NativeSnapshotCollector.Default,
) {

    fun installWatch(packageDirectory: String): ThroneHuntWatchSnapshot = collector.collect(
        readPayload = { nativeInstallWatch(packageDirectory) },
        parse = ::parseWatch,
        unavailable = { status ->
            ThroneHuntWatchSnapshot(
                collection = status,
                packageDirectory = packageDirectory,
            )
        },
    )

    open fun drainWatch(watchDescriptor: Int): ThroneHuntEventSummary = collector.collect(
        readPayload = { nativeDrainWatch(watchDescriptor) },
        parse = ::parseEvents,
        unavailable = { status -> ThroneHuntEventSummary(collection = status) },
    )

    fun resetWatch(watchDescriptor: Int) {
        // reset is cleanup only: a failed reset must not erase evidence already read from the fd.
        // reset 只做清理；失败不应抹掉已经从 fd 读到的证据。
        nativeResetWatch(watchDescriptor)
    }

    internal fun parseWatch(raw: String): ThroneHuntWatchSnapshot {
        if (raw.isBlank()) {
            return ThroneHuntWatchSnapshot(
                collection = NativeCollectionStatus.failed(
                    NativeCollectionOutcome.PAYLOAD_REJECTED,
                    IllegalArgumentException("Empty watch payload"),
                ),
            )
        }
        NativePayloadContract.requireKeys(
            raw,
            "WATCH_INSTALLED",
            "WATCH_DESCRIPTOR",
            "WATCH_ERRNO",
            "WATCH_PACKAGE_DIR",
            "WATCH_DETAIL",
        )

        var snapshot = ThroneHuntWatchSnapshot()
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains('=') }
            .forEach { line ->
                val key = line.substringBefore('=')
                val value = line.substringAfter('=')
                snapshot = when (key) {
                    "WATCH_INSTALLED" -> snapshot.copy(
                        watchInstalled = NativePayloadCodec.decodeFlag(value),
                    )

                    "WATCH_DESCRIPTOR" -> snapshot.copy(
                        watchDescriptor = value.toIntOrNull() ?: snapshot.watchDescriptor,
                    )

                    "WATCH_ERRNO" -> snapshot.copy(
                        errorNumber = value.toIntOrNull() ?: snapshot.errorNumber,
                    )

                    "WATCH_PACKAGE_DIR" -> snapshot.copy(
                        packageDirectory = NativePayloadCodec.decodeValue(value),
                    )

                    "WATCH_DETAIL" -> snapshot.copy(
                        detail = NativePayloadCodec.decodeValue(value),
                    )

                    else -> snapshot
                }
            }
        return snapshot
    }

    internal fun parseEvents(raw: String): ThroneHuntEventSummary {
        if (raw.isBlank()) {
            return ThroneHuntEventSummary(
                collection = NativeCollectionStatus.failed(
                    NativeCollectionOutcome.PAYLOAD_REJECTED,
                    IllegalArgumentException("Empty event payload"),
                ),
            )
        }
        NativePayloadContract.requireKeys(
            raw,
            "EVENT_DIRECTORY_OPEN",
            "EVENT_DIRECTORY_ACCESS",
            "EVENT_RAW",
            "EVENT_INVALID",
            "EVENT_DETAIL",
        )

        var summary = ThroneHuntEventSummary()
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains('=') }
            .forEach { line ->
                val key = line.substringBefore('=')
                val value = line.substringAfter('=')
                summary = when (key) {
                    "EVENT_DIRECTORY_OPEN" -> summary.copy(
                        directoryOpenCount = value.toIntOrNull() ?: summary.directoryOpenCount,
                    )

                    "EVENT_DIRECTORY_ACCESS" -> summary.copy(
                        directoryAccessCount = value.toIntOrNull() ?: summary.directoryAccessCount,
                    )

                    "EVENT_RAW" -> summary.copy(
                        rawEventCount = value.toIntOrNull() ?: summary.rawEventCount,
                    )

                    "EVENT_INVALID" -> summary.copy(
                        invalidCount = value.toIntOrNull() ?: summary.invalidCount,
                    )

                    "EVENT_DETAIL" -> summary.copy(
                        detail = NativePayloadCodec.decodeValue(value),
                    )

                    else -> summary
                }
            }
        return summary
    }

    private external fun nativeInstallWatch(packageDirectory: String): String

    private external fun nativeDrainWatch(watchDescriptor: Int): String

    private external fun nativeResetWatch(watchDescriptor: Int)
}
