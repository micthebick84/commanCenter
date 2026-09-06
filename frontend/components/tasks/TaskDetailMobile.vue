<script setup lang="ts">
// 모바일 상세 본문 (스펙 2026-09-06 §4.2, 캔버스 Detail/DetailHistory): 아코디언 섹션. 현재 단계 섹션만 기본 펼침.
// 데이터 조회/액션은 부모([id].vue) 소유. 인터뷰 패널·디자인 카드·이력은 슬롯으로 받는다.
import MarkdownViewerDialog from '~/components/MarkdownViewerDialog.vue'
import { renderMarkdown } from '~/composables/useMarkdown'
import type { StageStep } from '~/composables/taskStages'

// [id].vue의 TaskResponse 중 이 컴포넌트가 읽는 부분집합
export interface DetailTask {
  id: number
  description: string
  status: string
  statusLabel: string
  repoAlias: string | null
  githubRepo: string
  githubBranch: string
  model: string
  effort: string
  failureReason: string | null
  retryCount: number
  maxRetry: number
  totalCostUsd: number | null
  totalTokens: number | null
  mcpsExtra: { name: string; transport: string }[]
  attachments: { id: number; fileName: string; sizeBytes: number }[]
  envVars: { key: string; value: string; secret: boolean }[]
  analysis: { markdownResult: string; subtasksJson: string; approved: boolean } | null
  design: { designMarkdown: string } | null
  implementation: { prUrl: string | null; prNumber: number | null; headBranch: string | null; headSha: string | null } | null
  deployment: { deployUrl: string | null; deployHostPort: number | null; deployImage: string | null; deployedAt: string | null; deployLog: string | null } | null
}

const props = defineProps<{ task: DetailTask; isAdmin: boolean; steps: StageStep[] }>()
const emit = defineEmits<{ (e: 'retry'): void; (e: 'download', attachmentId: number): void }>()

