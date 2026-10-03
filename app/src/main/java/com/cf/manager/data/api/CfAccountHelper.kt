package com.cf.manager.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object CfAccountHelper {
    suspend fun ensureAccountId(): String {
        if (ApiClient.activeAccountId.isNotBlank()) {
            return ApiClient.activeAccountId
        }

        return withContext(Dispatchers.IO) {
            try {
                val res = ApiClient.api.listAccounts()
                if (res.isSuccessful && res.body()?.success == true) {
                    val accounts = res.body()?.result
                    if (!accounts.isNullOrEmpty()) {
                        val firstAccId = accounts[0].id
                        ApiClient.activeAccountId = firstAccId
                        firstAccId
                    } else {
                        ""
                    }
                } else {
                    ""
                }
            } catch (e: Exception) {
                ""
            }
        }
    }
}
