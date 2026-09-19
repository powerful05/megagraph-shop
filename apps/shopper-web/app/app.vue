<script setup lang="ts">
import type { PublicProduct } from '@megagraph/domain-types'

const config = useRuntimeConfig()
const apiBase = config.public.apiBase
const { data: products, pending, error, refresh } = await useFetch<PublicProduct[]>(
  `${apiBase}/api/shop/v1/products`,
  { default: () => [] },
)

function imageUrl(path: string | null): string {
  return path ? `${apiBase}${path}` : ''
}

function formatPrice(price: number): string {
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: 'CNY',
    minimumFractionDigits: 0,
  }).format(price)
}
</script>

<template>
  <main>
    <nav class="nav-shell">
      <a class="brand" href="#top" aria-label="MegaGraph Shop 首页">
        <span class="brand-mark" />
        MEGAGRAPH
      </a>
      <div class="nav-links">
        <a href="#collection">新品</a>
        <a href="#principle">为什么适合你</a>
      </div>
    </nav>

    <section id="top" class="hero">
      <div class="hero-copy">
        <p class="eyebrow">AI-NATIVE PRODUCT DISCOVERY</p>
        <h1>商品不只被搜索。<br><em>它被理解。</em></h1>
        <p class="lede">从一张图片形成商品事实，再用语言、关系与场景重新组织探索。阶段 1 已接入真实的商品发布状态。</p>
        <a class="primary-link" href="#collection">查看已发布商品 <span>↘</span></a>
      </div>
      <div class="hero-object" aria-hidden="true">
        <div class="orb orb-a" />
        <div class="orb orb-b" />
        <div class="wireframe" />
        <span class="object-label">SEMANTIC OBJECT / 001</span>
      </div>
    </section>

    <section id="collection" class="collection-section">
      <header class="section-header">
        <div>
          <p class="eyebrow">LIVE CATALOG</p>
          <h2>当前可售</h2>
        </div>
        <button type="button" :disabled="pending" @click="refresh">{{ pending ? '同步中' : '刷新商品' }}</button>
      </header>

      <div v-if="error" class="state-panel">
        <strong>商品服务暂时不可用</strong>
        <p>请确认后端已在 8080 端口启动。</p>
      </div>
      <div v-else-if="pending" class="state-panel">正在验证商品发布状态与库存…</div>
      <div v-else-if="products.length === 0" class="state-panel empty">
        <span>0</span>
        <strong>还没有已发布商品</strong>
        <p>在商家工作台完成草稿确认和发布后，商品会出现在这里。</p>
      </div>
      <div v-else class="product-grid">
        <article v-for="(product, index) in products" :key="product.id" class="product-card">
          <div class="product-media">
            <img v-if="product.mainImageUrl" :src="imageUrl(product.mainImageUrl)" :alt="product.title" />
            <div v-else class="image-placeholder">NO IMAGE</div>
            <span class="index">{{ String(index + 1).padStart(2, '0') }}</span>
          </div>
          <div class="product-meta">
            <div>
              <h3>{{ product.title }}</h3>
              <p>{{ product.subtitle }}</p>
            </div>
            <div class="price-block">
              <strong>{{ formatPrice(product.priceFrom) }}</strong>
              <span>{{ product.availableStock }} 件可售</span>
            </div>
          </div>
        </article>
      </div>
    </section>

    <section id="principle" class="principle-section">
      <p class="eyebrow">SYSTEM PRINCIPLE</p>
      <p class="manifesto">AI 可以组织语言，<br>但价格、库存与上架状态<br><em>只相信商品事实。</em></p>
      <div class="principle-grid">
        <span>01 / IMAGE TO DRAFT</span>
        <span>02 / MERCHANT CONFIRMATION</span>
        <span>03 / VERIFIED AVAILABILITY</span>
      </div>
    </section>
  </main>
</template>

