package com.billionsend.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;

/** An open {@code text/event-stream} response. */
public final class StreamConnection implements AutoCloseable {
  private final HttpResponse<InputStream> response;
  private boolean closed;

  StreamConnection(HttpResponse<InputStream> response) {
    this.response = response;
  }

  /** The response, whose body is the stream. */
  public HttpResponse<InputStream> response() {
    return response;
  }

  /** The body of the stream. */
  public InputStream body() {
    return response.body();
  }

  /** Closes the connection; closing twice does nothing. */
  @Override
  public void close() {
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
    }
    try {
      response.body().close();
    } catch (IOException ignored) {
      // Closing a stream that already failed.
    }
  }
}
