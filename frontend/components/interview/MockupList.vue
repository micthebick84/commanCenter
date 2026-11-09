<script setup lang="ts">
// 오른쪽 칸 맨 위 "이 인터뷰의 목업" 목록(캔버스 ①). 미리보기 없이 행만 — 이름별 묶음, 이전 버전은 접는다.
import { groupMockups, mockupVersionTail, type MockupSummary } from '~/composables/interviewMockups'

const props = defineProps<{ sessionId: number; mockups: MockupSummary[] }>()

const groups = computed(() => groupMockups(props.mockups))
const openOlder = ref<Record<string, boolean>>({})

const rowText = (m: MockupSummary) => `${m.n} · ${m.title}${mockupVersionTail(m.version)}`
const href = (m: MockupSummary) => `/interviews/${props.sessionId}/mockups/${m.id}`
</script>

<template>
  <div class="mockup-list q-mb-md" data-test="mockup-list">
    <div class="text-subtitle2">이 인터뷰의 목업</div>
    <div class="text-caption text-grey-7 q-mb-sm">
      에이전트가 만든 목업이 순서대로 쌓입니다. 같은 이름의 새 버전이 나오면 최신 것이 위에 옵니다.
    </div>
    <q-list bordered separator>
      <template v-for="g in groups" :key="g.name">
        <q-item :data-test="`mockup-row-${g.latest.id}`">
          <q-item-section>
            <q-item-label>
              {{ rowText(g.latest) }}
              <q-chip
                v-if="g.latest.turnKind === 'design'"
                dense
                size="sm"
                color="blue-grey-1"
                text-color="blue-grey-9"
                :data-test="`mockup-plan-${g.latest.id}`"
              >계획</q-chip>
            </q-item-label>
            <q-item-label v-if="g.older.length" caption>
              <q-btn
                flat
                dense
                no-caps
                size="sm"
                color="primary"
                :label="`이전 버전 ${g.older.length}개`"
                :data-test="`mockup-older-${g.name}`"
                @click="openOlder[g.name] = !openOlder[g.name]"
              />
            </q-item-label>
          </q-item-section>
          <q-item-section side>
            <a :href="href(g.latest)" target="_blank" rel="noopener">보기</a>
          </q-item-section>
        </q-item>
        <template v-if="openOlder[g.name]">
          <q-item v-for="o in g.older" :key="o.id" class="mockup-older-row" :data-test="`mockup-row-${o.id}`">
            <q-item-section>
              <q-item-label>
                {{ rowText(o) }}
                <q-chip
                  v-if="o.turnKind === 'design'"
                  dense
                  size="sm"
                  color="blue-grey-1"
                  text-color="blue-grey-9"
                  :data-test="`mockup-plan-${o.id}`"
                >계획</q-chip>
              </q-item-label>
            </q-item-section>
            <q-item-section side>
              <a :href="href(o)" target="_blank" rel="noopener">보기</a>
            </q-item-section>
          </q-item>
        </template>
      </template>
    </q-list>
  </div>
</template>

<style scoped>
.mockup-older-row {
  padding-left: 28px;
  background: #fafbfd;
}
</style>
