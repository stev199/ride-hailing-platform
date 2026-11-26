package com.ridehailing.core.data.di

import android.content.Context
import com.ridehailing.core.data.network.ApiClient
import com.ridehailing.core.data.network.ApiClientFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * Base URL comes from the app's BuildConfig.API_BASE_URL (rider/driver carry it;
     * -Papi.baseUrl override). Round-15 ROOT CAUSE (CI #191 e2e-booking): the base URL
     * was documented but NEVER applied - every app-side request used a RELATIVE path
     * on a host-less client, so login (and everything else) failed instantly with a
     * visible login_error while the e2e waited for session_info. The reflection lookup
     * keeps core-data app-agnostic; the compose default (10.0.2.2:8080) covers the
     * Robolectric/env fallback. The e2e-booking cell pins this wiring end to end.
     */
    @Provides
    @Singleton
    @ApiClient
    fun httpClient(@ApplicationContext context: Context): HttpClient {
        val baseUrl = try {
            val field = Class.forName(context.packageName + ".BuildConfig")
                .getDeclaredField("API_BASE_URL")
            field.get(null) as? String
        } catch (_: Exception) {
            null
        } ?: "http://10.0.2.2:8080"
        return ApiClientFactory.create(baseUrl = baseUrl)
    }
}
