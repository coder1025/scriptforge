package dev.scriptforge.tools;

import dev.scriptforge.domain.Json;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Deterministic checks are deliberately conservative; they do not claim semantic correctness. */
@Component
public class QualityChecks {
  public static final String VERSION = "rules-1";
  private static final Pattern QUANTITY =
      Pattern.compile("\\d+(?:\\.\\d+)?\\s*(?:公斤|千克|kg|KG|元|万元|折|毫升|升|厘米|毫米|%|％)");

  @SuppressWarnings("unchecked")
  public Map<String, Object> inspect(Map<String, Object> state) {
    var sections = (List<Map<String, Object>>) state.get("sections");
    var plan = (List<Map<String, Object>>) state.get("plan");
    var facts = (List<Map<String, Object>>) state.get("facts");
    Set<String> factIds =
        facts.stream().map(f -> f.get("id").toString()).collect(Collectors.toSet());
    String evidence =
        facts.stream()
            .map(f -> f.get("text").toString().replaceAll("\\s+", ""))
            .collect(Collectors.joining("\n"));
    List<Map<String, Object>> issues = new ArrayList<>();
    Map<String, String> seen = new HashMap<>();
    int length = 0;
    for (var s : sections) {
      String id = s.get("sectionId").toString(), text = s.get("text").toString();
      length += text.codePointCount(0, text.length());
      if (text.strip().length() < 20)
        issues.add(
            issue(
                "SHORT_" + id,
                id,
                "LENGTH",
                "BLOCKER",
                text,
                "Section is too short to inspect",
                "补充完整口播内容"));
      var refs = (List<String>) s.getOrDefault("factIds", List.of());
      if (refs.isEmpty() || !factIds.containsAll(refs))
        issues.add(
            issue(
                "REF_" + id,
                id,
                "FACTS",
                "BLOCKER",
                text.substring(0, Math.min(40, text.length())),
                "Missing or unknown fact references",
                "引用已提供的事实ID"));
      var quantity = QUANTITY.matcher(text);
      while (quantity.find())
        if (!evidence.contains(quantity.group().replaceAll("\\s+", "")))
          issues.add(
              issue(
                  "FACT_" + id + "_" + quantity.start(),
                  id,
                  "FACTS",
                  "BLOCKER",
                  quantity.group(),
                  "已提供事实中没有此数量或单位；须人工核对原始资料",
                  "删除无依据数字，或创建包含确切证据的新任务"));
      for (String sentence : text.split("[。！？!?\\n]")) {
        String normalized = sentence.replaceAll("[\\p{P}\\s]", "");
        if (normalized.length() < 18) continue;
        String prev = seen.putIfAbsent(normalized, id);
        if (prev != null)
          issues.add(
              issue(
                  "DUP_" + id + "_" + issues.size(),
                  id,
                  "REPETITION",
                  "BLOCKER",
                  sentence,
                  "与段落 " + prev + " 存在相同长句",
                  "保留信息，换用不同场景和表达"));
      }
    }
    Set<String> expected =
        plan.stream().map(p -> p.get("id").toString()).collect(Collectors.toSet());
    Set<String> actual =
        sections.stream().map(s -> s.get("sectionId").toString()).collect(Collectors.toSet());
    boolean complete = !expected.isEmpty() && expected.equals(actual);
    double estimatedSeconds = length / 3.8;
    int target = ((Number) state.get("targetSeconds")).intValue();
    // This estimate is a visible heuristic, not a promise about actual TTS or playback duration.
    boolean duration = estimatedSeconds >= target * 0.55 && estimatedSeconds <= target * 1.6;
    var checks =
        List.of(
            Json.object(
                "name",
                "PLAN_COVERAGE",
                "status",
                complete ? "PASS" : "FAIL",
                "detail",
                actual.size() + "/" + expected.size()),
            Json.object(
                "name",
                "FACT_REFERENCES_AND_QUANTITIES",
                "status",
                issues.stream().noneMatch(i -> "FACTS".equals(i.get("type"))) ? "PASS" : "FAIL",
                "detail",
                "引用存在性与数量原文匹配；不替代语义事实核验"),
            Json.object(
                "name",
                "EXACT_REPETITION",
                "status",
                issues.stream().noneMatch(i -> "REPETITION".equals(i.get("type")))
                    ? "PASS"
                    : "FAIL",
                "detail",
                "跨段及段内18字以上相同长句"),
            Json.object(
                "name",
                "ESTIMATED_DURATION",
                "status",
                duration ? "PASS" : "FAIL",
                "estimatedSeconds",
                Math.round(estimatedSeconds),
                "targetSeconds",
                target,
                "detail",
                "按3.8字符/秒粗估，需TTS实测确认"),
            Json.object(
                "name",
                "SECTION_LENGTH",
                "status",
                issues.stream().noneMatch(i -> "LENGTH".equals(i.get("type"))) ? "PASS" : "FAIL"));
    return Json.object(
        "checks",
        checks,
        "issues",
        issues,
        "passed",
        complete && duration && issues.isEmpty(),
        "estimatedSeconds",
        Math.round(estimatedSeconds));
  }

  public static Map<String, Object> issue(
      String id,
      String section,
      String type,
      String severity,
      String quote,
      String evidence,
      String repair) {
    return Json.object(
        "id",
        id,
        "sectionId",
        section,
        "type",
        type,
        "severity",
        severity,
        "quote",
        quote,
        "evidence",
        evidence,
        "repair",
        repair);
  }

  @SuppressWarnings("unchecked")
  public static Set<String> requiredEdges(List<Map<String, Object>> plan) {
    var edges = new LinkedHashSet<String>();
    for (int i = 0; i < plan.size(); i++) {
      String id = plan.get(i).get("id").toString();
      if (i > 0) edges.add(plan.get(i - 1).get("id") + "->" + id);
      for (String dep : (List<String>) plan.get(i).getOrDefault("dependsOn", List.of()))
        edges.add(dep + "->" + id);
    }
    return edges;
  }
}
