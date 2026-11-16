package dev.scriptforge.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/**
 * Deterministic protocol fixture, NOT an LLM. Exercises exactly the same host/tool/persistence
 * path.
 */
public final class DemoModelGateway implements ModelGateway {
  private final ObjectMapper mapper;

  public DemoModelGateway(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public ModelReply complete(ModelRequest request) {
    var state = request.state();
    int draft = number(state, "draftRevision"), planRevision = number(state, "planRevision");
    List<Map<String, Object>> sections = list(state.get("sections"));
    List<Map<String, Object>> plan = list(state.get("plan"));
    List<String> factIds =
        list(state.get("facts")).stream().map(f -> String.valueOf(f.get("id"))).toList();
    if ("REVIEWER".equals(request.role())) {
      Map<String, Object> coverage = map(state.get("coverage"));
      List<?> inspected = coverage.get("inspectedSections") instanceof List<?> l ? l : List.of();
      List<String> ids = sections.stream().map(s -> String.valueOf(s.get("sectionId"))).toList();
      if (!inspected.containsAll(ids))
        return reply(
            request,
            "inspect_sections",
            Map.of("sectionIds", ids, "checks", List.of("FACTS", "REPETITION", "CONTINUITY")));
      boolean unsupported =
          sections.stream().anyMatch(s -> String.valueOf(s.get("text")).contains("承重100公斤"));
      var issues =
          unsupported
              ? List.of(
                  Map.of(
                      "id",
                      "unsupported-load",
                      "sectionId",
                      "s2",
                      "type",
                      "UNSUPPORTED_CLAIM",
                      "severity",
                      "BLOCKER",
                      "quote",
                      "承重100公斤",
                      "evidence",
                      "提供的事实只说明折叠、提手与收纳用途，没有承重检测或数值来源。",
                      "repair",
                      "删除承重数字与保证，保留已知折叠事实，并承接下一段的提手用途。"))
              : List.of();
      return reply(
          request,
          "submit_review",
          Map.of(
              "draftRevision",
              draft,
              "verdict",
              unsupported ? "REVISE" : "PASS",
              "issues",
              issues,
              "summary",
              unsupported
                  ? "固定演示：审稿发现一处无依据承重声明，要求局部修订。"
                  : "固定演示：无依据声明已移除；三段及相邻关系已检查。本结果不是模型质量实测。"));
    }
    String subject =
        String.valueOf(state.getOrDefault("title", "")) + state.getOrDefault("brief", "");
    List<Map<String, Object>> suppliedFacts = list(state.get("facts"));
    boolean fixtureFacts =
        suppliedFacts.size() >= 3
            && String.valueOf(suppliedFacts.get(0).get("text")).contains("折叠")
            && String.valueOf(suppliedFacts.get(1).get("text")).contains("提手")
            && String.valueOf(suppliedFacts.get(2).get("text")).contains("衣物")
            && String.valueOf(suppliedFacts.get(2).get("text")).contains("杂物");
    if (!subject.contains("折叠收纳盒") || !fixtureFacts || number(state, "targetSeconds") != 90)
      return reply(
          request,
          "finish_run",
          Map.of(
              "outcome",
              "NEEDS_INPUT",
              "draftRevision",
              draft,
              "reportId",
              "",
              "reason",
              "DEMO 仅支持90秒预置折叠收纳盒样例及其三条事实；其他主题请配置真实模型。"));
    if (plan.isEmpty()) {
      int target = Math.max(3, number(state, "targetSeconds"));
      var planned =
          List.of(
              section(
                  "s1",
                  "从生活场景切入",
                  "明确整理问题",
                  "家中杂物分类",
                  List.of(factIds.get(2)),
                  target / 3,
                  "独立开场，面向刚进入直播间的观众",
                  "先分类再收纳的生活场景",
                  "提出闲置收纳盒占空间的问题",
                  List.of()),
              section(
                  "s2",
                  "不用时如何收起",
                  "解释折叠设计",
                  "使用与闲置两种状态",
                  List.of(factIds.get(0)),
                  target / 3,
                  "回答上一段不用时占空间的问题",
                  "折叠收起的已知设计",
                  "引出装入物品之后的拿取移动",
                  List.of("s1")),
              section(
                  "s3",
                  "整理后方便拿取",
                  "解释提手并收束",
                  "日常取放",
                  List.of(factIds.get(1)),
                  target - target / 3 * 2,
                  "从收起方式转向日常取放",
                  "两侧提手与按需整理建议",
                  "独立结束，不承诺未介绍的功能",
                  List.of("s2")));
      return reply(
          request, "save_plan", Map.of("expectedPlanRevision", planRevision, "sections", planned));
    }
    for (int i = 1; i <= 3; i++) {
      String id = "s" + i;
      if (sections.stream().noneMatch(s -> id.equals(s.get("sectionId"))))
        return patch(request, id, draft, planRevision, factIds, false, "固定演示：根据已保存计划生成这一段。");
    }
    Map<String, Object> report = map(state.get("report"));
    if (number(report, "draftRevision") == draft && "REVISE".equals(report.get("verdict")))
      return patch(request, "s2", draft, planRevision, factIds, true, "删除审稿定位的无依据承重声明，保持其他两段不变。");
    if (number(report, "draftRevision") == draft && "PASS".equals(report.get("verdict")))
      return reply(
          request,
          "finish_run",
          Map.of(
              "outcome",
              "SUBMIT",
              "draftRevision",
              draft,
              "reportId",
              String.valueOf(report.get("id")),
              "reason",
              "请求固定演示稿交付；由宿主验证版本和检查覆盖。"));
    return reply(request, "evaluate_candidate", Map.of("draftRevision", draft));
  }

  private ModelReply patch(
      ModelRequest request,
      String id,
      int draft,
      int plan,
      List<String> facts,
      boolean repaired,
      String reason) {
    String text =
        switch (id) {
          case "s1" ->
              "家里有时并不是东西太多，而是衣物和日常小物混在了一起，想用的时候还要翻找。"
                  + "这款折叠收纳盒，可以作为分类整理的一种选择。你可以先想想自己最常找的是哪类东西，再决定这一盒放什么。"
                  + "整理的重点是让物品有明确的位置，不用一次把所有角落都收完。不过，盒子装东西的时候有用途，不用的时候，又该放在哪里呢？";
          case "s2" ->
              "刚才说到，不用的收纳盒也需要一个位置。这款盒体采用可折叠设计，闲置时可以折叠收起，等有需要时再拿出来。"
                  + "它适合怎样用，还是要结合你准备整理的物品和家里的空间来考虑。"
                  + (repaired ? "具体承重没有提供数据，我们就不作数字承诺。" : "而且它承重100公斤，可以放心装满。")
                  + "从闲置时如何收起，到装入物品后怎样拿取，是两个不同的问题。下面我们再看取放这一步。";
          default ->
              "装好之后，拿取是否顺手也是日常会遇到的问题。这款盒子两侧设有提手，方便拿取和移动。"
                  + "你可以按照自己经常取用的位置安排收纳，移动时也应结合里面物品的实际重量，不把提手当作承重保证。"
                  + "如果刚进直播间，可以先从身边一类需要整理的东西开始考虑：是衣物，还是日常杂物？先明确用途，再判断这类收纳方式是否适合自己，按真实需要做选择。";
        };
    int factIndex = id.equals("s1") ? 2 : id.equals("s2") ? 0 : 1;
    return reply(
        request,
        "patch_script",
        Map.of(
            "expectedDraftRevision",
            draft,
            "planRevision",
            plan,
            "edits",
            List.of(
                Map.of("sectionId", id, "text", text, "factIds", List.of(facts.get(factIndex)))),
            "reason",
            reason));
  }

  private static Map<String, Object> section(
      String id,
      String title,
      String purpose,
      String angle,
      List<String> factIds,
      int seconds,
      String entry,
      String information,
      String exit,
      List<String> depends) {
    var result = new LinkedHashMap<String, Object>();
    result.put("id", id);
    result.put("title", title);
    result.put("purpose", purpose);
    result.put("angle", angle);
    result.put("factIds", factIds);
    result.put("targetSeconds", seconds);
    result.put("entryContext", entry);
    result.put("newInformation", information);
    result.put("exitExpectation", exit);
    result.put("dependsOn", depends);
    return result;
  }

  private ModelReply reply(ModelRequest request, String name, Map<String, Object> args) {
    try {
      String json = mapper.writeValueAsString(args);
      long input =
          Math.max(
              1,
              mapper
                      .writeValueAsString(request.messages())
                      .codePointCount(0, mapper.writeValueAsString(request.messages()).length())
                  / 2);
      return new ModelReply(
          "固定演示动作，不是模型推理",
          List.of(new ToolCall("demo_" + UUID.randomUUID(), name, json)),
          input,
          (long) Math.max(1, json.length() / 2),
          "DEMO");
    } catch (Exception e) {
      throw new IllegalStateException("Could not serialize demo fixture", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> list(Object value) {
    return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
  }

  private static int number(Map<String, Object> value, String key) {
    Object n = value.get(key);
    return n instanceof Number num ? num.intValue() : 0;
  }
}
