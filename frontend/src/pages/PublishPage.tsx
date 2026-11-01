import { useState, type FormEvent } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { ImagePlus, LoaderCircle, MapPin, Send, Trash2, UploadCloud } from 'lucide-react'
import { Link, useNavigate } from 'react-router-dom'
import { blogApi, discoveryApi, mediaUrl, uploadApi } from '../api'
import { useAuth } from '../auth'

export function PublishPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const [shopId, setShopId] = useState(1)
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [images, setImages] = useState<string[]>([])
  const [imageUrl, setImageUrl] = useState('')
  const [notice, setNotice] = useState('')
  const shops = useQuery({ queryKey: ['publish-shops'], queryFn: () => discoveryApi.shopsByName('') })
  const upload = useMutation({ mutationFn: uploadApi.image })
  const publish = useMutation({
    mutationFn: blogApi.create,
    onSuccess: (id) => { setNotice(`发布成功，笔记编号 ${id}`); window.setTimeout(() => navigate('/community'), 800) },
    onError: (error) => setNotice(error.message),
  })

  async function handleFiles(files: FileList | null) {
    if (!files) return
    const room = Math.max(0, 9 - images.length)
    for (const file of Array.from(files).slice(0, room)) {
      try {
        const path = await upload.mutateAsync(file)
        setImages((value) => [...value, path])
      } catch (error) {
        setNotice(error instanceof Error ? error.message : '图片上传失败')
      }
    }
  }

  async function removeImage(path: string) {
    try {
      if (path.startsWith('/blogs/')) await uploadApi.remove(path)
      setImages((value) => value.filter((item) => item !== path))
    } catch (error) {
      setNotice(error instanceof Error ? error.message : '删除失败')
    }
  }

  function addUrl() {
    if (!/^https?:\/\//.test(imageUrl) || images.length >= 9) return setNotice('请输入有效的 http(s) 图片地址，最多 9 张')
    setImages((value) => [...value, imageUrl])
    setImageUrl('')
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!title.trim() || !content.trim()) return setNotice('请填写标题和正文')
    publish.mutate({ shopId, title: title.trim(), content: content.trim(), images: images.join(','), liked: 0, comments: 0 })
  }

  if (!user) return <section className="page-section"><div className="empty large"><UploadCloud /><h2>登录后分享你的探店故事</h2><p>上传照片、关联商户，让更多人发现你的宝藏地点。</p><Link className="primary-button" to="/profile">现在登录</Link></div></section>

  return <section className="page-section publish-page">
    <div className="page-title"><div><p className="eyebrow">CREATE A NOTE</p><h1>把今天的好味道，<br />写进城市记忆。</h1></div><p>当前作者：<b>{user.nickName}</b><br />最多可上传 9 张图片</p></div>
    <form className="editor-layout" onSubmit={submit}>
      <div className="editor-main panel">
        <label className="field"><span>笔记标题</span><input value={title} onChange={(event) => setTitle(event.target.value)} maxLength={80} placeholder="一句话说说这家店最打动你的地方" /><small>{title.length}/80</small></label>
        <label className="field"><span>正文内容</span><textarea value={content} onChange={(event) => setContent(event.target.value)} maxLength={2000} rows={12} placeholder="推荐菜、环境、服务、交通……把真实体验分享给大家" /><small>{content.length}/2000</small></label>
        <div className="field"><span>探店照片</span><div className="image-grid">{images.map((path) => <div className="uploaded-image" key={path}><img src={mediaUrl(path)} alt="已上传" /><button type="button" onClick={() => void removeImage(path)} aria-label="删除图片"><Trash2 size={16} /></button></div>)}{images.length < 9 && <label className="upload-tile"><input type="file" accept="image/*" multiple onChange={(event) => void handleFiles(event.target.files)} /><ImagePlus /><span>{upload.isPending ? '上传中…' : '选择图片'}</span></label>}</div></div>
        <div className="url-row"><input value={imageUrl} onChange={(event) => setImageUrl(event.target.value)} placeholder="也可以粘贴图片 URL" /><button className="outline-button" type="button" onClick={addUrl}>添加</button></div>
      </div>
      <aside className="editor-side">
        <div className="panel"><p className="panel-label"><MapPin size={16} />关联商户</p><select value={shopId} onChange={(event) => setShopId(Number(event.target.value))}>{shops.data?.map((shop) => <option value={shop.id} key={shop.id}>#{shop.id} · {shop.name}</option>)}</select><label className="field small-field"><span>或直接输入商户 ID</span><input type="number" min="1" value={shopId} onChange={(event) => setShopId(Number(event.target.value))} /></label></div>
        <div className="publish-tip"><strong>真实，比完美更重要</strong><p>写清价格、时间和具体体验，能帮助更多人做决定。</p></div>
        {notice && <div className="notice info">{notice}</div>}
        <button className="primary-button full" type="submit" disabled={publish.isPending || upload.isPending}>{publish.isPending ? <LoaderCircle className="spin" /> : <Send />}发布笔记</button>
      </aside>
    </form>
  </section>
}
