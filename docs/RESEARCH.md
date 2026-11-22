# Pixel 10 で「純正の画質のまま、フィルムの質感」を得るための技術調査

調査日: 2026-11-22 / 対象端末: Google Pixel 10（無印）/ 参考: Chromagraph Camera（Rian Johnson・Steve Yedlin, 2026-10）

## 1. 結論

**撮影は純正の Pixel カメラ（RAW + JPEG）に任せ、保存された DNG を自作の現像エンジンでフィルム現像する**のが、
「Pixel のレンズ・センサー・画像処理の性能を最大限に使いつつ、画質を落とさずにフィルムの質感を出す」唯一現実的な方法。

- Pixel の画質の核は、純正カメラアプリの中で動く **HDR+（複数フレームの位置合わせ・合成）**。サードパーティアプリからはこの合成済み RAW を得られない。
- 純正カメラが保存する DNG は **HDR+ で合成済みの「計算 RAW」**。ノイズとダイナミックレンジは単写の RAW より良い。しかもトーンマップや色づくりは適用前なので、フィルムのモデルを最初から当てられる。
- 既存のフィルム風アプリが劣化させる原因は、ほぼすべて「処理済みの 8bit 画像への後がけ」と「再圧縮」。DNG から浮動小数点で一度だけ現像すれば、これらを避けられる。

このリポジトリの FilmLab アプリは、この方針で実装している。

## 2. 純正カメラの画質はどこから来るか

| 要素 | 内容 | サードパーティから使えるか |
|---|---|---|
| センサー/レンズ | Pixel 10: 広角 48MP（Pixel 9 より小さいセンサー。ビニングした 12MP 出力のみ）、超広角 13MP、5 倍望遠 10.8MP | Camera2 で可。ただし単写 |
| HDR+ | アンダー露出の RAW を 2〜15 枚撮り、位置合わせして合成した「計算 RAW」を作る | **不可**（純正カメラ内部の処理） |
| Super Res Zoom / Night Sight など | 多フレームの超解像・夜景合成 | 一部の夜景モードを Camera Extensions 経由で使えるだけ（処理済み YUV/JPEG のみで RAW は出ない） |
| 色・トーン（Real Tone、ローカルトーンマップ） | 処理済み JPEG に適用 | 不可（必要ない：フィルムのモデルで置き換える） |

- Camera2 の `RAW_SENSOR` は 1 フレーム分のセンサー値なので、合成済みの純正 DNG よりノイズが多く、ダイナミックレンジも狭い。
- Camera Extensions（Pixel Camera Services）で使える Night Sight などは、出力が処理済み画像に限られる。そのため「フィルムを RAW から作る」用途には使えない。

→ **画質を最優先するなら、自前で撮影せず純正カメラの DNG を素材にするしかない**。
アプリからは「Pixel カメラで撮影」ボタンで純正カメラを起動し、戻ってきたら新しい DNG を自動で開く。設定すれば、撮影するだけで自動現像も走る。

## 3. 技術的な障壁と、このアプリでの対応

