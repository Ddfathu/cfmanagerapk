package com.cf.manager.data.api

import com.cf.manager.data.AppConfig
import com.cf.manager.data.model.DnsRecordItem
import com.cf.manager.data.model.ZoneItem
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import okhttp3.Interceptor
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

data class CfError(val code: Int?, val message: String?)

data class CfStandardResponse(
    val success: Boolean,
    val errors: List<CfError>?,
    val messages: List<String>?,
    val result: JsonElement?
)

data class CfListResponse<T>(
    val success: Boolean,
    val errors: List<CfError>?,
    val result: List<T>?
)

data class CfBindingListResponse(
    val success: Boolean,
    val errors: List<CfError>?,
    val result: List<JsonObject>?
)

interface WorkerApi {
    @GET("accounts/{accountId}/workers/services")
    suspend fun listWorkers(@Path("accountId") accountId: String): Response<CfListResponse<JsonObject>>

    @GET("accounts/{accountId}/workers/services/{scriptName}/environments/production/content")
    suspend fun getWorkerCode(
        @Path("accountId") accountId: String,
        @Path("scriptName") scriptName: String
    ): Response<ResponseBody>

    @Multipart
    @PUT("accounts/{accountId}/workers/services/{scriptName}/environments/production/content")
    suspend fun deployWorkerMultipart(
        @Path("accountId") accountId: String,
        @Path("scriptName") scriptName: String,
        @Part("metadata") metadata: RequestBody,
        @Part script: MultipartBody.Part
    ): Response<CfStandardResponse>

    @GET("accounts/{accountId}/workers/domains")
    suspend fun listWorkerDomains(@Path("accountId") accountId: String): Response<CfListResponse<JsonObject>>

    @PUT("accounts/{accountId}/workers/domains")
    suspend fun putWorkerDomain(
        @Path("accountId") accountId: String,
        @Body body: Map<String, String>
    ): Response<CfStandardResponse>

    @DELETE("accounts/{accountId}/workers/domains/{domainId}")
    suspend fun deleteWorkerDomain(
        @Path("accountId") accountId: String,
        @Path("domainId") domainId: String
    ): Response<CfStandardResponse>

    @DELETE("accounts/{accountId}/workers/services/{scriptName}")
    suspend fun deleteWorker(
        @Path("accountId") accountId: String,
        @Path("scriptName") scriptName: String
    ): Response<CfStandardResponse>

    @GET("accounts/{accountId}/workers/services/{scriptName}/environments/production/settings")
    suspend fun getWorkerSettings(
        @Path("accountId") accountId: String,
        @Path("scriptName") scriptName: String
    ): Response<CfStandardResponse>

    @PUT("accounts/{accountId}/workers/services/{scriptName}/environments/production/settings")
    suspend fun updateWorkerSettings(
        @Path("accountId") accountId: String,
        @Path("scriptName") scriptName: String,
        @Body @JvmSuppressWildcards body: Map<String, Any>
    ): Response<CfStandardResponse>

    @GET("accounts/{accountId}/workers/subdomain")
    suspend fun getAccountSubdomain(
        @Path("accountId") accountId: String
    ): Response<CfStandardResponse>

    @GET("accounts/{accountId}/workers/services/{serviceName}/environments/production/bindings")
    suspend fun getWorkerBindings(
        @Path("accountId") accountId: String,
        @Path("serviceName") serviceName: String
    ): Response<CfBindingListResponse>

    @Multipart
    @PATCH("accounts/{accountId}/workers/services/{serviceName}/environments/production/settings")
    suspend fun patchWorkerSettingsMultipart(
        @Path("accountId") accountId: String,
        @Path("serviceName") serviceName: String,
        @Part settings: MultipartBody.Part
    ): Response<CfStandardResponse>

    // --- WORKER SECRETS & VARIABLES ---
    @GET("accounts/{accountId}/workers/services/{serviceName}/environments/production/bindings")
    suspend fun listWorkerSecrets(
        @Path("accountId") accountId: String,
        @Path("serviceName") serviceName: String
    ): Response<CfBindingListResponse>

    @PUT("accounts/{accountId}/workers/services/{serviceName}/environments/production/secrets")
    suspend fun putWorkerSecret(
        @Path("accountId") accountId: String,
        @Path("serviceName") serviceName: String,
        @Body @JvmSuppressWildcards body: Map<String, Any>
    ): Response<CfStandardResponse>

