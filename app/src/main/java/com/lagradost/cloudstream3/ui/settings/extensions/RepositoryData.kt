package com.lagradost.cloudstream3.ui.settings.extensions

import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RepositoryData(
    @JsonProperty("iconUrl") @SerialName("iconUrl") val iconUrl: String?,
    @JsonProperty("name") @SerialName("name") val name: String,
    @JsonProperty("url") @SerialName("url") val url: String,
    @JsonProperty("description") @SerialName("description") val description: String? = null,
) {
    constructor(name: String, url: String) : this(
        iconUrl = null,
        name = name,
        url = url,
        description = null
    )

    constructor(iconUrl: String?, name: String, url: String) : this(
        iconUrl = iconUrl,
        name = name,
        url = url,
        description = null
    )
}

const val REPOSITORIES_KEY = "REPOSITORIES_KEY"
