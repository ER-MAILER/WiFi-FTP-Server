package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ftp_config")
data class FtpConfigEntity(
    @PrimaryKey val id: Int = 1,
    val port: Int = 2121,
    val anonymous: Boolean = true,
    val username: String = "admin",
    val password: String = "12345",
    val rootDirType: String = "INTERNAL", // "SANDBOX" or "INTERNAL"
    val customPath: String = "",
    val serverProtocol: String = "FTP" // "FTP", "FTPS", "SFTP", "TFTP"
)

@Entity(tableName = "remote_servers")
data class RemoteServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val label: String,
    val host: String,
    val port: Int,
    val protocol: String, // "FTP", "FTPS", "SFTP", "TFTP"
    val username: String,
    val password: String,
    val anonymous: Boolean = false
)
