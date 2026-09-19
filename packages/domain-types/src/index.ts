export interface Category {
  id: number
  parentId: number | null
  name: string
  level: number
}

export interface ProductImage {
  id: number
  url: string
  role: 'MAIN' | 'DETAIL'
  originalFilename: string
}

export interface ProductSku {
  id: number
  code: string
  name: string
  price: number
  currency: string
  stock: number
  status: 'ENABLED' | 'DISABLED'
}

export interface MerchantProduct {
  id: number
  version: number
  categoryId: number | null
  title: string
  subtitle: string | null
  draftStatus: 'EDITING' | 'READY_FOR_CONFIRMATION' | 'CONFIRMED'
  publishStatus: 'UNPUBLISHED' | 'PUBLISHED' | 'OFF_SHELF' | 'ARCHIVED'
  publishedAt: string | null
  images: ProductImage[]
  skus: ProductSku[]
}

export interface PublicProduct {
  id: number
  title: string
  subtitle: string | null
  categoryId: number
  priceFrom: number
  currency: string
  availableStock: number
  mainImageUrl: string | null
  publishedAt: string
}

export interface IngestionJob {
  id: number
  productId: number
  status: string
  originalFilename: string
  createdAt: string
  finishedAt: string | null
  steps: Array<{ type: string; status: string; attempts: number }>
}

