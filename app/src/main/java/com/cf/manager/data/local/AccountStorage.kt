package com.cf.manager.data.local

import android.content.Context
import android.content.SharedPreferences
import com.cf.manager.data.model.CfAccount
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class AccountStorage(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("cf_accounts_pref", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getAccounts(): MutableList<CfAccount> {
        val json = prefs.getString("accounts_list", null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<CfAccount>>() {}.type
        return try {
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveAccounts(list: List<CfAccount>) {
        prefs.edit().putString("accounts_list", gson.toJson(list)).apply()
    }

    fun getActiveIndex(): Int = prefs.getInt("active_account_idx", 0)

    fun setActiveIndex(idx: Int) {
        prefs.edit().putInt("active_account_idx", idx).apply()
    }
}
