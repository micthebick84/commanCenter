<script setup lang="ts">
// Claude 사용량 · API 환산 비용 상세 (스펙 2026-11-03 §7). 열릴 때마다 date 없이 한 번 부르고, 이전·다음은 기간 시작일로
// date를 옮겨 다시 부른다. 차트는 CSS 막대(새 의존성 없음). 구독료 설정은 열 때만 마운트한다.
import { useQuasar } from 'quasar'
import UsageFeeSettings from '~/components/tasks/UsageFeeSettings.vue'
import { downloadAttachment } from '~/composables/attachmentDownload'
import { useFullscreenDialog } from '~/composables/dialogLayout'
import { formatAgo } from '~/composables/claudeUsage'
import {
  PERIOD_OPTIONS,
  apiErrorMessage,
  csvUrl,
  defaultFeeStart,
  formatRatio,
  formatStamp,
  formatTokens,
  formatUsd,
  isStale,
  periodLabel,
  shiftDate,
  summaryUrl,
  totalTokens,
  type UsagePeriodValue,
  type UsageSummary,
  type UsageTokens,
} from '~/composables/usageReport'

const props = defineProps<{ initialPeriod: 'day' | 'week' | 'month' }>()
const emit = defineEmits<{ (e: 'fee-saved'): void }>()
const show = defineModel<boolean>()

const $q = useQuasar()
const fullscreen = useFullscreenDialog()

const summary = ref<UsageSummary | null>(null)
const curPeriod = ref<UsagePeriodValue>(props.initialPeriod)
const curDate = ref<string | undefined>(undefined)
const loading = ref(false)
const feeOpen = ref(false)
const now = ref(Date.now())
let seq = 0

/** 성공했을 때만 보고 있는 기간·날짜를 바꾼다(실패하면 화면과 상태가 어긋나지 않게 이전 것을 둔다). */
async function load(period: UsagePeriodValue, date?: string) {
  const token = ++seq
  loading.value = true
  try {
    const res = await useApi<UsageSummary | null>(summaryUrl(period, date))
    if (token !== seq) return
    summary.value = res ?? null
    curPeriod.value = period
    curDate.value = date
    now.value = Date.now()
  } catch (e: unknown) {
    if (token !== seq) return
    $q.notify({ type: 'negative', message: apiErrorMessage(e, '사용량을 불러오지 못했습니다') })
  } finally {
    if (token === seq) loading.value = false
  }
}

// 열릴 때마다 date 없이 1회 조회. immediate: 이미 열린 채로 마운트되는 경우 커버(InterviewHistoryDialog와 같은 방식).
watch(
  show,
  (v) => {
    if (!v) return
    feeOpen.value = false
    summary.value = null
    curPeriod.value = props.initialPeriod
    curDate.value = undefined
    load(props.initialPeriod)
  },
  { immediate: true },
)

const startDate = computed(() => summary.value?.start?.slice(0, 10) ?? null)

function move(dir: -1 | 1) {
  if (!startDate.value) return
  load(curPeriod.value, shiftDate(curPeriod.value, startDate.value, dir))
}

// 구독료를 저장하면 지금 보는 기간·날짜로 요약을 다시 불러 배수를 갱신한 뒤 알린다.
async function onFeeSaved() {
  await load(curPeriod.value, curDate.value)
  emit('fee-saved')
}

async function downloadCsv() {
  const s = summary.value
  if (!s) return
  const d = s.start.slice(0, 10)
  try {
    await downloadAttachment(csvUrl(curPeriod.value, d), `claude-usage-${curPeriod.value}-${d}.csv`)
  } catch (e: unknown) {
    $q.notify({ type: 'negative', message: (e instanceof Error ? e.message : '') || '다운로드 실패' })
  }
}

const title = computed(() => (summary.value ? periodLabel(summary.value) : ''))
const ratioText = computed(() => {
  const s = summary.value
  if (!s) return '—'
  return formatRatio(s.ratio) ?? (!s.feeConfigured ? '구독료를 입력하세요' : s.feeMissing ? '구독료 적용 전 기간 포함' : '—')
})
const previousText = computed(() => {
  const p = summary.value?.previous
  if (!p) return ''
  const r = formatRatio(p.ratio)
  return `${summary.value?.inProgress ? '직전 같은 구간' : '직전 기간'} ${formatUsd(p.cost)}${r ? ` · ${r}` : ''}`
})

// 범주 비율은 비용 기준, 비용이 0이면 토큰 합 기준
const categories = computed(() => {
  const s = summary.value
  if (!s) return []
  const rows = ['SYMPHONY', 'DIRECT'].map((c) => {
    const row = (s.byCategory ?? []).find((x) => x.category === c)
    return { key: c, label: c === 'SYMPHONY' ? 'Symphony 작업' : '직접 사용', cost: row?.cost ?? 0, tokens: totalTokens(row?.tokens) }
  })
  const byCost = rows.reduce((a, r) => a + r.cost, 0) > 0
  const total = rows.reduce((a, r) => a + (byCost ? r.cost : r.tokens), 0)
  return rows.map((r) => ({ ...r, pct: total > 0 ? ((byCost ? r.cost : r.tokens) / total) * 100 : 0 }))
})

