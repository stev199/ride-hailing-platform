package com.ridehailing.core.data.di

import com.ridehailing.core.data.network.ApiClient
import com.ridehailing.core.data.network.ApiClientFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    /** Base URL comes from BuildConfig (API_BASE_URL in the app modules, -Papi.baseUrl override). */
    @Provides
    @Singleton
    @ApiClient
    fun httpClient(): HttpClient = ApiClientFactory.create()
}
