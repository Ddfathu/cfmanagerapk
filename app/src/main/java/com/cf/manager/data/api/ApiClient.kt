package com.cf.manager.data.api

import com.cf.manager.data.AppConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    const val CLOUDFLARE_BASE_URL = "https://api.cloudflare.com/client/v4/"

    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val builder = original.newBuilder()

        // Suntikkan header otentikasi resmi Cloudflare v4 langsung
        if (AppConfig.activeEmail.isNotBlank()) {
            builder.header("X-Auth-Email", AppConfig.activeEmail)
        }
        if (AppConfig.activeApiKey.isNotBlank()) {
            if (AppConfig.activeApiKey.startsWith("Bearer ", ignoreCase = true)) {
                builder.header("Authorization", AppConfig.activeApiKey)
            } else {
                builder.header("X-Auth-Key", AppConfig.activeApiKey)
            }
        }

        chain.proceed(builder.build())
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(CLOUDFLARE_BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val api: WorkerApi = retrofit.create(WorkerApi::class.java)

    var activeAccountId: String = ""
}
