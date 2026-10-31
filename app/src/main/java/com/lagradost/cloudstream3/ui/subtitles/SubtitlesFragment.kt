package com.lagradost.cloudstream3.ui.subtitles

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.util.TypedValue
import androidx.annotation.FontRes
import androidx.annotation.OptIn
import androidx.annotation.Px
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.CustomDecoder.Companion.setSubtitleAlignment
import com.lagradost.cloudstream3.ui.player.OutlineSpan
import com.lagradost.cloudstream3.ui.player.RoundedBackgroundColorSpan
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.Event
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

const val SUBTITLE_KEY = "subtitle_settings"
const val SUBTITLE_AUTO_SELECT_KEY = "subs_auto_select"
const val SUBTITLE_DOWNLOAD_KEY = "subs_auto_download"

@Serializable
enum class SubtitleFont(
    @FontRes val resource: Int,
    val label: String
) {
    @JsonProperty("Trebuchet")
    @SerialName("Trebuchet")
    Trebuchet(R.font.trebuchet_ms, "Trebuchet MS"),
    @JsonProperty("Netflix")
    @SerialName("Netflix")
    Netflix(R.font.netflix_sans, "Netflix Sans"),
    @JsonProperty("Google")
    @SerialName("Google")
    Google(R.font.google_sans, "Google Sans"),
    @JsonProperty("Open")
    @SerialName("Open")
    Open(R.font.open_sans, "Open Sans"),
    @JsonProperty("Futura")
    @SerialName("Futura")
    Futura(R.font.futura, "Futura"),
    @JsonProperty("Consola")
    @SerialName("Consola")
    Consola(R.font.consola, "Consola"),
    @JsonProperty("Gotham")
    @SerialName("Gotham")
    Gotham(R.font.gotham, "Gotham"),
    @JsonProperty("Lucida")
    @SerialName("Lucida")
    Lucida(R.font.lucida_grande, "Lucida Grande"),
    @JsonProperty("STIX")
    @SerialName("STIX")
    STIX(R.font.stix_general, "STIX General"),
    @JsonProperty("TimesNewRoman")
    @SerialName("TimesNewRoman")
    TimesNewRoman(R.font.times_new_roman, "Times New Roman"),
    @JsonProperty("Verdana")
    @SerialName("Verdana")
    Verdana(R.font.verdana, "Verdana"),
    @JsonProperty("Ubuntu")
    @SerialName("Ubuntu")
    Ubuntu(R.font.ubuntu_regular, "Ubuntu"),
    @JsonProperty("Comic")
    @SerialName("Comic")
    Comic(R.font.comic_sans, "Comic Sans"),
    @JsonProperty("Poppins")
    @SerialName("Poppins")
    Poppins(R.font.poppins_regular, "Poppins"),
    @JsonProperty("Custom")
    @SerialName("Custom")
    Custom(0, "Custom"),
}

@Serializable
@Immutable
data class SaveCaptionStyle(
    @JsonProperty("foregroundColor") @SerialName("foregroundColor") val foregroundColor: Int,
    @JsonProperty("backgroundColor") @SerialName("backgroundColor") val backgroundColor: Int,
    @JsonProperty("windowColor") @SerialName("windowColor") val windowColor: Int,
    @OptIn(UnstableApi::class)
    @JsonProperty("edgeType") @SerialName("edgeType") val edgeType: @CaptionStyleCompat.EdgeType Int,
    @JsonProperty("edgeColor") @SerialName("edgeColor") val edgeColor: Int,
    @JsonProperty("font") @SerialName("font") val font: SubtitleFont? = null,
    @JsonProperty("typefaceFilePath") @SerialName("typefaceFilePath") val typefaceFilePath: String?,
    @JsonProperty("elevation") @SerialName("elevation") val elevation: Int,
    @JsonProperty("fixedTextSize") @SerialName("fixedTextSize") val fixedTextSize: Float?,
    @Px @JsonProperty("edgeSize") @SerialName("edgeSize") val edgeSize: Float? = null,
    @JsonProperty("removeCaptions") @SerialName("removeCaptions") val removeCaptions: Boolean = false,
    @JsonProperty("removeBloat") @SerialName("removeBloat") val removeBloat: Boolean = true,
    @JsonProperty("upperCase") @SerialName("upperCase") val upperCase: Boolean = false,
    @JsonProperty("bold") @SerialName("bold") val bold: Boolean = false,
    @JsonProperty("italic") @SerialName("italic") val italic: Boolean = false,
    @JsonProperty("backgroundRadius") @SerialName("backgroundRadius") val backgroundRadius: Float? = null,
    @JsonProperty("alignment") @SerialName("alignment") val alignment: Int? = null,
)

const val DEF_SUBS_ELEVATION = 20

