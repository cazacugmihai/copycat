/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.kuujo.copycat.vertx;

import net.kuujo.copycat.protocol.ProtocolClient;
import net.kuujo.copycat.protocol.ProtocolException;
import net.kuujo.copycat.protocol.rpc.Response;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.impl.DefaultVertx;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.net.NetClient;
import org.vertx.java.core.net.NetSocket;
import org.vertx.java.core.parsetools.RecordParser;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Vert.x TCP protocol client.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class VertxTcpProtocolClient implements ProtocolClient {
  private static final String DELIMITER = "\\x00";
  private Vertx vertx;
  private final String host;
  private final int port;
  private boolean trustAll;
  private final VertxTcpProtocol protocol;
  private NetClient client;
  private NetSocket socket;
  private final Map<Object, ResponseHolder> responses = new HashMap<>(1000);

  /**
   * Holder for response handlers.
   */
  private static class ResponseHolder {
    private final CompletableFuture<ByteBuffer> future;
    private final long timer;
    private ResponseHolder(long timerId, CompletableFuture<ByteBuffer> future) {
      this.timer = timerId;
      this.future = future;
    }
  }

  public VertxTcpProtocolClient(String host, int port, VertxTcpProtocol protocol) {
    this.host = host;
    this.port = port;
    this.protocol = protocol;
  }

  /**
   * Sets whether to trust all server certs.
   *
   * @param trustAll Whether to trust all server certs.
   */
  public void setTrustAll(boolean trustAll) {
    this.trustAll = trustAll;
  }

  /**
   * Returns whether to trust all server certs.
   *
   * @return Whether to trust all server certs.
   */
  public boolean isTrustAll() {
    return trustAll;
  }

  /**
   * Sets whether to trust all server certs, returning the protocol for method chaining.
   *
   * @param trustAll Whether to trust all server certs.
   * @return The TCP protocol.
   */
  public VertxTcpProtocolClient withTrustAll(boolean trustAll) {
    this.trustAll = trustAll;
    return this;
  }

  @Override
  public CompletableFuture<ByteBuffer> write(ByteBuffer request) {
    CompletableFuture<ByteBuffer> future = new CompletableFuture<>();
    if (socket != null) {
      socket.write(new Buffer(request.array()).appendString(DELIMITER));
    } else {
      future.completeExceptionally(new ProtocolException("Client not connected"));
    }
    return future;
  }

  /**
   * Handles an identifiable response.
   */
  @SuppressWarnings("unchecked")
  private void handleResponse(Object id, ByteBuffer response) {
    ResponseHolder holder = responses.remove(id);
    if (holder != null) {
      vertx.cancelTimer(holder.timer);
      holder.future.complete(response);
    }
  }

  /**
   * Handles an identifiable error.
   */
  private void handleError(Object id, Throwable error) {
    ResponseHolder holder = responses.remove(id);
    if (holder != null) {
      vertx.cancelTimer(holder.timer);
      holder.future.completeExceptionally(error);
    }
  }

  /**
   * Stores a response callback by ID.
   */
  private <T extends Response> void storeFuture(final Object id, CompletableFuture<ByteBuffer> future) {
    long timerId = vertx.setTimer(5000, timer -> {
      handleError(id, new ProtocolException("Request timed out"));
    });
    ResponseHolder holder = new ResponseHolder(timerId, future);
    responses.put(id, holder);
  }

  @Override
  public CompletableFuture<Void> connect() {
    final CompletableFuture<Void> future = new CompletableFuture<>();

    if (vertx == null) {
      vertx = new DefaultVertx();
    }

    if (client == null) {
      client = vertx.createNetClient();
      client.setTCPKeepAlive(true);
      client.setTCPNoDelay(true);
      client.setSendBufferSize(protocol.getSendBufferSize());
      client.setReceiveBufferSize(protocol.getReceiveBufferSize());
      client.setSSL(protocol.isSsl());
      client.setKeyStorePath(protocol.getKeyStorePath());
      client.setKeyStorePassword(protocol.getKeyStorePassword());
      client.setTrustStorePath(protocol.getTrustStorePath());
      client.setTrustStorePassword(protocol.getTrustStorePassword());
      client.setTrustAll(trustAll);
      client.setUsePooledBuffers(true);
      client.connect(port, host, new Handler<AsyncResult<NetSocket>>() {
        @Override
        public void handle(AsyncResult<NetSocket> result) {
          if (result.failed()) {
            future.completeExceptionally(result.cause());
          } else {
            socket = result.result();
            socket.dataHandler(RecordParser.newDelimited(DELIMITER, new Handler<Buffer>() {
              @Override
              public void handle(Buffer buffer) {
                JsonObject response = new JsonObject(buffer.toString());
                Object id = response.getValue("id");
                if (response.getString("status").equals("ok")) {
                  handleResponse(id, ByteBuffer.wrap(response.getBinary("response")));
                } else {
                  handleError(id, new ProtocolException(response.getString("message")));
                }
              }
            }));
            future.complete(null);
          }
        }
      });
    } else {
      future.complete(null);
    }
    return future;
  }

  @Override
  public CompletableFuture<Void> close() {
    final CompletableFuture<Void> future = new CompletableFuture<>();
    if (client != null && socket != null) {
      socket.closeHandler(new Handler<Void>() {
        @Override
        public void handle(Void event) {
          socket = null;
          client.close();
          client = null;
          future.complete(null);
        }
      }).close();
    } else if (client != null) {
      client.close();
      client = null;
      future.complete(null);
    } else {
      future.complete(null);
    }
    return future;
  }

}
