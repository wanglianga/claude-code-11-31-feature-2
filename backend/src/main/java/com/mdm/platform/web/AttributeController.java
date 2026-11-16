package com.mdm.platform.web;

import com.mdm.platform.service.AttributeAssertionService;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 属性断言（编号 / 配对 / 剩余寿命）台账与按分析日期求值：
 *  - 双模式：SOURCE_PRIORITY（来源优先级）/ TEMPORAL（断言时态）
 *  - 输出 CONFIRMED / CONFLICT / EXPIRED / UNKNOWN
 *  - 来源路径与证据缺口随结果返回
 */
@RestController
@RequestMapping("/attributes")
public class AttributeController {

    private final AttributeAssertionService attributes;

    public AttributeController(AttributeAssertionService attributes) {
        this.attributes = attributes;
    }

    /** 可选的实物序列号清单（含断言行数） */
    @GetMapping("/subjects")
    public ApiResult<List<Map<String, Object>>> subjects() {
        return ApiResult.ok(attributes.listSubjects());
    }

    /** 断言台账（默认只返回 ACTIVE；includeRevoked=true 含已撤销） */
    @GetMapping("/assertions")
    public ApiResult<List<Map<String, Object>>> assertions(
            @RequestParam(required = false) Long subjectId,
            @RequestParam(required = false) String attributeKey,
            @RequestParam(required = false, defaultValue = "false") boolean includeRevoked) {
        return ApiResult.ok(attributes.listAssertions(subjectId, attributeKey, includeRevoked));
    }

    /** 追加来源断言（车间/质检/外协），原文与归一化值分别保留 */
    @PostMapping("/assertions")
    public ApiResult<Map<String, Object>> appendAssertion(@RequestBody Map<String, Object> body) {
        return ApiResult.ok(attributes.appendAssertion(body));
    }

    /** 撤销断言；配对断言支持 A/B 侧单向撤销（对侧证据保留但整对不可确认） */
    @PostMapping("/assertions/{id}/revoke")
    public ApiResult<Map<String, Object>> revokeAssertion(
            @PathVariable long id, @RequestBody(required = false) Map<String, String> body) {
        Map<String, String> payload = body == null ? Map.of() : body;
        return ApiResult.ok(attributes.revokeAssertion(id,
                payload.get("side"), payload.get("reason")));
    }

    /** 属性依赖边（证据缺口传播路径） */
    @GetMapping("/dependencies")
    public ApiResult<List<Map<String, Object>>> dependencies() {
        return ApiResult.ok(attributes.dependencies());
    }

    /**
     * 按分析日期求值。body: subjectId, mode(SOURCE_PRIORITY|TEMPORAL),
     * analysisDate, attributeKey(可空=全部属性)
     */
    @PostMapping("/evaluate")
    public ApiResult<Map<String, Object>> evaluate(@RequestBody Map<String, Object> body) {
        long subjectId = Long.parseLong(String.valueOf(body.get("subjectId")));
        OffsetDateTime analysisDate = body.get("analysisDate") == null
                || String.valueOf(body.get("analysisDate")).isBlank()
                ? null : OffsetDateTime.parse(String.valueOf(body.get("analysisDate")));
        String attributeKey = body.get("attributeKey") == null ? null : String.valueOf(body.get("attributeKey"));
        return ApiResult.ok(attributes.evaluate(new AttributeAssertionService.EvaluationRequest(
                subjectId, attributeKey, analysisDate,
                body.get("mode") == null ? null : String.valueOf(body.get("mode")))));
    }

    /** 历史求值台账（重演依据） */
    @GetMapping("/evaluations")
    public ApiResult<List<Map<String, Object>>> evaluations(
            @RequestParam(required = false) Long subjectId,
            @RequestParam(required = false) String attributeKey,
            @RequestParam(required = false) String mode) {
        return ApiResult.ok(attributes.listEvaluations(subjectId, attributeKey, mode));
    }

    /** 来源优先级模式的选择快照（解释"历史为什么选了它"） */
    @GetMapping("/mode-snapshots")
    public ApiResult<List<Map<String, Object>>> modeSnapshots(
            @RequestParam(required = false) Long subjectId) {
        return ApiResult.ok(attributes.listModeSnapshots(subjectId));
    }
}
