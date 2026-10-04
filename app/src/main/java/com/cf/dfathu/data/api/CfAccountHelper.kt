package com.cf.dfathu.data.api

object CfAccountHelper {
    private var cachedAccountId: String? = null

    fun clearCachedAccountId() {
        cachedAccountId = null
    }

    suspend fun ensureAccountId(): String {
        cachedAccountId?.let { return it }
        return try {
            val res = ApiClient.api.listMemberships()
            if (res.isSuccessful && res.body()?.success == true) {
                val firstMember = res.body()?.result?.firstOrNull()?.asJsonObject
                val accObj = firstMember?.getAsJsonObject("account")
                val accId = accObj?.get("id")?.asString ?: ""
                if (accId.isNotBlank()) {
                    cachedAccountId = accId
                    return accId
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }
}
