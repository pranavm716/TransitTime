package io.github.pranavm716.transittime.transit.muni

import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query

internal interface MuniApi {
    @GET("StopMonitoring")
    suspend fun getStopMonitoring(
        @Query("api_key") apiKey: String,
        @Query("agency") agency: String = "SF",
        @Query("stopcode") stopCode: String,
        @Query("format") format: String = "json"
    ): ResponseBody
}

object MuniApiClient {
    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
        )
        .build()

    internal val api: MuniApi = Retrofit.Builder()
        .baseUrl("https://api.511.org/transit/")
        .client(httpClient)
        .build()
        .create(MuniApi::class.java)
}
