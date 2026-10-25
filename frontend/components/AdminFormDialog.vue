<script setup lang="ts">
// 관리 카탈로그(레포·MCP) 등록/수정 다이얼로그 셸. 데스크톱은 가운데 카드, 폰(lt.sm)은 ApproveDialog와 같은
// 전체화면 구성: 상단 고정 바(닫기+제목) / 스크롤 본문(.dialog-body, assets/css/main.css) / 하단 액션 바.
// 입력 필드는 기본 슬롯으로 받고, 슬롯에 `mobile`을 넘겨 필드 밀도(dense)·placeholder를 화면에 맞춘다.
import { useFullscreenDialog } from '~/composables/dialogLayout'

const props = defineProps<{
  modelValue: boolean
  title: string
  submitLabel: string
  submitting?: boolean
  canSubmit?: boolean
  submitColor?: string
  // 제출 버튼 data-test (기본 form-submit) — 같은 화면에 다이얼로그가 여럿이거나 호출부가 이름을 정해야 할 때
  submitTest?: string
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  submit: []
}>()

// 폰에서만 전체화면 — 태블릿은 일반 너비 (composables/dialogLayout.ts)
const mobile = useFullscreenDialog()
const show = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})
</script>

<template>
  <!-- persistent: 입력 중 바깥 탭/ESC로 날아가지 않게. 닫기는 취소·상단 X로만 -->
  <q-dialog v-model="show" persistent :maximized="mobile">
    <q-card
      class="admin-form-card"
      :class="{ 'admin-form-card--mobile': mobile }"
      style="width: min(560px, 100vw)"
    >
      <q-toolbar v-if="mobile" class="admin-form-topbar" data-test="form-topbar">
        <q-btn
          flat
          round
          dense
          icon="close"
          aria-label="닫기"
          data-test="form-close"
          :disable="submitting"
          @click="show = false"
        />
        <q-toolbar-title class="text-subtitle1 text-weight-medium">{{ title }}</q-toolbar-title>
      </q-toolbar>

      <div class="dialog-body">
        <q-card-section v-if="!mobile">
          <div class="text-h6">{{ title }}</div>
        </q-card-section>
        <q-card-section class="q-gutter-md" :class="{ 'q-pt-none': !mobile }">
          <slot :mobile="mobile" />
        </q-card-section>
      </div>

      <q-card-actions align="right" class="admin-form-actions">
        <q-btn flat label="취소" :disable="submitting" @click="show = false" />
        <q-btn
          unelevated
          :color="submitColor ?? 'primary'"
          :label="submitLabel"
          :loading="submitting"
          :disable="!canSubmit"
          :data-test="submitTest ?? 'form-submit'"
          @click="emit('submit')"
        />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>

<style scoped>
.admin-form-topbar {
  background: #fff;
  border-bottom: 1px solid rgba(0, 0, 0, 0.12);
  min-height: 52px;
  padding-top: env(safe-area-inset-top, 0px);
}
.admin-form-card--mobile .admin-form-actions {
  border-top: 1px solid rgba(0, 0, 0, 0.12);
  padding: 8px 12px calc(8px + env(safe-area-inset-bottom, 0px));
}
/* iOS Safari는 16px 미만 입력란에 포커스하면 화면을 확대한다 — 모바일에서는 입력 글자를 16px로 */
.admin-form-card--mobile :deep(.q-field__native) {
  font-size: 16px;
}
/* 긴 힌트(허용 형식 안내)가 한 줄로 잘리지 않고 줄바꿈되게 */
.admin-form-card--mobile :deep(.q-field__messages) {
  white-space: normal;
  line-height: 1.4;
}
</style>
