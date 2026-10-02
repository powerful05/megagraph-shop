<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ImageOff } from 'lucide-vue-next'

const props = defineProps<{ src: string | null; alt: string; contain?: boolean; sizes?: string }>()
const failed = ref(false)
const responsive = computed(() => props.src !== null && /^\/api\/shop\/v1\/media\/\d+$/.test(props.src))
const imageSource = computed(() => responsive.value ? `${props.src}?width=640` : props.src)
const sourceSet = computed(() => responsive.value ? [320, 640, 960].map(width => `${props.src}?width=${width} ${width}w`).join(', ') : undefined)
watch(() => props.src, () => { failed.value = false })
</script>

<template>
  <div class="product-image" :class="{ 'is-contained': contain }">
    <img v-if="src && !failed" :src="imageSource ?? undefined" :srcset="sourceSet" :sizes="sizes ?? '(max-width: 600px) 100vw, (max-width: 1000px) 50vw, 640px'" :alt="alt" loading="lazy" decoding="async" @error="failed = true" />
    <div v-else class="product-image-fallback" role="img" :aria-label="`${alt}，${failed ? '图片暂不可用' : '暂无商品图'}`"><ImageOff :size="25" aria-hidden="true" /><span>{{ failed ? '图片暂不可用' : '暂无商品图' }}</span></div>
  </div>
</template>

<style scoped>
.product-image { display: block; width: 100%; aspect-ratio: 4 / 4.1; overflow: hidden; background: #f1f3f1; }
img { display: block; width: 100%; height: 100%; object-fit: cover; object-position: center 30%; }
.is-contained img { object-fit: contain; object-position: center; }
.product-image-fallback { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; width: 100%; height: 100%; color: #748179; font-size: 12px; }
</style>
