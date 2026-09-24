package com.techdelivery.r10.protocol.wire

import java.util.UUID

/**
 * GATT services/characteristics on the R10 (DESIGN §4). Kept in :protocol so the
 * device facade can drive the §7.1 setup order against a fake transport.
 */
object GattUuids {
    // Device interface service (the data channel)
    val DEVICE_INTERFACE_SERVICE: UUID = UUID.fromString("6A4E2800-667B-11E3-949A-0800200C9A66")
    val DATA_NOTIFIER: UUID = UUID.fromString("6A4E2812-667B-11E3-949A-0800200C9A66")
    val DATA_WRITER: UUID = UUID.fromString("6A4E2822-667B-11E3-949A-0800200C9A66")

    // Launch monitor service (measurement / control / status)
    val LM_SERVICE: UUID = UUID.fromString("6A4E3400-667B-11E3-949A-0800200C9A66")
    val MEASUREMENT: UUID = UUID.fromString("6A4E3401-667B-11E3-949A-0800200C9A66")
    val CONTROL_POINT: UUID = UUID.fromString("6A4E3402-667B-11E3-949A-0800200C9A66")
    val STATUS: UUID = UUID.fromString("6A4E3403-667B-11E3-949A-0800200C9A66")

    // Standard services
    val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    val DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val MODEL_NAME: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    val SERIAL_NUMBER: UUID = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
    val FIRMWARE_REV: UUID = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")
}
