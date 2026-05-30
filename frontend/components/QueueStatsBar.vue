<script setup lang="ts">
interface QueueStats {
  pending: number
  inProgress: number
  awaitingApproval: number
  approved: number
  implementing: number
  prCreated: number
  implementationFailed: number
  failed: number
  deployPending: number
  deploying: number
  deployed: number
  deployFailed: number
  avgDurationMs: number | null
}

const { data } = useTaskPolling<QueueStats>(() => useApi('/api/queue/stats'))

const avgDurationLabel = computed(() => {
  if (!data.value?.avgDurationMs) return '—'
  const sec = data.value.avgDurationMs / 1000
  if (sec < 60) return `${sec.toFixed(1)}초`
  return `${(sec / 60).toFixed(1)}분`
})
</script>

<template>
  <q-card v-if="data" flat bordered class="q-mb-md">
    <q-card-section class="row q-gutter-md items-center">
      <div class="stat">
        <div class="stat-label">작업대기</div>
        <div class="stat-value text-blue-9">{{ data.pending }}</div>
      </div>
      <q-separator vertical />
      <div class="stat">
        <div class="stat-label">분석중</div>
        <div class="stat-value text-orange-9">{{ data.inProgress }}</div>
      </div>
      <q-separator vertical />
      <div class="stat">
        <div class="stat-label">승인 대기</div>
        <div class="stat-value text-green-9">{{ data.awaitingApproval }}</div>
      </div>
      <q-separator vertical />
      <div class="stat">
        <div class="stat-label">구현대기</div>
        <div class="stat-value text-deep-purple-9">{{ data.approved }}</div>
      </div>
      <q-separator vertical />
      <div class="stat">
        <div class="stat-label">구현중</div>
        <div class="stat-value text-light-blue-9">{{ data.implementing }}</div>
      </div>
      <q-separator vertical />
      <div class="stat">
        <div class="stat-label">PR생성</div>
        <div class="stat-value text-teal-9">{{ data.prCreated }}</div>
      </div>
      <q-separator vertical />
      <div class="stat">
        <div class="stat-label">구현실패</div>
        <div class="stat-value text-pink-9">{{ data.implementationFailed }}</div>
      </div>
      <q-separator vertical />
      <div class="stat">
        <div class="stat-label">분석실패</div>
        <div class="stat-value text-red-9">{{ data.failed }}</div>
      </div>
      <q-separator vertical />
      <div class="stat">
        <div class="stat-label">배포대기</div>
        <div class="stat-value text-deep-purple-9">{{ data.deployPending }}</div>
      </div>
      <q-separator vertical />
      <div class="stat">
        <div class="stat-label">배포중</div>
        <div class="stat-value text-orange-9">{{ data.deploying }}</div>
      </div>
      <q-separator vertical />
      <div class="stat">
        <div class="stat-label">배포완료</div>
        <div class="stat-value text-teal-9">{{ data.deployed }}</div>
      </div>
      <q-separator vertical />
      <div class="stat">
        <div class="stat-label">배포실패</div>
        <div class="stat-value text-pink-9">{{ data.deployFailed }}</div>
      </div>
      <q-separator vertical />
      <div class="stat">
        <div class="stat-label">평균 소요</div>
        <div class="stat-value text-grey-8">{{ avgDurationLabel }}</div>
      </div>
    </q-card-section>
  </q-card>
</template>

<style scoped>
.stat {
  min-width: 78px;
  text-align: center;
}
.stat-label {
  font-size: 0.78rem;
  color: #757575;
  margin-bottom: 2px;
}
.stat-value {
  font-size: 1.4rem;
  font-weight: 600;
  line-height: 1;
}
</style>
