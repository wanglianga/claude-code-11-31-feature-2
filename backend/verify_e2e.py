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
check("附加 双重安装冲突 OPEN 待裁定", bool(double) and double[0]["resolution_status"] == "OPEN")

# 对外发布通道
_, r = call("GET", "/facts/public", token)
pub = r["data"]
check("对外通道仅 VERIFIED+PASS",
      all(f["status"] == "VERIFIED" and f["verdict"] == "PASS" for f in pub) and len(pub) >= 1)

# 凭证裁定后冲突解除（写操作，验证裁定闭环）
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
