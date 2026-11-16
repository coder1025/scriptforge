package dev.scriptforge.context;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.scriptforge.runtime.ContextAssembler;
import java.util.*;
import org.junit.jupiter.api.Test;

class ContextAssemblerTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final ContextAssembler assembler = new ContextAssembler(mapper);

  private Map<String, Object> state() {
    var s = new LinkedHashMap<String, Object>();
    s.put("taskId", "task-A");
    s.put("executionId", "writer-A");
    s.put("title", "数据隔离样例");
    s.put(
        "facts",
        List.of(Map.of("id", "f1", "text", "价格为199.50元，有效期至2026年12月。", "source", "source-17")));
    s.put("constraints", List.of("锁定s1，不得更改价格"));
    s.put("planRevision", 1);
    s.put("draftRevision", 2);
    s.put(
        "plan",
        List.of(
            Map.of("id", "s1", "angle", "介绍", "entryContext", "开场", "exitExpectation", "衔接s2")));
    s.put(
        "sections",
        List.of(Map.of("sectionId", "s1", "text", "这款产品的价格为199.50元。", "factIds", List.of("f1"))));
    s.put(
        "report",
        Map.of(
            "issues",
            List.of(
                Map.of(
                    "id",
                    "i1",
                    "sectionId",
                    "s1",
                    "severity",
                    "BLOCKER",
                    "quote",
                    "199.50",
                    "repair",
                    "核实有效期"))));
    return s;
  }

  @Test
  void compactionRetainsExactFactsVersionsAndIssuesWithPairedRecentCalls() throws Exception {
    var history = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 12; i++) history.addAll(turn("call-" + i, "historical".repeat(100)));
    var result = assembler.assemble("WRITER", state(), history, 24_000);
    assertTrue(result.compacted());
    assertTrue(result.estimatedTokens() <= 24_000);
    String snapshot = result.messages().get(1).get("content").toString();
    assertTrue(snapshot.contains("199.50元"));
    assertTrue(snapshot.contains("source-17"));
    assertTrue(snapshot.contains("核实有效期"));
    assertTrue(snapshot.contains("\"draftRevision\":2"));
    List<Map<String, Object>> kept = result.messages().subList(2, result.messages().size());
    assertEquals(0, kept.size() % 2);
    for (int i = 0; i < kept.size(); i += 2) {
      assertEquals("assistant", kept.get(i).get("role"));
      assertEquals("tool", kept.get(i + 1).get("role"));
      var calls = (List<Map<String, Object>>) kept.get(i).get("tool_calls");
      assertEquals(calls.getFirst().get("id"), kept.get(i + 1).get("tool_call_id"));
    }
    assertEquals("call-11", kept.getLast().get("tool_call_id"));
  }

  @Test
  void dropsOrphanAndIncompleteProtocolBlocksTogether() {
    var history = new ArrayList<Map<String, Object>>();
    history.add(Map.of("role", "tool", "tool_call_id", "missing", "content", "orphan-secret"));
    history.add(turn("pending", "unused").getFirst());
    history.addAll(turn("complete", "result"));
    var result = assembler.assemble("WRITER", state(), history, 24_000);
    String encoded = result.messages().toString();
    assertFalse(encoded.contains("orphan-secret"));
    assertFalse(encoded.contains("pending"));
    assertTrue(encoded.contains("complete"));
  }

  @Test
  void refusesOversizedEssentialSourcesInsteadOfSummarizingNumbersAway() {
    var state = state();
    state.put(
        "facts",
        List.of(Map.of("id", "f1", "text", "important fact ".repeat(5000), "source", "s")));
    assertThrows(
        ContextAssembler.ContextOverflowException.class,
        () -> assembler.assemble("REVIEWER", state, List.of(), 24_000));
  }

  @Test
  void contextsDoNotShareMutableMessagesOrTaskIdentity() {
    var other = state();
    other.put("taskId", "task-B");
    other.put("executionId", "reviewer-B");
    var a = assembler.assemble("WRITER", state(), List.of(), 24_000);
    var b = assembler.assemble("REVIEWER", other, List.of(), 24_000);
    assertFalse(b.messages().toString().contains("task-A"));
    assertFalse(a.messages().toString().contains("task-B"));
    assertNotEquals(a.messages().getFirst(), b.messages().getFirst());
  }

  private List<Map<String, Object>> turn(String id, String content) {
    return List.of(
        Map.of(
            "role",
            "assistant",
            "tool_calls",
            List.of(
                Map.of(
                    "id",
                    id,
                    "type",
                    "function",
                    "function",
                    Map.of("name", "read_context", "arguments", "{}")))),
        Map.of("role", "tool", "tool_call_id", id, "content", content));
  }
}
