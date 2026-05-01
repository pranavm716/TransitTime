package io.github.pranavm716.transittime.transit

import org.json.JSONException
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class TransitError(val label: String, val userMessage: String) {
    OFFLINE("Offline", "No internet connection"),
    TIMEOUT("Timeout", "Connection timed out"),
    RATE_LIMIT("RLE", "API rate limit exceeded (RLE)"),
    AUTH("Auth", "API authentication failed"),
    SERVER("Server", "Transit agency server error"),
    DATA("Data", "Error parsing transit data"),
    EMPTY("Empty", "No departures found for this stop. Try configuring at a different time."),
    FAILED("Failed", "An unexpected error occurred");

    companion object {
        fun fromException(e: Throwable): TransitError {
            return when (e) {
                is UnknownHostException -> OFFLINE
                is SocketTimeoutException -> TIMEOUT
                is RateLimitException -> RATE_LIMIT
                is AuthenticationException -> AUTH
                is TransitServerException -> SERVER
                is DataParsingException, is JSONException -> DATA
                is EmptyDataException -> EMPTY
                is HttpException -> {
                    when (e.code()) {
                        401, 403 -> AUTH
                        429 -> RATE_LIMIT
                        in 500..599 -> SERVER
                        else -> FAILED
                    }
                }

                is IOException -> {
                    val message = e.message?.lowercase() ?: ""
                    when {
                        message.contains("timeout") -> TIMEOUT
                        message.contains("429") -> RATE_LIMIT
                        message.contains("401") || message.contains("403") -> AUTH
                        message.contains("500") || message.contains("511 api error") || message.contains(
                            "api returned http"
                        ) -> SERVER

                        else -> FAILED
                    }
                }

                else -> FAILED
            }
        }
    }
}

class RateLimitException(message: String? = null) : IOException(message)
class AuthenticationException(message: String? = null) : IOException(message)
class TransitServerException(message: String? = null) : IOException(message)
class DataParsingException(message: String? = null) : IOException(message)
class EmptyDataException(message: String? = null) : IOException(message)
