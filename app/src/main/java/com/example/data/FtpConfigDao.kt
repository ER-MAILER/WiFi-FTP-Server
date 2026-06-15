package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FtpConfigDao {
    @Query("SELECT * FROM ftp_config WHERE id = 1 LIMIT 1")
    fun getConfigFlow(): Flow<FtpConfigEntity?>

    @Query("SELECT * FROM ftp_config WHERE id = 1 LIMIT 1")
    suspend fun getConfig(): FtpConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveConfig(config: FtpConfigEntity)
}
