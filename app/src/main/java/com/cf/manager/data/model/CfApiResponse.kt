package com.cf.manager.data.model

import com.google.gson.annotations.SerializedName

data class CfApiResponse<T>(
    @SerializedName("success") val success: Boolean,
    @SerializedName("errors") val errors: List<CfApiMessage>? = null,
    @SerializedName("messages") val messages: List<CfApiMessage>? = null,
    @SerializedName("result") val result: T? = null
)

data class CfApiMessage(
    @SerializedName("code") val code: Int? = null,
    @SerializedName("message") val message: String = ""
)

data class CfAccountItem(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String
)
