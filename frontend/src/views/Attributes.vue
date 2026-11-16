<template>
  <div>
    <el-card class="page-card">
      <template #header>
        <div class="card-head">
          <b>属性断言求值（编号 / 配对 / 剩余寿命 / 安装日期 / 规范号）</b>
          <el-tag size="small" type="info">按分析日期求值，不覆盖来源原文</el-tag>
        </div>
      </template>
      <el-form :inline="true">
        <el-form-item label="实物序列号">
          <el-select v-model="req.subjectId" placeholder="选择实物" style="width:260px" @change="loadAssertions">
            <el-option v-for="s in subjects" :key="s.id"
                       :label="`${s.entity_code}（${s.active_count}/${s.assertion_count} 条断言）`" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="分析日期">
          <el-date-picker v-model="req.analysisDate" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss[Z]"
                          placeholder="分析日期" style="width:220px" />
        </el-form-item>
        <el-form-item label="求值模式">
          <el-radio-group v-model="req.mode">
            <el-radio-button value="TEMPORAL">断言时态</el-radio-button>
            <el-radio-button value="SOURCE_PRIORITY">来源优先级</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" :disabled="!req.subjectId" @click="evaluate">求值</el-button>
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false"
        title="来源优先级模式为每个属性选一项当前断言、其余列为异常，读取快但需额外选择快照解释历史；断言时态模式在查询时按报告日期与方法适用性求值，历史重演完整。" />
    </el-card>

    <el-card class="page-card" v-if="result">
      <template #header>
        <div class="card-head">
          <b>求值结果 {{ result.subjectCode }} · {{ modeText(result.mode) }} · {{ fmtDate(result.analysisDate) }}</b>
          <div>
            <el-tag v-if="result.modeSnapshotNo" type="warning" size="small" style="margin-right:8px">
              选择快照 {{ result.modeSnapshotNo }}
            </el-tag>
            <el-tag v-for="(v, k) in result.summary" :key="k" :type="statusTag(k)" size="small"
                    style="margin-right:6px">{{ statusText(k) }} {{ v }}</el-tag>
          </div>
        </div>
      </template>
      <el-table :data="result.results" size="small" border row-key="attributeKey">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand">
              <h4>来源路径（原始编号 / 前缀 / 检测方法 / 报告日期 / 有效期，原文均保留）</h4>
              <el-table :data="row.sourcePath" size="small">
                <el-table-column prop="assertionNo" label="断言号" width="130" class-name="mono" />
                <el-table-column prop="sourceKind" label="来源" width="100" />
                <el-table-column prop="rawValue" label="原始编号/值" min-width="150" class-name="mono" />
                <el-table-column prop="normalizedValue" label="归一值" width="120" class-name="mono" />
                <el-table-column prop="manufacturerPrefix" label="制造商前缀" width="110" class-name="mono" />
                <el-table-column prop="inspectionMethod" label="检测方法" width="120" />
                <el-table-column label="寿命区间" width="130">
                  <template #default="s">
                    <span v-if="s.row.lifeLowerHours != null" class="mono">
                      {{ s.row.lifeLowerHours }}~{{ s.row.lifeUpperHours }}h
                    </span>
                  </template>
                </el-table-column>
                <el-table-column prop="reportDate" label="报告日期" width="160" />
                <el-table-column prop="validUntil" label="有效期至" width="160" />
                <el-table-column label="有效性" width="90">
                  <template #default="s">
                    <el-tag size="small" :type="{ VALID: 'success', EXPIRED: 'danger', REVOKED: 'info' }[s.row.validity]">
                      {{ { VALID: '有效', EXPIRED: '过期', REVOKED: '已撤销' }[s.row.validity] }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="sourceRef" label="来源引用" width="150" class-name="mono" />
              </el-table>

              <template v-if="row.attributeKey === 'PAIRING'">
                <h4>配对两侧证据（A/B 独立留痕，撤销一侧整对不可确认）</h4>
                <el-table :data="row.detail?.pairingEvidence || []" size="small">
                  <el-table-column prop="side" label="侧" width="60" />
                  <el-table-column prop="subject_code" label="实物" width="140" class-name="mono" />
                  <el-table-column prop="evidence_ref" label="证据引用" min-width="180" class-name="mono" />
                  <el-table-column prop="payload_digest" label="指纹" width="180" class-name="mono" />
                  <el-table-column label="状态" width="150">
                    <template #default="s">
                      <el-tag size="small" :type="s.row.revoked_at ? 'danger' : 'success'">
                        {{ s.row.revoked_at ? '已撤销：' + (s.row.revoke_reason || '') : '有效' }}
                      </el-tag>
                    </template>
                  </el-table-column>
                </el-table>
              </template>

              <template v-if="row.detail?.methodIntervals?.length">
                <h4>检测方法区间（不同方法且不相交时禁止平均）</h4>
                <el-table :data="row.detail.methodIntervals" size="small">
                  <el-table-column prop="method" label="检测方法" width="160" />
                  <el-table-column prop="assertionNo" label="断言号" width="140" class-name="mono" />
                  <el-table-column prop="lowerHours" label="下界(h)" width="110" />
                  <el-table-column prop="upperHours" label="上界(h)" width="110" />
                </el-table>
                <el-alert v-if="row.detail.combinedInterval" type="success" :closable="false" style="margin-top:8px"
                          :title="`多方法区间重叠，取交集 [${row.detail.combinedInterval.lowerHours}, ${row.detail.combinedInterval.upperHours}] 小时（非平均）`" />
                <el-alert v-if="row.status === 'CONFLICT'" type="error" :closable="false" style="margin-top:8px"
                          title="区间不相交：保持冲突，任何模式下都不做平均" />
              </template>

              <template v-if="row.evidenceGaps?.length">
                <h4>证据缺口（已传给依赖属性）</h4>
                <el-table :data="row.evidenceGaps" size="small">
                  <el-table-column prop="missingAttribute" label="缺口属性" width="160" />
                  <el-table-column prop="upstreamStatus" label="上游结论" width="120" />
                  <el-table-column prop="via" label="传播经" width="120" />
                  <el-table-column prop="rationale" label="说明" min-width="240" />
                </el-table>
              </template>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="属性" width="150">
          <template #default="{ row }">{{ attributeText(row.attributeKey) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="确认值 / 选中断言" width="220">
          <template #default="{ row }">
            <span v-if="row.chosen" class="mono">{{ row.chosen.raw_value }}
              <el-tag size="small" type="info" style="margin-left:4px">{{ row.chosen.assertion_no }}</el-tag>
            </span>
            <span v-else class="muted">无</span>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="结论说明" min-width="280" />
        <el-table-column label="异常/历史见证" width="120">
          <template #default="{ row }">{{ row.exceptions.length }} 条</template>
        </el-table-column>
        <el-table-column label="缺口" width="80">
          <template #default="{ row }">{{ row.evidenceGaps.length }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card class="page-card">
      <template #header>
        <div class="card-head">
          <b>断言台账（{{ assertions.length }} 条，追加式；含已撤销）</b>
          <el-checkbox v-model="includeRevoked" @change="loadAssertions">显示已撤销</el-checkbox>
        </div>
      </template>
      <el-table :data="assertions" size="small" stripe>
        <el-table-column prop="assertion_no" label="断言号" width="130" class-name="mono" />
        <el-table-column label="属性" width="120">
          <template #default="{ row }">{{ attributeText(row.attribute_key) }}</template>
        </el-table-column>
        <el-table-column prop="source_kind" label="来源" width="110" />
        <el-table-column prop="source_priority" label="优先级" width="80" />
        <el-table-column prop="raw_value" label="原始编号/值" min-width="150" class-name="mono" />
        <el-table-column prop="normalized_value" label="归一值" width="130" class-name="mono" />
        <el-table-column prop="inspection_method" label="检测方法" width="120" />
        <el-table-column prop="report_date" label="报告日期" width="160" />
        <el-table-column prop="valid_until" label="有效期至" width="160" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'REVOKED' ? 'info' : 'success'">
              {{ row.status === 'REVOKED' ? '已撤销(' + (row.revoked_side || 'ALL') + ')' : '有效' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button v-if="row.attribute_key === 'PAIRING' && row.status !== 'REVOKED'" size="small"
                       type="warning" :disabled="!auth.canWrite" @click="revokeSide(row)">撤销一侧</el-button>
            <el-button v-else-if="row.status !== 'REVOKED'" size="small" type="danger"
                       :disabled="!auth.canWrite" @click="revokeAll(row)">撤销</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card>
      <template #header>
        <div class="card-head">
          <b>求值台账与选择快照（历史选择可重演）</b>
          <el-button size="small" @click="loadHistory">刷新</el-button>
        </div>
      </template>
      <el-table :data="history" size="small" stripe>
        <el-table-column prop="evaluation_no" label="求值号" width="140" class-name="mono" />
        <el-table-column prop="subject_code" label="实物" width="130" class-name="mono" />
        <el-table-column label="属性" width="120">
          <template #default="{ row }">{{ attributeText(row.attribute_key) }}</template>
        </el-table-column>
        <el-table-column label="模式" width="120">
          <template #default="{ row }">{{ modeText(row.mode) }}</template>
        </el-table-column>
        <el-table-column prop="analysis_date" label="分析日期" width="170" />
        <el-table-column label="结论" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="chosen_assertion_no" label="选中断言" width="130" class-name="mono" />
        <el-table-column prop="mode_snapshot_no" label="选择快照" width="190" class-name="mono" />
        <el-table-column prop="created_at" label="求值时间" width="170" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const subjects = ref([]); const assertions = ref([]); const history = ref([])
const result = ref(null); const loading = ref(false); const includeRevoked = ref(true)
const req = reactive({ subjectId: 50, analysisDate: '2024-12-01T00:00:00Z', mode: 'TEMPORAL' })

const attributeText = (k) => ({
  SERIAL_NUMBER: '原始编号', CATALOG_NUMBER: '规范号', PAIRING: '配对',
  REMAINING_LIFE: '剩余寿命', INSTALL_DATE: '安装日期'
}[k] || k)
const modeText = (m) => ({ SOURCE_PRIORITY: '来源优先级', TEMPORAL: '断言时态' }[m] || m)
const statusText = (s) => ({ CONFIRMED: '确认', CONFLICT: '冲突', EXPIRED: '过期', UNKNOWN: '未知' }[s] || s)
const statusTag = (s) => ({ CONFIRMED: 'success', CONFLICT: 'danger', EXPIRED: 'warning', UNKNOWN: 'info' }[s] || 'info')
const fmtDate = (v) => (v ? String(v).replace('T', ' ').replace('Z', '') : '-')

async function loadAssertions() {
  if (!req.subjectId) return
  assertions.value = (await http.get('/attributes/assertions',
      { params: { subjectId: req.subjectId, includeRevoked: includeRevoked.value } })).data
}
async function evaluate() {
  loading.value = true
  try {
    result.value = (await http.post('/attributes/evaluate', { ...req })).data
    ElMessage.success(`求值完成：${JSON.stringify(result.value.summary)}`)
    loadHistory()
  } finally { loading.value = false }
}
async function revokeSide(row) {
  const { value } = await ElMessageBox.prompt('选择撤销侧并说明原因', '单向撤销配对证据', {
    inputValue: 'B',
    inputPlaceholder: 'A 或 B'
  })
  const side = String(value || '').trim().toUpperCase()
  const reason = await ElMessageBox.prompt('撤销原因', '撤销原因',
      { inputValue: '外协资料撤回对侧签收凭证，单向撤销' })
  await http.post(`/attributes/assertions/${row.id}/revoke`, { side, reason: reason.value })
  ElMessage.success(`已撤销 ${side} 侧证据，整对不可确认，对侧证据保留`)
  loadAssertions()
}
async function revokeAll(row) {
  const { value } = await ElMessageBox.prompt('撤销说明', '撤销断言',
      { inputValue: '来源撤回该断言，历史原文保留' })
  await http.post(`/attributes/assertions/${row.id}/revoke`, { reason: value })
  ElMessage.success('断言已撤销（历史仍保留）')
  loadAssertions()
}
async function loadHistory() {
  history.value = (await http.get('/attributes/evaluations')).data
}
onMounted(async () => {
  subjects.value = (await http.get('/attributes/subjects')).data
  await loadAssertions()
  await loadHistory()
})
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; }
.expand { padding: 6px 14px 12px; }
.expand h4 { margin: 12px 0 8px; font-size: 13px; color: #334155; }
.muted { color: #94a3b8; }
</style>