| # | 障壁 | 詳細 | 対応（実装箇所） |
|---|---|---|---|
| 1 | HDR+ は純正カメラ専用 | 上記のとおり | 純正カメラで RAW+JPEG 撮影 → その DNG を現像（`app/…/MainActivity.kt`） |
| 2 | Pixel 10 系の DNG はロスレス JPEG（LJ92）圧縮（Pixel 10 Pro での報告） | darktable / RawTherapee で埋め込みプレビューしか表示されない、Lightroom で JPEG 扱いになる、という報告がある | LJ92 デコーダを自前実装（1/2 成分・任意のタイル形状・予測子 1〜7・リスタート対応）。非圧縮版と完全一致することをテストで確認（`core/…/dng/LosslessJpeg.kt`） |
| 3 | レンズ補正が「命令」として DNG に入っている | 周辺減光・色シェーディングは OpcodeList2 の **GainMap**（ベイヤー各色ごとに 4 枚）、歪曲と倍率色収差は OpcodeList3 の **WarpRectilinear**。対応していない現像ソフトが多く、四隅が暗く色かぶりして歪みも残る | dng_sdk と同じ式で GainMap・WarpRectilinear・FixVignetteRadial・不良画素リストなどを実装（`dng/Opcodes.kt`, `raw/Warp.kt`） |
| 4 | 歪曲補正が画像の外を参照する場合がある | 端の画素が引き伸ばされて縞になる | 補正後に外を参照する縁を自動で切り詰める（`Developer.computeCrop`） |
| 5 | DNG は露出アンダーで、トーンも未調整 | HDR+ はハイライトを守るため暗めに合成する。そのまま現像すると暗い | 埋め込みプレビュー（HDR+ 処理済み）の輝度の中央値に合わせる自動露出。フィルムの特性曲線を逆算して露出を決める（`Developer.analyze`） |
| 6 | 既存アプリの劣化（処理済み画像への後がけ） | 処理済み 8bit 画像は、トーンも色もすでに圧縮・クリップされている。そこへの後がけはバンディングや破綻の原因になる | DNG（シーン線形）から 32bit 浮動小数点で一度だけ現像 |
| 7 | 既存アプリの劣化（解像度） | プレビューフレームや縮小画像から作るアプリが多い | センサーの全画素（DefaultCrop）をそのまま出力 |
| 8 | 既存アプリの劣化（再圧縮） | Android の `Bitmap.compress` は JPEG を 4:2:0 で圧縮する。色差が 1/4 になり、粒子や細い色エッジが潰れる | 色差を間引かない 4:4:4 の JPEG エンコーダを自前実装し、既定は品質 95 で 1 回だけ圧縮（`output/JpegEncoder.kt`） |
| 9 | 色域 | Pixel 純正は Display P3 で保存している | Display P3 で出力し、ICC v4 プロファイルを埋め込む（`output/IccProfile.kt`） |
| 10 | メタデータ | 再エンコードすると撮影情報や向きが失われる | DNG の機種・日時・露出・GPS・向きを EXIF にコピー。画素は回転しない（`output/ExifWriter.kt`） |
| 11 | スマホのメモリ | 50MP を float RGB で持つと約 600MB | DNG はメモリマップで読み、現像は 128 行ずつの帯で処理し、JPEG もストリーミングで書く。保持するのは CFA（12MP なら 48MB）だけ |
| 12 | ハイライトの色かぶり | センサーで一部のチャンネルだけが飽和すると、WB 後にマゼンタになる | 飽和に近い画素を WB 空間で白へ寄せる（`Developer.toWorking`） |
| 13 | 撮影 → 現像の手間 | 撮るたびにアプリで現像するのは面倒 | MediaStore の変更をトリガーにする JobScheduler で、新しい DNG を自動現像（`AutoDevelopJob.kt`） |
| 14 | 撮影中にルックを確認できない | 純正カメラを使うため、ファインダーにフィルムのルックは出せない | 撮影後にプレビューで確認する。Chromagraph もファインダーにはルックを出さない設計 |

## 4. フィルムの質感の作り方

Steve Yedlin の主張は「フィルムらしさの大半はフォーマットではなく、表示用の変換（display prep）で決まる」というもの
（“On Color Science for Filmmakers”）。Chromagraph Camera も RAW を入力にして、この変換を適用している。
ただし Yedlin の具体的な数式は非公開なので、このアプリでは**フィルムの仕組みを簡略化した物理モデル**として独自に組み立てた（`film/FilmRenderer.kt`）。

```
シーン線形 RGB（Rec.2020, 露出調整済み）
  → ハレーション（ベース面の反射で、明部の周りの主に赤い層が再露光）
  → 各感光層への露光（分光感度の重なり = 3x3）
  → ネガの特性曲線（トウ・直線部・ショルダー、層ごとにわずかに異なる → 色のクロスオーバー）
  → 層間抑制効果 DIR（彩度・色分離）
  → 隣接効果（現像のエッジ強調）
  → 粒子（濃度に依存、層ごとに大きさが違う、解像度非依存）
  → プリント（印画紙）の特性曲線 + プリンターライト（中間グレーで自動バランス）
  → 色素の副吸収 → 透過率 = 表示光
  → Display P3、輝度を保ったガマットマッピング、TPDF ディザで 8bit
```

- **粒子**は「35mm 判の 36mm に対する µm」で大きさを指定し、出力解像度に応じて画素単位へ換算する。
  縮小プレビューでもフル解像度でも見え方が揃う。画像の絶対座標からハッシュで作るので、帯処理の継ぎ目も出ない。
- **銘柄**（設計上の性格づけであり、実在フィルムの測定値ではない）:
  カラーネガ 400 ナチュラル / カラーネガ 200 ウォーム / シネマ 500T タングステン（3200K バランス、強いハレーション）/
  リバーサル 100 ビビッド / モノクロ 400 ハイコントラスト。
- RAW 段階では、輝度を保ったまま色比だけを平滑化する色ノイズ低減と、対数輝度のキャプチャーシャープを行う。
  解像感を損なわないことを優先している。

## 5. 検証したこと / していないこと

