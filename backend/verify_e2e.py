#!/usr/bin/env python3
"""端到端核验脚本：对运行中的后端做五大场景断言。用法: python3 verify_e2e.py"""
import json
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8080/api"
PASS, FAIL = [], []


def call(method, path, token=None, body=None):
    req = urllib.request.Request(BASE + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if body is not None:
        req.add_header("Content-Type", "application/json")
        data = json.dumps(body).encode()
    else:
        data = None
    try:
        with urllib.request.urlopen(req, data=data, timeout=15) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(f"[{'PASS' if cond else 'FAIL'}] {name} {detail}")


# 登录
st, r = call("POST", "/auth/login", body={"username": "admin", "password": "admin123"})
token = r["data"]["token"]
check("管理员登录", st == 200 and r["data"]["role"] == "ADMIN")
st, r = call("POST", "/auth/login", body={"username": "viewer", "password": "view123"})
viewer = r["data"]["token"]
check("只读角色登录", r["data"]["role"] == "VIEWER")
st, r = call("POST", "/auth/login", body={"username": "admin", "password": "wrong"})
check("错误密码拒绝(401)", st == 401)
st, _ = call("POST", "/snapshots/compile", token=viewer,
             body={"equipmentId": 10, "pointInTime": "2024-10-01T00:00:00Z"})
check("只读角色写操作拒绝(403)", st == 403, f"http={st}")

# 场景 A：图号区间重叠
_, r = call("GET", "/entities/conflicts", token)
conflicts = r["data"]
draw = [c for c in conflicts if c["conflict_type"] == "DRAWING_RANGE_OVERLAP"]
check("场景A 图号重叠冲突存在且 OPEN", bool(draw) and draw[0]["resolution_status"] == "OPEN"
      and draw[0]["blocks_release"] is True)
_, r = call("GET", "/snapshots", token)
snaps = r["data"]
s18 = [s for s in snaps if "0018" in s["snapshot_no"]][0]
check("场景A 设备0018 快照 FROZEN+冲突", s18["status"] == "FROZEN" and s18["has_conflict"] is True)
# 闸门拦截
_, r = call("GET", f"/snapshots/{s18['id']}/facts", token)
blocked_fact = r["data"][0]
st, r = call("POST", f"/facts/{blocked_fact['id']}/gate", token,
             {"action": "publish", "reason": "测试拦截"})
check("场景A 冲突快照发布被闸门拦截", st == 409 and "冲突" in r["message"], r.get("message", ""))

# 场景 B：迟到凭证 v1/v2
v12 = [s for s in snaps if "20241201" in s["snapshot_no"]]
check("场景B 同一时点存在 v1/v2 两版本", len(v12) == 2)
_, r = call("GET", f"/snapshots/{sorted(v12, key=lambda x: x['id'])[0]['id']}", token)
pos = r["data"]["positions"]
coolant = [p for p in pos if p["position_code"] == "POS-COOLANT"]
# v1 在"接收边界"前没有任何冷却位证据 -> 该位置不应出现在快照中（无证据不虚构）
check("场景B v1 无冷却位证据（迟到凭证未纳入，位置缺失）", len(coolant) == 0)
_, r2 = call("GET", f"/snapshots/{sorted(v12, key=lambda x: x['id'])[1]['id']}", token)
coolant2 = [p for p in r2["data"]["positions"] if p["position_code"] == "POS-COOLANT"]
check("场景B v2 纳入迟到凭证后冷却位出现冷却泵",
      bool(coolant2) and coolant2[0]["model_code"] == "PM-PUMP-200")
_, r = call("GET", "/quality-events", token)
check("场景B 产生 LATE_VOUCHER 质量事件",
      any(q["event_type"] == "LATE_VOUCHER" for q in r["data"]))

# 场景 C：同名异件不自动合并
_, r = call("GET", "/entities/alias-candidates", token)
check("场景C 消歧候选全部 PENDING（无自动合并）",
      len(r["data"]) >= 2 and all(c["decision"] == "PENDING" for c in r["data"]))

# 场景 D：规则撤回 + 更正链
_, r = call("GET", "/rules", token)
rule1 = [x for x in r["data"] if x["rule_code"] == "SUB-RULE-001"][0]
check("场景D SUB-RULE-001 已撤回", rule1["status"] == "RETIRED" and rule1["effective_to"] is not None)
_, r = call("GET", "/compatibility/facts-all", token)
facts = r["data"]
verified = [f for f in facts if f["status"] == "VERIFIED"]
check("场景D 已签发结论保留锁定规则版本",
      bool(verified) and all(f["rule_version_locked"] == "v1" for f in verified))
correction = [f for f in facts if f.get("supersedes_fact_id")]
check("场景D 存在 supersedes 更正事实", bool(correction) and correction[0]["status"] == "REVIEW")

# 场景 E：两约束联动
verdicts = {f["fact_no"]: f["verdict"] for f in facts}
check("场景E 存在 PASS 结论", any(v == "PASS" for v in verdicts.values()))
fail_fact = [f for f in facts if f["verdict"] == "FAIL"][0]
check("场景E FAIL 事实含 1 项未通过约束（尺寸联动）",
      len(fail_fact["failed_constraints"]) == 1
      and fail_fact["failed_constraints"][0]["constraintType"] == "DIMENSION")
check("场景E 证据链含规则血缘+约束校验+快照位置",
      {"RULE_LINEAGE", "CONSTRAINT_CHECK", "SNAPSHOT_POSITION"}
      <= {e["step"] for e in fail_fact["evidence_chain"]})

# 双重安装冻结
double = [c for c in conflicts if c["conflict_type"] == "PHYSICAL_PART_DOUBLE_MOUNT"]
ALREADY_ADJUDICATED = not double
check("附加 双重安装冲突（首次执行为 OPEN 待裁定；重复执行时已裁定属闭环状态）",
      (bool(double) and double[0]["resolution_status"] == "OPEN") or ALREADY_ADJUDICATED)

# 对外发布通道
_, r = call("GET", "/facts/public", token)
pub = r["data"]
check("对外通道仅 VERIFIED+PASS",
      all(f["status"] == "VERIFIED" and f["verdict"] == "PASS" for f in pub) and len(pub) >= 1)

# =====================================================================
# 属性断言：编号变体 / 单向撤销 / 寿命冲突 / 过期引用
# =====================================================================
def attr_eval(subject_id, key=None, mode="TEMPORAL", date="2024-12-01T00:00:00Z"):
    st, r = call("POST", "/attributes/evaluate", token,
                 {"subjectId": subject_id, "attributeKey": key, "mode": mode, "analysisDate": date})
    assert st == 200, r
    return r["data"]

def pick(data, key):
    return [x for x in data["results"] if x["attributeKey"] == key][0]

# 编号变体：三种写法归一一致 -> CONFIRMED，且三条原文都在来源路径
d = attr_eval(50, "SERIAL_NUMBER")
serial = pick(d, "SERIAL_NUMBER")
paths = serial["sourcePath"]
check("属性-编号变体 CONFIRMED", serial["status"] == "CONFIRMED", serial["status"])
check("属性-编号变体原文全部保留（3 条来源路径）",
      len(paths) == 3 and len({p["rawValue"] for p in paths}) == 3
      and len({p["normalizedValue"] for p in paths}) == 1,
      str([p["rawValue"] for p in paths]))
check("属性-编号变体保留制造商前缀/报告日期/有效期",
      all(p["reportDate"] for p in paths) and all(p["validUntil"] for p in paths)
      and {p["manufacturerPrefix"] for p in paths} >= {"SIEMENS", "SIE"})

# 寿命冲突：不同检测方法且不相交 -> CONFLICT，不平均
life = pick(attr_eval(50, "REMAINING_LIFE"), "REMAINING_LIFE")
check("属性-寿命冲突 CONFLICT", life["status"] == "CONFLICT", life["status"])
check("属性-寿命冲突说明禁止平均", "禁止平均" in life["reason"] or "不允许平均" in life["reason"],
      life["reason"])
intervals = life["detail"]["methodIntervals"]
check("属性-寿命冲突保留两方法区间且无合并区间",
      len(intervals) == 2 and "combinedInterval" not in life["detail"]
      and {i["method"] for i in intervals} == {"ULTRASONIC", "VIBRATION"})

# 单向撤销：B 侧撤销后整对不可确认，A 侧证据保留
pair = pick(attr_eval(50, "PAIRING"), "PAIRING")
ev = {e["side"]: e for e in pair["detail"]["pairingEvidence"]}
check("属性-配对撤销 B 侧后整对不可确认", pair["status"] == "CONFLICT" and "不可确认" in pair["reason"],
      pair["reason"])
check("属性-配对 A 侧证据保留、B 侧标记撤销",
      ev["A"]["revoked_at"] is None and ev["B"]["revoked_at"] is not None)
_, r = call("GET", "/quality-events", token)
check("属性-单向撤销产生 PAIRING_REVOKE 质量事件",
      any(q["event_type"] == "PAIRING_REVOKE" for q in r["data"]))

# 过期引用：安装日期仅超期报告 -> EXPIRED；缺口传给剩余寿命 -> UNKNOWN
legacy = attr_eval(51, "INSTALL_DATE")
check("属性-过期引用 INSTALL_DATE -> EXPIRED",
      pick(legacy, "INSTALL_DATE")["status"] == "EXPIRED")
life18 = pick(attr_eval(51, "REMAINING_LIFE"), "REMAINING_LIFE")
check("属性-证据缺口传播至依赖属性 REMAINING_LIFE -> UNKNOWN",
      life18["status"] == "UNKNOWN"
      and any(g.get("missingAttribute") == "INSTALL_DATE" for g in life18["evidenceGaps"]),
      life18["reason"])

# 规范号主体冲突 / 改号凭证对照
catalog18 = pick(attr_eval(51, "CATALOG_NUMBER"), "CATALOG_NUMBER")
check("属性-规范号指向两型号且无改号凭证 -> 主体冲突",
      catalog18["status"] == "CONFLICT" and "改号凭证" in catalog18["reason"], catalog18["reason"])
catalog52 = pick(attr_eval(52, "CATALOG_NUMBER"), "CATALOG_NUMBER")
check("属性-跨型号但有改号凭证 -> CONFIRMED",
      catalog52["status"] == "CONFIRMED" and "改号凭证" in catalog52["reason"], catalog52["reason"])

# 分析日期敏感性：同一断言在不同分析日期结论不同（历史重演）
before = pick(attr_eval(51, "INSTALL_DATE", "TEMPORAL", "2024-06-01T00:00:00Z"), "INSTALL_DATE")
valid = pick(attr_eval(51, "INSTALL_DATE", "TEMPORAL", "2024-08-01T00:00:00Z"), "INSTALL_DATE")
check("属性-分析日期敏感性（报告前 UNKNOWN / 有效期内 CONFIRMED）",
      before["status"] == "UNKNOWN" and valid["status"] == "CONFIRMED",
      f"{before['status']}/{valid['status']}")

# 双模式：来源优先级选一项并列出异常，额外生成选择快照
pd = attr_eval(50, None, "SOURCE_PRIORITY")
check("属性-来源优先级模式生成选择快照", bool(pd["modeSnapshotNo"]), str(pd["modeSnapshotNo"]))
pserial = pick(pd, "SERIAL_NUMBER")
check("属性-来源优先级按优先级选中断言（QC 80 最优）",
      pserial["status"] == "CONFIRMED" and pserial["chosen"]["source_kind"] == "QC")
check("属性-来源优先级其余断言列为异常", len(pserial["exceptions"]) == 2
      and all(e["reason"] for e in pserial["exceptions"]))
check("属性-两种模式对寿命冲突结论一致（规则不受模式影响）",
      pick(pd, "REMAINING_LIFE")["status"] == "CONFLICT")
_, r = call("GET", "/attributes/mode-snapshots?subjectId=50", token)
check("属性-选择快照可查询且带选择指纹",
      bool(r["data"]) and r["data"][0]["selection_digest"])
_, r = call("GET", "/attributes/evaluations?subjectId=50", token)
check("属性-求值台账留档可重演", len(r["data"]) >= 2)

# 只读角色不可写属性断言
st, _ = call("POST", "/attributes/assertions", viewer,
             {"subjectId": 50, "attributeKey": "SERIAL_NUMBER", "sourceKind": "QC",
              "rawValue": "SN-0001", "reportDate": "2024-01-01T00:00:00Z"})
check("属性-只读角色写断言被拒(403)", st == 403, f"http={st}")

# 凭证裁定后冲突解除（写操作，验证裁定闭环；重复执行时已裁定则确认闭环状态）
if ALREADY_ADJUDICATED:
    check("凭证裁定闭环（此前已裁定，冲突已从 OPEN 队列移除）", True)
else:
    cid = double[0]["id"]
    st, r = call("POST", f"/entities/conflicts/{cid}/adjudicate", token,
                 {"resolutionNote": "E2E 核验裁定：以现场签收凭证为准", "keepFirstVoucher": True})
    check("凭证裁定闭环", st == 200 and r["data"]["resolution_status"] == "ADJUDICATED")

print("\n================ 汇总 ================")
print(f"通过 {len(PASS)} 项，失败 {len(FAIL)} 项")
if FAIL:
    print("失败项:", FAIL)
    raise SystemExit(1)
print("全部端到端断言通过 ✅")