const seriesMax = computed(() => Math.max(0, ...(summary.value?.series ?? []).map((p) => p.cost ?? 0)))
function barHeight(cost: number | null): string {
  const c = cost ?? 0
  if (seriesMax.value <= 0 || c <= 0) return '0%'
  return `${Math.max(2, (c / seriesMax.value) * 100)}%`
}
function seriesLabel(at: string): string {
  return curPeriod.value === 'day' ? `${at.slice(11, 13)}시` : at.slice(5, 10)
}

function tok(t: UsageTokens | null | undefined, k: keyof UsageTokens): string {
  return formatTokens(t?.[k] ?? 0)
}
</script>

<template>
  <q-dialog v-model="show" :maximized="fullscreen">
    <q-card style="width: min(760px, 100vw)" data-test="cost-dialog">
      <q-toolbar class="cost-topbar">
        <q-toolbar-title class="text-subtitle1 text-weight-medium">Claude 사용량 · API 환산 비용</q-toolbar-title>
        <q-btn flat round dense icon="close" aria-label="닫기" data-test="cost-close" @click="show = false" />
      </q-toolbar>

      <div class="dialog-body">
        <q-card-section class="cost-nav">
          <q-btn flat round dense icon="chevron_left" aria-label="이전" :disable="!startDate" data-test="cost-prev" @click="move(-1)" />
          <span class="cost-period" data-test="cost-period-label">{{ title }}</span>
          <q-btn
            flat
            round
            dense
            icon="chevron_right"
            aria-label="다음"
            :disable="!startDate || !!summary?.inProgress"
            data-test="cost-next"
            @click="move(1)"
          />
          <q-space />
          <q-btn-toggle
            :model-value="curPeriod"
            :options="PERIOD_OPTIONS"
            outline
            dense
            no-caps
            toggle-color="primary"
            aria-label="기간 단위"
            data-test="cost-period"
            @update:model-value="(v: UsagePeriodValue) => load(v)"
          />
          <q-spinner v-if="loading" size="18px" color="primary" />
        </q-card-section>

        <q-card-section class="cost-big">
          <div class="big-item">
            <div class="big-label">토큰 합계</div>
            <div class="big-value" data-test="cost-tokens">{{ formatTokens(totalTokens(summary?.tokens)) }}</div>
          </div>
          <div class="big-item">
            <div class="big-label">API 환산 비용</div>
            <div class="big-value" data-test="cost-total">{{ formatUsd(summary?.cost) }}</div>
          </div>
          <div class="big-item">
            <div class="big-label">구독료 몫</div>
            <div class="big-value" data-test="cost-fee-share">{{ summary?.feeConfigured ? formatUsd(summary?.feeShare) : '—' }}</div>
            <div v-if="summary?.inProgress && summary?.feeConfigured" class="text-caption text-grey-7">
              기간 전체 구독료 {{ formatUsd(summary?.fullPeriodFeeShare) }}
            </div>
          </div>
          <div class="big-item">
            <div class="big-label">배수</div>
            <div class="big-value" :class="{ 'big-value--small': formatRatio(summary?.ratio) == null }" data-test="cost-ratio">
              {{ ratioText }}
            </div>
          </div>
          <div v-if="previousText" class="big-item big-item--wide">
            <div class="big-label">직전 기간 대비</div>
            <div data-test="cost-previous">{{ previousText }}</div>
          </div>
        </q-card-section>

        <q-card-section v-if="summary">
          <div class="section-title">Symphony · 직접 사용</div>
          <div class="cat-bar">
            <div
              v-for="c in categories"
              :key="c.key"
              class="cat-seg"
              :class="c.key === 'SYMPHONY' ? 'cat-seg--a' : 'cat-seg--b'"
              :style="{ width: `${c.pct}%` }"
              data-test="cost-category-bar"
            />
          </div>
          <div class="cat-legend">
            <span v-for="c in categories" :key="c.key">{{ c.label }} {{ Math.round(c.pct) }}%</span>
          </div>
        </q-card-section>

        <q-card-section v-if="summary">
          <div class="section-title">모델별</div>
          <div class="table-wrap">
            <table class="cost-table">
              <thead>
                <tr>
                  <th>모델</th>
                  <th>입력</th>
                  <th>출력</th>
                  <th>캐시 쓰기(5분)</th>
                  <th>캐시 쓰기(1시간)</th>
                  <th>캐시 읽기</th>
                  <th>비용</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="m in summary.byModel ?? []" :key="m.model" data-test="cost-model-row">
                  <td>{{ m.model }}</td>
                  <td>{{ tok(m.tokens, 'input') }}</td>
                  <td>{{ tok(m.tokens, 'output') }}</td>
                  <td>{{ tok(m.tokens, 'cacheWrite5m') }}</td>
                  <td>{{ tok(m.tokens, 'cacheWrite1h') }}</td>
                  <td>{{ tok(m.tokens, 'cacheRead') }}</td>
                  <td>{{ formatUsd(m.cost) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </q-card-section>

        <q-card-section v-if="summary?.coverage?.unpricedModels?.length" class="text-warning" data-test="cost-unpriced">
          가격 미등록 모델(비용에서 빠짐):
          <span v-for="u in summary.coverage.unpricedModels" :key="u.model" class="q-mr-sm">
            {{ u.model }} ({{ formatTokens(totalTokens(u.tokens)) }})
          </span>
        </q-card-section>

        <q-card-section v-if="summary">
          <div class="section-title">{{ curPeriod === 'day' ? '시간별' : '일별' }} 비용</div>
          <div class="series">
            <div
              v-for="p in summary.series ?? []"
              :key="p.at"
              class="series-col"
              :title="`${seriesLabel(p.at)} ${formatUsd(p.cost)}`"
            >
              <div class="series-bar" :style="{ height: barHeight(p.cost) }" data-test="cost-series-bar" />
            </div>
          </div>
          <div v-if="summary.series?.length" class="series-axis text-caption text-grey-7">
            <span>{{ seriesLabel(summary.series[0].at) }}</span>
            <span>{{ seriesLabel(summary.series[summary.series.length - 1].at) }}</span>
          </div>
        </q-card-section>

        <q-card-section v-if="summary" class="text-caption">
          <div class="section-title">수집 상태</div>
          <div
            v-for="c in summary.coverage?.collectors ?? []"
            :key="c.machine"
            :class="{ 'text-negative': isStale(c.lastReportedAt, now) }"
            data-test="cost-collector-row"
          >
            {{ c.machine }} · {{ formatAgo(c.lastReportedAt, now) }}<template v-if="c.unreadableLines > 0"> · 못 읽은 줄 {{ c.unreadableLines }}</template>
          </div>
          <div v-if="summary.coverage?.earliestEventAt" class="text-grey-7">
            가장 이른 기록 {{ formatStamp(summary.coverage.earliestEventAt) }}
          </div>
          <div class="text-grey-7 q-mt-xs">claude.ai 채팅 제외 · 기록은 Claude Code가 약 30일 보관한 것부터</div>
        </q-card-section>

        <q-card-section class="cost-actions">
          <q-btn flat no-caps icon="download" label="CSV 내려받기" :disable="!summary" data-test="cost-csv" @click="downloadCsv" />
          <q-btn flat no-caps icon="settings" label="구독료 설정" data-test="fee-toggle" @click="feeOpen = !feeOpen" />
        </q-card-section>

        <q-card-section v-if="feeOpen">
          <UsageFeeSettings :default-from="defaultFeeStart(summary)" @saved="onFeeSaved" />
        </q-card-section>
      </div>
    </q-card>
  </q-dialog>
</template>

<style scoped>
.cost-topbar {
  border-bottom: 1px solid rgba(0, 0, 0, 0.12);
  min-height: 52px;
  padding-top: env(safe-area-inset-top, 0px);
}
.cost-nav {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.cost-period {
  font-weight: 500;
}
.cost-big {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
}
.big-item--wide {
  grid-column: 1 / -1;
}
.big-label {
  font-size: 12px;
  color: #757575;
}
.big-value {
  font-size: 24px;
  font-weight: 600;
}
.big-value--small {
  font-size: 14px;
  font-weight: 500;
}
.section-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 6px;
}
.cat-bar {
  display: flex;
  height: 14px;
  border-radius: 7px;
  overflow: hidden;
  background: #eee;
}
.cat-seg--a {
  background: var(--q-primary, #1976d2);
}
.cat-seg--b {
  background: #9e9e9e;
}
.cat-legend {
  display: flex;
  gap: 16px;
  font-size: 12px;
  margin-top: 4px;
}
.table-wrap {
  overflow-x: auto;
}
.cost-table {
  border-collapse: collapse;
  font-size: 12px;
  white-space: nowrap;
}
.cost-table th,
.cost-table td {
  text-align: right;
  padding: 3px 10px 3px 0;
}
.cost-table th:first-child,
.cost-table td:first-child {
  text-align: left;
}
.series {
  display: flex;
  align-items: flex-end;
  gap: 2px;
  height: 90px;
}
.series-col {
  flex: 1 1 0;
  height: 100%;
  display: flex;
  align-items: flex-end;
}
.series-bar {
  width: 100%;
  background: var(--q-primary, #1976d2);
  border-radius: 2px 2px 0 0;
}
.series-axis {
  display: flex;
  justify-content: space-between;
}
.cost-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
</style>
