package com.cf.manager.data.api

import com.cf.manager.data.model.*
import com.google.gson.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface WorkerApi {

    // ACCOUNTS
    @GET("accounts")
    suspend fun listAccounts(): Response<CfApiResponse<List<CfAccountItem>>>

    // WORKERS
    @GET("accounts/{account_id}/workers/scripts")
    suspend fun listWorkers(
        @Path("account_id") accountId: String
    ): Response<CfApiResponse<List<JsonObject>>>

    @GET("accounts/{account_id}/workers/scripts/{script_name}")
    suspend fun getWorkerCode(
        @Path("account_id") accountId: String,
        @Path("script_name") scriptName: String
    ): Response<ResponseBody>

    @Multipart
    @PUT("accounts/{account_id}/workers/scripts/{script_name}")
    suspend fun deployWorkerMultipart(
        @Path("account_id") accountId: String,
        @Path("script_name") scriptName: String,
        @Part("metadata") metadata: RequestBody,
        @Part script: MultipartBody.Part
    ): Response<CfApiResponse<JsonObject>>

    @DELETE("accounts/{account_id}/workers/scripts/{script_name}")
    suspend fun deleteWorker(
        @Path("account_id") accountId: String,
        @Path("script_name") scriptName: String
    ): Response<CfApiResponse<JsonObject>>

    // WORKER DOMAINS
    @GET("accounts/{account_id}/workers/domains")
    suspend fun listWorkerDomains(
        @Path("account_id") accountId: String
    ): Response<CfApiResponse<List<JsonObject>>>

    @PUT("accounts/{account_id}/workers/domains")
    suspend fun putWorkerDomain(
        @Path("account_id") accountId: String,
        @Body payload: Map<String, String>
    ): Response<CfApiResponse<JsonObject>>

    @DELETE("accounts/{account_id}/workers/domains/{domain_id}")
    suspend fun deleteWorkerDomain(
        @Path("account_id") accountId: String,
        @Path("domain_id") domainId: String
    ): Response<CfApiResponse<JsonObject>>

    // WORKER SECRETS & BINDINGS
    @GET("accounts/{account_id}/workers/scripts/{script_name}/secrets")
    suspend fun listWorkerSecrets(
        @Path("account_id") accountId: String,
        @Path("script_name") scriptName: String
    ): Response<CfApiResponse<List<JsonObject>>>

    @PUT("accounts/{account_id}/workers/scripts/{script_name}/secrets")
    suspend fun putWorkerSecret(
        @Path("account_id") accountId: String,
        @Path("script_name") scriptName: String,
        @Body payload: Map<String, String>
    ): Response<CfApiResponse<JsonObject>>

    @DELETE("accounts/{account_id}/workers/scripts/{script_name}/secrets/{secret_name}")
    suspend fun deleteWorkerSecret(
        @Path("account_id") accountId: String,
        @Path("script_name") scriptName: String,
        @Path("secret_name") secretName: String
    ): Response<CfApiResponse<JsonObject>>

    // ZONES & DNS
    @GET("zones")
    suspend fun listZones(
        @Query("per_page") perPage: Int = 50
    ): Response<CfApiResponse<List<ZoneItem>>>

    @GET("zones/{zone_id}/dns_records")
    suspend fun listDns(
        @Path("zone_id") zoneId: String,
        @Query("per_page") perPage: Int = 100
    ): Response<CfApiResponse<List<DnsRecordItem>>>

    @POST("zones/{zone_id}/dns_records")
    suspend fun createDns(
        @Path("zone_id") zoneId: String,
        @Body payload: Map<String, Any>
    ): Response<CfApiResponse<DnsRecordItem>>

    @PUT("zones/{zone_id}/dns_records/{record_id}")
    suspend fun updateDns(
        @Path("zone_id") zoneId: String,
        @Path("record_id") recordId: String,
        @Body payload: Map<String, Any>
    ): Response<CfApiResponse<DnsRecordItem>>

    @DELETE("zones/{zone_id}/dns_records/{record_id}")
    suspend fun deleteDns(
        @Path("zone_id") zoneId: String,
        @Path("record_id") recordId: String
    ): Response<CfApiResponse<JsonObject>>

    // SSL / TLS SETTINGS
    @GET("zones/{zone_id}/settings/ssl")
    suspend fun getSslSetting(
        @Path("zone_id") zoneId: String
    ): Response<CfApiResponse<JsonObject>>

    @PATCH("zones/{zone_id}/settings/ssl")
    suspend fun updateSslSetting(
        @Path("zone_id") zoneId: String,
        @Body payload: Map<String, String>
    ): Response<CfApiResponse<JsonObject>>

    @PATCH("zones/{zone_id}/settings/always_use_https")
    suspend fun updateAlwaysUseHttps(
        @Path("zone_id") zoneId: String,
        @Body payload: Map<String, String>
    ): Response<CfApiResponse<JsonObject>>

    // TUNNEL
    @GET("accounts/{account_id}/cfd_tunnel")
    suspend fun listTunnels(
        @Path("account_id") accountId: String,
        @Query("is_deleted") isDeleted: Boolean = false
    ): Response<CfApiResponse<List<TunnelItem>>>

    @POST("accounts/{account_id}/cfd_tunnel")
    suspend fun createTunnel(
        @Path("account_id") accountId: String,
        @Body payload: Map<String, String>
    ): Response<CfApiResponse<JsonObject>>

    @GET("accounts/{account_id}/cfd_tunnel/{tunnel_id}/token")
    suspend fun getTunnelToken(
        @Path("account_id") accountId: String,
        @Path("tunnel_id") tunnelId: String
    ): Response<CfApiResponse<String>>

    @PUT("accounts/{account_id}/cfd_tunnel/{tunnel_id}/configurations")
    suspend fun updateTunnelConfigurations(
        @Path("account_id") accountId: String,
        @Path("tunnel_id") tunnelId: String,
        @Body payload: Map<String, Any>
    ): Response<CfApiResponse<JsonObject>>

    @DELETE("accounts/{account_id}/cfd_tunnel/{tunnel_id}")
    suspend fun deleteTunnel(
        @Path("account_id") accountId: String,
        @Path("tunnel_id") tunnelId: String
    ): Response<CfApiResponse<JsonObject>>

    // PAGES
    @GET("accounts/{account_id}/pages/projects")
    suspend fun listPagesProjects(
        @Path("account_id") accountId: String
    ): Response<CfApiResponse<List<JsonObject>>>

    @POST("accounts/{account_id}/pages/projects")
    suspend fun createPagesProject(
        @Path("account_id") accountId: String,
        @Body payload: Map<String, String>
    ): Response<CfApiResponse<JsonObject>>

    @DELETE("accounts/{account_id}/pages/projects/{project_name}")
    suspend fun deletePagesProject(
        @Path("account_id") accountId: String,
        @Path("project_name") projectName: String
    ): Response<CfApiResponse<JsonObject>>

    @GET("accounts/{account_id}/pages/projects/{project_name}/domains")
    suspend fun listPagesCustomDomains(
        @Path("account_id") accountId: String,
        @Path("project_name") projectName: String
    ): Response<CfApiResponse<List<JsonObject>>>

    @POST("accounts/{account_id}/pages/projects/{project_name}/domains")
    suspend fun addPagesCustomDomain(
        @Path("account_id") accountId: String,
        @Path("project_name") projectName: String,
        @Body payload: Map<String, String>
    ): Response<CfApiResponse<JsonObject>>

    @DELETE("accounts/{account_id}/pages/projects/{project_name}/domains/{domain_name}")
    suspend fun deletePagesCustomDomain(
        @Path("account_id") accountId: String,
        @Path("project_name") projectName: String,
        @Path("domain_name") domainName: String
    ): Response<CfApiResponse<JsonObject>>

    // EMAIL ROUTING
    @GET("accounts/{account_id}/email/routing/addresses")
    suspend fun listEmailDestinations(
        @Path("account_id") accountId: String
    ): Response<CfApiResponse<List<JsonObject>>>

    @POST("accounts/{account_id}/email/routing/addresses")
    suspend fun createEmailDestination(
        @Path("account_id") accountId: String,
        @Body payload: Map<String, String>
    ): Response<CfApiResponse<JsonObject>>

    @DELETE("accounts/{account_id}/email/routing/addresses/{address_id}")
    suspend fun deleteEmailDestination(
        @Path("account_id") accountId: String,
        @Path("address_id") addressId: String
    ): Response<CfApiResponse<JsonObject>>

    @POST("zones/{zone_id}/email/routing/enable")
    suspend fun enableEmailRouting(
        @Path("zone_id") zoneId: String
    ): Response<CfApiResponse<JsonObject>>

    @GET("zones/{zone_id}/email/routing/rules/catch_all")
    suspend fun getEmailCatchAll(
        @Path("zone_id") zoneId: String
    ): Response<CfApiResponse<JsonObject>>

    @PUT("zones/{zone_id}/email/routing/rules/catch_all")
    suspend fun updateEmailCatchAll(
        @Path("zone_id") zoneId: String,
        @Body payload: Map<String, Any>
    ): Response<CfApiResponse<JsonObject>>
}
