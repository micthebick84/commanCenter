<script setup lang="ts">
import { useInterviewStream } from '~/composables/useInterviewStream'

const props = defineProps<{ sessionId: number }>()
const emit = defineEmits<{ (e: 'registered', taskId: number): void; (e: 'close'): void }>()

const stream = useInterviewStream()
const { connState, status, turns, designSections, plan, error } = stream

// Narrow-screen tab fallback ('chat' | 'design').
const activeTab = ref<'chat' | 'design'>('chat')

onMounted(() => stream.open(props.sessionId))
onUnmounted(() => stream.close())
</script>

<template>
  <div class="interview-panel column no-wrap">
    <!-- 좁은 화면 탭 전환 -->
    <q-tabs
      v-model="activeTab"
      class="lt-md text-primary interview-tabs"
      dense
      align="justify"
    >
      <q-tab name="chat" icon="forum" label="대화" />
      <q-tab name="design" icon="design_services" label="설계·플랜" />
    </q-tabs>

    <div class="interview-body row no-wrap">
      <!-- 좌: 대화 트랜스크립트 -->
      <section
        class="chat-col column no-wrap"
        :class="{ 'mobile-hidden': activeTab !== 'chat' }"
      >
        <div class="transcript col scroll q-pa-sm">
          <div
            v-for="t in turns"
            :key="t.seq"
            class="turn q-mb-sm"
            :class="`turn-${t.role}`"
          >
            <div class="turn-role text-caption text-grey-7">
              {{ t.role === 'assistant' ? 'AI' : t.role === 'user' ? '나' : '시스템' }}
            </div>
            <div class="turn-content" style="white-space: pre-wrap">{{ t.content }}</div>
          </div>
          <div v-if="turns.length === 0" class="text-grey-6 q-pa-md text-center">
            인터뷰를 시작합니다… 첫 질문을 준비 중입니다.
          </div>
        </div>
      </section>

      <q-separator vertical class="gt-sm" />

      <!-- 우: 설계 섹션 + 플랜 -->
      <section
        class="design-col column no-wrap"
        :class="{ 'mobile-hidden': activeTab !== 'design' }"
      >
        <div class="col scroll q-pa-sm">
          <div class="text-subtitle2 q-mb-sm">설계 섹션</div>
          <q-list bordered separator>
            <q-item v-for="d in designSections" :key="d.key">
              <q-item-section avatar>
                <q-icon
                  :name="d.approved ? 'check_circle' : 'radio_button_unchecked'"
                  :color="d.approved ? 'positive' : 'grey-5'"
                />
              </q-item-section>
              <q-item-section>
                <q-item-label>{{ d.title }}</q-item-label>
                <q-item-label caption style="white-space: pre-wrap">{{ d.body }}</q-item-label>
              </q-item-section>
            </q-item>
            <q-item v-if="designSections.length === 0">
              <q-item-section class="text-grey-6">아직 설계 섹션이 없습니다.</q-item-section>
            </q-item>
          </q-list>

          <div v-if="plan" class="q-mt-md">
            <div class="text-subtitle2 q-mb-sm">구현 플랜</div>
            <pre class="plan-md">{{ plan.planMarkdown }}</pre>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.interview-panel {
  height: 70vh;
  min-height: 420px;
}
.interview-body {
  flex: 1;
  min-height: 0;
}
.chat-col,
.design-col {
  flex: 1;
  min-width: 0;
  min-height: 0;
}
.transcript {
  min-height: 0;
}
.plan-md {
  white-space: pre-wrap;
  font-family: 'Pretendard', sans-serif;
  font-size: 0.85rem;
}
.turn-assistant {
  border-left: 3px solid var(--q-primary);
  padding-left: 8px;
}
.turn-user {
  border-left: 3px solid #bdbdbd;
  padding-left: 8px;
}
@media (max-width: 1023px) {
  .mobile-hidden {
    display: none;
  }
}
</style>
