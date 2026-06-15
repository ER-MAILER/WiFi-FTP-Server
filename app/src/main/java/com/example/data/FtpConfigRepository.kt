package com.example.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class FtpConfigRepository(private val dao: FtpConfigDao) {
    val configFlow: Flow<FtpConfigEntity> = dao.getConfigFlow().map { it ?: FtpConfigEntity() }

    suspend fun getConfig(): FtpConfigEntity {
        return dao.getConfig() ?: FtpConfigEntity()
    }

    suspend fun saveConfig(config: FtpConfigEntity) {
        dao.saveConfig(config)
    }
}
