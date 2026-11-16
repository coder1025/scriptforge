package dev.scriptforge.api;

import static dev.scriptforge.domain.ForgeException.require;
import static dev.scriptforge.store.ForgeStore.*;

import dev.scriptforge.config.ForgeProperties;
import dev.scriptforge.domain.Json;
import dev.scriptforge.runtime.TaskService;
import dev.scriptforge.store.ForgeStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ApiController {
  private final ForgeStore db;
  private final TaskService service;
  private final ForgeProperties config;

  public ApiController(ForgeStore db, TaskService service, ForgeProperties config) {
    this.db = db;
    this.service = service;
    this.config = config;
  }

  @GetMapping("/config")
  public Object config() {
    return Json.object(
        "name",
        "ScriptForge",
        "version",
        "0.1.0",
        "mode",
        config.mode(),
        "readOnly",
        config.readOnly(),
        "modelConfigured",
        config.modelConfigured(),
        "tokenProtected",
        !config.adminToken().isBlank(),
        "limits",
        Json.object("maxTargetSeconds", 1800, "maxSections", 24, "maxReviewRounds", 4),
        "demoNotice",
        "DEMO uses a deterministic fictional fixture, not live AI generation");
  }

  @GetMapping("/tasks")
  public Object tasks() {
    return db.rows("SELECT * FROM tasks ORDER BY created_at DESC LIMIT 100").stream()
        .map(
            t ->
                Json.object(
                    "id",
                    str(t, "id"),
                    "title",
                    str(t, "title"),
                    "status",
                    str(t, "status"),
                    "mode",
                    str(t, "mode"),
                    "createdAt",
                    str(t, "created_at"),
                    "updatedAt",
                    str(t, "updated_at"),
                    "draftRevision",
                    integer(t, "draft_revision"),
                    "reviewRound",
                    integer(t, "review_round"),
                    "modelCalls",
                    integer(t, "model_calls"),
                    "usedTokens",
                    number(t, "used_tokens"),
                    "targetSeconds",
                    db.json().tree(str(t, "input_json")).path("targetSeconds").asInt(),
                    "error",
                    t.get("error")))
        .toList();
  }

  @PostMapping("/tasks")
  public Object create(@Valid @RequestBody CreateTask request) {
    return service.create(request);
  }

  @GetMapping("/tasks/{id}")
  public Object detail(@PathVariable String id) {
    return db.detail(id);
  }

  public record Command(@NotBlank @Size(max = 100) String requestId) {}

  @PostMapping("/tasks/{id}/cancel")
  public Object cancel(@PathVariable String id, @Valid @RequestBody Command command) {
    return service.command(id, command.requestId(), "CANCEL");
  }

  @PostMapping("/tasks/{id}/resume")
  public Object resume(@PathVariable String id, @Valid @RequestBody Command command) {
    return service.command(id, command.requestId(), "RESUME");
  }

  @GetMapping("/templates")
  public Object templates() {
    return db.rows("SELECT * FROM templates ORDER BY created_at DESC").stream()
        .map(
            t ->
                Json.object(
                    "id",
                    str(t, "id"),
                    "name",
                    str(t, "name"),
                    "version",
                    integer(t, "version"),
                    "instructions",
                    str(t, "instructions"),
                    "createdAt",
                    str(t, "created_at")))
        .toList();
  }

  public record Template(
      @NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 6000) String instructions) {}

  @PostMapping("/templates")
  public Object template(@Valid @RequestBody Template input) {
    require(!config.readOnly(), "READ_ONLY", "This server is read-only");
    return db.tx(
        () -> {
          require(
              db.jdbc().queryForObject("SELECT COUNT(*) FROM templates", Long.class) < 500,
              "CAPACITY",
              "Template limit reached");
          int version =
              Objects.requireNonNull(
                  db.jdbc()
                      .queryForObject(
                          "SELECT COALESCE(MAX(version),0)+1 FROM templates WHERE name=?",
                          Integer.class,
                          input.name()));
          String id = id();
          db.jdbc()
              .update(
                  "INSERT INTO templates(id,name,version,instructions,created_at) VALUES(?,?,?,?,?)",
                  id,
                  input.name(),
                  version,
                  input.instructions(),
                  now());
          return Json.object(
              "id",
              id,
              "name",
              input.name(),
              "version",
              version,
              "instructions",
              input.instructions());
        });
  }

  @SuppressWarnings("unchecked")
  @GetMapping("/tasks/{id}/export")
  public ResponseEntity<String> export(@PathVariable String id) {
    var task = db.detail(id);
    StringBuilder md = new StringBuilder("# ").append(task.get("title")).append("\n\n");
    md.append("> 状态：")
        .append(task.get("status"))
        .append(" · 稿件 v")
        .append(task.get("draftRevision"))
        .append(" · 模式：")
        .append(task.get("mode"))
        .append("\n\n");
    if (!Set.of("READY", "DEMO_READY").contains(task.get("status")))
      md.append("> 未完成交付验收；以下为已保存的部分稿件。\n\n");
    if ("DEMO".equals(task.get("mode"))) md.append("> 固定虚构演示样例，不代表真实模型生成或审稿效果。\n\n");
    var titles = new HashMap<String, String>();
    for (var p : (List<Map<String, Object>>) task.get("plan"))
      titles.put(p.get("id").toString(), p.get("title").toString());
    for (var section : (List<Map<String, Object>>) task.get("sections"))
      md.append("## ")
          .append(
              titles.getOrDefault(
                  section.get("sectionId").toString(), section.get("sectionId").toString()))
          .append("\n\n")
          .append(section.get("text"))
          .append("\n\n");
    md.append("---\n\n时长为文字估算，实际播读时长须使用目标声音测量。\n");
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"scriptforge-" + id.replaceAll("[^a-zA-Z0-9-]", "") + ".md\"")
        .body(md.toString());
  }
}
