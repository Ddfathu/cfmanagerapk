package com.cf.manager.data.api

object CfAccountHelper {
    private var cachedAccountId: String? = null

    fun clearCachedAccountId() {
        cachedAccountId = null
    }

    suspend fun ensureAccountId(): String {
        cachedAccountId?.let { return it }
        return try {
            val res = ApiClient.api.listAccounts()
            if (res.isSuccessful && res.body()?.success == true) {
                val acc = res.body()?.result?.firstOrNull()
                val id = acc?.id ?: ""
                if (id.isNotBlank()) cachedAccountId = id
                id
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }
}
