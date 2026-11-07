package com.floss.microscope.di

import android.content.Context
import androidx.room.Room
import com.floss.microscope.data.local.MicroscopeDao
import com.floss.microscope.data.local.MicroscopeDatabase
import com.floss.microscope.data.repository.ProcessingRepositoryImpl
import com.floss.microscope.data.repository.ProjectRepositoryImpl
import com.floss.microscope.domain.repository.CameraRepository
import com.floss.microscope.domain.repository.ProcessingRepository
import com.floss.microscope.domain.repository.ProjectRepository
import com.floss.microscope.infrastructure.camera.CameraXManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryBindingModule {

    @Binds
    @Singleton
    abstract fun bindProjectRepository(impl: ProjectRepositoryImpl): ProjectRepository

    @Binds
    @Singleton
    abstract fun bindProcessingRepository(impl: ProcessingRepositoryImpl): ProcessingRepository

    @Binds
    @Singleton
    abstract fun bindCameraRepository(impl: CameraXManager): CameraRepository
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MicroscopeDatabase {
        return Room.databaseBuilder(
            context,
            MicroscopeDatabase::class.java,
            "floss_microscope.db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideMicroscopeDao(db: MicroscopeDatabase): MicroscopeDao = db.dao()
}
