最新版1.0.3-devは [RELEASE-1.0.3-dev.md](RELEASE-1.0.3-dev.md) を参照してください。以下は旧版1.0.0-devの記録です。

# GitHubでの配布と署名

配布先は [GitHub Releases](https://github.com/kgymk1-hub/TextEditor-Android/releases) です。`v1.0.0-dev` は実機受入前の検証版（Prerelease）です。アプリのversionCodeは1、versionNameは1.0.0-devです。

## 配布ファイル

- `PocketTextEditor-release.apk`: Android 8.0以上にインストールする署名済みAPK。
- `PocketTextEditor-release.aab`: 同じ鍵で署名した開発者向けAAB。直接インストールできません。
- `TextEditor-Android-source.zip`: 秘密鍵を含まないソース。
- `test-and-lint-reports.zip`: 自動テスト61件とLintの結果。
- `signing-certificate.pem`: 公開証明書。秘密鍵ではありません。
- `SHA256SUMS.txt`: ファイルのSHA-256一覧。

APKをダウンロードして開き、Androidの案内に従ってブラウザー／ファイルアプリからのインストールを許可します。デバッグ版とは署名が異なるため、そのまま上書き更新できません。デバッグ版を削除する前にアプリ内でバックアップを出力し、署名済み版にインポートしてください。

## 配布用署名鍵

所有者の指示に基づいて配布用のRSA 4096ビット鍵を新規作成しました。証明書の有効期間は10,000日です。証明書のSHA-256は次のとおりです。

```
9815e7dc833b80e700d3ac6d7e83f4fd094bf5d1393e9b87b17241085cecd07b
```

署名済みAPKはapksignerでv2・v3署名を検証し、AABはjarsignerで検証しています。Androidのアプリ署名では自己署名証明書を使用します。公開証明書は `docs/signing-certificate.pem` に保存しています。

秘密のJKSとパスワードはリポジトリ、Release、ソースZIPに含めません。作成環境ではGit管理対象外の `work/private-signing/` に、アクセス権を制限して保管しています。この環境が唯一の保管先のままにならないよう、所有者はJKSと資格情報の両方を安全な非公開の場所へバックアップしてください。

今後の更新では同じ鍵を使い、versionCodeを必ず増やします。鍵を失うと、同じ署名で既存のインストールへ更新を提供できなくなります。

## 再ビルド時の署名

次の環境変数を、秘密情報を保護できるローカル端末／CIに設定します。

| 環境変数 | 値 |
| --- | --- |
| `POCKET_SIGN_STORE` | JKSの絶対パス |
| `POCKET_SIGN_STORE_PASSWORD` | ストアのパスワード |
| `POCKET_SIGN_ALIAS` | `pocket-release` |
| `POCKET_SIGN_KEY_PASSWORD` | 鍵のパスワード |

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease :app:bundleRelease
```

鍵のパスだけ指定し、必要な秘密情報が足りない場合はビルドを停止します。変数を指定しなければ署名前の成果物を生成します。

既に検証した署名前のAPK／AABへ署名する場合は、JDK 17とSDK Build Tools 35.0.0を用意し、JAVA_HOME、ANDROID_HOME、上記の環境変数を設定して次を実行します。出力先は未作成のディレクトリを指定してください。

```sh
python scripts/sign-artifacts.py --apk /path/to/unsigned.apk --aab /path/to/unsigned.aab --output work/signed-release
```

このスクリプトはAPKのzipalign、APK／AABの署名・検証、公開証明書の出力を行います。パスワードをコマンド引数へ展開しません。

## GitHubへの公開

Release作成権限で検証版を公開し、上記の公開ファイルだけを添付します。鍵のバックアップや資格情報は添付しないでください。GitHub Actionsの署名自動化は未設定です。この環境の接続はActions Secrets APIへのアクセスで403となったため、Secretsへの鍵保存はできていません。

実機受入手順は [TESTING.md](TESTING.md) を参照してください。ストアへの公開は行いません。
