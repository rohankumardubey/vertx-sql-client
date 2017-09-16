/*
 * Copyright (C) 2017 Julien Viet
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
 *
 */

package com.julienviet.pgclient.impl;


import com.julienviet.pgclient.PgConnection;
import com.julienviet.pgclient.codec.Message;
import com.julienviet.pgclient.codec.decoder.MessageDecoder;
import com.julienviet.pgclient.codec.encoder.MessageEncoder;
import com.julienviet.pgclient.codec.encoder.message.Terminate;
import io.netty.channel.ChannelPipeline;
import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.VertxException;
import io.vertx.core.impl.ContextImpl;
import io.vertx.core.impl.NetSocketInternal;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class DbConnection {

  enum Status {

    CLOSED, CONNECTED, CLOSING

  }

  private final NetSocketInternal socket;
  private final ArrayDeque<CommandBase> inflight = new ArrayDeque<>();
  private final ArrayDeque<CommandBase> pending = new ArrayDeque<>();
  final PgClientImpl client;
  private final Context context;
  private Status status = Status.CONNECTED;
  private Handler<Void> closeHandler;
  private Handler<Throwable> exceptionHandler;
  private final PgConnection conn;

  public DbConnection(PgClientImpl client,
                      NetSocketInternal socket, ContextImpl context) {
    this.socket = socket;
    this.client = client;
    this.context = context;
    this.conn = new PgConnectionImpl(this, client.cachePreparedStatements);
  }

  void init(String username, String password, String database, Handler<AsyncResult<DbConnection>> completionHandler) {
    ChannelPipeline pipeline = socket.channelHandlerContext().pipeline();
    pipeline.addBefore("handler", "decoder", new MessageDecoder());
    pipeline.addBefore("handler", "encoder", new MessageEncoder());
    socket.closeHandler(this::handleClosed);
    socket.exceptionHandler(this::handleException);
    socket.messageHandler(this::handleMessage);
    schedule(new StartupCommand(username, password, database, completionHandler));
  }

  void closeHandler(Handler<Void> handler) {
    closeHandler = handler;
  }

  void exceptionHandler(Handler<Throwable> handler) {
    exceptionHandler = handler;
  }

  void writeMessage(Message cmd) {
    socket.writeMessage(cmd);
  }

  void doClose() {
    if (Vertx.currentContext() == context) {
      if (status == Status.CONNECTED) {
        status = Status.CLOSING;
        socket.writeMessage(Terminate.INSTANCE);
      }
    } else {
      context.runOnContext(v -> doClose());
    }
  }

  void schedule(CommandBase cmd) {
    if (Vertx.currentContext() != context) {
      throw new IllegalStateException();
    }
    if (status == Status.CONNECTED) {
      pending.add(cmd);
      checkPending();
    } else {
      cmd.fail(new VertxException("Connection not open " + status));
    }
  }

  private void checkPending() {
    CommandBase cmd;
    while (inflight.size() < client.pipeliningLimit && (cmd = pending.poll()) != null) {
      cmd.exec(this, v -> {
        inflight.poll();
        checkPending();
      });
      inflight.add(cmd);
    }
  }

  private void handleMessage(Object msg) {
    Message pgMsg = (Message) msg;
    CommandBase cmd = inflight.peek();
    if (cmd != null) {
      cmd.handleMessage(pgMsg);
    } else {
      System.out.println("Uh oh, no inflight command for " + msg);
    }
  }

  private void handleClosed(Void v1) {
    status = Status.CLOSED;
    for (ArrayDeque<CommandBase> q : Arrays.asList(inflight, pending)) {
      CommandBase cmd;
      while ((cmd = q.poll()) != null) {
        CommandBase c = cmd;
        context.runOnContext(v2 -> c.fail(new VertxException("closed")));
      }
    }
    Handler<Void> handler = this.closeHandler;
    if (handler != null) {
      context.runOnContext(handler);
    }
  }

  private synchronized void handleException(Throwable t) {
    Handler<Throwable> handler = this.exceptionHandler;
    if (handler != null) {
      handler.handle(t);
    }
    socket.close();
  }
}