- 検証済み（PC 上の JVM で実施）
  - 合成 DNG（scikit-image の実写 + カラーチャート + 露光ウェッジ + 点光源）で、全処理を通して出力を目視確認
  - 合成 DNG の内容: ベイヤー 12bit、LJ92 1/2 成分タイルと非圧縮、GainMap、WarpRectilinear、ノイズ、埋め込みプレビュー、EXIF
  - 単体テスト 13 件（CI で毎回実行）: LJ92 版と非圧縮版の復号結果が完全一致、GainMap による周辺減光の補正、
    DNG の色変換（ForwardMatrix 経路と ColorMatrix のみの経路の両方で合成カメラの色を正確に再現）、
    WB シフトの向き、JPEG 4:4:4 の往復画質、ICC の妥当性、EXIF、フィルムの中間グレーの無彩色性と階調の単調性、
    粒子の分散と帯の継ぎ目
  - 出力 JPEG: 4:4:4 であること、ICC が LittleCMS で読めること、EXIF タグ（Pillow で確認）
  - 12MP のフル現像は約 4〜5 秒（PC 4 コア）。48MP の DNG も 512MB のヒープ内で処理できる（約 15 秒）
  - 銘柄ごとの見え方: [stocks.jpg](stocks.jpg)
- 未検証
  - **実機 Pixel 10 の DNG**（入手できなかった）。タグの使い方に想定外があっても調べられるよう、アプリに「DNG 情報」（圧縮形式・opcode・黒/白レベル・行列など）を表示している
  - 実機での処理時間と、自動現像ジョブの起動タイミング（Android の省電力制御の影響を受ける）

## 6. 今後の改善候補

- 実機の DNG に合わせた銘柄の色・トーンの調整、NoiseProfile タグを使ったノイズ低減の最適化
- デモザイクの精度向上（Menon の refining step、RCD など）
- GPU（Vulkan / OpenGL ES compute）による高速化
- 16bit TIFF / Ultra HDR（ゲインマップ）での出力

## 参考

- Chromagraph Camera: [CineD](https://www.cined.com/steve-yedlin-and-rian-johnson-release-chromagraph-camera-film-color-pipeline-for-iphone-stills-grain-halation-and-a-raw-gallery/), [IndieWire](https://www.indiewire.com/features/craft/steve-yedlin-interview-chromagraph-camera-app-rian-johnson-1235225234/), [PetaPixel](https://petapixel.com/2026/11/12/the-director-of-knives-out-has-just-released-a-13-camera-app/), [GSMArena](https://www.gsmarena.com/we_check_out_chromagraph_rian_johnsons_viral_iphone_camera_app-news-75357.php), [App Store](https://apps.apple.com/us/app/chromagraph-camera/id6803329435)
- Pixel 10 のカメラ: [Google Store 仕様](https://store.google.com/us/product/pixel_10_specs?hl=en-US), [DPReview: Pixel 10 series camera comparison](https://www.dpreview.com/articles/1124418567/google-pixel-10-series-camera-comparison/)
- HDR+: [Google Research: HDR+ with Bracketing on Pixel Phones](https://research.google/blog/hdr-with-bracketing-on-pixel-phones/), [HDR+ burst photography dataset](https://hdrplusdata.org/dataset.html)
- Camera Extensions: [Android: Camera extensions](https://source.android.com/docs/core/camera/camerax-vendor-extensions), [Android Central: Night Sight features for third-party apps](https://www.androidcentral.com/apps-software/google-pixel-night-sight-features-coming-to-third-party-apps)
- Pixel 10 の DNG を扱えない報告: [discuss.pixls.us: DNG from Pixel 10 Pro only shows JPEG thumbnail](https://discuss.pixls.us/t/dng-from-pixel-10-pro-only-shows-jpeg-thumbnail/59895), [Adobe: Pixel 10 raw appear tinted & blurred](https://community.adobe.com/t5/lightroom-ecosystem-cloud-based-bugs/p-pixel-10-raw-appear-tinted-amp-blurred/idi-p/15612893), [Adobe: Google Pixel 10 DNGs are treated as JPGs](https://community.adobe.com/t5/lightroom-classic-discussions/google-pixel-10-dng-s-are-treated-as-jpgs/m-p/15495750)
- DNG の opcode: [Adobe DNG Specification 1.6](https://paulbourke.net/dataformats/dng/dng_spec_1_6_0_0.pdf), [discuss.pixls.us: smartphone DNGs with embedded GainMap](https://discuss.pixls.us/t/looking-for-samples-smartphone-dngs-with-embedded-gainmap/27296?page=4), [darktable PR: embedded DNG lens corrections](https://github.com/darktable-org/darktable/pull/12880)
- フィルムの粒子: [Newson et al., Realistic Film Grain Rendering (IPOL 2017)](https://www.ipol.im/pub/art/2017/192/)
- デモザイク: D. Menon, S. Andriani, G. Calvagno, “Demosaicing With Directional Filtering and a posteriori Decision”, IEEE TIP 2007
