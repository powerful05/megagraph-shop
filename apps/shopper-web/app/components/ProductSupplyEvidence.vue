<script setup lang="ts">
import type { PublicProduct } from '@megagraph/domain-types'
const props = defineProps<{ product: PublicProduct }>()
const evidence = computed(() => props.product.evidence)
const details = computed(() => ['MERCHANT_CONFIRMED', 'TEST_CONFIRMED'].includes(evidence.value.confirmationStatus)
  ? evidence.value.details : null)
const confirmedLabel = computed(() => ({ MISSING: '资料未补齐', UNCONFIRMED: '资料待确认',
  MERCHANT_CONFIRMED: '商家已确认', TEST_CONFIRMED: '测试资料已确认' })[evidence.value.confirmationStatus])
</script>

<template>
  <section class="supply-evidence" aria-label="商品资料与推荐依据">
    <header><strong>{{ evidence.merchantName }}</strong><span>{{ confirmedLabel }} · 版本 {{ evidence.productVersion }}</span></header>
    <p v-if="evidence.dataOrigin === 'LOCAL_TEST'" class="test-notice">本地测试商品。属性、价格、库存与尺码均为测试数据，图片为 AI 生成素材，不是真实商家供给。</p>
    <p v-if="!details">没有已确认的属性和尺码依据，不能据此判断面料、版型或合身程度。</p>
    <template v-else>
      <dl>
        <dt>颜色</dt><dd>{{ details.color }}</dd>
        <dt>面料</dt><dd>{{ details.fabric }}</dd>
        <dt>版型</dt><dd>{{ details.fit }}</dd>
        <dt>季节</dt><dd>{{ details.seasons.join('、') }}</dd>
        <dt>场景</dt><dd>{{ details.scenarios.join('、') }}</dd>
        <dt>图片来源</dt><dd>{{ details.imageSourceNote }}</dd>
      </dl>
      <h3>该商品尺码表 / cm</h3>
      <div class="size-table-scroll" tabindex="0" aria-label="尺码表">
        <table>
          <thead><tr><th>尺码</th><th>胸围</th><th>衣长</th><th>肩宽</th><th>袖长</th></tr></thead>
          <tbody><tr v-for="row in details.sizeChart" :key="row.skuCode"><th>{{ row.sizeLabel }}<small>{{ row.skuCode }}</small></th><td>{{ row.chestCm }}</td><td>{{ row.lengthCm }}</td><td>{{ row.shoulderCm ?? '未提供' }}</td><td>{{ row.sleeveCm ?? '未提供' }}</td></tr></tbody>
        </table>
      </div>
      <p>{{ details.measurementNote }}</p>
      <p>以上为成衣测量值；不同商品的同名尺码不等价，是否合身还需结合人体尺寸与活动余量。</p>
    </template>
    <h3>当前可售规格</h3>
    <ul><li v-for="sku in product.skus" :key="sku.id"><span>{{ sku.name }}</span><span>{{ new Intl.NumberFormat('zh-CN', { style: 'currency', currency: sku.currency }).format(sku.price) }} · 库存 {{ sku.availableStock }}</span></li></ul>
    <small v-if="evidence.confirmedAt">确认时间 {{ new Date(evidence.confirmedAt).toLocaleString('zh-CN') }}</small>
  </section>
</template>

<style scoped>
.supply-evidence { min-width: 0; color: #34423b; font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }
header { display: grid; gap: 3px; padding-block: 12px; border-bottom: 1px solid #dce4df; }
header span, small { color: #737c77; font-size: 11px; }
.test-notice { color: #854a31; background: #fff3ed; padding: 10px; border-left: 2px solid #c7764d; }
dl { display: grid; grid-template-columns: 60px minmax(0, 1fr); gap: 6px 10px; margin-block: 14px; }
dt { color: #69756e; } dd { margin: 0; }
h3 { margin: 16px 0 8px; font-size: 12px; }
.size-table-scroll { max-width: 100%; overflow-x: auto; }
table { width: 100%; border-collapse: collapse; text-align: left; }
th, td { padding: 7px 5px; border-bottom: 1px solid #e3e8e4; white-space: nowrap; font-weight: 400; }
thead th { background: #f3f6f4; } th small { display: block; }
ul { padding: 0; list-style: none; } li { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 6px; padding-block: 5px; }
</style>
