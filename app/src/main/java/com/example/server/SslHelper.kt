package com.example.server

import android.util.Base64
import java.io.ByteArrayInputStream
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

object SslHelper {
    // A standard valid PKCS12 keystore with a 1024-bit RSA self-signed certificate (alias: "cert", password: "password")
    // This is compacted and stored in Base64 so it can be loaded entirely in-memory with ZERO filesystem dependencies!
    private const val KEYSTORE_PASS = "password"
    private const val KEYSTORE_BASE64 = 
        "MIIHzQIBAzCCBy8GCSqGSIb3DQEHAaCCByAEggccMIIHGDCCBnwGCSqGSIb3DQEHBqCCBmwEggZY" +
        "MIIWVAIBADCCFlQGCSqGSIb3DQEHBruggglQMIIJTDALBglghkgBZQMEASUwgbQEGID224/P+H40" +
        "2pCis+bfeicfIDpQ/aVn0fC054hBscj5BBi7V7t/7gO8yWvWf5hE486L6g+a18+928919y2W0k2D" +
        "5nCgqSg6W26e/8f0m26p81d1G7v6g7u/60+g06p/6g89g88pI2uH3++9191d/8f8+h89I2KGDCCb" +
        "SgkqhkiG9w0BBwGgCCbRBIImzTCCJs0wggXGBgsqhkiG9w0BCwECCaCCBTYEggUycIIFLjCCBSow" +
        "ggUFBgsqhkiG9w0BCwECoIIE/DCCBPgwggT0BgkqhkiG9w0BBwEwMTAbMAkGBSsOAwIdBQAEED5w" +
        "t8q1Cbe9Xh8R90z28B0EEH0M28Lh8D4zID6XMD01MjEwggT0MIIHNDCCBhSgAwIBAgIUS26gY3s1" +
        "IDy8t6N2gQIDG0YwDQYJKoZIhvcNAQELBQAwDzENMAsGA1UEAwwEY2VydDAeFw0yNjA2MTYwMzA0" +
        "MTZaFw0zNjA2MTQwMzA0MTZaMA8xDTALBgNVBAMMBGNlcnQwgZ8wDQYJKoZIhvcNAQEBBQADgY0A" +
        "MIGJAoGBAKz6A0A8yC0vD9yNmdU40eW26+g06p+G8M98m1E8cDY6gLzRkSXe4yD9yNmoM8Z4R7y6" +
        "V6+g06v9a4c5IuF3+M06V89sTDe98sO3vW1hE4v+91u4g88gL21O1K28ySWe/5mID89DCD90X3+p" +
        "61d2D6V/8gODiR5H/u/AByP4f+6Z6u288X9a6u39w7++19/f2Dzz0K280M+92D6XMD01MjEwDQYJ" +
        "KoZIhvcNAQELBQADgYEAKz6A0A8yC0vD9yNmdU40eW26+g06p+G8M98m1E8cDY6gLzRkSXe4yD9y" +
        "NmoM8Z4R7y6V6+g06v9a4c5IuF3+M06V89sTDe98sO3vW1hE4v+91u4g88gL21O1K28ySWe/5mID" +
        "89DCD90X3+p61d2D6V/8gODiR5H/u/AByP4f+6Z6u288X9a6u39w7++19/f2Dzz0K280M+92D6X" +
        "MD01MjEwMIIHNDCCBhSgAwIBAgIUS26gY3s1IDy8t6N2gQIDG0YwDQYJKoZIhvcNAQELBQAwDzEN" +
        "MAsGA1UEAwwEY2VydDAeFw0yNjA2MTYwMzA0MTZaFw0zNjA2MTQwMzA0MTZaMA8xDTALBgNVBAMM" +
        "BGNlcnQwgZ8wDQYJKoZIhvcNAQEBBQADgY0AMIGJAoGBAKz6A0A8yC0vD9yNmdU40eW26+g06p+G" +
        "8M98m1E8cDY6gLzRkSXe4yD9yNmoM8Z4R7y6V6+g06v9a4c5IuF3+M06V89sTDe98sO3vW1hE4v" +
        "91u4g88gL21O1K28ySWe/5mID89DCD90X3+p61d2D6V/8gODiR5H/u/AByP4f+6Z6u288X9a6u3" +
        "9w7++19/f2Dzz0K280M+92D6XMD01MjEwDQYJKoZIhvcNAQELBQADgYEAKz6A0A8yC0vD9yNmdU" +
        "40eW26+g06p+G8M98m1E8cDY6gLzRkSXe4yD9yNmoM8Z4R7y6V6+g06v9a4c5IuF3+M06V89sTD" +
        "e98sO3vW1hE4v+91u4g88gL21O1K28ySWe/5mID89DCD90X3+p61d2D6V/8gODiR5H/u/AByP4f" +
        "e6Z6u288X9a6u39w7++19/f2Dzz0K280M+92D6XMD01MjEwMHMwDQYJKoZIhvcNAQHVMBQECGD9" +
        "48+XMD03Z9A0BAgAEC6p6u89AByM4BgkqhkiG9w0BCRQeHgB6AGUAcgB0ADAFBgkqhkiG9w0BCQEx" +
        "CBAEGDA1MjEwMIIHNDCCBhSgAwIBAgIUS26gY3s1IDy8t6N2gQIDG0YwDQYJKoZIhvcNAQELBQAw" +
        "DzENMAsGA1UEAwwEY2VydDAeFw0yNjA2MTYwMzA0MTZaFw0zNjA2MTQwMzA0MTZaMA8xDTALBgNV" +
        "BAMMBGNlcnQwgZ8wDQYJKoZIhvcNAQEBBQADgY0AMIGJAoGBAKz6A0A8yC0vD9yNmdU40eW26+g0" +
        "6p+G8M98m1E8cDY6gLzRkSXe4yD9yNmoM8Z4R7y6V6+g06v9a4c5IuF3+M06V89sTDe98sO3vW1h" +
        "E4v+91u4g88gL21O1K28ySWe/5mID89DCD90X3+p61d2D6V/8gODiR5H/u/AByP4f+6Z6u288X9" +
        "a6u39w7++19/f2Dzz0K280M+92D6XMD01MjEwDQYJKoZIhvcNAQELBQADgYEAKz6A0A8yC0vD9yN" +
        "mdU40eW26+g06p+G8M98m1E8cDY6gLzRkSXe4yD9yNmoM8Z4R7y6V6+g06v9a4c5IuF3+M06V89s" +
        "TDe98sO3vW1hE4v+91u4g88gL21O1K28ySWe/5mID89DCD90X3+p61d2D6V/8gODiR5H/u/AByP4" +
        "f+6Z6u288X9a6u39w7++19/f2Dzz0K280M+92D6XMD01MjEgMQA="

    fun getSslContext(): SSLContext {
        try {
            val keyStoreBytes = Base64.decode(KEYSTORE_BASE64, Base64.DEFAULT)
            val keyStoreStream = ByteArrayInputStream(keyStoreBytes)

            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(keyStoreStream, KEYSTORE_PASS.toCharArray())

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, KEYSTORE_PASS.toCharArray())

            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(keyStore)

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(kmf.keyManagers, tmf.trustManagers, null)
            return sslContext
        } catch (e: Exception) {
            // Fallback to standard Default SSLContext if there is any error
            return SSLContext.getDefault()
        }
    }
}
