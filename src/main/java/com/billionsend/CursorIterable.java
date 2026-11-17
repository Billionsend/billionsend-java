package com.billionsend;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Every item of a cursor-paginated list, one at a time, fetching pages as the loop asks for them.
 * Starting with {@code before} set walks backward (using the first id of each page); otherwise it
 * walks forward from {@code after} (using the last id). A failed page is thrown as its {@link
 * BillionSendException}; so is a page that is not a list ({@code application_error}: no {@code
 * data} array, or more pages but no id on the item to continue from, thrown after that page's
 * items). Each call of {@link #iterator()} starts again from the first page.
 *
 * <pre>{@code
 * for (JsonNode contact : client.contacts().listAll(Map.of("limit", 100))) {
 *   System.out.println(contact.get("email").asText());
 * }
 * }</pre>
 */
public final class CursorIterable implements Iterable<JsonNode> {
  private final Function<Map<String, Object>, JsonNode> fetchPage;
  private final Map<String, ?> start;

  /**
   * Builds the iterable over any page fetcher.
   *
   * @param fetchPage fetches the page for the given query (the starting query with {@code after}
   *     or {@code before} set): a {@code {data: [...], has_more}} object whose items have an {@code
   *     id}
   * @param start the starting query, or {@code null}
   */
  public CursorIterable(Function<Map<String, Object>, JsonNode> fetchPage, Map<String, ?> start) {
    this.fetchPage = fetchPage;
    this.start = start == null ? Collections.emptyMap() : start;
  }

  /** The items as a sequential {@link Stream}, fetched as the stream is consumed. */
  public Stream<JsonNode> stream() {
    return StreamSupport.stream(
        Spliterators.spliteratorUnknownSize(iterator(), Spliterator.ORDERED), false);
  }

  @Override
  public Iterator<JsonNode> iterator() {
    return new Pages();
  }

  private final class Pages implements Iterator<JsonNode> {
    private final boolean backward = start.get("before") != null;
    private final Deque<JsonNode> items = new ArrayDeque<>();
    private Map<String, Object> cursor = new LinkedHashMap<>();
    private boolean last;
    private BillionSendException pending;

    Pages() {
      if (backward) {
        cursor.put("before", start.get("before"));
      } else if (start.get("after") != null && !"".equals(start.get("after"))) {
        cursor.put("after", start.get("after"));
      }
    }

    @Override
    public boolean hasNext() {
      while (items.isEmpty() && !last) {
        Map<String, Object> query = new LinkedHashMap<>(start);
        query.putAll(cursor);
        JsonNode page = fetchPage.apply(query);
        JsonNode data = page == null || !page.isObject() ? null : page.get("data");
        if (data == null || !data.isArray()) {
          last = true;
          throw new BillionSendException("The list page has no data array");
        }
        for (JsonNode item : data) {
          items.add(item);
        }
        if (!page.path("has_more").asBoolean(false) || data.size() == 0) {
          last = true;
          break;
        }
        JsonNode edge = backward ? data.get(0) : data.get(data.size() - 1);
        JsonNode id = edge == null ? null : edge.get("id");
        last = true;
        // Only a non-empty string continues the walk: an empty, numeric or structured id would
        // page from the wrong place.
        if (id == null || !id.isTextual() || id.asText().isEmpty()) {
          // Hand out this page first, then fail where the walk cannot go on.
          pending =
              new BillionSendException(
                  "The "
                      + (backward ? "first" : "last")
                      + " item of the list page has no id to continue from");
          break;
        }
        last = false;
        cursor = new LinkedHashMap<>();
        cursor.put(backward ? "before" : "after", id.asText());
      }
      if (items.isEmpty() && pending != null) {
        BillionSendException error = pending;
        pending = null;
        throw error;
      }
      return !items.isEmpty();
    }

    @Override
    public JsonNode next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      return items.poll();
    }
  }
}
