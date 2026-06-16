package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FtpConfigDao {
    @Query("SELECT * FROM ftp_config WHERE id = 1 LIMIT 1")
    fun getConfigFlow(): Flow<FtpConfigEntity?>

    @Query("SELECT * FROM ftp_config WHERE id = 1 LIMIT 1")
    suspend fun getConfig(): FtpConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveConfig(config: FtpConfigEntity)

    // Remote server profiles
    @Query("SELECT * FROM remote_servers ORDER BY id DESC")
    fun getRemoteServersFlow(): Flow<List<RemoteServerEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRemoteServer(server: RemoteServerEntity)

    @Delete
    suspend fun deleteRemoteServer(server: RemoteServerEntity)
}
