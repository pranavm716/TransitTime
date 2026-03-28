package io.github.pranavm716.transittime.transit.caltrain

import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query

internal interface CaltrainApi {
    @GET("tripupdates")
    suspend fun getTripUpdates(
        @Query("agency") agency: String = "CT",
        @Query("api_key") apiKey: String
    ): ResponseBody
}

object CaltrainApiClient {
    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
        )
        .build()

    internal val api: CaltrainApi = Retrofit.Builder()
        .baseUrl("https://api.511.org/transit/")
        .client(httpClient)
        .build()
        .create(CaltrainApi::class.java)
}
