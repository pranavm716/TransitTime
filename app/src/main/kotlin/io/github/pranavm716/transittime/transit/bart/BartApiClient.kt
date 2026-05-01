package io.github.pranavm716.transittime.transit.bart

import io.github.pranavm716.transittime.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

internal interface BartApi {
    @GET("gtfsrt/tripupdate.aspx")
    suspend fun getTripUpdates(
        @Query("key") key: String = BuildConfig.BART_API_KEY
    ): Response<ResponseBody>
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
