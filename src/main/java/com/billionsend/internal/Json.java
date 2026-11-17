package com.billionsend.internal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The JSON mapper the SDK reads and writes with. */
public final class Json {
  /** Shared, thread-safe once configured. */
  public static final ObjectMapper MAPPER =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private Json() {}
}
