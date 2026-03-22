package io.github.pranavm716.transittime.data.api.bart

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object BartApiClient {
    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
        )
        .build()

    val api: BartApi = Retrofit.Builder()
        .baseUrl("http://api.bart.gov/gtfsrt/tripupdate.aspx")
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(BartApi::class.java)
}