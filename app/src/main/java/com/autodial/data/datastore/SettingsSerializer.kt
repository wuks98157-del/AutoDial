package com.autodial.data.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.autodial.UserSettings
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

object SettingsSerializer : Serializer<UserSettings> {
    override val defaultValue: UserSettings = UserSettings.newBuilder()
        .setDefaultHangupSeconds(25)
        .setDefaultCycles(10)
        .setDefaultTargetPackage("com.b3networks.bizphone")
        .setSpamModeSafetyCap(9999)
        .setInterDigitDelayMs(400)
        .setOverlayX(0)
        .setOverlayY(200)
        .setOnboardingCompletedAt(0L)
        .setVerboseLoggingEnabled(false)
        .build()

    override suspend fun readFrom(input: InputStream): UserSettings =
        try { UserSettings.parseFrom(input) }
        catch (e: InvalidProtocolBufferException) { throw CorruptionException("Cannot read proto", e) }

    override suspend fun writeTo(t: UserSettings, output: OutputStream) = t.writeTo(output)
}