const currentKey = computed(() => props.steps.find((s) => s.state === 'current' || s.state === 'failed')?.key ?? 'analysis')
const open = reactive<Record<string, boolean>>({
  history: false, request: false,
  analysis: currentKey.value === 'analysis', design: currentKey.value === 'design',
  impl: currentKey.value === 'impl', deploy: currentKey.value === 'deploy', interviews: false, info: false,
})
// 헤더 aria-controls ↔ 콘텐츠 id 연결 — 인스턴스 두 개가 동시에 마운트돼도 충돌하지 않도록 task.id를 접두어로 둔다.
function contentId(key: string) {
  return `task-${props.task.id}-${key}-content`
}
const analysisPreview = computed(() => renderMarkdown(props.task.analysis?.markdownResult))
const viewer = reactive({ open: false, title: '', markdown: null as string | null })
function openViewer(title: string, markdown: string | null) {
  viewer.title = title
  viewer.markdown = markdown
  viewer.open = true
}
function fmtTokens(n: number) {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'k'
  return String(n)
}
function fmtSize(bytes: number) {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)}MB`
  if (bytes >= 1024) return `${Math.round(bytes / 1024)}KB`
  return `${bytes}B`
}
</script>

<template>
  <div class="detail-mobile">
    <!--
      Quasar q-expansion-item은 접힌 상태에서도 .q-expansion-item__content를 DOM에 always-mount하고
      v-show로만 숨긴다(quasar.client.js getTransitionChild — withDirectives(div, [[vShow, showing]])).
      "현재 단계만 펼침" 요구(다른 6개 섹션은 진짜 미마운트)를 만족시키려면 q-slide-transition + v-if로
      직접 여닫이를 구현해야 한다 — 헤더(q-item)는 그대로 재사용해 48px 탭 타깃·룩앤필은 유지한다.
    -->
    <q-list bordered class="rounded-borders bg-white sections">
      <div class="acc-section" data-test="section-history">
        <q-item clickable class="acc-header" role="button" :aria-expanded="open.history" :aria-controls="contentId('history')" @click="open.history = !open.history">
          <q-item-section><q-item-label>진행 이력</q-item-label></q-item-section>
          <q-item-section side><q-icon :name="open.history ? 'expand_less' : 'expand_more'" /></q-item-section>
        </q-item>
        <q-slide-transition>
          <div v-if="open.history" class="q-expansion-item__content" :id="contentId('history')">
            <slot name="history" />
          </div>
        </q-slide-transition>
      </div>

      <div class="acc-section" data-test="section-request">
        <q-item clickable class="acc-header" role="button" :aria-expanded="open.request" :aria-controls="contentId('request')" @click="open.request = !open.request">
          <q-item-section>
            <q-item-label>요청 상세</q-item-label>
            <q-item-label caption>{{ task.repoAlias ?? task.githubRepo }} · {{ task.githubBranch }}</q-item-label>
          </q-item-section>
          <q-item-section side><q-icon :name="open.request ? 'expand_less' : 'expand_more'" /></q-item-section>
        </q-item>
        <q-slide-transition>
          <div v-if="open.request" class="q-expansion-item__content" :id="contentId('request')">
            <q-card-section><div class="md-scroll"><pre class="req">{{ task.description }}</pre></div></q-card-section>
          </div>
        </q-slide-transition>
      </div>

      <div v-if="task.analysis" class="acc-section" data-test="section-analysis">
        <q-item clickable class="acc-header" role="button" :aria-expanded="open.analysis" :aria-controls="contentId('analysis')" @click="open.analysis = !open.analysis">
          <q-item-section>
            <q-item-label>분석 결과</q-item-label>
            <q-item-label caption>마크다운</q-item-label>
          </q-item-section>
          <q-item-section side><q-icon :name="open.analysis ? 'expand_less' : 'expand_more'" /></q-item-section>
        </q-item>
        <q-slide-transition>
          <div v-if="open.analysis" class="q-expansion-item__content" :id="contentId('analysis')">
            <q-card-section>
              <div class="md-preview"><div class="md-scroll markdown" v-html="analysisPreview" /><div class="fade" /></div>
              <q-btn outline color="primary" class="full-width q-mt-sm" label="전체 보기" data-test="analysis-open-viewer" @click="openViewer('분석 결과', task.analysis.markdownResult)" />
            </q-card-section>
          </div>
        </q-slide-transition>
      </div>

      <div v-if="task.design" class="acc-section" data-test="section-design">
        <q-item clickable class="acc-header" role="button" :aria-expanded="open.design" :aria-controls="contentId('design')" @click="open.design = !open.design">
          <q-item-section>
            <q-item-label>디자인 문서</q-item-label>
            <q-item-label caption>{{ task.status === 'DESIGN_REVIEW' ? '검토 대기' : '마크다운' }}</q-item-label>
          </q-item-section>
          <q-item-section side><q-icon :name="open.design ? 'expand_less' : 'expand_more'" /></q-item-section>
        </q-item>
        <q-slide-transition>
          <div v-if="open.design" class="q-expansion-item__content" :id="contentId('design')">
            <slot name="design">
              <q-card-section>
                <q-btn outline color="primary" class="full-width" label="전체 보기" @click="openViewer('디자인 문서', task.design.designMarkdown)" />
              </q-card-section>
            </slot>
          </div>
        </q-slide-transition>
      </div>

      <div class="acc-section" data-test="section-impl">
        <q-item clickable class="acc-header" role="button" :aria-expanded="open.impl" :aria-controls="contentId('impl')" @click="open.impl = !open.impl">
          <q-item-section>
            <q-item-label>구현 결과</q-item-label>
            <q-item-label caption>{{ task.implementation?.prNumber ? `PR #${task.implementation.prNumber}` : '아직 구현 전' }}</q-item-label>
          </q-item-section>
          <q-item-section side><q-icon :name="open.impl ? 'expand_less' : 'expand_more'" /></q-item-section>
        </q-item>
        <q-slide-transition>
          <div v-if="open.impl" class="q-expansion-item__content" :id="contentId('impl')">
            <q-card-section v-if="task.implementation" class="kv">
              <div v-if="task.implementation.prUrl"><span class="k">PR</span><a :href="task.implementation.prUrl" target="_blank" rel="noopener">#{{ task.implementation.prNumber }} 열기</a></div>
              <div v-if="task.implementation.headBranch"><span class="k">브랜치</span><code>{{ task.implementation.headBranch }}</code></div>
              <div v-if="task.implementation.headSha"><span class="k">커밋</span><code>{{ task.implementation.headSha.slice(0, 7) }}</code></div>
            </q-card-section>
            <q-card-section v-else class="text-grey-7">플랜 확정·승인 뒤 워커가 구현합니다.</q-card-section>
          </div>
        </q-slide-transition>
      </div>

      <div class="acc-section" data-test="section-deploy">
        <q-item clickable class="acc-header" role="button" :aria-expanded="open.deploy" :aria-controls="contentId('deploy')" @click="open.deploy = !open.deploy">
          <q-item-section>
            <q-item-label>배포</q-item-label>
            <q-item-label caption>{{ task.deployment?.deployUrl ?? '아직 배포 전' }}</q-item-label>
          </q-item-section>
          <q-item-section side><q-icon :name="open.deploy ? 'expand_less' : 'expand_more'" /></q-item-section>
        </q-item>
        <q-slide-transition>
          <div v-if="open.deploy" class="q-expansion-item__content" :id="contentId('deploy')">
            <q-card-section v-if="task.deployment?.deployUrl" class="kv">
              <div><span class="k">URL</span><a :href="task.deployment.deployUrl" target="_blank" rel="noopener">{{ task.deployment.deployUrl }}</a></div>
              <div><span class="k">포트</span><code>{{ task.deployment.deployHostPort }}</code></div>
              <div v-if="task.deployment.deployedAt"><span class="k">배포</span>{{ new Date(task.deployment.deployedAt).toLocaleString() }}</div>
            </q-card-section>
            <q-card-section v-else class="text-grey-7">PR생성 상태에서 관리자가 배포할 수 있습니다.</q-card-section>
          </div>
        </q-slide-transition>
      </div>

      <div class="acc-section" data-test="section-interviews">
        <q-item clickable class="acc-header" role="button" :aria-expanded="open.interviews" :aria-controls="contentId('interviews')" @click="open.interviews = !open.interviews">
          <q-item-section><q-item-label>지난 인터뷰</q-item-label></q-item-section>
          <q-item-section side><q-icon :name="open.interviews ? 'expand_less' : 'expand_more'" /></q-item-section>
        </q-item>
        <q-slide-transition>
          <div v-if="open.interviews" class="q-expansion-item__content" :id="contentId('interviews')">
            <slot name="interviews" />
          </div>
        </q-slide-transition>
      </div>

      <div class="acc-section" data-test="section-info">
        <q-item clickable class="acc-header" role="button" :aria-expanded="open.info" :aria-controls="contentId('info')" @click="open.info = !open.info">
          <q-item-section>
            <q-item-label>정보</q-item-label>
            <q-item-label caption>모델 · 사용량 · 첨부</q-item-label>
          </q-item-section>
          <q-item-section side><q-icon :name="open.info ? 'expand_less' : 'expand_more'" /></q-item-section>
        </q-item>
        <q-slide-transition>
          <div v-if="open.info" class="q-expansion-item__content" :id="contentId('info')">
            <q-card-section class="kv">
              <div><span class="k">모델</span>{{ task.model }} · {{ task.effort }}</div>
              <div v-if="task.totalCostUsd != null"><span class="k">사용량</span>{{ fmtTokens(task.totalTokens ?? 0) }} 토큰 · ${{ Number(task.totalCostUsd).toFixed(2) }}</div>
              <div v-if="task.mcpsExtra.length"><span class="k">MCP</span>{{ task.mcpsExtra.map((m) => m.name).join(', ') }}</div>
              <div v-if="task.attachments.length" class="column" style="gap: 4px">
                <span class="k">첨부</span>
                <q-chip v-for="a in task.attachments" :key="a.id" clickable dense icon="attach_file" :label="`${a.fileName} (${fmtSize(a.sizeBytes)})`" @click="emit('download', a.id)" />
              </div>
              <div v-if="task.failureReason" class="text-negative"><span class="k">실패 사유</span>{{ task.failureReason }}</div>
              <q-btn v-if="task.status === 'FAILED' || task.status === 'DESIGN_FAILED'" unelevated color="warning" icon="refresh" :label="task.status === 'DESIGN_FAILED' ? '재시도' : `재시도 (${task.retryCount}/${task.maxRetry})`" :disable="task.status !== 'DESIGN_FAILED' && task.retryCount >= task.maxRetry" @click="emit('retry')" />
            </q-card-section>
          </div>
        </q-slide-transition>
      </div>
    </q-list>
    <MarkdownViewerDialog v-model="viewer.open" :title="viewer.title" :markdown="viewer.markdown" />
  </div>
</template>

<style scoped>
.sections :deep(.q-item) { min-height: 48px; }
.acc-section:not(:last-child) { border-bottom: 1px solid rgba(0, 0, 0, 0.08); }
.req { white-space: pre-wrap; margin: 0; font-family: inherit; font-size: 14px; }
.md-preview { position: relative; max-height: 9.3em; overflow: hidden; }
.fade { position: absolute; left: 0; right: 0; bottom: 0; height: 3em; background: linear-gradient(to bottom, rgba(255, 255, 255, 0), #fff); }
.kv { display: flex; flex-direction: column; gap: 8px; font-size: 13.5px; }
.kv .k { display: inline-block; width: 64px; color: #757575; }
</style>
