package dev.scriptforge.tools;

import java.util.*;

/** Provider-facing JSON schemas. Host validation remains mandatory even in strict mode. */
public final class ToolCatalog {
  private ToolCatalog() {}

  public static List<Map<String, Object>> forRole(String role) {
    if (!Set.of("WRITER", "REVIEWER").contains(role))
      throw new IllegalArgumentException("Unknown agent role");
    var tools = new ArrayList<Map<String, Object>>();
    tools.add(
        tool(
            "read_context",
            "Read persisted sources in this task. Reviewers read their frozen draft. "
                + "Use section IDs to retrieve exact text; summaries are not a substitute for source evidence. "
                + "This tool never changes text or completes a review.",
            object(
                "kind", enumeration("facts", "plan", "draft", "section", "report", "ledger"),
                "ids", array(string(80), 0, 24))));
    tools.add(
        tool(
            "search_history",
            "Search only authorized prior scripts for the current project. "
                + "No matches does not establish originality. Compare quoted source text before reporting duplication.",
            object("query", nonemptyString(200), "limit", integer(1, 5))));
    if (role.equals("WRITER")) {
      tools.add(
          tool(
              "save_plan",
              "Save the complete section plan with current expected revision. "
                  + "Each section must add new information, cite supplied fact IDs, and define entry/exit continuity. "
                  + "dependsOn lists earlier section IDs. Planning is not writing: use patch_script for prose.",
              object(
                  "expectedPlanRevision", integer(0, Integer.MAX_VALUE),
                  "sections",
                      array(
                          object(
                              "id",
                              nonemptyString(40),
                              "title",
                              string(120),
                              "purpose",
                              string(500),
                              "angle",
                              string(300),
                              "factIds",
                              array(nonemptyString(40), 1, 30),
                              "targetSeconds",
                              integer(5, 1800),
                              "entryContext",
                              string(500),
                              "newInformation",
                              string(500),
                              "exitExpectation",
                              string(500),
                              "dependsOn",
                              array(string(80), 0, 24)),
                          1,
                          24))));
      tools.add(
          tool(
              "patch_script",
              "Write full replacement prose for selected planned sections, "
                  + "using the latest draft and plan revisions. Preserve unaffected sections. "
                  + "Use actual source fact IDs, never invent claims. A successful save is not quality approval.",
              object(
                  "expectedDraftRevision",
                  integer(0, Integer.MAX_VALUE),
                  "planRevision",
                  integer(1, Integer.MAX_VALUE),
                  "edits",
                  array(
                      object(
                          "sectionId",
                          nonemptyString(40),
                          "text",
                          Map.of("type", "string", "minLength", 20, "maxLength", 6000),
                          "factIds",
                          array(nonemptyString(40), 1, 30)),
                      1,
                      6),
                  "reason",
                  string(1000))));
      tools.add(
          tool(
              "evaluate_candidate",
              "Request independent review of the complete current draft. "
                  + "The host persists a read-only Reviewer execution and suspends this Writer until its report is ready. "
                  + "Do not poll. Each edited draft requires a fresh report.",
              object("draftRevision", integer(1, Integer.MAX_VALUE))));
      tools.add(
          tool(
              "finish_run",
              "Request delivery only after a PASS report for the exact current complete draft. "
                  + "The host checks versions, coverage, rules and provenance. Use NEEDS_INPUT or NEEDS_REVIEW "
                  + "when evidence or quality remains unresolved; never call incomplete work complete.",
              object(
                  "outcome",
                  enumeration("SUBMIT", "NEEDS_INPUT", "NEEDS_REVIEW"),
                  "draftRevision",
                  integer(0, Integer.MAX_VALUE),
                  "reportId",
                  string(100),
                  "reason",
                  string(1000))));
    } else {
      tools.add(
          tool(
              "inspect_sections",
              "Read full frozen section text and run selected deterministic checks. "
                  + "Inspect every section with FACTS, REPETITION and CONTINUITY before reporting PASS. "
                  + "For adjacency and explicit dependencies, inspect both endpoints together. "
                  + "Checks are evidence, not a semantic guarantee: independently assess support, creativity and transitions.",
              object(
                  "sectionIds", array(string(80), 1, 24),
                  "checks", array(enumeration("FACTS", "REPETITION", "CONTINUITY"), 1, 3))));
      tools.add(
          tool(
              "submit_review",
              "Submit an evidence-bearing report for the exact frozen revision. "
                  + "Every issue must quote text actually present in its section and explain source evidence plus a specific repair. "
                  + "PASS requires complete inspected coverage and no blocking findings. "
                  + "Choose NEEDS_REVIEW for uncertainty; do not invent faults. This tool cannot edit or publish the draft.",
              object(
                  "draftRevision", integer(1, Integer.MAX_VALUE),
                  "verdict", enumeration("PASS", "REVISE", "NEEDS_REVIEW"),
                  "issues",
                      array(
                          object(
                              "id",
                              string(100),
                              "sectionId",
                              nonemptyString(40),
                              "type",
                              string(80),
                              "severity",
                              enumeration("BLOCKER", "WARNING"),
                              "quote",
                              string(1500),
                              "evidence",
                              string(2500),
                              "repair",
                              string(1500)),
                          0,
                          50),
                  "summary", string(2000))));
    }
    return List.copyOf(tools);
  }

  public static boolean allowed(String role, String name) {
    if (!Set.of("WRITER", "REVIEWER").contains(role)) return false;
    return forRole(role).stream()
        .anyMatch(t -> ((Map<?, ?>) t.get("function")).get("name").equals(name));
  }

  private static Map<String, Object> tool(
      String name, String description, Map<String, Object> schema) {
    return Map.of(
        "type",
        "function",
        "function",
        Map.of("name", name, "description", description, "strict", true, "parameters", schema));
  }

  private static Map<String, Object> object(Object... pairs) {
    var properties = new LinkedHashMap<String, Object>();
    for (int i = 0; i < pairs.length; i += 2) properties.put((String) pairs[i], pairs[i + 1]);
    return Map.of(
        "type",
        "object",
        "properties",
        properties,
        "required",
        new ArrayList<>(properties.keySet()),
        "additionalProperties",
        false);
  }

  private static Map<String, Object> string(int max) {
    return Map.of("type", "string", "maxLength", max);
  }

  private static Map<String, Object> nonemptyString(int max) {
    return Map.of("type", "string", "minLength", 1, "maxLength", max);
  }

  private static Map<String, Object> enumeration(String... values) {
    return Map.of("type", "string", "enum", List.of(values));
  }

  private static Map<String, Object> integer(int min, int max) {
    return Map.of("type", "integer", "minimum", min, "maximum", max);
  }

  private static Map<String, Object> array(Map<String, Object> items, int min, int max) {
    return Map.of("type", "array", "items", items, "minItems", min, "maxItems", max);
  }
}
