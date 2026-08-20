package com.example.jellyfinserver.core

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object NetworkManager {

    fun waitForHttpServer(urlStr: String, timeoutSeconds: Int): Boolean {
        val startTime = System.currentTimeMillis()
        val endTime = startTime + (timeoutSeconds * 1000)

        while (System.currentTimeMillis() < endTime) {
            try {
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 1000
                conn.readTimeout = 1000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                conn.disconnect()
                if (code == 200 || code == 302 || code == 401) {
                    return true
                }
            } catch (_: Exception) {}
            Thread.sleep(1000)
        }
        return false
    }

    fun configureNetworkSettings(configDir: File) {
        val networkXml = File(configDir, "network.xml")
        val defaultXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <NetworkConfiguration xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
              <RequireHttps>false</RequireHttps>
              <CertificatePath />
              <CertificatePassword />
              <BaseUrl />
              <EnableRemoteAccess>true</EnableRemoteAccess>
              <LocalNetworkSubnets />
              <LocalNetworkAddresses />
              <EnableIPv4>true</EnableIPv4>
              <EnableIPv6>false</EnableIPv6>
              <IsStartupWizardCompleted>false</IsStartupWizardCompleted>
            </NetworkConfiguration>
        """.trimIndent()

        try {
            if (!networkXml.exists()) {
                networkXml.writeText(defaultXml)
            } else {
                var content = networkXml.readText()
                if (content.contains("<EnableRemoteAccess>false</EnableRemoteAccess>")) {
                    content = content.replace("<EnableRemoteAccess>false</EnableRemoteAccess>", "<EnableRemoteAccess>true</EnableRemoteAccess>")
                    networkXml.writeText(content)
                }
            }
        } catch (_: Exception) {}
    }
}
