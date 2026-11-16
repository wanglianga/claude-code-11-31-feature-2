package com.mdm.platform.service;

import com.mdm.platform.repository.JdbcSupport;
import com.mdm.platform.security.CurrentUser;
import com.mdm.platform.security.DigestUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 属性断言求值器（编号 / 配对 / 剩余寿命 / 安装日期 / 规范号）。
 *
 * 车间、质检、外协资料会对同一实物给出冲突断言。本服务不做"最新写入覆盖"，而是：
 *  - 保留每条断言的原始编号、制造商前缀、检测方法、报告日期、有效期与配对两侧证据；
 *  - 按分析日期求值，输出 CONFIRMED / CONFLICT / EXPIRED / UNKNOWN；
 *  - 支持两种模式：
 *      SOURCE_PRIORITY 每个属性选一项当前断言、其余列为异常，并额外落一份"选择快照"解释历史选择；
 *      TEMPORAL        查询时按报告日期与方法适用性求值，历史可完整重演（索引更复杂）。
 *  - 硬性规则（两种模式一致）：
 *      * 不同检测方法且不相交的寿命区间绝不平均；
 *      * 一个规范号指向两个型号且无改号凭证时保持主体冲突；
 *      * 撤销一侧配对证据后整对不可确认；
 *      * 超期报告不能继续支撑确认结论；
 *      * 证据缺口沿属性依赖边传播给依赖属性。
 */
@Service
public class AttributeAssertionService {

    private static final List<String> BASE_ORDER =
            List.of("SERIAL_NUMBER", "INSTALL_DATE", "CATALOG_NUMBER", "REMAINING_LIFE", "PAIRING");
    private static final Set<String> ATTRIBUTE_KEYS = new LinkedHashSet<>(BASE_ORDER);
    private static final Set<String> SOURCE_KINDS = Set.of("WORKSHOP", "QC", "OUTSOURCE");
    private static final Pattern TRAILING_DIGITS = Pattern.compile("([0-9]+)$");

    private final JdbcSupport db;
    private final QualityEventService qualityEvents;

    public AttributeAssertionService(JdbcSupport db, QualityEventService qualityEvents) {
        this.db = db;
        this.qualityEvents = qualityEvents;
    }

    public record EvaluationRequest(long subjectId, String attributeKey,
                                    OffsetDateTime analysisDate, String mode) {}

    // ------------------------------------------------------------------
    // 断言台账
    // ------------------------------------------------------------------
    public List<Map<String, Object>> listSubjects() {
        return db.query(
                "SELECT e.id, e.entity_code, e.display_name, e.status, " +
                "(SELECT count(*) FROM attribute_assertion a WHERE a.subject_id=e.id) AS assertion_count, " +
                "(SELECT count(*) FROM attribute_assertion a WHERE a.subject_id=e.id AND a.status='ACTIVE') " +
                "  AS active_count " +
                "FROM master_entity e WHERE e.entity_type='PHYSICAL_PART' ORDER BY e.id");
    }

    public List<Map<String, Object>> listAssertions(Long subjectId, String attributeKey, boolean includeRevoked) {
        StringBuilder sql = new StringBuilder(
                "SELECT a.*, s.entity_code AS subject_code, m.entity_code AS asserted_model_code, " +
                "p.entity_code AS pair_peer_code FROM attribute_assertion a " +
                "JOIN master_entity s ON s.id=a.subject_id " +
                "LEFT JOIN master_entity m ON m.id=a.asserted_model_id " +
                "LEFT JOIN master_entity p ON p.id=a.pair_peer_subject_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (subjectId != null) {
            sql.append(" AND a.subject_id=?");
            args.add(subjectId);
        }
        if (attributeKey != null && !attributeKey.isBlank()) {
            sql.append(" AND a.attribute_key=?");
            args.add(attributeKey.toUpperCase(Locale.ROOT));
        }
        if (!includeRevoked) {
            sql.append(" AND a.status='ACTIVE'");
        }
        sql.append(" ORDER BY a.subject_id, a.attribute_key, a.report_date DESC, a.id DESC");
        List<Map<String, Object>> rows = db.query(sql.toString(), args.toArray());
        for (Map<String, Object> row : rows) {
            row.put("pairingEvidence", pairingEvidence(id(row)));
        }
        return rows;
    }

    public List<Map<String, Object>> dependencies() {
        return db.query("SELECT * FROM attribute_dependency ORDER BY attribute_key, depends_on_key");
    }

