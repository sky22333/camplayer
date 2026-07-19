package com.zhenshi.capture.di

import com.zhenshi.capture.media.DefaultPlaybackSession
import com.zhenshi.capture.media.PlaybackSession
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    @Singleton
    abstract fun bindPlaybackSession(impl: DefaultPlaybackSession): PlaybackSession
}
