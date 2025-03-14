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

package org.apache.pekko.http.javadsl.server;

import org.apache.pekko.Done;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.event.Logging;
import org.apache.pekko.http.javadsl.ConnectHttp;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.http.javadsl.ServerBinding;
import org.apache.pekko.http.javadsl.settings.ServerSettings;
import org.apache.pekko.stream.ActorMaterializer;
import com.typesafe.config.ConfigFactory;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DEPRECATED, consider https://developer.lightbend.com/guides/akka-http-quickstart-java/ instead
 *
 * Bootstrap trait for Http Server. It helps booting up an pekko-http server by only defining the desired routes.
 * It offers additional hooks to modify the default behavior.
 *
 * @deprecated HttpApp this doesn't reflect the latest Pekko APIs, since Akka HTTP 10.2.0
 */
@Deprecated
public abstract class HttpApp extends AllDirectives {

  private AtomicReference<ServerBinding> serverBinding = new AtomicReference<>();
  /**
   * Holds a reference to the {@link ActorSystem} used to start this server. Stopping this system will interfere with the proper functioning condition of the server.
   */
  protected AtomicReference<ActorSystem> systemReference = new AtomicReference<>();

  /**
   * Start a server on the specified host and port.
   * Note that this method is blocking.
   */
  public void startServer(String host, int port) throws ExecutionException, InterruptedException {
      startServer(host, port, ServerSettings.create(ConfigFactory.load()));
  }

  /**
   * Start a server on the specified host and port, using the provided [[ActorSystem]]
   * Note that this method is blocking.
   *
   * @param system ActorSystem to use for starting the app,
   *   if null is passed in a new default ActorSystem will be created instead, which will
   *   be terminated when the server is stopped.
   */
  public void startServer(String host, int port, ActorSystem system) throws ExecutionException, InterruptedException {
    startServer(host, port, ServerSettings.create(system), Optional.ofNullable(system));
  }

  /**
   * Start a server on the specified host and port, using the provided settings.
   * Note that this method is blocking.
   */
  public void startServer(String host, int port, ServerSettings settings) throws ExecutionException, InterruptedException {
    startServer(host, port, settings, Optional.empty());
  }

  /**
   * Start a server on the specified host and port, using the provided settings and [[ActorSystem]].
   * Note that this method is blocking.
   *
   * @param system ActorSystem to use for starting the app,
   *   if null is passed in a new default ActorSystem will be created instead, which will
   *   be terminated when the server is stopped.
   */
  public void startServer(String host, int port, ServerSettings settings, ActorSystem system) throws ExecutionException, InterruptedException {
    startServer(host, port, settings, Optional.ofNullable(system));
  }

  /**
   * Start a server on the specified host and port, using the provided settings and [[ActorSystem]] if present.
   * Note that this method is blocking.
   * This method may throw an {@link ExecutionException} or {@link InterruptedException} if the future that signals that
   * the server should shutdown is interrupted or cancelled.
   *
   * @param system ActorSystem to use for starting the app,
   *   if an empty Optional is passed in a new default ActorSystem will be created instead, which will
   *   be terminated when the server is stopped.
   */
  public void startServer(String host, int port, ServerSettings settings, Optional<ActorSystem> system) throws ExecutionException, InterruptedException {

    final ActorSystem theSystem = system.orElseGet(() -> ActorSystem.create(Logging.simpleName(this).replaceAll("\\$", "")));
    systemReference.set(theSystem);
    final ActorMaterializer materializer = ActorMaterializer.create(theSystem);

    CompletionStage<ServerBinding> bindingFuture = Http
      .get(theSystem)
      .newServerAt(host, port)
      .withSettings(settings)
      .bind(routes());

    bindingFuture.handle((binding, exception) -> {
      if (exception != null) {
        postHttpBindingFailure(exception);
      } else {
        //setting the server binding for possible future uses in the client
        serverBinding.set(binding);
        postHttpBinding(binding);
      }
      return null;
    });

    try {
      bindingFuture
        .thenCompose(ignore -> waitForShutdownSignal(theSystem)) // chaining both futures to fail fast
        .toCompletableFuture()
        .exceptionally(ignored -> Done.getInstance()) // If the future fails, we want to complete normally
        .get(); // It's waiting forever because maybe there is never a shutdown signal
    } finally {
      bindingFuture.thenCompose(ServerBinding::unbind).handle( (success, exception) -> {
        postServerShutdown(Optional.ofNullable(exception), theSystem);
        if (!system.isPresent()) {
          // we created the system. we should cleanup!
          theSystem.terminate();
        }
        return null;
      });
    }

  }

  /**
   * It tries to retrieve the {@link ServerBinding} if the server has been successfully started. It throws an {@link IllegalStateException} otherwise.
   * You can use this method to attempt to retrieve the {@link ServerBinding} at any point in time to, for example, stop the server due to unexpected circumstances.
   */
  ServerBinding binding() {
    if (serverBinding.get() == null)
      throw new IllegalStateException("Binding not yet stored. Have you called startServer?");
    return serverBinding.get();
  }

  /**
   * Hook that will be called just after the server termination. Override this method if you want to perform some cleanup actions after the server is stopped.
   * The {@code failure} parameter contains a {@link Throwable} only if there has been a problem shutting down the server.
   */
  protected void postServerShutdown(Optional<Throwable> failure, ActorSystem system) {
    systemReference.get().log().info("Shutting down the server");
  }

  /**
   * Hook that will be called just after the Http server binding is done. Override this method if you want to perform some actions after the server is up.
   */
  protected void postHttpBinding (ServerBinding binding) {
    systemReference.get().log().info("Server online at http://" + binding.localAddress().getHostName() + ":" + binding.localAddress().getPort() + "/");
  }

  /**
   * Hook that will be called in case the Http server binding fails. Override this method if you want to perform some actions after the server binding failed.
   */
  protected void postHttpBindingFailure(Throwable cause) {
    systemReference.get().log().error(cause, "Error starting the server: " + cause.getMessage());
  }

  /**
   * Hook that lets the user specify the future that will signal the shutdown of the server whenever completed.
   */
  protected CompletionStage<Done> waitForShutdownSignal (ActorSystem system) {
    final CompletableFuture<Done> promise = new CompletableFuture<>();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> promise.complete(Done.getInstance())));
    CompletableFuture.runAsync(() -> {
      System.out.println("Press RETURN to stop...");
      try {
        if (System.in.read() >= 0)
          promise.complete(Done.getInstance());
      } catch (IOException e) {
        systemReference.get().log().error(e, "Problem occurred! " + e.getMessage());
      }
    });
    return promise;
  }

  /**
   * Override to implement the route that will be served by this http server.
   */
  protected abstract Route routes();

}
