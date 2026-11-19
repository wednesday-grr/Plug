# Plug

Plug は、オフライン音楽プレーヤー [Gramophone](https://github.com/FoedusProgramme/Gramophone)
のフォークです。DJ Automix、ビートマッチングとビート位相調整、オフライン BPM・キー DSP
解析、ハーモニックなキューのスマートソートを追加しています。Plug は GPLv3 以降で提供され、
Gramophone の原著作者および貢献者へのクレジットを保持しています。

[English](./readme.md)

## 機能

- 端末内の音楽をオフライン再生
- テンポとビート位相を合わせる DJ Automix と切り替えエフェクト
- BPM と楽曲キーのオフライン解析・ローカルキャッシュ
- テンポと楽曲キーに基づくハーモニックキューソート
- Material 3、ダイナミックカラー、Android 12 以降の Monet テーマアイコン
- 検索、プレイリスト、フォルダー表示、自然順ソート
- 同期歌詞（LRC、TTML、SRT）と単語・音節単位のカラオケ同期
- ReplayGain 2.0 とシステム／サードパーティ製イコライザー

Automix の解析結果は推定値であり、正確なビートグリッドを保証するものではありません。
埋め込みタグはキャッシュ済み解析より優先されます。安定した更新日時がないトラックや、
システムデコーダーが対応しないトラックは解析をスキップする場合があります。

## インストール

Plug の application ID は `org.plug.player` です。Gramophone とは別のアプリとして
インストールされるため、Gramophone のアプリ専用設定、キュー、解析キャッシュ、
権限は自動的に引き継がれません。

[GitHub の最新リリース](https://github.com/FoedusProgramme/Gramophone/releases/latest)
から Plug ビルドを入手してください。F-Droid と IzzyOnDroid の
`org.akanework.gramophone` は upstream Gramophone の掲載ページであり、Plug ではありません。

## ビルド

[Android Studio](https://developer.android.com/studio) をインストールし、サブモジュールを
含めてリポジトリを取得します。

```sh
git clone --recurse-submodules https://github.com/FoedusProgramme/Gramophone.git
cd Gramophone
git submodule update --init --recursive
```

リポジトリのルートに `package.properties` を作成し、次の内容を記述します。

```properties
releaseType=SelfBuilt
```

その後 `./gradlew assembleDebug`（Windows では `.\gradlew.bat assembleDebug`）を実行します。
application ID の既定値は `org.plug.player` です。

## ライセンスと upstream へのクレジット

Plug は GNU General Public License version 3 またはそれ以降のバージョンで提供されます。
Gramophone の著作権表示と原著作者へのクレジットを保持しています。
[LICENSE](./LICENSE) と
[FoedusProgramme/Gramophone](https://github.com/FoedusProgramme/Gramophone) を参照してください。
