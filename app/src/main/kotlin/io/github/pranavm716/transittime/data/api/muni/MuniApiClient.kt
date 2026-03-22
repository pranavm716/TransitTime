package io.github.pranavm716.transittime.data.api.muni

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

object MuniApiClient {
    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
        )
        .build()

    val api: MuniApi = Retrofit.Builder()
        .baseUrl("https://api.511.org/transit/")
        .client(httpClient)
        .build()
        .create(MuniApi::class.java)
}