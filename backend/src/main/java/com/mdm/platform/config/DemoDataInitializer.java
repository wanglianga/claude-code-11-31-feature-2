package com.mdm.platform.config;

import com.mdm.platform.repository.JdbcSupport;
import com.mdm.platform.service.AttributeAssertionService;
import com.mdm.platform.service.CompatibilityService;
import com.mdm.platform.service.ImpactAnalysisService;
import com.mdm.platform.service.IngestionService;
import com.mdm.platform.service.ReleaseGateService;
import com.mdm.platform.service.SnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 启动初始化：
 *  1. 执行 schema.sql / seed.sql（可通过 RESET_ON_START 重建）
 *  2. 按真实服务逻辑重放样例：编译各时点快照、求值兼容事实、发布、撤回规则、做影响分析
 * 所有派生数据都经服务产生，保证任一兼容结论可在接口上回放。
 */
@Component
public class DemoDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    private final DataSource dataSource;
    private final JdbcSupport db;
    private final SnapshotService snapshotService;
    private final CompatibilityService compatibilityService;
    private final ReleaseGateService releaseGateService;
    private final IngestionService ingestionService;
    private final ImpactAnalysisService impactAnalysisService;
    private final AttributeAssertionService attributeService;

    @Value("${app.demo.auto-init:true}")
    private boolean autoInit;

    @Value("${app.demo.reset-on-start:false}")
    private boolean resetOnStart;

    public DemoDataInitializer(DataSource dataSource, JdbcSupport db,
                               SnapshotService snapshotService,
                               CompatibilityService compatibilityService,
                               ReleaseGateService releaseGateService,
                               IngestionService ingestionService,
                               ImpactAnalysisService impactAnalysisService,
                               AttributeAssertionService attributeService) {
        this.dataSource = dataSource;
        this.db = db;
        this.snapshotService = snapshotService;
        this.compatibilityService = compatibilityService;
        this.releaseGateService = releaseGateService;
        this.ingestionService = ingestionService;
        this.impactAnalysisService = impactAnalysisService;
        this.attributeService = attributeService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!autoInit) {
            log.info("自动初始化已关闭(app.demo.auto-init=false)，跳过");
            return;
        }
        long userCount = db.count("SELECT count(*) FROM information_schema.tables WHERE table_name='app_user'");
        if (userCount == 0 || resetOnStart) {
            log.info("执行 schema.sql 与 seed.sql ...");
            try (Connection conn = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(conn, new ClassPathResource("db/schema.sql"));
                if (resetOnStart) {
                    // 清空派生与基础数据后重新灌种子
                    truncateAll(conn);
                }
                if (db.count("SELECT count(*) FROM app_user") == 0) {
                    ScriptUtils.executeSqlScript(conn, new ClassPathResource("db/seed.sql"));
                }
            }
        }
        if (db.count("SELECT count(*) FROM config_snapshot") > 0) {
            log.info("派生样例已存在，跳过场景重放");
            return;
        }
        replayScenarios();
    }

    private void truncateAll(Connection conn) throws Exception {
        String[] tables = {"release_audit_log","impact_analysis","quality_event","compatibility_fact",
                "conflict_fact","snapshot_position","config_snapshot","rule_constraint","substitution_rule",
                "inspection_record","factory_record","install_event","drawing_revision","drawing",
                "entity_alias_candidate","master_entity","ingestion_contract_version","ingestion_contract",
                "attribute_evaluation","attribute_mode_snapshot","attribute_dependency",
                "pairing_evidence","attribute_assertion","app_user"};
        try (var st = conn.createStatement()) {
            for (String t : tables) {
                st.execute("TRUNCATE TABLE " + t + " RESTART IDENTITY CASCADE");
            }
        }
    }

    private void replayScenarios() {
        log.info("==== 开始重放五大核验场景 ====");

        // 设备/位置/型号 ID（与 seed.sql 固定主键一致）
        final long EQ7 = 10, EQ8 = 11, EQ18 = 12;
        final long POS_SERVO_X = 21, POS_POWER_BOX = 22, POS_COOLANT = 23;
        final long MODEL_SDU_C3 = 34, MODEL_PWR_501 = 36, MODEL_PWR_502 = 37;
        final long RULE1 = 1, RULE2 = 2, RULE3 = 3;

        // ---------- 场景 B：迟到拆装凭证 ----------
        // 2024-12-01 时点编译：此时冷却泵迟到凭证(2025-03-01 才接收)尚不可见 -> 冷却位为空
        OffsetDateTime beforeLate = OffsetDateTime.parse("2024-12-01T00:00:00Z");
        var snapBeforeLate = snapshotService.compile(new SnapshotService.CompileRequest(
                EQ7, beforeLate, beforeLate, "system-replay"));
        long snapBeforeLateId = ((Number) snapBeforeLate.get("id")).longValue();
        log.info("场景B-1: 迟到凭证接收前快照 {} 编译完成", snapBeforeLate.get("snapshot_no"));

        // 2025-03-02 时点编译：迟到凭证已纳入 -> 冷却位出现冷却泵；旧快照保留不改写
        OffsetDateTime afterLate = OffsetDateTime.parse("2025-03-02T00:00:00Z");
        var snapAfterLate = snapshotService.compile(new SnapshotService.CompileRequest(
                EQ7, OffsetDateTime.parse("2024-12-01T00:00:00Z"), afterLate, "system-replay"));
        long snapAfterLateId = ((Number) snapAfterLate.get("id")).longValue();
        log.info("场景B-2: 迟到凭证纳入后新版本快照 {} 编译完成，旧快照保留", snapAfterLate.get("snapshot_no"));

        // ---------- 场景 E + D：伺服替代（两约束联动 PASS）并发布，随后规则撤回 ----------
        // 2024-08-01 维修时点：伺服位仍在装 A1（9月才换装 C3），在此评估 A1 -> C3 的替代兼容性
        OffsetDateTime beforeReplacement = OffsetDateTime.parse("2024-08-01T00:00:00Z");
        var snapAug = snapshotService.compile(new SnapshotService.CompileRequest(
                EQ7, beforeReplacement, beforeReplacement, "system-replay"));
        long snapAugId = ((Number) snapAug.get("id")).longValue();
        var servoFact = compatibilityService.evaluate(new CompatibilityService.EvaluationRequest(
                snapAugId, POS_SERVO_X, MODEL_SDU_C3));
        long servoFactId = ((Number) servoFact.get("id")).longValue();
        log.info("场景E: 伺服 A1->C3 求值结论 {}", servoFact.get("verdict"));
        if ("PASS".equals(servoFact.get("verdict"))) {
            releaseGateService.transition(servoFactId, "publish", "两约束(电源+协议)联动通过，签发对外");
            log.info("场景D-1: 伺服兼容事实已发布为 VERIFIED");
        }

        // ---------- 场景 E：电源 500->501 PASS；500->502 因尺寸 3U≠2U 联动 FAIL ----------
        // 12 月快照电源仓仍在装 500，规则 2/3 已于 2024-08-15 生效
        var powerPass = compatibilityService.evaluate(new CompatibilityService.EvaluationRequest(
                snapBeforeLateId, POS_POWER_BOX, MODEL_PWR_501));
        long powerPassId = ((Number) powerPass.get("id")).longValue();
        if ("PASS".equals(powerPass.get("verdict"))) {
            releaseGateService.transition(powerPassId, "publish", "电源+尺寸联动通过");
        }
        var powerFail = compatibilityService.evaluate(new CompatibilityService.EvaluationRequest(
                snapBeforeLateId, POS_POWER_BOX, MODEL_PWR_502));
        log.info("场景E: 电源 500->501={} / 500->502={}（尺寸约束拦截）",
                powerPass.get("verdict"), powerFail.get("verdict"));

        // ---------- 场景 D：供应商撤回规则1 ----------
        ingestionService.retireRule(RULE1, "供应商通知：C3 批次 E2411 协议栈缺陷，收回替代授权");
        var ruleImpact = impactAnalysisService.analyzeRuleChange(RULE1, "RETIRE",
                "供应商收回 SUB-RULE-001，历史结论保留锁定版本并另发更正");
        log.info("场景D-2: 规则撤回影响分析完成，受影响事实 {}，更正链已生成",
                ruleImpact.get("affected_fact_ids"));

        // 撤回后对同一历史时点重新求值：历史规则在该时点仍有效 -> PASS 但标注已撤回
        var reEval = compatibilityService.evaluate(new CompatibilityService.EvaluationRequest(
                snapAugId, POS_SERVO_X, MODEL_SDU_C3));
        log.info("场景D-3: 撤回后历史时点重评结论 {}（锁定规则版本 {}）",
                reEval.get("verdict"), reEval.get("rule_version_locked"));

        // ---------- 场景 A：跨修订序列号区间重叠 -> 冲突事实阻止发布 ----------
        var conflictSnap = snapshotService.compile(new SnapshotService.CompileRequest(
                EQ18, OffsetDateTime.parse("2024-07-15T00:00:00Z"), null, "system-replay"));
        long conflictSnapId = ((Number) conflictSnap.get("id")).longValue();
        log.info("场景A: 设备0018 快照 has_conflict={} status={}",
                conflictSnap.get("has_conflict"), conflictSnap.get("status"));
        // 试图在冲突快照上发布结论 -> 闸门必须拦截（捕获异常留痕，不中断初始化）
        try {
            var blocked = compatibilityService.evaluate(new CompatibilityService.EvaluationRequest(
                    conflictSnapId, 20, MODEL_SDU_C3));
            releaseGateService.transition(((Number) blocked.get("id")).longValue(), "publish",
                    "尝试在冲突快照发布");
            log.warn("场景A: 闸门未拦截，不符合预期");
        } catch (IllegalStateException ex) {
            log.info("场景A: 发布闸门正确拦截 -> {}", ex.getMessage());
        }

        // ---------- 场景：同一实物重叠时间出现在两台设备 -> 冻结 ----------
        // EQ8 的编译会触发 PP-PWR-0001 双重安装检测
        var eq8Snap = snapshotService.compile(new SnapshotService.CompileRequest(
                EQ8, OffsetDateTime.parse("2024-06-01T00:00:00Z"), null, "system-replay"));
        log.info("实体冻结: 设备0008 快照 status={}，双重安装冲突已登记", eq8Snap.get("status"));

        // 迟到凭证事件影响分析（场景B 配套）
        impactAnalysisService.analyzeInstallEventChange(4L);
        // 图号修订影响分析（场景A 配套）
        impactAnalysisService.analyzeDrawingChange(2L);

        replayAttributeScenarios();

        log.info("==== 五大场景重放完成，共生成快照 {} 个，兼容事实 {} 条，质量事件 {} 条 ====",
                db.count("SELECT count(*) FROM config_snapshot"),
                db.count("SELECT count(*) FROM compatibility_fact"),
                db.count("SELECT count(*) FROM quality_event"));
    }

    /**
     * 属性断言场景重放：编号变体 / 寿命冲突 / 单向撤销 / 过期引用（含缺口传播）/ 规范号主体冲突。
     * 每条结论均通过真实求值服务产生并留档，接口可直接回放。
     */
    private void replayAttributeScenarios() {
        log.info("==== 开始重放属性断言场景（编号变体/单向撤销/寿命冲突/过期引用）====");
        final long PP_MTR_0001 = 50, PP_MTR_0018 = 51, PP_SDU_0001 = 52;
        OffsetDateTime analyzeAt = OffsetDateTime.parse("2024-12-01T00:00:00Z");

        // 编号变体：三来源不同写法，归一一致 -> CONFIRMED，原文保留
        var serial = attributeService.evaluate(new AttributeAssertionService.EvaluationRequest(
                PP_MTR_0001, "SERIAL_NUMBER", analyzeAt, "TEMPORAL"));
        log.info("属性-编号变体: PP-MTR-0001 SERIAL_NUMBER -> {}", statusOf(serial));

        // 寿命冲突：ULTRASONIC 1200~1500h 与 VIBRATION 300~500h 不相交 -> CONFLICT，不平均
        var life = attributeService.evaluate(new AttributeAssertionService.EvaluationRequest(
                PP_MTR_0001, "REMAINING_LIFE", analyzeAt, "TEMPORAL"));
        log.info("属性-寿命冲突: PP-MTR-0001 REMAINING_LIFE -> {}", statusOf(life));

        // 配对：先以两侧证据求值（CONFIRMED），再撤销 B 侧 -> 整对不可确认，A 侧证据保留
        var pairBefore = attributeService.evaluate(new AttributeAssertionService.EvaluationRequest(
                PP_MTR_0001, "PAIRING", analyzeAt, "TEMPORAL"));
        log.info("属性-配对(撤销前): {}", statusOf(pairBefore));
        Long pairAssertionId = db.queryOne(
                "SELECT id FROM attribute_assertion WHERE subject_id=? AND attribute_key='PAIRING' " +
                "ORDER BY id LIMIT 1", PP_MTR_0001) == null ? null
                : ((Number) db.queryOne(
                "SELECT id FROM attribute_assertion WHERE subject_id=? AND attribute_key='PAIRING' " +
                "ORDER BY id LIMIT 1", PP_MTR_0001).get("id")).longValue();
        if (pairAssertionId != null) {
            attributeService.revokeAssertion(pairAssertionId, "B",
                    "外协资料撤回对侧签收凭证，单向撤销");
            var pairAfter = attributeService.evaluate(new AttributeAssertionService.EvaluationRequest(
                    PP_MTR_0001, "PAIRING", analyzeAt, "TEMPORAL"));
            log.info("属性-配对(撤销B侧后): {}（A 侧证据保留）", statusOf(pairAfter));
        }

        // 过期引用：PP-MTR-0018 安装日期仅超期报告 -> EXPIRED；寿命因依赖缺口 -> UNKNOWN
        var legacyInstall = attributeService.evaluate(new AttributeAssertionService.EvaluationRequest(
                PP_MTR_0018, "INSTALL_DATE", OffsetDateTime.parse("2024-12-01T00:00:00Z"), "TEMPORAL"));
        var legacyLife = attributeService.evaluate(new AttributeAssertionService.EvaluationRequest(
                PP_MTR_0018, "REMAINING_LIFE", OffsetDateTime.parse("2024-12-01T00:00:00Z"), "TEMPORAL"));
        log.info("属性-过期引用: PP-MTR-0018 INSTALL_DATE -> {}；REMAINING_LIFE 缺口传播 -> {}",
                statusOf(legacyInstall), statusOf(legacyLife));

        // 规范号主体冲突：同一规范号指向 100A/100B 且无改号凭证 -> CONFLICT
        var catalog = attributeService.evaluate(new AttributeAssertionService.EvaluationRequest(
                PP_MTR_0018, "CATALOG_NUMBER", analyzeAt, "TEMPORAL"));
        log.info("属性-主体冲突: PP-MTR-0018 CATALOG_NUMBER -> {}", statusOf(catalog));

        // 改号凭证对照：跨型号但有改号凭证 -> CONFIRMED
        var renumbered = attributeService.evaluate(new AttributeAssertionService.EvaluationRequest(
                PP_SDU_0001, "CATALOG_NUMBER", analyzeAt, "TEMPORAL"));
        log.info("属性-改号凭证: PP-SDU-0001 CATALOG_NUMBER -> {}", statusOf(renumbered));

        // 来源优先级模式：每个属性选一项当前断言、其余列异常，并落选择快照解释历史选择
        var priority = attributeService.evaluate(new AttributeAssertionService.EvaluationRequest(
                PP_MTR_0001, null, analyzeAt, "SOURCE_PRIORITY"));
        log.info("属性-来源优先级模式: PP-MTR-0001 全属性求值快照 {} 完成",
                priority.get("modeSnapshotNo"));

        log.info("==== 属性断言场景重放完成，求值台账 {} 条，选择快照 {} 份 ====",
                db.count("SELECT count(*) FROM attribute_evaluation"),
                db.count("SELECT count(*) FROM attribute_mode_snapshot"));
    }

    private String statusOf(Map<String, Object> evaluation) {
        Object results = evaluation.get("results");
        if (results instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> first) {
            return String.valueOf(first.get("status"));
        }
        return String.valueOf(evaluation.get("status"));
    }
}