    @PUT("accounts/{accountId}/workers/services/{serviceName}/environments/production/secrets")
    suspend fun putWorkerSecretStringMap(
        @Path("accountId") accountId: String,
        @Path("serviceName") serviceName: String,
        @Body body: Map<String, String>
    ): Response<CfStandardResponse>

    @DELETE("accounts/{accountId}/workers/services/{serviceName}/environments/production/secrets/{secretName}")
    suspend fun deleteWorkerSecret(
        @Path("accountId") accountId: String,
        @Path("serviceName") serviceName: String,
        @Path("secretName") secretName: String
    ): Response<CfStandardResponse>

    @GET("memberships")
    suspend fun listMemberships(): Response<CfListResponse<JsonObject>>

    // --- ZONES & DNS ---
    @GET("zones")
    suspend fun listZones(@Query("account.id") accountId: String? = null): Response<CfListResponse<ZoneItem>>

    @POST("zones")
    suspend fun createZone(@Body @JvmSuppressWildcards body: Map<String, Any>): Response<CfStandardResponse>

    @DELETE("zones/{zoneId}")
    suspend fun deleteZone(@Path("zoneId") zoneId: String): Response<CfStandardResponse>

    @GET("zones/{zoneId}/dns_records")
    suspend fun listDns(@Path("zoneId") zoneId: String, @Query("per_page") perPage: Int = 100): Response<CfListResponse<DnsRecordItem>>

    @POST("zones/{zoneId}/dns_records")
    suspend fun createDns(@Path("zoneId") zoneId: String, @Body @JvmSuppressWildcards body: Map<String, Any>): Response<CfStandardResponse>

    @PUT("zones/{zoneId}/dns_records/{recordId}")
    suspend fun updateDns(@Path("zoneId") zoneId: String, @Path("recordId") recordId: String, @Body @JvmSuppressWildcards body: Map<String, Any>): Response<CfStandardResponse>

    @DELETE("zones/{zoneId}/dns_records/{recordId}")
    suspend fun deleteDns(@Path("zoneId") zoneId: String, @Path("recordId") recordId: String): Response<CfStandardResponse>

    // --- SSL / UNIVERSAL CA ---
    @GET("zones/{zoneId}/ssl/universal/settings")
    suspend fun getUniversalSslSettings(@Path("zoneId") zoneId: String): Response<CfStandardResponse>

    @PATCH("zones/{zoneId}/ssl/universal/settings")
    suspend fun updateUniversalSslSettings(@Path("zoneId") zoneId: String, @Body @JvmSuppressWildcards body: Map<String, Any>): Response<CfStandardResponse>

    @POST("zones/{zoneId}/ssl/universal/settings")
    suspend fun reorderSslVerification(@Path("zoneId") zoneId: String, @Body @JvmSuppressWildcards body: Map<String, Any>): Response<CfStandardResponse>

    // --- EMAIL ROUTING ---
    @GET("accounts/{accountId}/email/routing/addresses")
    suspend fun listEmailDestinations(@Path("accountId") accountId: String): Response<CfListResponse<JsonObject>>

    @POST("accounts/{accountId}/email/routing/addresses")
    suspend fun createEmailDestination(@Path("accountId") accountId: String, @Body @JvmSuppressWildcards body: Map<String, Any>): Response<CfStandardResponse>

    @DELETE("accounts/{accountId}/email/routing/addresses/{emailId}")
    suspend fun deleteEmailDestination(@Path("accountId") accountId: String, @Path("emailId") emailId: String): Response<CfStandardResponse>

    @GET("zones/{zoneId}/email/routing")
    suspend fun getEmailRoutingSettings(@Path("zoneId") zoneId: String): Response<CfStandardResponse>

    @POST("zones/{zoneId}/email/routing/enable")
    suspend fun enableEmailRouting(@Path("zoneId") zoneId: String): Response<CfStandardResponse>

    @GET("zones/{zoneId}/email/routing/rules")
    suspend fun listEmailRules(@Path("zoneId") zoneId: String): Response<CfListResponse<JsonObject>>

    @GET("zones/{zoneId}/email/routing/rules/catch_all")
    suspend fun getEmailCatchAll(@Path("zoneId") zoneId: String): Response<CfStandardResponse>

    @PUT("zones/{zoneId}/email/routing/rules/catch_all")
    suspend fun updateEmailCatchAll(@Path("zoneId") zoneId: String, @Body @JvmSuppressWildcards body: Map<String, Any>): Response<CfStandardResponse>

