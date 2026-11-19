# Plug

Plug は、オフライン再生を中心とする Android 音楽プレーヤーで、
[Gramophone](https://github.com/FoedusProgramme/Gramophone) のフォークです。
リアルタイム DJ Automix、ビートマッチング、オフライン BPM・楽曲キー解析、
ハーモニックなキューのスマートソートを追加しています。

[English](./readme.md)

## 機能

- 端末内の音楽ライブラリ、検索、プレイリスト、フォルダー表示、自然順ソート
- Media3 による再生とメディアセッション連携。Android のデコーダーに加え、
  ALAC と MIDI のデコーダーを同梱
- 2 デッキの DJ Automix、テンポ同期、ビート位相調整、独立した音声レンダリング
- Smart Auto、Smooth Crossfade、Club Bass-Swap、Filter Sweep、BPM 同期 Echo Out、
  Vinyl Brake の切り替えエフェクト
- BPM、ビートグリッド、楽曲キーのオフライン解析と SQLite へのキャッシュ。
  埋め込み BPM・キー情報はキャッシュ済み解析より優先
- テンポと Camelot キーに基づくハーモニックキューソート
- 同期歌詞（LRC、TTML、SRT）、単語・音節単位のカラオケ同期、歌詞ウィジェット
- Spicy Lyrics によるオンライン歌詞検索（既定では無効。利用には自身の API キーが必要）。
  検索時に ISRC、または曲名・アーティスト名・再生時間がサービスへ送信されます
- ReplayGain 2.0、ダイナミックレンジ圧縮、システム／サードパーティ製イコライザー
- Material 3、ダイナミックカラー、Android 12 以降の Monet テーマアイコン、
  Android Auto 対応メディア連携

## 使い方

**Settings > Audio** で Automix を有効にします。ビートマッチングは既定で有効です。
同じ画面でトランジションの種類とクロスフェード時間を選択できます。Automix が有効なときは、
キューの **Sort for Automix** から現在の曲より後ろを並べ替えられます。
オンライン歌詞検索は歌詞設定で有効にし、自身の Spicy Lyrics API キーを入力してください。

Plug は Android アプリであり、公開サーバー API や独立したアプリ用 CLI は提供しません。
以下の Gradle コマンドはビルドとテスト用です。

Automix の解析結果は推定値であり、正確なビートグリッドを保証するものではありません。
安定したファイル情報がないトラックや、対応デコーダーで読み込めないトラックは
解析をスキップする場合があります。キューソートは現在の曲より後ろのトラックを並べ替え、
トランジション中は利用できません。

## インストール

Plug の application ID は `org.plug.player` です（debug ビルドは
`org.plug.player.debug`）。Gramophone とは別のアプリとしてインストールされるため、
Gramophone のアプリ専用設定、キュー、解析キャッシュ、権限は自動的に引き継がれません。

[GitHub の最新リリース](https://github.com/FoedusProgramme/Gramophone/releases/latest)
から Plug ビルドを入手してください。F-Droid と IzzyOnDroid の
`org.akanework.gramophone` は upstream Gramophone の掲載ページであり、Plug ではありません。

## ビルド

[Android Studio](https://developer.android.com/studio) と JDK 21、Android SDK Platform 37、
Android NDK、CMake 3.22.1 を用意し、サブモジュールを含めてリポジトリを取得します。

```sh
git clone --recurse-submodules https://github.com/FoedusProgramme/Gramophone.git
cd Gramophone
git submodule update --init --recursive
```

追跡対象の `package.properties` には必須設定 `releaseType=SelfBuilt` がすでにあります。
debug APK のビルドには次を実行します。

```sh
./gradlew :app:assembleDebug
```

Windows では `.\gradlew.bat :app:assembleDebug` を実行します。debug ビルドの application ID
には `.debug` が付きます。

JVM 単体テストは次のコマンドで実行します。

```sh
./gradlew test
```

Windows では `.\gradlew.bat test` を使用してください。アプリのテストは
`app/src/test` にあります。ベースラインプロファイルの生成には、設定済みの
エミュレーターまたは接続済み端末が必要です。

```sh
./gradlew :app:generateBaselineProfile
```

`.env` や Python の `requirements.txt` はありません。ビルド設定は Gradle プロパティと
`package.properties` で指定します。必要に応じて `appIdOverride` と
`versionNameSuffixOverride` も設定できます。リリース署名値はリポジトリ外の Gradle
ユーザープロパティに保存してください。通常の Release は
`AKANE_RELEASE_STORE_FILE`、`AKANE_RELEASE_STORE_PASSWORD`、`AKANE_RELEASE_KEY_ALIAS`、
`AKANE_RELEASE_KEY_PASSWORD` を使い、Google Play Release は対応する
`AKANE2_RELEASE_*` プロパティを使います。

Plug は主に端末内の音楽を再生し、音楽のストリーミングは行いません。上記の任意機能である
Spicy Lyrics のオンライン検索に Internet 権限を使用します。ローカル再生とライブラリ閲覧に
ネットワーク接続は必要ありません。

## プロジェクト構成

```text
.
├── app/                 Android アプリ、UI、再生、Automix、歌詞、テスト
├── baselineprofile/     Macrobenchmark とベースラインプロファイル生成
├── hificore/            C++ 音声出力・HiFi 連携
├── media3/              ビルドで使用する Media3 ソースサブモジュール
├── misc/
│   ├── alacdecoder/     同梱 ALAC デコーダー
│   └── audiofx*/        Audio Effect 転送と SDK スタブ
├── fastlane/            Android リリース自動化とストアメタデータ
├── .github/workflows/   CI ワークフロー
├── gradle/              Gradle Wrapper 設定
├── build.gradle.kts     ルートのプラグイン・ビルド設定
├── settings.gradle.kts  Gradle モジュールと Media3 の置換設定
└── package.properties   ローカルビルド識別設定（releaseType）
```

アプリの主なソースは `app/src/main/java` にあります。
`org.akanework.gramophone` は再生ロジック、Automix、解析、UI を、
`uk.akane.libphonograph` はメディアライブラリの読み込みとアイテム処理を含みます。
アプリのリソースは `app/src/main/res` にあります。

## 技術スタック

- Kotlin 2.3.0、Java 21
- Gradle 9.4.1 Wrapper、Android Gradle Plugin 9.2.1
- AndroidX Compose／Material 3（Compose BOM 2025.05.00）、AndroidX Media3
  （アプリの依存関係宣言は 1.10.1。ローカルのソースサブモジュールに置換）、
  Kotlin coroutines-guava 1.11.0、Coil 3.4.0
- CMake 3.22.1 でビルドする C++
- Android 最小 SDK 23、アプリの compile／target SDK 37
- Gradle 上の現在のアプリバージョン：1.1.2（version code 24）

アプリの Media3 依存関係は、チェックアウトした `media3` サブモジュールのプロジェクトに
置換されます。ビルド時は Gradle Wrapper と再帰的に初期化したサブモジュールを使用してください。

## ライセンスと upstream へのクレジット

Plug は GNU General Public License version 3 またはそれ以降のバージョンで提供されます。
Gramophone の著作権表示と原著作者へのクレジットを保持しています。
[LICENSE](./LICENSE) と
[FoedusProgramme/Gramophone](https://github.com/FoedusProgramme/Gramophone) を参照してください。