@OptIn(UnstableApi::class)
class SubtitlesFragment {
    companion object {
        val applyStyleEvent = Event<SaveCaptionStyle>()
        private val captionRegex = Regex("""(-\s?|)[\[({][\S\s]*?[])}]\s*""")

        fun setSubtitleViewStyle(
            view: SubtitleView?,
            data: SaveCaptionStyle,
            applyElevation: Boolean
        ) {
            if (view == null) return
            val ctx = view.context ?: return
            val style = ctx.fromSaveToStyle(data)
            view.setStyle(style)

            if (applyElevation) {
                view.setPadding(
                    view.paddingLeft, data.elevation.toPx, view.paddingRight, view.paddingBottom
                )
            }

            view.clipToPadding = false
            view.clipChildren = false

            val size = data.fixedTextSize ?: 25.0f
            view.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            view.setBottomPaddingFraction(0.0f)
        }

        fun Cue.Builder.applyStyle(style: SaveCaptionStyle): Cue.Builder {
            val edgeSize = style.edgeSize ?: 0.0f

            setTextSize(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)

            text?.let { text ->
                val textSpanBuilder = SpannableStringBuilder(text)

                if (edgeSize > 0.0f) {
                    textSpanBuilder.setSpan(
                        OutlineSpan(edgeSize), 0, textSpanBuilder.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                val typeface = when (style.bold to style.italic) {
                    (true to true) -> Typeface.BOLD_ITALIC
                    (true to false) -> Typeface.BOLD
                    (false to true) -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                }
                if (typeface != Typeface.NORMAL) {
                    val styleSpan = StyleSpan(typeface)
                    textSpanBuilder.setSpan(
                        styleSpan, 0, textSpanBuilder.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                val radius = style.backgroundRadius ?: 0.0f
                if (radius > 0.0f && style.backgroundColor != Color.TRANSPARENT) {
                    val styleSpan = RoundedBackgroundColorSpan(
                        style.backgroundColor,
                        this.textAlignment ?: Layout.Alignment.ALIGN_CENTER,
                        2.0F + radius * 0.5f,
                        radius
                    )
                    textSpanBuilder.setSpan(
                        styleSpan, 0, textSpanBuilder.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                if (style.removeCaptions) {
                    for (match in captionRegex.findAll(text).toList().asReversed()) {
                        textSpanBuilder.delete(match.range.first, match.range.last + 1)
                    }
                }

                this.setText(textSpanBuilder)
            }

            return this.setSubtitleAlignment(style.alignment)
        }

        private fun Context.fromSaveToStyle(data: SaveCaptionStyle): CaptionStyleCompat {
            return CaptionStyleCompat(
                data.foregroundColor,
                data.backgroundColor,
                data.windowColor,
                data.edgeType,
                data.edgeColor,
                data.typefaceFilePath?.let {
                    try {
                        Typeface.createFromFile(File(it))
                    } catch (_: Exception) {
                        null
                    }
                } ?: data.font?.let { font ->
                    if (font.resource != 0) {
                        try {
                            ResourcesCompat.getFont(
                                this,
                                font.resource
                            )
                        } catch (_: Throwable) {
                            Typeface.SANS_SERIF
                        }
                    } else {
                        Typeface.SANS_SERIF
                    }
                } ?: Typeface.SANS_SERIF
            )
        }

        fun push(activity: Activity?, hide: Boolean = true) {
            activity.navigate(R.id.global_to_navigation_subtitles, Bundle().apply {
                putBoolean("hide", hide)
                putBoolean("popFragment", true)
            })
        }

        private fun getDefColor(id: Int): Int {
            return when (id) {
                0 -> Color.WHITE
                1 -> Color.BLACK
                2 -> Color.TRANSPARENT
                3 -> Color.TRANSPARENT
                else -> Color.TRANSPARENT
            }
        }

        fun Context.saveStyle(style: SaveCaptionStyle) {
            if (subtitleStyleState.value !== style) {
                subtitleStyleState.value = style
            }
            this.setKey(SUBTITLE_KEY, style)
        }

        val defaultSubtitleStyle = SaveCaptionStyle(
            foregroundColor = getDefColor(0),
            backgroundColor = getDefColor(2),
            windowColor = getDefColor(3),
            edgeType = CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            edgeColor = getDefColor(1),
            font = null,
            typefaceFilePath = null,
            elevation = DEF_SUBS_ELEVATION,
            fixedTextSize = null,
        )
        val subtitleStyleState =
            mutableStateOf((getKey<SaveCaptionStyle>(SUBTITLE_KEY) ?: defaultSubtitleStyle))

        fun getCurrentSavedStyle(): SaveCaptionStyle {
            return subtitleStyleState.value
        }

        fun getDownloadSubsLanguageTagIETF(): List<String> {
            return getKey<List<String>>(SUBTITLE_DOWNLOAD_KEY) ?: listOf("en")
        }

        fun getAutoSelectLanguageTagIETF(): String {
            return getKey<String>(SUBTITLE_AUTO_SELECT_KEY) ?: "en"
        }
    }
}
