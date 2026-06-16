package com.example.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class FtpConfigRepository(private val dao: FtpConfigDao) {
    val configFlow: Flow<FtpConfigEntity> = dao.getConfigFlow().map { it ?: FtpConfigEntity() }

    val remoteServersFlow: Flow<List<RemoteServerEntity>> = dao.getRemoteServersFlow()

    suspend fun getConfig(): FtpConfigEntity {
        return dao.getConfig() ?: FtpConfigEntity()
    }

    suspend fun saveConfig(config: FtpConfigEntity) {
        dao.saveConfig(config)
    }

    suspend fun saveRemoteServer(server: RemoteServerEntity) {
        dao.saveRemoteServer(server)
    }

    suspend fun deleteRemoteServer(server: RemoteServerEntity) {
        dao.deleteRemoteServer(server)
    }
}
