<script setup lang="ts">
// 승인 — 인터뷰 시작 다이얼로그. 내용·상태·승인 호출은 ApproveForm이 가진다(처리함도 같은 폼을 펼쳐 쓴다) — 여기는 창 껍데기:
// 폰(lt.sm)은 전체화면이라 상단 고정 바(닫기)를 두고, 승인 요청 중에는 닫히지 않게 한다.
// 열 때마다 폼을 새로 마운트해(key=openSeq) 기본값·지난 인터뷰 프리필을 다시 한다.
import ApproveForm from '~/components/ApproveForm.vue'
import type { SummaryTask } from './tasks/ApproveTaskSummary.vue'
import { useFullscreenDialog } from '~/composables/dialogLayout'

defineProps<{ task: SummaryTask }>()
const show = defineModel<boolean>({ required: true })
const emit = defineEmits<{ (e: 'approved'): void }>()

const approving = ref(false)
const form = ref<{ approve: () => Promise<void> } | null>(null)
const openSeq = ref(0)
watch(
  show,
  (open) => {
    if (open) openSeq.value++
  },
  { immediate: true },
)

function onApproved() {
  show.value = false
  emit('approved')
}

// 최상위 함수로 둔다 — 부모 시험이 findComponent().vm.approve()로 부른다
function approve() {
  return form.value?.approve()
}
defineExpose({ approve })
// 폰에서만 전체화면 — 태블릿은 일반 너비 (composables/dialogLayout.ts)
const fullscreen = useFullscreenDialog()
</script>

<template>
  <!-- 승인 요청 중엔 ESC/바깥 클릭으로도 닫히지 않게(persistent) — 결과 토스트와 후속 스크롤을 놓치지 않도록 -->
  <q-dialog v-model="show" :maximized="fullscreen" :persistent="approving">
    <q-card class="approve-card" style="width: min(560px, 100vw)">
      <!-- 모바일: 상단 고정 바 — 전체화면이라 뒤 페이지가 안 보이므로 닫기와 제목을 항상 노출 -->
      <q-toolbar
        v-if="fullscreen"
        class="approve-topbar"
        data-test="approve-topbar"
      >
        <q-btn
          flat
          round
          dense
          icon="close"
          aria-label="닫기"
          data-test="approve-close"
          :disable="approving"
          @click="show = false"
        />
        <q-toolbar-title class="text-subtitle1 text-weight-medium"
          >인터뷰 시작 승인</q-toolbar-title
        >
      </q-toolbar>

      <ApproveForm
        :key="openSeq"
        ref="form"
        v-model:approving="approving"
        :task="task"
        @approved="onApproved"
        @cancel="show = false"
      />
    </q-card>
  </q-dialog>
</template>

<style scoped>
.approve-topbar {
  background: #fff;
  border-bottom: 1px solid rgba(0, 0, 0, 0.12);
  min-height: 52px;
  padding-top: env(safe-area-inset-top, 0px);
}
</style>
