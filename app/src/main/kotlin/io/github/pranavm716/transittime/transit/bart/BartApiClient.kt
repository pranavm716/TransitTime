package io.github.pranavm716.transittime.transit.bart

import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET

internal interface BartApi {
    @GET("gtfsrt/tripupdate.aspx")
    suspend fun getTripUpdates(): ResponseBody
}

object BartApiClient {
    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
        )
        .build()

    internal val api: BartApi = Retrofit.Builder()
        .baseUrl("https://api.bart.gov/")
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(BartApi::class.java)
}