    /**
     * 登记一条来源断言（追加式）。原始编号与归一化值分开保存，原文永不覆盖。
     */
    @Transactional
    public Map<String, Object> appendAssertion(Map<String, Object> body) {
        long subjectId = longVal(body.get("subjectId"), "subjectId");
        String attributeKey = required(body, "attributeKey").toUpperCase(Locale.ROOT);
        if (!ATTRIBUTE_KEYS.contains(attributeKey)) {
            throw new IllegalArgumentException("不支持的属性: " + attributeKey);
        }
        String sourceKind = required(body, "sourceKind").toUpperCase(Locale.ROOT);
        if (!SOURCE_KINDS.contains(sourceKind)) {
            throw new IllegalArgumentException("来源类型只支持 WORKSHOP / QC / OUTSOURCE");
        }
        String rawValue = required(body, "rawValue");
        String prefix = optional(body.get("manufacturerPrefix"));
        String normalized = normalizeNumber(rawValue, prefix);
        OffsetDateTime reportDate = OffsetDateTime.parse(required(body, "reportDate"));
        OffsetDateTime validUntil = optionalDate(body.get("validUntil"));

        Long assertedModelId = optionalLong(body.get("assertedModelId"));
        String renumberCert = optional(body.get("renumberCertNo"));
        BigDecimal lifeLow = optionalDecimal(body.get("lifeLowerHours"));
        BigDecimal lifeHigh = optionalDecimal(body.get("lifeUpperHours"));
        Long pairPeerId = optionalLong(body.get("pairPeerSubjectId"));
        String inspectionMethod = optional(body.get("inspectionMethod"));
        String sourceRef = optional(body.get("sourceRef"));
        if (sourceRef == null) {
            sourceRef = "manual-assertion";
        }

        if ("REMAINING_LIFE".equals(attributeKey) && (lifeLow == null || lifeHigh == null)) {
            throw new IllegalArgumentException("剩余寿命断言必须给出 lifeLowerHours / lifeUpperHours 区间");
        }
        if ("PAIRING".equals(attributeKey) && pairPeerId == null) {
            throw new IllegalArgumentException("配对断言必须给出 pairPeerSubjectId");
        }
        if ("CATALOG_NUMBER".equals(attributeKey) && assertedModelId == null) {
            throw new IllegalArgumentException("规范号断言必须给出 assertedModelId");
        }

        long count = db.count("SELECT count(*) FROM attribute_assertion");
        String assertionNo = String.format("AA-%s-%05d", attributeKey.substring(0, 3), count + 1);
        String digest = "sha256:" + DigestUtil.sha256(assertionNo + "|" + rawValue + "|" + sourceRef);
        long newId = db.insertReturningId(
                "INSERT INTO attribute_assertion(assertion_no,subject_id,attribute_key,source_kind," +
                "source_priority,inspection_method,raw_value,normalized_value,manufacturer_prefix," +
                "asserted_model_id,renumber_cert_no,life_lower_hours,life_upper_hours,pair_peer_subject_id," +
                "report_date,valid_until,source_contract_id,source_ref,payload_digest) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                assertionNo, subjectId, attributeKey, sourceKind,
                body.getOrDefault("sourcePriority", 50), inspectionMethod, rawValue, normalized, prefix,
                assertedModelId, renumberCert, lifeLow, lifeHigh, pairPeerId,
                reportDate, validUntil, optionalLong(body.get("sourceContractId")), sourceRef, digest);

        if ("PAIRING".equals(attributeKey)) {
            appendPairingEvidence(newId, subjectId, pairPeerId, body, sourceRef, digest);
        }
        return listAssertions(subjectId, attributeKey, true).stream()
                .filter(r -> id(r) == newId).findFirst()
                .orElse(db.queryOne("SELECT * FROM attribute_assertion WHERE id=?", newId));
    }

