<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { PhArrowLeft as ArrowLeft } from '@phosphor-icons/vue'
import { goSmartBack, resolveBackTarget, shouldShowBack } from '../navigation/smart-back'

const route = useRoute()
const router = useRouter()

const visible = computed(() => shouldShowBack(route))
const target = computed(() => resolveBackTarget(route))

function onBack() {
  goSmartBack(router, route)
}
</script>

<template>
  <button
    v-if="visible"
    type="button"
    class="page-back"
    :aria-label="target.label"
    :title="target.label"
    @click="onBack"
  >
    <ArrowLeft :size="18" weight="bold" aria-hidden="true" />
    <span>{{ target.label }}</span>
  </button>
</template>
