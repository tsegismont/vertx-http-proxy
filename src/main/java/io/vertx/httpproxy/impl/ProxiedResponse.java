/*
 * Copyright (c) 2011-2025 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package io.vertx.httpproxy.impl;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.streams.Pipe;
import io.vertx.core.streams.ReadStream;
import io.vertx.httpproxy.Body;
import io.vertx.httpproxy.MediaType;
import io.vertx.httpproxy.ProxyRequest;
import io.vertx.httpproxy.ProxyResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static io.vertx.core.http.HttpHeaders.CONTENT_LENGTH;

class ProxiedResponse implements ProxyResponse {

  private final ProxiedRequest request;
  private final HttpServerResponse proxiedResponse;
  private int statusCode;
  private String statusMessage;
  private Body body;
  private final MultiMap headers;
  private HttpClientResponse response;
  private long maxAge;
  private String etag;
  private boolean publicCacheControl;

  ProxiedResponse(ProxiedRequest request, HttpServerResponse proxiedResponse) {
    this.response = null;
    this.statusCode = 200;
    this.headers = MultiMap.caseInsensitiveMultiMap();
    this.request = request;
    this.proxiedResponse = proxiedResponse;
  }

  ProxiedResponse(ProxiedRequest request, HttpServerResponse proxiedResponse, HttpClientResponse response) {

    // Determine content length
    long contentLength = -1L;
    String contentLengthHeader = response.getHeader(HttpHeaders.CONTENT_LENGTH);
    if (contentLengthHeader != null) {
      try {
        contentLength = Long.parseLong(contentLengthHeader);
      } catch (NumberFormatException e) {
        // Ignore ???
      }
    }

    // Content type
    String contentType = response.getHeader(HttpHeaders.CONTENT_TYPE);

    this.request = request;
    this.response = response;
    this.proxiedResponse = proxiedResponse;
    this.statusCode = response.statusCode();
    this.statusMessage = response.statusMessage();
    this.body = Body.body(response, contentLength, contentType);

    long maxAge = -1;
    boolean publicCacheControl = false;
    String cacheControlHeader = response.getHeader(HttpHeaders.CACHE_CONTROL);
    if (cacheControlHeader != null) {
      CacheControl cacheControl = new CacheControl().parse(cacheControlHeader);
      if (cacheControl.isPublic()) {
        publicCacheControl = true;
        if (cacheControl.maxAge() > 0) {
          maxAge = (long)cacheControl.maxAge() * 1000;
        } else {
          String dateHeader = response.getHeader(HttpHeaders.DATE);
          String expiresHeader = response.getHeader(HttpHeaders.EXPIRES);
          if (dateHeader != null && expiresHeader != null) {
            maxAge = ParseUtils.parseHeaderDate(expiresHeader).toEpochMilli() - ParseUtils.parseHeaderDate(dateHeader).toEpochMilli();
          }
        }
      }
    }
    this.maxAge = maxAge;
    this.publicCacheControl = publicCacheControl;
    this.etag = response.getHeader(HttpHeaders.ETAG);
    this.headers = MultiMap.caseInsensitiveMultiMap().addAll(response.headers());
  }

  @Override
  public ProxyRequest request() {
    return request;
  }

  @Override
  public int getStatusCode() {
    return statusCode;
  }

  @Override
  public ProxyResponse setStatusCode(int sc) {
    statusCode = sc;
    return this;
  }

  @Override
  public String getStatusMessage() {
    return statusMessage;
  }

  @Override
  public ProxyResponse setStatusMessage(String statusMessage) {
    this.statusMessage = statusMessage;
    return this;
  }

  @Override
  public Body getBody() {
    return body;
  }

  @Override
  public ProxyResponse setBody(Body body) {
    this.body = body;
    return this;
  }

  @Override
  public HttpClientResponse proxiedResponse() {
    return response;
  }

  @Override
  public boolean publicCacheControl() {
    return publicCacheControl;
  }

  @Override
  public long maxAge() {
    return maxAge;
  }

  @Override
  public String etag() {
    return etag;
  }

  @Override
  public MultiMap headers() {
    return headers;
  }

  @Override
  public ProxyResponse putHeader(CharSequence name, CharSequence value) {
    headers.set(name, value);
    return this;
  }

  @Override
  public Future<Void> send() {
    // Set stuff
    proxiedResponse.setStatusCode(statusCode);

    if(statusMessage != null) {
      proxiedResponse.setStatusMessage(statusMessage);
    }

    // Date header
    Instant date = HttpUtils.dateHeader(headers);
    if (date == null) {
      date = Instant.now();
    }
    try {
      proxiedResponse.putHeader("date", ParseUtils.formatHttpDate(date));
    } catch (Exception e) {
      e.printStackTrace();
    }

    // Warning header
    List<String> warningHeaders = headers.getAll("warning");
    if (warningHeaders.size() > 0) {
      warningHeaders = new ArrayList<>(warningHeaders);
      String dateHeader = headers.get("date");
      Instant dateInstant = dateHeader != null ? ParseUtils.parseHeaderDate(dateHeader) : null;
      Iterator<String> i = warningHeaders.iterator();
      // Suppress incorrect warning header
      while (i.hasNext()) {
        String warningHeader = i.next();
        Instant warningInstant = ParseUtils.parseWarningHeaderDate(warningHeader);
        if (warningInstant != null && dateInstant != null && !warningInstant.equals(dateInstant)) {
          i.remove();
        }
      }
    }
    proxiedResponse.putHeader("warning", warningHeaders);

    // Handle other headers
    headers.forEach(header -> {
      String name = header.getKey();
      String value = header.getValue();
      if (name.equals("content-type") && body != null) {
        // Skip
      } else if (name.equalsIgnoreCase("date") || name.equalsIgnoreCase("warning") || name.equalsIgnoreCase("transfer-encoding")) {
        // Skip
      } else {
        proxiedResponse.headers().add(name, value);
      }
    });

    if (body == null) {
      if (response != null && response.headers().contains(CONTENT_LENGTH)) {
        proxiedResponse.putHeader(CONTENT_LENGTH, "0");
      }
      return proxiedResponse.end();
    } else {
      String mediaType = body.mediaType();
      if (mediaType != null) {
        proxiedResponse.putHeader(HttpHeaderNames.CONTENT_TYPE, mediaType);
      }
      long len = body.length();
      if (len >= 0) {
        proxiedResponse.putHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(len));
      } else {
        if (request.proxiedRequest().version() == HttpVersion.HTTP_1_0) {
          // Special handling for HTTP 1.0 clients that cannot handle chunked encoding
          // we need to buffer the content
          BufferingWriteStream buffer = new BufferingWriteStream();
          return
            body
            .stream()
            .pipeTo(buffer)
            .compose(v -> {
              Buffer content = buffer.content();
              return proxiedResponse.end(content);
            });
        }
        proxiedResponse.setChunked(true);
      }
      return sendResponse(body.stream());
    }
  }

  @Override
  public ProxyResponse release() {
    if (response != null) {
      response.resume();
      response = null;
      body = null;
      headers.clear();
    }
    return this;
  }

  private Future<Void> sendResponse(ReadStream<Buffer> body) {
    Pipe<Buffer> pipe = body.pipe();
    pipe.endOnSuccess(true);
    pipe.endOnFailure(false);
    return pipe
      .to(proxiedResponse)
      .andThen(ar -> {
      if (ar.failed()) {
        request.request.reset();
        proxiedResponse.reset();
      }
    });
  }
}