    @POST("zones/{zoneId}/email/routing/rules")
    suspend fun createEmailRule(@Path("zoneId") zoneId: String, @Body @JvmSuppressWildcards body: Map<String, Any>): Response<CfStandardResponse>

    @DELETE("zones/{zoneId}/email/routing/rules/{ruleId}")
    suspend fun deleteEmailRule(@Path("zoneId") zoneId: String, @Path("ruleId") ruleId: String): Response<CfStandardResponse>

    // --- TUNNELS ---
    @GET("accounts/{accountId}/cfd_tunnel")
    suspend fun listTunnels(@Path("accountId") accountId: String, @Query("is_deleted") isDeleted: Boolean = false): Response<CfListResponse<JsonObject>>

    @POST("accounts/{accountId}/cfd_tunnel")
    suspend fun createTunnel(@Path("accountId") accountId: String, @Body @JvmSuppressWildcards body: Map<String, Any>): Response<CfStandardResponse>

    @DELETE("accounts/{accountId}/cfd_tunnel/{tunnelId}")
    suspend fun deleteTunnel(@Path("accountId") accountId: String, @Path("tunnelId") tunnelId: String): Response<CfStandardResponse>

    @GET("accounts/{accountId}/cfd_tunnel/{tunnelId}/token")
    suspend fun getTunnelToken(@Path("accountId") accountId: String, @Path("tunnelId") tunnelId: String): Response<CfStandardResponse>

    @GET("accounts/{accountId}/cfd_tunnel/{tunnelId}/configurations")
    suspend fun getTunnelConfigurations(@Path("accountId") accountId: String, @Path("tunnelId") tunnelId: String): Response<CfStandardResponse>

    @PUT("accounts/{accountId}/cfd_tunnel/{tunnelId}/configurations")
    suspend fun updateTunnelConfigurations(@Path("accountId") accountId: String, @Path("tunnelId") tunnelId: String, @Body @JvmSuppressWildcards body: Map<String, Any>): Response<CfStandardResponse>

    // --- CLOUDFLARE PAGES ---
    @GET("accounts/{accountId}/pages/projects")
    suspend fun listPagesProjects(@Path("accountId") accountId: String): Response<CfListResponse<JsonObject>>

    @POST("accounts/{accountId}/pages/projects")
    suspend fun createPagesProject(@Path("accountId") accountId: String, @Body @JvmSuppressWildcards body: Map<String, Any>): Response<CfStandardResponse>

    @DELETE("accounts/{accountId}/pages/projects/{projectName}")
    suspend fun deletePagesProject(@Path("accountId") accountId: String, @Path("projectName") projectName: String): Response<CfStandardResponse>

    @GET("accounts/{accountId}/pages/projects/{projectName}/domains")
    suspend fun listPagesCustomDomains(@Path("accountId") accountId: String, @Path("projectName") projectName: String): Response<CfListResponse<JsonObject>>

    @POST("accounts/{accountId}/pages/projects/{projectName}/domains")
    suspend fun addPagesCustomDomain(@Path("accountId") accountId: String, @Path("projectName") projectName: String, @Body @JvmSuppressWildcards body: Map<String, Any>): Response<CfStandardResponse>

    @DELETE("accounts/{accountId}/pages/projects/{projectName}/domains/{domainName}")
    suspend fun deletePagesCustomDomain(@Path("accountId") accountId: String, @Path("projectName") projectName: String, @Path("domainName") domainName: String): Response<CfStandardResponse>

    // --- R2 ---
    @GET("accounts/{accountId}/r2/buckets")
    suspend fun listR2Buckets(@Path("accountId") accountId: String): Response<JsonObject>

    @POST("accounts/{accountId}/r2/buckets")
    suspend fun createR2Bucket(@Path("accountId") accountId: String, @Body @JvmSuppressWildcards body: Map<String, Any>): Response<CfStandardResponse>

    @DELETE("accounts/{accountId}/r2/buckets/{bucketName}")
    suspend fun deleteR2Bucket(@Path("accountId") accountId: String, @Path("bucketName") bucketName: String): Response<CfStandardResponse>
}

object ApiClient {
    const val CLOUDFLARE_BASE_URL = "https://api.cloudflare.com/client/v4/"

    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val builder = original.newBuilder()

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
