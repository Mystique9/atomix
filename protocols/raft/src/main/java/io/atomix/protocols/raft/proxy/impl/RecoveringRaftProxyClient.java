/*
 * Copyright 2017-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.protocols.raft.proxy.impl;

import com.google.common.collect.Sets;
import io.atomix.protocols.raft.RaftClient;
import io.atomix.protocols.raft.RaftEvent;
import io.atomix.protocols.raft.RaftException;
import io.atomix.protocols.raft.RaftOperation;
import io.atomix.protocols.raft.service.ServiceType;
import io.atomix.protocols.raft.proxy.RaftProxy;
import io.atomix.protocols.raft.proxy.RaftProxyClient;
import io.atomix.protocols.raft.session.SessionId;
import io.atomix.utils.concurrent.Scheduled;
import io.atomix.utils.concurrent.Scheduler;
import io.atomix.utils.logging.ContextualLoggerFactory;
import io.atomix.utils.logging.LoggerContext;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Raft proxy that supports recovery.
 */
public class RecoveringRaftProxyClient implements RaftProxyClient {
  private final String clientId;
  private final RaftProxyClient.Builder proxyClientBuilder;
  private final Scheduler scheduler;
  private Logger log;
  private RaftProxyClient client;
  private volatile RaftProxy.State state = State.CONNECTED;
  private final Set<Consumer<State>> stateChangeListeners = Sets.newCopyOnWriteArraySet();
  private final Set<Consumer<RaftEvent>> eventListeners = Sets.newCopyOnWriteArraySet();
  private Scheduled recoverTask;
  private boolean recover = true;

  public RecoveringRaftProxyClient(String clientId, RaftProxyClient.Builder proxyClientBuilder, Scheduler scheduler) {
    this.clientId = checkNotNull(clientId);
    this.proxyClientBuilder = checkNotNull(proxyClientBuilder);
    this.scheduler = checkNotNull(scheduler);
    this.log = ContextualLoggerFactory.getLogger(getClass(), LoggerContext.builder(RaftClient.class)
        .addValue(clientId)
        .build());
    this.client = openClient().join();
  }

  @Override
  public SessionId sessionId() {
    return client.sessionId();
  }

  @Override
  public String name() {
    return client.name();
  }

  @Override
  public ServiceType serviceType() {
    return client.serviceType();
  }

  @Override
  public State getState() {
    return state;
  }

  /**
   * Sets the session state.
   *
   * @param state the session state
   */
  private synchronized void onStateChange(State state) {
    if (this.state != state) {
      log.debug("State changed: {}", state);
      this.state = state;
      stateChangeListeners.forEach(l -> l.accept(state));

      // If the session was closed then reopen it.
      if (state == State.CLOSED) {
        recover();
      }
    }
  }

  @Override
  public void addStateChangeListener(Consumer<State> listener) {
    stateChangeListeners.add(listener);
  }

  @Override
  public void removeStateChangeListener(Consumer<State> listener) {
    stateChangeListeners.remove(listener);
  }

  /**
   * Recovers the underlying proxy client.
   */
  private synchronized void recover() {
    recoverTask = null;
    RaftProxyClient newClient = openClient().join();
    if (newClient != null) {
      this.client = newClient;
      onStateChange(State.CONNECTED);
    }
  }

  /**
   * Opens the underlying proxy client.
   */
  private synchronized CompletableFuture<RaftProxyClient> openClient() {
    if (recoverTask == null) {
      CompletableFuture<RaftProxyClient> future = new CompletableFuture<>();
      openClient(future);
      return future;
    }
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Opens the underlying proxy client.
   */
  private synchronized void openClient(CompletableFuture<RaftProxyClient> future) {
    if (recover) {
      log.debug("Opening session");
      RaftProxyClient client;
      try {
        client = proxyClientBuilder.build();
        this.log = ContextualLoggerFactory.getLogger(getClass(), LoggerContext.builder(RaftProxy.class)
            .addValue(client.sessionId())
            .add("type", client.serviceType())
            .add("name", client.name())
            .build());
        client.addStateChangeListener(this::onStateChange);
        eventListeners.forEach(client::addEventListener);
        future.complete(client);
      } catch (RaftException.Unavailable e) {
        recoverTask = scheduler.schedule(Duration.ofSeconds(1), this::recover);
      }
    } else {
      future.complete(null);
    }
  }

  @Override
  public CompletableFuture<byte[]> execute(RaftOperation operation) {
    return client.execute(operation);
  }

  @Override
  public void addEventListener(Consumer<RaftEvent> consumer) {
    eventListeners.add(consumer);
    client.addEventListener(consumer);
  }

  @Override
  public void removeEventListener(Consumer<RaftEvent> consumer) {
    eventListeners.remove(consumer);
    client.removeEventListener(consumer);
  }

  @Override
  public boolean isOpen() {
    return state == State.CONNECTED;
  }

  @Override
  public synchronized CompletableFuture<Void> close() {
    recover = false;
    if (recoverTask != null) {
      recoverTask.cancel();
    }
    return client.close();
  }

  @Override
  public String toString() {
    return toStringHelper(this)
        .add("name", client.name())
        .add("serviceType", client.serviceType())
        .add("state", state)
        .toString();
  }
}