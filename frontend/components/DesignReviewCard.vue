<script setup lang="ts">
import { useQuasar } from 'quasar'

interface MockupFile {
  path: string
  title: string
  html: string
}

interface DesignView {
  designMarkdown: string
  mockupFilesJson: string
  designProjectId: string | null
  designUrl: string | null
  rejectCount: number
  feedbackHistoryJson: string
  approved: boolean
  approvedBy: string | null
  approvedAt: string | null
  completedAt: string
}

const props = defineProps<{
  taskId: string | number
  status: string
  design: DesignView
  isAdmin: boolean
}>()

const emit = defineEmits<{ (e: 'refresh'): void }>()

const $q = useQuasar()
const MAX_REJECTS = 3

const mockups = computed<MockupFile[]>(() => {
  try {
    return JSON.parse(props.design.mockupFilesJson) as MockupFile[]
  } catch {
    return []
  }
})

const feedbackHistory = computed<Array<{ feedback: string; rejectedBy: string; rejectedAt: string }>>(() => {
  try {
    return JSON.parse(props.design.feedbackHistoryJson)
  } catch {
    return []
  }
})

const selectedTab = ref(0)
const rejectDialog = ref(false)
const rejectFeedback = ref('')
const submitting = ref(false)

const rejectLimitReached = computed(() => props.design.rejectCount >= MAX_REJECTS)

async function approveDesign() {
  if (!confirm('이 디자인을 승인하시겠습니까?\n승인 즉시 이 디자인 기준으로 구현이 시작됩니다.')) return
  submitting.value = true
  try {
    await useApi(`/api/tasks/${props.taskId}/design/approve`, { method: 'POST' })
    $q.notify({ type: 'positive', message: '디자인 승인 — 구현 큐에 진입했습니다' })
    emit('refresh')
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '디자인 승인 실패' })
  } finally {
    submitting.value = false
  }
}

async function submitReject() {
  if (!rejectFeedback.value.trim()) return
  submitting.value = true
  try {
    await useApi(`/api/tasks/${props.taskId}/design/reject`, {
      method: 'POST',
      body: { feedback: rejectFeedback.value.trim() },
    })
    $q.notify({ type: 'warning', message: '디자인 반려 — 피드백 반영해 재생성합니다' })
    rejectDialog.value = false
    rejectFeedback.value = ''
    emit('refresh')
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '반려 실패' })
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <q-card flat bordered class="q-mb-md">
    <q-card-section class="row items-center q-gutter-sm">
      <div class="text-h6">디자인</div>
      <q-chip
        v-if="design.approved"
        color="positive"
        text-color="white"
        icon="check"
        label="승인됨"
        dense
      />
      <q-chip
        v-else-if="design.rejectCount > 0"
        color="warning"
        text-color="white"
        icon="replay"
        :label="`반려 ${design.rejectCount}/3`"
        dense
      />
      <q-space />
      <q-btn
        v-if="design.designUrl"
        flat
        dense
        icon="open_in_new"
        label="Claude Design에서 열기"
        type="a"
        :href="design.designUrl"
        target="_blank"
      />
      <q-chip v-else dense outline icon="cloud_off" label="업로드 실패 — 로컬 미리보기만" />
      <template v-if="isAdmin && status === 'DESIGN_REVIEW'">
        <q-btn
          unelevated
          color="positive"
          icon="check"
          label="디자인 승인"
          :loading="submitting"
          data-test="design-approve"
          @click="approveDesign"
        />
        <q-btn
          outline
          color="warning"
          icon="replay"
          label="반려"
          :disable="rejectLimitReached"
          data-test="design-reject"
          @click="rejectDialog = true"
        >
          <q-tooltip v-if="rejectLimitReached">
            반려 한도 도달 — 승인 또는 작업 삭제만 가능합니다
          </q-tooltip>
        </q-btn>
      </template>
    </q-card-section>

    <q-separator />

    <q-card-section v-if="mockups.length">
      <q-tabs v-model="selectedTab" dense align="left" class="q-mb-sm">
        <q-tab v-for="(m, i) in mockups" :key="m.path" :name="i" :label="m.title" />
      </q-tabs>
      <!-- sandbox: same-origin 차단으로 생성 HTML을 앱 컨텍스트에서 격리 -->
      <iframe
        :srcdoc="mockups[selectedTab]?.html"
        sandbox="allow-scripts"
        style="width: 100%; height: 600px; border: 1px solid #e0e0e0; border-radius: 4px; background: #fff"
        :title="mockups[selectedTab]?.title"
      />
    </q-card-section>

    <q-separator />

    <q-card-section>
      <pre style="white-space: pre-wrap; font-family: 'Pretendard', sans-serif">{{ design.designMarkdown }}</pre>
    </q-card-section>

    <template v-if="feedbackHistory.length">
      <q-separator />
      <q-card-section>
        <div class="text-subtitle2 q-mb-sm">반려 이력</div>
        <q-list dense bordered>
          <q-item v-for="(f, i) in feedbackHistory" :key="i">
            <q-item-section>
              <q-item-label>{{ f.feedback }}</q-item-label>
              <q-item-label caption>{{ f.rejectedBy }} · {{ f.rejectedAt }}</q-item-label>
            </q-item-section>
          </q-item>
        </q-list>
      </q-card-section>
    </template>

    <q-dialog v-model="rejectDialog" persistent>
      <q-card style="min-width: 480px">
        <q-card-section class="text-h6">디자인 반려</q-card-section>
        <q-card-section>
          <q-input
            v-model="rejectFeedback"
            type="textarea"
            outlined
            autofocus
            label="반려 피드백 (필수)"
            hint="워커가 이 피드백을 반영해 디자인을 수정합니다"
            :rules="[(v: string) => !!v?.trim() || '피드백을 입력하세요']"
          />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn v-close-popup flat label="취소" />
          <q-btn
            unelevated
            color="warning"
            label="반려"
            :disable="!rejectFeedback.trim()"
            :loading="submitting"
            @click="submitReject"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-card>
</template>
