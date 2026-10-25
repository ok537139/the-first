export interface ApiResult<T> {
  success: boolean
  errorMsg?: string
  data: T
  total?: number
}

export interface ShopType {
  id: number
  name: string
  icon: string
  sort: number
}

export interface Shop {
  id: number
  name: string
  typeId: number
  images: string
  area: string
  address: string
  x: number
  y: number
  avgPrice: number
  sold: number
  comments: number
  score: number
  openHours: string
  distance?: number
}

export interface User {
  id: number
  nickName: string
  icon?: string
}

export interface UserInfo {
  userId: number
  city?: string
  introduce?: string
  fans?: number
  followee?: number
  gender?: boolean
  birthday?: string
  credits?: number
  level?: boolean
}

export interface Blog {
  id: number
  shopId: number
  userId: number
  icon?: string
  name?: string
  isLike?: boolean
  title: string
  images: string
  content: string
  liked: number
  comments?: number
  createTime?: string
}

export interface ScrollResult<T> {
  list: T[]
  nextCursor?: string
  hasMore: boolean
}

/** SUCCESS 已创建订单；PROCESSING 订单处理中；NONE 没有资格（没抢过，或下单失败资格已退回） */
export interface SeckillResult {
  status: 'SUCCESS' | 'PROCESSING' | 'NONE'
  orderId?: string
}

export interface Voucher {
  id: number
  shopId: number
  title: string
  subTitle?: string
  rules?: string
  payValue: number
  actualValue: number
  type: number
  status: number
  stock?: number
  beginTime?: string
  endTime?: string
}

export interface ShopPayload extends Omit<Shop, 'id'> {
  id?: number
}

export interface VoucherPayload extends Omit<Voucher, 'id'> {
  id?: number
}

export interface BlogComment {
  id: number
  userId: number
  blogId: number
  parentId: number
  answerId: number
  content: string
  liked: number
  status: number
  createTime?: string
  userName?: string
  userIcon?: string
  answerName?: string
  isLike?: boolean
  replies?: BlogComment[]
}
