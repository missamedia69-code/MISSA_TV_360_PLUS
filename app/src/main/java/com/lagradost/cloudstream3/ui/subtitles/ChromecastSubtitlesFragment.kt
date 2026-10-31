package com.lagradost.cloudstream3.ui.subtitles

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.android.gms.cast.TextTrackStyle.EDGE_TYPE_OUTLINE
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.Event
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val CHROME_SUBTITLE_KEY = "chome_subtitle_settings"

@Serializable
@Immutable
data class SaveChromeCaptionStyle(
    @JsonProperty("fontFamily") @SerialName("fontFamily") val fontFamily: String? = null,
    @JsonProperty("fontGenericFamily") @SerialName("fontGenericFamily") val fontGenericFamily: Int? = null,
    @JsonProperty("backgroundColor") @SerialName("backgroundColor") val backgroundColor: Int = 0x00FFFFFF,
    @JsonProperty("edgeColor") @SerialName("edgeColor") val edgeColor: Int = Color.BLACK,
    @JsonProperty("edgeType") @SerialName("edgeType") val edgeType: Int = EDGE_TYPE_OUTLINE,
    @JsonProperty("foregroundColor") @SerialName("foregroundColor") val foregroundColor: Int = Color.WHITE,
    @JsonProperty("fontScale") @SerialName("fontScale") val fontScale: Float = 1.05f,
    @JsonProperty("windowColor") @SerialName("windowColor") val windowColor: Int = Color.TRANSPARENT,
)

class ChromecastSubtitlesFragment {
    companion object {
        val applyStyleEvent = Event<SaveChromeCaptionStyle>()

        fun push(activity: Activity?, hide: Boolean = true) {
            activity.navigate(R.id.global_to_navigation_chrome_subtitles, Bundle().apply {
                putBoolean("hide", hide)
            })
        }

        fun Context.saveStyle(style: SaveChromeCaptionStyle) {
            if (chromeCastSubtitleState.value !== style) {
                chromeCastSubtitleState.value = style
            }
            this.setKey(CHROME_SUBTITLE_KEY, style)
        }

        fun getCurrentSavedStyle(): SaveChromeCaptionStyle {
            return chromeCastSubtitleState.value
        }

        val defaultChromeCastSubtitleState = SaveChromeCaptionStyle()
        val chromeCastSubtitleState = mutableStateOf(
            getKey<SaveChromeCaptionStyle>(CHROME_SUBTITLE_KEY) ?: defaultChromeCastSubtitleState
        )
    }
}
