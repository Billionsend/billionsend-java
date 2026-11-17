package com.billionsend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Every Java block of the README is in {@link ReadmeExamples}, which the build compiles. */
class ReadmeTest {
  static List<String> lines(String text) {
    return text.lines().map(String::strip).filter(l -> !l.isEmpty()).collect(Collectors.toList());
  }

  @Test
  void everyJavaBlockOfTheReadmeCompilesInReadmeExamples() throws Exception {
    String readme = Files.readString(Path.of("README.md"));
    List<String> examples =
        lines(Files.readString(Path.of("src/test/java/com/billionsend/ReadmeExamples.java")));
    Matcher blocks = Pattern.compile("```java\\n(.*?)```", Pattern.DOTALL).matcher(readme);
    List<String> missing = new ArrayList<>();
    int count = 0;
    while (blocks.find()) {
      count++;
      List<String> block = lines(blocks.group(1));
      if (Collections.indexOfSubList(examples, block) < 0) {
        missing.add(block.get(0));
      }
    }
    assertTrue(count >= 10, "README Java blocks: " + count);
    assertEquals(List.of(), missing, "README blocks not in ReadmeExamples.java");
  }
}
