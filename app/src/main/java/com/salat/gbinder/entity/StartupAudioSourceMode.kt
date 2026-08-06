package com.salat.gbinder.entity

import androidx.annotation.StringRes
import com.salat.gbinder.R

enum class StartupAudioSourceMode(
    val prefValue: String,
    @StringRes val title: Int
) {
    SYSTEM_DEFAULT("SYSTEM_DEFAULT", R.string.startup_audio_source_default),
    RADIO("RADIO", R.string.startup_audio_source_radio),
    BT("BT", R.string.startup_audio_source_bluetooth),
    ONLINE("ONLINE", R.string.startup_audio_source_media),
    USB("USB", R.string.startup_audio_source_usb);

    companion object {
        val DEFAULT = SYSTEM_DEFAULT

        fun fromPref(value: String?): StartupAudioSourceMode =
            entries.firstOrNull { it.prefValue == value } ?: DEFAULT
    }
}
