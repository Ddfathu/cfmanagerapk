package com.cf.manager.data.api

import com.google.gson.JsonObject
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface WorkerApi {
    @GET("accounts/{accountId}/workers/services")
    suspend fun listWorkers(@Path("accountId") accountId: String): Response<CfListResponse>

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
        @Part("metadata") metadata: okhttp3.RequestBody,
        @Part script: MultipartBody.Part
    ): Response<CfStandardResponse>

    @GET("accounts/{accountId}/workers/domains")
    suspend fun listWorkerDomains(@Path("accountId") accountId: String): Response<CfListResponse>

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

    // --- TAMBAHAN BARU SESUAI WEB DASHBOARD ---
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
}

// Data class pendukung binding list
data class CfBindingListResponse(
    val success: Boolean,
    val errors: List<CfError>?,
    val result: List<JsonObject>?
)
