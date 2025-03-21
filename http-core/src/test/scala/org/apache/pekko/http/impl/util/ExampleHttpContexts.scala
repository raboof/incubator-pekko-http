/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.http.impl.util

import java.io.InputStream
import java.net.InetSocketAddress
import java.security.{ KeyStore, SecureRandom }
import java.security.cert.{ Certificate, CertificateFactory }

import org.apache.pekko
import pekko.actor.ActorSystem
import javax.net.ssl.{ KeyManagerFactory, SSLContext, TrustManagerFactory }
import pekko.http.scaladsl.{ ClientTransport, ConnectionContext, Http }
import pekko.http.impl.util.JavaMapping.Implicits._
import pekko.http.scaladsl.settings.ClientConnectionSettings
import pekko.stream.scaladsl.Flow
import pekko.util.ByteString

import scala.concurrent.Future

/**
 * These are HTTPS example configurations that take key material from the resources/key folder.
 */
object ExampleHttpContexts {

  def getExampleServerContext() = exampleServerContext.asJava

  val exampleServerContext = {
    // never put passwords into code!
    val password = "abcdef".toCharArray

    val ks = KeyStore.getInstance("PKCS12")
    ks.load(resourceStream("keys/server.p12"), password)

    val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(ks, password)

    val context = SSLContext.getInstance("TLS")
    context.init(keyManagerFactory.getKeyManagers, null, new SecureRandom)

    ConnectionContext.httpsServer(context)
  }

  val exampleClientContext = {
    val certStore = KeyStore.getInstance(KeyStore.getDefaultType)
    certStore.load(null, null)
    // only do this if you want to accept a custom root CA. Understand what you are doing!
    certStore.setCertificateEntry("ca", loadX509Certificate("keys/rootCA.crt"))

    val certManagerFactory = TrustManagerFactory.getInstance("SunX509")
    certManagerFactory.init(certStore)

    val context = SSLContext.getInstance("TLSv1.2")
    context.init(null, certManagerFactory.getTrustManagers, new SecureRandom)
    ConnectionContext.httpsClient(context)
  }

  def resourceStream(resourceName: String): InputStream = {
    val is = getClass.getClassLoader.getResourceAsStream(resourceName)
    require(is ne null, s"Resource $resourceName not found")
    is
  }

  def loadX509Certificate(resourceName: String): Certificate =
    CertificateFactory.getInstance("X.509").generateCertificate(resourceStream(resourceName))

  /**
   * A client transport that will rewrite the target address to a fixed address. This can be used
   * to pretend to connect to pekko.example.org which is required to connect to the example server certificate.
   */
  def proxyTransport(realAddress: InetSocketAddress): ClientTransport =
    new ClientTransport {
      override def connectTo(host: String, port: Int, settings: ClientConnectionSettings)(
          implicit system: ActorSystem): Flow[ByteString, ByteString, Future[Http.OutgoingConnection]] =
        ClientTransport.TCP.connectTo(realAddress.getHostString, realAddress.getPort, settings)
    }
}
