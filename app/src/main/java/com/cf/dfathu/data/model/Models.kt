package com.cf.dfathu.data.model

import com.google.gson.annotations.SerializedName

data class CfAccount(
    val alias: String = "Utama",
    val email: String = "",
    val apiKey: String = ""
)

data class ZoneItem(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("status") val status: String = "active"
)

data class DnsRecordItem(
    @SerializedName("id") val id: String = "",
    @SerializedName("zone_id") val zoneId: String = "",
    @SerializedName("zone_name") val zoneName: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("type") val type: String = "A",
    @SerializedName("content") val content: String = "",
    @SerializedName("proxiable") val proxiable: Boolean = false,
    @SerializedName("proxied") val proxied: Boolean = false,
    @SerializedName("ttl") val ttl: Int = 1
)

data class TunnelItem(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("status") val status: String? = "inactive",
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("deleted_at") val deletedAt: String? = null
)