    private void appendPairingEvidence(long assertionId, long subjectId, long peerId,
                                       Map<String, Object> body, String sourceRef, String digest) {
        List<?> supplied = body.get("pairingEvidence") instanceof List<?> l ? l : List.of();
        boolean sideAWritten = false;
        boolean sideBWritten = false;
        for (Object o : supplied) {
            if (!(o instanceof Map<?, ?> raw)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> ev = (Map<String, Object>) raw;
            String side = String.valueOf(ev.get("side")).toUpperCase(Locale.ROOT);
            if (!"A".equals(side) && !"B".equals(side)) {
                throw new IllegalArgumentException("配对证据 side 只支持 A / B");
            }
            long evSubject = ev.get("subjectId") == null
                    ? ("A".equals(side) ? subjectId : peerId) : longVal(ev.get("subjectId"), "subjectId");
            writePairingEvidence(assertionId, side, evSubject,
                    String.valueOf(ev.getOrDefault("evidenceRef", sourceRef)),
                    "sha256:" + DigestUtil.sha256(assertionId + "|" + side + "|" + ev.get("evidenceRef")));
            sideAWritten |= "A".equals(side);
            sideBWritten |= "B".equals(side);
        }
        // 未显式给出的侧按来源凭证自动补齐，保证"两侧证据"都被保留
        if (!sideAWritten) {
            writePairingEvidence(assertionId, "A", subjectId, sourceRef,
                    "sha256:" + DigestUtil.sha256(assertionId + "|A|" + sourceRef));
        }
        if (!sideBWritten) {
            writePairingEvidence(assertionId, "B", peerId,
                    String.valueOf(body.getOrDefault("pairPeerEvidenceRef", sourceRef + "-peer")),
                    "sha256:" + DigestUtil.sha256(assertionId + "|B|" + sourceRef));
        }
        db.update("UPDATE attribute_assertion SET payload_digest=? WHERE id=?", digest, assertionId);
    }

    private void writePairingEvidence(long assertionId, String side, long subjectId,
                                      String evidenceRef, String digest) {
        db.update("INSERT INTO pairing_evidence(assertion_id,side,subject_id,evidence_ref,payload_digest," +
                        "recorded_by) VALUES (?,?,?,?,?,?) ON CONFLICT (assertion_id, side) DO NOTHING",
                assertionId, side, subjectId, evidenceRef, digest, CurrentUser.username());
    }

    private List<Map<String, Object>> pairingEvidence(long assertionId) {
        return db.query("SELECT pe.id, pe.side, pe.subject_id, m.entity_code AS subject_code, " +
                "pe.evidence_ref, pe.payload_digest, pe.recorded_by, pe.revoked_at, pe.revoke_reason " +
                "FROM pairing_evidence pe JOIN master_entity m ON m.id=pe.subject_id " +
                "WHERE pe.assertion_id=? ORDER BY pe.side", assertionId);
    }

    /**
     * 撤销断言。配对断言按 A/B 侧单向撤销：保留对侧证据，但整对不可确认。
     */
    @Transactional
    public Map<String, Object> revokeAssertion(long assertionId, String side, String reason) {
        Map<String, Object> assertion = db.queryOne("SELECT * FROM attribute_assertion WHERE id=?", assertionId);
        if (assertion == null) {
            throw new IllegalArgumentException("属性断言不存在: " + assertionId);
        }
        String attributeKey = String.valueOf(assertion.get("attribute_key"));
        String normalizedSide = side == null ? "" : side.toUpperCase(Locale.ROOT);
        String note = reason == null || reason.isBlank() ? "来源撤回断言" : reason;

        if ("PAIRING".equals(attributeKey) && ("A".equals(normalizedSide) || "B".equals(normalizedSide))) {
            if ("REVOKED".equals(assertion.get("status"))) {
                throw new IllegalStateException("配对断言已整体撤销");
            }
            long peerSubject = assertion.get("pair_peer_subject_id") == null ? 0L
                    : longVal(assertion.get("pair_peer_subject_id"), "pairPeerSubjectId");
            int updated = db.update("UPDATE pairing_evidence SET revoked_at=now(), revoke_reason=? " +
                    "WHERE assertion_id=? AND side=? AND revoked_at IS NULL", note, assertionId, normalizedSide);
            if (updated == 0) {
                throw new IllegalStateException("配对 " + normalizedSide + " 侧证据不存在或已撤销");
            }
            long remaining = db.count("SELECT count(*) FROM pairing_evidence WHERE assertion_id=? " +
                    "AND revoked_at IS NULL", assertionId);
            if (remaining == 0) {
                db.update("UPDATE attribute_assertion SET status='REVOKED', revoked_at=now(), " +
                        "revoked_side='ALL', revoke_reason=? WHERE id=?", note, assertionId);
            } else {
                db.update("UPDATE attribute_assertion SET revoked_side=?, revoke_reason=? WHERE id=?",
                        normalizedSide, note, assertionId);
            }
            qualityEvents.record("PAIRING_REVOKE", "HIGH", "PHYSICAL_PART",
                    longVal(assertion.get("subject_id"), "subjectId"), null, null,
                    "配对断言 " + assertion.get("assertion_no") + " 的 " + normalizedSide
                            + " 侧证据被撤销：" + note + "（整对不可确认，对侧证据保留）",
                    Map.of("assertionNo", assertion.get("assertion_no"), "side", normalizedSide,
                            "peerSubjectId", peerSubject, "remainingSides", remaining));
        } else {
            if ("REVOKED".equals(assertion.get("status"))) {
                throw new IllegalStateException("断言已撤销");
            }
            db.update("UPDATE attribute_assertion SET status='REVOKED', revoked_at=now(), " +
                    "revoked_side='ALL', revoke_reason=? WHERE id=?", note, assertionId);
        }
        return db.queryOne("SELECT * FROM attribute_assertion WHERE id=?", assertionId);
    }

    // ------------------------------------------------------------------
    // 求值
    // ------------------------------------------------------------------
    @Transactional
    public Map<String, Object> evaluate(EvaluationRequest req) {
        Map<String, Object> subject = db.queryOne(
                "SELECT * FROM master_entity WHERE id=? AND entity_type='PHYSICAL_PART'", req.subjectId());
        if (subject == null) {
            throw new IllegalArgumentException("实物序列号不存在: " + req.subjectId());
        }
        String mode = normalizeMode(req.mode());
        OffsetDateTime date = req.analysisDate() == null ? OffsetDateTime.now() : req.analysisDate();
        List<String> requested = req.attributeKey() == null || req.attributeKey().isBlank()
                ? orderedKeys() : List.of(req.attributeKey().toUpperCase(Locale.ROOT));
        for (String key : requested) {
            if (!ATTRIBUTE_KEYS.contains(key)) {
                throw new IllegalArgumentException("不支持的属性: " + key);
            }
        }
        Set<String> required = new LinkedHashSet<>(requested);
        Set<String> frontier = new LinkedHashSet<>(requested);
        while (!frontier.isEmpty()) {
            Set<String> next = new LinkedHashSet<>();
            List<Object> args = new ArrayList<>(frontier);
            String placeholders = String.join(",", java.util.Collections.nCopies(frontier.size(), "?"));
            for (Map<String, Object> dep : db.query(
                    "SELECT depends_on_key FROM attribute_dependency WHERE attribute_key IN ("
                            + placeholders + ")", args.toArray())) {
                String depKey = String.valueOf(dep.get("depends_on_key"));
                if (required.add(depKey)) {
                    next.add(depKey);
                }
            }
            frontier = next;
        }

        Map<String, Map<String, Object>> allComputed = new LinkedHashMap<>();
        for (String key : orderedKeys()) {
            if (required.contains(key)) {
                allComputed.put(key, evaluateAttribute(subject, key, date, mode, allComputed));
            }
        }
        Map<String, Map<String, Object>> computed = new LinkedHashMap<>();
        for (String key : requested) {
            computed.put(key, allComputed.get(key));
        }

        Long modeSnapshotId = null;
        String modeSnapshotNo = null;
        if ("SOURCE_PRIORITY".equals(mode)) {
            Map<String, Object> snapshot = persistModeSnapshot(req.subjectId(), date, allComputed);
            modeSnapshotId = longVal(snapshot.get("id"), "id");
            modeSnapshotNo = String.valueOf(snapshot.get("snapshot_no"));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("CONFIRMED", 0);
        summary.put("CONFLICT", 0);
        summary.put("EXPIRED", 0);
        summary.put("UNKNOWN", 0);
        for (Map.Entry<String, Map<String, Object>> entry : computed.entrySet()) {
            Map<String, Object> result = persistEvaluation(subject, entry.getKey(), date, mode,
                    entry.getValue(), modeSnapshotId);
            results.add(result);
            summary.merge(String.valueOf(result.get("status")), 1, Integer::sum);
            recordQualityEvents(subject, entry.getKey(), date, mode, entry.getValue());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subjectId", req.subjectId());
        payload.put("subjectCode", subject.get("entity_code"));
        payload.put("subjectName", subject.get("display_name"));
        payload.put("mode", mode);
        payload.put("analysisDate", date);
        payload.put("modeSnapshotId", modeSnapshotId);
        payload.put("modeSnapshotNo", modeSnapshotNo);
        payload.put("results", results);
        payload.put("summary", summary);
        payload.put("modeNote", "SOURCE_PRIORITY".equals(mode)
                ? "每个属性选一项当前断言（按来源优先级），其余断言列为异常；已额外落选择快照以解释历史选择。"
                : "按分析日期与检测方法适用性在查询时求值，历史可完整重演；索引复杂度更高。");
        return payload;
    }

    private Map<String, Object> evaluateAttribute(Map<String, Object> subject, String key,
                                                   OffsetDateTime date, String mode,
                                                   Map<String, Map<String, Object>> prior) {
        long subjectId = longVal(subject.get("id"), "id");
        List<Map<String, Object>> rows = db.query(
                "SELECT * FROM attribute_assertion WHERE subject_id=? AND attribute_key=? " +
                "ORDER BY report_date DESC, source_priority DESC, id DESC", subjectId, key);
        List<Map<String, Object>> known = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            OffsetDateTime reportDate = db.toOffsetDateTime(row.get("report_date"));
            if (reportDate != null && reportDate.isAfter(date)) {
                continue; // 分析日期时报告尚未产生
            }
            row.put("validity", validityOf(row, date));
            known.add(row);
        }
        List<Map<String, Object>> active = filterValidity(known, "VALID");
        List<Map<String, Object>> expired = filterValidity(known, "EXPIRED");
        List<Map<String, Object>> revoked = filterValidity(known, "REVOKED");

        Outcome outcome = switch (key) {
            case "SERIAL_NUMBER" -> evalNumber(key, active, expired, revoked, mode);
            case "CATALOG_NUMBER" -> evalNumber(key, active, expired, revoked, mode);
            case "INSTALL_DATE" -> evalInstallDate(active, expired, revoked, mode);
            case "REMAINING_LIFE" -> evalRemainingLife(active, expired, revoked, mode);
            case "PAIRING" -> evalPairing(active, expired, revoked, mode);
            default -> new Outcome("UNKNOWN", null, "不支持的属性", Map.of());
        };

        List<Map<String, Object>> exceptions = buildExceptions(known, outcome.chosen, mode);
        List<Map<String, Object>> gaps = new ArrayList<>();
        List<Map<String, Object>> dependencyTrace = new ArrayList<>();
        for (Map<String, Object> dep : db.query(
                "SELECT depends_on_key, rationale FROM attribute_dependency WHERE attribute_key=?", key)) {
            String depKey = String.valueOf(dep.get("depends_on_key"));
            Map<String, Object> upstream = prior.get(depKey);
            if (upstream == null) {
                continue;
            }
            String depStatus = String.valueOf(upstream.get("status"));
            dependencyTrace.add(Map.of("attribute", depKey, "status", depStatus,
                    "rationale", dep.get("rationale")));
            if (!"CONFIRMED".equals(depStatus)) {
                gaps.add(Map.of("missingAttribute", depKey, "upstreamStatus", depStatus,
                        "rationale", dep.get("rationale")));
                if ("CONFIRMED".equals(outcome.status)) {
                    outcome = new Outcome("UNKNOWN", outcome.chosen,
                            "依赖属性 " + depKey + " 未确认（" + depStatus + "），证据缺口传播至本属性",
                            outcome.detail);
                }
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> upstreamGaps =
                    upstream.get("gaps") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
            for (Map<String, Object> g : upstreamGaps) {
                Map<String, Object> carried = new LinkedHashMap<>(g);
                carried.put("via", depKey);
                gaps.add(carried);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("attributeKey", key);
        result.put("status", outcome.status);
        result.put("reason", outcome.reason);
        result.put("chosen", outcome.chosen);
        result.put("exceptions", exceptions);
        result.put("gaps", gaps);
        result.put("dependencyTrace", dependencyTrace);
        result.put("detail", outcome.detail);
        result.put("sourcePath", buildSourcePath(subjectId, key, known));
        return result;
    }

    private Outcome evalNumber(String key, List<Map<String, Object>> active,
                               List<Map<String, Object>> expired, List<Map<String, Object>> revoked,
                               String mode) {
        if (active.isEmpty()) {
            return emptyOutcome(expired, revoked);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        if ("CATALOG_NUMBER".equals(key)) {
            Set<Long> models = new LinkedHashSet<>();
            active.forEach(a -> {
                if (a.get("asserted_model_id") != null) {
                    models.add(longVal(a.get("asserted_model_id"), "asserted_model_id"));
                }
            });
            detail.put("assertedModelCount", models.size());
            if (models.size() > 1) {
                boolean anyCert = active.stream()
                        .anyMatch(a -> optional(a.get("renumber_cert_no")) != null);
                if (!anyCert) {
                    return new Outcome("CONFLICT", null,
                            "同一规范号指向 " + models.size() + " 个型号且无改号凭证，保持主体冲突（不按来源优先级消解）",
                            Map.of("assertedModelIds", new ArrayList<>(models), "renumberCert", "MISSING"));
                }
                List<String> certs = active.stream()
                        .map(a -> optional(a.get("renumber_cert_no")))
                        .filter(c -> c != null).distinct().toList();
                detail.put("renumberCert", certs);
                Map<String, Object> chosen = select(active, mode);
                return new Outcome("CONFIRMED", chosen,
                        "规范号跨型号但存在改号凭证 " + certs + "，按改号链确认", detail);
            }
        }
        Map<String, List<Map<String, Object>>> byNormalized = new LinkedHashMap<>();
        for (Map<String, Object> row : active) {
            String norm = normalizeNumber(String.valueOf(row.get("raw_value")),
                    optional(row.get("manufacturer_prefix")));
            byNormalized.computeIfAbsent(norm.isEmpty() ? "<EMPTY>" : norm, k -> new ArrayList<>()).add(row);
        }
        detail.put("normalizedVariants", byNormalized.keySet());
        if (byNormalized.size() > 1) {
            return new Outcome("CONFLICT", null,
                    "编号归一后仍存在 " + byNormalized.size() + " 个不同值，无法确认", detail);
        }
        Map<String, Object> chosen = select(active, mode);
        String value = String.valueOf(chosen.get("raw_value"));
        return new Outcome("CONFIRMED", chosen,
                "编号变体归一一致（原文分别保留），确认值 " + value, detail);
    }

    private Outcome evalInstallDate(List<Map<String, Object>> active, List<Map<String, Object>> expired,
                                    List<Map<String, Object>> revoked, String mode) {
        if (active.isEmpty()) {
            return emptyOutcome(expired, revoked);
        }
        Set<String> values = new LinkedHashSet<>();
        active.forEach(a -> values.add(String.valueOf(a.get("raw_value"))));
        if (values.size() > 1) {
            return new Outcome("CONFLICT", null, "安装日期存在 " + values.size() + " 个不同值", Map.of());
        }
        return new Outcome("CONFIRMED", select(active, mode),
                "安装日期一致，确认 " + values.iterator().next(), Map.of());
    }

    private Outcome evalRemainingLife(List<Map<String, Object>> active, List<Map<String, Object>> expired,
                                      List<Map<String, Object>> revoked, String mode) {
        if (active.isEmpty()) {
            return emptyOutcome(expired, revoked);
        }
        // 每个检测方法取一项代表：来源优先级模式按优先级，断言时态模式按分析日期最新报告
        Map<String, Map<String, Object>> byMethod = new LinkedHashMap<>();
        for (Map<String, Object> row : active) {
            String method = optional(row.get("inspection_method"));
            method = method == null ? "UNSPECIFIED" : method;
            Map<String, Object> current = byMethod.get(method);
            if (current == null || better(row, current, mode)) {
                byMethod.put(method, row);
            }
        }
        List<Map<String, Object>> methodIntervals = new ArrayList<>();
        List<String> incomplete = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : byMethod.entrySet()) {
            BigDecimal low = decimal(entry.getValue().get("life_lower_hours"));
            BigDecimal high = decimal(entry.getValue().get("life_upper_hours"));
            if (low == null || high == null) {
                incomplete.add(entry.getKey());
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("method", entry.getKey());
            item.put("assertionNo", entry.getValue().get("assertion_no"));
            item.put("lowerHours", low);
            item.put("upperHours", high);
            methodIntervals.add(item);
        }
        if (methodIntervals.isEmpty()) {
            return new Outcome("UNKNOWN", null, "剩余寿命断言未给出完整区间", Map.of("incompleteMethods", incomplete));
        }
        // 不相交检测：来自不同方法且区间无交集时绝不平均
        for (int i = 0; i < methodIntervals.size(); i++) {
            for (int j = i + 1; j < methodIntervals.size(); j++) {
                Map<String, Object> a = methodIntervals.get(i);
                Map<String, Object> b = methodIntervals.get(j);
                BigDecimal aLow = (BigDecimal) a.get("lowerHours");
                BigDecimal aUp = (BigDecimal) a.get("upperHours");
                BigDecimal bLow = (BigDecimal) b.get("lowerHours");
                BigDecimal bUp = (BigDecimal) b.get("upperHours");
                if (aLow.compareTo(bUp) > 0 || bLow.compareTo(aUp) > 0) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("methodIntervals", methodIntervals);
                    detail.put("rule", "不同检测方法且不相交的寿命区间不允许平均");
                    detail.put("incompleteMethods", incomplete);
                    return new Outcome("CONFLICT", null,
                            "检测方法 " + a.get("method") + " 与 " + b.get("method")
                                    + " 的剩余寿命区间不相交，禁止平均，保持冲突", detail);
                }
            }
        }
        Map<String, Object> chosen = select(active, mode);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("methodIntervals", methodIntervals);
        detail.put("incompleteMethods", incomplete);
        detail.put("averaged", false);
        if (methodIntervals.size() > 1 && "TEMPORAL".equals(mode)) {
            BigDecimal low = methodIntervals.stream()
                    .map(m -> (BigDecimal) m.get("lowerHours")).max(Comparator.naturalOrder()).orElseThrow();
            BigDecimal high = methodIntervals.stream()
                    .map(m -> (BigDecimal) m.get("upperHours")).min(Comparator.naturalOrder()).orElseThrow();
            detail.put("combinedInterval", Map.of("lowerHours", low, "upperHours", high));
            detail.put("combination", "取交集（非平均）");
            return new Outcome("CONFIRMED", chosen,
                    "多检测方法区间重叠，取交集 [" + low + ", " + high + "] 小时，未取平均", detail);
        }
        detail.put("interval", Map.of("lowerHours", chosen.get("life_lower_hours"),
                "upperHours", chosen.get("life_upper_hours")));
        return new Outcome("CONFIRMED", chosen,
                "按单一检测方法确认剩余寿命区间 [" + chosen.get("life_lower_hours") + ", "
                        + chosen.get("life_upper_hours") + "] 小时", detail);
    }

    private Outcome evalPairing(List<Map<String, Object>> active, List<Map<String, Object>> expired,
                                List<Map<String, Object>> revoked, String mode) {
        if (active.isEmpty()) {
            return emptyOutcome(expired, revoked);
        }
        Map<String, Object> chosen = select(active, mode);
        List<Map<String, Object>> evidence = pairingEvidence(longVal(chosen.get("id"), "id"));
        boolean sideA = evidence.stream()
                .anyMatch(e -> "A".equals(e.get("side")) && e.get("revoked_at") == null);
        boolean sideB = evidence.stream()
                .anyMatch(e -> "B".equals(e.get("side")) && e.get("revoked_at") == null);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("pairingEvidence", evidence);
        detail.put("pairPeerSubjectId", chosen.get("pair_peer_subject_id"));
        if (!sideA || !sideB) {
            String reason = !sideA && !sideB
                    ? "配对两侧证据均已撤销，无可确认证据"
                    : "配对" + (sideA ? " B" : " A") + " 侧证据已撤销，整对不可确认（对侧证据保留）";
            return new Outcome(!sideA && !sideB ? "UNKNOWN" : "CONFLICT", chosen, reason, detail);
        }
        return new Outcome("CONFIRMED", chosen,
                "配对两侧证据均有效，原始配对值 " + chosen.get("raw_value"), detail);
    }

    private Outcome emptyOutcome(List<Map<String, Object>> expired, List<Map<String, Object>> revoked) {
        if (!expired.isEmpty()) {
            return new Outcome("EXPIRED", null,
                    "仅存超期报告（有效期已过），超期报告不能继续支撑确认", Map.of());
        }
        if (!revoked.isEmpty()) {
            return new Outcome("UNKNOWN", null, "全部来源断言已撤销，无可信证据", Map.of());
        }
        return new Outcome("UNKNOWN", null, "分析日期不存在该属性的断言", Map.of());
    }

    private Map<String, Object> select(List<Map<String, Object>> active, String mode) {
        List<Map<String, Object>> sorted = new ArrayList<>(active);
        sorted.sort("SOURCE_PRIORITY".equals(mode)
                ? (a, b) -> compareAssertion(a, b, true)
                : (a, b) -> compareAssertion(a, b, false));
        return sorted.get(0);
    }

    private boolean better(Map<String, Object> candidate, Map<String, Object> current, String mode) {
        return compareAssertion(candidate, current, "SOURCE_PRIORITY".equals(mode)) < 0;
    }

    private int compareAssertion(Map<String, Object> a, Map<String, Object> b, boolean priorityFirst) {
        int cmp;
        if (priorityFirst) {
            cmp = Integer.compare(intVal(b.get("source_priority")), intVal(a.get("source_priority")));
            if (cmp != 0) {
                return cmp;
            }
            cmp = compareDates(b.get("report_date"), a.get("report_date"));
        } else {
            cmp = compareDates(b.get("report_date"), a.get("report_date"));
            if (cmp != 0) {
                return cmp;
            }
            cmp = Integer.compare(intVal(b.get("source_priority")), intVal(a.get("source_priority")));
        }
        return cmp != 0 ? cmp : Long.compare(longVal(b.get("id"), "id"), longVal(a.get("id"), "id"));
    }

    private int compareDates(Object left, Object right) {
        OffsetDateTime l = db.toOffsetDateTime(left);
        OffsetDateTime r = db.toOffsetDateTime(right);
        if (l == null && r == null) {
            return 0;
        }
        if (l == null) {
            return 1;
        }
        if (r == null) {
            return -1;
        }
        return l.compareTo(r);
    }

    private List<Map<String, Object>> buildExceptions(List<Map<String, Object>> known,
                                                       Map<String, Object> chosen, String mode) {
        List<Map<String, Object>> exceptions = new ArrayList<>();
        for (Map<String, Object> row : known) {
            if (chosen != null && longVal(row.get("id"), "id") == longVal(chosen.get("id"), "id")) {
                continue;
            }
            String validity = String.valueOf(row.get("validity"));
            String reason = switch (validity) {
                case "REVOKED" -> "来源已撤销";
                case "EXPIRED" -> "报告已过期（有效期 " + row.get("valid_until") + "），不能支撑确认";
                default -> "SOURCE_PRIORITY".equals(mode)
                        ? "来源优先级低于当前选中断言（" + row.get("source_priority") + " < "
                          + (chosen == null ? "-" : chosen.get("source_priority")) + "），列为异常"
                        : "非分析日期主见证，列为历史见证";
            };
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("assertionNo", row.get("assertion_no"));
            item.put("sourceKind", row.get("source_kind"));
            item.put("rawValue", row.get("raw_value"));
            item.put("normalizedValue", row.get("normalized_value"));
            item.put("inspectionMethod", row.get("inspection_method"));
            item.put("reportDate", row.get("report_date"));
            item.put("validUntil", row.get("valid_until"));
            item.put("validity", validity);
            item.put("reason", reason);
            exceptions.add(item);
        }
        return exceptions;
    }

    private List<Map<String, Object>> buildSourcePath(long subjectId, String key,
                                                      List<Map<String, Object>> known) {
        List<Map<String, Object>> path = new ArrayList<>();
        for (Map<String, Object> row : known) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("assertionNo", row.get("assertion_no"));
            item.put("sourceKind", row.get("source_kind"));
            item.put("sourcePriority", row.get("source_priority"));
            item.put("inspectionMethod", row.get("inspection_method"));
            item.put("rawValue", row.get("raw_value"));
            item.put("normalizedValue", row.get("normalized_value"));
            item.put("manufacturerPrefix", row.get("manufacturer_prefix"));
            item.put("assertedModelId", row.get("asserted_model_id"));
            item.put("renumberCertNo", row.get("renumber_cert_no"));
            item.put("lifeLowerHours", row.get("life_lower_hours"));
            item.put("lifeUpperHours", row.get("life_upper_hours"));
            item.put("pairPeerSubjectId", row.get("pair_peer_subject_id"));
            item.put("reportDate", row.get("report_date"));
            item.put("validUntil", row.get("valid_until"));
            item.put("validity", row.get("validity"));
            item.put("revokedSide", row.get("revoked_side"));
            item.put("revokeReason", row.get("revoke_reason"));
            item.put("sourceRef", row.get("source_ref"));
            item.put("payloadDigest", row.get("payload_digest"));
            if ("PAIRING".equals(key)) {
                item.put("pairingEvidence", pairingEvidence(longVal(row.get("id"), "id")));
            }
            path.add(item);
        }
        return path;
    }

    // ------------------------------------------------------------------
    // 持久化与质量事件
    // ------------------------------------------------------------------
    private Map<String, Object> persistModeSnapshot(long subjectId, OffsetDateTime date,
                                                    Map<String, Map<String, Object>> computed) {
        List<Map<String, Object>> selections = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : computed.entrySet()) {
            Map<String, Object> result = entry.getValue();
            Map<String, Object> selection = new LinkedHashMap<>();
            selection.put("attributeKey", entry.getKey());
            selection.put("status", result.get("status"));
            @SuppressWarnings("unchecked")
            Map<String, Object> chosen = (Map<String, Object>) result.get("chosen");
            selection.put("chosenAssertionNo", chosen == null ? null : chosen.get("assertion_no"));
            selection.put("chosenRawValue", chosen == null ? null : chosen.get("raw_value"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> exceptions =
                    (List<Map<String, Object>>) result.get("exceptions");
            selection.put("exceptions", exceptions.stream().map(e -> Map.of(
                    "assertionNo", String.valueOf(e.get("assertionNo")),
                    "reason", String.valueOf(e.get("reason")),
                    "validity", String.valueOf(e.get("validity")))).toList());
            selections.add(selection);
        }
        long count = db.count("SELECT count(*) FROM attribute_mode_snapshot");
        String snapshotNo = String.format("ATTR-MODE-%s-%05d", date.toLocalDate(),
                count + 1);
        String digest = DigestUtil.sha256(snapshotNo + "|" + date + "|" + db.toJson(selections));
        long id = db.insertReturningId(
                "INSERT INTO attribute_mode_snapshot(snapshot_no,mode,subject_id,analysis_date," +
                "selections,selection_digest,created_by) VALUES (?,?,?,?,?::jsonb,?,?) RETURNING id",
                snapshotNo, "SOURCE_PRIORITY", subjectId, date, db.toJson(selections), digest,
                CurrentUser.username());
        return db.queryOne("SELECT * FROM attribute_mode_snapshot WHERE id=?", id);
    }

    private Map<String, Object> persistEvaluation(Map<String, Object> subject, String key,
                                                  OffsetDateTime date, String mode,
                                                  Map<String, Object> result, Long modeSnapshotId) {
        @SuppressWarnings("unchecked")
        Map<String, Object> chosen = (Map<String, Object>) result.get("chosen");
        Long chosenId = chosen == null ? null : longVal(chosen.get("id"), "id");
        long count = db.count("SELECT count(*) FROM attribute_evaluation");
        String evaluationNo = String.format("ATTR-EVAL-%05d", count + 1);
        long id = db.insertReturningId(
                "INSERT INTO attribute_evaluation(evaluation_no,mode,subject_id,attribute_key,analysis_date," +
                "status,chosen_assertion_id,exceptions,evidence_gaps,source_path,detail,mode_snapshot_id," +
                "created_by) VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?) RETURNING id",
                evaluationNo, mode, longVal(subject.get("id"), "id"), key, date,
                result.get("status"), chosenId,
                db.toJson(result.get("exceptions")), db.toJson(result.get("gaps")),
                db.toJson(result.get("sourcePath")),
                db.toJson(Map.of("reason", String.valueOf(result.get("reason")),
                        "detail", result.get("detail"),
                        "dependencyTrace", result.get("dependencyTrace"))),
                modeSnapshotId, CurrentUser.username());
        Map<String, Object> saved = db.queryOne("SELECT * FROM attribute_evaluation WHERE id=?", id);
        // 响应只保留一套字段名：台账原列 + 求值结果的驼峰视图，避免同一数据出现两种命名
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", saved.get("id"));
        response.put("evaluationNo", saved.get("evaluation_no"));
        response.put("mode", saved.get("mode"));
        response.put("subjectId", saved.get("subject_id"));
        response.put("subjectCode", subject.get("entity_code"));
        response.put("attributeKey", key);
        response.put("analysisDate", saved.get("analysis_date"));
        response.put("status", saved.get("status"));
        response.put("chosenAssertionId", saved.get("chosen_assertion_id"));
        response.put("reason", result.get("reason"));
        response.put("chosen", chosen);
        response.put("exceptions", result.get("exceptions"));
        response.put("evidenceGaps", result.get("gaps"));
        response.put("sourcePath", result.get("sourcePath"));
        response.put("detail", result.get("detail"));
        response.put("modeSnapshotId", saved.get("mode_snapshot_id"));
        response.put("createdBy", saved.get("created_by"));
        response.put("createdAt", saved.get("created_at"));
        return response;
    }

    private void recordQualityEvents(Map<String, Object> subject, String key, OffsetDateTime date,
                                     String mode, Map<String, Object> result) {
        long subjectId = longVal(subject.get("id"), "id");
        String code = String.valueOf(subject.get("entity_code"));
        String status = String.valueOf(result.get("status"));
        String reason = String.valueOf(result.get("reason"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gaps = (List<Map<String, Object>>) result.get("gaps");
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("attributeKey", key);
        detail.put("analysisDate", date.toString());
        detail.put("mode", mode);
        detail.put("status", status);
        if ("CONFLICT".equals(status)) {
            recordOnce("ATTRIBUTE_CONFLICT", "HIGH", subjectId,
                    "实物 " + code + " 属性 " + key + " 在分析日期 " + date.toLocalDate() + " 判定冲突：" + reason,
                    detail);
        } else if ("EXPIRED".equals(status)) {
            recordOnce("ATTRIBUTE_EXPIRED", "MEDIUM", subjectId,
                    "实物 " + code + " 属性 " + key + " 仅有超期报告，不能支撑确认", detail);
        }
        if (gaps != null && !gaps.isEmpty()) {
            detail.put("gaps", gaps);
            recordOnce("ATTRIBUTE_GAP", "MEDIUM", subjectId,
                    "实物 " + code + " 属性 " + key + " 依赖属性证据缺口：" + gaps.size() + " 项，缺口已传播",
                    detail);
        }
    }

    private void recordOnce(String type, String severity, long subjectId, String description,
                            Map<String, Object> detail) {
        long existing = db.count("SELECT count(*) FROM quality_event WHERE event_type=? " +
                        "AND related_entity_type='PHYSICAL_PART' AND related_entity_id=? " +
                        "AND handling_status='OPEN' AND detail->>'attributeKey'=? " +
                        "AND detail->>'status'=?",
                type, subjectId, String.valueOf(detail.get("attributeKey")),
                String.valueOf(detail.get("status")));
        if (existing == 0) {
            qualityEvents.record(type, severity, "PHYSICAL_PART", subjectId, null, null,
                    description, detail);
        }
    }

    // ------------------------------------------------------------------
    // 历史回放
    // ------------------------------------------------------------------
    public List<Map<String, Object>> listEvaluations(Long subjectId, String attributeKey, String mode) {
        StringBuilder sql = new StringBuilder(
                "SELECT ev.*, s.entity_code AS subject_code, a.assertion_no AS chosen_assertion_no, " +
                "a.raw_value AS chosen_raw_value, ms.snapshot_no AS mode_snapshot_no " +
                "FROM attribute_evaluation ev " +
                "JOIN master_entity s ON s.id=ev.subject_id " +
                "LEFT JOIN attribute_assertion a ON a.id=ev.chosen_assertion_id " +
                "LEFT JOIN attribute_mode_snapshot ms ON ms.id=ev.mode_snapshot_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (subjectId != null) {
            sql.append(" AND ev.subject_id=?");
            args.add(subjectId);
        }
        if (attributeKey != null && !attributeKey.isBlank()) {
            sql.append(" AND ev.attribute_key=?");
            args.add(attributeKey.toUpperCase(Locale.ROOT));
        }
        if (mode != null && !mode.isBlank()) {
            sql.append(" AND ev.mode=?");
            args.add(normalizeMode(mode));
        }
        sql.append(" ORDER BY ev.id DESC LIMIT 200");
        return db.query(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> listModeSnapshots(Long subjectId) {
        if (subjectId == null) {
            return db.query("SELECT * FROM attribute_mode_snapshot ORDER BY id DESC LIMIT 100");
        }
        return db.query("SELECT * FROM attribute_mode_snapshot WHERE subject_id=? ORDER BY id DESC", subjectId);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------
    private List<String> orderedKeys() {
        List<Map<String, Object>> deps = db.query(
                "SELECT attribute_key, depends_on_key FROM attribute_dependency");
        Set<String> all = new LinkedHashSet<>(BASE_ORDER);
        deps.forEach(d -> {
            all.add(String.valueOf(d.get("attribute_key")));
            all.add(String.valueOf(d.get("depends_on_key")));
        });
        List<String> ordered = new ArrayList<>();
        Set<String> visiting = new HashSet<>();
        Set<String> done = new HashSet<>();
        for (String key : all) {
            visit(key, deps, ordered, visiting, done);
        }
        return ordered;
    }

    private void visit(String key, List<Map<String, Object>> deps, List<String> ordered,
                       Set<String> visiting, Set<String> done) {
        if (done.contains(key) || visiting.contains(key)) {
            return;
        }
        visiting.add(key);
        for (Map<String, Object> dep : deps) {
            if (key.equals(String.valueOf(dep.get("attribute_key")))) {
                visit(String.valueOf(dep.get("depends_on_key")), deps, ordered, visiting, done);
            }
        }
        visiting.remove(key);
        done.add(key);
        ordered.add(key);
    }

    private String validityOf(Map<String, Object> row, OffsetDateTime date) {
        if ("REVOKED".equals(row.get("status"))) {
            return "REVOKED";
        }
        OffsetDateTime validUntil = db.toOffsetDateTime(row.get("valid_until"));
        if (validUntil != null && validUntil.isBefore(date)) {
            return "EXPIRED";
        }
        return "VALID";
    }

    private List<Map<String, Object>> filterValidity(List<Map<String, Object>> rows, String validity) {
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (validity.equals(row.get("validity"))) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    /**
     * 编号归一：去掉制造商前缀与非字母数字，末段数字去前导零。仅用于比对，原文独立保留。
     */
    static String normalizeNumber(String raw, String manufacturerPrefix) {
        if (raw == null) {
            return "";
        }
        String value = raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (manufacturerPrefix != null && !manufacturerPrefix.isBlank()) {
            String prefix = manufacturerPrefix.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
            if (!prefix.isEmpty() && value.startsWith(prefix) && value.length() > prefix.length()) {
                value = value.substring(prefix.length());
            }
        }
        Matcher matcher = TRAILING_DIGITS.matcher(value);
        if (matcher.find()) {
            String digits = matcher.group(1).replaceFirst("^0+(?!$)", "");
            value = value.substring(0, matcher.start(1)) + digits;
        }
        return value;
    }

    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "SOURCE_PRIORITY";
        }
        String value = mode.toUpperCase(Locale.ROOT);
        if (!"SOURCE_PRIORITY".equals(value) && !"TEMPORAL".equals(value)) {
            throw new IllegalArgumentException("模式只支持 SOURCE_PRIORITY / TEMPORAL");
        }
        return value;
    }

    private static long id(Map<String, Object> row) {
        return ((Number) row.get("id")).longValue();
    }

    private static int intVal(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static long longVal(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("缺少字段: " + field);
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("字段 " + field + " 不是合法数字: " + value);
        }
    }

    private static Long optionalLong(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value).trim());
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return new BigDecimal(String.valueOf(value));
    }

    private static BigDecimal optionalDecimal(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return decimal(value);
    }

    private static OffsetDateTime optionalDate(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return OffsetDateTime.parse(String.valueOf(value));
    }

    private static String required(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("缺少必填字段: " + field);
        }
        return String.valueOf(value);
    }

    private static String optional(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value);
    }

    private record Outcome(String status, Map<String, Object> chosen, String reason,
                           Map<String, Object> detail) {}
}
